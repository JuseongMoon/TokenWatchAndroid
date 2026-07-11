package com.ScienceFiction.TokenWatchAndroid.auth.session

import com.ScienceFiction.TokenWatchAndroid.auth.OAuthTokens
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types

data class BrowserCookie(
    val name: String,
    val value: String,
    val domain: String,
)

object CursorSessionAuth {
    const val loginUrl = "https://cursor.com/dashboard"
    const val cookieName = "WorkosCursorSessionToken"

    fun probe(cookies: List<BrowserCookie>): OAuthTokens? {
        val value = cookies.firstOrNull { it.name == cookieName }
            ?.value
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?: return null
        val normalized = value.replace("%3A%3A", "::")
        if (!normalized.contains("::")) return null
        val userId = normalized.substringBefore("::").takeIf(String::isNotEmpty)
        return OAuthTokens.session(value, accountId = userId)
    }
}

object GrokSessionAuth {
    const val loginUrl = "https://grok.com/"
    val infrastructureCookies = setOf("grok_device_id", "__cf_bm", "cf_clearance", "_cfuvid")

    fun probe(cookies: List<BrowserCookie>): OAuthTokens? {
        val grokCookies = cookies.filter {
            it.domain.contains("grok.com") && it.value.isNotEmpty()
        }
        if (grokCookies.none { it.name !in infrastructureCookies }) return null
        val cookieHeader = grokCookies.joinToString("; ") { "${it.name}=${it.value}" }
        return OAuthTokens.session(cookieHeader)
    }
}

object WindsurfSessionAuth {
    const val loginUrl = "https://windsurf.com/account"

    private val mapping = listOf(
        "x-devin-auth1-token" to "auth1-token",
        "x-devin-session-token" to "session-token",
        "x-devin-account-id" to "account-id",
        "x-devin-primary-org-id" to "primary-org-id",
        "x-devin-primary-org-id" to "org-id",
        "x-auth-token" to "auth-token",
    )
    private val mapType = Types.newParameterizedType(
        Map::class.java,
        String::class.java,
        String::class.java,
    )
    private val adapter = Moshi.Builder().build().adapter<Map<String, String>>(mapType)

    fun probe(localStorage: Map<String, String>): OAuthTokens? {
        val headers = linkedMapOf<String, String>()
        localStorage.forEach { (key, value) ->
            if (value.isEmpty()) return@forEach
            val lowercaseKey = key.lowercase()
            mapping.firstOrNull { (header, needle) ->
                header !in headers && lowercaseKey.contains(needle)
            }?.let { (header, _) -> headers[header] = value }
        }
        if (headers["x-auth-token"] == null && headers["x-devin-session-token"] == null) return null
        return OAuthTokens.session(adapter.toJson(headers))
    }

    fun unpackHeaders(tokens: OAuthTokens): Map<String, String>? =
        runCatching { adapter.fromJson(tokens.accessToken) }.getOrNull()
}
