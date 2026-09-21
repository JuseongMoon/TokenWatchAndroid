package com.ScienceFiction.TokenWatchAndroid.analytics

import com.ScienceFiction.TokenWatchAndroid.auth.device.DeviceFlowException
import com.ScienceFiction.TokenWatchAndroid.auth.oauth.OAuthException
import com.ScienceFiction.TokenWatchAndroid.network.core.UsageException
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * The machine-readable reason a sign-in failed, mirroring the iOS `LoginFailureCode`.
 *
 * Both the `login_fail` analytics event and the diagnostic report ([LoginFailureReporter]) send
 * this value, and the server classifies on the same table — change a code here and the iOS
 * counterpart and the server table move with it.
 *
 * Every code matches `^[a-z0-9_]{1,40}$`. Raw response bodies, URLs, emails and tokens must never
 * reach it: a body is read only to *decide* a code, never to build one.
 *
 * | code | meaning |
 * |------|---------|
 * | http_<status> | an abnormal response that is none of the specific reasons below |
 * | invalid_grant | OAuth `invalid_grant` — the authorization code expired or was replayed |
 * | invalid_client | `invalid_client`/`unauthorized_client` — a blocked client id |
 * | redirect_mismatch | the redirect_uri was rejected |
 * | parse | 2xx that could not be decoded, or a missing token field — a schema change |
 * | denied / expired / timed_out | polling sign-in refused, code expired, approval timed out |
 * | network_offline / network_timeout / network_other | transport failures |
 * | state_mismatch / code_parse / keystore_save | in-app steps |
 * | other | anything unclassified |
 */
object LoginFailureCode {
    const val OTHER = "other"

    /** Classifies an exception caught anywhere in the sign-in flow. */
    fun from(error: Throwable): String = when (error) {
        is OAuthException.StateMismatch -> "state_mismatch"
        is OAuthException.ExchangeFailed -> classifyDetail(error.detail)
        is OAuthException.RefreshFailed -> classifyDetail(error.detail)
        is OAuthException.RefreshRevoked -> "invalid_grant"
        is OAuthException.CodeExpired -> "expired"
        is OAuthException.NotAuthenticated -> OTHER
        is DeviceFlowException.Expired -> "expired"
        is DeviceFlowException.Denied -> "denied"
        is DeviceFlowException.TimedOut -> "timed_out"
        is DeviceFlowException.Http -> classifyDetail(error.message.orEmpty())
        is UsageException.Unauthorized -> "http_401"
        is UsageException.RateLimited -> "http_429"
        is UsageException.Http -> http(error.statusCode)
        is UsageException.Decode, is UsageException.NoWindows -> "parse"
        is UnknownHostException -> "network_offline"
        is SocketTimeoutException -> "network_timeout"
        is IOException -> "network_other"
        else -> OTHER
    }

    fun http(status: Int): String = "http_${maxOf(status, 0)}"

    /**
     * An abnormal token/device endpoint response. The OAuth `error` value in the body names a
     * specific reason; without one the status alone is the code. The body itself is never sent.
     */
    fun http(status: Int, body: String): String {
        val error = jsonStringField(body, "error")
        val description = jsonStringField(body, "error_description")
        oauthError(error, description)?.let { return it }
        // Not JSON, or not a field we know: invalid_grant still reads the same anywhere in the body.
        if (body.contains("invalid_grant")) return "invalid_grant"
        return http(status)
    }

    /**
     * One string field out of an OAuth error body. A regex rather than a JSON parser on purpose:
     * `org.json` is a stub in JVM unit tests, and the only thing read here is a short enum-like
     * value that is matched against a fixed table — never copied into the code that is sent.
     */
    private fun jsonStringField(body: String, name: String): String =
        Regex("\"" + Regex.escape(name) + "\"\\s*:\\s*\"([^\"]*)\"")
            .find(body)
            ?.groupValues
            ?.get(1)
            .orEmpty()

    /** An OAuth (RFC 6749) or GitHub device-flow `error` value, or null when it is not specific. */
    fun oauthError(error: String, description: String = ""): String? = when {
        error == "invalid_grant" -> "invalid_grant"
        error == "invalid_client" || error == "unauthorized_client" ||
            error == "incorrect_client_credentials" -> "invalid_client"
        error == "redirect_uri_mismatch" -> "redirect_mismatch"
        error == "invalid_request" && description.lowercase().contains("redirect") -> "redirect_mismatch"
        else -> null
    }

    /**
     * The detail carried by an exchange/refresh/device failure. These messages are built by the
     * auth clients from the status line and body, so they are scanned for a specific reason and
     * otherwise reduced to the status code — the text itself never leaves the device.
     */
    private fun classifyDetail(detail: String): String {
        val lowered = detail.lowercase()
        oauthError(oauthTokenIn(lowered), lowered)?.let { return it }
        if (lowered.contains("redirect")) return "redirect_mismatch"
        Regex("\\b([45]\\d{2})\\b").find(lowered)?.let { return http(it.groupValues[1].toInt()) }
        if (lowered.contains("decode") || lowered.contains("parse")) return "parse"
        return OTHER
    }

    /** The first OAuth error keyword present in a message, for [classifyDetail]. */
    private fun oauthTokenIn(lowered: String): String = listOf(
        "invalid_grant", "invalid_client", "unauthorized_client",
        "incorrect_client_credentials", "redirect_uri_mismatch",
    ).firstOrNull { lowered.contains(it) }.orEmpty()

    /** Last line of defence before transmission: lowercase, `[a-z0-9_]` only, 40 characters. */
    fun sanitized(code: String): String {
        val cleaned = code.lowercase()
            .map { if (it in 'a'..'z' || it in '0'..'9' || it == '_') it else '_' }
            .joinToString("")
            .take(40)
        return cleaned.ifEmpty { OTHER }
    }
}
