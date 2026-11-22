package to.wim.clauder.tile

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import to.wim.clauder.ClauderApp
import to.wim.clauder.MainActivity
import to.wim.clauder.R
import to.wim.clauder.tasks.Summary
import to.wim.clauder.tasks.TaskNotifier

/** The Quick Settings tile: what Claude is doing, in one line. Tapping it opens Clauder. */
class ClauderTileService : TileService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onStartListening() {
        super.onStartListening()
        val app = application as ClauderApp
        scope.launch {
            val summary = Summary.from(app.store.cachedSessions(), app.store.muted.first(), System.currentTimeMillis())
            val tile = qsTile ?: return@launch
            tile.label = getString(R.string.app_name)
            tile.subtitle = TaskNotifier.summaryText(this@ClauderTileService, summary)
            tile.state = if (summary.isEmpty) Tile.STATE_INACTIVE else Tile.STATE_ACTIVE
            tile.updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE))
        } else {
            // The Intent variant is deprecated (it throws on Android 14+), so older phones open
            // Clauder without collapsing the shade first.
            unlockAndRun { startActivity(intent) }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        /** Asks the system to redraw the tile after a push, when it is in Quick Settings. */
        fun refresh(context: Context) {
            try {
                requestListeningState(context, ComponentName(context, ClauderTileService::class.java))
            } catch (e: IllegalArgumentException) {
                // The tile isn't added.
            } catch (e: SecurityException) {
                // Not allowed right now, for example while the phone is locked.
            }
        }
    }
}
