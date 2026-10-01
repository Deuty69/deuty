package com.deuty.agent.agent.planner

import com.deuty.agent.core.model.AgentSettings
import com.deuty.agent.core.model.ChatMessage
import com.deuty.agent.core.model.RiskLevel
import com.deuty.agent.core.model.TaskSource
import com.deuty.agent.core.model.TaskStep

/**
 * The planner port (requirement 12).
 *
 * A planner turns a natural-language instruction into a *typed list of steps*.
 * It never produces code, shell commands, coordinate macros or anything else the
 * engine would have to "interpret" - the only output the system accepts is the
 * same action vocabulary the recorder produces. That is what allows a rule-based
 * planner, a local model and a remote model to be swapped without touching the
 * engine, and it is why a hallucinating model cannot do anything the deterministic
 * executor has not already decided how to do safely.
 */
interface Planner {
    val id: String
    val displayName: String

    /** False means "don't even ask me" (no model installed, no endpoint, ...). */
    val available: Boolean

    val requiresNetwork: Boolean

    /** Rough cost class, used to order the fallback chain: cheap planners first. */
    val costRank: Int

    suspend fun plan(request: PlanRequest): PlanResult
}

data class WorkflowSummary(val id: String, val name: String, val stepCount: Int)

/** Everything a planner is allowed to know. Deliberately small. */
data class PlanContext(
    val currentPackage: String?,
    val screenSummary: String?,
    val recentMessages: List<ChatMessage> = emptyList(),
    val workflows: List<WorkflowSummary> = emptyList(),
    val settings: AgentSettings = AgentSettings(),
    val taskInProgress: Boolean = false,
    val installedApps: List<String> = emptyList()
)

data class PlanRequest(
    val instruction: String,
    val context: PlanContext = PlanContext(currentPackage = null, screenSummary = null)
)

/** Control utterances are recognised by the planner but never become tasks. */
enum class ControlCommand { STOP, PAUSE, RESUME, RETRY, SKIP, STATUS, WHY_STUCK, HELP }

sealed interface PlanResult {

    data class Plan(
        val goal: String,
        val steps: List<TaskStep>,
        val source: TaskSource = TaskSource.USER_TEXT,
        val notes: String? = null,
        val risk: RiskLevel = RiskLevel.LOW,
        val contextValues: Map<String, String> = emptyMap(),
        val plannerId: String = "unknown"
    ) : PlanResult

    /** The planner needs one answer before it can produce steps. */
    data class Clarify(
        val question: String,
        val options: List<String> = emptyList(),
        val plannerId: String = "unknown"
    ) : PlanResult

    /** Understood, but refused (policy, permissions, or plain impossible). */
    data class Rejected(val reason: String, val plannerId: String = "unknown") : PlanResult

    /** Not understood. The engine falls through to the next planner or explains. */
    data class NotCapable(val reason: String, val plannerId: String = "unknown") : PlanResult

    data class Control(val command: ControlCommand, val plannerId: String = "unknown") : PlanResult
}
