package com.deuty.agent.agent

import com.deuty.agent.agent.planner.ControlCommand
import com.deuty.agent.agent.planner.ControlGrammar
import com.deuty.agent.agent.planner.PlanContext
import com.deuty.agent.agent.planner.PlanRequest
import com.deuty.agent.agent.planner.PlanResult
import com.deuty.agent.agent.planner.PlannerProvider
import com.deuty.agent.agent.planner.WorkflowSummary
import com.deuty.agent.agent.port.AgentView
import com.deuty.agent.agent.port.DeviceSession
import com.deuty.agent.agent.port.SessionHolder
import com.deuty.agent.agent.port.SettingsProvider
import com.deuty.agent.agent.sandbox.SandboxDemo
import com.deuty.agent.core.model.ActionOutcome
import com.deuty.agent.core.model.AgentSettings
import com.deuty.agent.core.model.AgentStatus
import com.deuty.agent.core.model.AgentTask
import com.deuty.agent.core.model.AskUserRequest
import com.deuty.agent.core.model.ChatMessage
import com.deuty.agent.core.model.RecordedInteraction
import com.deuty.agent.core.model.RiskLevel
import com.deuty.agent.core.model.Role
import com.deuty.agent.core.model.ScreenSnapshot
import com.deuty.agent.core.model.StepStatus
import com.deuty.agent.core.model.TaskConstraints
import com.deuty.agent.core.model.TaskSource
import com.deuty.agent.core.model.TaskState
import com.deuty.agent.core.model.TaskStep
import com.deuty.agent.core.model.UserChoice
import com.deuty.agent.core.model.UserDecision
import com.deuty.agent.core.model.WorkflowSource
import com.deuty.agent.core.model.emptyDebugInfo
import com.deuty.agent.core.model.toDebugInfo
import com.deuty.agent.core.model.toTaskView
import com.deuty.agent.core.util.AgentLog
import com.deuty.agent.core.util.Clock
import com.deuty.agent.core.util.Ids
import com.deuty.agent.core.util.LoopDetector
import com.deuty.agent.core.util.Redactor
import com.deuty.agent.core.util.TextMatch
import com.deuty.agent.storage.TaskRepository
import com.deuty.agent.workflow.WorkflowRecorder
import com.deuty.agent.workflow.WorkflowRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The engine facade: the only object the UI, the overlay and the accessibility
 * service talk to (requirements 4, 11, 15, 17, 27).
 *
 * Responsibilities, and justifications:
 *   - Own the *one* task at a time. Two concurrent automations on one screen is
 *     not a feature, it is a fight.
 *   - Turn user input into either a control command, an answer to a pending
 *     question, or a new task.
 *   - Project state into [AgentStatus] and render it. The UI never sees a
 *     mutable [AgentTask].
 *   - Survive process death: load an interrupted task on start, offer to resume.
 *
 * Threading contract: every public method is safe to call from any thread. All
 * mutation happens on [mainDispatcher], which is also the thread the accessibility
 * service and the overlay live on - so there is no locking in this class, and no
 * possibility of a UI update racing a state change.
 *
 * Two job slots, deliberately separate:
 *   - [taskJob] runs a task. STOP cancels it.
 *   - command jobs are short-lived (answer a question, start recording). They must
 *     not be cancellable by STOP, or pressing STOP while answering a question
 *     would swallow the answer.
 */
