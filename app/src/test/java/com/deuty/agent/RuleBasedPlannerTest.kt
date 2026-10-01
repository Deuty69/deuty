package com.deuty.agent

import com.deuty.agent.agent.executor.Back
import com.deuty.agent.agent.executor.LaunchApp
import com.deuty.agent.agent.executor.LongPress
import com.deuty.agent.agent.executor.ReadScreen
import com.deuty.agent.agent.executor.Scroll
import com.deuty.agent.agent.executor.Tap
import com.deuty.agent.agent.executor.Type
import com.deuty.agent.agent.executor.Wait
import com.deuty.agent.agent.planner.ControlCommand
import com.deuty.agent.agent.planner.PlanContext
import com.deuty.agent.agent.planner.PlanRequest
import com.deuty.agent.agent.planner.PlanResult
import com.deuty.agent.agent.planner.RuleBasedPlanner
import com.deuty.agent.core.model.Direction
import com.deuty.agent.core.model.ElementPresent
import com.deuty.agent.core.model.RiskLevel
import com.deuty.agent.core.model.TaskSource
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The offline grammar (requirements 12, 13).
 *
 * The rule-based planner is what makes the app usable with no model and no
 * network, so its contract is worth pinning down: what it understands, what it
 * refuses to guess about, and - just as important - what it must NOT mistake for
 * something else ("start the timer" is a task, not a workflow name).
 */
class RuleBasedPlannerTest {

    private val clock = testClock()
    private val log = testLog(clock)
    private val planner = RuleBasedPlanner(clock, log, apps = null, workflows = null)

    private suspend fun plan(text: String): PlanResult =
        planner.plan(PlanRequest(text, PlanContext(currentPackage = null, screenSummary = null)))

    private suspend fun planOf(text: String): PlanResult.Plan {
        val result = plan(text)
        assertTrue("expected a plan for \"$text\" but got $result", result is PlanResult.Plan)
        return result as PlanResult.Plan
    }

    @Test
    fun openBecomesALaunchStep() = runTest {
        val plan = planOf("Open Settings")
        assertEquals(1, plan.steps.size)
        val action = plan.steps[0].action as LaunchApp
        assertEquals("Settings", action.appLabel)
        assertEquals("Open Settings", plan.steps[0].title)
        assertTrue("a launch must be verified", plan.steps[0].postcondition != null)
    }

    @Test
    fun politenessAndPunctuationAreIgnored() = runTest {
        val plan = planOf("Please open Settings!")
        assertEquals(1, plan.steps.size)
        assertEquals("Settings", (plan.steps[0].action as LaunchApp).appLabel)
    }

    @Test
    fun tapBecomesASemanticTargetNotCoordinates() = runTest {
        val plan = planOf("tap Wi-Fi")
        val action = plan.steps[0].action as Tap
        assertEquals("wi-fi", action.target.text?.lowercase())
        assertFalse(action.target.isCoordinateOnly)
        assertEquals(RiskLevel.LOW, plan.risk)
    }

    @Test
    fun typeIntoTargetsTheNamedField() = runTest {
        val plan = planOf("type 500 into Amount")
        val action = plan.steps[0].action as Type
        assertEquals("500", action.text)
        assertEquals("amount", action.target?.text?.lowercase())
        assertTrue(action.clearFirst)
    }

    @Test
    fun quotedTextKeepsItsSpaces() = runTest {
        val plan = planOf("type \"college timetable\" into search")
        val action = plan.steps[0].action as Type
        assertEquals("college timetable", action.text)
    }

    @Test
    fun compoundInstructionsBecomeSeveralSteps() = runTest {
        val plan = planOf("Open Settings and tap Wi-Fi")
        assertEquals(2, plan.steps.size)
        assertTrue(plan.steps[0].action is LaunchApp)
        assertTrue(plan.steps[1].action is Tap)
    }

