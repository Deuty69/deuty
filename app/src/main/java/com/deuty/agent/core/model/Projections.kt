package com.deuty.agent.core.model

/**
 * Model -> view projections.
 *
 * The UI is only ever allowed to see these immutable snapshots, never a live
 * [AgentTask]. Two reasons:
 *   - A task is mutable and written from the runner's coroutine; handing it to
 *     the UI would mean rendering state that changes mid-layout (and RecyclerView
 *     crashes on inconsistent adapter state).
 *   - It keeps `core/` free of Android imports: these are plain data classes, so
 *     a projection can be asserted in a JVM unit test.
 */

fun TaskStep.toStepView(isCurrent: Boolean): StepView = StepView(
    title = title,
    status = status,
    note = note,
    isCurrent = isCurrent
)

fun AgentTask.toTaskView(nowMs: Long): TaskView = TaskView(
    id = id,
    goal = goal,
    headline = headline(),
    state = state,
    progress = progress,
    steps = steps.mapIndexed { index, step ->
        step.toStepView(isCurrent = index == currentStep && !state.isTerminal)
    },
    actionsUsed = actionsUsed,
    maxActions = budget.maxActions,
    riskLevel = riskLevel,
    elapsedMs = elapsedMs(nowMs),
    stopReason = stopReason,
    resultMessage = resultMessage
)

/** The most recent step record, for the inspector's "last action / result". */
fun AgentTask.lastRecord(): StepRecord? = history.lastOrNull()

fun ScreenSnapshot.toDebugInfo(
    task: AgentTask?,
    engineName: String,
    loopWarning: String? = null
): DebugInfo {
    val record = task?.lastRecord()
    return DebugInfo(
        packageName = packageName,
        windowTitle = windowTitle,
        nodeCount = nodes.size,
        interactiveCount = nodes.count { it.isClickable || it.isEditable || it.isScrollable },
        source = source,
        signature = signature,
        captureMs = capturedAtMs,
        lastAction = record?.actionLabel,
        lastResult = record?.let { "${it.status.name}: ${it.message ?: it.outcome}" },
        lastTier = record?.tier,
        taskState = task?.state ?: TaskState.CREATED,
        retryCount = task?.activeStep?.attempts ?: 0,
        totalRetries = task?.retriesUsed ?: 0,
        actionsUsed = task?.actionsUsed ?: 0,
        budgetActions = task?.budget?.maxActions ?: 0,
        loopWarning = loopWarning,
        engine = engineName
    )
}

fun emptyDebugInfo(engineName: String): DebugInfo = DebugInfo(
    packageName = null,
    windowTitle = null,
    nodeCount = 0,
    interactiveCount = 0,
    source = ScreenSource.UNAVAILABLE,
    signature = "-",
    captureMs = 0L,
    lastAction = null,
    lastResult = null,
    lastTier = null,
    taskState = TaskState.CREATED,
    retryCount = 0,
    totalRetries = 0,
    actionsUsed = 0,
    budgetActions = 0,
    loopWarning = null,
    engine = engineName
)

/**
 * What the capsule shows. Short on purpose: the capsule is 34dp tall and the
 * user glances at it while doing something else.
 */
fun AgentStatus.headline(): String = when {
    ask != null -> ask.question.take(48)
    task == null -> if (recording) "Recording" else "Ready"
    task.state == TaskState.PAUSED -> "Paused"
    task.state == TaskState.WAITING_FOR_USER -> "Waiting for you"
    else -> task.headline.take(48)
}
