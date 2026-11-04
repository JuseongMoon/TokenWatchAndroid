package com.ScienceFiction.TokenWatchAndroid.auth.device

import com.ScienceFiction.TokenWatchAndroid.auth.OAuthTokens
import com.ScienceFiction.TokenWatchAndroid.auth.oauth.JsonMap
import com.ScienceFiction.TokenWatchAndroid.auth.oauth.number
import com.ScienceFiction.TokenWatchAndroid.auth.oauth.string
import com.ScienceFiction.TokenWatchAndroid.network.core.NetworkTransport
import java.time.Instant
import com.ScienceFiction.TokenWatchAndroid.analytics.LoginFailureCode
import com.ScienceFiction.TokenWatchAndroid.auth.ForegroundSignal
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import okhttp3.FormBody
import okhttp3.Request

data class DeviceCode(
    val deviceCode: String,
    val userCode: String,
    val verificationUri: String,
    val intervalSeconds: Int,
    val expiresInSeconds: Int,
)

sealed class DeviceFlowException(message: String) : Exception(message) {
    class Expired : DeviceFlowException("The code expired. Please try again.")
    class Denied : DeviceFlowException("Authorization was denied.")

    /** A code-less polling login (Cursor) waited for approval until its attempts ran out. */
    class TimedOut : DeviceFlowException("Sign-in timed out. Please try again.")
    class Http(detail: String) : DeviceFlowException(detail)

    /**
     * The token arrived but the GitHub account could not be identified, so the sign-in is not
     * completed (it could not tell a re-login from a new account). [failureCode] is already a
     * `LoginFailureCode` table value: `http_<status>`, `parse`, or a `network_*` code.
     */
    class AccountLookupFailed(val failureCode: String, val isNetwork: Boolean = false) :
        DeviceFlowException("Couldn't confirm the GitHub account. Please try again.")
}

fun interface DeviceFlowSleeper {
    suspend fun sleep(seconds: Int)
}

