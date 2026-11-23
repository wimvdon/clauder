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
    private const val CHANNEL_RUNS = "runs"
    private const val CHANNEL_REPLIES = "chat_replies"

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
                NotificationChannel(
                    CHANNEL_RUNS,
                    context.getString(R.string.channel_runs),
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply { description = context.getString(R.string.channel_runs_description) },
                NotificationChannel(
                    CHANNEL_REPLIES,
                    context.getString(R.string.channel_replies),
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply { description = context.getString(R.string.channel_replies_description) },
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
    fun apply(context: Context, list: TaskList, board: List<TaskList>, muted: Set<String>, prefs: NotifyPrefs, alert: Boolean = true) {
        val now = System.currentTimeMillis()
        if (NotificationPlan.individual(list, muted, prefs, now)) show(context, list, alert, prefs) else cancel(context, list.key)
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
            .setShortCriticalText(chipText(context, Chip.forSummary(summary)))
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
            // Collapsed is all a Live Update shows: the projects by name, in the blocks' order, and
            // the steps done across them. A single session says what it is doing instead, which is
            // also what a watch shows when the Live Update is opened there.
            notification.setStyle(style)
                .setContentText(if (summary.active.size == 1) lines.first() else summary.names.joinToString(" · "))
                .setSubText(context.resources.getQuantityString(R.plurals.step_counts, summary.total, summary.done, summary.total))
        }
        try {
            manager.notify(SUMMARY_ID, notification.build())
        } catch (e: SecurityException) {
            // The permission was taken away between the check and the call.
        }
    }

    /** The status bar chip, which is also what a watch shows of the Live Update in its tray. */
    fun chipText(context: Context, chip: Chip): String = when (chip) {
        is Chip.Waiting -> context.resources.getQuantityString(R.plurals.chip_waiting, chip.count, chip.count)
        is Chip.Working -> context.resources.getQuantityString(R.plurals.chip_working, chip.count, chip.count)
        Chip.WaitsForYou -> context.getString(R.string.chip_waits_for_you)
        is Chip.Steps -> context.getString(R.string.notification_short, chip.done, chip.total)
        Chip.Done -> context.getString(R.string.state_done)
    }

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
        LiveUpdate.Status.Done -> update.headline ?: context.getString(R.string.state_done)
        LiveUpdate.Status.Waiting -> if (update.error) {
            update.need?.let { context.getString(R.string.state_error_need, it) } ?: context.getString(R.string.state_error)
        } else {
            (update.need ?: update.headline)?.let { context.getString(R.string.state_waiting_need, it) }
                ?: context.getString(R.string.state_waiting)
        }
        LiveUpdate.Status.Idle -> update.headline ?: context.getString(R.string.state_idle)
        LiveUpdate.Status.Tasks -> update.headline
            ?: update.currentTask?.let { context.getString(R.string.notification_now, it) }
            ?: update.holding?.let { holdingText(context, update) }
            ?: counts
    }

    /** "Waiting on CI for PR #22", "Back at 20:00", "3 helpers working". */
    fun holdingText(context: Context, update: LiveUpdate): String? = when (update.holding) {
        LiveUpdate.Holding.Ci -> context.getString(R.string.holding_ci, update.prNumber ?: 0)
        LiveUpdate.Holding.Run -> context.getString(R.string.run_running, update.runName.orEmpty())
        LiveUpdate.Holding.Reminder -> update.wakeAtMillis?.let {
            context.getString(R.string.holding_reminder, android.text.format.DateFormat.getTimeFormat(context).format(java.util.Date(it)))
        }
        LiveUpdate.Holding.Helpers -> context.resources.getQuantityString(R.plurals.holding_helpers, update.helpers, update.helpers)
        null -> null
    }

    /** "Editing TaskList.kt", "Shell: Run the unit tests". */
    fun activityText(context: Context, doing: Doing): String = context.getString(
        when (doing.kind) {
            Doing.Kind.Shell -> R.string.activity_shell
            Doing.Kind.Edit -> R.string.activity_edit
            Doing.Kind.Read -> R.string.activity_read
            Doing.Kind.Search -> R.string.activity_search
            Doing.Kind.Fetch -> R.string.activity_fetch
            Doing.Kind.WebSearch -> R.string.activity_web_search
            Doing.Kind.Helper -> R.string.activity_helper
            Doing.Kind.Other -> R.string.activity_tool
            Doing.Kind.Blocked -> if (doing.detail == null) R.string.activity_blocked_plain else R.string.activity_blocked
            Doing.Kind.Compacting -> R.string.activity_compacting
            Doing.Kind.PushFailed -> R.string.activity_push_failed
        },
        doing.subject,
    )

    /** "Opus 5.5 · high effort · Plan mode": the model and how it works, when the hooks said. */
    fun modelText(context: Context, list: TaskList): String? = listOfNotNull(
        list.model?.let { ModelName.readable(it) },
        list.effort?.let { context.getString(R.string.model_effort, it) },
        list.mode?.takeIf { it == "plan" }?.let { context.getString(R.string.model_plan_mode) },
    ).takeIf { it.isNotEmpty() }?.joinToString(" · ")

    /** "12 min · 3 files · +120 −30": how long since the person's message, and what changed. */
    fun receiptText(context: Context, receipt: WorkReceipt): String? {
        val res = context.resources
        val time = receipt.minutes?.let { minutes ->
            when {
                minutes < 1 -> context.getString(R.string.receipt_under_minute)
                minutes < 60 -> res.getQuantityString(R.plurals.receipt_minutes, minutes.toInt(), minutes.toInt())
                else -> context.getString(R.string.receipt_hours, (minutes / 60).toInt(), (minutes % 60).toInt())
            }
        }
        val files = receipt.files.takeIf { it > 0 }?.let { res.getQuantityString(R.plurals.receipt_files, it, it) }
        val lines = context.getString(R.string.receipt_lines, receipt.added, receipt.removed).takeIf { receipt.files > 0 }
        return listOfNotNull(time, files, lines).takeIf { it.isNotEmpty() }?.joinToString(" · ")
    }

    /** The line for a workflow run Claude started, such as "Release passed". */
    fun runText(context: Context, run: WorkflowRun): String = context.getString(
        when (run.state) {
            CiState.Running -> R.string.run_running
            CiState.Passed -> R.string.run_passed
            CiState.Failed -> R.string.run_failed
        },
        run.name,
    )

    /** An alert that a workflow run Claude started (such as a release) passed or failed. Tapping
     * it opens the run on GitHub. One per session: a newer result replaces it. */
    fun showRunFinished(context: Context, list: TaskList, run: WorkflowRun) {
        if (!canNotify(context)) return
        val id = idFor(list.key) * 31 + 5
        val open = linkIntent(context, id, run.url)
        val notification = NotificationCompat.Builder(context, CHANNEL_RUNS)
            .setSmallIcon(R.drawable.ic_stat_clauder)
            .setContentTitle(runText(context, run))
            .setContentText(list.title)
            .setSubText(list.group)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(id, notification)
        } catch (e: SecurityException) {
            // The permission was taken away between the check and the call.
        }
    }

    private fun chatId(projectId: String): Int = "chat:$projectId".hashCode()

    /** Claude replied in a project chat after your latest message there: the project's name and the
     * reply's start. One per project; a later reply replaces it, quietly unless [ChatReply.sound]. */
    fun showChatReply(context: Context, reply: to.wim.clauder.claudeai.ChatReply) {
        if (!canNotify(context)) return
        val id = chatId(reply.projectId)
        val notification = NotificationCompat.Builder(context, CHANNEL_REPLIES)
            .setSmallIcon(R.drawable.ic_stat_clauder)
            .setContentTitle(reply.projectName)
            .setContentText(reply.text)
            .setSubText(context.getString(R.string.chat_reply_label))
            .setStyle(NotificationCompat.BigTextStyle().bigText(reply.text))
            .setContentIntent(linkIntent(context, id, to.wim.clauder.claudeai.ProjectChat.url(reply.projectId)))
            .setOnlyAlertOnce(!reply.sound)
            .setSilent(!reply.sound)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(id, notification)
        } catch (e: SecurityException) {
            // The permission was taken away between the check and the call.
        }
    }

    /** Takes a project chat's reply alert away, once the chat was read in the Claude app. */
    fun cancelChatReply(context: Context, projectId: String) {
        NotificationManagerCompat.from(context).cancel(chatId(projectId))
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
        // A plain alert the person swiped away stays away until there is something new to say.
        if (!alert && update.plain && !isShowing(context, idFor(list.key))) return
        try {
            manager.notify(idFor(list.key), build(context, update, list.key, alert, NotificationPlan.alerts(update.status, prefs)))
            refreshBundles(context)
        } catch (e: SecurityException) {
            // The permission was taken away between the check and the call.
        }
    }

    private fun isShowing(context: Context, id: Int): Boolean =
        context.getSystemService(NotificationManager::class.java).activeNotifications.any { it.id == id && it.packageName == context.packageName }

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
        // While Claude is on a step, what it also waits on (CI, a reminder) or else the tool it runs
        // goes on its own line; a finished session shows what Claude changed there.
        val holding = (
            holdingText(context, update)
                ?: update.doing?.let { activityText(context, it) }
                ?: update.receipt?.let { receiptText(context, it) }
            )?.takeIf { it != text }
        val next = update.nextTask
            ?.takeIf { update.status != LiveUpdate.Status.Done }
            ?.let { context.getString(R.string.notification_next, it) }
        // Expanded: what Claude is on, what it waits on, the task after it and the count.
        val expanded = listOfNotNull(text, holding, next, counts.takeIf { it != text && update.total > 0 }).joinToString("\n")
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
            .setCategory(if (update.plain) NotificationCompat.CATEGORY_REMINDER else NotificationCompat.CATEGORY_PROGRESS)
            .setRequestPromotedOngoing(update.requestPromotion)
        if (!update.plain) {
            builder.setShortCriticalText(chipText(context, Chip.forSession(update)))
        }
        update.group?.let { builder.setSortKey(it) }
        update.bundle?.let { builder.setGroup(it) }
        update.timeoutMs?.let { builder.setTimeoutAfter(it) }
        val waiting = update.status == LiveUpdate.Status.Waiting
        // A thread that waits for you can be marked resolved here: resolving it in the Claude app
        // doesn't reach Clauder.
        // A thread only claude.ai knows has no session on the Worker to mark.
        if (waiting && !key.startsWith(TaskList.CLAUDE_AI_KEY_PREFIX)) {
            val resolve = PendingIntent.getBroadcast(
                context,
                id * 31 + 3,
                Intent(context, ResolveReceiver::class.java).putExtra(ResolveReceiver.EXTRA_KEY, key),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            builder.addAction(R.drawable.ic_check_circle, context.getString(R.string.action_resolve), resolve)
        }
        val mute = PendingIntent.getBroadcast(
            context,
            id,
            Intent(context, MuteReceiver::class.java).putExtra(MuteReceiver.EXTRA_KEY, key),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        // A plain alert is swiped away instead of muted.
        val muteButton = update.status != LiveUpdate.Status.Done && !update.plain
        if (muteButton && !waiting) builder.addAction(R.drawable.ic_notifications_off, context.getString(R.string.action_mute), mute)
        // Up to two more buttons: the page Claude attached, then its pull request.
        update.linkUrl?.let { url ->
            val title = update.linkTitle?.let { context.getString(R.string.action_open_named, it) } ?: context.getString(R.string.action_open_link)
            builder.addAction(R.drawable.ic_open_in_new, title, linkIntent(context, id * 31 + 1, url))
        }
        update.prUrl?.let { url ->
            builder.addAction(R.drawable.ic_open_in_new, context.getString(R.string.action_open_pr, update.prNumber ?: 0), linkIntent(context, id * 31 + 2, url))
        }
        if (muteButton && waiting) builder.addAction(R.drawable.ic_notifications_off, context.getString(R.string.action_mute), mute)

        if (update.plain) {
            // Not a Live Update: no progress bar and no status bar chip, just what Claude needs.
            builder.setStyle(NotificationCompat.BigTextStyle().bigText(expanded))
            update.group?.let { builder.setSubText(it) }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
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
