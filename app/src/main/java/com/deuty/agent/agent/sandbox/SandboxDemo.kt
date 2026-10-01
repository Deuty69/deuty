package com.deuty.agent.agent.sandbox

import com.deuty.agent.agent.EngineControl
import com.deuty.agent.agent.TaskRunner
import com.deuty.agent.agent.TaskRunnerHost
import com.deuty.agent.agent.planner.PlanContext
import com.deuty.agent.agent.planner.PlanRequest
import com.deuty.agent.agent.planner.PlanResult
import com.deuty.agent.agent.planner.RuleBasedPlanner
import com.deuty.agent.core.model.AgentTask
import com.deuty.agent.core.model.StepStatus
import com.deuty.agent.core.model.TaskConstraints
import com.deuty.agent.core.model.TaskBudget
import com.deuty.agent.core.model.TaskSource
import com.deuty.agent.core.model.TaskState
import com.deuty.agent.core.util.AgentLog
import com.deuty.agent.core.util.Clock
import com.deuty.agent.core.util.Ids
import com.deuty.agent.core.util.LoopDetector

/**
 * Runs a real task against [SimulatedDevice].
 *
 * This is the "show, don't tell" path: before a user grants an accessibility
 * permission that can read every screen they own, they can watch the exact same
 * planner, risk gate, executor, verifier and recovery loop drive a fake phone.
 * Nothing here is a mock of the pipeline - only the device is fake.
 *
 * It is also the cheapest possible regression test for the whole engine, which is
 * why it lives in `main/` rather than in the test source set.
 */
class SandboxDemo(
    private val clock: Clock,
    private val log: AgentLog,
    private val runner: TaskRunner
) {

    data class Report(
        val instruction: String,
        val finalState: TaskState,
        val stepsTotal: Int,
        val stepsDone: Int,
        val actionsUsed: Int,
        val observations: Int,
        val deviceTrace: List<String>,
        val message: String,
        val durationMs: Long
    ) {
        val succeeded: Boolean get() = finalState == TaskState.COMPLETED

        fun summary(): String = buildString {
            append(if (succeeded) "Sandbox demo finished: " else "Sandbox demo ended in $finalState: ")
            append(message)
            append("\n$stepsDone/$stepsTotal steps done, $actionsUsed action(s), $observations observation(s)")
            if (deviceTrace.isNotEmpty()) {
                append("\nDevice calls: ")
                append(deviceTrace.take(10).joinToString(", "))
                if (deviceTrace.size > 10) append(", +${deviceTrace.size - 10} more")
            }
        }
    }

    suspend fun run(
        host: TaskRunnerHost,
        control: EngineControl,
        instruction: String = DEFAULT_INSTRUCTION,
        device: SimulatedDevice = SimulatedDevice(clock, log)
    ): Report {
        val started = clock.elapsedRealtimeMs()
        val planner = RuleBasedPlanner(clock, log, apps = device, workflows = null)
        val task = AgentTask(
            id = Ids.task(),
            goal = instruction,
            source = TaskSource.SANDBOX,
            createdAtMs = clock.nowMs(),
            constraints = TaskConstraints(offlineOnly = true, notes = "sandbox"),
            budget = TaskBudget.SANDBOX,
            state = TaskState.CREATED
        )

        val plan = when (val result = planner.plan(PlanRequest(instruction, PlanContext(currentPackage = null, screenSummary = null)))) {
            is PlanResult.Plan -> result
            is PlanResult.Clarify -> return failure(instruction, device, started, "the sandbox planner asked: ${result.question}")
            is PlanResult.Rejected -> return failure(instruction, device, started, result.reason)
            is PlanResult.NotCapable -> return failure(instruction, device, started, result.reason)
            is PlanResult.Control -> return failure(instruction, device, started, "\"$instruction\" is a control command, not a task")
        }

        task.steps += plan.steps.take(task.budget.maxSteps)
        plan.contextValues.forEach { (key, value) -> task.context[key] = value }
        task.riskLevel = plan.risk
        task.startedAtMs = clock.nowMs()

        control.reset()
        val detector = LoopDetector(task.budget.loopWindow, task.budget.loopRepeatThreshold)
        val finalState = runCatching { runner.run(task, host, device, control, detector) }
            .getOrElse { error ->
                log.e(TAG, "sandbox run threw", error)
                TaskState.FAILED
            }

        val done = task.steps.count { it.status == StepStatus.DONE || it.status == StepStatus.SKIPPED }
        val message = task.resultMessage ?: when (finalState) {
            TaskState.COMPLETED -> "every step verified"
            TaskState.CANCELLED -> "cancelled"
            else -> task.stopReason ?: "see the step list"
        }
        log.i(TAG, "sandbox finished: $finalState ($done/${task.steps.size} steps)")
        return Report(
            instruction = instruction,
            finalState = finalState,
            stepsTotal = task.steps.size,
            stepsDone = done,
            actionsUsed = task.actionsUsed,
            observations = device.observationCount,
            deviceTrace = device.trace.toList(),
            message = message,
            durationMs = clock.elapsedRealtimeMs() - started
        )
    }

    private fun failure(
        instruction: String,
        device: SimulatedDevice,
        started: Long,
        reason: String
    ) = Report(
        instruction = instruction,
        finalState = TaskState.FAILED,
        stepsTotal = 0,
        stepsDone = 0,
        actionsUsed = 0,
        observations = device.observationCount,
        deviceTrace = device.trace.toList(),
        message = reason,
        durationMs = clock.elapsedRealtimeMs() - started
    )

    /** A sandbox screen that is deliberately secure, to show the refusal path. */
    suspend fun runSecureScreenDemo(host: TaskRunnerHost, control: EngineControl): Report =
        run(
            host = host,
            control = control,
            instruction = "Open Bank and tap Confirm payment",
            device = SimulatedDevice(clock, log, startKey = "bank")
        )

    companion object {
        private const val TAG = "sandbox-demo"
        val DEFAULT_INSTRUCTION = "Open Settings, then tap Wi-Fi, then go back"
        val SECURE_INSTRUCTION = "Open Bank and tap Confirm payment"
        val RENAME_INSTRUCTION = "Open Files, then rename timetable.pdf to College Timetable.pdf"

        val DEMOS = listOf(DEFAULT_INSTRUCTION, RENAME_INSTRUCTION, SECURE_INSTRUCTION)
    }
}
