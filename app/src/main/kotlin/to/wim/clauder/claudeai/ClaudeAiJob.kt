package to.wim.clauder.claudeai

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import kotlinx.coroutines.launch
import to.wim.clauder.ClauderApp

/**
 * Reads claude.ai in the background while signed in: about every 15 minutes (the shortest period
 * Android allows), and a minute later while a project chat waits for Claude's reply. The project
 * chat's Claude sends no hook events, so without this a reply there would only show up when the app
 * opens or a push comes in. Android's own JobScheduler, so no new library and no new permission:
 * there is no network condition (that would need one), a read without network just fails.
 */
class ClaudeAiJob : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        val app = application as ClauderApp
        app.scope.launch {
            try {
                app.syncClaudeAi()
            } finally {
                jobFinished(params, false)
            }
        }
        return true
    }

    /** Android stopped the job early; the next period reads again. */
    override fun onStopJob(params: JobParameters): Boolean = false

    companion object {
        // Far above the ids WorkManager (used inside Glance) counts up from 0, so they never clash.
        private const val PERIODIC_ID = 0x7c1a0001
        private const val SOON_ID = 0x7c1a0002
        private const val PERIOD_MS = 15 * 60 * 1000L
        private const val SOON_MS = 60 * 1000L

        /** Starts the 15-minute reads, unless they already run. */
        fun schedule(context: Context) {
            val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
            if (scheduler.getPendingJob(PERIODIC_ID) != null) return
            scheduler.schedule(
                JobInfo.Builder(PERIODIC_ID, ComponentName(context, ClaudeAiJob::class.java))
                    .setPeriodic(PERIOD_MS)
                    .build(),
            )
        }

        /** One more read in about a minute, while a project chat waits for Claude's reply. */
        fun scheduleSoon(context: Context) {
            val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
            scheduler.schedule(
                JobInfo.Builder(SOON_ID, ComponentName(context, ClaudeAiJob::class.java))
                    .setMinimumLatency(SOON_MS)
                    .setOverrideDeadline(3 * SOON_MS)
                    .build(),
            )
        }

        fun cancel(context: Context) {
            val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
            scheduler.cancel(PERIODIC_ID)
            scheduler.cancel(SOON_ID)
        }
    }
}