class CopilotDeviceFlow(
    private val transport: NetworkTransport,
    private val now: () -> Instant = Instant::now,
    private val sleeper: DeviceFlowSleeper = DeviceFlowSleeper { seconds -> delay(seconds * 1_000L) },
    private val deviceCodeUrl: String = DEVICE_CODE_URL,
    private val tokenUrl: String = TOKEN_URL,
    private val userUrl: String = USER_URL,
) {
    suspend fun requestDeviceCode(): DeviceCode {
        val json = postForm(
            deviceCodeUrl,
            FormBody.Builder()
                .add("client_id", CLIENT_ID)
                .add("scope", SCOPE)
                .build(),
        )
        val deviceCode = json.string("device_code")
            ?: throw DeviceFlowException.Http(json.string("error_description") ?: "Login required")
        val userCode = json.string("user_code")
            ?: throw DeviceFlowException.Http(json.string("error_description") ?: "Login required")
        return DeviceCode(
            deviceCode = deviceCode,
            userCode = userCode,
            verificationUri = json.string("verification_uri") ?: "https://github.com/login/device",
            intervalSeconds = json.number("interval")?.toInt() ?: 5,
            expiresInSeconds = json.number("expires_in")?.toInt() ?: 900,
        )
    }

    /**
     * Polls until the code is approved. Only GitHub's `expired_token`/`access_denied` and the code's
     * own expiry end the wait: a network failure (typically the app being cut off while the
     * approval tab covers it) is retried on the next interval, and [signal] ends an interval early
     * when the app returns to the front.
     */
    suspend fun pollForToken(device: DeviceCode, signal: ForegroundSignal = ForegroundSignal()): OAuthTokens {
        var interval = device.intervalSeconds.coerceAtLeast(1)
        val deadline = now().plusSeconds(device.expiresInSeconds.toLong())
        while (now().isBefore(deadline)) {
            val waitSeconds = interval
            signal.pause { sleeper.sleep(waitSeconds) }
            val json = try {
                postForm(
                    tokenUrl,
                    FormBody.Builder()
                        .add("client_id", CLIENT_ID)
                        .add("device_code", device.deviceCode)
                        .add("grant_type", GRANT_TYPE)
                        .build(),
                )
            } catch (_: IOException) {
                continue
            }
            json.string("access_token")?.let { token ->
                return OAuthTokens.session(token, accountId = accountIdAfterApproval(token, signal))
            }
            when (val error = json.string("error")) {
                "authorization_pending", null -> Unit
                "slow_down" -> interval += 5
                "expired_token" -> throw DeviceFlowException.Expired()
                "access_denied" -> throw DeviceFlowException.Denied()
                else -> throw DeviceFlowException.Http(json.string("error_description") ?: error)
            }
        }
        throw DeviceFlowException.Expired()
    }

    /**
     * The GitHub account's numeric user id, the stable key that tells a re-login of the same
     * account apart from a second account. Logins and emails can change or be private, so neither
     * is used for matching. A sign-in without it is refused ([DeviceFlowException.AccountLookupFailed]).
     */
    suspend fun requireAccountId(token: String): String {
        val response = try {
            transport.execute(
                Request.Builder()
                    .url(userUrl)
                    .get()
                    .header("Authorization", "token $token")
                    .header("Accept", "application/vnd.github+json")
                    .header("User-Agent", USER_AGENT)
                    .build(),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: IOException) {
            throw DeviceFlowException.AccountLookupFailed(LoginFailureCode.from(error), isNetwork = true)
        }
        if (response.statusCode !in 200..299) {
            throw DeviceFlowException.AccountLookupFailed(LoginFailureCode.http(response.statusCode))
        }
        val id = JsonMap.decode(response.bodyText())?.number("id")?.toDouble()
        if (id == null || id <= 0.0 || id != Math.floor(id)) {
            throw DeviceFlowException.AccountLookupFailed("parse")
        }
        return id.toLong().toString()
    }

    /**
     * [requireAccountId] right after approval, when the app is often still behind the approval tab
     * and cut off from the network. A network failure in the background waits for the app to
     * return; in front it is retried [FOREGROUND_LOOKUP_RETRIES] times before the sign-in fails.
     * Other failures (an HTTP error, an unreadable answer) fail at once.
     */
    private suspend fun accountIdAfterApproval(token: String, signal: ForegroundSignal): String {
        var foregroundRetries = 0
        while (true) {
            try {
                return requireAccountId(token)
            } catch (failure: DeviceFlowException.AccountLookupFailed) {
                if (!failure.isNetwork) throw failure
                if (!signal.isForeground) {
                    signal.awaitForeground()
                    continue
                }
                if (foregroundRetries >= FOREGROUND_LOOKUP_RETRIES) throw failure
                foregroundRetries += 1
                sleeper.sleep(FOREGROUND_LOOKUP_RETRY_SECONDS)
            }
        }
    }

    /** [requireAccountId] for identifying a legacy card; null when it cannot be confirmed. */
    suspend fun accountId(token: String): String? = try {
        requireAccountId(token)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }

    private suspend fun postForm(url: String, body: FormBody): Map<String, Any?> {
        val response = transport.execute(
            Request.Builder()
                .url(url)
                .post(body)
                .header("Accept", "application/json")
                .build(),
        )
        if (response.statusCode !in 200..299) {
            throw DeviceFlowException.Http("HTTP ${response.statusCode}: ${response.bodyText()}")
        }
        return JsonMap.decode(response.bodyText())
            ?: throw DeviceFlowException.Http("Invalid JSON response")
    }

    companion object {
        const val CLIENT_ID = "Iv1.b507a08c87ecfe98"
        const val DEVICE_CODE_URL = "https://github.com/login/device/code"
        const val TOKEN_URL = "https://github.com/login/oauth/access_token"
        const val USER_URL = "https://api.github.com/user"
        const val SCOPE = "read:user"
        private const val USER_AGENT = "TokenWatch"
        private const val FOREGROUND_LOOKUP_RETRIES = 2
        private const val FOREGROUND_LOOKUP_RETRY_SECONDS = 2
        const val GRANT_TYPE = "urn:ietf:params:oauth:grant-type:device_code"
    }
}
