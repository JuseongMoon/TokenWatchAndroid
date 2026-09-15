package com.ScienceFiction.TokenWatchAndroid

import android.app.Application
import android.content.Context
import com.ScienceFiction.TokenWatchAndroid.analytics.AnalyticsService
import com.ScienceFiction.TokenWatchAndroid.analytics.fetchOutcomeEvent
import com.ScienceFiction.TokenWatchAndroid.domain.AgentProvider
import com.ScienceFiction.TokenWatchAndroid.auth.AndroidCredentialVault
import com.ScienceFiction.TokenWatchAndroid.auth.ProviderAuthRegistry
import com.ScienceFiction.TokenWatchAndroid.auth.ProviderTokenRefresher
import com.ScienceFiction.TokenWatchAndroid.auth.TokenStore
import com.ScienceFiction.TokenWatchAndroid.auth.device.CopilotDeviceFlow
import com.ScienceFiction.TokenWatchAndroid.auth.oauth.ClaudeOAuthClient
import com.ScienceFiction.TokenWatchAndroid.auth.oauth.CodexOAuthClient
import com.ScienceFiction.TokenWatchAndroid.data.AgentRepository
import com.ScienceFiction.TokenWatchAndroid.data.AnnouncementRepository
import com.ScienceFiction.TokenWatchAndroid.data.SettingsRepository
import com.ScienceFiction.TokenWatchAndroid.localization.AppLocaleState
import com.ScienceFiction.TokenWatchAndroid.network.announcements.AnnouncementFeedClient
import com.ScienceFiction.TokenWatchAndroid.network.core.HttpTransport
import com.ScienceFiction.TokenWatchAndroid.network.orchestration.ProviderUsageRegistry
import com.ScienceFiction.TokenWatchAndroid.network.orchestration.RateLimitGate
import com.ScienceFiction.TokenWatchAndroid.network.orchestration.UsageGateway
import com.ScienceFiction.TokenWatchAndroid.network.providers.subscription.CodexAccountClient
import com.ScienceFiction.TokenWatchAndroid.network.status.ServiceStatusClient
import com.ScienceFiction.TokenWatchAndroid.notifications.BackgroundRefreshScheduler
import com.ScienceFiction.TokenWatchAndroid.notifications.ResetNotificationManager
import com.ScienceFiction.TokenWatchAndroid.store.AgentStore
import com.ScienceFiction.TokenWatchAndroid.store.AnnouncementStore
import java.io.Closeable
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

class TokenWatchApplication : Application() {
    lateinit var container: TokenWatchContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = TokenWatchContainer(this)
    }
}

/** Process-scoped dependency graph; no credential is ever retained in Compose state. */
class TokenWatchContainer(context: Context) : Closeable {
    private val applicationContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val transport = HttpTransport()

    val localeState = AppLocaleState()
    val claudeOAuth = ClaudeOAuthClient(transport)
    val codexOAuth = CodexOAuthClient(transport)
    val providerAuth = ProviderAuthRegistry(claudeOAuth, codexOAuth)
    val deviceFlow = CopilotDeviceFlow(transport)

    private val credentialVault = AndroidCredentialVault(applicationContext)
    val tokenStore = TokenStore(
        vault = credentialVault,
        refresher = ProviderTokenRefresher(claudeOAuth, codexOAuth),
    )

    private val usageRegistry = ProviderUsageRegistry.create(
        transport = transport,
        codexAdditionalLimitLabel = { localeState.l10n().codexAdditionalLimit },
    )
    private val usageGateway = UsageGateway(
        registry = usageRegistry,
        tokenStore = tokenStore,
        rateLimitGate = RateLimitGate(),
        codexAccountClient = CodexAccountClient(transport),
        localization = localeState::l10n,
        onFetchOutcome = ::recordFetchOutcome,
    )
    private val serviceStatusClient = ServiceStatusClient(transport)
    private val agentRepository = AgentRepository(applicationContext)
    private val settingsRepository = SettingsRepository(applicationContext)
    val resetNotificationManager = ResetNotificationManager(applicationContext, localeState::l10n)
    val backgroundRefreshScheduler = BackgroundRefreshScheduler(applicationContext)

    val agentStore = AgentStore(
        scope = scope,
        agentRepository = agentRepository,
        settingsRepository = settingsRepository,
        tokenStore = tokenStore,
        usageGateway = usageGateway,
        serviceStatusClient = serviceStatusClient,
        resetNotificationManager = resetNotificationManager,
    )

    /**
     * True while running under instrumentation. The announcement popup is a modal scrim, and a
     * real announcement can be published at any time, so leaving it enabled would make the UI
     * tests fail intermittently for reasons unrelated to what they cover.
     */
    val underInstrumentation: Boolean = runCatching {
        Class.forName("androidx.test.platform.app.InstrumentationRegistry")
    }.isSuccess

    // Announcements ride the shared transport but are otherwise independent of the usage path.
    val announcementStore = AnnouncementStore(
        scope = scope,
        repository = AnnouncementRepository(applicationContext),
        client = AnnouncementFeedClient(transport),
    )

    /** Demo state is read lazily so sample agents never surface as real provider activity. */
    val analytics = AnalyticsService(
        context = applicationContext,
        isDemo = { agentStore.isDemo.value },
    )

    /**
     * Last known outcome per agent. Only transitions are reported: a provider that keeps failing
     * logs one error, not one per refresh, and the recovery is what closes the pair.
     */
    private val fetchHadError = ConcurrentHashMap<UUID, Boolean>()

    private fun recordFetchOutcome(provider: AgentProvider, agentId: UUID, error: Throwable?) {
        val previouslyFailed = fetchHadError.put(agentId, error != null) == true
        fetchOutcomeEvent(provider, error, previouslyFailed)?.let(analytics::log)
    }

    private val analyticsSyncJob = scope.launch {
        agentStore.settings.collect { settings ->
            analytics.setCollectionEnabled(settings.analyticsEnabled)
            analytics.syncSettingsProperties(settings)
        }
    }

    private val providerMigrationJob = scope.launch {
        agentRepository.migrateUnsupportedProviders { id ->
            tokenStore.delete(id)
            resetNotificationManager.removePending(id)
        }
    }

    private val languageSyncJob = scope.launch {
        agentStore.settings.collect { settings ->
            localeState.language = settings.language
        }
    }

    override fun close() {
        languageSyncJob.cancel()
        analyticsSyncJob.cancel()
        providerMigrationJob.cancel()
        agentStore.close()
        announcementStore.close()
        scope.cancel()
    }
}

fun Context.tokenWatchContainer(): TokenWatchContainer =
    (applicationContext as TokenWatchApplication).container
