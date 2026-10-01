package com.deuty.agent.di

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import com.deuty.agent.agent.AgentEngine
import com.deuty.agent.agent.ElementResolver
import com.deuty.agent.agent.RecoveryManager
import com.deuty.agent.agent.RiskManager
import com.deuty.agent.agent.StateManager
import com.deuty.agent.agent.TaskRunner
import com.deuty.agent.agent.executor.ActionExecutor
import com.deuty.agent.agent.executor.ExpectationEvaluator
import com.deuty.agent.agent.planner.LocalModelPlanner
import com.deuty.agent.agent.planner.PlannerProvider
import com.deuty.agent.agent.planner.RemotePlanner
import com.deuty.agent.agent.planner.RuleBasedPlanner
import com.deuty.agent.agent.port.AgentView
import com.deuty.agent.agent.port.SessionHolder
import com.deuty.agent.agent.sandbox.SandboxDemo
import com.deuty.agent.core.model.AgentSettings
import com.deuty.agent.core.model.AgentStatus
import com.deuty.agent.core.util.AgentLog
import com.deuty.agent.core.util.Clock
import com.deuty.agent.core.util.SystemTime
import com.deuty.agent.platform.AccessibilityDeviceSession
import com.deuty.agent.platform.AccessibilityInteractionEngine
import com.deuty.agent.platform.AccessibilityScreenSource
import com.deuty.agent.platform.AppDirectoryImpl
import com.deuty.agent.platform.InteractionMapper
import com.deuty.agent.platform.NodeTable
import com.deuty.agent.platform.OverlayController
import com.deuty.agent.platform.PermissionStatus
import com.deuty.agent.storage.JsonFileStore
import com.deuty.agent.platform.PreferencesStore
import com.deuty.agent.storage.TaskRepository
import com.deuty.agent.ui.MainActivity
import com.deuty.agent.ui.overlay.OverlayCallbacks
import com.deuty.agent.vision.NoOcrEngine
import com.deuty.agent.vision.OcrVisionService
import com.deuty.agent.workflow.WorkflowRecorder
import com.deuty.agent.workflow.WorkflowRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

/**
 * The composition root.
 *
 * No DI framework, on purpose. The graph is ~20 objects with one instance each
 * and one lifetime (the process); Dagger/Hilt/Koin would add an annotation
 * processor, a codegen step and a runtime to save about 60 lines that are also
 * the clearest documentation of how the system fits together. Every dependency is
 * still injected by constructor, so every class remains unit-testable and the
 * whole graph can be built in a JVM test with fakes.
 *
 * Lifetime: one container per process, created by [com.deuty.agent.AgentApplication].
 * The engine lives here rather than in the accessibility service so that opening
 * the chat activity does not require the service, and so that toggling the service
 * off and on does not throw away a running task's state.
 *
 * Layering check, visible in this file: `core/`, `agent/`, `workflow/`, `storage/`
 * never appear with an `android.*` import; only `platform/`, `vision/`, `ui/` and
 * this container do.
 */
class AgentContainer(context: Context) {

    private val appContext: Context = context.applicationContext

    // ---------------------------------------------------------------- core

    val clock: Clock = SystemTime
    val log: AgentLog = AgentLog(capacity = 500, clock = clock)
    val prefs: PreferencesStore = PreferencesStore(appContext)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate

    /** Public so the inspector can write screen dumps next to the task files. */
    val store = JsonFileStore(File(appContext.filesDir, DATA_DIR))
    val tasks = TaskRepository(store, log)
    val workflows = WorkflowRepository(store, clock, log)
    val recorder = WorkflowRecorder(clock, log)

    // --------------------------------------------------------------- agent

    val resolver = ElementResolver()
    val evaluator = ExpectationEvaluator(resolver)
    val riskManager = RiskManager(log)
    val recoveryManager = RecoveryManager(clock, log)
    val executor = ActionExecutor(resolver, clock, log)
    val runner = TaskRunner(clock, log, riskManager, recoveryManager, resolver, evaluator, executor)
    val stateManager = StateManager(tasks, log, clock, scope)
    val sessions = SessionHolder()

    // ------------------------------------------------------------ platform

    private val nodeTable = NodeTable(log)

    @Volatile
    private var service: AccessibilityService? = null

    private val serviceRef: () -> AccessibilityService? = { service }

    val screenSource = AccessibilityScreenSource(
        serviceRef = serviceRef,
        nodeTable = nodeTable,
        clock = clock,
        log = log,
        scope = scope,
        mainDispatcher = mainDispatcher,
        ownPackage = appContext.packageName
    )

    val interaction = AccessibilityInteractionEngine(
        serviceRef = serviceRef,
        nodeTable = nodeTable,
        screens = screenSource,
        clock = clock,
        log = log,
        mainDispatcher = mainDispatcher
    )

