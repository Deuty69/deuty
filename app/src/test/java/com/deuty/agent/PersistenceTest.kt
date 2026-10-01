package com.deuty.agent

import com.deuty.agent.agent.executor.Back
import com.deuty.agent.agent.executor.AgentAction
import com.deuty.agent.agent.executor.FindElement
import com.deuty.agent.agent.executor.LaunchApp
import com.deuty.agent.agent.executor.LongPress
import com.deuty.agent.agent.executor.ReadScreen
import com.deuty.agent.agent.executor.Report
import com.deuty.agent.agent.executor.Scroll
import com.deuty.agent.agent.executor.Screenshot
import com.deuty.agent.agent.executor.Tap
import com.deuty.agent.agent.executor.Type
import com.deuty.agent.agent.executor.Wait
import com.deuty.agent.core.json.ActionCodecs
import com.deuty.agent.core.json.Json
import com.deuty.agent.core.json.ScreenCodecs
import com.deuty.agent.core.json.TaskCodecs
import com.deuty.agent.core.json.toTreeText
import com.deuty.agent.core.model.ChatMessage
import com.deuty.agent.core.model.Direction
import com.deuty.agent.core.model.ElementQuery
import com.deuty.agent.core.model.Role
import com.deuty.agent.core.model.RetryPolicy
import com.deuty.agent.core.model.RiskLevel
import com.deuty.agent.core.model.ScreenChanged
import com.deuty.agent.core.model.StepStatus
import com.deuty.agent.core.model.TaskState
import com.deuty.agent.core.model.TaskStep
import com.deuty.agent.core.model.WorkflowSource
import com.deuty.agent.core.util.Ids
import com.deuty.agent.storage.JsonFileStore
import com.deuty.agent.storage.TaskRepository
import com.deuty.agent.workflow.WorkflowRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Persistence (requirements 11, 18) and the codec contract.
 *
 * Two properties matter here:
 *   - Round-trip fidelity. A task that is written and read back must be equal,
 *     because "resume after process death" is a promise, not a nicety.
 *   - Graceful degradation. A truncated or hand-edited file must cost us that one
 *     record, never the whole store, and an unknown action type must decode to
 *     null rather than crash - which is what lets an older APK read a newer file.
 */
class PersistenceTest {

    private val clock = testClock()
    private val log = testLog(clock)

    private val actions: List<AgentAction> = listOf(
        Tap(ElementQuery.byAnyLabel("Wi-Fi")),
        LongPress(ElementQuery.byAnyLabel("timetable.pdf")),
        Type("hello", ElementQuery(editable = true, focused = true), clearFirst = true, pressEnter = true),
        Scroll(direction = Direction.DOWN, times = 2),
        Back,
        LaunchApp(appLabel = "Settings", packageName = "com.android.settings"),
        Wait(1500L),
        ReadScreen(),
        Screenshot("test"),
        FindElement(ElementQuery.byText("Download")),
        Report("all done")
    )

    @Test
    fun everyActionTypeSurvivesAJsonRoundTrip() {
        actions.forEach { action ->
            val encoded = ActionCodecs.encodeAction(action)
            val decoded = ActionCodecs.decodeAction(encoded)
            assertEquals("round trip failed for ${action.label}", action, decoded)
        }
    }

    @Test
    fun anUnknownActionTypeDecodesToNullInsteadOfCrashing() {
        assertNull(ActionCodecs.decodeAction(Json.obj("t" to "fly_to_the_moon", "v" to 999)))
        assertNull(ActionCodecs.decodeAction(null))
    }

