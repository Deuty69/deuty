package com.deuty.agent.core.model

/**
 * Task lifecycle (requirement 6).
 *
 * Illegal transitions are rejected loudly in debug builds: a state machine that
 * silently accepts nonsense is how an agent ends up tapping in a loop while the
 * UI says "paused".
 */
enum class TaskState {
    CREATED,
    PLANNING,
    RUNNING,
    /** Waiting for the UI to settle (screen change, keyboard, page load). */
    WAITING_FOR_UI,
    /** Action performed, verifying the postcondition. */
    VERIFYING,
    /** Postcondition failed; running a recovery strategy. */
    RECOVERING,
    /** Needs a human: risk gate, ambiguity, or a restriction we will not bypass. */
    WAITING_FOR_USER,
    PAUSED,
    COMPLETED,
    FAILED,
    CANCELLED;

    val isTerminal: Boolean get() = this == COMPLETED || this == FAILED || this == CANCELLED

    /** True while the engine is actively driving the device. */
    val isActive: Boolean get() = this == RUNNING || this == VERIFYING || this == RECOVERING || this == WAITING_FOR_UI

    val isWaiting: Boolean get() = this == WAITING_FOR_USER || this == PAUSED

    fun canTransitionTo(next: TaskState): Boolean = next in ALLOWED.getValue(this)

    companion object {
        val ALLOWED: Map<TaskState, Set<TaskState>> = mapOf(
            CREATED to setOf(PLANNING, RUNNING, FAILED, CANCELLED),
            PLANNING to setOf(RUNNING, WAITING_FOR_USER, FAILED, CANCELLED),
            RUNNING to setOf(
                VERIFYING, WAITING_FOR_UI, WAITING_FOR_USER, RECOVERING,
                PAUSED, COMPLETED, FAILED, CANCELLED
            ),
            WAITING_FOR_UI to setOf(
                RUNNING, VERIFYING, RECOVERING, WAITING_FOR_USER, PAUSED, FAILED, CANCELLED
            ),
            VERIFYING to setOf(
                RUNNING, RECOVERING, WAITING_FOR_UI, WAITING_FOR_USER,
                COMPLETED, FAILED, CANCELLED, PAUSED
            ),
            RECOVERING to setOf(RUNNING, WAITING_FOR_USER, PAUSED, FAILED, CANCELLED),
            WAITING_FOR_USER to setOf(RUNNING, RECOVERING, PAUSED, COMPLETED, FAILED, CANCELLED),
            PAUSED to setOf(RUNNING, RECOVERING, FAILED, CANCELLED),
            // FAILED -> PLANNING/RUNNING is the "Retry" path; COMPLETED and
            // CANCELLED are final and a new task object is created instead.
            FAILED to setOf(PLANNING, RUNNING, CANCELLED),
            COMPLETED to emptySet(),
            CANCELLED to emptySet()
        )
    }
}

/** Where a task came from. Drives defaults (e.g. workflows skip re-planning). */
enum class TaskSource { USER_TEXT, WORKFLOW, QUICK_ACTION, RECOVERY, SANDBOX, SCHEDULED }

/**
 * Retry policy per step. Everything is bounded; nothing retries forever
 * (requirement 20).
 */
data class RetryPolicy(
    val maxAttempts: Int = 3,
    val backoffMs: Long = 350L,
    val backoffMultiplier: Double = 1.8,
    val maxBackoffMs: Long = 3_000L,
    /** Re-read the screen before each retry instead of blindly re-tapping. */
    val reObserveBeforeRetry: Boolean = true
) {
    fun delayBefore(attempt: Int): Long {
        if (attempt <= 0) return 0L
        var delay = backoffMs
        repeat(attempt - 1) { delay = (delay * backoffMultiplier).toLong() }
        return delay.coerceAtMost(maxBackoffMs)
    }

    companion object {
        val DEFAULT = RetryPolicy()
        /** Typing and gestures on slow devices need more settle time. */
        val PATIENT = RetryPolicy(maxAttempts = 4, backoffMs = 600L)
        val NONE = RetryPolicy(maxAttempts = 1)
    }
}

/**
 * Hard limits for a single task. These are the reason a stuck agent costs the
 * user a few seconds of battery instead of an afternoon.
 */
data class TaskBudget(
    val maxActions: Int = 60,
    val maxDurationMs: Long = 10 * 60 * 1000L,
    val maxRetriesPerStep: Int = 3,
    val maxTotalRetries: Int = 12,
    /** Sliding window of screen signatures inspected for cycles. */
    val loopWindow: Int = 16,
    /** Same (state, action) pair seen this many times inside the window => loop. */
    val loopRepeatThreshold: Int = 3,
    /** Global ceiling for steps in one plan; catches pathological planners. */
    val maxSteps: Int = 40
) {
    companion object {
        val DEFAULT = TaskBudget()
        val SANDBOX = TaskBudget(maxActions = 200, maxDurationMs = 60_000L)
    }
}

