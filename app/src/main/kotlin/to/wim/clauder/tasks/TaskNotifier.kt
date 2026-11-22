package to.wim.clauder.tasks

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import to.wim.clauder.R
import to.wim.clauder.links.ClaudeApp

/** Shows one notification per Claude session: a Live Update on Android 16, an ongoing progress
 * notification on older versions. */
object TaskNotifier {
    private const val CHANNEL_PROGRESS = "task_progress"
    private const val CHANNEL_WAITING = "waiting"

    fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannels(
            listOf(
                NotificationChannel(
                    CHANNEL_PROGRESS,
                    context.getString(R.string.channel_progress),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply { description = context.getString(R.string.channel_progress_description) },
                NotificationChannel(
                    CHANNEL_WAITING,
                    context.getString(R.string.channel_waiting),
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply { description = context.getString(R.string.channel_waiting_description) },
            ),
        )
    }

    /** Android 13 and later ask for the notification permission; Android 12 has a plain
     * on/off switch per app. */
    fun canNotify(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else {
            NotificationManagerCompat.from(context).areNotificationsEnabled()
        }

    private fun idFor(key: String): Int = key.hashCode()

    fun cancel(context: Context, key: String) {
        NotificationManagerCompat.from(context).cancel(idFor(key))
        refreshBundles(context)
    }

    /** Opens the system settings of the "Waiting for you" channel (sound, vibration, pop-up). */
    fun waitingChannelSettings(context: Context): Intent =
        Intent(android.provider.Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
            .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
            .putExtra(android.provider.Settings.EXTRA_CHANNEL_ID, CHANNEL_WAITING)

    private const val SUMMARY_ID = 0x5c1a0de

    /**
     * Shows what [list] changed, and keeps the summary up to date in summary mode. [board] is every
     * session the phone knows, [list] included.
     */
    fun apply(context: Context, list: TaskList, board: List<TaskList>, muted: Set<String>, prefs: NotifyPrefs) {
        val now = System.currentTimeMillis()
        if (NotificationPlan.individual(list, muted, prefs, now)) show(context, list, prefs = prefs) else cancel(context, list.key)
        showSummary(context, board, muted, prefs.summary, now)
    }

    /** Redraws every notification after a notification setting changed, without alerting again. */
    fun applyAll(context: Context, board: List<TaskList>, muted: Set<String>, prefs: NotifyPrefs) {
        val now = System.currentTimeMillis()
        board.forEach {
            if (NotificationPlan.individual(it, muted, prefs, now)) show(context, it, alert = false, prefs = prefs) else cancel(context, it.key)
        }
        showSummary(context, board, muted, prefs.summary, now)
    }

    /** One ongoing notification for everything that is working or waiting. It never alerts: the
     * sessions that wait for you are posted on their own, on the "Waiting for you" channel. */
    private fun showSummary(context: Context, board: List<TaskList>, muted: Set<String>, summaryMode: Boolean, now: Long) {
        val manager = NotificationManagerCompat.from(context)
        val summary = Summary.from(board, muted, now)
        if (!summaryMode || summary.isEmpty || !canNotify(context)) {
            manager.cancel(SUMMARY_ID)
            return
        }
        val title = summaryText(context, summary)
        val lines = summary.active.map { sessionLine(context, it, now) }
        val open = PendingIntent.getActivity(
            context,
            SUMMARY_ID,
            Intent(context, to.wim.clauder.MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_PROGRESS)
            .setSmallIcon(R.drawable.ic_stat_clauder)
            .setContentTitle(title)
            .setContentText(lines.first())
            .setStyle(NotificationCompat.BigTextStyle().bigText(lines.joinToString("\n")))
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setShortCriticalText(summaryChip(context, summary))
            .setRequestPromotedOngoing(true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
            // One block per session, coloured by state, with a dot at how far each one is. The
            // bar is full, so every block keeps its colour.
            val bar = SummaryBar.from(summary, now)
            val style = NotificationCompat.ProgressStyle()
                .setStyledByProgress(false)
                .setProgressSegments(bar.blocks.map { NotificationCompat.ProgressStyle.Segment(SummaryBar.BLOCK).setColor(blockColor(context, it)) })
                .setProgressPoints(bar.points.map { NotificationCompat.ProgressStyle.Point(it).setColor(context.getColor(R.color.summary_point)) })
                .setProgress(bar.max)
            // Collapsed is all a Live Update shows: the threads by name, in the blocks' order, and
            // the steps done across them.
            notification.setStyle(style)
                .setContentText(summary.names.joinToString(" · "))
                .setSubText(context.resources.getQuantityString(R.plurals.step_counts, summary.total, summary.done, summary.total))
        }
        try {
            manager.notify(SUMMARY_ID, notification.build())
        } catch (e: SecurityException) {
            // The permission was taken away between the check and the call.
        }
    }

    /** The status bar chip: how many sessions are active, and how many wait for you ("3 · 1?"). */
    fun summaryChip(context: Context, summary: Summary): String =
        if (summary.waiting > 0) context.getString(R.string.summary_chip_waiting, summary.active.size, summary.waiting)
        else summary.active.size.toString()

    private fun blockColor(context: Context, kind: SummaryBar.Kind): Int = context.getColor(
        when (kind) {
            SummaryBar.Kind.Waiting -> R.color.summary_waiting
            SummaryBar.Kind.Working -> R.color.summary_working
            SummaryBar.Kind.Holding -> R.color.summary_holding
        },
    )

    /** "2 working · 1 waiting for you". */
    fun summaryText(context: Context, summary: Summary): String {
        val res = context.resources
        val parts = listOfNotNull(
            summary.working.takeIf { it > 0 }?.let { res.getQuantityString(R.plurals.summary_working, it, it) },
            summary.waiting.takeIf { it > 0 }?.let { res.getQuantityString(R.plurals.summary_waiting, it, it) },
        )
        return parts.ifEmpty { listOf(context.getString(R.string.summary_nothing)) }.joinToString(" · ")
    }

    /** "Title: Now: step" or "Title: Waiting for you". */
    private fun sessionLine(context: Context, list: TaskList, now: Long): String {
        val update = LiveUpdate.from(list, now) ?: return list.title
        return context.getString(R.string.summary_line, update.title, statusText(context, update, counts(context, update)))
    }

    private fun counts(context: Context, update: LiveUpdate): String = context.resources.getQuantityString(
        if (update.steps) R.plurals.step_counts else R.plurals.notification_counts,
        update.total,
        update.done,
        update.total,
    )

    /** What a session's notification says it is doing now. */
    private fun statusText(context: Context, update: LiveUpdate, counts: String): String = when (update.status) {
        LiveUpdate.Status.Done -> context.getString(R.string.state_done)
        LiveUpdate.Status.Waiting ->
            update.need?.let { context.getString(R.string.state_waiting_need, it) } ?: context.getString(R.string.state_waiting)
        LiveUpdate.Status.Idle -> context.getString(R.string.state_idle)
        LiveUpdate.Status.Tasks -> update.currentTask?.let { context.getString(R.string.notification_now, it) }
            ?: update.holding?.let { holdingText(context, update) }
            ?: counts
    }

    /** "Waiting on CI for PR #22", "Back at 20:00", "3 helpers working". */
    fun holdingText(context: Context, update: LiveUpdate): String? = when (update.holding) {
        LiveUpdate.Holding.Ci -> context.getString(R.string.holding_ci, update.prNumber ?: 0)
        LiveUpdate.Holding.Reminder -> update.wakeAtMillis?.let {
            context.getString(R.string.holding_reminder, android.text.format.DateFormat.getTimeFormat(context).format(java.util.Date(it)))
        }
        LiveUpdate.Holding.Helpers -> context.resources.getQuantityString(R.plurals.holding_helpers, update.helpers, update.helpers)
        null -> null
    }

    fun show(context: Context, list: TaskList, alert: Boolean = true, prefs: NotifyPrefs = NotifyPrefs()) {
        val manager = NotificationManagerCompat.from(context)
        val update = LiveUpdate.from(list, summaryMode = prefs.summary)
        if (update == null) {
            manager.cancel(idFor(list.key))
            refreshBundles(context)
            return
        }
        if (!canNotify(context)) return
        try {
            manager.notify(idFor(list.key), build(context, update, list.key, alert, NotificationPlan.alerts(update.status, prefs)))
            refreshBundles(context)
        } catch (e: SecurityException) {
            // The permission was taken away between the check and the call.
        }
    }

    private fun linkIntent(context: Context, requestCode: Int, url: String): PendingIntent = PendingIntent.getActivity(
        context,
        requestCode,
        ClaudeApp.openIntent(context, url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun summaryId(bundle: String): Int = "summary:$bundle".hashCode()

    /** Keeps one summary per bundle of idle and finished sessions, so the shade shows each
     * project's inactive sessions as one group that folds open and shut. A summary goes away
     * with the last session in it. */
    private fun refreshBundles(context: Context) {
        val manager = NotificationManagerCompat.from(context)
        val active = context.getSystemService(NotificationManager::class.java).activeNotifications
        val children = active
            .filter { it.packageName == context.packageName && it.notification.group?.startsWith(LiveUpdate.BUNDLE_PREFIX) == true }
            .filter { it.notification.flags and android.app.Notification.FLAG_GROUP_SUMMARY == 0 }
            .groupBy { it.notification.group }
        active
            .filter { it.notification.flags and android.app.Notification.FLAG_GROUP_SUMMARY != 0 && it.notification.group !in children }
            .forEach { manager.cancel(it.id) }
        if (!canNotify(context)) return
        children.forEach { (bundle, members) ->
            val name = bundle.removePrefix(LiveUpdate.BUNDLE_PREFIX).ifEmpty { context.getString(R.string.app_name) }
            val text = context.resources.getQuantityString(R.plurals.notification_inactive, members.size, members.size)
            val summary = NotificationCompat.Builder(context, CHANNEL_PROGRESS)
                .setSmallIcon(R.drawable.ic_stat_clauder)
                .setContentTitle(name)
                .setContentText(text)
                .setSubText(name)
                .setGroup(bundle)
                .setGroupSummary(true)
                .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
                .setSortKey(name)
                .setShowWhen(false)
                .setSilent(true)
                .build()
            try {
                manager.notify(summaryId(bundle), summary)
            } catch (e: SecurityException) {
                return
            }
        }
    }

    private fun build(context: Context, update: LiveUpdate, key: String, alert: Boolean, alerting: Boolean): android.app.Notification {
        val id = idFor(key)
        val counts = counts(context, update)
        val text = statusText(context, update, counts)
        // While Claude is on a step, what it also waits on (CI, a reminder) goes on its own line.
        val holding = holdingText(context, update)?.takeIf { it != text }
        val next = update.nextTask
            ?.takeIf { update.status != LiveUpdate.Status.Done }
            ?.let { context.getString(R.string.notification_next, it) }
        // Expanded: what Claude is on, what it waits on, the task after it and the count.
        val expanded = listOfNotNull(text, holding, next, counts.takeIf { it != text }).joinToString("\n")
        val open = PendingIntent.getActivity(
            context,
            id,
            ClaudeApp.openIntent(context, update.openUrl).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val channel = if (alerting) CHANNEL_WAITING else CHANNEL_PROGRESS
        val builder = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_stat_clauder)
            .setContentTitle(update.title)
            .setContentText(text)
            .setContentIntent(open)
            .setOnlyAlertOnce(!alert || !alerting)
            .setSilent(update.silent)
            .setOngoing(update.ongoing)
            .setAutoCancel(!update.ongoing)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setShortCriticalText(
                if (update.status == LiveUpdate.Status.Done) context.getString(R.string.state_done)
                else context.getString(R.string.notification_short, update.done, update.total),
            )
            .setRequestPromotedOngoing(update.requestPromotion)
        update.group?.let { builder.setSortKey(it) }
        update.bundle?.let { builder.setGroup(it) }
        update.timeoutMs?.let { builder.setTimeoutAfter(it) }
        if (update.status != LiveUpdate.Status.Done) {
            val mute = PendingIntent.getBroadcast(
                context,
                id,
                Intent(context, MuteReceiver::class.java).putExtra(MuteReceiver.EXTRA_KEY, key),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            builder.addAction(R.drawable.ic_notifications_off, context.getString(R.string.action_mute), mute)
        }
        // Up to two more buttons: the page Claude attached, then its pull request.
        update.linkUrl?.let { url ->
            val title = update.linkTitle?.let { context.getString(R.string.action_open_named, it) } ?: context.getString(R.string.action_open_link)
            builder.addAction(R.drawable.ic_open_in_new, title, linkIntent(context, id * 31 + 1, url))
        }
        update.prUrl?.let { url ->
            builder.addAction(R.drawable.ic_open_in_new, context.getString(R.string.action_open_pr, update.prNumber ?: 0), linkIntent(context, id * 31 + 2, url))
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
            val style = NotificationCompat.ProgressStyle()
                .setStyledByProgress(true)
                .setProgressSegments(List(update.segmentCount) { NotificationCompat.ProgressStyle.Segment(LiveUpdate.SEGMENT_LENGTH) })
                .setProgress(update.progress)
            update.pointPosition?.let { style.addProgressPoint(NotificationCompat.ProgressStyle.Point(it)) }
            // A Live Update can't be expanded, so what matters fits on the one collapsed line: what
            // Claude is on (or asks), then what it also waits on.
            builder.setStyle(style).setSubText(update.group?.let { "$it · $counts" } ?: counts)
                .setContentText(listOfNotNull(text, holding).joinToString(" · "))
        } else {
            update.group?.let { builder.setSubText(it) }
            builder.setProgress(update.max, update.progress, false)
                .setStyle(NotificationCompat.BigTextStyle().bigText(expanded))
        }
        return builder.build()
    }
}
