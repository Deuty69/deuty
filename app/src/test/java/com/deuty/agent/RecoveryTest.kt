package com.deuty.agent

import com.deuty.agent.agent.RecoveryManager
import com.deuty.agent.agent.executor.Relaunch
import com.deuty.agent.agent.executor.Scroll
import com.deuty.agent.agent.executor.Tap
import com.deuty.agent.agent.executor.Wait
import com.deuty.agent.core.model.Direction
import com.deuty.agent.core.model.ElementQuery
import com.deuty.agent.core.model.FailureReason
import com.deuty.agent.core.model.ActionOutcome
import com.deuty.agent.core.model.RetryPolicy
import com.deuty.agent.core.model.ScreenSnapshot
import com.deuty.agent.core.model.ScreenSource
import com.deuty.agent.core.model.TaskStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Error recovery as a first-class system (requirements 10, 17, 20).
 *
 * The invariant under test: recovery must escalate in a fixed, bounded order
 * (look again -> scroll -> loosen the locator -> ask the human), it must never
 * invent a new dangerous action, and a budget or cancellation failure must end the
 * task rather than trigger another attempt.
 */
class RecoveryTest {

    private val clock = testClock()
    private val log = testLog(clock)
    private val manager = RecoveryManager(clock, log)

    private val wifiStep = TaskStep(
        id = "s1",
        title = "Tap Wi-Fi",
        action = Tap(ElementQuery(viewId = "com.android.settings:id/wifi", text = "Wi-Fi")),
        retry = RetryPolicy(maxAttempts = 4, backoffMs = 400L)
    )

    private val plainScreen = screenOf(
        packageName = "com.android.settings",
        windowTitle = "Settings",
        nodes = listOf(
            uiNode(1, text = "Network & internet"),
            uiNode(2, text = "Display"),
            uiNode(3, text = "Sound")
        ),
        clock = clock
    )

    private fun planFor(
        reason: FailureReason,
        message: String = "failed",
        step: TaskStep = wifiStep,
        screen: ScreenSnapshot = plainScreen,
        attempt: Int = 0,
        packageName: String? = "com.android.settings"
    ) = manager.planFor(
        outcome = ActionOutcome.failure(message = message, reason = reason, atMs = clock.nowMs()),
        step = step,
        screen = screen,
        attempt = attempt,
        ctx = execCtx(),
        appPackage = packageName
    )

    @Test
    fun elementNotFoundEscalatesInAFixedOrder() {
        assertEquals(RecoveryManager.Strategy.RE_OBSERVE, planFor(FailureReason.ELEMENT_NOT_FOUND, attempt = 0).strategy)

        val scrolled = planFor(FailureReason.ELEMENT_NOT_FOUND, attempt = 1)
        assertEquals(RecoveryManager.Strategy.SCROLL_THEN_RETRY, scrolled.strategy)
        assertTrue(scrolled.preActions.any { it is Scroll && it.direction == Direction.DOWN })
        assertNotNull("scrolling should also relax the locator", scrolled.relaxedQuery)

        val loosened = planFor(FailureReason.ELEMENT_NOT_FOUND, attempt = 2)
        assertEquals(RecoveryManager.Strategy.SWITCH_LOCATOR, loosened.strategy)
        assertTrue(loosened.preActions.isEmpty())

        val asked = planFor(FailureReason.ELEMENT_NOT_FOUND, attempt = 3)
        assertEquals(RecoveryManager.Strategy.ASK_USER, asked.strategy)
        assertTrue(asked.needsUser)
        assertNotNull(asked.ask)
        assertTrue(asked.ask!!.question.lowercase().contains("wifi"))
    }

    @Test
    fun relaxedQueriesDropCoordinatesAndKeepSemantics() {
        val relaxed = planFor(FailureReason.ELEMENT_NOT_FOUND, attempt = 1).relaxedQuery

        assertNotNull(relaxed)
        assertFalse("a relaxed query must still have something to match on", relaxed!!.isUnconstrained)
        assertFalse("relaxing must not turn into blind coordinates", relaxed.isCoordinateOnly)
    }

    @Test
    fun anAmbiguousMatchGoesStraightToTheUser() {
        val plan = planFor(FailureReason.AMBIGUOUS_MATCH, message = "3 candidates")
        assertEquals(RecoveryManager.Strategy.ASK_USER, plan.strategy)
        assertTrue(plan.needsUser)
    }

    @Test
    fun aPostconditionFailureRetriesTwiceThenAsks() {
        assertEquals(RecoveryManager.Strategy.RE_OBSERVE, planFor(FailureReason.POSTCONDITION_FAILED, attempt = 0).strategy)
        assertEquals(RecoveryManager.Strategy.WAIT_AND_RETRY, planFor(FailureReason.POSTCONDITION_FAILED, attempt = 1).strategy)
        val asked = planFor(FailureReason.POSTCONDITION_FAILED, attempt = 2, message = "screen did not change")
        assertEquals(RecoveryManager.Strategy.ASK_USER, asked.strategy)
        assertTrue(asked.ask!!.question.contains("Tap Wi-Fi"))
    }

