package com.deuty.agent.agent.port

import com.deuty.agent.core.model.Bounds
import com.deuty.agent.core.model.Direction
import com.deuty.agent.core.model.FailureReason
import com.deuty.agent.core.model.MatchedElement
import com.deuty.agent.core.model.Point
import com.deuty.agent.core.model.ScreenCapture
import com.deuty.agent.core.model.ScreenSnapshot

/**
 * PORTS.
 *
 * These two interfaces are the entire boundary between "the agent" and
 * "Android". Everything above them is pure Kotlin; everything below them is
 * framework code. Two consequences that matter:
 *
 *  1. The engine is testable and runnable without a device (SimulatedDevice
 *     implements both ports).
 *  2. Requirement 2 - "the AI decides *what*, the deterministic engine decides
 *     *how*" - is enforced by the compiler: a planner cannot reach an
 *     AccessibilityNodeInfo even if it wanted to.
 */

/** A navigation/global action the system permits a service to perform. */
enum class GlobalAction { BACK, HOME, RECENTS, NOTIFICATIONS, QUICK_SETTINGS }

data class EngineResult(
    val ok: Boolean,
    val message: String,
    val failure: FailureReason? = null,
    val capture: ScreenCapture? = null,
    /** Extra structured data, e.g. the resolved package name of a launch. */
    val detail: String? = null,
    /** True when the operation is impossible on this device/API, not just failed. */
    val unsupported: Boolean = false
) {
    companion object {
        fun ok(message: String = "done", detail: String? = null) = EngineResult(true, message, detail = detail)
        fun fail(message: String, failure: FailureReason) = EngineResult(false, message, failure = failure)
        fun unsupported(message: String) = EngineResult(false, message, FailureReason.NOT_IMPLEMENTED, unsupported = true)
    }
}

/**
 * What this particular engine is able to do.
 *
 * Capability discovery is explicit rather than optimistic: the executor checks
 * before attempting, so the user gets "this device cannot do that" instead of a
 * silent no-op that the verifier then reports as a mysterious timeout.
 */
data class EngineCapabilities(
    val gestures: Boolean = false,
    val nodeActions: Boolean = false,
    val screenshots: Boolean = false,
    val globalActions: Boolean = false,
    val clipboard: Boolean = false,
    val launchApps: Boolean = false,
    val imeActionInjection: Boolean = false,
    val description: String = "unavailable"
)

interface InteractionEngine {
    val name: String
    val available: Boolean
    val capabilities: EngineCapabilities

    /** Perform a click. Prefers the node's own click action over a gesture tap. */
    suspend fun click(target: MatchedElement): EngineResult

    suspend fun longClick(target: MatchedElement, durationMs: Long): EngineResult

    /**
     * Set text on an editable node.
     *
     * [target] null means "the currently focused field". [clearFirst] makes the
     * operation idempotent, which is what allows a failed step to be retried
     * without duplicating text.
     */
    suspend fun setText(target: MatchedElement?, text: String, clearFirst: Boolean): EngineResult

    /**
     * Press the IME action (Enter/Search/Send) if it can be found.
     *
     * Android does not let an accessibility service inject key events, so this
     * is implemented by tapping the on-screen action key. Returns
     * [EngineResult.unsupported] when there is nothing to tap; callers must then
     * fall back to asking the user.
     */
    suspend fun pressImeAction(): EngineResult

    suspend fun paste(text: String): EngineResult

    suspend fun swipe(
        direction: Direction,
        origin: MatchedElement? = null,
        distanceRatio: Float = 0.55f,
        durationMs: Long = 280L
    ): EngineResult

    /**
     * Scroll. Uses the node's scroll action when the container exposes one and
     * falls back to a swipe inside [container]'s bounds (or the screen centre).
     */
    suspend fun scroll(direction: Direction, container: MatchedElement?, times: Int = 1): EngineResult

    suspend fun globalAction(action: GlobalAction): EngineResult

    suspend fun launchApp(packageName: String): EngineResult

    /**
     * Capture the screen. Level 2 - only used when the tree cannot answer.
     * [region] crops to the interesting area before any pixel work happens.
     */
    suspend fun captureScreen(region: Bounds?): EngineResult

    fun displaySize(): Point?
}

interface ScreenSource {
    val name: String
    val available: Boolean

    /** Read the screen now. Cheapest available level, never a screenshot. */
    suspend fun observe(reason: String = "observe"): ScreenSnapshot

    /** Event-driven snapshot stream; only emits when the screen really changed. */
    val snapshots: kotlinx.coroutines.flow.Flow<ScreenSnapshot>

    /**
     * Suspend until the screen signature differs from [fromSignature], or until
     * [timeoutMs] elapses. This is the event-driven replacement for polling and
     * the backbone of action verification.
     */
    suspend fun awaitChange(fromSignature: String?, timeoutMs: Long): ScreenSnapshot?

    /** The signature of the last observed screen, without doing any work. */
    fun lastSignature(): String?

    fun lastSnapshot(): ScreenSnapshot?
}

/**
 * Resolves a human/AI-visible app label to a package name.
 * Separated from InteractionEngine because it needs a PackageManager, not a
 * service, and because the sandbox implements it from a fixed table.
 */
interface AppDirectory {
    /** Exact-ish package for a label ("Settings", "Files", "Chrome"). */
    suspend fun resolve(label: String): String?

    /** All launchable apps, label -> package. Cached. */
    suspend fun launchableApps(): Map<String, String>

    /** Human label for a package, for reports ("com.android.settings" -> "Settings"). */
    suspend fun labelOf(packageName: String?): String?

    fun refresh()
}
