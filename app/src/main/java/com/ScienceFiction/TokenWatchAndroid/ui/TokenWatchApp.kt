package com.ScienceFiction.TokenWatchAndroid.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ScienceFiction.TokenWatchAndroid.BuildConfig
import com.ScienceFiction.TokenWatchAndroid.TokenWatchContainer
import com.ScienceFiction.TokenWatchAndroid.data.AppSettings
import com.ScienceFiction.TokenWatchAndroid.domain.Agent
import com.ScienceFiction.TokenWatchAndroid.domain.ServiceHealth
import com.ScienceFiction.TokenWatchAndroid.localization.L10n
import com.ScienceFiction.TokenWatchAndroid.store.AccountInfo
import com.ScienceFiction.TokenWatchAndroid.tokenWatchContainer
import com.ScienceFiction.TokenWatchAndroid.ui.components.AnnouncementDialog
import com.ScienceFiction.TokenWatchAndroid.ui.add.AddAgentScreen
import com.ScienceFiction.TokenWatchAndroid.ui.add.AddAgentFlowViewModel
import com.ScienceFiction.TokenWatchAndroid.ui.screens.AnnouncementDetailScreen
import com.ScienceFiction.TokenWatchAndroid.ui.screens.AnnouncementsScreen
import com.ScienceFiction.TokenWatchAndroid.ui.screens.DetailAccountUiState
import com.ScienceFiction.TokenWatchAndroid.ui.screens.DetailScreen
import com.ScienceFiction.TokenWatchAndroid.ui.screens.MainScreen
import com.ScienceFiction.TokenWatchAndroid.ui.screens.SettingsScreen
import com.ScienceFiction.TokenWatchAndroid.ui.theme.Term
import java.util.UUID
import kotlinx.coroutines.launch

private const val ROUTE_MAIN = "main"
private const val ROUTE_ADD = "add"
private const val ROUTE_SETTINGS = "settings"
private const val ROUTE_DETAIL_PREFIX = "detail:"
private const val ROUTE_ANNOUNCEMENTS = "announcements"
// Distinct from ROUTE_ANNOUNCEMENTS: "announcements" does not start with "announcement:".
private const val ROUTE_ANNOUNCEMENT_PREFIX = "announcement:"

@Composable
fun TokenWatchApp() {
    val container = LocalContext.current.tokenWatchContainer()
    val addAgentFlow: AddAgentFlowViewModel = viewModel()
    TokenWatchApp(container, addAgentFlow)
}

