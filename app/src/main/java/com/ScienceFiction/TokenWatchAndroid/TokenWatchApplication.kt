package com.ScienceFiction.TokenWatchAndroid

import android.app.Application
import android.content.Context
import com.ScienceFiction.TokenWatchAndroid.auth.AndroidCredentialVault
import com.ScienceFiction.TokenWatchAndroid.auth.ProviderAuthRegistry
import com.ScienceFiction.TokenWatchAndroid.auth.ProviderTokenRefresher
import com.ScienceFiction.TokenWatchAndroid.auth.TokenStore
import com.ScienceFiction.TokenWatchAndroid.auth.device.CopilotDeviceFlow
import com.ScienceFiction.TokenWatchAndroid.auth.oauth.ClaudeOAuthClient
import com.ScienceFiction.TokenWatchAndroid.auth.oauth.CodexOAuthClient
import com.ScienceFiction.TokenWatchAndroid.data.AgentRepository
import com.ScienceFiction.TokenWatchAndroid.data.SettingsRepository
import com.ScienceFiction.TokenWatchAndroid.localization.AppLocaleState
import com.ScienceFiction.TokenWatchAndroid.network.core.HttpTransport
import com.ScienceFiction.TokenWatchAndroid.network.orchestration.ProviderUsageRegistry
import com.ScienceFiction.TokenWatchAndroid.network.orchestration.RateLimitGate
import com.ScienceFiction.TokenWatchAndroid.network.orchestration.UsageGateway
import com.ScienceFiction.TokenWatchAndroid.network.status.ServiceStatusClient
import com.ScienceFiction.TokenWatchAndroid.store.AgentStore
import java.io.Closeable
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
        localization = localeState::l10n,
    )
    private val serviceStatusClient = ServiceStatusClient(transport)
    private val agentRepository = AgentRepository(applicationContext)
    private val settingsRepository = SettingsRepository(applicationContext)

    val agentStore = AgentStore(
        scope = scope,
        agentRepository = agentRepository,
        settingsRepository = settingsRepository,
        tokenStore = tokenStore,
        usageGateway = usageGateway,
        serviceStatusClient = serviceStatusClient,
    )

    private val languageSyncJob = scope.launch {
        agentStore.settings.collect { settings ->
            localeState.language = settings.language
        }
    }

    override fun close() {
        languageSyncJob.cancel()
        agentStore.close()
        scope.cancel()
    }
}

fun Context.tokenWatchContainer(): TokenWatchContainer =
    (applicationContext as TokenWatchApplication).container