    @Test
    fun aBudgetOrCancellationFailureGivesUpWithoutRetrying() {
        listOf(
            FailureReason.LOOP_DETECTED,
            FailureReason.BUDGET_ACTIONS,
            FailureReason.BUDGET_TIME,
            FailureReason.BUDGET_RETRIES,
            FailureReason.USER_CANCELLED,
            FailureReason.USER_DENIED
        ).forEach { reason ->
            val plan = planFor(reason, message = "stopped: $reason")
            assertEquals("$reason must not be retried", RecoveryManager.Strategy.GIVE_UP, plan.strategy)
            assertFalse("$reason must not retry the step", plan.retryStep)
        }
    }

    @Test
    fun aBlockedScreenAsksAndStopsOnDeny() {
        listOf(
            FailureReason.SECURE_SCREEN,
            FailureReason.PERMISSION_DIALOG,
            FailureReason.RISK_BLOCKED
        ).forEach { reason ->
            val plan = planFor(reason, message = "not allowed here")
            assertEquals(RecoveryManager.Strategy.ASK_USER, plan.strategy)
            assertTrue(plan.ask!!.denyStopsTask)
        }
    }

    @Test
    fun aCrashedAppIsRelaunchedOnce() {
        val plan = planFor(FailureReason.APP_CRASHED, attempt = 0)
        assertEquals(RecoveryManager.Strategy.RELAUNCH_APP, plan.strategy)
        assertTrue(plan.preActions.any { it is Relaunch && it.packageName == "com.android.settings" })
        assertTrue(plan.retryStep)

        val later = planFor(FailureReason.APP_CRASHED, attempt = 2)
        assertFalse("relaunching forever is a loop", later.retryStep)
    }

    @Test
    fun aPermissionDialogOnScreenBeatsTheGenericFailureReason() {
        val dialog = screenOf(
            packageName = "com.google.android.permissioncontroller",
            windowTitle = null,
            nodes = listOf(
                uiNode(1, text = "Allow Chrome to access your files?"),
                uiNode(2, text = "Allow", clickable = true),
                uiNode(3, text = "Don't allow", clickable = true)
            ),
            clock = clock
        )

        val plan = planFor(FailureReason.ELEMENT_NOT_FOUND, attempt = 0, screen = dialog)

        assertEquals(
            "the blocker is the cause; element-not-found is only the symptom",
            RecoveryManager.Strategy.ASK_USER, plan.strategy
        )
        assertTrue(plan.needsUser)
    }

    @Test
    fun aNetworkErrorWaitsAndRetriesWithinABound() {
        val plan = planFor(FailureReason.NETWORK_UNAVAILABLE, attempt = 0)
        assertEquals(RecoveryManager.Strategy.WAIT_AND_RETRY, plan.strategy)
        assertTrue(plan.preActions.any { it is Wait })
        assertTrue(plan.retryStep)

        assertFalse(planFor(FailureReason.NETWORK_UNAVAILABLE, attempt = 3).retryStep)
    }

    @Test
    fun aRejectedGestureIsNotReAttemptedForever() {
        assertEquals(RecoveryManager.Strategy.RE_OBSERVE, planFor(FailureReason.GESTURE_FAILED, attempt = 0).strategy)
        val asked = planFor(FailureReason.GESTURE_FAILED, attempt = 1)
        assertEquals(RecoveryManager.Strategy.ASK_USER, asked.strategy)
        assertTrue(asked.ask!!.denyStopsTask)
    }

    @Test
    fun failedTextInputTapsTheFieldAgain() {
        val plan = planFor(FailureReason.TEXT_INPUT_FAILED, attempt = 0)
        assertEquals(RecoveryManager.Strategy.RE_OBSERVE, plan.strategy)
        assertTrue("re-focusing the field is the cheap fix", plan.preActions.any { it is Tap })
        assertTrue(plan.retryStep)
    }

    @Test
    fun anUnreadableScreenEscalatesOnlyIfCaptureIsAllowed() {
        val opaque = screenOf(
            packageName = "com.some.game",
            windowTitle = null,
            // A game or a WebView: nodes exist but carry no usable metadata.
            nodes = listOf(
                uiNode(1, text = null, className = "android.view.SurfaceView", clickable = false),
                uiNode(2, text = null, className = "android.view.View", clickable = false)
            ),
            source = ScreenSource.SCREENSHOT,
            clock = clock
        )

        val plan = planFor(FailureReason.SCREEN_UNREADABLE, screen = opaque, attempt = 0)
        assertEquals(RecoveryManager.Strategy.ESCALATE_TO_VISION, plan.strategy)

        // Same failure with capture switched off in settings: ask instead.
        val noCapture = manager.planFor(
            outcome = ActionOutcome.failure("unreadable", FailureReason.SCREEN_UNREADABLE, atMs = clock.nowMs()),
            step = wifiStep,
            screen = opaque,
            attempt = 0,
            ctx = execCtx().copy(allowScreenshots = false),
            appPackage = "com.some.game"
        )
        assertEquals(RecoveryManager.Strategy.ASK_USER, noCapture.strategy)
    }

    @Test
    fun everyPlanCarriesAMessageForTheDebugPanel() {
        FailureReason.entries.forEach { reason ->
            val plan = planFor(reason, message = "m")
            assertTrue("$reason produced an empty message", plan.message.isNotBlank())
        }
        
    }
}