data class TaskConstraints(
    /** If non-null the agent only acts while one of these packages is foreground. */
    val allowedPackages: Set<String> = emptySet(),
    val forbiddenPackages: Set<String> = emptySet(),
    /** Never let a plan escalate past this. */
    val maxRisk: RiskLevel = RiskLevel.HIGH,
    val confirmAtOrAbove: RiskLevel = RiskLevel.MEDIUM,
    val offlineOnly: Boolean = true,
    val deadlineMs: Long? = null,
    val notes: String? = null
) {
    fun allowsPackage(pkg: String?): Boolean {
        if (pkg == null) return true
        if (pkg in forbiddenPackages) return false
        return allowedPackages.isEmpty() || pkg in allowedPackages
    }
}

enum class StepStatus { PENDING, RUNNING, DONE, SKIPPED, FAILED, WAITING_FOR_USER }

/** One executable unit of a plan: action + how to verify it + how to fail. */
data class TaskStep(
    val id: String,
    val title: String,
    val action: AgentAction,
    val precondition: Expectation? = null,
    val postcondition: Expectation? = null,
    val timeoutMs: Long = 8_000L,
    val retry: RetryPolicy = RetryPolicy.DEFAULT,
    val riskOverride: RiskLevel? = null,
    var status: StepStatus = StepStatus.PENDING,
    var attempts: Int = 0,
    var note: String? = null,
    var lastOutcome: ActionOutcome? = null
) {
    /** Effective risk: explicit override wins, else the action's own default. */
    val risk: RiskLevel get() = riskOverride ?: action.baseRisk

    val isDone: Boolean get() = status == StepStatus.DONE || status == StepStatus.SKIPPED
}

/** One line of the task history, persisted so the user can audit the agent. */
data class StepRecord(
    val stepId: String,
    val title: String,
    val actionLabel: String,
    val outcome: String,
    val status: OutcomeStatus,
    val risk: RiskLevel,
    val tier: MatchTier?,
    val startedAtMs: Long,
    val durationMs: Long,
    val signature: String?,
    val message: String?
)

/**
 * The task representation (requirement 11) - provider-independent, strongly
 * typed, persistable, and completely free of AI assumptions.
 *
 * Single-writer rule: only TaskManager mutates an AgentTask, and only from the
 * engine dispatcher. Everything else observes the immutable snapshots handed
 * out via the status flow.
 */
data class AgentTask(
    val id: String,
    val goal: String,
    val source: TaskSource,
    val createdAtMs: Long,
    val constraints: TaskConstraints = TaskConstraints(),
    val budget: TaskBudget = TaskBudget.DEFAULT,
    val steps: MutableList<TaskStep> = mutableListOf(),
    val context: MutableMap<String, String> = mutableMapOf(),
    val history: MutableList<StepRecord> = mutableListOf(),
    var currentStep: Int = 0,
    var state: TaskState = TaskState.CREATED,
    var riskLevel: RiskLevel = RiskLevel.LOW,
    var startedAtMs: Long? = null,
    var finishedAtMs: Long? = null,
    var updatedAtMs: Long = createdAtMs,
    var actionsUsed: Int = 0,
    var retriesUsed: Int = 0,
    var workflowId: String? = null,
    var resultMessage: String? = null,
    /** Set when the loop detector or a budget guard stopped the task. */
    var stopReason: String? = null
) {
    val isFinished: Boolean get() = state.isTerminal

    val activeStep: TaskStep? get() = steps.getOrNull(currentStep)

    /** 0..1 progress for the capsule. Steps done / steps total. */
    val progress: Float
        get() {
            if (steps.isEmpty()) return if (isFinished) 1f else 0f
            val done = steps.count { it.isDone }
            return (done.toFloat() / steps.size).coerceIn(0f, 1f)
        }

    fun remainingActions(): Int = (budget.maxActions - actionsUsed).coerceAtLeast(0)

    fun remainingRetries(): Int = (budget.maxTotalRetries - retriesUsed).coerceAtLeast(0)

    fun elapsedMs(nowMs: Long): Long {
        val start = startedAtMs ?: createdAtMs
        val end = finishedAtMs ?: nowMs
        return (end - start).coerceAtLeast(0L)
    }

    fun nextStepId(): String = "s${steps.size + 1}"

    fun addStep(
        title: String,
        action: AgentAction,
        precondition: Expectation? = null,
        postcondition: Expectation? = null,
        timeoutMs: Long = 8_000L,
        retry: RetryPolicy = RetryPolicy.DEFAULT,
        riskOverride: RiskLevel? = null
    ): TaskStep {
        val step = TaskStep(
            id = nextStepId(),
            title = title,
            action = action,
            precondition = precondition,
            postcondition = postcondition,
            timeoutMs = timeoutMs,
            retry = retry,
            riskOverride = riskOverride
        )
        steps += step
        return step
    }

    fun touch(nowMs: Long) {
        updatedAtMs = nowMs
    }

    /** Short description for the capsule headline, e.g. "Moving timetable.pdf". */
    fun headline(): String {
        val step = activeStep
        return when {
            step != null && !step.isDone -> step.title
            else -> goal
        }
    }
}