@Composable
internal fun TokenWatchApp(
    container: TokenWatchContainer,
    addAgentFlow: AddAgentFlowViewModel,
) {
    val store = container.agentStore
    val announcements = container.announcementStore
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current
    val view = LocalView.current

    val agents by store.agents.collectAsStateWithLifecycle()
    val snapshots by store.snapshots.collectAsStateWithLifecycle()
    val loadingIds by store.loadingIDs.collectAsStateWithLifecycle()
    val serviceStatus by store.serviceStatus.collectAsStateWithLifecycle()
    val settings by store.settings.collectAsStateWithLifecycle()
    val autoIntervalSeconds by store.autoIntervalSeconds.collectAsStateWithLifecycle()
    val presentedAnnouncement by announcements.presented.collectAsStateWithLifecycle()
    val announcementFeed by announcements.feed.collectAsStateWithLifecycle()
    val announcementSeen by announcements.seen.collectAsStateWithLifecycle()
    val announcementFailedAt by announcements.lastAttemptFailedAt.collectAsStateWithLifecycle()
    val inboxItems = remember(announcementFeed, announcementSeen) { announcements.inbox() }
    val unreadIds = remember(announcementFeed, announcementSeen) {
        inboxItems.filter(announcements::isUnread).map { it.id }.toSet()
    }
    val isDemo by store.isDemo.collectAsStateWithLifecycle()
    val loc = remember(settings.language) { L10n(settings.language.resolved()) }

    var route by rememberSaveable { mutableStateOf(ROUTE_MAIN) }
    var initialLoadFinished by remember { mutableStateOf(false) }
    var isForeground by remember { mutableStateOf(false) }
    var isRefreshingAll by remember { mutableStateOf(false) }
    var notificationPermissionRevision by remember { mutableIntStateOf(0) }
    val mainStateHolder = rememberSaveableStateHolder()

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        container.resetNotificationManager.markPermissionAsked()
        notificationPermissionRevision += 1
        scope.launch { store.reapplyNotificationSchedule() }
    }

    LaunchedEffect(store) {
        store.awaitInitialLoad()
        initialLoadFinished = true
    }

    DisposableEffect(lifecycleOwner, store) {
        val lifecycle = lifecycleOwner.lifecycle
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> {
                    isForeground = true
                    container.backgroundRefreshScheduler.cancel()
                    // Both cold start and foreground return land here; the store throttles.
                    announcements.check()
                }
                Lifecycle.Event.ON_RESUME -> isForeground = true
                // Keep the screen-awake flag across ON_PAUSE (notification shade, permission UI,
                // and other transient interruptions). ON_STOP is the actual background boundary,
                // matching iOS c983971's active/inactive versus background distinction.
                Lifecycle.Event.ON_STOP -> {
                    isForeground = false
                    container.backgroundRefreshScheduler.schedule(store.nextResetInstant())
                }
                Lifecycle.Event.ON_DESTROY -> isForeground = false
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        isForeground = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        onDispose {
            lifecycle.removeObserver(observer)
            store.stopAutoRefresh()
        }
    }

    LaunchedEffect(isForeground, settings.refreshInterval, store) {
        if (isForeground) {
            store.startAutoRefresh(settings.refreshInterval)
        } else {
            store.stopAutoRefresh()
        }
    }

    DisposableEffect(view, isForeground, settings.keepScreenOn) {
        view.keepScreenOn = isForeground && settings.keepScreenOn
        onDispose { view.keepScreenOn = false }
    }

    val selectedAgent = route
        .takeIf { it.startsWith(ROUTE_DETAIL_PREFIX) }
        ?.removePrefix(ROUTE_DETAIL_PREFIX)
        ?.let { value -> runCatching { UUID.fromString(value) }.getOrNull() }
        ?.let { id -> agents.firstOrNull { it.id == id } }

    val selectedAnnouncement = route
        .takeIf { it.startsWith(ROUTE_ANNOUNCEMENT_PREFIX) }
        ?.removePrefix(ROUTE_ANNOUNCEMENT_PREFIX)
        ?.let { id -> inboxItems.firstOrNull { it.id == id } }

    LaunchedEffect(route, inboxItems) {
        // A cap or a takedown can drop an item out from under an open detail screen.
        if (route.startsWith(ROUTE_ANNOUNCEMENT_PREFIX) && selectedAnnouncement == null) {
            route = ROUTE_ANNOUNCEMENTS
        }
    }

    LaunchedEffect(route, agents, initialLoadFinished) {
        if (
            initialLoadFinished && route.startsWith(ROUTE_DETAIL_PREFIX) &&
            selectedAgent == null
        ) {
            route = ROUTE_MAIN
        }
    }

    val openUrl = remember(context) {
        { rawUrl: String ->
            runCatching {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(rawUrl)))
            }
            Unit
        }
    }

    when (route) {
        ROUTE_MAIN -> mainStateHolder.SaveableStateProvider(ROUTE_MAIN) {
            MainScreen(
                agents = agents,
                snapshots = snapshots,
                loadingAgentIds = loadingIds,
                serviceStatus = serviceStatus,
                settings = settings,
                loc = loc,
                appVersion = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                isRefreshingAll = isRefreshingAll,
                isDemo = isDemo,
                unreadAnnouncements = unreadIds.size,
                onSettings = { route = ROUTE_SETTINGS },
                onAnnouncements = { route = ROUTE_ANNOUNCEMENTS },
                onAddAgent = { route = ROUTE_ADD },
                onOpenAgent = { agent -> route = "$ROUTE_DETAIL_PREFIX${agent.id}" },
                onMoveUp = { agent -> scope.launch { store.moveUp(agent) } },
                onMoveDown = { agent -> scope.launch { store.moveDown(agent) } },
                onRefreshAgent = { agent -> scope.launch { store.refresh(agent) } },
                onDeleteAgent = { agent -> scope.launch { store.remove(agent) } },
                onRefreshAll = {
                    if (!isRefreshingAll) {
                        scope.launch {
                            isRefreshingAll = true
                            try {
                                store.refreshAll()
                            } finally {
                                isRefreshingAll = false
                            }
                        }
                    }
                },
                onEnterDemo = { scope.launch { store.enterDemo(); store.startAutoRefresh(settings.refreshInterval) } },
                onExitDemo = { scope.launch { store.exitDemo(); store.startAutoRefresh(settings.refreshInterval) } },
            )
        }

        else -> when (route) {
            ROUTE_ADD -> AddAgentScreen(
                flowState = addAgentFlow,
                providerAuth = container.providerAuth,
                deviceFlow = container.deviceFlow,
                loc = loc,
                onAddAgent = { provider, tokens ->
                    store.addAgent(provider, tokens)
                    if (
                        Build.VERSION.SDK_INT >= 33 &&
                        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                        PackageManager.PERMISSION_GRANTED &&
                        !container.resetNotificationManager.permissionWasAsked()
                    ) {
                        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                },
                onOpenUrl = openUrl,
                onDismiss = {
                    addAgentFlow.cancelAndReset()
                    route = ROUTE_MAIN
                },
            )

            ROUTE_ANNOUNCEMENTS -> {
                BackHandler { route = ROUTE_MAIN }
                AnnouncementsScreen(
                    announcements = inboxItems,
                    unread = unreadIds,
                    fetchFailed = announcementFeed == null && announcementFailedAt != null,
                    loc = loc,
                    onOpen = { route = "$ROUTE_ANNOUNCEMENT_PREFIX${it.id}" },
                    onBack = { route = ROUTE_MAIN },
                )
            }

            ROUTE_SETTINGS -> {
                BackHandler { route = ROUTE_MAIN }
                SettingsScreen(
                    settings = settings,
                    agents = agents,
                    snapshots = snapshots,
                    autoIntervalSeconds = autoIntervalSeconds,
                    appVersion = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                    loc = loc,
                    onSettingsChange = { value ->
                        val base = settings
                        scope.launch {
                            store.updateSettings { current ->
                                mergeSettingsChange(base = base, proposed = value, current = current)
                            }
                        }
                    },
                    onLogoutAgent = { agent -> scope.launch { store.remove(agent) } },
                    isDemo = isDemo,
                    onToggleDemo = {
                        scope.launch {
                            if (isDemo) store.exitDemo() else store.enterDemo()
                            store.startAutoRefresh(settings.refreshInterval)
                            route = ROUTE_MAIN
                        }
                    },
                    onDone = { route = ROUTE_MAIN },
                    notificationDenied = run {
                        notificationPermissionRevision
                        container.resetNotificationManager.isDenied()
                    },
                    onOpenNotificationSettings = {
                        context.startActivity(
                            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                                putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                            },
                        )
                    },
                )
            }

            else -> if (selectedAnnouncement != null) {
                BackHandler { route = ROUTE_ANNOUNCEMENTS }
                LaunchedEffect(selectedAnnouncement.id) {
                    announcements.markSeen(selectedAnnouncement.id)
                }
                AnnouncementDetailScreen(
                    announcement = selectedAnnouncement,
                    loc = loc,
                    onBack = { route = ROUTE_ANNOUNCEMENTS },
                )
            } else if (selectedAgent != null) {
                AgentDetailRoute(
                    agent = selectedAgent,
                    container = container,
                    loc = loc,
                    snapshot = snapshots[selectedAgent.id],
                    isLoading = selectedAgent.id in loadingIds,
                    serviceHealth = serviceStatus[selectedAgent.provider] ?: ServiceHealth.UNKNOWN,
                    hideUnusedWindows = settings.hideUnusedWindows,
                    gaugeCritterEnabled = settings.gaugeCritter,
                    workHours = settings.workHours,
                    workHoursEnabled = settings.workHoursEnabled,
                    isDemo = isDemo,
                    onOpenUrl = openUrl,
                    onBack = { route = ROUTE_MAIN },
                    onLogout = {
                        scope.launch {
                            store.remove(selectedAgent)
                            route = ROUTE_MAIN
                        }
                    },
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Term.Background),
                )
            }
        }
    }

    // Sits outside the route switch so it can also cover a pushed detail screen, matching iOS.
    // Held back on the add and settings routes: those are full screens here rather than sheets, and
    // the add route hosts the OAuth WebView, where a modal scrim would block a login in progress.
    val announcementAllowed = initialLoadFinished &&
        !container.underInstrumentation &&
        (route == ROUTE_MAIN || route.startsWith(ROUTE_DETAIL_PREFIX))
    val announcement = presentedAnnouncement
    if (announcementAllowed && announcement != null) {
        LaunchedEffect(announcement.id) {
            // Seen in the popup counts as read in the inbox too, so no badge is left behind.
            announcements.markSeen(announcement.id)
        }
        AnnouncementDialog(
            announcement = announcement,
            loc = loc,
            onClose = announcements::closePresented,
            onDismissForever = { scope.launch { announcements.dismissPresentedForever() } },
        )
    }

}

