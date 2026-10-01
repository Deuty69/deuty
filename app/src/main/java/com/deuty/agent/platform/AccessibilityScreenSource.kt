package com.deuty.agent.platform

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.deuty.agent.core.model.Bounds
import com.deuty.agent.core.model.ScreenSnapshot
import com.deuty.agent.core.model.ScreenSource
import com.deuty.agent.core.model.UiNode
import com.deuty.agent.core.util.AgentLog
import com.deuty.agent.core.util.Clock
import com.deuty.agent.core.util.withSignature
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Level-1 screen understanding: the accessibility tree, event-driven.
 *
 * The battery-critical decisions live here:
 *
 *  1. When nothing is active (no task, no live inspector, not recording) an
 *     event updates ONE string - the foreground package - and returns. No tree
 *     walk, no allocation, no coroutine. This is the difference between an
 *     accessibility service that costs ~0% battery and one that costs 5%.
 *  2. When something IS active, events are debounced ([DEBOUNCE_MS]). A scrolling
 *     list fires dozens of TYPE_WINDOW_CONTENT_CHANGED per second; we walk the
 *     tree once, after they settle.
 *  3. Captures are bounded ([MAX_NODES], [MAX_DEPTH]) and invisible/off-screen
 *     subtrees are pruned, because a RecyclerView with 5000 items must not turn
 *     into 5000 UiNode objects.
 *  4. Our own package is ignored, so the agent's overlay and UI can never trigger
 *     a feedback loop.
 */
