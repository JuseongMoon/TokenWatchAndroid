package com.ScienceFiction.TokenWatchAndroid.network.providers.subscription

import com.ScienceFiction.TokenWatchAndroid.auth.OAuthTokens
import com.ScienceFiction.TokenWatchAndroid.domain.UsageWindow
import com.ScienceFiction.TokenWatchAndroid.domain.WindowKind
import com.ScienceFiction.TokenWatchAndroid.network.core.HttpTransport
import com.ScienceFiction.TokenWatchAndroid.network.core.NetworkTransport
import com.ScienceFiction.TokenWatchAndroid.network.core.ProviderUsageClient
import java.time.LocalDate
import java.time.ZoneId
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request

/** GitHub Copilot quota client matching clean iOS commit 6df2689. */
class CopilotUsageClient(
    private val transport: NetworkTransport = HttpTransport(),
    private val endpoint: HttpUrl = DefaultEndpoint.toHttpUrl(),
) : ProviderUsageClient {
    override suspend fun fetch(tokens: OAuthTokens): List<UsageWindow> {
        val request = Request.Builder()
            .url(endpoint)
            .get()
            .header("Authorization", "token ${tokens.accessToken}")
            .header("Accept", "application/json")
            .header("Editor-Version", EditorVersion)
            .header("Editor-Plugin-Version", EditorPluginVersion)
            .header("User-Agent", UserAgent)
            .header("X-Github-Api-Version", GithubApiVersion)
            .build()
        val response = transport.executeUsage(request, forbiddenIsUnauthorized = true)
        return decodeSubscriptionUsage(response.body, ::mapWindows)
    }

    private fun mapWindows(root: Map<String, Any?>): List<UsageWindow> {
        root.optionalString("copilot_plan") // Validate the optional field like Decodable.
        val resetAt = parseResetDate(root.optionalString("quota_reset_date"))
        val rawSnapshots = root.optionalObject("quota_snapshots") ?: return emptyList()
        val snapshots = linkedMapOf<String, Snapshot>()
        for ((key, rawSnapshot) in rawSnapshots) {
            val value = rawSnapshot.asObject()
                ?: throw IllegalArgumentException("Copilot quota snapshot must be an object")
            snapshots[key] = Snapshot(
                entitlement = value.optionalDouble("entitlement"),
                remaining = value.optionalDouble("remaining"),
                percentRemaining = value.optionalDouble("percent_remaining"),
                unlimited = value.optionalBoolean("unlimited"),
            )
        }

        val output = mutableListOf<UsageWindow>()
        fun add(key: String) {
            val snapshot = snapshots[key] ?: return
            if (snapshot.unlimited == true) return
            val percentRemaining = when {
                snapshot.percentRemaining != null -> snapshot.percentRemaining
                snapshot.entitlement != null && snapshot.entitlement > 0.0 && snapshot.remaining != null ->
                    snapshot.remaining / snapshot.entitlement * 100.0
                else -> 100.0
            }
            val label = Labels[key] ?: key.capitalizedWords()
            output += UsageWindow(
                label = label,
                usedPercent = (100.0 - percentRemaining).coerceIn(0.0, 100.0),
                resetsAt = resetAt,
                kind = WindowKind.WEEKLY,
                windowSeconds = null,
            )
        }

        PreferredOrder.forEach(::add)
        snapshots.keys.sorted().filterNot(PreferredOrder::contains).forEach(::add)
        return output
    }

    private fun parseResetDate(value: String?): java.time.Instant? = value?.let { date ->
        runCatching { LocalDate.parse(date).atStartOfDay(ZoneId.systemDefault()).toInstant() }.getOrNull()
    }

    private fun Map<String, Any?>.optionalObject(key: String): Map<String, Any?>? {
        if (!containsKey(key) || this[key] == null) return null
        return this[key].asObject()
            ?: throw IllegalArgumentException("$key must be an object")
    }

    private fun Map<String, Any?>.optionalString(key: String): String? {
        if (!containsKey(key) || this[key] == null) return null
        return this[key] as? String
            ?: throw IllegalArgumentException("$key must be a string")
    }

    private fun Map<String, Any?>.optionalDouble(key: String): Double? {
        if (!containsKey(key) || this[key] == null) return null
        return this[key].asNumber()
            ?: throw IllegalArgumentException("$key must be a number")
    }

    private fun Map<String, Any?>.optionalBoolean(key: String): Boolean? {
        if (!containsKey(key) || this[key] == null) return null
        return this[key] as? Boolean
            ?: throw IllegalArgumentException("$key must be a boolean")
    }

    private data class Snapshot(
        val entitlement: Double?,
        val remaining: Double?,
        val percentRemaining: Double?,
        val unlimited: Boolean?,
    )

    companion object {
        const val DefaultEndpoint = "https://api.github.com/copilot_internal/user"
        const val EditorVersion = "vscode/1.96.2"
        const val EditorPluginVersion = "copilot-chat/0.26.7"
        const val UserAgent = "GitHubCopilotChat/0.26.7"
        const val GithubApiVersion = "2025-04-01"

        private val PreferredOrder = listOf("premium_interactions", "chat", "completions")
        private val Labels = mapOf(
            "premium_interactions" to "Premium requests",
            "chat" to "Chat",
            "completions" to "Completions",
        )
    }
}
