package com.deuty.agent.agent.sandbox

import com.deuty.agent.agent.port.AppDirectory
import com.deuty.agent.agent.port.DeviceSession
import com.deuty.agent.agent.port.EngineCapabilities
import com.deuty.agent.agent.port.EngineResult
import com.deuty.agent.agent.port.GlobalAction
import com.deuty.agent.agent.port.InteractionEngine
import com.deuty.agent.agent.port.ScreenSource
import com.deuty.agent.core.model.Bounds
import com.deuty.agent.core.model.Direction
import com.deuty.agent.core.model.FailureReason
import com.deuty.agent.core.model.MatchedElement
import com.deuty.agent.core.model.OcrLine
import com.deuty.agent.core.model.ElementQuery
import com.deuty.agent.core.model.Point
import com.deuty.agent.core.model.ScreenCapture
import com.deuty.agent.core.model.ScreenSnapshot
import com.deuty.agent.core.model.UiNode
import com.deuty.agent.core.util.AgentLog
import com.deuty.agent.core.util.Clock
import com.deuty.agent.core.util.SystemTime
import com.deuty.agent.core.util.TextMatch
import com.deuty.agent.core.util.withSignature
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * A fake phone.
 *
 * This is not a toy: it is the same [DeviceSession] the real accessibility
 * service provides, so the *entire* pipeline - planner, risk gate, executor,
 * verifier, recovery, loop detector, budgets - runs against it unchanged. That
 * gives three things the project would otherwise not have:
 *
 *   1. Unit tests for automation logic that would otherwise need a device.
 *   2. A "sandbox demo" the user can run before granting accessibility, which is
 *      the honest way to show what the agent does.
 *   3. Proof of requirement 2: the engine is usable without an AI model and
 *      without a specific device.
 *
 * It also encodes the *failure* modes, not just the happy path: nodes can be
 * marked as not clickable (forcing the gesture fallback), screens can be marked
 * secure, apps can be missing, and taps can be configured to do nothing so the
 * verifier and recovery paths get exercised.
 */
