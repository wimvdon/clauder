package to.wim.clauder.ui.tasks

import androidx.annotation.StringRes
import to.wim.clauder.R
import to.wim.clauder.tasks.SessionState
import to.wim.clauder.tasks.TaskList

/** The word for a session's state, shared by the Tasks screen and the widget. */
@StringRes
fun stateLabelRes(list: TaskList): Int = when (list.state) {
    SessionState.Running -> R.string.state_running
    SessionState.Idle -> R.string.state_idle
    SessionState.Waiting -> R.string.state_waiting
    SessionState.Done -> R.string.state_done
    SessionState.Ended -> if (list.endedEarly) R.string.state_ended_early else R.string.state_ended
}
