package com.ScienceFiction.TokenWatchAndroid.analytics

import com.ScienceFiction.TokenWatchAndroid.domain.AgentProvider
import java.util.concurrent.TimeUnit
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/**
 * Login failure diagnostics, mirroring the iOS `LoginFailureReporter`.
 *
 * A provider can change its sign-in policy — a blocked client id, a moved redirect, a new schema —
 * and the first sign we would otherwise get is a store review. So the same content as `login_fail`
 * goes once to the developer's endpoint, which raises a Telegram alert.
 *
 * What travels: platform, app version, build, provider, auth method, stage, code. Nothing else —
 * no device or account identifier, no email, no token, no raw error text. The server rejects any
 * extra key with a 400, so the payload and the contract move together.
 *
 * Fire and forget: failures are ignored and never retried. The gate (opt-out, demo, debug) is
 * decided by [AnalyticsService] before this is called.
 */
object LoginFailureReporter {
    const val ENDPOINT =
        "https://us-central1-footagemanager-41ad8.cloudfunctions.net/bot02LoginFailureReport"

    /** Exactly the seven keys the server accepts. */
    data class Payload(
        val platform: String,
        val appVersion: String,
        val build: String,
        val provider: String,
        val authKind: String,
        val stage: String,
        val code: String,
    ) {
        /**
         * Written by hand rather than through a JSON library: every value is already constrained
         * (wire ids, a sanitized code, version strings matched against the server's pattern), and
         * `org.json` is a stub under JVM unit tests, where the contract is asserted.
         */
        fun toJson(): String = buildString {
            append('{')
            listOf(
                "platform" to platform,
                "appVersion" to appVersion,
                "build" to build,
                "provider" to provider,
                "authKind" to authKind,
                "stage" to stage,
                "code" to code,
            ).forEachIndexed { index, (key, value) ->
                if (index > 0) append(',')
                append('"').append(key).append("\":\"").append(escape(value)).append('"')
            }
            append('}')
        }

        private fun escape(value: String): String = value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
    }

    /** One-shot requests unrelated to any credential, so they share nothing with the usage client. */
    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .callTimeout(5, TimeUnit.SECONDS)
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build()
    }

    fun report(
        provider: AgentProvider,
        stage: LoginStage,
        code: String,
        appVersion: String,
        build: String,
    ) {
        val payload = payload(provider, stage, code, appVersion, build) ?: return
        val request = Request.Builder()
            .url(ENDPOINT)
            .post(payload.toJson().toRequestBody(JSON))
            .build()
        runCatching {
            client.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: java.io.IOException) = Unit
                override fun onResponse(call: Call, response: Response) = response.close()
            })
        }
    }

    /**
     * The body to send, or null when the version strings do not match the server rule
     * (`^[0-9A-Za-z][0-9A-Za-z.\-]{0,19}$`) — a malformed report is worse than a missing one.
     */
    fun payload(
        provider: AgentProvider,
        stage: LoginStage,
        code: String,
        appVersion: String,
        build: String,
    ): Payload? {
        if (!isValidVersion(appVersion) || !isValidVersion(build)) return null
        return Payload(
            platform = "android",
            appVersion = appVersion,
            build = build,
            provider = provider.wireId,
            authKind = provider.analyticsAuthKind,
            stage = stage.wireId,
            code = LoginFailureCode.sanitized(code),
        )
    }

    fun isValidVersion(value: String): Boolean =
        value.length in 1..20 && VERSION_PATTERN.matches(value)

    private val VERSION_PATTERN = Regex("^[0-9A-Za-z][0-9A-Za-z.\\-]{0,19}$")
    private val JSON = "application/json".toMediaType()
}
