package com.deuty.agent.platform

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.graphics.Path
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.annotation.RequiresApi
import com.deuty.agent.agent.port.EngineCapabilities
import com.deuty.agent.agent.port.EngineResult
import com.deuty.agent.agent.port.GlobalAction
import com.deuty.agent.agent.port.InteractionEngine
import com.deuty.agent.core.model.Bounds
import com.deuty.agent.core.model.Direction
import com.deuty.agent.core.model.FailureReason
import com.deuty.agent.core.model.MatchedElement
import com.deuty.agent.core.model.Point
import com.deuty.agent.core.model.ScreenCapture
import com.deuty.agent.core.util.AgentLog
import com.deuty.agent.core.util.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * The agent's hands (requirement 2: the AI decides *what*, this decides *how*).
 *
 * Two strategies for every physical action, in this order:
 *   1. The node's own accessibility action (ACTION_CLICK, ACTION_SET_TEXT,
 *      ACTION_SCROLL_*): cheaper, works on elements that are scrolled off-screen,
 *      and it is what the app author intended.
 *   2. An injected gesture (dispatchGesture): needed when an app exposes a node
 *      that is not itself clickable (a TextView inside a clickable row), for
 *      WebView/Compose/custom-drawn UI, and for long-press-with-duration.
 *
 * Threading: every operation runs on the main thread (accessibility APIs are not
 * thread-safe), and gesture dispatch *suspends* rather than blocking. Blocking
 * the main thread waiting for a gesture callback that is itself posted to the
 * main thread is a guaranteed deadlock - the classic accessibility-service ANR.
 *
 * Cancellation: [onMain] rethrows CancellationException so the user's STOP button
 * actually stops the task instead of being converted into "gesture failed".
 *
 * Node ownership: nodes obtained here (rootInActiveWindow, getChild, parent) are
 * ours and are recycled before returning; nodes from [NodeTable] belong to the
 * current capture generation and are recycled by the table. On API 26-32 the
 * framework keeps a 50-node pool, so leaking even a few per action eventually
 * makes rootInActiveWindow return null and the agent goes blind.
 *
 * Hard platform limits honoured here (docs/ANDROID_LIMITATIONS.md):
 *   - No key-event injection exists for accessibility services, so "press Enter"
 *     taps the IME action key and reports unsupported when there is none.
 *   - takeScreenshot() needs API 30+ and is rate limited to ~1/second.
 *   - GLOBAL_ACTION_RECENTS needs API 28+.
 *   - Gestures are rejected on FLAG_SECURE windows; we surface that as
 *     SECURE_SCREEN so the risk layer can stop and ask.
 */
