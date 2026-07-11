package com.ScienceFiction.TokenWatchAndroid.auth

import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import java.util.Base64

/** Decodes display/header claims from an ID token. It does not authenticate the JWT. */
object Jwt {
    private val mapType = Types.newParameterizedType(
        Map::class.java,
        String::class.java,
        Any::class.java,
    )
    private val adapter = Moshi.Builder().build().adapter<Map<String, Any?>>(mapType)

    fun payload(token: String): Map<String, Any?>? {
        val segments = token.split('.')
        if (segments.size < 2) return null
        return runCatching {
            val json = String(Base64.getUrlDecoder().decode(segments[1]), Charsets.UTF_8)
            adapter.fromJson(json)
        }.getOrNull()
    }

    fun email(token: String): String? {
        val payload = payload(token) ?: return null
        return payload["email"].nonEmptyString()
            ?: payload.nested("https://api.openai.com/profile")?.get("email").nonEmptyString()
    }

    fun plan(token: String): String? {
        val payload = payload(token) ?: return null
        val raw = payload["chatgpt_plan_type"].nonEmptyString()
            ?: payload.nested("https://api.openai.com/auth")
                ?.get("chatgpt_plan_type")
                .nonEmptyString()
        return raw?.prettyPlan()
    }

    fun accountId(token: String): String? =
        payload(token)
            ?.nested("https://api.openai.com/auth")
            ?.get("chatgpt_account_id")
            .nonEmptyString()

    @Suppress("UNCHECKED_CAST")
    private fun Map<String, Any?>.nested(key: String): Map<String, Any?>? =
        this[key] as? Map<String, Any?>

    private fun Any?.nonEmptyString(): String? =
        (this as? String)?.trim()?.takeIf(String::isNotEmpty)

    private fun String.prettyPlan(): String =
        split('_', '-').filter(String::isNotEmpty).joinToString(" ") { part ->
            part.replaceFirstChar { it.uppercase() }
        }
}
