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

    fun cancel(context: Context, key: String) = NotificationManagerCompat.from(context).cancel(idFor(key))

    /** Opens the system settings of the "Waiting for you" channel (sound, vibration, pop-up). */
    fun waitingChannelSettings(context: Context): Intent =
        Intent(android.provider.Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
            .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
            .putExtra(android.provider.Settings.EXTRA_CHANNEL_ID, CHANNEL_WAITING)

    fun show(context: Context, list: TaskList) {
        val manager = NotificationManagerCompat.from(context)
        val update = LiveUpdate.from(list)
        if (update == null) {
            manager.cancel(idFor(list.key))
            return
        }
        if (!canNotify(context)) return
        try {
            manager.notify(idFor(list.key), build(context, update, list.key))
        } catch (e: SecurityException) {
            // The permission was taken away between the check and the call.
        }
    }

    private fun build(context: Context, update: LiveUpdate, key: String): android.app.Notification {
        val id = idFor(key)
        val counts = context.resources.getQuantityString(
            if (update.steps) R.plurals.step_counts else R.plurals.notification_counts,
            update.total,
            update.done,
            update.total,
        )
        val text = when (update.status) {
            LiveUpdate.Status.Done -> context.getString(R.string.state_done)
            LiveUpdate.Status.Waiting -> context.getString(R.string.state_waiting)
            LiveUpdate.Status.Idle -> context.getString(R.string.state_idle)
            LiveUpdate.Status.Tasks -> update.currentTask?.let { context.getString(R.string.notification_now, it) } ?: counts
        }
        val next = update.nextTask
            ?.takeIf { update.status != LiveUpdate.Status.Done }
            ?.let { context.getString(R.string.notification_next, it) }
        // Expanded: what Claude is on, the task after it and the count.
        val expanded = listOfNotNull(text, next, counts.takeIf { it != text }).joinToString("\n")
        val open = PendingIntent.getActivity(
            context,
            id,
            ClaudeApp.openIntent(context, update.openUrl).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val channel = if (update.status == LiveUpdate.Status.Waiting) CHANNEL_WAITING else CHANNEL_PROGRESS
        val builder = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_stat_clauder)
            .setContentTitle(update.title)
            .setContentText(text)
            .setContentIntent(open)
            .setOnlyAlertOnce(update.status != LiveUpdate.Status.Waiting)
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

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
            val style = NotificationCompat.ProgressStyle()
                .setStyledByProgress(true)
                .setProgressSegments(List(update.segmentCount) { NotificationCompat.ProgressStyle.Segment(LiveUpdate.SEGMENT_LENGTH) })
                .setProgress(update.progress)
            update.pointPosition?.let { style.addProgressPoint(NotificationCompat.ProgressStyle.Point(it)) }
            builder.setStyle(style).setSubText(update.group?.let { "$it · $counts" } ?: counts)
            if (next != null) builder.setContentText("$text\n$next")
        } else {
            update.group?.let { builder.setSubText(it) }
            builder.setProgress(update.max, update.progress, false)
                .setStyle(NotificationCompat.BigTextStyle().bigText(expanded))
        }
        return builder.build()
    }
}
