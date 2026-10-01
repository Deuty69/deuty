package com.deuty.agent

import com.deuty.agent.agent.EngineControl
import com.deuty.agent.agent.planner.PlanContext
import com.deuty.agent.agent.planner.PlanRequest
import com.deuty.agent.agent.planner.PlanResult
import com.deuty.agent.agent.planner.RuleBasedPlanner
import com.deuty.agent.agent.sandbox.SandboxDemo
import com.deuty.agent.agent.sandbox.SimNode
import com.deuty.agent.agent.sandbox.SimScreen
import com.deuty.agent.agent.sandbox.SimulatedDevice
import com.deuty.agent.core.model.AgentSettings
import com.deuty.agent.core.model.AgentTask
import com.deuty.agent.core.model.StepStatus
import com.deuty.agent.core.model.TaskBudget
import com.deuty.agent.core.model.TaskState
import com.deuty.agent.core.model.UserChoice
import com.deuty.agent.core.util.Ids
import com.deuty.agent.core.util.LoopDetector
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * End-to-end tests of the automation pipeline against a simulated device.
 *
 * These are the tests that matter most: they run the real planner, risk manager,
 * executor, resolver, verifier, recovery manager, loop detector and budget guards
 * - only the phone is fake. If one of these passes, the same code path will behave
 * the same way on a device, modulo the framework's own reliability.
 *
 * They also encode the safety requirements as assertions rather than prose:
 * a secure screen is never touched, a dead element cannot loop forever, a missing
 * app is reported instead of crashing, and STOP wins immediately.
 */
class SandboxPipelineTest {

    private class Fixture(
        startKey: String = "home",
        world: Map<String, SimScreen> = SimulatedDevice.defaultWorld()
    ) {
        val clock = testClock()
        val log = testLog(clock)
        val device = SimulatedDevice(clock, log, screens = world, startKey = startKey)
        val runner = buildRunner(clock, log)
        val planner = RuleBasedPlanner(clock, log, apps = device, workflows = null)

        fun host(
            answer: UserChoice = UserChoice.APPROVE,
            settings: AgentSettings = AgentSettings()
        ) = RecordingHost(settings, answer)

        suspend fun execute(
            instruction: String,
            host: RecordingHost,
            control: EngineControl = EngineControl(),
            budget: TaskBudget = TaskBudget.SANDBOX
        ): AgentTask {
            val result = planner.plan(
                PlanRequest(instruction, PlanContext(currentPackage = null, screenSummary = null))
            )
            assertTrue("expected a plan for \"$instruction\" but got $result", result is PlanResult.Plan)
            val plan = result as PlanResult.Plan
            val task = AgentTask(
                id = Ids.task(),
                goal = instruction,
                source = plan.source,
                createdAtMs = clock.nowMs(),
                budget = budget,
                state = TaskState.CREATED
            )
            task.steps += plan.steps
            task.riskLevel = plan.risk
            task.startedAtMs = clock.nowMs()
            val finalState = runner.run(task, host, device, control, LoopDetector())
            assertEquals("the runner must leave the task in the state it returns", finalState, task.state)
            return task
        }
    }

    @Test
    fun plansAndRunsTheMvpCommand() = runTest {
        val fixture = Fixture()
        val host = fixture.host()

        val task = fixture.execute("Open Settings, then tap Wi-Fi, then go back", host)

        assertEquals(TaskState.COMPLETED, task.state)
        assertEquals(3, task.steps.size)
        assertTrue(task.steps.all { it.isDone })
        // back from Wi-Fi lands on Settings, which is where the run must end
        assertEquals("settings", fixture.device.screenKey())
        assertTrue("the agent must have clicked something", fixture.device.clickCount >= 1)
        assertTrue("every step observes first", fixture.device.observationCount >= 3)
        assertEquals("nothing risky in this flow, so nothing should have been asked", 0, host.asks.size)
        assertTrue(
            "the launch must resolve to a real package, not a label guess",
            fixture.device.trace.any { it.startsWith("launch(com.android.settings)") }
        )
    }

    @Test
    fun neverTouchesASecureScreen() = runTest {
        val fixture = Fixture(startKey = "bank")
        val host = fixture.host(answer = UserChoice.DENY)

        val task = fixture.execute("tap Confirm payment", host)

        assertNotEquals(TaskState.COMPLETED, task.state)
        assertEquals("a secure screen must never receive a click", 0, fixture.device.clickCount)
        assertEquals(0, fixture.device.gestureCount)
        assertTrue("the user must have been asked rather than bypassed", host.asks.isNotEmpty())
    }

