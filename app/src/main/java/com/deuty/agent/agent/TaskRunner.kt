package com.deuty.agent.agent

import com.deuty.agent.agent.executor.ActionExecutor
import com.deuty.agent.agent.executor.AgentAction
import com.deuty.agent.agent.executor.Conditional
import com.deuty.agent.agent.executor.Repeat
import com.deuty.agent.agent.executor.Relaunch
import com.deuty.agent.agent.executor.Report
import com.deuty.agent.agent.executor.withTarget
import com.deuty.agent.agent.port.DeviceSession
import com.deuty.agent.agent.port.ExecContext
import com.deuty.agent.core.model.ActionOutcome
import com.deuty.agent.core.model.AgentSettings
import com.deuty.agent.core.model.AgentTask
import com.deuty.agent.core.model.Always
import com.deuty.agent.core.model.AskUserRequest
import com.deuty.agent.core.model.FailureReason
import com.deuty.agent.core.model.OutcomeStatus
import com.deuty.agent.core.model.RiskLevel
import com.deuty.agent.core.model.Role
import com.deuty.agent.core.model.ScreenSnapshot
import com.deuty.agent.core.model.ScreenUnchanged
import com.deuty.agent.core.model.StepRecord
import com.deuty.agent.core.model.StepStatus
import com.deuty.agent.core.model.TaskState
import com.deuty.agent.core.model.TaskStep
import com.deuty.agent.core.model.UserChoice
import com.deuty.agent.core.model.UserDecision
import com.deuty.agent.core.model.Verdict
import com.deuty.agent.core.util.AgentLog
import com.deuty.agent.core.util.Clock
import com.deuty.agent.core.util.Ids
import com.deuty.agent.core.util.LoopDetector
import com.deuty.agent.core.util.Redactor

/** What the runner needs from its owner. Implemented by AgentEngine, faked in tests. */
interface TaskRunnerHost {
    fun onTaskUpdated(task: AgentTask)
    fun onStepFinished(task: AgentTask, step: TaskStep, outcome: ActionOutcome)
    fun onMessage(role: Role, text: String)
    suspend fun askUser(request: AskUserRequest): UserDecision
    fun settings(): AgentSettings
    fun persistSoon(task: AgentTask)
}

enum class StepDecision {
    /** Step finished (done or nothing more to do here): move to the next one. */
    CONTINUE,
    /** Re-run the same step, e.g. after the user cleared a risk gate. */
    RETRY_STEP,
    SKIP_STEP,
    STOP_TASK,
    FAIL_TASK
}

data class StepOutcome(
    val outcome: ActionOutcome,
    val decision: StepDecision,
    val note: String? = null
)

/**
 * The task loop: observe -> gate -> act -> verify -> recover.
 * (requirements 3, 5, 19, 20)
 *
 * This is the only place in the system that knows the *order* things happen in.
 * Everything else is a pure function of a screen snapshot and an action, which is
 * what makes the whole pipeline runnable against a simulated device.
 *
 * Invariants, checked in this order on every step:
 *   1. Stop/pause first. The STOP button must never wait behind a retry, a
 *      verification timeout or an animation.
 *   2. Budgets (actions, wall clock, retries) before any work.
 *   3. The loop detector sees every (screen, action) pair; three identical pairs
 *      inside the window ends the task with "I appear to be stuck".
 *   4. Risk is assessed against the *live screen*, not the plan: the plan says
 *      "tap", the screen says "Confirm payment".
 *   5. Every action is verified. An unverified success is reported as unverified
 *      rather than silently counted as done.
 *   6. Failures go through RecoveryManager, which may retry with a relaxed
 *      locator, scroll, wait, relaunch, ask the user, or give up. Never loop.
 *   7. A risk gate the user has answered is never asked twice for the same step
 *      (otherwise "approve" would be an infinite dialog).
 */
