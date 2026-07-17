package com.ScienceFiction.TokenWatchAndroid.notifications

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import com.ScienceFiction.TokenWatchAndroid.TokenWatchApplication
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Best-effort 15-minute-floor background polling for surprise reset detection. */
class BackgroundRefreshScheduler(context: Context) {
    private val applicationContext = context.applicationContext
    private val scheduler = applicationContext.getSystemService(JobScheduler::class.java)

    fun schedule(earliestBegin: Instant?) {
        val now = Instant.now()
        val floor = now.plus(MinInterval)
        val target = earliestBegin?.takeIf { it.isAfter(floor) } ?: floor
        val latency = Duration.between(now, target).toMillis().coerceAtLeast(MinInterval.toMillis())
        val job = JobInfo.Builder(
            JobId,
            ComponentName(applicationContext, ResetRefreshJobService::class.java),
        )
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setMinimumLatency(latency)
            .setPersisted(true)
            .build()
        scheduler.schedule(job)
    }

    fun cancel() {
        scheduler.cancel(JobId)
    }

    companion object {
        val MinInterval: Duration = Duration.ofMinutes(15)
        private const val JobId = 0x544F4B
    }
}

class ResetRefreshJobService : JobService() {
    private var scope: CoroutineScope? = null
    private var running: Job? = null
    @Volatile private var stopped = false

    override fun onStartJob(params: JobParameters): Boolean {
        stopped = false
        val app = application as TokenWatchApplication
        val jobScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = jobScope
        running = jobScope.launch {
            try {
                app.container.agentStore.performBackgroundRefresh()
                app.container.backgroundRefreshScheduler.schedule(
                    app.container.agentStore.nextResetInstant(),
                )
            } finally {
                if (!stopped) jobFinished(params, false)
                running = null
                scope = null
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        stopped = true
        running?.cancel()
        scope?.cancel()
        running = null
        scope = null
        return true
    }
}
