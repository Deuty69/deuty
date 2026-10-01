package com.deuty.agent.core.model

/**
 * Every user-facing policy knob in one immutable value.
 *
 * Read once per task into an [com.deuty.agent.agent.port.ExecContext] so the
 * engine never touches SharedPreferences inside an accessibility callback.
 */
data class AgentSettings(
    // --- safety -------------------------------------------------------------
    val confirmMedium: Boolean = true,
    val blockSensitiveScreens: Boolean = true,
    val allowPasswordEntry: Boolean = false,
    val askWhenAmbiguous: Boolean = false,

    // --- limits -------------------------------------------------------------
    val maxActions: Int = 60,
    val maxTaskSeconds: Int = 600,
    val maxRetriesPerStep: Int = 3,

    // --- privacy ------------------------------------------------------------
    val redact: Boolean = true,
    val keepHistory: Boolean = true,
    val allowScreenshots: Boolean = false,
    val historyLimit: Int = 200,

    // --- planner ------------------------------------------------------------
    /** "rule" is the only implementation shipped in V1. See ai/PlannerProvider. */
    val plannerId: String = PlannerIds.RULE_BASED,

    // --- debug / ux ---------------------------------------------------------
    val debugMode: Boolean = false,
    val logEvents: Boolean = false,
    val minimizeOverlayWhenIdle: Boolean = true,
    val showStepListInCapsule: Boolean = true
) {
    fun budget(): TaskBudget = TaskBudget(
        maxActions = maxActions.coerceIn(1, 500),
        maxDurationMs = maxTaskSeconds.coerceIn(10, 7200) * 1000L,
        maxRetriesPerStep = maxRetriesPerStep.coerceIn(0, 10)
    )

    fun confirmAtOrAbove(): RiskLevel = if (confirmMedium) RiskLevel.MEDIUM else RiskLevel.HIGH
}

object PlannerIds {
    const val RULE_BASED = "rule"
    const val LOCAL = "local"
    const val REMOTE = "remote"
    const val NONE = "none"

    fun label(id: String): String = when (id) {
        RULE_BASED -> "Rule-based (offline)"
        LOCAL -> "On-device model (not available in this build)"
        REMOTE -> "Remote model (not available in this build)"
        NONE -> "No planner - workflows and direct commands only"
        else -> id
    }
}