@Composable
private fun AgentDetailRoute(
    agent: Agent,
    container: TokenWatchContainer,
    loc: L10n,
    snapshot: com.ScienceFiction.TokenWatchAndroid.domain.AgentSnapshot?,
    isLoading: Boolean,
    serviceHealth: ServiceHealth,
    hideUnusedWindows: Boolean,
    gaugeCritterEnabled: Boolean,
    workHours: String,
    workHoursEnabled: Boolean?,
    isDemo: Boolean,
    onOpenUrl: (String) -> Unit,
    onBack: () -> Unit,
    onLogout: () -> Unit,
) {
    val store = container.agentStore
    val scope = rememberCoroutineScope()
    var account by remember(agent.id) { mutableStateOf(DetailAccountUiState()) }
    var showLogoutConfirmation by rememberSaveable(agent.id) { mutableStateOf(false) }

    BackHandler(onBack = onBack)
    LaunchedEffect(agent.id) {
        account = store.accountInfo(agent).toDetailUiState()
    }
    LaunchedEffect(agent.provider) {
        store.refreshStatus(agent.provider)
    }

    DetailScreen(
        agent = agent,
        snapshot = snapshot,
        isLoading = isLoading,
        account = account,
        serviceHealth = serviceHealth,
        hideUnusedWindows = hideUnusedWindows,
        gaugeCritterEnabled = gaugeCritterEnabled,
        workHoursSchedule = com.ScienceFiction.TokenWatchAndroid.domain.WorkHoursSchedule
            .active(workHours, workHoursEnabled),
        isDemo = isDemo,
        loc = loc,
        showLogoutConfirmation = showLogoutConfirmation,
        onBack = onBack,
        onRefresh = {
            scope.launch {
                store.refresh(agent, manual = true)
                store.refreshStatus(agent.provider, force = true)
                account = store.accountInfo(agent).toDetailUiState()
            }
        },
        onOpenStatusPage = onOpenUrl,
        onResetCreditPeak = { windowLabel ->
            scope.launch { store.resetCreditPeak(agent.id, windowLabel) }
        },
        onLogoutRequest = { showLogoutConfirmation = true },
        onLogoutConfirm = {
            showLogoutConfirmation = false
            onLogout()
        },
        onLogoutDismiss = { showLogoutConfirmation = false },
    )
}