class SimulatedDevice(
    private val clock: Clock = SystemTime,
    private val log: AgentLog? = null,
    private val screens: Map<String, SimScreen> = defaultWorld(),
    startKey: String = "home",
    private val width: Int = 1080,
    private val height: Int = 2340
) : DeviceSession, InteractionEngine, ScreenSource, AppDirectory {

    // ------------------------------------------------------- DeviceSession

    override val id = "sandbox"
    override val available = true
    override val interaction: InteractionEngine get() = this
    override val screens: ScreenSource get() = this
    override val apps: AppDirectory get() = this
    override val vision: com.deuty.agent.agent.port.VisionService
        get() = com.deuty.agent.agent.port.NoVision

    // --------------------------------------------------- InteractionEngine

    override val name = "simulated"

    // `available` is declared once above: DeviceSession, InteractionEngine and
    // ScreenSource all ask for the same property name, so one declaration
    // satisfies all three.
    override val capabilities = EngineCapabilities(
        gestures = true,
        nodeActions = true,
        screenshots = true,
        globalActions = true,
        clipboard = true,
        launchApps = true,
        imeActionInjection = true,
        description = "in-memory simulated device"
    )

    private var currentKey: String = startKey
    private val history = ArrayDeque<String>()
    private val typedText = mutableMapOf<String, String>()
    private var scrollOffset = 0

    /** id-suffix -> new label, applied by a rename confirmation. */
    private val renameOverrides = mutableMapOf<String, String>()

    /** screen key -> nodes added at runtime (a file that was just moved here). */
    private val extraNodes = mutableMapOf<String, MutableList<SimNode>>()

    /** labels hidden everywhere (the file that was just moved away). */
    private val removedLabels = mutableSetOf<String>()

    private var lastLongPressedLabel: String? = null

    /** Statistics for tests and the debug panel. */
    var clickCount = 0
        private set
    var gestureCount = 0
        private set
    var textSetCount = 0
        private set
    var scrollCount = 0
        private set
    var observationCount = 0
        private set

    val trace: MutableList<String> = mutableListOf()

    private val current: SimScreen
        get() = screens[currentKey] ?: screens.getValue(startKey)

    override suspend fun click(target: MatchedElement): EngineResult {
        clickCount++
        val screen = current
        if (screen.secure) {
            return EngineResult.fail("simulated secure screen refuses input", FailureReason.SECURE_SCREEN)
        }
        val nodeKey = target.node?.label ?: target.node?.idName
        val simNode = screen.nodes.firstOrNull { it.node.label == nodeKey || it.node.idName == nodeKey }
        trace += "click(${target.describe()})"
        log?.d(TAG, "click ${target.describe()} on $currentKey")

        if (simNode == null) {
            // Coordinate taps land somewhere: treat them as a no-op success, which
            // is exactly how a mistap on a real device behaves (and is why the
            // verifier exists).
            gestureCount++
            return EngineResult.ok("tapped empty space")
        }
        if (simNode.swallowClick) {
            return EngineResult.ok("click accepted but nothing happened")
        }
        if (simNode.node.isEditable) {
            return EngineResult.ok("focused ${simNode.node.label}")
        }
        simNode.renameTarget?.let { targetId -> applyRename(targetId) }
        if (simNode.moveHere) applyMove(simNode.onClick ?: currentKey)
        simNode.onClick?.let { next ->
            navigate(next)
            return EngineResult.ok("opened $next")
        }
        return EngineResult.ok("clicked ${simNode.node.label ?: "node"}")
    }

    override suspend fun longClick(target: MatchedElement, durationMs: Long): EngineResult {
        clickCount++
        trace += "longClick(${target.describe()})"
        val screen = current
        val nodeKey = target.node?.label ?: target.node?.idName
        val simNode = screen.nodes.firstOrNull { it.node.label == nodeKey || it.node.idName == nodeKey }
        if (simNode?.node?.label != null) lastLongPressedLabel = simNode.node.label
        val next = simNode?.onLongClick ?: simNode?.onClick
        if (next != null) {
            navigate(next)
            return EngineResult.ok("long press opened $next")
        }
        return EngineResult.ok("long pressed ${simNode?.node?.label ?: "node"}")
    }

    override suspend fun setText(target: MatchedElement?, text: String, clearFirst: Boolean): EngineResult {
        textSetCount++
        val key = target?.node?.idName ?: target?.node?.label ?: "focused"
        val previous = typedText[key] ?: ""
        typedText[key] = if (clearFirst) text else previous + text
        trace += "setText($key=${typedText[key]?.length} chars)"
        log?.d(TAG, "setText into $key")
        return EngineResult.ok("typed ${text.length} char(s) into $key")
    }

    override suspend fun pressImeAction(): EngineResult {
        trace += "imeAction()"
        val screen = current
        val submit = screen.nodes.firstOrNull { it.node.isClickable && SUBMIT_LABELS.any { l -> TextMatch.equalsLoose(it.node.label, l) } }
        submit?.onClick?.let { navigate(it) }
        return EngineResult.ok("pressed the keyboard action key")
    }

    override suspend fun paste(text: String): EngineResult {
        trace += "paste(${text.length})"
        return EngineResult.ok("pasted ${text.length} char(s)")
    }

    override suspend fun swipe(
        direction: Direction,
        origin: MatchedElement?,
        distanceRatio: Float,
        durationMs: Long
    ): EngineResult {
        gestureCount++
        scrollOffset = (scrollOffset + if (direction == Direction.DOWN) 3 else -3).coerceAtLeast(0)
        trace += "swipe(${direction.name.lowercase()})"
        return EngineResult.ok("swiped ${direction.name.lowercase()}")
    }

    override suspend fun scroll(direction: Direction, container: MatchedElement?, times: Int): EngineResult {
        scrollCount += times
        val delta = if (direction == Direction.DOWN) times * 3 else -times * 3
        scrollOffset = (scrollOffset + delta).coerceAtLeast(0)
        trace += "scroll(${direction.name.lowercase()} x$times) offset=$scrollOffset"
        val screen = current
        // Scrolling reveals the nodes that were below the fold.
        val revealed = screen.paged && scrollOffset > 0
        if (revealed) log?.d(TAG, "scroll revealed more of $currentKey")
        return EngineResult.ok("scrolled ${direction.name.lowercase()} (offset $scrollOffset)")
    }

    override suspend fun globalAction(action: GlobalAction): EngineResult {
        trace += "global(${action.name})"
        when (action) {
            GlobalAction.BACK -> {
                val previous = history.removeLastOrNull()
                if (previous != null) {
                    currentKey = previous
                    scrollOffset = 0
                } else {
                    currentKey = "home"
                }
            }
            GlobalAction.HOME -> {
                history.addLast(currentKey)
                currentKey = "home"
                scrollOffset = 0
            }
            GlobalAction.RECENTS -> { /* nothing to show in a sandbox */ }
            GlobalAction.NOTIFICATIONS -> { /* nothing to show in a sandbox */ }
            GlobalAction.QUICK_SETTINGS -> { /* nothing to show in a sandbox */ }
        }
        return EngineResult.ok("${action.name.lowercase()} performed")
    }

    override suspend fun launchApp(packageName: String): EngineResult {
        trace += "launch($packageName)"
        val target = screens.entries.firstOrNull { it.value.packageName == packageName }
        if (target == null) {
            return EngineResult.fail("no simulated app for $packageName", FailureReason.APP_NOT_FOUND)
        }
        navigate(target.key)
        return EngineResult.ok("launched $packageName", detail = packageName)
    }

    override suspend fun captureScreen(region: Bounds?): EngineResult {
        trace += "screenshot()"
        return EngineResult.fail(
            "the sandbox does not produce bitmaps", FailureReason.NOT_IMPLEMENTED
        )
    }

    override fun displaySize(): Point = Point(width, height)

    /** The typed text becomes the label of [targetId] everywhere it appears. */
    private fun applyRename(targetId: String) {
        val field = current.nodes.firstOrNull { it.node.isEditable } ?: return
        val typed = typedText[field.node.idName ?: field.node.label ?: ""]?.trim()
        if (typed.isNullOrEmpty()) return
        renameOverrides[targetId] = typed
        log?.d(TAG, "renamed $targetId to $typed")
    }

    /**
     * The long-pressed item disappears from its old screen and appears on
     * [destinationKey] - the screen the "Move here" button navigates to. Adding it
     * to the current screen instead would make the move unverifiable, which is
     * exactly the kind of lie a simulation must not tell.
     */
    private fun applyMove(destinationKey: String) {
        val label = lastLongPressedLabel ?: return
        removedLabels += label
        val row = extraNodes[destinationKey]?.size ?: 0
        extraNodes.getOrPut(destinationKey) { mutableListOf() } += SimNode(
            node(20 + row, label, "moved_${row}")
        )
        log?.d(TAG, "moved $label into $destinationKey")
    }

    private fun navigate(key: String) {
        if (key == currentKey) return
        history.addLast(currentKey)
        while (history.size > MAX_HISTORY) history.removeFirst()
        currentKey = key
        scrollOffset = 0
    }

    // -------------------------------------------------------- ScreenSource

    private val _snapshots = MutableSharedFlow<ScreenSnapshot>(replay = 1, extraBufferCapacity = 4)
    override val snapshots: Flow<ScreenSnapshot> = _snapshots

    override fun lastSignature(): String? = latest?.signature
    override fun lastSnapshot(): ScreenSnapshot? = latest

    @Volatile
    private var latest: ScreenSnapshot? = null

    override suspend fun observe(reason: String): ScreenSnapshot {
        observationCount++
        val screen = current
        val visible = visibleNodes(screen)
        val snapshot = ScreenSnapshot(
            packageName = screen.packageName,
            windowTitle = screen.title,
            nodes = visible.mapIndexed { index, sim ->
                val renamed = sim.node.idName?.substringAfterLast('/')?.let { renameOverrides[it] }
                val typed = if (sim.node.isEditable) {
                    typedText[sim.node.idName ?: sim.node.label ?: ""] ?: sim.node.text
                } else sim.node.text
                sim.node.copy(key = index, text = renamed ?: typed)
            },
            source = com.deuty.agent.core.model.ScreenSource.SIMULATED,
            capturedAtMs = clock.nowMs(),
            displayWidth = width,
            displayHeight = height,
            isSecure = screen.secure,
            ocrLines = emptyList<OcrLine>()
        ).withSignature()
        latest = snapshot
        _snapshots.tryEmit(snapshot)
        log?.d(TAG, "observed $currentKey (${snapshot.nodes.size} nodes, sig ${snapshot.signature}) for $reason")
        return snapshot
    }

    /** Scrolling a paged screen slides the visible window over its rows. */
    private fun visibleNodes(screen: SimScreen): List<SimNode> {
        var nodes = screen.nodes
        if (screen.paged && nodes.size > PAGE_SIZE) {
            val from = scrollOffset.coerceIn(0, nodes.size - PAGE_SIZE)
            nodes = nodes.subList(from, from + PAGE_SIZE)
        }
        // A moved item disappears from its original screen; nodes added at runtime
        // by the move must survive that filter.
        val base = nodes.filterNot { it.node.label != null && it.node.label in removedLabels }
        return base + (extraNodes[screen.key] ?: emptyList())
    }

    override suspend fun awaitChange(fromSignature: String?, timeoutMs: Long): ScreenSnapshot? {
        val baseline = fromSignature ?: latest?.signature
        val snapshot = observe("awaitChange")
        if (baseline == null) return snapshot
        // The simulated world changes synchronously, so a single re-read is the
        // honest equivalent of "waited for the next accessibility event".
        val deadline = clock.elapsedRealtimeMs() + timeoutMs.coerceAtMost(1_000L)
        var currentSnapshot = snapshot
        while (currentSnapshot.signature == baseline && clock.elapsedRealtimeMs() < deadline) {
            clock.sleep(POLL_MS)
            currentSnapshot = observe("awaitChange-poll")
        }
        return if (currentSnapshot.signature != baseline) currentSnapshot else null
    }

    // -------------------------------------------------------- AppDirectory

    override suspend fun resolve(label: String): String? {
        val wanted = TextMatch.normalize(label)
        return screens.values.firstOrNull { TextMatch.normalize(it.appLabel) == wanted }?.packageName
            ?: screens.values.firstOrNull { TextMatch.normalize(it.appLabel).contains(wanted) }?.packageName
            ?: APP_ALIASES[wanted]?.let { pkg -> screens.values.firstOrNull { it.packageName == pkg }?.packageName }
    }

    override suspend fun launchableApps(): Map<String, String> =
        screens.values.associate { TextMatch.normalize(it.appLabel) to it.packageName }

    override suspend fun labelOf(packageName: String?): String? =
        screens.values.firstOrNull { it.packageName == packageName }?.appLabel

    override fun refresh() = Unit

    // ------------------------------------------------------------- helpers

    fun screenKey(): String = currentKey

    fun reset(startKey: String = "home") {
        currentKey = startKey
        history.clear()
        typedText.clear()
        scrollOffset = 0
        renameOverrides.clear()
        extraNodes.clear()
        removedLabels.clear()
        lastLongPressedLabel = null
        trace.clear()
        clickCount = 0; gestureCount = 0; textSetCount = 0; scrollCount = 0; observationCount = 0
    }

    companion object {
        private const val TAG = "sandbox"
        private const val PAGE_SIZE = 12
        private const val MAX_HISTORY = 12
        private const val POLL_MS = 25L
        val SUBMIT_LABELS = listOf("Send", "Search", "Go", "Done", "Next")

        val APP_ALIASES = mapOf(
            "settings" to "com.android.settings",
            "files" to "com.deuty.files",
            "chrome" to "com.android.chrome",
            "bank" to "com.example.bank"
        )

        /**
         * Node factory, public so tests (and the sandbox demo) can build custom
         * worlds without duplicating the bounds/flag boilerplate.
         */
        fun node(
            key: Int,
            label: String?,
            id: String? = null,
            clickable: Boolean = true,
            editable: Boolean = false,
            scrollable: Boolean = false,
            checked: Boolean = false,
            row: Int = key,
            className: String = "android.widget.TextView",
            password: Boolean = false,
            focused: Boolean = false
        ) = UiNode(
            key = key,
            parentKey = null,
            depth = 2,
            text = label,
            contentDescription = null,
            viewIdResourceName = id?.let { "com.sim:id/$it" },
            className = className,
            packageName = "com.sim",
            bounds = Bounds(48, 220 + row * 130, 1032, 220 + row * 130 + 110),
            isClickable = clickable,
            isLongClickable = clickable,
            isScrollable = scrollable,
            isEditable = editable,
            isChecked = checked,
            isSelected = false,
            isEnabled = true,
            isFocused = focused,
            isPassword = password,
            childCount = 0
        )

        /** A small but realistic world: launcher, Settings, a file manager, a bank. */
        fun defaultWorld(): Map<String, SimScreen> {
            val home = SimScreen(
                key = "home",
                packageName = "com.sim.launcher",
                appLabel = "Launcher",
                title = "Home",
                nodes = listOf(
                    SimNode(node(0, "Settings", "settings"), onClick = "settings"),
                    SimNode(node(1, "Files", "files"), onClick = "files"),
                    SimNode(node(2, "Chrome", "chrome"), onClick = "browser"),
                    SimNode(node(3, "Bank", "bank"), onClick = "bank"),
                    SimNode(node(4, "Search", "search"))
                )
            )
            val settings = SimScreen(
                key = "settings",
                packageName = "com.android.settings",
                appLabel = "Settings",
                title = "Settings",
                nodes = listOf(
                    SimNode(node(0, "Search settings", "search", editable = true, clickable = true, className = "android.widget.EditText")),
                    SimNode(node(1, "Wi-Fi", "wifi"), onClick = "wifi"),
                    SimNode(node(2, "Bluetooth", "bluetooth"), onClick = "bluetooth"),
                    SimNode(node(3, "Display", "display"), onClick = "display"),
                    SimNode(node(4, "Apps", "apps"), onClick = "apps")
                ),
                paged = true
            )
            val wifi = SimScreen(
                key = "wifi",
                packageName = "com.android.settings",
                appLabel = "Settings",
                title = "Wi-Fi",
                nodes = listOf(
                    SimNode(node(0, "Wi-Fi", "wifi_toggle", checked = true)),
                    SimNode(node(1, "CollegeWiFi", "net1"), onClick = "wifi_dialog"),
                    SimNode(node(2, "HomeNet", "net2"), onClick = "wifi_dialog"),
                    SimNode(node(3, "Connect", "connect"))
                )
            )
            val wifiDialog = SimScreen(
                key = "wifi_dialog",
                packageName = "com.android.settings",
                appLabel = "Settings",
                title = "Connect to network",
                nodes = listOf(
                    SimNode(node(0, "Password", "wifi_password", editable = true, className = "android.widget.EditText", password = true, focused = true)),
                    SimNode(node(1, "Cancel", "cancel"), onClick = "wifi"),
                    SimNode(node(2, "Connect", "connect_ok"), onClick = "wifi_connected")
                )
            )
            val wifiConnected = SimScreen(
                key = "wifi_connected",
                packageName = "com.android.settings",
                appLabel = "Settings",
                title = "Wi-Fi",
                nodes = listOf(
                    SimNode(node(0, "Connected", "status")),
                    SimNode(node(1, "CollegeWiFi", "net1")),
                    SimNode(node(2, "Disconnect", "disconnect"), onClick = "wifi")
                )
            )
            val files = SimScreen(
                key = "files",
                packageName = "com.deuty.files",
                appLabel = "Files",
                title = "Files",
                nodes = listOf(
                    SimNode(node(0, "Downloads", "downloads"), onClick = "downloads", onLongClick = "downloads"),
                    SimNode(node(1, "College", "college"), onClick = "college"),
                    SimNode(node(2, "Search files", "search", editable = true, className = "android.widget.EditText")),
                    SimNode(node(3, "timetable.pdf", "file1"), onClick = null, onLongClick = "file_menu")
                )
            )
            val downloads = SimScreen(
                key = "downloads",
                packageName = "com.deuty.files",
                appLabel = "Files",
                title = "Downloads",
                nodes = listOf(
                    SimNode(node(0, "timetable.pdf", "file1"), onLongClick = "file_menu"),
                    SimNode(node(1, "notes.pdf", "file2"), onLongClick = "file_menu"),
                    SimNode(node(2, "invoice.pdf", "file3"), onLongClick = "file_menu")
                )
            )
            val fileMenu = SimScreen(
                key = "file_menu",
                packageName = "com.deuty.files",
                appLabel = "Files",
                title = "timetable.pdf",
                nodes = listOf(
                    SimNode(node(0, "Open", "open"), onClick = "files"),
                    SimNode(node(1, "Rename", "rename"), onClick = "rename_dialog"),
                    SimNode(node(2, "Move to", "move"), onClick = "move_dialog"),
                    SimNode(node(3, "Delete", "delete"), onClick = "delete_confirm"),
                    SimNode(node(4, "Share", "share"))
                )
            )
            val renameDialog = SimScreen(
                key = "rename_dialog",
                packageName = "com.deuty.files",
                appLabel = "Files",
                title = "Rename",
                nodes = listOf(
                    SimNode(node(0, "timetable.pdf", "name_field", editable = true, className = "android.widget.EditText", focused = true)),
                    SimNode(node(1, "Cancel", "cancel"), onClick = "files"),
                    SimNode(node(2, "OK", "ok"), onClick = "files", renameTarget = "file1")
                )
            )
            val moveDialog = SimScreen(
                key = "move_dialog",
                packageName = "com.deuty.files",
                appLabel = "Files",
                title = "Move to",
                nodes = listOf(
                    SimNode(node(0, "College", "college"), onClick = "college"),
                    SimNode(node(1, "Downloads", "downloads"), onClick = "downloads"),
                    SimNode(node(2, "Move here", "move_here"), onClick = "moved", moveHere = true)
                )
            )
            val college = SimScreen(
                key = "college",
                packageName = "com.deuty.files",
                appLabel = "Files",
                title = "College",
                nodes = listOf(
                    SimNode(node(0, "Assignments", "assignments"), onClick = "assignments"),
                    SimNode(node(1, "Timetable", "timetable"), onClick = "timetable_folder"),
                    SimNode(node(2, "Move here", "move_here"), onClick = "moved", moveHere = true)
                )
            )
            val assignments = SimScreen(
                key = "assignments",
                packageName = "com.deuty.files",
                appLabel = "Files",
                title = "Assignments",
                nodes = listOf(
                    SimNode(node(0, "DS Assignment.pdf", "ds"), onLongClick = "file_menu"),
                    SimNode(node(1, "Move here", "move_here"), onClick = "moved", moveHere = true)
                )
            )
            val timetableFolder = SimScreen(
                key = "timetable_folder",
                packageName = "com.deuty.files",
                appLabel = "Files",
                title = "Timetable",
                nodes = listOf(
                    SimNode(node(0, "Move here", "move_here"), onClick = "moved", moveHere = true),
                    SimNode(node(1, "timetable.pdf", "file1"), onLongClick = "file_menu")
                )
            )
            val moved = SimScreen(
                key = "moved",
                packageName = "com.deuty.files",
                appLabel = "Files",
                title = "Files",
                nodes = listOf(
                    SimNode(node(0, "Moved 1 item", "toast")),
                    SimNode(node(1, "Downloads", "downloads"), onClick = "downloads"),
                    SimNode(node(2, "College", "college"), onClick = "college")
                )
            )
            val deleteConfirm = SimScreen(
                key = "delete_confirm",
                packageName = "com.deuty.files",
                appLabel = "Files",
                title = "Delete file?",
                nodes = listOf(
                    SimNode(node(0, "Delete 1 file?", "question")),
                    SimNode(node(1, "Cancel", "cancel"), onClick = "files"),
                    SimNode(node(2, "Delete", "delete_ok"), onClick = "files")
                )
            )
            val browser = SimScreen(
                key = "browser",
                packageName = "com.android.chrome",
                appLabel = "Chrome",
                title = "New tab",
                nodes = listOf(
                    SimNode(node(0, "Search or type URL", "url", editable = true, className = "android.widget.EditText", focused = true)),
                    SimNode(node(1, "College portal", "link1"), onClick = "college_site")
                )
            )
            val collegeSite = SimScreen(
                key = "college_site",
                packageName = "com.android.chrome",
                appLabel = "Chrome",
                title = "College portal",
                nodes = listOf(
                    SimNode(node(0, "Timetable", "tt_link"), onClick = "timetable_page"),
                    SimNode(node(1, "Results", "results")),
                    SimNode(node(2, "Sign in", "signin"))
                )
            )
            val timetablePage = SimScreen(
                key = "timetable_page",
                packageName = "com.android.chrome",
                appLabel = "Chrome",
                title = "Timetable",
                nodes = listOf(
                    SimNode(node(0, "Download PDF", "download"), onClick = "download_started"),
                    SimNode(node(1, "Semester 3", "sem"))
                )
            )
            val downloadStarted = SimScreen(
                key = "download_started",
                packageName = "com.android.chrome",
                appLabel = "Chrome",
                title = "Timetable",
                nodes = listOf(
                    SimNode(node(0, "Download complete", "status")),
                    SimNode(node(1, "timetable.pdf", "file"))
                )
            )
            // A secure screen: the agent must refuse here, which is what makes it
            // worth having in the sandbox at all.
            val bank = SimScreen(
                key = "bank",
                packageName = "com.example.bank",
                appLabel = "Bank",
                title = "Transfer money",
                secure = true,
                nodes = listOf(
                    SimNode(node(0, "Amount", "amount", editable = true, className = "android.widget.EditText")),
                    SimNode(node(1, "Confirm payment", "pay")),
                    SimNode(node(2, "Enter OTP", "otp", password = true, editable = true, className = "android.widget.EditText"))
                )
            )
            val bluetooth = SimScreen(
                key = "bluetooth", packageName = "com.android.settings", appLabel = "Settings", title = "Bluetooth",
                nodes = listOf(
                    SimNode(node(0, "Bluetooth", "bt_toggle", checked = false)),
                    SimNode(node(1, "Pair new device", "pair"))
                )
            )
            val display = SimScreen(
                key = "display", packageName = "com.android.settings", appLabel = "Settings", title = "Display",
                nodes = listOf(
                    SimNode(node(0, "Dark theme", "dark"), swallowClick = false),
                    SimNode(node(1, "Brightness", "brightness", scrollable = true))
                )
            )
            val apps = SimScreen(
                key = "apps", packageName = "com.android.settings", appLabel = "Settings", title = "Apps",
                nodes = listOf(SimNode(node(0, "See all apps", "all_apps")))
            )

            return listOf(
                home, settings, wifi, wifiDialog, wifiConnected, files, downloads, fileMenu,
                renameDialog, moveDialog, college, assignments, timetableFolder, moved,
                deleteConfirm, browser, collegeSite, timetablePage, downloadStarted, bank,
                bluetooth, display, apps
            ).associateBy { it.key }
        }
    }
}

/** One node plus what the simulated world does when it is used. */
data class SimNode(
    val node: UiNode,
    val onClick: String? = null,
    val onLongClick: String? = null,
    /** True to model a dead element: the framework reports success, nothing moves. */
    val swallowClick: Boolean = false,
    /** Set on a confirm button: the typed text becomes this node id's label. */
    val renameTarget: String? = null,
    /** Set on a "Move here" button: the long-pressed item lands on this screen. */
    val moveHere: Boolean = false
)

data class SimScreen(
    val key: String,
    val packageName: String,
    val appLabel: String,
    val title: String?,
    val nodes: List<SimNode>,
    val secure: Boolean = false,
    /** True when the screen has more rows than fit; scrolling reveals them. */
    val paged: Boolean = false
)