class AccessibilityInteractionEngine(
    private val serviceRef: () -> AccessibilityService?,
    private val nodeTable: NodeTable,
    private val screens: AccessibilityScreenSource,
    private val clock: Clock,
    private val log: AgentLog,
    private val mainDispatcher: CoroutineDispatcher
) : InteractionEngine {

    override val name = "accessibility"

    override val available: Boolean get() = serviceRef() != null

    override val capabilities = EngineCapabilities(
        gestures = true,
        nodeActions = true,
        screenshots = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R,
        globalActions = true,
        clipboard = true,
        launchApps = true,
        // No accessibility API can inject a key event; the IME action key is
        // tapped instead, which only works while the keyboard is visible.
        imeActionInjection = false,
        description = "AccessibilityService gestures + node actions" +
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) ", screenshots (API 30+)" else ""
    )

    // ------------------------------------------------------------------ click

    override suspend fun click(target: MatchedElement): EngineResult = onMain {
        val live = target.node?.let { nodeTable.get(it.key) }

        if (live != null && live.isClickable) {
            if (perform(live, AccessibilityNodeInfo.ACTION_CLICK)) {
                return@onMain EngineResult.ok("clicked ${describe(live)}")
            }
            log.d(TAG, "ACTION_CLICK returned false on ${describe(live)}; falling back to a gesture")
        }

        val ancestor = live?.let { clickableAncestor(it) }
        if (ancestor != null) {
            val ok = perform(ancestor, AccessibilityNodeInfo.ACTION_CLICK)
            recycle(ancestor)
            if (ok) return@onMain EngineResult.ok("clicked the row containing ${describe(live)}")
        }

        val point = target.tapPoint ?: return@onMain EngineResult.fail(
            "no clickable node and no coordinates to tap", FailureReason.ELEMENT_NOT_FOUND
        )
        if (gestureTap(point.x, point.y, TAP_MS)) {
            EngineResult.ok("tapped (${point.x},${point.y})")
        } else {
            gestureFailure(point)
        }
    }

    override suspend fun longClick(target: MatchedElement, durationMs: Long): EngineResult = onMain {
        val live = target.node?.let { nodeTable.get(it.key) }
        if (live != null && live.isLongClickable && perform(live, AccessibilityNodeInfo.ACTION_LONG_CLICK)) {
            return@onMain EngineResult.ok("long-clicked ${describe(live)}")
        }
        val point = target.tapPoint ?: return@onMain EngineResult.fail(
            "nothing to long-press", FailureReason.ELEMENT_NOT_FOUND
        )
        val hold = durationMs.coerceAtLeast(LONG_PRESS_MIN_MS)
        if (gestureTap(point.x, point.y, hold)) {
            EngineResult.ok("long-pressed (${point.x},${point.y}) for ${hold}ms")
        } else {
            gestureFailure(point)
        }
    }

    // ------------------------------------------------------------------- text

    override suspend fun setText(target: MatchedElement?, text: String, clearFirst: Boolean): EngineResult = onMain {
        val service = serviceRef() ?: return@onMain noService()
        var owned: AccessibilityNodeInfo? = null
        try {
            var node = target?.node?.let { nodeTable.get(it.key) }
            if (node == null) {
                owned = focusedEditable(service)
                node = owned
            }
            if (node == null) {
                return@onMain EngineResult.fail(
                    "no editable field on screen and nothing is focused", FailureReason.ELEMENT_NOT_FOUND
                )
            }
            if (!node.isEditable) {
                // Tap to focus, then look for whichever field gained focus.
                perform(node, AccessibilityNodeInfo.ACTION_CLICK)
                clock.sleep(FOCUS_SETTLE_MS)
                recycle(owned)
                owned = focusedEditable(service)
                node = owned ?: node
            }

            perform(node, AccessibilityNodeInfo.ACTION_FOCUS)
            if (clearFirst) {
                // Select-all + replace is what makes retrying a failed step safe:
                // without it, three attempts type the text three times.
                val length = node.text?.length ?: 0
                val selection = Bundle().apply {
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0)
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, length)
                }
                runCatching { node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selection) }
            }

            val existing = if (clearFirst) "" else (node.text?.toString() ?: "")
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, existing + text)
            }
            val ok = runCatching { node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args) }.getOrDefault(false)

            if (!ok) {
                // WebView and some custom editors reject ACTION_SET_TEXT.
                // Clipboard + ACTION_PASTE is the only remaining legitimate path.
                return@onMain if (pasteInto(node, text)) {
                    EngineResult.ok("set text via clipboard paste (${text.length} chars)")
                } else {
                    EngineResult.fail(
                        "the app refused ACTION_SET_TEXT and clipboard paste", FailureReason.TEXT_INPUT_FAILED
                    )
                }
            }

            val keyboard = screens.isKeyboardVisible()
            EngineResult.ok("typed ${text.length} char(s)" + if (keyboard) "" else " (keyboard not visible yet)")
        } finally {
            recycle(owned)
        }
    }

    override suspend fun pressImeAction(): EngineResult = onMain {
        val service = serviceRef() ?: return@onMain noService()
        if (!screens.isKeyboardVisible()) {
            return@onMain EngineResult.fail("keyboard is not visible", FailureReason.KEYBOARD_DID_NOT_APPEAR)
        }
        val imeRoot = runCatching {
            service.windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }?.root
        }.getOrNull()
        if (imeRoot == null) {
            return@onMain EngineResult.unsupported(
                "the keyboard window is not exposed to accessibility services on this device"
            )
        }
        try {
            val labels = listOf("Send", "Search", "Go", "Done", "Next", "Enter", "Return", "\u21B5", "\u23CE")
            val candidate = findClickableByLabel(imeRoot, labels, 0)
            if (candidate == null) {
                return@onMain EngineResult.unsupported(
                    "Android does not let an accessibility service inject key events, and I could not find a " +
                        "keyboard action key to tap. Please press it yourself."
                )
            }
            val ok = perform(candidate, AccessibilityNodeInfo.ACTION_CLICK)
            recycle(candidate)
            return@onMain if (ok) {
                EngineResult.ok("pressed the keyboard action key")
            } else {
                EngineResult.fail("the keyboard action key refused the click", FailureReason.TEXT_INPUT_FAILED)
            }
        } finally {
            recycle(imeRoot)
        }
    }

    override suspend fun paste(text: String): EngineResult = onMain {
        val service = serviceRef() ?: return@onMain noService()
        val node = focusedEditable(service)
        if (node == null) {
            return@onMain EngineResult.fail("nothing focused to paste into", FailureReason.ELEMENT_NOT_FOUND)
        }
        return@onMain try {
            if (pasteInto(node, text)) EngineResult.ok("pasted ${text.length} char(s)")
            else EngineResult.fail("paste was refused", FailureReason.TEXT_INPUT_FAILED)
        } finally {
            recycle(node)
        }
    }

    private fun pasteInto(node: AccessibilityNodeInfo, text: String): Boolean {
        val service = serviceRef() ?: return false
        val clipboard = service.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return false
        return runCatching {
            clipboard.setPrimaryClip(ClipData.newPlainText("deuty-agent", text))
            node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        }.getOrDefault(false)
    }

    // --------------------------------------------------------------- gestures

    override suspend fun swipe(
        direction: Direction,
        origin: MatchedElement?,
        distanceRatio: Float,
        durationMs: Long
    ): EngineResult = onMain {
        val size = displaySize() ?: return@onMain EngineResult.fail(
            "display size unknown", FailureReason.SCREEN_UNREADABLE
        )
        val anchor = origin?.tapPoint ?: Point(size.x / 2, size.y / 2)
        val ratio = distanceRatio.coerceIn(0.05f, 0.9f)
        val end = when (direction) {
            Direction.UP -> Point(anchor.x, (anchor.y - size.y * ratio).toInt())
            Direction.DOWN -> Point(anchor.x, (anchor.y + size.y * ratio).toInt())
            Direction.LEFT -> Point((anchor.x - size.x * ratio).toInt(), anchor.y)
            Direction.RIGHT -> Point((anchor.x + size.x * ratio).toInt(), anchor.y)
        }
        val safeEnd = Point(
            end.x.coerceIn(EDGE_MARGIN, (size.x - EDGE_MARGIN).coerceAtLeast(EDGE_MARGIN)),
            end.y.coerceIn(EDGE_MARGIN, (size.y - EDGE_MARGIN).coerceAtLeast(EDGE_MARGIN))
        )
        if (gestureLine(anchor, safeEnd, durationMs.coerceIn(80L, 3_000L))) {
            EngineResult.ok("swiped ${direction.name.lowercase()} (${anchor.x},${anchor.y})->(${safeEnd.x},${safeEnd.y})")
        } else {
            gestureFailure(anchor)
        }
    }

    /**
     * Direction semantics: [Direction.DOWN] means "reveal the content below", so
     * the finger travels UP. Getting this backwards is the most common bug in
     * gesture automation; it is stated here and covered by a unit test.
     */
    override suspend fun scroll(direction: Direction, container: MatchedElement?, times: Int): EngineResult = onMain {
        val service = serviceRef() ?: return@onMain noService()
        var nodeActions = 0
        var gestures = 0
        val rounds = times.coerceIn(1, 20)

        for (i in 0 until rounds) {
            val fromTable = container?.node?.let { nodeTable.get(it.key) }
            var owned: AccessibilityNodeInfo? = null
            val node = fromTable ?: scrollableNode(service).also { owned = it }
            try {
                var done = false
                if (node != null && node.isScrollable) {
                    val primary = when (direction) {
                        Direction.DOWN -> AccessibilityNodeInfo.ACTION_SCROLL_DOWN
                        Direction.UP -> AccessibilityNodeInfo.ACTION_SCROLL_UP
                        Direction.LEFT -> AccessibilityNodeInfo.ACTION_SCROLL_LEFT
                        Direction.RIGHT -> AccessibilityNodeInfo.ACTION_SCROLL_RIGHT
                    }
                    done = perform(node, primary)
                    if (!done) {
                        val legacy = when (direction) {
                            Direction.DOWN -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                            Direction.UP -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                            else -> 0
                        }
                        if (legacy != 0) done = perform(node, legacy)
                    }
                    if (done) nodeActions++
                }
                if (!done) {
                    // Gesture fallback: Compose lazy lists, WebViews and any
                    // container that does not expose ACTION_SCROLL_*.
                    val area = container?.node?.bounds ?: fullScreenArea()
                    if (swipeInside(area, direction)) gestures++ else break
                }
            } finally {
                recycle(owned)
            }
            if (i < rounds - 1) clock.sleep(SCROLL_SETTLE_MS)
        }

        return@onMain if (nodeActions + gestures > 0) {
            EngineResult.ok(
                "scrolled ${direction.name.lowercase()} x${nodeActions + gestures} " +
                    "($nodeActions node action, $gestures gesture)"
            )
        } else {
            EngineResult.fail("could not scroll ${direction.name.lowercase()}", FailureReason.GESTURE_FAILED)
        }
    }

    private fun fullScreenArea(): Bounds {
        val size = displaySize() ?: return Bounds(0, 0, 1080, 1920)
        return Bounds(0, 0, size.x, size.y)
    }

    private suspend fun swipeInside(area: Bounds, direction: Direction): Boolean {
        val screen = fullScreenArea()
        val usable = if (area.width > 40 && area.height > 40) area else screen
        val cx = usable.centerX
        val cy = usable.centerY
        val vSpan = (usable.height / 2).coerceAtLeast(60)
        val hSpan = (usable.width / 2).coerceAtLeast(60)
        val margin = EDGE_MARGIN
        return when (direction) {
            // Content down == finger up.
            Direction.DOWN -> gestureLine(
                Point(cx, (cy + vSpan / 2).coerceAtMost(screen.bottom - margin)),
                Point(cx, (cy - vSpan / 2).coerceAtLeast(margin)),
                SCROLL_GESTURE_MS
            )
            Direction.UP -> gestureLine(
                Point(cx, (cy - vSpan / 2).coerceAtLeast(margin)),
                Point(cx, (cy + vSpan / 2).coerceAtMost(screen.bottom - margin)),
                SCROLL_GESTURE_MS
            )
            Direction.LEFT -> gestureLine(
                Point((cx - hSpan / 2).coerceAtLeast(margin), cy),
                Point((cx + hSpan / 2).coerceAtMost(screen.right - margin), cy),
                SCROLL_GESTURE_MS
            )
            Direction.RIGHT -> gestureLine(
                Point((cx + hSpan / 2).coerceAtMost(screen.right - margin), cy),
                Point((cx - hSpan / 2).coerceAtLeast(margin), cy),
                SCROLL_GESTURE_MS
            )
        }
    }

    // ------------------------------------------------------------ global acts

    override suspend fun globalAction(action: GlobalAction): EngineResult = onMain {
        val service = serviceRef() ?: return@onMain noService()
        val code = when (action) {
            GlobalAction.BACK -> AccessibilityService.GLOBAL_ACTION_BACK
            GlobalAction.HOME -> AccessibilityService.GLOBAL_ACTION_HOME
            GlobalAction.RECENTS -> {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
                    return@onMain EngineResult.unsupported("recents needs Android 9+")
                }
                AccessibilityService.GLOBAL_ACTION_RECENTS
            }
            GlobalAction.NOTIFICATIONS -> AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
            GlobalAction.QUICK_SETTINGS -> AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS
        }
        val ok = runCatching { service.performGlobalAction(code) }.getOrDefault(false)
        if (ok) {
            EngineResult.ok("${action.name.lowercase()} performed")
        } else {
            EngineResult.fail("the system refused ${action.name.lowercase()}", FailureReason.GESTURE_FAILED)
        }
    }

    override suspend fun launchApp(packageName: String): EngineResult = onMain {
        val service = serviceRef() ?: return@onMain noService()
        val intent = runCatching { service.packageManager.getLaunchIntentForPackage(packageName) }.getOrNull()
        if (intent == null) {
            return@onMain EngineResult.fail(
                "no launch intent for $packageName (not installed, or hidden by package visibility)",
                FailureReason.APP_NOT_FOUND
            )
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        if (runCatching { service.startActivity(intent) }.isSuccess) {
            EngineResult.ok("launched $packageName", detail = packageName)
        } else {
            EngineResult.fail("startActivity was refused for $packageName", FailureReason.APP_NOT_FOUND)
        }
    }

    // ------------------------------------------------------------- screenshot

    override suspend fun captureScreen(region: Bounds?): EngineResult {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return EngineResult.unsupported("screenshot capture needs Android 11 (API 30) or newer")
        }
        val service = serviceRef() ?: return noService()
        val size = displaySize()

        val frame = withContext(mainDispatcher) {
            withTimeoutOrNull(SCREENSHOT_TIMEOUT_MS) {
                suspendCancellableCoroutine<ScreenCapture?> { cont ->
                    val callback = object : AccessibilityService.TakeScreenshotCallback {
                        override fun onSuccess(result: AccessibilityService.TakeScreenshotResult) {
                            val capture = runCatching { toCapture(result, region) }.getOrElse { error ->
                                log.e(TAG, "screenshot conversion failed", error)
                                null
                            }
                            if (cont.isActive) cont.resume(capture)
                        }

                        override fun onFailure(errorCode: Int) {
                            log.w(
                                TAG, "takeScreenshot failed errorCode=$errorCode " +
                                    "(1 usually means the app blocks screenshots / FLAG_SECURE)"
                            )
                            if (cont.isActive) cont.resume(null)
                        }
                    }
                    val dispatched = runCatching {
                        // An inline Executor avoids allocating a thread pool for
                        // something that runs at most a handful of times per task.
                        service.takeScreenshot(Display.DEFAULT_DISPLAY, { command -> command.run() }, callback)
                    }.getOrElse { error ->
                        log.e(TAG, "takeScreenshot threw", error)
                        false
                    }
                    if (!dispatched && cont.isActive) cont.resume(null)
                }
            }
        }

        if (frame == null) {
            return EngineResult.fail(
                "the system refused the screenshot (secure screen, rate limit, or capture disabled)",
                FailureReason.SCREEN_UNREADABLE
            )
        }
        log.i(TAG, "captured ${frame.width}x${frame.height} (scale=${frame.scale})")
        return EngineResult.ok(
            "captured ${frame.width}x${frame.height}",
            detail = size?.let { "display ${it.x}x${it.y}" }
        ).copy(capture = frame)
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun toCapture(result: AccessibilityService.TakeScreenshotResult, region: Bounds?): ScreenCapture? {
        val buffer = result.hardwareBuffer ?: return null
        val hardware = Bitmap.wrapHardwareBuffer(buffer, ColorSpace.get(ColorSpace.Named.SRGB))
        // HardwareBuffer.close() only exists from API 31; calling it on API 30
        // would throw NoSuchMethodError.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching { buffer.close() }
        }
        if (hardware == null) return null
        // Hardware bitmaps cannot be read or cropped: copy once into software.
        val software = hardware.copy(Bitmap.Config.ARGB_8888, false)
        if (!hardware.isRecycled) hardware.recycle()
        if (software == null) return null
        val finalBitmap = if (region != null) crop(software, region, result.scale) else software
        return ScreenCapture(
            width = finalBitmap.width,
            height = finalBitmap.height,
            capturedAtMs = clock.nowMs(),
            region = region,
            scale = result.scale,
            payload = finalBitmap
        )
    }

    private fun crop(source: Bitmap, region: Bounds, scale: Float): Bitmap {
        val factor = if (scale <= 0f) 1f else scale
        val left = (region.left * factor).toInt().coerceIn(0, source.width - 1)
        val top = (region.top * factor).toInt().coerceIn(0, source.height - 1)
        val right = (region.right * factor).toInt().coerceIn(left + 1, source.width)
        val bottom = (region.bottom * factor).toInt().coerceIn(top + 1, source.height)
        val cropped = runCatching {
            Bitmap.createBitmap(source, left, top, right - left, bottom - top)
        }.getOrNull()
        if (cropped != null && cropped !== source && !source.isRecycled) source.recycle()
        return cropped ?: source
    }

    override fun displaySize(): Point? {
        val service = serviceRef() ?: return null
        return runCatching {
            val metrics = service.resources.displayMetrics
            Point(metrics.widthPixels, metrics.heightPixels)
        }.getOrNull()
    }

    // --------------------------------------------------------------- plumbing

    /**
     * Runs [block] on the main thread, converting framework exceptions into a
     * failed [EngineResult] - but never swallowing cancellation, which is how the
     * STOP button works.
     */
    @Suppress("UNCHECKED_CAST")
    private suspend fun <T> onMain(block: suspend () -> T): T = withContext(mainDispatcher) {
        try {
            block()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            log.e(TAG, "engine operation failed", error)
            EngineResult.fail("engine error: ${error.javaClass.simpleName}", FailureReason.UNKNOWN) as T
        }
    }

    private fun noService() = EngineResult.fail(
        "the accessibility service is not connected", FailureReason.SERVICE_UNAVAILABLE
    )

    private fun perform(node: AccessibilityNodeInfo, action: Int): Boolean =
        runCatching { node.performAction(action) }.getOrDefault(false)

    private fun recycle(node: AccessibilityNodeInfo?) {
        if (node == null) return
        @Suppress("DEPRECATION")
        runCatching { node.recycle() }
    }

    /** Returns a node WE own (caller must recycle). */
    private fun clickableAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current = runCatching { node.parent }.getOrNull() ?: return null
        var hops = 0
        while (hops < MAX_ANCESTOR_HOPS) {
            if (current.isClickable) return current
            val next = runCatching { current.parent }.getOrNull()
            recycle(current)
            current = next ?: return null
            hops++
        }
        recycle(current)
        return null
    }

    /** Returns a node WE own (caller must recycle), or null. */
    private fun scrollableNode(service: AccessibilityService): AccessibilityNodeInfo? {
        val root = service.rootInActiveWindow ?: return null
        val found = findFirstScrollable(root, 0)
        if (found !== root) recycle(root)
        return found
    }

    private fun findFirstScrollable(node: AccessibilityNodeInfo, depth: Int): AccessibilityNodeInfo? {
        if (depth > FIND_DEPTH) return null
        if (node.isScrollable) return node
        for (i in 0 until node.childCount) {
            val child = runCatching { node.getChild(i) }.getOrNull() ?: continue
            val found = findFirstScrollable(child, depth + 1)
            if (found != null) return found
            recycle(child)
        }
        return null
    }

    /** Returns a node WE own (caller must recycle), or null. */
    private fun focusedEditable(service: AccessibilityService): AccessibilityNodeInfo? {
        val root = service.rootInActiveWindow ?: return null
        val found = findFocusedEditable(root, 0)
        if (found !== root) recycle(root)
        return found
    }

    private fun findFocusedEditable(node: AccessibilityNodeInfo, depth: Int): AccessibilityNodeInfo? {
        if (depth > FIND_DEPTH) return null
        if (node.isFocused && node.isEditable) return node
        for (i in 0 until node.childCount) {
            val child = runCatching { node.getChild(i) }.getOrNull() ?: continue
            val found = findFocusedEditable(child, depth + 1)
            if (found != null) return found
            recycle(child)
        }
        return null
    }

    private fun findClickableByLabel(
        node: AccessibilityNodeInfo,
        labels: List<String>,
        depth: Int
    ): AccessibilityNodeInfo? {
        if (depth > FIND_DEPTH) return null
        val label = (node.text?.toString() ?: node.contentDescription?.toString())?.trim()
        if (node.isClickable && label != null && labels.any { it.equals(label, ignoreCase = true) }) {
            return node
        }
        for (i in 0 until node.childCount) {
            val child = runCatching { node.getChild(i) }.getOrNull() ?: continue
            val found = findClickableByLabel(child, labels, depth + 1)
            if (found != null) return found
            recycle(child)
        }
        return null
    }

    private fun describe(node: AccessibilityNodeInfo?): String {
        if (node == null) return "?"
        val text = node.text?.toString()?.take(24) ?: node.contentDescription?.toString()?.take(24)
        val cls = node.className?.toString()?.substringAfterLast('.')
        return "${cls ?: "?"}${text?.let { " \"$it\"" } ?: ""}"
    }

    private suspend fun gestureTap(x: Int, y: Int, durationMs: Long): Boolean {
        val path = Path().apply {
            moveTo(x.toFloat(), y.toFloat())
            // A zero-length path is rejected by some devices.
            lineTo(x.toFloat() + 0.5f, y.toFloat() + 0.5f)
        }
        return dispatchGesture(path, durationMs.coerceAtLeast(1L))
    }

    private suspend fun gestureLine(start: Point, end: Point, durationMs: Long): Boolean {
        val path = Path().apply {
            moveTo(start.x.toFloat(), start.y.toFloat())
            lineTo(end.x.toFloat(), end.y.toFloat())
        }
        return dispatchGesture(path, durationMs.coerceAtLeast(1L))
    }

    /**
     * Bridges the asynchronous gesture callback into a suspension.
     *
     * The callback is posted to the main looper, which is also the thread this
     * coroutine runs on - so this MUST suspend and must never block, otherwise
     * the callback can never be delivered.
     */
    private suspend fun dispatchGesture(path: Path, durationMs: Long): Boolean {
        val service = serviceRef() ?: return false
        val gesture = runCatching {
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0L, durationMs))
                .build()
        }.getOrElse { error ->
            log.e(TAG, "could not build the gesture", error)
            return false
        }
        val outcome = withTimeoutOrNull(GESTURE_TIMEOUT_MS) {
            suspendCancellableCoroutine<Boolean> { cont ->
                val callback = object : AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        if (cont.isActive) cont.resume(true)
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        if (cont.isActive) cont.resume(false)
                    }
                }
                val dispatched = runCatching {
                    service.dispatchGesture(gesture, callback, Handler(Looper.getMainLooper()))
                }.getOrElse { error ->
                    log.e(TAG, "dispatchGesture threw", error)
                    false
                }
                if (!dispatched) {
                    log.w(TAG, "dispatchGesture returned false (canPerformGestures missing, or service restarting)")
                    if (cont.isActive) cont.resume(false)
                }
            }
        }
        if (outcome == null) log.w(TAG, "gesture callback never arrived within ${GESTURE_TIMEOUT_MS}ms")
        return outcome == true
    }

    private fun gestureFailure(point: Point): EngineResult {
        val pkg = screens.foregroundPackage
        return if (AccessibilityScreenSource.isSensitivePackage(pkg)) {
            EngineResult.fail(
                "gesture rejected on a payment/security screen ($pkg)", FailureReason.SECURE_SCREEN
            )
        } else {
            EngineResult.fail(
                "gesture at (${point.x},${point.y}) was cancelled by the system", FailureReason.GESTURE_FAILED
            )
        }
    }

    companion object {
        private const val TAG = "engine"
        private const val TAP_MS = 45L
        private const val LONG_PRESS_MIN_MS = 600L
        private const val SCROLL_GESTURE_MS = 320L
        private const val SCROLL_SETTLE_MS = 380L
        private const val FOCUS_SETTLE_MS = 220L
        private const val GESTURE_TIMEOUT_MS = 4_000L
        private const val SCREENSHOT_TIMEOUT_MS = 5_000L
        private const val EDGE_MARGIN = 24
        private const val FIND_DEPTH = 30
        private const val MAX_ANCESTOR_HOPS = 6
    }
}
