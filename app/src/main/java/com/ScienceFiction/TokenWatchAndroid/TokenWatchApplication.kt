package com.ScienceFiction.TokenWatchAndroid

import android.app.Application
import android.content.Context
import com.ScienceFiction.TokenWatchAndroid.account.AccountAuth
import com.ScienceFiction.TokenWatchAndroid.account.AccountStore
import com.ScienceFiction.TokenWatchAndroid.account.FirebaseAccountAuth
import com.ScienceFiction.TokenWatchAndroid.account.PlayerProfileClient
import com.ScienceFiction.TokenWatchAndroid.account.UnavailableAccountAuth
import com.ScienceFiction.TokenWatchAndroid.analytics.AnalyticsService
import com.ScienceFiction.TokenWatchAndroid.analytics.fetchOutcomeEvent
import com.ScienceFiction.TokenWatchAndroid.domain.AgentProvider
import com.ScienceFiction.TokenWatchAndroid.auth.AndroidCredentialVault
import com.ScienceFiction.TokenWatchAndroid.auth.ProviderAuthRegistry
import com.ScienceFiction.TokenWatchAndroid.auth.ProviderTokenRefresher
import com.ScienceFiction.TokenWatchAndroid.auth.TokenStore
import com.ScienceFiction.TokenWatchAndroid.auth.device.CopilotDeviceFlow
import com.ScienceFiction.TokenWatchAndroid.auth.device.CursorAuth
import com.ScienceFiction.TokenWatchAndroid.auth.oauth.ClaudeOAuthClient
import com.ScienceFiction.TokenWatchAndroid.auth.oauth.CodexOAuthClient
import com.ScienceFiction.TokenWatchAndroid.auth.oauth.GrokOAuthClient
import com.ScienceFiction.TokenWatchAndroid.data.AgentRepository
import com.ScienceFiction.TokenWatchAndroid.data.AnnouncementRepository
import com.ScienceFiction.TokenWatchAndroid.data.PlayerProfileRepository
import com.ScienceFiction.TokenWatchAndroid.domain.account.AvatarCatalog
import com.ScienceFiction.TokenWatchAndroid.data.ReviewPromptRepository
import com.ScienceFiction.TokenWatchAndroid.data.SettingsRepository
import com.ScienceFiction.TokenWatchAndroid.localization.AppLocaleState
import com.ScienceFiction.TokenWatchAndroid.network.announcements.AnnouncementFeedClient
import com.ScienceFiction.TokenWatchAndroid.network.core.HttpTransport
import com.ScienceFiction.TokenWatchAndroid.network.orchestration.ProviderUsageRegistry
import com.ScienceFiction.TokenWatchAndroid.network.orchestration.RateLimitGate
import com.ScienceFiction.TokenWatchAndroid.network.orchestration.UsageGateway
import com.ScienceFiction.TokenWatchAndroid.network.providers.apikey.KimiUsageClient
import com.ScienceFiction.TokenWatchAndroid.network.providers.subscription.CodexAccountClient
import com.ScienceFiction.TokenWatchAndroid.network.status.ServiceStatusClient
import com.ScienceFiction.TokenWatchAndroid.notifications.BackgroundRefreshScheduler
import com.ScienceFiction.TokenWatchAndroid.notifications.ResetNotificationManager
import com.ScienceFiction.TokenWatchAndroid.store.AgentStore
import com.ScienceFiction.TokenWatchAndroid.store.AnnouncementStore
import com.google.firebase.FirebaseApp
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

    /** For hand-attached session cookies (Cursor), which must never follow a redirect. */
    private val noRedirectTransport = transport.withoutRedirects()

    val localeState = AppLocaleState()
    private val claudeOAuth = ClaudeOAuthClient(transport)
    private val codexOAuth = CodexOAuthClient(transport)
    private val grokOAuth = GrokOAuthClient(transport)
    private val kimiUsage = KimiUsageClient(transport)
    val providerAuth = ProviderAuthRegistry(
        claudeOAuth = claudeOAuth,
        codexOAuth = codexOAuth,
        grokOAuth = grokOAuth,
        copilotDeviceFlow = CopilotDeviceFlow(transport),
        cursorAuth = CursorAuth(noRedirectTransport),
        kimi = kimiUsage,
    )

    private val credentialVault = AndroidCredentialVault(applicationContext)
    val tokenStore = TokenStore(
        vault = credentialVault,
        refresher = ProviderTokenRefresher(claudeOAuth, codexOAuth, grokOAuth),
    )

    private val usageRegistry = ProviderUsageRegistry.create(
        transport = transport,
        noRedirectTransport = noRedirectTransport,
        kimi = kimiUsage,
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

    /** Review prompt counters. One cold start is recorded as the graph is built (see below). */
    val reviewPromptRepository = ReviewPromptRepository(applicationContext)

    /** Demo state is read lazily so sample agents never surface as real provider activity. */
    val analytics = AnalyticsService(
        context = applicationContext,
        isDemo = { agentStore.isDemo.value },
    )

    /** Bundled avatar artwork for the app account, decoded once on first use. */
    val avatarCatalog: AvatarCatalog by lazy {
        AvatarCatalog.fromFiles(
            AvatarCatalog.BUNDLED_FILES.mapNotNull { name ->
                runCatching { applicationContext.assets.open(name).use { it.readBytes() } }.getOrNull()
            },
        )
    }

    /** Main-thread scope for the app account, so its state changes are confined to one thread. */
    private val accountScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /**
     * The optional TokenWatch app account. Separate from the provider accounts above: it shares only
     * the HTTP transport. The Firestore project comes from `google-services.json`, not from the
     * announcement keys in local.properties, and a build without Firebase leaves the feature hidden.
     */
    val accountStore: AccountStore = run {
        val auth: AccountAuth = (applicationContext as? Application)
            ?.let { FirebaseAccountAuth(it, language = { localeState.l10n().lang }) }
            ?: UnavailableAccountAuth
        val projectId = runCatching { FirebaseApp.getInstance().options.projectId }.getOrNull()
            ?.takeIf { it.isNotBlank() && auth.isAvailable }
        AccountStore(
            auth = auth,
            profiles = projectId?.let { PlayerProfileClient(it, transport, auth::idToken) },
            cache = PlayerProfileRepository(applicationContext),
            scope = accountScope,
            isDemo = { agentStore.isDemo.value },
            log = analytics::log,
        ).also(AccountStore::start)
    }

    /**
     * Last known outcome per agent. Only transitions are reported: a provider that keeps failing
     * logs one error, not one per refresh, and the recovery is what closes the pair.
     */
    private val fetchHadError = ConcurrentHashMap<UUID, Boolean>()

    private fun recordFetchOutcome(provider: AgentProvider, agentId: UUID, error: Throwable?) {
        val previouslyFailed = fetchHadError.put(agentId, error != null) == true
        fetchOutcomeEvent(provider, error, previouslyFailed)?.let(analytics::log)
    }

    /** The process starting is the cold start the review policy counts. */
    private val reviewLaunchJob = scope.launch {
        reviewPromptRepository.recordLaunch(System.currentTimeMillis())
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
        accountScope.cancel()
        scope.cancel()
    }
}

fun Context.tokenWatchContainer(): TokenWatchContainer =
    (applicationContext as TokenWatchApplication).container
