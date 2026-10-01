package com.deuty.agent

import com.deuty.agent.agent.executor.LaunchApp
import com.deuty.agent.agent.executor.Scroll
import com.deuty.agent.agent.executor.Tap
import com.deuty.agent.agent.executor.Type
import com.deuty.agent.core.model.Direction
import com.deuty.agent.core.model.FieldContains
import com.deuty.agent.core.model.InteractionKind
import com.deuty.agent.core.model.RecordedInteraction
import com.deuty.agent.core.model.UiNode
import com.deuty.agent.workflow.WorkflowRecorder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Workflow recording (requirement 9).
 *
 * The rule this suite defends: a recording is a list of *semantic* steps, not a
 * macro of coordinates. If these tests pass, replaying a workflow recorded today
 * still works after the app moves a button, changes a label's case, or adds a row
 * above it - and a password typed during a recording is not on disk anywhere.
 */
class WorkflowRecorderTest {

    private val clock = testClock()
    private val log = testLog(clock)
    private val recorder = WorkflowRecorder(clock, log)

    private val searchField: UiNode = uiNode(
        key = 1,
        text = "Search",
        id = "com.x:id/search",
        editable = true,
        className = "android.widget.EditText",
        focused = true
    )

    private val button: UiNode = uiNode(key = 2, text = "Download", id = "com.x:id/download")

    private fun interaction(
        kind: InteractionKind,
        node: UiNode?,
        text: String? = null,
        packageName: String = "com.example.app",
        direction: Direction? = null,
        ownApp: Boolean = false
    ) = RecordedInteraction(
        kind = kind,
        atMs = clock.nowMs(),
        packageName = packageName,
        node = node,
        text = text,
        direction = direction,
        ownApp = ownApp
    )

    @Test
    fun keystrokesCollapseIntoOneTypeStep() {
        recorder.start()
        recorder.onInteraction(interaction(InteractionKind.TEXT_CHANGE, searchField, text = "h"))
        recorder.onInteraction(interaction(InteractionKind.TEXT_CHANGE, searchField, text = "he"))
        recorder.onInteraction(interaction(InteractionKind.TEXT_CHANGE, searchField, text = "hello"))
        val steps = recorder.stop()

        assertEquals("recording every keystroke would replay character by character", 1, steps.size)
        val action = steps[0].action as Type
        assertEquals("hello", action.text)
        assertTrue(action.clearFirst)
        assertTrue(steps[0].postcondition is FieldContains)
    }

    @Test
    fun passwordTextIsNeverRecorded() {
        val passwordField = uiNode(
            key = 3, text = "Password", id = "com.x:id/password",
            editable = true, password = true, className = "android.widget.EditText"
        )
        recorder.start()
        recorder.onInteraction(interaction(InteractionKind.TEXT_CHANGE, passwordField, text = "hunter2"))
        val steps = recorder.stop()

        assertEquals(0, steps.size)
        assertFalse(steps.any { it.action is Type })
    }

    @Test
    fun consecutiveScrollsInTheSameDirectionMerge() {
        val list = uiNode(key = 4, text = null, scrollable = true, clickable = false)
        recorder.start()
        repeat(3) { recorder.onInteraction(interaction(InteractionKind.SCROLL, list, direction = Direction.DOWN)) }
        val steps = recorder.stop()

        assertEquals(1, steps.size)
        val action = steps[0].action as Scroll
        assertEquals(Direction.DOWN, action.direction)
        assertEquals(3, action.times)
    }

    @Test
    fun aDirectionChangeStartsANewScrollStep() {
        val list = uiNode(key = 4, text = null, scrollable = true, clickable = false)
        recorder.start()
        recorder.onInteraction(interaction(InteractionKind.SCROLL, list, direction = Direction.DOWN))
        recorder.onInteraction(interaction(InteractionKind.SCROLL, list, direction = Direction.DOWN))
        recorder.onInteraction(interaction(InteractionKind.SCROLL, list, direction = Direction.UP))
        val steps = recorder.stop()

        assertEquals(2, steps.size)
        assertEquals(2, (steps[0].action as Scroll).times)
        assertEquals(Direction.UP, (steps[1].action as Scroll).direction)
    }

    @Test
    fun aPackageChangeBecomesAnExplicitLaunchStep() {
        recorder.start()
        recorder.onInteraction(interaction(InteractionKind.TAP, button, packageName = "com.example.app"))
        recorder.onInteraction(interaction(InteractionKind.TAP, button, packageName = "com.other.app"))
        val steps = recorder.stop()

        assertEquals(3, steps.size)
        val launch = steps[1].action as LaunchApp
        assertEquals("com.other.app", launch.packageName)
        assertEquals(listOf("com.other.app"), recorder.packagesOf(steps))
    }

    @Test
    fun touchesOnOurOwnUiAreIgnored() {
        recorder.start()
        recorder.onInteraction(interaction(InteractionKind.TAP, button, ownApp = true))
        recorder.onInteraction(interaction(InteractionKind.TAP, button, ownApp = true))
        val steps = recorder.stop()

        assertEquals("pressing STOP must not become a workflow step", 0, steps.size)
    }

    @Test
    fun recordedTargetsAreSemanticNotCoordinates() {
        recorder.start()
        recorder.onInteraction(interaction(InteractionKind.TAP, button))
        val steps = recorder.stop()

        val query = (steps[0].action as Tap).target
        assertEquals("com.x:id/download", query.viewId)
        assertEquals("Download", query.text)
        assertNull("a labelled node must not be recorded as bounds", query.bounds)
        assertNull(query.point)
        assertFalse(query.isCoordinateOnly)
    }

    @Test
    fun anUnlabelledNodeKeepsItsBoundsAsALastResort() {
        val bare = uiNode(key = 9, text = null, id = null, className = "android.view.View")
        recorder.start()
        recorder.onInteraction(interaction(InteractionKind.TAP, bare))
        val steps = recorder.stop()

        val query = (steps[0].action as Tap).target
        assertTrue("with no metadata at all, bounds are the only option", query.bounds != null)
    }

    @Test
    fun aFocusTapBeforeTypingIsMergedAway() {
        recorder.start()
        recorder.onInteraction(interaction(InteractionKind.TAP, searchField))
        recorder.onInteraction(interaction(InteractionKind.TEXT_CHANGE, searchField, text = "timetable"))
        val steps = recorder.stop()

        assertEquals("Type focuses the field itself; the tap only adds a failure point", 1, steps.size)
        assertTrue(steps[0].action is Type)
    }

    @Test
    fun previewReflectsWhatHasBeenRecordedSoFar() {
        recorder.start()
        assertTrue(recorder.isRecording)
        recorder.onInteraction(interaction(InteractionKind.TAP, button))
        recorder.onInteraction(interaction(InteractionKind.LONG_PRESS, button))

        assertEquals(2, recorder.preview().size)
        assertEquals(2, recorder.interactionCount)
        assertTrue(recorder.isRecording)
    }

    @Test
    fun cancelThrowsEverythingAway() {
        recorder.start()
        recorder.onInteraction(interaction(InteractionKind.TAP, button))
        recorder.cancel()

        assertFalse(recorder.isRecording)
        assertEquals(0, recorder.stop().size)
    }

    @Test
    fun interactionsBeforeStartAreIgnored() {
        recorder.onInteraction(interaction(InteractionKind.TAP, button))
        assertEquals(0, recorder.interactionCount)
        assertEquals(0, recorder.stop().size)
    }
}
