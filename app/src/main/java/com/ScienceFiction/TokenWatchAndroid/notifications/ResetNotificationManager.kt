package com.ScienceFiction.TokenWatchAndroid.notifications

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import com.ScienceFiction.TokenWatchAndroid.MainActivity
import com.ScienceFiction.TokenWatchAndroid.domain.Agent
import com.ScienceFiction.TokenWatchAndroid.domain.WindowKind
import com.ScienceFiction.TokenWatchAndroid.localization.L10n
import java.time.Instant
import java.util.UUID

/** Android side-effect adapter for immediate and scheduled local reset notifications. */
class ResetNotificationManager(
    context: Context,
    private val localization: () -> L10n,
) {
    private val applicationContext = context.applicationContext
    private val notifications = applicationContext.getSystemService(NotificationManager::class.java)
    private val alarms = applicationContext.getSystemService(AlarmManager::class.java)
    private val preferences = applicationContext.getSharedPreferences(PreferencesName, Context.MODE_PRIVATE)

    init {
        notifications.createNotificationChannel(
            NotificationChannel(ChannelId, "Usage resets", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "TokenWatch usage-limit reset notifications"
            },
        )
    }

    fun isAuthorized(): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(
                applicationContext,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED

    fun isDenied(): Boolean =
        Build.VERSION.SDK_INT >= 33 && permissionWasAsked() && !isAuthorized()

    fun permissionWasAsked(): Boolean = preferences.getBoolean(PermissionAskedKey, false)

    fun markPermissionAsked() {
        preferences.edit { putBoolean(PermissionAskedKey, true) }
    }

    fun fire(agent: Agent, events: List<ResetEvent>) {
        if (!isAuthorized()) return
        val loc = localization()
        events.forEach { event ->
            val identifier =
                "${ResetSchedulePolicy.IdPrefix}${agent.id}|fired|${event.fireTime.epochSecond / 60L}"
            post(
                identifier = identifier,
                title = loc.notifResetTitle(agent.provider.displayName, agent.accountLabel),
                body = loc.notifResetBody(
                    session = WindowKind.SESSION in event.kinds,
                    weekly = WindowKind.WEEKLY in event.kinds,
                ),
                agentId = agent.id.toString(),
            )
        }
    }

    fun applyScheduled(
        targets: List<ResetScheduleTarget>,
        agents: List<Agent>,
        now: Instant = Instant.now(),
    ) {
        val loc = localization()
        val agentsById = agents.associateBy(Agent::id)
        val desiredTargets = if (isAuthorized()) {
            targets.filter { it.fireTime.isAfter(now) }.associateBy(ResetScheduleTarget::identifier)
        } else {
            emptyMap()
        }
        val pending = pendingIds()
        val reconciliation = ResetSchedulePolicy.reconcile(desiredTargets.keys, pending)

        reconciliation.remove.forEach(::cancel)
        reconciliation.add.forEach { identifier ->
            val target = desiredTargets.getValue(identifier)
            val agent = agentsById[target.agentId]
            schedule(
                identifier = identifier,
                fireTime = target.fireTime,
                title = agent?.let {
                    loc.notifResetTitle(it.provider.displayName, it.accountLabel)
                } ?: loc.notifDefaultTitle,
                body = loc.notifResetBody(
                    session = WindowKind.SESSION in target.kinds,
                    weekly = WindowKind.WEEKLY in target.kinds,
                ),
                agentId = target.agentId.toString(),
            )
        }
        preferences.edit { putStringSet(PendingIdsKey, desiredTargets.keys.toSet()) }
    }

    fun removePending(agentId: UUID) {
        val prefix = "${ResetSchedulePolicy.IdPrefix}$agentId|"
        val remove = pendingIds().filter { it.startsWith(prefix) }
        remove.forEach(::cancel)
        preferences.edit { putStringSet(PendingIdsKey, pendingIds() - remove.toSet()) }
    }

    private fun schedule(
        identifier: String,
        fireTime: Instant,
        title: String,
        body: String,
        agentId: String,
    ) {
        alarms.setAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            fireTime.toEpochMilli(),
            alarmPendingIntent(identifier, title, body, agentId, PendingIntent.FLAG_UPDATE_CURRENT),
        )
    }

    private fun cancel(identifier: String) {
        val pendingIntent = alarmPendingIntent(
            identifier,
            "",
            "",
            "",
            PendingIntent.FLAG_UPDATE_CURRENT,
        )
        alarms.cancel(pendingIntent)
        pendingIntent.cancel()
    }

    private fun alarmPendingIntent(
        identifier: String,
        title: String,
        body: String,
        agentId: String,
        lookupFlag: Int,
    ): PendingIntent {
        val intent = Intent(applicationContext, ResetAlarmReceiver::class.java).apply {
            data = "tokenwatch://reset/${Uri.encode(identifier)}".toUri()
            putExtra(ExtraIdentifier, identifier)
            putExtra(ExtraTitle, title)
            putExtra(ExtraBody, body)
            putExtra(ExtraAgentId, agentId)
        }
        return PendingIntent.getBroadcast(
            applicationContext,
            identifier.hashCode(),
            intent,
            lookupFlag or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun pendingIds(): Set<String> =
        preferences.getStringSet(PendingIdsKey, emptySet()).orEmpty().toSet()

    private fun post(identifier: String, title: String, body: String, agentId: String?) {
        if (!isAuthorized()) return
        post(applicationContext, identifier, title, body, agentId)
    }

    companion object {
        private const val PreferencesName = "tokenwatch.notifications"
        private const val PendingIdsKey = "pending.reset.ids"
        private const val PermissionAskedKey = "permission.asked"
        private const val ChannelId = "usage_resets"
        internal const val ExtraIdentifier = "identifier"
        internal const val ExtraTitle = "title"
        internal const val ExtraBody = "body"
        internal const val ExtraAgentId = "agentId"

        internal fun post(
            context: Context,
            identifier: String,
            title: String,
            body: String,
            agentId: String?,
        ) {
            if (
                Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) return

            val contentIntent = PendingIntent.getActivity(
                context,
                identifier.hashCode(),
                Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    agentId?.let { putExtra(ExtraAgentId, it) }
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val notification = Notification.Builder(context, ChannelId)
                .setSmallIcon(android.R.drawable.ic_popup_reminder)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(Notification.BigTextStyle().bigText(body))
                .setContentIntent(contentIntent)
                .setAutoCancel(true)
                .build()
            context.getSystemService(NotificationManager::class.java)
                .notify(identifier.hashCode(), notification)
        }
    }
}

class ResetAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val identifier = intent.getStringExtra(ResetNotificationManager.ExtraIdentifier) ?: return
        val title = intent.getStringExtra(ResetNotificationManager.ExtraTitle) ?: return
        val body = intent.getStringExtra(ResetNotificationManager.ExtraBody) ?: return
        ResetNotificationManager.post(
            context = context,
            identifier = identifier,
            title = title,
            body = body,
            agentId = intent.getStringExtra(ResetNotificationManager.ExtraAgentId),
        )
    }
}
