package com.deuty.agent.ui.overlay

import com.deuty.agent.core.model.AgentStatus
import com.deuty.agent.core.model.TaskState

/**
 * Every control the user has over a running agent, from the capsule.
 *
 * Requirement 15 (emergency control) is why this interface exists at all: STOP
 * must be reachable without unlocking anything, without finding our app in the
 * launcher, and without the target app being in a state where our UI can open.
 */
interface OverlayCallbacks {
    fun onStop()
    fun onPauseResume()
    fun onRetry()
    fun onSkipStep()
    fun onChoice(label: String)
    fun onReply(text: String)
    fun onOpenAgent()
    fun onToggleExpand()
}

/** What the capsule should look like for a given status. */
data class CapsuleStyle(
    val text: String,
    val colorRes: Int,
    val progressPercent: Int?,
    val pulse: Boolean
) {
    companion object {
        fun from(status: AgentStatus): CapsuleStyle {
            val task = status.task
            return when {
                status.recording -> CapsuleStyle("Recording", com.deuty.agent.R.color.status_recording, null, true)
                status.ask != null -> CapsuleStyle("Needs you", com.deuty.agent.R.color.status_warn, percent(task?.progress), true)
                task == null -> CapsuleStyle("Idle", com.deuty.agent.R.color.status_idle, null, false)
                task.state == TaskState.PAUSED -> CapsuleStyle("Paused", com.deuty.agent.R.color.status_warn, percent(task.progress), false)
                task.state == TaskState.COMPLETED -> CapsuleStyle("Done", com.deuty.agent.R.color.status_ok, 100, false)
                task.state == TaskState.FAILED -> CapsuleStyle("Stopped", com.deuty.agent.R.color.status_error, percent(task.progress), false)
                task.state == TaskState.CANCELLED -> CapsuleStyle("Cancelled", com.deuty.agent.R.color.status_error, percent(task.progress), false)
                task.state == TaskState.RECOVERING -> CapsuleStyle("Recovering", com.deuty.agent.R.color.status_warn, percent(task.progress), true)
                task.state == TaskState.VERIFYING -> CapsuleStyle("Checking", com.deuty.agent.R.color.status_working, percent(task.progress), true)
                task.state == TaskState.WAITING_FOR_USER -> CapsuleStyle("Needs you", com.deuty.agent.R.color.status_warn, percent(task.progress), true)
                else -> CapsuleStyle("Working", com.deuty.agent.R.color.status_working, percent(task.progress), true)
            }
        }

        private fun percent(progress: Float?): Int? = progress?.let { (it * 100).toInt().coerceIn(0, 100) }
    }
}
