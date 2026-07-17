package com.ScienceFiction.TokenWatchAndroid.notifications

import com.ScienceFiction.TokenWatchAndroid.domain.UsageStyle
import com.ScienceFiction.TokenWatchAndroid.domain.UsageWindow
import com.ScienceFiction.TokenWatchAndroid.domain.WindowKind
import java.time.Instant
import java.util.UUID

data class ResetScheduleTarget(
    val agentId: UUID,
    val fireTime: Instant,
    val kinds: Set<WindowKind>,
    val labels: List<String>,
) {
    val identifier: String
        get() = ResetSchedulePolicy.identifier(agentId, fireTime)
}

/** Pure scheduled-reset policy shared by Android alarms and JVM tests. */
object ResetSchedulePolicy {
    const val IdPrefix = "reset|"
    const val PerAgentLimit = 4
    const val GlobalLimit = 32

    fun identifier(agentId: UUID, fireTime: Instant): String =
        "$IdPrefix$agentId|${fireTime.epochSecond / 60L}"

    fun targets(
        agentId: UUID,
        windows: List<UsageWindow>,
        now: Instant,
        sessionOn: Boolean,
        weeklyOn: Boolean,
    ): List<ResetScheduleTarget> {
        data class Bucket(
            var fireTime: Instant,
            val kinds: MutableSet<WindowKind>,
            val labels: MutableList<String>,
        )

        val buckets = linkedMapOf<Long, Bucket>()
        windows.asSequence()
            .filter { it.style == UsageStyle.GAUGE }
            .filter { window ->
                if (window.kind == WindowKind.SESSION) sessionOn else weeklyOn
            }
            .forEach { window ->
                val reset = window.resetsAt?.takeIf { it.isAfter(now) } ?: return@forEach
                val bucketKey = reset.epochSecond / 60L
                val bucket = buckets.getOrPut(bucketKey) {
                    Bucket(reset, linkedSetOf(), mutableListOf())
                }
                if (reset.isBefore(bucket.fireTime)) bucket.fireTime = reset
                bucket.kinds += window.kind
                bucket.labels += window.label
            }

        return buckets.values
            .sortedBy { it.fireTime }
            .take(PerAgentLimit)
            .map { bucket ->
                ResetScheduleTarget(
                    agentId = agentId,
                    fireTime = bucket.fireTime,
                    kinds = bucket.kinds,
                    labels = bucket.labels.sorted(),
                )
            }
    }

    fun clampGlobal(targets: List<ResetScheduleTarget>): List<ResetScheduleTarget> =
        targets.sortedBy(ResetScheduleTarget::fireTime).take(GlobalLimit)

    data class Reconciliation(
        val add: Set<String>,
        val remove: List<String>,
    )

    fun reconcile(desired: Set<String>, pending: Collection<String>): Reconciliation {
        val ours = pending.filter { it.startsWith(IdPrefix) }
        return Reconciliation(
            add = desired - ours.toSet(),
            remove = ours.filterNot(desired::contains),
        )
    }
}