    @Test
    fun sequencingWordsSplitClauses() = runTest {
        val plan = planOf("open Files, then rename timetable.pdf to College Timetable.pdf")
        assertTrue(plan.steps[0].action is LaunchApp)
        assertTrue("a rename starts by finding the item", plan.steps[1].action is LongPress || plan.steps[1].postcondition is ElementPresent)
        assertEquals(7, plan.steps.size)
        assertTrue("the rename must end by verifying the new name", plan.steps.last().postcondition is ElementPresent)
        assertEquals(RiskLevel.MEDIUM, plan.risk)
    }

    @Test
    fun moveWalksEveryFolderInTheDestination() = runTest {
        val plan = planOf("move timetable.pdf to College/Assignments")
        assertEquals(7, plan.steps.size)
        assertTrue(plan.steps[1].action is LongPress)
        val titles = plan.steps.joinToString(" | ") { it.title }
        assertTrue(titles, titles.contains("College"))
        assertTrue(titles, titles.contains("Assignments"))
    }

    @Test
    fun scrollCarriesDirectionAndCount() = runTest {
        val action = planOf("scroll down 3 times").steps[0].action as Scroll
        assertEquals(Direction.DOWN, action.direction)
        assertEquals(3, action.times)
    }

    @Test
    fun navigationAndWaitingAreUnderstood() = runTest {
        assertTrue(planOf("go back").steps[0].action is Back)
        assertEquals(5_000L, (planOf("wait 5 seconds").steps[0].action as Wait).millis)
        assertTrue(planOf("read the screen").steps[0].action is ReadScreen)
    }

    @Test
    fun searchOpensTheBoxThenTypes() = runTest {
        val plan = planOf("search for wifi in settings")
        assertEquals(3, plan.steps.size)
        assertTrue(plan.steps[0].action is LaunchApp)
        assertTrue(plan.steps[1].action is Tap)
        val type = plan.steps[2].action as Type
        assertEquals("wifi", type.text)
        assertTrue("search must submit", type.pressEnter)
    }

    @Test
    fun controlWordsNeverBecomeTasks() = runTest {
        assertEquals(ControlCommand.STOP, (plan("stop") as PlanResult.Control).command)
        assertEquals(ControlCommand.PAUSE, (plan("pause") as PlanResult.Control).command)
        assertEquals(ControlCommand.RESUME, (plan("resume") as PlanResult.Control).command)
        assertEquals(ControlCommand.SKIP, (plan("skip this step") as PlanResult.Control).command)
        assertEquals(ControlCommand.WHY_STUCK, (plan("why are you stuck?") as PlanResult.Control).command)
        assertEquals(ControlCommand.HELP, (plan("what can you do") as PlanResult.Control).command)
    }

    @Test
    fun anOrdinarySentenceStartingWithAVerbIsNotAWorkflowRequest() = runTest {
        // "start the timer" must not be swallowed by the workflow grammar and
        // answered with "I don't have a workflow called the timer".
        val result = plan("start the timer")
        assertFalse(result is PlanResult.Clarify)
    }

    @Test
    fun anExplicitWorkflowRequestIsRejectedWithoutStorage() = runTest {
        val result = plan("run workflow download timetable")
        assertTrue("expected a refusal, got $result", result is PlanResult.Rejected)
    }

    @Test
    fun nonsenseIsNotGuessedAt() = runTest {
        val result = plan("make me a coffee")
        assertTrue("expected NotCapable, got $result", result is PlanResult.NotCapable)
    }

    @Test
    fun emptyInputIsNotGuessedAt() = runTest {
        assertTrue(plan("   ") is PlanResult.NotCapable)
    }

    @Test
    fun plansAreTypedStepsFromTheUserTextSource() = runTest {
        val plan = planOf("tap Wi-Fi")
        assertEquals(TaskSource.USER_TEXT, plan.source)
        assertEquals("rule", plan.plannerId)
        assertTrue(plan.steps.all { it.timeoutMs > 0 })
        assertTrue(plan.steps.all { it.id.isNotBlank() })
    }
}
