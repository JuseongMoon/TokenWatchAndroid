package com.ScienceFiction.TokenWatchAndroid.network.orchestration

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

/** Token validation, one-shot 401 refresh, 429 gating, and last-good snapshot orchestration. */
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
            val windows = try {
                registry.fetchWindows(provider, tokens)
            } catch (_: UsageException.Unauthorized) {
                tokens = tokenStore.forceRefresh(agentId, provider)
                registry.fetchWindows(provider, tokens)
            }
            if (windows.isEmpty()) throw UsageException.NoWindows()

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
            errorSnapshot(localizedMessage(error, localization()))
        }
    }

    /** Keeps the last successful graph visible while clearly marking it as stale during backoff. */
    private suspend fun rateLimitedSnapshot(agentId: UUID, until: Instant): AgentSnapshot {
        val minutes = minutesUntil(until)
        val l10n = localization()
        return rateLimitGate.lastGood(agentId)?.copy(
            error = l10n.errRateLimitedRetryStale(minutes),
        ) ?: errorSnapshot(l10n.errRateLimitedRetry(minutes))
    }

    private fun errorSnapshot(message: String) = AgentSnapshot(
        windows = emptyList(),
        planLabel = null,
        fetchedAt = now(),
        error = message,
    )

    private fun minutesUntil(until: Instant): Int {
        val remainingMillis = max(0L, Duration.between(now(), until).toMillis())
        return max(1, ((remainingMillis + MILLIS_PER_MINUTE - 1) / MILLIS_PER_MINUTE).toInt())
    }

    companion object {
        private const val MILLIS_PER_MINUTE = 60_000L

        internal fun localizedMessage(error: Throwable, l10n: L10n): String = when (error) {
            is UsageException.Unauthorized -> l10n.errAuthExpired
            is UsageException.RateLimited -> l10n.errRateLimited
            is UsageException.Http -> l10n.errHttp(error.statusCode)
            is UsageException.Decode -> l10n.errDecode(error.message.orEmpty())
            is UsageException.NoWindows -> l10n.errNoWindows
            is OAuthException.NotAuthenticated -> l10n.errNotAuthenticated
            is OAuthException.RefreshRevoked -> l10n.errAuthExpired
            is OAuthException.RefreshFailed -> l10n.errTokenRefresh(error.detailAfterPrefix())
            is OAuthException.ExchangeFailed -> l10n.errTokenExchange(error.detailAfterPrefix())
            is OAuthException.StateMismatch -> l10n.errTokenExchange(error.message.orEmpty())
            else -> error.localizedMessage?.takeIf(String::isNotBlank)
                ?: error::class.java.simpleName
        }

        private fun Throwable.detailAfterPrefix(): String =
            message?.substringAfter(": ", message.orEmpty()).orEmpty()
    }
}
