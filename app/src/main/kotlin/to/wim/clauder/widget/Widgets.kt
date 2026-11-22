package to.wim.clauder.widget

import android.content.Context
import androidx.glance.appwidget.updateAll

/** Redraws the Tasks widget after the task lists or the theme changed. */
object Widgets {
    suspend fun updateTasks(context: Context) = TasksWidget().updateAll(context)
}
