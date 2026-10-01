package com.deuty.agent.core.model

/**
 * Immutable projections of engine state for the UI.
 *
 * The engine mutates a single [AgentTask] on its own dispatcher. The UI must
 * never see that object - it would be reading a list that another coroutine is
 * appending to, which on a low-end device shows up as a random
 * ConcurrentModificationException during rotation. So every publish is a copy.
 */
data class StepView(
    val title: String,
    val status: StepStatus,
    val note: String?,
    val isCurrent: Boolean
)

data class TaskView(
    val id: String,
    val goal: String,
    val headline: String,
    val state: TaskState,
    val progress: Float,
    val steps: List<StepView>,
    val actionsUsed: Int,
    val maxActions: Int,
    val riskLevel: RiskLevel,
    val elapsedMs: Long,
    val stopReason: String?,
    val resultMessage: String?
)

data class DebugInfo(
    val packageName: String?,
    val windowTitle: String?,
    val nodeCount: Int,
    val interactiveCount: Int,
    val source: ScreenSource,
    val signature: String,
    val captureMs: Long,
    val lastAction: String?,
    val lastResult: String?,
    val lastTier: MatchTier?,
    val taskState: TaskState,
    val retryCount: Int,
    val totalRetries: Int,
    val actionsUsed: Int,
    val budgetActions: Int,
    val loopWarning: String?,
    val engine: String
)

/**
 * Everything the capsule, the chat screen and the inspector render. One object,
 * one flow, no cross-thread state sharing.
 */
data class AgentStatus(
    val accessibilityEnabled: Boolean = false,
    val overlayEnabled: Boolean = false,
    val recording: Boolean = false,
    val recordedSteps: Int = 0,
    val task: TaskView? = null,
    val ask: AskUserRequest? = null,
    val debug: DebugInfo? = null,
    val lastMessage: String? = null,
    val updatedAtMs: Long = 0L
) {
    val busy: Boolean get() = task?.state?.isActive == true

    /** True when the user can do nothing but answer. */
    val needsUser: Boolean get() = ask != null

    val isIdle: Boolean get() = task == null || task.state.isTerminal
}

enum class Role { USER, AGENT, SYSTEM, ERROR }

data class ChatMessage(
    val id: String,
    val role: Role,
    val text: String,
    val atMs: Long,
    val taskId: String? = null,
    /** Present when this message introduced a task, so the UI can show steps. */
    val progressAtSend: Float? = null
)

enum class EventLevel { DEBUG, INFO, WARN, ERROR }

/** Ring-buffer entry for the debug log. Never persisted by default. */
data class AgentEvent(
    val atMs: Long,
    val level: EventLevel,
    val tag: String,
    val message: String
) {
    fun line(): String = "${tag.padEnd(11)} ${level.name.first()} $message"
}