    @Test
    fun aTaskSurvivesAJsonRoundTrip() {
        val task = taskOf(
            goal = "Open Settings and tap Wi-Fi",
            steps = listOf(
                TaskStep(
                    id = "s1",
                    title = "Open Settings",
                    action = LaunchApp(appLabel = "Settings", packageName = "com.android.settings"),
                    postcondition = ScreenChanged(),
                    timeoutMs = 9_000L,
                    retry = RetryPolicy(maxAttempts = 4, backoffMs = 500L),
                    status = StepStatus.DONE,
                    attempts = 2
                ),
                TaskStep(id = "s2", title = "Tap Wi-Fi", action = Tap(ElementQuery.byAnyLabel("Wi-Fi")))
            ),
            clock = clock
        )
        task.state = TaskState.RUNNING
        task.currentStep = 1
        task.actionsUsed = 3
        task.retriesUsed = 1
        task.riskLevel = RiskLevel.MEDIUM
        task.context["lastPackage"] = "com.android.settings"

        val decoded = TaskCodecs.decodeTask(TaskCodecs.encodeTask(task))

        assertNotNull(decoded)
        decoded!!
        assertEquals(task.id, decoded.id)
        assertEquals(task.goal, decoded.goal)
        assertEquals(task.state, decoded.state)
        assertEquals(task.currentStep, decoded.currentStep)
        assertEquals(task.actionsUsed, decoded.actionsUsed)
        assertEquals(task.retriesUsed, decoded.retriesUsed)
        assertEquals(task.riskLevel, decoded.riskLevel)
        assertEquals(task.source, decoded.source)
        assertEquals(task.context["lastPackage"], decoded.context["lastPackage"])
        assertEquals(task.steps.size, decoded.steps.size)
        assertEquals(task.steps[0].action, decoded.steps[0].action)
        assertEquals(task.steps[0].status, decoded.steps[0].status)
        assertEquals(task.steps[0].attempts, decoded.steps[0].attempts)
        assertEquals(task.steps[0].timeoutMs, decoded.steps[0].timeoutMs)
        assertEquals(task.steps[0].postcondition, decoded.steps[0].postcondition)
        assertEquals(task.budget.maxActions, decoded.budget.maxActions)
    }

    @Test
    fun theRunningTaskSurvivesProcessDeath() = runTest {
        val dir = tempDir("deuty-current")
        val repository = TaskRepository(JsonFileStore(dir), log)
        val task = taskOf(goal = "download the timetable", clock = clock)
        task.state = TaskState.RUNNING

        assertTrue(repository.saveCurrent(task))
        val restored = repository.loadCurrent()

        assertNotNull(restored)
        assertEquals(task.id, restored?.id)
        // A task that was RUNNING when the process died is not running any more:
        // it comes back parked, with a reason, so the UI can offer a resume.
        assertEquals(TaskState.PAUSED, restored?.state)
        assertNotNull(restored?.stopReason)

        repository.clearCurrent()
        assertNull(repository.loadCurrent())
    }

    @Test
    fun aCorruptFileFallsBackToThePreviousCopy() = runTest {
        val dir = tempDir("deuty-corrupt")
        val store = JsonFileStore(dir)
        val repository = TaskRepository(store, log)

        val first = taskOf(goal = "first", clock = clock)
        val second = taskOf(goal = "second", clock = clock)
        repository.saveCurrent(first)
        repository.saveCurrent(second)

        // Simulate the write being interrupted: the primary file is garbage, but
        // the previous good copy is still on disk as the backup.
        File(dir, "current-task.json").writeText("{\"goal\": ")
        val restored = repository.loadCurrent()

        assertNotNull("a corrupt primary must not lose the task", restored)
        assertEquals("first", restored?.goal)
    }

    @Test
    fun historyIsBounded() = runTest {
        val repository = TaskRepository(JsonFileStore(tempDir("deuty-history")), log)
        repeat(5) { index ->
            val task = taskOf(goal = "task $index", clock = clock)
            task.state = TaskState.COMPLETED
            repository.appendHistory(task, limit = 3)
        }
        val history = repository.history(limit = 10)
        assertTrue("history was ${history.size}", history.size <= 3)
    }

    @Test
    fun savingTheTranscriptReplacesItRatherThanDuplicatingIt() = runTest {
        val repository = TaskRepository(JsonFileStore(tempDir("deuty-chat-replace")), log)
        repository.saveMessages(listOf(ChatMessage(Ids.message(), Role.USER, "one", clock.nowMs())))
        repository.saveMessages(
            listOf(
                ChatMessage(Ids.message(), Role.USER, "one", clock.nowMs()),
                ChatMessage(Ids.message(), Role.AGENT, "two", clock.nowMs())
            )
        )
        assertEquals("re-saving the whole transcript must not duplicate it", 2, repository.loadMessages().size)
    }

