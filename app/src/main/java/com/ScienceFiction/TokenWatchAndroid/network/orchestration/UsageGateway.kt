package com.ScienceFiction.TokenWatchAndroid.network.orchestration

import com.ScienceFiction.TokenWatchAndroid.analytics.FetchErrorReason
import com.ScienceFiction.TokenWatchAndroid.analytics.toFetchErrorReason
import com.ScienceFiction.TokenWatchAndroid.auth.CredentialUnavailableException
import com.ScienceFiction.TokenWatchAndroid.auth.TokenStore
import com.ScienceFiction.TokenWatchAndroid.auth.oauth.OAuthException
import com.ScienceFiction.TokenWatchAndroid.domain.AgentProvider
import com.ScienceFiction.TokenWatchAndroid.domain.AgentSnapshot
import com.ScienceFiction.TokenWatchAndroid.localization.AppLanguage
import com.ScienceFiction.TokenWatchAndroid.localization.L10n
import com.ScienceFiction.TokenWatchAndroid.network.core.UsageException
import com.ScienceFiction.TokenWatchAndroid.network.providers.subscription.CodexAccountClient
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlin.math.max

/**
 * Token validation, one-shot 401 refresh, 429 gating, and last-good snapshot orchestration.
 *
 * A failed fetch comes back as a snapshot with [AgentSnapshot.errorReason] set. Nothing is logged
 * here: the store records the outcome only once it has accepted the result (D021), so a result it
 * discards (the account was removed, or signed in again meanwhile) is never counted.
 */
class UsageGateway(
    private val registry: ProviderUsageRegistry,
    private val tokenStore: TokenStore,
    private val rateLimitGate: RateLimitGate,
    private val codexAccountClient: CodexAccountClient = CodexAccountClient(),
    private val localization: () -> L10n = { L10n(AppLanguage.SYSTEM.resolved()) },
    private val now: () -> Instant = Instant::now,
) {
    suspend fun fetchSnapshot(
        provider: AgentProvider,
        agentId: UUID,
        manual: Boolean = false,
    ): AgentSnapshot {
        rateLimitGate.blocked(agentId)?.let { until ->
            return rateLimitedSnapshot(agentId, until)
        }

        return try {
            var tokens = tokenStore.validTokens(agentId, provider)
            val usage = try {
                registry.fetchUsage(provider, tokens)
            } catch (_: UsageException.Unauthorized) {
                tokens = tokenStore.forceRefresh(agentId, provider)
                registry.fetchUsage(provider, tokens)
            }
            val windows = usage.windows
            if (windows.isEmpty()) throw UsageException.NoWindows()

            // A plan label that rides on the usage response (Grok, Cursor) is stored with the
            // credential on every fetch, manual or automatic; no extra request is involved.
            usage.plan?.takeIf { it != tokens.plan }?.let { livePlan ->
                tokens = tokens.copy(plan = livePlan)
                tokenStore.updatePlan(agentId, livePlan)
            }

            if (provider == AgentProvider.CODEX && manual) {
                runCatching { codexAccountClient.fetchPlan(tokens) }
                    .getOrNull()
                    ?.takeIf { it != tokens.plan }
                    ?.let { livePlan ->
                        tokens = tokens.copy(plan = livePlan)
                        tokenStore.updatePlan(agentId, livePlan)
                    }
            }

            AgentSnapshot(
                windows = windows,
                planLabel = tokens.plan,
                fetchedAt = now(),
                error = null,
            ).also { snapshot ->
                rateLimitGate.recordSuccess(agentId, snapshot)
            }
        } catch (error: UsageException.RateLimited) {
            rateLimitGate.recordRateLimit(agentId, error.retryAfter)
            val until = rateLimitGate.blocked(agentId) ?: now().plusSeconds(300)
            rateLimitedSnapshot(agentId, until)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            errorSnapshot(localizedMessage(error, localization(), provider), error.toFetchErrorReason())
        }
    }

    /**
     * Keeps the last successful graph visible while clearly marking it as stale during backoff. A
     * fetch held back by the backoff counts as rate limited too, as on iOS.
     */
    private suspend fun rateLimitedSnapshot(agentId: UUID, until: Instant): AgentSnapshot {
        val minutes = minutesUntil(until)
        val l10n = localization()
        return rateLimitGate.lastGood(agentId)?.copy(
            error = l10n.errRateLimitedRetryStale(minutes),
            errorReason = FetchErrorReason.RATE_LIMIT,
        ) ?: errorSnapshot(l10n.errRateLimitedRetry(minutes), FetchErrorReason.RATE_LIMIT)
    }

    private fun errorSnapshot(message: String, reason: FetchErrorReason) = AgentSnapshot(
        windows = emptyList(),
        planLabel = null,
        fetchedAt = now(),
        error = message,
        errorReason = reason,
    )

    private fun minutesUntil(until: Instant): Int {
        val remainingMillis = max(0L, Duration.between(now(), until).toMillis())
        return max(1, ((remainingMillis + MILLIS_PER_MINUTE - 1) / MILLIS_PER_MINUTE).toInt())
    }

    companion object {
        private const val MILLIS_PER_MINUTE = 60_000L

        /**
         * [provider] only refines the wording for errors whose meaning is provider-specific; the
         * exception (and therefore the analytics bucket) is unchanged.
         */
        internal fun localizedMessage(
            error: Throwable,
            l10n: L10n,
            provider: AgentProvider? = null,
        ): String = when {
            // An API key has no refresh token, so its 401 surfaces as NotAuthenticated.
            provider == AgentProvider.OPENROUTER && error is OAuthException.NotAuthenticated ->
                l10n.errOpenRouterKeyRejected
            provider == AgentProvider.OPENROUTER && error is UsageException.Http && error.statusCode == 403 ->
                l10n.errOpenRouterKeyPermission
            else -> genericMessage(error, l10n)
        }

        private fun genericMessage(error: Throwable, l10n: L10n): String = when (error) {
            is UsageException.Unauthorized -> l10n.errAuthExpired
            is UsageException.RateLimited -> l10n.errRateLimited
            is UsageException.Http -> l10n.errHttp(error.statusCode)
            is UsageException.Decode -> l10n.errDecode(error.message.orEmpty())
            is UsageException.NoWindows -> l10n.errNoWindows
            is CredentialUnavailableException -> l10n.errCredential(error.reason)
            is OAuthException.NotAuthenticated -> l10n.errNotAuthenticated
            is OAuthException.RefreshRevoked -> l10n.errAuthExpired
            is OAuthException.RefreshFailed -> l10n.errTokenRefresh(error.detailAfterPrefix())
            is OAuthException.ExchangeFailed -> l10n.errTokenExchange(error.detailAfterPrefix())
            is OAuthException.StateMismatch -> l10n.errStateMismatch
            is OAuthException.CodeExpired -> l10n.errTokenExchange(l10n.errCodeExpired)
            is java.io.IOException -> l10n.errNetwork
            else -> error.localizedMessage?.takeIf(String::isNotBlank)
                ?: error::class.java.simpleName
        }

        private fun Throwable.detailAfterPrefix(): String =
            message?.substringAfter(": ", message.orEmpty()).orEmpty()
    }
}