    val apps = AppDirectoryImpl(appContext, log)
    val vision = OcrVisionService(NoOcrEngine, log)
    val mapper = InteractionMapper(clock, log, appContext.packageName)

    private val session = AccessibilityDeviceSession(
        serviceRef = serviceRef,
        interaction = interaction,
        screens = screenSource,
        apps = apps,
        vision = vision
    )

    val overlay = OverlayController(appContext, prefs, log)

    // --------------------------------------------------------------- engine

    val plannerProvider = PlannerProvider(
        registry = listOf(
            RuleBasedPlanner(clock, log, apps, workflows),
            LocalModelPlanner(log),
            RemotePlanner(log)
        ),
        settings = prefs,
        log = log
    )

    val sandbox = SandboxDemo(clock, log, runner)

    val engine = AgentEngine(
        clock = clock,
        log = log,
        settingsProvider = prefs,
        tasks = tasks,
        workflows = workflows,
        planner = plannerProvider,
        runner = runner,
        stateManager = stateManager,
        recorder = recorder,
        sessions = sessions,
        sandbox = sandbox,
        scope = scope,
        mainDispatcher = mainDispatcher
    )

    /** Set by the inspector while it is open: screen events keep flowing then. */
    @Volatile
    var inspectorLive: Boolean = false

    /** The overlay, seen through the engine's port. Keeps Android out of agent/. */
    private val overlayView = object : AgentView {
        override val isVisible: Boolean get() = overlay.isAttached
        override fun render(status: AgentStatus) = overlay.render(status)
        override fun expand() {
            overlay.setExpanded(true)
        }
    }

    private val overlayCallbacks = object : OverlayCallbacks {
        override fun onStop() = engine.stopTask()
        override fun onPauseResume() = engine.togglePause()
        override fun onRetry() = engine.retryStep()
        override fun onSkipStep() = engine.skipStep()
        override fun onChoice(label: String) = engine.answer(label)
        override fun onReply(text: String) = engine.answer(text)

        override fun onOpenAgent() {
            val intent = Intent(appContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            runCatching { appContext.startActivity(intent) }
                .onFailure { log.w(TAG, "could not open the agent screen", it) }
        }

        override fun onToggleExpand() = overlay.toggleExpanded()
    }

    init {
        applySettings(prefs.current())
        engine.attachView(overlayView)
        overlay.callbacks = overlayCallbacks

        // Settings -> engine behaviour, without the engine knowing about prefs.
        scope.launch { prefs.changes.collect { applySettings(it) } }

        // Screen snapshots -> engine, for the debug panel only. Cheap when off.
        scope.launch { screenSource.snapshots.collect { engine.onScreenSnapshot(it) } }

        // Power state: the tree is only walked on events while something actually
        // needs a live view of the screen. Idle = zero cost (requirement 3).
        scope.launch {
            engine.status.collect { status ->
                screenSource.active = status.busy || status.recording || inspectorLive
            }
        }

        scope.launch(mainDispatcher) {
            runCatching { engine.start() }
                .onFailure { log.e(TAG, "engine start failed", it) }
        }
        log.i(TAG, "container ready (planner=${plannerProvider.activeId})")
    }

    private fun applySettings(settings: AgentSettings) {
        log.verbose = settings.logEvents || settings.debugMode
        log.redactEnabled = settings.redact
        screenSource.logEvents = settings.logEvents
    }

    // ------------------------------------------------------- service hooks

    fun onServiceConnected(service: AccessibilityService) {
        this.service = service
        nodeTable.clear()
        sessions.attach(session)
        applySettings(prefs.current())
        if (PermissionStatus.canDrawOverlays(appContext)) {
            overlay.show(expand = false)
        } else {
            log.w(TAG, "overlay permission missing; the capsule stays hidden")
        }
        engine.onSessionChanged()
        log.i(TAG, "accessibility service connected")
    }

    fun onServiceDisconnected(service: AccessibilityService, reason: String) {
        if (this.service !== service) return
        this.service = null
        screenSource.active = false
        nodeTable.clear()
        sessions.detach(session.id)
        overlay.hide()
        engine.onSessionChanged()
        // The process may be killed right after the user disables the service, so
        // anything in flight goes to disk now rather than on a best-effort timer.
        scope.launch { runCatching { stateManager.flush() } }
        log.w(TAG, "accessibility service disconnected ($reason)")
    }

    fun onAccessibilityEvent(event: AccessibilityEvent) {
        screenSource.onEvent(event)
        if (recorder.isRecording) {
            val interaction = mapper.map(event) ?: return
            engine.onUserInteraction(interaction)
        }
    }

    /** Called by the UI so a workflow can be replayed from a list tap. */
    val isServiceConnected: Boolean get() = service != null

    companion object {
        private const val TAG = "container"
        private const val DATA_DIR = "agent"
    }
}
