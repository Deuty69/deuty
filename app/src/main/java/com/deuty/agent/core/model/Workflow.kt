package com.deuty.agent.core.model

enum class WorkflowSource { RECORDED, HANDWRITTEN, IMPORTED, LEARNED }

/**
 * A saved, reusable task template (requirements 9 and 10).
 *
 * A workflow is just a list of [TaskStep]s: recorded interactions are
 * normalised into the same semantic action vocabulary the planner emits, so
 * replay goes through exactly the same executor, verifier and risk gate as a
 * freshly planned task. That is what makes replay safe - a recording is never
 * a macro of raw coordinates.
 */
data class Workflow(
    val id: String,
    val name: String,
    val steps: List<TaskStep>,
    val createdAtMs: Long,
    val updatedAtMs: Long,
    val source: WorkflowSource = WorkflowSource.RECORDED,
    val lastRunAtMs: Long? = null,
    val runCount: Int = 0,
    val successCount: Int = 0,
    val packages: List<String> = emptyList(),
    val notes: String? = null,
    val tags: List<String> = emptyList(),
    val riskLevel: RiskLevel = RiskLevel.LOW
) {
    val stepCount: Int get() = steps.size

    /** True when any step could only be targeted by coordinates. */
    val hasBrittleSteps: Boolean
        get() = steps.any { step ->
            val query = step.action.targetQuery()
            query != null && (query.isCoordinateOnly || query.bounds != null)
        }

    fun highestRisk(): RiskLevel =
        steps.fold(RiskLevel.LOW) { acc, s -> if (s.risk.rank > acc.rank) s.risk else acc }

    fun describe(): String = buildString {
        append(name).append(" (").append(steps.size).append(" steps)\n")
        steps.forEachIndexed { i, s ->
            append("  ").append(i + 1).append(". ").append(s.title)
            append("  [").append(s.action.label).append("]")
            if (s.risk != RiskLevel.LOW) append(" <").append(s.risk.name).append(">")
            append('\n')
        }
    }
}
