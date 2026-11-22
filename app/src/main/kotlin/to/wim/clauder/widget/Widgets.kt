package to.wim.clauder.widget

import android.content.Context
import androidx.glance.appwidget.updateAll

/** Redraws the widgets after the task lists, the pinned shortcuts or the theme changed. */
object Widgets {
    suspend fun updateTasks(context: Context) = TasksWidget().updateAll(context)

    suspend fun updateShortcuts(context: Context) = ShortcutsWidget().updateAll(context)

    suspend fun updateAll(context: Context) {
        updateTasks(context)
        updateShortcuts(context)
    }
}
