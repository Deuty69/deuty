package com.deuty.agent

import com.deuty.agent.agent.ElementResolver
import com.deuty.agent.agent.RecoveryManager
import com.deuty.agent.agent.RiskManager
import com.deuty.agent.agent.TaskRunner
import com.deuty.agent.agent.TaskRunnerHost
import com.deuty.agent.agent.executor.ActionExecutor
import com.deuty.agent.agent.executor.AgentAction
import com.deuty.agent.agent.executor.ExpectationEvaluator
import com.deuty.agent.core.model.ActionOutcome
import com.deuty.agent.core.model.AgentSettings
import com.deuty.agent.core.model.AgentTask
import com.deuty.agent.core.model.AskUserRequest
import com.deuty.agent.core.model.Bounds
import com.deuty.agent.agent.port.ExecContext
import com.deuty.agent.core.model.Role
import com.deuty.agent.core.model.ScreenSnapshot
import com.deuty.agent.core.model.RiskLevel
import com.deuty.agent.core.model.ScreenSource
import com.deuty.agent.core.model.TaskSource
import com.deuty.agent.core.model.TaskState
import com.deuty.agent.core.model.TaskStep
import com.deuty.agent.core.model.UiNode
import com.deuty.agent.core.model.UserChoice
import com.deuty.agent.core.model.UserDecision
import com.deuty.agent.core.util.AgentLog
import com.deuty.agent.core.util.Clock
import com.deuty.agent.core.util.Ids
import com.deuty.agent.core.util.ManualClock
import com.deuty.agent.core.util.withSignature
import java.io.File
import java.nio.file.Files

/**
 * Shared fixtures.
 *
 * Everything here is a real object, not a mock: the resolver, the risk manager,
 * the recovery manager, the executor and the runner are the production ones, and
 * the only doubles are the clock (so tests do not sleep) and the
 * [com.deuty.agent.agent.port.DeviceSession] (a SimulatedDevice). A test that
 * mocks the thing it is supposed to be verifying is worse than no test.
 */

internal fun testClock(start: Long = 1_700_000_000_000L) = ManualClock(start)

internal fun testLog(clock: Clock = testClock()) = AgentLog(capacity = 300, clock = clock)

internal fun tempDir(prefix: String = "deuty-test"): File =
    Files.createTempDirectory(prefix).toFile().apply { deleteOnExit() }

internal fun buildRunner(clock: Clock, log: AgentLog): TaskRunner {
    val resolver = ElementResolver()
    return TaskRunner(
        clock = clock,
        log = log,
        riskManager = RiskManager(log),
        recoveryManager = RecoveryManager(clock, log),
        resolver = resolver,
        evaluator = ExpectationEvaluator(resolver),
        executor = ActionExecutor(resolver, clock, log)
    )
}

/** A host that records everything and answers questions without a human. */
internal class RecordingHost(
    private val agentSettings: AgentSettings = AgentSettings(),
    private val autoAnswer: UserChoice = UserChoice.APPROVE
) : TaskRunnerHost {

    val messages = mutableListOf<Pair<Role, String>>()
    val asks = mutableListOf<AskUserRequest>()
    val finishedSteps = mutableListOf<Pair<TaskStep, ActionOutcome>>()
    var updates = 0
        private set

    override fun onTaskUpdated(task: AgentTask) {
        updates++
    }

    override fun onStepFinished(task: AgentTask, step: TaskStep, outcome: ActionOutcome) {
        finishedSteps += step to outcome
    }

    override fun onMessage(role: Role, text: String) {
        messages += role to text
    }

    override suspend fun askUser(request: AskUserRequest): UserDecision {
        asks += request
        return UserDecision(request.id, autoAnswer, null, 0L)
    }

    override fun settings(): AgentSettings = agentSettings

    override fun persistSoon(task: AgentTask) = Unit
}

internal fun uiNode(
    key: Int,
    text: String? = null,
    id: String? = null,
    description: String? = null,
    className: String = "android.widget.TextView",
    packageName: String = "com.example.app",
    bounds: Bounds = Bounds(0, key * 100, 500, key * 100 + 90),
    clickable: Boolean = true,
    longClickable: Boolean = false,
    scrollable: Boolean = false,
    editable: Boolean = false,
    checked: Boolean = false,
    focused: Boolean = false,
    enabled: Boolean = true,
    password: Boolean = false,
    depth: Int = 2,
    parentKey: Int? = 0
) = UiNode(
    key = key,
    parentKey = parentKey,
    depth = depth,
    text = text,
    contentDescription = description,
    viewIdResourceName = id,
    className = className,
    packageName = packageName,
    bounds = bounds,
    isClickable = clickable,
    isLongClickable = longClickable,
    isScrollable = scrollable,
    isEditable = editable,
    isChecked = checked,
    isSelected = false,
    isEnabled = enabled,
    isFocused = focused,
    isPassword = password,
    childCount = 0
)

internal fun screenOf(
    packageName: String = "com.example.app",
    windowTitle: String? = "Screen",
    secure: Boolean = false,
    width: Int = 1080,
    height: Int = 2340,
    nodes: List<UiNode> = emptyList(),
    source: ScreenSource = ScreenSource.ACCESSIBILITY_TREE,
    clock: Clock = testClock()
): ScreenSnapshot = ScreenSnapshot(
    packageName = packageName,
    windowTitle = windowTitle,
    nodes = nodes,
    source = source,
    capturedAtMs = clock.nowMs(),
    displayWidth = width,
    displayHeight = height,
    isSecure = secure
).withSignature()

internal fun taskOf(
    goal: String = "test task",
    steps: List<TaskStep> = emptyList(),
    source: TaskSource = TaskSource.USER_TEXT,
    clock: Clock = testClock(),
    settings: AgentSettings = AgentSettings()
): AgentTask = AgentTask(
    id = Ids.task(),
    goal = goal,
    source = source,
    createdAtMs = clock.nowMs(),
    budget = settings.budget(),
    state = TaskState.CREATED
).apply {
    this.steps += steps
    startedAtMs = clock.nowMs()
}

internal fun actionOf(step: TaskStep): AgentAction = step.action

/**
 * Execution policy for tests.
 *
 * Capture is ON here (the production default is OFF) because the recovery tests
 * need to reach the vision-escalation branch; the safety tests still use the real
 * default through [ExecContext] directly.
 */
internal fun execCtx(
    allowScreenshots: Boolean = true,
    allowPasswordEntry: Boolean = false,
    blockSensitiveScreens: Boolean = true,
    confirmAtOrAbove: RiskLevel = RiskLevel.MEDIUM,
    redact: Boolean = true,
    taskId: String? = "task-test"
) = ExecContext(
    allowPasswordEntry = allowPasswordEntry,
    allowScreenshots = allowScreenshots,
    blockSensitiveScreens = blockSensitiveScreens,
    confirmAtOrAbove = confirmAtOrAbove,
    redact = redact,
    taskId = taskId
)