class AgentEngine(
    private val clock: Clock,
    private val log: AgentLog,
    private val settingsProvider: SettingsProvider,
    private val tasks: TaskRepository,
    private val workflows: WorkflowRepository,
    private val planner: PlannerProvider,
    private val runner: TaskRunner,
    private val stateManager: StateManager,
    private val recorder: WorkflowRecorder,
    private val sessions: SessionHolder,
    private val sandbox: SandboxDemo,
    private val scope: CoroutineScope,
    private val mainDispatcher: CoroutineDispatcher
) : TaskRunnerHost {

    // ------------------------------------------------------------------ state

    private val _status = MutableStateFlow(AgentStatus())
    val status: StateFlow<AgentStatus> = _status.asStateFlow()

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    /** User-facing controls. Exposed so the overlay can bind directly. */
    val control = EngineControl()

    val events get() = log.version

    @Volatile
    private var view: AgentView? = null

    @Volatile
    private var taskJob: Job? = null

    @Volatile
    private var messageJob: Job? = null

    /** The task the UI is showing. Not necessarily running - see [isBusy]. */
    @Volatile
    private var shown: AgentTask? = null

    @Volatile
    private var pendingAsk: AskUserRequest? = null

    @Volatile
    private var pendingResume: Pair<String, AgentTask>? = null

    @Volatile
    private var lastSnapshot: ScreenSnapshot? = null

    @Volatile
    private var lastOutcome: ActionOutcome? = null

    @Volatile
    private var loopWarning: String? = null

    @Volatile
    private var lastPublishMs = 0L

    @Volatile
    private var lastSessionId: String? = null

    @Volatile
    private var started = false

    val isBusy: Boolean get() = taskJob?.isActive == true

    val isRecording: Boolean get() = recorder.isRecording

    // -------------------------------------------------------------- lifecycle

    /** Called once when the service (or the app process) comes up. */
    suspend fun start() {
        if (started) return
        started = true
        val restored = runCatching { tasks.loadMessages(limit = MESSAGE_LIMIT) }.getOrDefault(emptyList())
        _messages.value = restored
        runCatching { workflows.load() }.onFailure { log.w(TAG, "could not load workflows", it) }
        val interrupted = runCatching { stateManager.loadInterrupted() }.getOrNull()
        publish()
        log.i(
            TAG, "engine started: planner=${planner.activeId} session=${sessions.get()?.id ?: "none"} " +
                "messages=${restored.size} interrupted=${interrupted?.id ?: "none"}"
        )
        when {
            interrupted != null && !interrupted.isFinished -> offerResume(interrupted)
            restored.isEmpty() -> addMessage(Role.AGENT, WELCOME)
        }
    }

    /** Called when the process is going away. Flushes, never suspends for long. */
    suspend fun shutdown() {
        started = false
        messageJob?.cancel()
        taskJob?.cancel()
        val task = shown
        // A sandbox task is throwaway: persisting it would offer to "resume" a demo.
        if (task != null && !task.isFinished && task.source != TaskSource.SANDBOX) {
            // Persist exactly where we are, so the next start can offer to resume.
            runCatching { stateManager.persistNow(task) }
                .onFailure { log.e(TAG, "could not persist the interrupted task", it) }
        }
        runCatching { stateManager.flush() }
        runCatching { tasks.saveMessages(_messages.value, MESSAGE_LIMIT) }
            .onFailure { log.w(TAG, "could not persist chat", it) }
        log.i(TAG, "engine shut down")
    }

    // ------------------------------------------------------------------- view

    fun attachView(next: AgentView) {
        view = next
        next.render(_status.value)
        publish()
    }

    fun detachView() {
        view = null
    }

    fun expandPanel() {
        view?.expand()
    }

    // ---------------------------------------------------------------- session

    /** Called by the container when the accessibility service connects or dies. */
    fun onSessionChanged() {
        val session = sessions.get()
        val id = session?.id
        val changed = id != lastSessionId
        lastSessionId = id
        publish()
        if (!changed) return
        if (session == null) {
            addMessage(Role.SYSTEM, NO_SESSION)
        } else {
            log.i(TAG, "session attached: ${session.id} (${session.interaction.name})")
        }
    }

    /** Screen events, forwarded by the service. Cheap when nothing is watching. */
    fun onScreenSnapshot(snapshot: ScreenSnapshot) {
        lastSnapshot = snapshot
        if (!settings().debugMode) return
        publishThrottled()
    }

    /** User interactions, forwarded by the service while recording. */
    fun onUserInteraction(interaction: RecordedInteraction) {
        if (!recorder.isRecording) return
        recorder.onInteraction(interaction)
        publishThrottled()
    }

    // ------------------------------------------------------------------ input

    /** The chat box, a quick action, or a voice transcription. Any thread. */
    fun submit(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        command { input(trimmed) }
    }

    private suspend fun input(text: String) {
        addMessage(Role.USER, text)

        // 1. A question is on screen: this text is the answer, not a new task.
        val ask = pendingAsk
        if (ask != null) {
            answerInternal(ask, text)
            return
        }

        // 2. Control words are checked before "am I busy", because STOP has to
        //    work while a task is running.
        ControlGrammar.parse(text)?.let { control ->
            handleControl(control)
            return
        }

        // 3. Otherwise, a new task - unless one is already running.
        if (isBusy) {
            addMessage(Role.AGENT, busyMessage())
            return
        }
        startTaskJob { planAndRun(text, clarifyDepth = 0) }
    }

    private suspend fun planAndRun(instruction: String, clarifyDepth: Int) {
        val session = sessions.get()
        if (session == null) {
            addMessage(Role.AGENT, NO_SESSION)
            return
        }
        val task = newTask(instruction, TaskSource.USER_TEXT)
        transition(task, TaskState.PLANNING)

        val result = planner.plan(PlanRequest(instruction, planContext(session)))
        when (result) {
            is PlanResult.Control -> {
                abandon(task)
                handleControl(result.command)
            }

            is PlanResult.Plan -> {
                applyPlan(task, result)
                runTask(task, session)
            }

            is PlanResult.Clarify -> clarify(task, instruction, result, clarifyDepth, session)

            is PlanResult.Rejected -> {
                addMessage(Role.AGENT, result.reason)
                abandon(task, result.reason)
            }

            is PlanResult.NotCapable -> {
                log.d(TAG, "not capable: ${result.reason}")
                if (settings().askWhenAmbiguous && clarifyDepth < MAX_CLARIFY) {
                    clarify(
                        task, instruction,
                        PlanResult.Clarify(NOT_UNDERSTOOD_QUESTION, EXAMPLES, result.plannerId),
                        clarifyDepth, session
                    )
                } else {
                    addMessage(Role.AGENT, notUnderstood(instruction))
                    abandon(task, result.reason)
                }
            }
        }
    }

    private suspend fun clarify(
        task: AgentTask,
        instruction: String,
        result: PlanResult.Clarify,
        depth: Int,
        session: DeviceSession
    ) {
        if (depth >= MAX_CLARIFY) {
            addMessage(Role.AGENT, notUnderstood(instruction))
            abandon(task, "clarification limit reached")
            return
        }
        val request = AskUserRequest(
            id = Ids.request(),
            question = result.question,
            options = result.options,
            allowFreeText = true,
            risk = RiskLevel.LOW,
            taskId = task.id,
            createdAtMs = clock.nowMs()
        )
        transition(task, TaskState.WAITING_FOR_USER)
        publishAsk(request)
        addMessage(Role.AGENT, result.question)

        val decision = control.awaitDecision(request.id)
        publishAsk(null)

        when (decision.choice) {
            UserChoice.REPLY, UserChoice.APPROVE -> {
                val answer = decision.text?.takeIf { it.isNotBlank() }
                    ?: result.options.firstOrNull()
                    ?: ""
                abandon(task)
                if (answer.isBlank()) {
                    addMessage(Role.AGENT, notUnderstood(instruction))
                    return
                }
                planAndRun(mergeInstruction(instruction, answer, session), depth + 1)
            }

            UserChoice.RETRY -> {
                publishAsk(request)
                val again = control.awaitDecision(request.id)
                publishAsk(null)
                if (again.choice == UserChoice.REPLY || again.choice == UserChoice.APPROVE) {
                    val answer = again.text ?: result.options.firstOrNull().orEmpty()
                    abandon(task)
                    planAndRun(mergeInstruction(instruction, answer, session), depth + 1)
                } else {
                    abandon(task, "user did not answer")
                }
            }

            UserChoice.SKIP, UserChoice.DENY, UserChoice.STOP -> {
                abandon(task, "user declined to clarify")
                addMessage(Role.AGENT, "OK, I'll leave it.")
            }
        }
    }

    /**
     * "rename the file to X" + "timetable.pdf" is worth trying as one sentence;
     * "what should I type?" + "hello" is not. Probing the planner is free for the
     * rule-based one, and for a remote planner it costs one extra call - which is
     * still cheaper than planning the wrong task.
     */
    private suspend fun mergeInstruction(instruction: String, answer: String, session: DeviceSession): String {
        val combined = "$instruction $answer".trim()
        val probe = planner.plan(PlanRequest(combined, planContext(session)))
        return if (probe is PlanResult.Plan || probe is PlanResult.Clarify) combined else answer
    }

    // -------------------------------------------------------------- execution

    private suspend fun runTask(task: AgentTask, session: DeviceSession) {
        if (task.steps.isEmpty()) {
            addMessage(Role.AGENT, "That plan has no steps I can run.")
            abandon(task, "empty plan")
            return
        }
        control.reset()
        loopWarning = null
        lastOutcome = null
        val detector = LoopDetector(
            window = task.budget.loopWindow,
            repeatThreshold = task.budget.loopRepeatThreshold
        )
        transition(task, TaskState.RUNNING)
        task.startedAtMs = clock.nowMs()
        addMessage(Role.AGENT, planSummary(task))
        publish()

        val finalState = try {
            runner.run(task, this, session, control, detector)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            log.e(TAG, "the runner threw", error)
            addMessage(Role.ERROR, "The run failed with ${error.javaClass.simpleName}. Details are in the inspector.")
            TaskState.FAILED
        }
        loopWarning = detector.warning
        finishTask(task, finalState)
    }

    private fun finishTask(task: AgentTask, state: TaskState) {
        val terminal = if (state.isTerminal) state else TaskState.FAILED
        if (task.state != terminal) transition(task, terminal)
        task.finishedAtMs = clock.nowMs()
        task.touch(clock.nowMs())
        addMessage(Role.AGENT, resultText(task))
        publish()
        scope.launch(Dispatchers.Default) {
            runCatching {
                stateManager.finish(task, settings().keepHistory, settings().historyLimit)
            }.onFailure { log.e(TAG, "could not finish task ${task.id}", it) }
            task.workflowId?.let { id ->
                runCatching { workflows.recordRun(id, task.state == TaskState.COMPLETED) }
            }
        }
    }

    private fun onTaskCancelled() {
        val task = shown ?: return
        if (task.isFinished) return
        task.state = TaskState.CANCELLED
        task.finishedAtMs = clock.nowMs()
        task.resultMessage = "Stopped by you."
        task.touch(clock.nowMs())
        control.reset()
        addMessage(Role.SYSTEM, "Stopped. ${progressNote(task)}")
        publish()
        scope.launch(Dispatchers.Default) {
            runCatching { stateManager.finish(task, settings().keepHistory, settings().historyLimit) }
        }
    }

    private fun startTaskJob(block: suspend () -> Unit) {
        if (taskJob?.isActive == true) {
            addMessage(Role.AGENT, busyMessage())
            return
        }
        taskJob = scope.launch(mainDispatcher) {
            try {
                block()
            } catch (cancelled: CancellationException) {
                onTaskCancelled()
                throw cancelled
            } catch (error: Throwable) {
                log.e(TAG, "task job failed", error)
                addMessage(Role.ERROR, "Something broke: ${error.javaClass.simpleName}. The inspector has details.")
                shown?.let { task -> if (!task.isFinished) finishTask(task, TaskState.FAILED) }
            } finally {
                publish()
            }
        }
    }

    private fun command(block: suspend () -> Unit) {
        scope.launch(mainDispatcher) {
            try {
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                log.e(TAG, "command failed", error)
                addMessage(Role.ERROR, "That did not work: ${error.javaClass.simpleName}.")
            }
        }
    }

    // --------------------------------------------------------------- controls

    fun stopTask() {
        control.requestStop()
        val job = taskJob
        if (job?.isActive == true) {
            addMessage(Role.SYSTEM, "Stopping...")
            job.cancel()
        } else {
            addMessage(Role.AGENT, "Nothing is running.")
            publish()
        }
    }

    fun pauseTask() {
        if (!isBusy) {
            addMessage(Role.AGENT, "Nothing to pause.")
            return
        }
        control.requestPause()
        addMessage(Role.SYSTEM, "Paused. Nothing will happen until you resume.")
        shown?.let { transition(it, TaskState.PAUSED) }
        publish()
    }

    fun resumeTask() {
        if (!control.paused.value) {
            addMessage(Role.AGENT, "I'm not paused.")
            return
        }
        control.requestResume()
        addMessage(Role.SYSTEM, "Resumed.")
        shown?.let { transition(it, TaskState.RUNNING) }
        publish()
    }

    fun togglePause() {
        if (control.paused.value) resumeTask() else pauseTask()
    }

    fun skipStep() {
        if (!isBusy) {
            addMessage(Role.AGENT, "Nothing to skip.")
            return
        }
        control.requestSkip()
        addMessage(Role.SYSTEM, "Asked the runner to skip this step.")
        publish()
    }

    fun retryStep() {
        if (control.waitingForUser) {
            control.requestRetry()
            addMessage(Role.SYSTEM, "Retrying.")
            publish()
            return
        }
        val task = shown
        if (isBusy) {
            control.requestRetry()
            return
        }
        if (task != null && task.isFinished && task.state != TaskState.COMPLETED) {
            rerun(task)
        } else {
            addMessage(Role.AGENT, "Nothing to retry.")
        }
    }

    /** Re-run a finished task from the top: same plan, fresh step states. */
    fun rerun(task: AgentTask) {
        val session = sessions.get()
        if (session == null) {
            addMessage(Role.AGENT, NO_SESSION)
            return
        }
        if (isBusy) {
            addMessage(Role.AGENT, busyMessage())
            return
        }
        task.steps.forEach { step ->
            step.status = StepStatus.PENDING
            step.attempts = 0
            step.note = null
            step.lastOutcome = null
        }
        task.currentStep = 0
        task.history.clear()
        task.actionsUsed = 0
        task.retriesUsed = 0
        task.stopReason = null
        task.resultMessage = null
        task.finishedAtMs = null
        task.startedAtMs = null
        shown = task
        startTaskJob { runTask(task, session) }
    }

    fun cancelTask() = stopTask()

    /** A tapped option in the overlay or the chat. */
    fun answer(label: String) {
        val ask = pendingAsk ?: run {
            // No pending question: treat it as ordinary input.
            submit(label)
            return
        }
        command { answerInternal(ask, label) }
    }

    private suspend fun answerInternal(ask: AskUserRequest, label: String) {
        addMessage(Role.USER, label)
        val resume = pendingResume?.takeIf { it.first == ask.id }?.second
        if (resume != null) {
            pendingResume = null
            publishAsk(null)
            val normalized = TextMatch.normalize(label)
            if (normalized.startsWith("resume") || normalized.startsWith("continue") ||
                normalized == "yes" || normalized == "do it"
            ) {
                resumeInterrupted(resume)
            } else {
                discardInterrupted()
                addMessage(Role.AGENT, "OK, I dropped the interrupted task.")
            }
            return
        }
        publishAsk(null)
        control.answer(UserDecision.fromLabel(ask.id, label, clock.nowMs()))
    }

    // -------------------------------------------------------------- recording

    fun startRecording() = command {
        if (sessions.get() == null) {
            addMessage(Role.AGENT, NO_SESSION)
            return@command
        }
        if (recorder.isRecording) {
            addMessage(Role.AGENT, "I'm already recording. Tap Stop when you're done.")
            return@command
        }
        if (isBusy) {
            addMessage(Role.AGENT, "Stop the running task before recording.")
            return@command
        }
        recorder.start()
        addMessage(
            Role.AGENT,
            "Recording. Do the task on your phone now, then tap Stop. " +
                "Touches on my own overlay are ignored, and anything typed into a password field is never stored."
        )
        publish()
    }

    fun stopRecording(name: String? = null) = command {
        if (!recorder.isRecording) {
            addMessage(Role.AGENT, "I'm not recording.")
            return@command
        }
        val steps = recorder.stop()
        publish()
        if (steps.isEmpty()) {
            addMessage(Role.AGENT, "I didn't see any actions to record. Make sure the accessibility service is on.")
            return@command
        }
        val label = name?.takeIf { it.isNotBlank() } ?: defaultWorkflowName()
        val saved = runCatching {
            workflows.save(
                name = label,
                steps = steps,
                source = WorkflowSource.RECORDED,
                packages = recorder.packagesOf(steps)
            )
        }.getOrElse { error ->
            log.e(TAG, "could not save the workflow", error)
            addMessage(Role.ERROR, "I recorded ${steps.size} step(s) but could not save them.")
            return@command
        }
        val recap = buildString {
            append("Saved \"").append(saved.name).append("\" with ")
            append(saved.steps.size).append(" step(s):\n")
            saved.steps.take(6).forEach { step -> append("  - ").append(step.title).append('\n') }
            if (saved.steps.size > 6) append("  - +").append(saved.steps.size - 6).append(" more\n")
            append("Replay it with: run ").append(saved.name)
        }
        addMessage(Role.AGENT, recap)
    }

    fun cancelRecording() = command {
        if (!recorder.isRecording) return@command
        recorder.cancel()
        addMessage(Role.SYSTEM, "Recording discarded.")
        publish()
    }

    // -------------------------------------------------------------- workflows

    fun runWorkflow(workflowId: String) = command {
        if (isBusy) {
            addMessage(Role.AGENT, busyMessage())
            return@command
        }
        val session = sessions.get()
        if (session == null) {
            addMessage(Role.AGENT, NO_SESSION)
            return@command
        }
        val workflow = workflows.get(workflowId)
        if (workflow == null) {
            addMessage(Role.AGENT, "I don't have that workflow any more.")
            return@command
        }
        val task = newTask(workflow.name, TaskSource.WORKFLOW)
        task.steps += workflows.instantiate(workflow)
        task.workflowId = workflow.id
        task.riskLevel = workflow.riskLevel
        addMessage(Role.AGENT, "Replaying \"${workflow.name}\" (${task.steps.size} steps).")
        startTaskJob { runTask(task, session) }
    }

    fun deleteWorkflow(workflowId: String) = command {
        val removed = workflows.delete(workflowId)
        addMessage(if (removed) Role.SYSTEM else Role.ERROR,
            if (removed) "Workflow deleted." else "That workflow is already gone.")
    }

    // ---------------------------------------------------------------- sandbox

    /** Runs the full pipeline against a simulated phone. Touches nothing real. */
    fun runSandboxDemo(instruction: String = SandboxDemo.DEFAULT_INSTRUCTION) = command {
        if (isBusy) {
            addMessage(Role.AGENT, busyMessage())
            return@command
        }
        addMessage(Role.AGENT, "Sandbox demo: \"$instruction\" on a simulated phone. Your device is not touched.")
        startTaskJob {
            val report = sandbox.run(this@AgentEngine, control, instruction)
            addMessage(if (report.succeeded) Role.AGENT else Role.SYSTEM, report.summary())
        }
    }

    // ------------------------------------------------------- TaskRunnerHost

    override fun onTaskUpdated(task: AgentTask) {
        if (shown?.id != task.id) shown = task
        publish()
    }

    override fun onStepFinished(task: AgentTask, step: TaskStep, outcome: ActionOutcome) {
        lastOutcome = outcome
        if (settings().debugMode) {
            log.d(TAG, "step \"${step.title}\" -> ${outcome.shortLabel}")
        }
        publishThrottled()
    }

    override fun onMessage(role: Role, text: String) {
        addMessage(role, text)
    }

    override suspend fun askUser(request: AskUserRequest): UserDecision {
        val task = shown
        if (task != null) transition(task, TaskState.WAITING_FOR_USER)
        publishAsk(request)
        addMessage(Role.AGENT, request.question)
        view?.expand()
        val decision = control.awaitDecision(request.id)
        publishAsk(null)
        if (task != null && !task.isFinished) {
            transition(task, if (control.paused.value) TaskState.PAUSED else TaskState.RUNNING)
        }
        return decision
    }

    override fun settings(): AgentSettings = settingsProvider.current()

    override fun persistSoon(task: AgentTask) {
        stateManager.persistSoon(task)
    }

    // --------------------------------------------------------------- internal

    private fun newTask(goal: String, source: TaskSource): AgentTask {
        val currentSettings = settings()
        val task = AgentTask(
            id = Ids.task(),
            goal = goal,
            source = source,
            createdAtMs = clock.nowMs(),
            constraints = TaskConstraints(offlineOnly = true),
            budget = currentSettings.budget(),
            state = TaskState.CREATED
        )
        shown = task
        return task
    }

    private fun applyPlan(task: AgentTask, plan: PlanResult.Plan) {
        val max = task.budget.maxSteps
        if (plan.steps.size > max) {
            addMessage(
                Role.SYSTEM,
                "That plan had ${plan.steps.size} steps; I only run the first $max. " +
                    "Break the task into smaller ones."
            )
        }
        task.steps.clear()
        task.steps += plan.steps.take(max)
        task.context.putAll(plan.contextValues)
        task.riskLevel = plan.risk
        task.workflowId = plan.contextValues["workflowId"]
        plan.notes?.let { task.context["plannerNotes"] = it }
        log.i(TAG, "plan applied: ${task.steps.size} step(s), risk ${plan.risk}, planner ${plan.plannerId}")
    }

    private fun transition(task: AgentTask, next: TaskState) {
        if (task.state == next) return
        if (!task.state.canTransitionTo(next)) {
            log.w(TAG, "illegal transition ${task.state} -> $next (task ${task.id})")
        }
        task.state = next
        task.touch(clock.nowMs())
        publish()
    }

    /** Drop a task that never became real (control command, refusal, no plan). */
    private fun abandon(task: AgentTask, reason: String? = null) {
        if (shown?.id == task.id) {
            task.state = TaskState.CANCELLED
            task.resultMessage = reason
            task.finishedAtMs = clock.nowMs()
            task.touch(clock.nowMs())
        }
        reason?.let { log.d(TAG, "task ${task.id} abandoned: $it") }
        publish()
    }

    private fun offerResume(task: AgentTask) {
        val request = AskUserRequest(
            id = Ids.request(),
            question = "I was interrupted in the middle of \"${task.goal}\" " +
                "(step ${task.currentStep + 1} of ${task.steps.size}). Resume where I left off?",
            options = listOf(RESUME_LABEL, DISCARD_LABEL),
            allowFreeText = false,
            risk = RiskLevel.LOW,
            taskId = task.id,
            createdAtMs = clock.nowMs(),
            denyStopsTask = false
        )
        pendingResume = request.id to task
        shown = task
        publishAsk(request)
        log.i(TAG, "offering to resume interrupted task ${task.id}")
    }

    private fun resumeInterrupted(task: AgentTask) {
        val session = sessions.get()
        if (session == null) {
            addMessage(Role.AGENT, NO_SESSION)
            return
        }
        task.steps.getOrNull(task.currentStep)?.let { step ->
            step.status = StepStatus.PENDING
            step.attempts = 0
            step.note = "resumed after an interruption"
            step.lastOutcome = null
        }
        task.stopReason = null
        task.resultMessage = null
        task.finishedAtMs = null
        shown = task
        addMessage(Role.AGENT, "Resuming \"${task.goal}\" at step ${task.currentStep + 1} of ${task.steps.size}.")
        startTaskJob { runTask(task, session) }
    }

    private fun discardInterrupted() {
        pendingResume = null
        scope.launch(Dispatchers.Default) {
            runCatching { tasks.clearCurrent() }
                .onFailure { log.w(TAG, "could not clear the interrupted task", it) }
        }
    }

    private fun planContext(session: DeviceSession): PlanContext {
        val snapshot = session.screens.lastSnapshot() ?: lastSnapshot
        val currentSettings = settings()
        return PlanContext(
            currentPackage = snapshot?.packageName,
            screenSummary = snapshot?.let { summarise(it) },
            recentMessages = _messages.value.takeLast(6),
            workflows = workflows.cached().map { WorkflowSummary(it.id, it.name, it.steps.size) },
            settings = currentSettings,
            taskInProgress = isBusy,
            installedApps = emptyList()
        )
    }

    private fun summarise(snapshot: ScreenSnapshot): String {
        val labels = snapshot.nodes
            .filter { it.isClickable || it.isEditable }
            .mapNotNull { it.label }
            .distinct()
            .take(12)
        val redacted = Redactor.text(labels.joinToString(", "), settings().redact) ?: ""
        return "${snapshot.packageName ?: "?"} / ${snapshot.windowTitle ?: "?"}: $redacted"
    }

    // -------------------------------------------------------------- rendering

    private fun publishAsk(request: AskUserRequest?) {
        pendingAsk = request
        publish()
    }

    private fun publishThrottled() {
        val now = clock.elapsedRealtimeMs()
        if (now - lastPublishMs < PUBLISH_THROTTLE_MS) return
        publish()
    }

    private fun publish() {
        lastPublishMs = clock.elapsedRealtimeMs()
        val currentSettings = settings()
        val task = shown
        val snapshot = lastSnapshot
        val next = AgentStatus(
            accessibilityEnabled = sessions.attached,
            overlayEnabled = view?.isVisible == true,
            recording = recorder.isRecording,
            recordedSteps = recorder.interactionCount,
            task = task?.toTaskView(clock.nowMs()),
            ask = pendingAsk,
            debug = if (currentSettings.debugMode) {
                snapshot?.toDebugInfo(task, sessionName(), loopWarning) ?: emptyDebugInfo(sessionName())
            } else null,
            lastMessage = _messages.value.lastOrNull()?.text,
            updatedAtMs = clock.nowMs()
        )
        _status.value = next
        view?.render(next)
    }

    private fun sessionName(): String = sessions.get()?.interaction?.name ?: "none"

    private fun addMessage(role: Role, text: String) {
        if (text.isBlank()) return
        val currentSettings = settings()
        val safe = if (currentSettings.redact) Redactor.text(text, true) ?: text else text
        val message = ChatMessage(
            id = Ids.message(),
            role = role,
            text = safe,
            atMs = clock.nowMs(),
            taskId = shown?.id
        )
        _messages.value = (_messages.value + message).takeLast(MESSAGE_LIMIT)
        scheduleMessageSave()
        publish()
    }

    private fun scheduleMessageSave() {
        messageJob?.cancel()
        messageJob = scope.launch(Dispatchers.Default) {
            delay(MESSAGE_SAVE_DEBOUNCE_MS)
            runCatching { tasks.saveMessages(_messages.value, MESSAGE_LIMIT) }
                .onFailure { log.w(TAG, "could not persist chat", it) }
        }
    }

    // ----------------------------------------------------------------- texts

    private fun busyMessage(): String {
        val task = shown
        val step = task?.activeStep?.title
        return "I'm still working on \"${task?.goal ?: "a task"}\"" +
            (step?.let { " - currently: $it" } ?: "") +
            ". Tap STOP in the capsule, or type \"stop\", if you want me to abandon it."
    }

    private fun planSummary(task: AgentTask): String {
        val header = "Here's the plan (${task.steps.size} step(s), risk ${task.riskLevel.name.lowercase()}):"
        val show = if (settings().showStepListInCapsule) 6 else 3
        val lines = task.steps.take(show).mapIndexed { index, step ->
            "  ${index + 1}. ${step.title}"
        }
        val more = if (task.steps.size > show) "\n  + ${task.steps.size - show} more" else ""
        return header + "\n" + lines.joinToString("\n") + more +
            "\nI'll verify each step and stop to ask before anything risky."
    }

    private fun resultText(task: AgentTask): String {
        val done = task.steps.count { it.status == StepStatus.DONE }
        val skipped = task.steps.count { it.status == StepStatus.SKIPPED }
        val failed = task.steps.count { it.status == StepStatus.FAILED }
        val recap = progressNote(task)
        return when (task.state) {
            TaskState.COMPLETED ->
                "Done. ${task.resultMessage ?: ""} $recap".trim()
            TaskState.CANCELLED ->
                "Stopped. $recap"
            TaskState.FAILED -> {
                val why = task.resultMessage ?: task.stopReason ?: lastOutcome?.message
                "I could not finish: ${why ?: "unknown reason"}. $recap " +
                    "(done $done, skipped $skipped, failed $failed)"
            }
            else -> "Task ended in state ${task.state}. $recap"
        }
    }

    private fun progressNote(task: AgentTask): String {
        val total = task.steps.size
        if (total == 0) return ""
        val done = task.steps.count { it.isDone }
        return "Steps: $done/$total completed in ${task.elapsedMs(clock.nowMs()) / 1000}s, " +
            "${task.actionsUsed} action(s), ${task.retriesUsed} retr(y/ies)."
    }

    private fun statusText(): String {
        val task = shown
        if (task == null) return "Idle. No task yet."
        return buildString {
            append("Task: \"${task.goal}\" - ${task.state.name}")
            task.activeStep?.let { append("\nCurrent step: ${it.title} (${it.status.name}, attempt ${it.attempts})") }
            append("\n${progressNote(task)}")
            append("\nBudget: ${task.remainingActions()} action(s) and ${task.remainingRetries()} retry(ies) left")
            lastSnapshot?.let { append("\nScreen: ${it.packageName ?: "?"} / ${it.windowTitle ?: "?"} (${it.nodes.size} nodes)") }
            loopWarning?.let { append("\nLoop warning: $it") }
        }
    }

    /** "Why are you stuck?" - the most important question an agent gets asked. */
    private fun stuckText(): String {
        val task = shown
        if (task == null) return "I'm not working on anything right now."
        val diagnosis = lastSnapshot?.let { ScreenDiagnostics.diagnose(it) }
        return buildString {
            append("Task \"${task.goal}\" is ${task.state.name}.")
            task.stopReason?.let { append("\nReason: $it") }
            task.activeStep?.let { step ->
                append("\nStuck on: ${step.title}")
                step.lastOutcome?.let { outcome ->
                    append("\nLast result: ${outcome.shortLabel} - ${outcome.message}")
                    outcome.reason?.let { append(" (${it.name})") }
                }
                step.note?.let { append("\nNote: $it") }
            }
            loopWarning?.let { append("\nLoop detector: $it") }
            if (diagnosis != null && diagnosis.isBlocking) {
                append("\nScreen says: ${diagnosis.blocker.name} (${diagnosis.evidence})")
                diagnosis.question?.let { append("\n$it") }
            }
            append("\nOptions: retry, skip this step, or stop.")
        }
    }

    private fun notUnderstood(instruction: String): String =
        "I could not turn \"${instruction.take(60)}\" into steps. I understand things like:\n" +
            EXAMPLES.joinToString("\n") { "  - $it" } +
            "\nOr tap Record and show me once."

    private fun helpText(): String = buildString {
        append("I drive the phone's normal UI - no root, no app APIs, nothing leaves this device.\n")
        append("Try:\n")
        append(EXAMPLES.joinToString("\n") { "  - $it" })
        append("\nControls: stop, pause, resume, retry, skip, status, \"why are you stuck?\"")
        append("\nRecording: tap Record, do the task, tap Stop, then \"run <name>\" to replay it.")
        append("\nPlanner: ${planner.describe()}")
    }

    private fun handleControl(command: ControlCommand) {
        when (command) {
            ControlCommand.STOP -> if (isBusy) stopTask() else addMessage(Role.AGENT, "Nothing is running.")
            ControlCommand.PAUSE -> pauseTask()
            ControlCommand.RESUME -> resumeTask()
            ControlCommand.RETRY -> retryStep()
            ControlCommand.SKIP -> skipStep()
            ControlCommand.STATUS -> addMessage(Role.AGENT, statusText())
            ControlCommand.WHY_STUCK -> addMessage(Role.AGENT, stuckText())
            ControlCommand.HELP -> addMessage(Role.AGENT, helpText())
        }
    }

    private fun defaultWorkflowName(): String {
        val time = SimpleDateFormat("HH:mm", Locale.US).format(Date(clock.nowMs()))
        return "Workflow $time"
    }

    companion object {
        private const val TAG = "engine"
        private const val MESSAGE_LIMIT = 200
        private const val MESSAGE_SAVE_DEBOUNCE_MS = 800L
        private const val PUBLISH_THROTTLE_MS = 200L
        private const val MAX_CLARIFY = 2
        private const val RESUME_LABEL = "Resume where I left off"
        private const val DISCARD_LABEL = "Drop it"

        const val NO_SESSION = "I have no connection to the screen yet. Open Settings > Accessibility > Deuty " +
            "and turn the service on - then I can see and act."

        val EXAMPLES = listOf(
            "open Settings",
            "tap Wi-Fi",
            "type 500 into Amount",
            "search for deuty in Chrome",
            "scroll down",
            "go back",
            "read the screen",
            "rename timetable.pdf to College Timetable.pdf",
            "run <workflow name>",
            "why are you stuck?"
        )

        const val NOT_UNDERSTOOD_QUESTION =
            "I'm not sure what you want me to do. Which of these is closest?"

        val WELCOME = "I'm Deuty. I use the phone's own UI - accessibility, taps, typing - so I work in any app, " +
            "with no root and no app APIs. Everything stays on this device.\n" +
            "Try \"open Settings\", or tap Record and show me a task once. Type \"help\" for the full list."
    }
}