class TaskRunner(
    private val clock: Clock,
    private val log: AgentLog,
    private val riskManager: RiskManager,
    private val recoveryManager: RecoveryManager,
    private val resolver: ElementResolver,
    private val evaluator: ExpectationEvaluator,
    private val executor: ActionExecutor
) {

    private sealed interface RecoveryOutcome {
        data class Retry(val action: AgentAction) : RecoveryOutcome
        data object Skip : RecoveryOutcome
        data object Stop : RecoveryOutcome
        data object GiveUp : RecoveryOutcome
    }

    suspend fun run(
        task: AgentTask,
        host: TaskRunnerHost,
        session: DeviceSession,
        control: EngineControl,
        loopDetector: LoopDetector
    ): TaskState {
        val settings = host.settings()
        val ctx = ExecContext(
            allowPasswordEntry = settings.allowPasswordEntry,
            allowScreenshots = settings.allowScreenshots,
            blockSensitiveScreens = settings.blockSensitiveScreens,
            confirmAtOrAbove = settings.confirmAtOrAbove(),
            redact = settings.redact,
            taskId = task.id
        )
        // Steps the user has personally cleared. Never persisted: clearing a
        // payment screen once must not clear it forever.
        val clearedSteps = mutableSetOf<String>()

        task.startedAtMs = task.startedAtMs ?: clock.nowMs()
        transition(task, TaskState.RUNNING, host)

        while (task.currentStep < task.steps.size) {
            if (control.stopRequested) return finish(task, TaskState.CANCELLED, "stopped by user", host)
            control.awaitResume()
            if (control.stopRequested) return finish(task, TaskState.CANCELLED, "stopped by user", host)

            // ---- budgets (requirement 20) --------------------------------
            val elapsed = task.elapsedMs(clock.nowMs())
            if (elapsed > task.budget.maxDurationMs) {
                return failWithBudget(
                    task, FailureReason.BUDGET_TIME,
                    "time limit of ${task.budget.maxDurationMs / 1000}s reached after ${task.actionsUsed} action(s)", host
                )
            }
            if (task.actionsUsed >= task.budget.maxActions) {
                return failWithBudget(task, FailureReason.BUDGET_ACTIONS,
                    "action limit of ${task.budget.maxActions} reached", host)
            }
            if (task.retriesUsed >= task.budget.maxTotalRetries) {
                return failWithBudget(task, FailureReason.BUDGET_RETRIES,
                    "retry limit of ${task.budget.maxTotalRetries} reached", host)
            }

            val step = task.steps[task.currentStep]
            if (step.isDone) {
                task.currentStep++
                continue
            }

            val result = runStep(task, step, host, session, control, loopDetector, ctx, clearedSteps)
            recordHistory(task, step, result.outcome, ctx)
            host.onStepFinished(task, step, result.outcome)

            when (result.decision) {
                StepDecision.STOP_TASK ->
                    return finish(task, TaskState.CANCELLED, result.note ?: "stopped", host)

                StepDecision.FAIL_TASK ->
                    return finish(
                        task, TaskState.FAILED,
                        result.outcome.message.ifBlank { result.note ?: "step failed" },
                        host, result.outcome.reason
                    )

                StepDecision.SKIP_STEP -> {
                    step.status = StepStatus.SKIPPED
                    task.currentStep++
                }

                StepDecision.RETRY_STEP -> {
                    // Counted against the retry budget so a user (or a bug) cannot
                    // make the same step re-run forever.
                    task.retriesUsed++
                    step.status = StepStatus.PENDING
                }

                StepDecision.CONTINUE -> task.currentStep++
            }
            task.touch(clock.nowMs())
            host.onTaskUpdated(task)
            host.persistSoon(task)
        }

        return finish(task, TaskState.COMPLETED, task.resultMessage ?: "all ${task.steps.size} step(s) done", host)
    }

    // --------------------------------------------------------------- one step

    private suspend fun runStep(
        task: AgentTask,
        step: TaskStep,
        host: TaskRunnerHost,
        session: DeviceSession,
        control: EngineControl,
        loopDetector: LoopDetector,
        ctx: ExecContext,
        clearedSteps: MutableSet<String>
    ): StepOutcome {
        step.status = StepStatus.RUNNING
        step.note = null
        val stepCtx = ctx.copy(stepId = step.id)
        val maxAttempts = step.retry.maxAttempts
            .coerceAtLeast(1)
            .coerceAtMost(task.budget.maxRetriesPerStep.coerceAtLeast(1) + 1)

        var action: AgentAction = step.action
        var attempt = 0
        var lastOutcome: ActionOutcome = ActionOutcome.failure("step did not run", FailureReason.UNKNOWN)

        while (attempt < maxAttempts) {
            if (control.stopRequested) {
                return StepOutcome(cancelledOutcome("stopped by user"), StepDecision.STOP_TASK, "stopped by user")
            }
            control.awaitResume()

            if (control.consumeSkip()) {
                step.status = StepStatus.SKIPPED
                return StepOutcome(skippedOutcome("skipped by user"), StepDecision.SKIP_STEP, "skipped")
            }

            val screen = session.screens.observe("step ${step.id} attempt $attempt")
            if (screen.packageName != null) task.context["lastPackage"] = screen.packageName

            // ---- loop detection -----------------------------------------
            val verdict = loopDetector.record(screen.signature, action.label)
            if (verdict.isLoop) {
                val message = verdict.message ?: "repeating the same action on the same screen"
                log.w(TAG, "loop detected: $message")
                step.status = StepStatus.FAILED
                step.note = message
                return StepOutcome(
                    ActionOutcome.failure(message, FailureReason.LOOP_DETECTED, atMs = clock.nowMs()),
                    StepDecision.FAIL_TASK, message
                )
            }

            // ---- risk gate ----------------------------------------------
            val alreadyCleared = step.id in clearedSteps
            val risk = riskManager.assess(action, screen, stepCtx, task)
            if (risk.level.rank > task.riskLevel.rank) task.riskLevel = risk.level

            if (risk.refuse && !alreadyCleared) {
                step.status = StepStatus.WAITING_FOR_USER
                transition(task, TaskState.WAITING_FOR_USER, host)
                val ask = AskUserRequest(
                    id = Ids.request(),
                    question = "I won't do this one on my own: ${risk.explanation}. " +
                        "Handle it and I'll continue, skip it, or stop?",
                    options = listOf("I've handled it, continue", "Skip this step", "Stop the task"),
                    allowFreeText = true,
                    risk = RiskLevel.FORBIDDEN,
                    reason = FailureReason.RISK_BLOCKED,
                    stepId = step.id,
                    taskId = task.id,
                    createdAtMs = clock.nowMs(),
                    denyStopsTask = true
                )
                val decision = host.askUser(ask)
                transition(task, TaskState.RUNNING, host)
                return when (decision.choice) {
                    UserChoice.APPROVE, UserChoice.RETRY, UserChoice.REPLY -> {
                        clearedSteps += step.id
                        decision.text?.let { task.context["userReply"] = it }
                        StepOutcome(
                            ActionOutcome(OutcomeStatus.SKIPPED, "user cleared a refused step", atMs = clock.nowMs()),
                            StepDecision.RETRY_STEP, "user cleared"
                        )
                    }
                    UserChoice.SKIP -> {
                        step.status = StepStatus.SKIPPED
                        StepOutcome(skippedOutcome("skipped by user"), StepDecision.SKIP_STEP, "skipped")
                    }
                    else -> StepOutcome(cancelledOutcome("stopped by user"), StepDecision.STOP_TASK, "stopped")
                }
            }

            if (risk.requiresConfirmation && !alreadyCleared) {
                step.status = StepStatus.WAITING_FOR_USER
                transition(task, TaskState.WAITING_FOR_USER, host)
                val question = buildString {
                    append("About to: ").append(step.title).append('\n')
                    append("Risk: ").append(risk.level.name)
                    if (risk.reasons.isNotEmpty()) append(" - ").append(risk.explanation)
                    append('\n').append("In: ").append(screen.packageName ?: "?")
                }
                val ask = AskUserRequest.confirmRisk(
                    id = Ids.request(),
                    question = question,
                    risk = risk.level,
                    stepId = step.id,
                    taskId = task.id,
                    atMs = clock.nowMs()
                )
                val decision = host.askUser(ask)
                transition(task, TaskState.RUNNING, host)
                when (decision.choice) {
                    UserChoice.APPROVE -> clearedSteps += step.id
                    UserChoice.RETRY -> clearedSteps += step.id
                    UserChoice.SKIP -> {
                        step.status = StepStatus.SKIPPED
                        return StepOutcome(skippedOutcome("skipped by user"), StepDecision.SKIP_STEP, "skipped")
                    }
                    UserChoice.DENY -> {
                        step.status = StepStatus.FAILED
                        return StepOutcome(
                            ActionOutcome.failure("you declined this step", FailureReason.USER_DENIED, atMs = clock.nowMs()),
                            if (ask.denyStopsTask) StepDecision.STOP_TASK else StepDecision.SKIP_STEP,
                            "declined"
                        )
                    }
                    UserChoice.STOP ->
                        return StepOutcome(cancelledOutcome("stopped by user"), StepDecision.STOP_TASK, "stopped")
                    UserChoice.REPLY -> {
                        task.context["userReply"] = decision.text ?: ""
                        host.onMessage(Role.USER, decision.text ?: "")
                        clearedSteps += step.id
                    }
                }
            }

            // ---- precondition -------------------------------------------
            step.precondition?.let { pre ->
                val preResult = evaluator.evaluate(pre, screen)
                if (preResult.verdict == Verdict.NOT_SATISFIED) {
                    lastOutcome = ActionOutcome.failure(
                        "precondition not met: ${preResult.message}",
                        FailureReason.PRECONDITION_FAILED,
                        atMs = clock.nowMs()
                    )
                    return when (val rec = recover(task, step, host, session, control, lastOutcome, screen, attempt, stepCtx)) {
                        is RecoveryOutcome.Retry -> {
                            attempt++
                            task.retriesUsed++
                            step.attempts = attempt
                            action = rec.action
                            continue
                        }
                        RecoveryOutcome.Skip -> {
                            step.status = StepStatus.SKIPPED
                            StepOutcome(skippedOutcome("skipped during recovery"), StepDecision.SKIP_STEP, "skipped")
                        }
                        RecoveryOutcome.Stop ->
                            StepOutcome(cancelledOutcome("stopped during recovery"), StepDecision.STOP_TASK, "stopped")
                        RecoveryOutcome.GiveUp -> {
                            step.status = StepStatus.FAILED
                            StepOutcome(lastOutcome, StepDecision.FAIL_TASK, lastOutcome.message)
                        }
                    }
                }
            }

            // ---- act ------------------------------------------------------
            transition(task, TaskState.RUNNING, host)
            val outcome = when (action) {
                is Conditional -> runConditional(action, task, host, session, stepCtx, screen)
                is Repeat -> runRepeat(action, task, session, stepCtx, screen)
                is Relaunch -> executor.execute(
                    action.copy(packageName = task.context["lastPackage"] ?: action.packageName),
                    screen, session, stepCtx
                )
                else -> executor.execute(action, screen, session, stepCtx)
            }
            task.actionsUsed++

            if (outcome.needsUser && outcome.ask != null) {
                step.status = StepStatus.WAITING_FOR_USER
                transition(task, TaskState.WAITING_FOR_USER, host)
                val decision = host.askUser(outcome.ask)
                transition(task, TaskState.RUNNING, host)
                when (decision.choice) {
                    UserChoice.APPROVE, UserChoice.RETRY, UserChoice.REPLY -> {
                        clearedSteps += step.id
                        decision.text?.let { task.context["userReply"] = it }
                        attempt++
                        task.retriesUsed++
                        continue
                    }
                    UserChoice.SKIP -> {
                        step.status = StepStatus.SKIPPED
                        return StepOutcome(skippedOutcome("skipped by user"), StepDecision.SKIP_STEP, "skipped")
                    }
                    else -> {
                        step.status = StepStatus.FAILED
                        val stop = decision.choice == UserChoice.STOP || outcome.ask.denyStopsTask
                        return StepOutcome(
                            cancelledOutcome(if (stop) "stopped by user" else "declined"),
                            if (stop) StepDecision.STOP_TASK else StepDecision.SKIP_STEP,
                            "user"
                        )
                    }
                }
            }

            // ---- verify ----------------------------------------------------
            val verified = verify(step, action, screen, outcome, session, task, host)
            lastOutcome = verified

            if (verified.status == OutcomeStatus.SUCCESS) {
                step.status = StepStatus.DONE
                step.lastOutcome = verified
                step.attempts = attempt + 1
                // Progress was made: forget what the loop detector has seen.
                loopDetector.reset()
                return StepOutcome(verified, StepDecision.CONTINUE)
            }

            if (verified.status == OutcomeStatus.CANCELLED || control.stopRequested) {
                return StepOutcome(verified, StepDecision.STOP_TASK, "stopped")
            }

            // ---- recover ---------------------------------------------------
            val currentScreen = session.screens.lastSnapshot() ?: screen
            when (val rec = recover(task, step, host, session, control, verified, currentScreen, attempt, stepCtx)) {
                is RecoveryOutcome.Retry -> {
                    attempt++
                    task.retriesUsed++
                    step.attempts = attempt
                    action = rec.action
                }
                RecoveryOutcome.Skip -> {
                    step.status = StepStatus.SKIPPED
                    return StepOutcome(skippedOutcome("skipped during recovery"), StepDecision.SKIP_STEP, "skipped")
                }
                RecoveryOutcome.Stop ->
                    return StepOutcome(cancelledOutcome("stopped during recovery"), StepDecision.STOP_TASK, "stopped")
                RecoveryOutcome.GiveUp -> {
                    step.status = StepStatus.FAILED
                    step.lastOutcome = verified
                    return StepOutcome(verified, StepDecision.FAIL_TASK, verified.message)
                }
            }
        }

        step.status = StepStatus.FAILED
        step.lastOutcome = lastOutcome
        return StepOutcome(
            lastOutcome.copy(message = "gave up after $attempt attempt(s): ${lastOutcome.message}"),
            StepDecision.FAIL_TASK,
            "exhausted $maxAttempts attempts"
        )
    }

    // --------------------------------------------------------------- verify

    private suspend fun verify(
        step: TaskStep,
        action: AgentAction,
        before: ScreenSnapshot,
        outcome: ActionOutcome,
        session: DeviceSession,
        task: AgentTask,
        host: TaskRunnerHost
    ): ActionOutcome {
        if (outcome.status != OutcomeStatus.SUCCESS && outcome.status != OutcomeStatus.UNVERIFIED) {
            return outcome
        }
        val post = step.postcondition
        if (post is Always || (post == null && action.observesOnly)) {
            return outcome.copy(
                status = OutcomeStatus.SUCCESS,
                verified = true,
                signatureAfter = before.signature
            )
        }

        transition(task, TaskState.VERIFYING, host)
        val timeout = step.timeoutMs.coerceIn(400L, 30_000L)
        // Actions that only look at the device cannot change the screen, so
        // waiting for a change would just waste the timeout.
        val waitForChange = !action.observesOnly && post !is ScreenUnchanged

        val after: ScreenSnapshot = if (waitForChange) {
            session.screens.awaitChange(before.signature, timeout) ?: session.screens.observe("verify")
        } else {
            session.screens.observe("verify")
        }

        if (post == null) {
            val changed = after.signature != before.signature
            return outcome.copy(
                status = OutcomeStatus.SUCCESS,
                verified = changed,
                signatureAfter = after.signature,
                message = outcome.message + if (changed) " | screen changed" else " | screen unchanged"
            )
        }

        val result = evaluator.evaluate(post, after, before, clock.nowMs())
        return when (result.verdict) {
            Verdict.SATISFIED -> outcome.copy(
                status = OutcomeStatus.SUCCESS,
                verified = true,
                signatureAfter = after.signature,
                message = "${outcome.message} | verified: ${result.message}"
            )

            Verdict.NOT_SATISFIED -> ActionOutcome(
                status = OutcomeStatus.FAILURE,
                message = "postcondition failed: ${result.message}",
                reason = FailureReason.POSTCONDITION_FAILED,
                tier = outcome.tier,
                startedAtMs = outcome.startedAtMs,
                durationMs = (clock.elapsedRealtimeMs() - outcome.startedAtMs).coerceAtLeast(0L),
                signatureBefore = before.signature,
                signatureAfter = after.signature,
                attempts = outcome.attempts
            )

            Verdict.UNKNOWN -> outcome.copy(
                status = OutcomeStatus.SUCCESS,
                verified = false,
                signatureAfter = after.signature,
                message = "${outcome.message} | unverified: ${result.message}"
            )
        }
    }

    // ------------------------------------------------------------- recovery

    private suspend fun recover(
        task: AgentTask,
        step: TaskStep,
        host: TaskRunnerHost,
        session: DeviceSession,
        control: EngineControl,
        outcome: ActionOutcome,
        screen: ScreenSnapshot,
        attempt: Int,
        ctx: ExecContext
    ): RecoveryOutcome {
        transition(task, TaskState.RECOVERING, host)
        val appPackage = task.context["lastPackage"] ?: screen.packageName
        val plan = recoveryManager.planFor(outcome, step, screen, attempt, ctx, appPackage)
        log.i(TAG, "recovery[${plan.strategy}] ${plan.message}")
        step.note = plan.message

        if (plan.needsUser && plan.ask != null) {
            step.status = StepStatus.WAITING_FOR_USER
            transition(task, TaskState.WAITING_FOR_USER, host)
            val decision = host.askUser(plan.ask)
            transition(task, TaskState.RUNNING, host)
            return when (decision.choice) {
                UserChoice.APPROVE, UserChoice.RETRY -> RecoveryOutcome.Retry(step.action)
                UserChoice.REPLY -> {
                    task.context["userReply"] = decision.text ?: ""
                    host.onMessage(Role.USER, decision.text ?: "")
                    RecoveryOutcome.Retry(step.action)
                }
                UserChoice.SKIP -> RecoveryOutcome.Skip
                UserChoice.DENY, UserChoice.STOP -> {
                    control.requestStop()
                    RecoveryOutcome.Stop
                }
            }
        }

        if (plan.strategy == RecoveryManager.Strategy.GIVE_UP) return RecoveryOutcome.GiveUp

        // Corrective actions go through the same executor, but are never
        // themselves retried: a recovery that needs recovery is a dead end.
        plan.preActions.forEach { pre ->
            if (control.stopRequested) return RecoveryOutcome.Stop
            val current = session.screens.observe("recovery")
            val result = executor.execute(pre, current, session, ctx)
            task.actionsUsed++
            log.i(TAG, "recovery action ${pre.describe()} -> ${result.shortLabel}")
            clock.sleep(RECOVERY_SETTLE_MS)
        }

        val relaxed = plan.relaxedQuery
        return RecoveryOutcome.Retry(if (relaxed != null) step.action.withTarget(relaxed) else step.action)
    }

    // ------------------------------------------------------------ composites

    private suspend fun runConditional(
        action: Conditional,
        task: AgentTask,
        host: TaskRunnerHost,
        session: DeviceSession,
        ctx: ExecContext,
        screen: ScreenSnapshot
    ): ActionOutcome {
        val result = evaluator.evaluate(action.condition, screen)
        val branch = if (result.satisfied) action.then else action.otherwise
        host.onMessage(Role.SYSTEM, "if ${action.condition.describe()} -> ${result.message}")
        return runNested(branch, task, session, ctx, "if-branch")
    }

    private suspend fun runRepeat(
        action: Repeat,
        task: AgentTask,
        session: DeviceSession,
        ctx: ExecContext,
        @Suppress("UNUSED_PARAMETER") screen: ScreenSnapshot
    ): ActionOutcome {
        val rounds = action.maxTimes.coerceIn(1, 20)
        var last: ActionOutcome = ActionOutcome.failure("repeat body was empty", FailureReason.UNKNOWN)
        for (round in 1..rounds) {
            last = runNested(action.body, task, session, ctx, "repeat round $round")
            if (last.status != OutcomeStatus.SUCCESS && last.status != OutcomeStatus.UNVERIFIED) break
            if (action.until != null) {
                val current = session.screens.observe("repeat until")
                if (evaluator.evaluate(action.until, current).satisfied) {
                    return last.copy(message = "repeat finished after $round round(s)")
                }
            }
        }
        return last
    }

    private suspend fun runNested(
        actions: List<AgentAction>,
        task: AgentTask,
        session: DeviceSession,
        ctx: ExecContext,
        label: String
    ): ActionOutcome {
        if (actions.isEmpty()) {
            return ActionOutcome.success("$label: nothing to do", verified = true, atMs = clock.nowMs())
        }
        var last: ActionOutcome? = null
        for (action in actions) {
            if (task.actionsUsed >= task.budget.maxActions) {
                return ActionOutcome.failure(
                    "$label stopped: action budget reached", FailureReason.BUDGET_ACTIONS, atMs = clock.nowMs()
                )
            }
            val screen = session.screens.observe(label)
            val outcome = when (action) {
                is Report -> ActionOutcome.success(action.message, verified = true, atMs = clock.nowMs(), data = action.message)
                is Relaunch -> executor.execute(
                    action.copy(packageName = task.context["lastPackage"] ?: action.packageName),
                    screen, session, ctx
                )
                else -> executor.execute(action, screen, session, ctx)
            }
            task.actionsUsed++
            last = outcome
            if (outcome.status != OutcomeStatus.SUCCESS && outcome.status != OutcomeStatus.UNVERIFIED) {
                return outcome.copy(message = "$label failed: ${outcome.message}")
            }
            clock.sleep(NESTED_STEP_GAP_MS)
        }
        return last ?: ActionOutcome.success("$label done", verified = false, atMs = clock.nowMs())
    }

    // -------------------------------------------------------------- helpers

    private fun skippedOutcome(message: String) =
        ActionOutcome(OutcomeStatus.SKIPPED, message, startedAtMs = clock.nowMs())

    private fun cancelledOutcome(message: String) =
        ActionOutcome(OutcomeStatus.CANCELLED, message, FailureReason.USER_CANCELLED, startedAtMs = clock.nowMs())

    private fun recordHistory(task: AgentTask, step: TaskStep, outcome: ActionOutcome, ctx: ExecContext) {
        val message = Redactor.text(outcome.message, ctx.redact) ?: outcome.message
        task.history += StepRecord(
            stepId = step.id,
            title = step.title,
            actionLabel = step.action.label,
            outcome = outcome.shortLabel,
            status = outcome.status,
            risk = step.risk,
            tier = outcome.tier,
            startedAtMs = outcome.startedAtMs,
            durationMs = outcome.durationMs,
            signature = outcome.signatureAfter ?: outcome.signatureBefore,
            message = message.take(400)
        )
        if (task.history.size > MAX_HISTORY_PER_TASK) task.history.removeAt(0)
    }

    private fun transition(task: AgentTask, next: TaskState, host: TaskRunnerHost) {
        if (task.state == next) return
        if (!task.state.canTransitionTo(next)) {
            log.w(TAG, "illegal transition ${task.state} -> $next (task ${task.id})")
        }
        task.state = next
        task.touch(clock.nowMs())
        host.onTaskUpdated(task)
    }

    private fun finish(
        task: AgentTask,
        state: TaskState,
        message: String,
        host: TaskRunnerHost,
        reason: FailureReason? = null
    ): TaskState {
        task.state = state
        task.finishedAtMs = clock.nowMs()
        task.resultMessage = Redactor.text(message, true) ?: message
        if (reason != null) task.stopReason = reason.name
        task.touch(clock.nowMs())
        host.onTaskUpdated(task)
        log.i(TAG, "task ${task.id} -> $state ($message)")
        return state
    }

    private fun failWithBudget(
        task: AgentTask,
        reason: FailureReason,
        message: String,
        host: TaskRunnerHost
    ): TaskState {
        log.w(TAG, "budget: $message")
        return finish(task, TaskState.FAILED, message, host, reason)
    }

    companion object {
        private const val TAG = "runner"
        private const val RECOVERY_SETTLE_MS = 450L
        private const val NESTED_STEP_GAP_MS = 180L
        private const val MAX_HISTORY_PER_TASK = 120
    }
}