private fun AccountInfo?.toDetailUiState(): DetailAccountUiState = DetailAccountUiState(
    email = this?.email,
    plan = this?.plan,
    isLoading = false,
    canRefresh = this?.canRefresh ?: true,
    expiresAt = this?.expiresAt,
)

/** Applies only fields changed by a settings event, preserving other concurrent taps. */
internal fun mergeSettingsChange(
    base: AppSettings,
    proposed: AppSettings,
    current: AppSettings,
): AppSettings = current.copy(
    refreshInterval = proposed.refreshInterval.takeIf { it != base.refreshInterval }
        ?: current.refreshInterval,
    keepScreenOn = proposed.keepScreenOn.takeIf { it != base.keepScreenOn }
        ?: current.keepScreenOn,
    hideUnusedWindows = proposed.hideUnusedWindows.takeIf { it != base.hideUnusedWindows }
        ?: current.hideUnusedWindows,
    gaugeCritter = proposed.gaugeCritter.takeIf { it != base.gaugeCritter }
        ?: current.gaugeCritter,
    workHours = proposed.workHours.takeIf { it != base.workHours } ?: current.workHours,
    // Compared explicitly rather than with the takeIf/elvis idiom above: for a nullable
    // field null.takeIf {} is always null, so a deliberate reset would fall back to current.
    workHoursEnabled = if (proposed.workHoursEnabled != base.workHoursEnabled) {
        proposed.workHoursEnabled
    } else {
        current.workHoursEnabled
    },
    heartbeatCursor = proposed.heartbeatCursor.takeIf { it != base.heartbeatCursor }
        ?: current.heartbeatCursor,
    heartbeatTracking = proposed.heartbeatTracking.takeIf { it != base.heartbeatTracking }
        ?: current.heartbeatTracking,
    heartbeatTargets = proposed.heartbeatTargets.takeIf { it != base.heartbeatTargets }
        ?: current.heartbeatTargets,
    notifySessionResets = proposed.notifySessionResets.takeIf {
        it != base.notifySessionResets
    } ?: current.notifySessionResets,
    notifyWeeklyResets = proposed.notifyWeeklyResets.takeIf {
        it != base.notifyWeeklyResets
    } ?: current.notifyWeeklyResets,
    language = proposed.language.takeIf { it != base.language } ?: current.language,
)