    @Test
    fun renameFlowIsVerifiedAgainstTheNewName() = runTest {
        val fixture = Fixture()
        val host = fixture.host()

        val task = fixture.execute("Open Files, then rename timetable.pdf to College Timetable.pdf", host)

        assertEquals(TaskState.COMPLETED, task.state)
        assertTrue("the rename must type the new name", fixture.device.textSetCount >= 1)
        assertTrue("a rename starts with a long press", fixture.device.trace.any { it.startsWith("longClick") })
        assertTrue(
            "confirming a rename is at least MEDIUM risk, so it must be gated",
            host.asks.isNotEmpty()
        )
    }

    @Test
    fun moveFlowWalksFoldersAndVerifiesTheDestination() = runTest {
        val fixture = Fixture()
        val host = fixture.host()

        val task = fixture.execute("Open Files, then move timetable.pdf to College", host)

        assertEquals(TaskState.COMPLETED, task.state)
        assertTrue(fixture.device.trace.any { it.startsWith("longClick") })
        assertTrue(
            "the destination folder must be opened by label, not by coordinates",
            fixture.device.trace.any { it.contains("College") }
        )
    }

    @Test
    fun deadElementFailsInsteadOfLoopingForever() = runTest {
        val world = mapOf(
            "dead" to SimScreen(
                key = "dead",
                packageName = "com.sim.dead",
                appLabel = "Dead",
                title = "Dead end",
                nodes = listOf(
                    // The framework reports success and nothing happens: the exact
                    // failure mode that makes verification mandatory.
                    SimNode(SimulatedDevice.node(0, "Go", "go"), swallowClick = true)
                )
            )
        )
        val fixture = Fixture(startKey = "dead", world = world)
        val host = fixture.host()

        val task = fixture.execute("tap Go", host)

        assertEquals(TaskState.FAILED, task.state)
        assertTrue(
            "the agent tapped ${fixture.device.clickCount} times; a dead element must not loop",
            fixture.device.clickCount <= 12
        )
        assertTrue(task.steps.any { it.status == StepStatus.FAILED })
    }

    @Test
    fun missingAppIsReportedNotCrashed() = runTest {
        val fixture = Fixture()
        val host = fixture.host()

        val task = fixture.execute("Open Nonexistent", host)

        assertNotEquals(TaskState.COMPLETED, task.state)
        assertTrue("the reason must reach the user", task.resultMessage != null || task.stopReason != null)
    }

    @Test
    fun actionBudgetStopsARunawayTask() = runTest {
        val fixture = Fixture()
        val host = fixture.host()

        val task = fixture.execute(
            "tap Settings, then tap Settings, then tap Settings, then tap Settings",
            host,
            budget = TaskBudget.SANDBOX.copy(maxActions = 2)
        )

        assertNotEquals(TaskState.COMPLETED, task.state)
        assertTrue("actions used = ${task.actionsUsed}", task.actionsUsed <= 4)
    }

    @Test
    fun stopWinsBeforeAnyActionRuns() = runTest {
        val fixture = Fixture()
        val host = fixture.host()
        val control = EngineControl()
        control.requestStop()

        val task = fixture.execute("Open Settings, then tap Wi-Fi", host, control)

        assertEquals(TaskState.CANCELLED, task.state)
        assertEquals(0, fixture.device.clickCount)
    }

    @Test
    fun readScreenIsAnObservationNotAnAction() = runTest {
        val fixture = Fixture()
        val host = fixture.host()

        val task = fixture.execute("read the screen", host)

        assertEquals(TaskState.COMPLETED, task.state)
        assertEquals("reading must not touch the device", 0, fixture.device.clickCount)
        assertEquals(0, fixture.device.gestureCount)
    }

    @Test
    fun sandboxDemoDrivesTheWholePipeline() = runTest {
        val clock = testClock()
        val log = testLog(clock)
        val demo = SandboxDemo(clock, log, buildRunner(clock, log))
        val host = RecordingHost()

        val report = demo.run(host, EngineControl())

        assertTrue(report.summary(), report.succeeded)
        assertEquals(3, report.stepsTotal)
        assertEquals(3, report.stepsDone)
        assertTrue(report.deviceTrace.isNotEmpty())
    }
}
