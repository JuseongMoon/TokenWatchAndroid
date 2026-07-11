package com.ScienceFiction.TokenWatchAndroid.auth.device

import com.ScienceFiction.TokenWatchAndroid.auth.OAuthTokens
import com.ScienceFiction.TokenWatchAndroid.auth.oauth.JsonMap
import com.ScienceFiction.TokenWatchAndroid.auth.oauth.number
import com.ScienceFiction.TokenWatchAndroid.auth.oauth.string
import com.ScienceFiction.TokenWatchAndroid.network.core.NetworkTransport
import java.time.Instant
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
    class Http(detail: String) : DeviceFlowException(detail)
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

    suspend fun pollForToken(device: DeviceCode): OAuthTokens {
        var interval = device.intervalSeconds.coerceAtLeast(1)
        val deadline = now().plusSeconds(device.expiresInSeconds.toLong())
        while (now().isBefore(deadline)) {
            sleeper.sleep(interval)
            val json = postForm(
                tokenUrl,
                FormBody.Builder()
                    .add("client_id", CLIENT_ID)
                    .add("device_code", device.deviceCode)
                    .add("grant_type", GRANT_TYPE)
                    .build(),
            )
            json.string("access_token")?.let { return OAuthTokens.session(it) }
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
        const val SCOPE = "read:user"
        const val GRANT_TYPE = "urn:ietf:params:oauth:grant-type:device_code"
    }
}