    @Test
    fun chatMessagesRoundTrip() = runTest {
        val repository = TaskRepository(JsonFileStore(tempDir("deuty-chat")), log)
        val messages = listOf(
            ChatMessage(Ids.message(), Role.USER, "open settings", clock.nowMs()),
            ChatMessage(Ids.message(), Role.AGENT, "on it", clock.nowMs())
        )

        assertTrue(repository.appendMessages(messages))
        val restored = repository.loadMessages()

        assertEquals(2, restored.size)
        assertEquals("open settings", restored[0].text)
        assertEquals(Role.AGENT, restored[1].role)
    }

    @Test
    fun workflowsRoundTripThroughTheRepository() = runTest {
        val dir = tempDir("deuty-workflows")
        val repository = WorkflowRepository(JsonFileStore(dir), clock, log)

        val saved = repository.save(
            name = "Download college timetable",
            steps = listOf(
                TaskStep(id = "w1", title = "Open Chrome", action = LaunchApp(appLabel = "Chrome")),
                TaskStep(id = "w2", title = "Tap Download", action = Tap(ElementQuery.byAnyLabel("Download")))
            ),
            source = WorkflowSource.RECORDED,
            packages = listOf("com.android.chrome")
        )

        val reloaded = WorkflowRepository(JsonFileStore(dir), clock, log)
        val all = reloaded.all()

        assertEquals(1, all.size)
        assertEquals(saved.id, all[0].id)
        assertEquals(saved.name, all[0].name)
        assertEquals(2, all[0].steps.size)
        assertEquals(saved.steps[1].action, all[0].steps[1].action)
        assertEquals(listOf("com.android.chrome"), all[0].packages)

        val found = reloaded.findByName("download college timetable")
        assertEquals(saved.id, found?.id)

        // Instantiating gives fresh, pending steps: replaying a workflow must not
        // inherit the statuses of the run that created it.
        val instance = reloaded.instantiate(found!!)
        assertEquals(2, instance.size)
        assertTrue(instance.all { it.status == StepStatus.PENDING })
        assertTrue(instance.all { it.attempts == 0 })

        val afterRun = reloaded.recordRun(saved.id, success = true)
        assertEquals(1, afterRun?.runCount)
        assertEquals(1, afterRun?.successCount)

        assertTrue(reloaded.delete(saved.id))
        assertEquals(0, reloaded.all().size)
    }

    @Test
    fun aScreenDumpCanBeLoadedBackForOfflineReplay() {
        val snapshot = screenOf(
            packageName = "com.android.settings",
            windowTitle = "Wi-Fi",
            nodes = listOf(
                uiNode(1, text = "Wi-Fi", id = "com.android.settings:id/wifi"),
                uiNode(2, text = "CollegeWiFi", clickable = true)
            ),
            clock = clock
        )

        val decoded = ScreenCodecs.decodeSnapshot(ScreenCodecs.encodeSnapshot(snapshot))

        assertNotNull(decoded)
        decoded!!
        assertEquals(snapshot.packageName, decoded.packageName)
        assertEquals(snapshot.windowTitle, decoded.windowTitle)
        assertEquals(snapshot.nodes.size, decoded.nodes.size)
        assertEquals(snapshot.signature, decoded.signature)
        assertEquals(snapshot.nodes[0].viewIdResourceName, decoded.nodes[0].viewIdResourceName)
        assertEquals(snapshot.nodes[1].bounds, decoded.nodes[1].bounds)

        val tree = decoded.toTreeText()
        assertTrue(tree.contains("com.android.settings"))
        assertTrue(tree.contains("CollegeWiFi"))
    }

    @Test
    fun theStoreReportsWhatItIsHolding() {
        val store = JsonFileStore(tempDir("deuty-store"))
        store.writeText("a.json", "{}")
        store.writeText("b.json", "{}")

        assertTrue(store.exists("a.json"))
        assertEquals(2, store.names().size)
        assertTrue(store.totalBytes() > 0)
        assertEquals(2, store.deleteAll())
        assertFalse(store.exists("a.json"))
    }
}
