package com.deuty.agent.agent.port

import com.deuty.agent.core.model.Bounds
import com.deuty.agent.core.model.ElementQuery
import com.deuty.agent.core.model.MatchedElement
import com.deuty.agent.core.model.OcrLine
import com.deuty.agent.core.model.ScreenCapture

/**
 * Levels 2-4 of screen understanding (requirement 4), behind one interface.
 *
 * V1 ships [NoVision] only: the accessibility tree answers almost everything and
 * costs nothing. The interface exists so that adding OCR later is a
 * configuration change, not a refactor - and so the rest of the system never
 * contains a `Bitmap` or an `if (ocr != null)` branch.
 */
interface VisionService {
    val available: Boolean
    val description: String

    /** Level 3: text out of an already-captured frame. */
    suspend fun readText(capture: ScreenCapture, region: Bounds? = null): List<OcrLine>

    /** Level 4: "where is the thing described by this query?" in an image. */
    suspend fun locate(capture: ScreenCapture, query: ElementQuery): List<MatchedElement>
}

object NoVision : VisionService {
    override val available = false
    override val description = "vision disabled (accessibility tree only)"
    override suspend fun readText(capture: ScreenCapture, region: Bounds?): List<OcrLine> = emptyList()
    override suspend fun locate(capture: ScreenCapture, query: ElementQuery): List<MatchedElement> = emptyList()
}

/**
 * The bundle of device capabilities the agent currently has.
 *
 * Exactly one session is attached at a time:
 *   - the real AccessibilityService (normal operation),
 *   - a [com.deuty.agent.agent.sandbox.SimulatedDevice] (sandbox / unit tests),
 *   - nothing (accessibility disabled -> the engine answers "I cannot act yet"
 *     instead of crashing or pretending).
 *
 * Sessions are swapped on the main thread and read through a volatile holder, so
 * the agent never holds a stale reference to a destroyed service - which is the
 * classic way an accessibility-based app leaks a Service and crashes after the
 * user toggles it off.
 */
interface DeviceSession {
    val id: String
    val available: Boolean
    val interaction: InteractionEngine
    val screens: ScreenSource
    val apps: AppDirectory
    val vision: VisionService
}

/**
 * Per-execution policy, derived from user settings and the task's constraints.
 * Computed once per task so the executor never has to read SharedPreferences
 * (which is disk I/O) inside an accessibility callback.
 */
data class ExecContext(
    val allowPasswordEntry: Boolean = false,
    val allowScreenshots: Boolean = false,
    val blockSensitiveScreens: Boolean = true,
    val confirmAtOrAbove: com.deuty.agent.core.model.RiskLevel = com.deuty.agent.core.model.RiskLevel.MEDIUM,
    val redact: Boolean = true,
    val taskId: String? = null,
    val stepId: String? = null
)