class AccessibilityScreenSource(
    private val serviceRef: () -> AccessibilityService?,
    private val nodeTable: NodeTable,
    private val clock: Clock,
    private val log: AgentLog,
    private val scope: CoroutineScope,
    private val mainDispatcher: CoroutineDispatcher,
    private val ownPackage: String
) : com.deuty.agent.agent.port.ScreenSource {

    override val name = "accessibility-tree"
    override val available: Boolean get() = serviceRef() != null

    private val _snapshots = MutableSharedFlow<ScreenSnapshot>(
        replay = 1,
        extraBufferCapacity = 4,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST
    )
    override val snapshots: Flow<ScreenSnapshot> = _snapshots.asSharedFlow()

    @Volatile
    private var lastSnapshot: ScreenSnapshot? = null

    @Volatile
    var foregroundPackage: String? = null
        private set

    /**
     * When false, events cost nothing. Set by the engine: true while a task runs,
     * while recording, or while the inspector is live.
     */
    @Volatile
    var active: Boolean = false

    @Volatile
    var logEvents: Boolean = false

    private var debounceJob: Job? = null

    // ------------------------------------------------------------- events

    /** Called from AccessibilityService.onAccessibilityEvent on the main thread. */
    fun onEvent(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString()
        if (pkg != null) {
            if (pkg == ownPackage) {
                // Our own overlay/activity. Ignoring this is what stops the agent
                // from reacting to itself and looping.
                if (logEvents) log.d(TAG, "ignored own event ${eventTypeName(event.eventType)}")
                return
            }
            if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                foregroundPackage = pkg
            }
        }
        if (!active) {
            if (logEvents) log.d(TAG, "inactive: ${eventTypeName(event.eventType)} from $pkg")
            return
        }
        if (logEvents) log.d(TAG, "${eventTypeName(event.eventType)} from $pkg")
        scheduleCapture()
    }

    private fun scheduleCapture() {
        debounceJob?.cancel()
        debounceJob = scope.launch(mainDispatcher) {
            clock.sleep(DEBOUNCE_MS)
            val snapshot = captureTree("event")
            publish(snapshot)
        }
    }

    private fun publish(snapshot: ScreenSnapshot) {
        lastSnapshot = snapshot
        _snapshots.tryEmit(snapshot)
    }

    // ---------------------------------------------------------- ScreenSource

    override suspend fun observe(reason: String): ScreenSnapshot = withContext(mainDispatcher) {
        val snapshot = captureTree(reason)
        publish(snapshot)
        snapshot
    }

    override fun lastSignature(): String? = lastSnapshot?.signature

    override fun lastSnapshot(): ScreenSnapshot? = lastSnapshot

    /**
     * Event-driven wait: suspends until the signature differs, or until the
     * timeout. On timeout we do one final read, because some UIs (WebView,
     * games, Compose with heavy custom drawing) change without firing an event
     * we listen for - and a verification that reports "nothing changed" when
     * something did is worse than one extra tree walk.
     */
    override suspend fun awaitChange(fromSignature: String?, timeoutMs: Long): ScreenSnapshot? {
        val baseline = fromSignature ?: lastSnapshot?.signature
        if (baseline == null) return observe("awaitChange(no baseline)")

        val changed = withTimeoutOrNull(timeoutMs) {
            snapshots.first { it.signature != baseline }
        }
        if (changed != null) return changed

        val finalRead = observe("awaitChange(timeout)")
        return if (finalRead.signature != baseline) finalRead else null
    }

    // --------------------------------------------------------------- capture

    /**
     * Walk the tree into a flat, framework-free snapshot.
     * Must be called on the main thread.
     */
    private fun captureTree(reason: String): ScreenSnapshot {
        val started = clock.elapsedRealtimeMs()
        val service = serviceRef()
        if (service == null) {
            return ScreenSnapshot.unavailable(clock.nowMs(), "accessibility service not connected")
        }

        val root = service.rootInActiveWindow ?: activeWindowRoot(service)
        if (root == null) {
            val pkg = foregroundPackage
            return ScreenSnapshot(
                packageName = pkg,
                windowTitle = null,
                nodes = emptyList(),
                source = ScreenSource.ACCESSIBILITY_TREE,
                capturedAtMs = clock.nowMs(),
                displayWidth = displayWidth(service),
                displayHeight = displayHeight(service),
                isSecure = isSensitivePackage(pkg),
                signature = "",
                captureDurationMs = clock.elapsedRealtimeMs() - started
            ).withSignature()
        }

        nodeTable.beginGeneration()
        val nodes = ArrayList<UiNode>(128)
        val rect = Rect()
        var truncated = false

        // Iterative DFS: recursion over a deep view tree can overflow the stack
        // on some OEM layouts, and an explicit stack lets us stop at MAX_NODES.
        val stack = ArrayList<Triple<AccessibilityNodeInfo, Int, Int>>()
        var nextKey = 0
        val rootKey = nextKey++
        stack.add(Triple(root, -1, 0))
        nodeTable.put(rootKey, root)

        while (stack.isNotEmpty()) {
            if (nodes.size >= MAX_NODES) {
                truncated = true
                // Anything left on the stack is ours and must not leak.
                stack.forEach { recycleQuietly(it.first) }
                stack.clear()
                break
            }
            val (node, parentKey, depth) = stack.removeAt(stack.size - 1)
            val key = if (parentKey == -1) rootKey else nextKey++
            if (parentKey != -1) nodeTable.put(key, node)

            node.getBoundsInScreen(rect)
            val bounds = Bounds(rect.left, rect.top, rect.right, rect.bottom)
            val isPassword = runCatching { node.isPassword }.getOrDefault(false)
            val rawText = if (isPassword) null else node.text?.toString()
            val desc = if (isPassword) null else node.contentDescription?.toString()

            // Prune: a node with no bounds, no text, no id and no children is
            // noise. This typically removes 30-60% of the tree in Compose apps.
            val childCount = node.childCount
            val hasContent = !rawText.isNullOrBlank() || !desc.isNullOrBlank() ||
                node.viewIdResourceName != null || node.isClickable || node.isScrollable ||
                node.isEditable || node.isLongClickable || node.isChecked

            if (hasContent || childCount > 0 || depth <= 2) {
                nodes += UiNode(
                    key = key,
                    parentKey = if (parentKey == -1) null else parentKey,
                    depth = depth,
                    text = rawText?.take(MAX_TEXT),
                    contentDescription = desc?.take(MAX_TEXT),
                    viewIdResourceName = node.viewIdResourceName,
                    className = node.className?.toString()?.take(120),
                    packageName = node.packageName?.toString(),
                    bounds = bounds,
                    isClickable = node.isClickable,
                    isLongClickable = node.isLongClickable,
                    isScrollable = node.isScrollable,
                    isEditable = node.isEditable,
                    isChecked = node.isChecked,
                    isSelected = node.isSelected,
                    isEnabled = node.isEnabled,
                    isFocused = node.isFocused,
                    isPassword = isPassword,
                    childCount = childCount
                )
            }

            if (depth < MAX_DEPTH) {
                for (i in 0 until childCount) {
                    val child = runCatching { node.getChild(i) }.getOrNull()
                    if (child == null) continue
                    if (nodes.size + stack.size >= MAX_NODES) {
                        truncated = true
                        recycleQuietly(child)
                        continue
                    }
                    // Skip off-screen subtrees entirely; they cannot be tapped.
                    child.getBoundsInScreen(rect)
                    if (rect.isEmpty && child.childCount == 0) {
                        recycleQuietly(child)
                        continue
                    }
                    stack.add(Triple(child, key, depth + 1))
                }
            } else {
                truncated = true
            }
        }

        val pkg = root.packageName?.toString() ?: foregroundPackage
        val snapshot = ScreenSnapshot(
            packageName = pkg,
            windowTitle = windowTitle(service),
            nodes = nodes,
            source = ScreenSource.ACCESSIBILITY_TREE,
            capturedAtMs = clock.nowMs(),
            displayWidth = displayWidth(service),
            displayHeight = displayHeight(service),
            isSecure = isSensitivePackage(pkg),
            truncated = truncated,
            captureDurationMs = clock.elapsedRealtimeMs() - started
        ).withSignature()

        log.d(TAG, "captured ${nodes.size} node(s) in ${snapshot.captureDurationMs}ms " +
            "pkg=$pkg sig=${snapshot.signature} reason=$reason${if (truncated) " TRUNCATED" else ""}")
        return snapshot
    }

    private fun activeWindowRoot(service: AccessibilityService): AccessibilityNodeInfo? = runCatching {
        service.windows
            .firstOrNull { it.isFocused }
            ?.root
            ?: service.windows.firstOrNull { it.isActive }?.root
    }.getOrNull()

    private fun windowTitle(service: AccessibilityService): String? = runCatching {
        val windows = service.windows
        val target = windows.firstOrNull { it.isFocused } ?: windows.firstOrNull { it.isActive }
        target?.title?.toString()?.take(120)
    }.getOrNull()

    private fun displayWidth(service: AccessibilityService): Int =
        runCatching { service.resources.displayMetrics.widthPixels }.getOrDefault(1080)

    private fun displayHeight(service: AccessibilityService): Int =
        runCatching { service.resources.displayMetrics.heightPixels }.getOrDefault(1920)

    /** Keyboard visible? Used to verify that typing raised the IME. */
    fun isKeyboardVisible(): Boolean = runCatching {
        serviceRef()?.windows?.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD } == true
    }.getOrDefault(false)

    private fun recycleQuietly(node: AccessibilityNodeInfo) {
        @Suppress("DEPRECATION")
        runCatching { node.recycle() }
    }

    companion object {
        private const val TAG = "screen"
        private const val MAX_NODES = 700
        private const val MAX_DEPTH = 45
        private const val MAX_TEXT = 300
        private const val DEBOUNCE_MS = 120L

        /**
         * FLAG_SECURE cannot be queried from an accessibility service -
         * AccessibilityWindowInfo does not expose window flags. This list is the
         * conservative proxy: if a package looks like it handles money or
         * credentials, we treat the screen as sensitive and refuse to act.
         * A false positive costs the user one manual tap; a false negative could
         * let the agent touch a payment sheet.
         */
        private val SENSITIVE_PACKAGE_HINTS = listOf(
            "bank", "pay", "wallet", "money", "invest", "broker", "trading",
            "crypto", "finance", "loan", "insurance", "upi", "authenticator",
            "permissioncontroller", "packageinstaller"
        )

        fun isSensitivePackage(pkg: String?): Boolean {
            if (pkg.isNullOrBlank()) return false
            val lower = pkg.lowercase()
            return SENSITIVE_PACKAGE_HINTS.any { lower.contains(it) }
        }

        fun eventTypeName(type: Int): String = when (type) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> "WINDOW_STATE"
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> "CONTENT"
            AccessibilityEvent.TYPE_VIEW_CLICKED -> "CLICKED"
            AccessibilityEvent.TYPE_VIEW_LONG_CLICKED -> "LONG_CLICKED"
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> "SCROLLED"
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> "TEXT_CHANGED"
            AccessibilityEvent.TYPE_VIEW_FOCUSED -> "FOCUSED"
            AccessibilityEvent.TYPE_VIEW_SELECTED -> "SELECTED"
            else -> "type=$type"
        }
    }
}
