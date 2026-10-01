package com.deuty.agent.vision

import com.deuty.agent.agent.port.VisionService
import com.deuty.agent.core.model.Bounds
import com.deuty.agent.core.model.ElementQuery
import com.deuty.agent.core.model.MatchedElement
import com.deuty.agent.core.model.MatchTier
import com.deuty.agent.core.model.OcrLine
import com.deuty.agent.core.model.Point
import com.deuty.agent.core.model.ScreenCapture
import com.deuty.agent.core.util.AgentLog
import com.deuty.agent.core.util.TextMatch

/**
 * The OCR rung of the screen-understanding ladder (requirement 3).
 *
 * The ladder is: accessibility tree -> screenshot -> light OCR -> vision model,
 * cheapest first, and each rung is only used when the previous one could not
 * answer. In practice the tree answers almost everything, so OCR is a fallback
 * for canvas-drawn UI (games, maps, PDF viewers, some WebViews) where the tree is
 * one opaque node.
 *
 * This class is the *adapter*; the actual text recognition sits behind [OcrEngine]
 * so that:
 *   - core/ and agent/ stay free of Android and of any third-party SDK;
 *   - the app ships and works with no OCR dependency at all ([NoOcrEngine]);
 *   - adding ML Kit (`com.google.mlkit:text-recognition`, on-device, ~4 MB) later
 *     is a one-class change plus one dependency, not a refactor.
 *
 * Nothing here sends pixels anywhere. OCR runs on-device or not at all.
 */
class OcrVisionService(
    private val engine: OcrEngine,
    private val log: AgentLog
) : VisionService {

    override val available: Boolean get() = engine.available

    override val description: String
        get() = if (engine.available) "ocr: ${engine.description}" else "ocr unavailable (${engine.description})"

    override suspend fun readText(capture: ScreenCapture, region: Bounds?): List<OcrLine> {
        if (!engine.available || !capture.hasPixels) return emptyList()
        val started = System.nanoTime()
        val lines = runCatching { engine.recognize(capture.payload, region) }
            .getOrElse { error ->
                log.w("vision", "ocr failed: ${error.javaClass.simpleName}")
                emptyList()
            }
        val ms = (System.nanoTime() - started) / 1_000_000L
        log.d("vision", "ocr produced ${lines.size} line(s) in ${ms}ms")
        return lines
    }

    /**
     * Locate by OCR text. Produces [MatchTier.OCR] matches, which the resolver
     * ranks below every structural tier and above raw coordinates - so a
     * canvas-drawn "Download" button is findable, but never preferred to a real
     * node with the same label.
     */
    override suspend fun locate(capture: ScreenCapture, query: ElementQuery): List<MatchedElement> {
        val needle = query.ocrText ?: query.text ?: query.textContains ?: query.contentDescription
        if (needle.isNullOrBlank()) return emptyList()
        val lines = readText(capture, query.bounds)
        if (lines.isEmpty()) return emptyList()

        val exact = mutableListOf<MatchedElement>()
        val partial = mutableListOf<MatchedElement>()
        lines.forEach { line ->
            val confidence = line.confidence.coerceIn(0f, 1f)
            if (confidence < MIN_CONFIDENCE) return@forEach
            when {
                TextMatch.equalsLoose(line.text, needle) ->
                    exact += MatchedElement(
                        node = null,
                        point = centreOf(line.bounds, capture.scale),
                        tier = MatchTier.OCR,
                        score = 0.6f + 0.2f * confidence,
                        reason = "ocr exact \"${line.text.take(40)}\""
                    )

                TextMatch.contains(line.text, needle) ->
                    partial += MatchedElement(
                        node = null,
                        point = centreOf(line.bounds, capture.scale),
                        tier = MatchTier.OCR,
                        score = 0.45f + 0.2f * confidence,
                        reason = "ocr contains \"${line.text.take(40)}\""
                    )
            }
        }
        return (exact.ifEmpty { partial }).sortedByDescending { it.score }
    }

    /**
     * OCR bounds are in screenshot pixels; the capture may be downscaled by the
     * framework, so scale back to display coordinates before tapping.
     */
    private fun centreOf(bounds: Bounds, scale: Float): Point {
        val factor = if (scale > 0f) 1f / scale else 1f
        return Point((bounds.centerX * factor).toInt(), (bounds.centerY * factor).toInt())
    }

    companion object {
        private const val MIN_CONFIDENCE = 0.45f
    }
}

/**
 * Recognition backend port. Implement this with ML Kit / Tesseract / PaddleOCR
 * and the whole OCR rung lights up; nothing else changes.
 */
interface OcrEngine {
    val available: Boolean
    val description: String

    /**
     * [payload] is the opaque capture payload (an android.graphics.Bitmap at
     * runtime). This is the only place in the app allowed to cast it.
     */
    suspend fun recognize(payload: Any?, region: Bounds?): List<OcrLine>
}

/**
 * Shipped default: no OCR.
 *
 * Deliberate, not an oversight. Adding a 4 MB model to a build whose MVP is
 * "open Settings, tap Wi-Fi, record a workflow" would cost every user download
 * size and RAM to benefit the handful of canvas-drawn apps. The ladder degrades
 * honestly instead: the resolver reports OCR_UNAVAILABLE and recovery asks the
 * user, rather than pretending to read a screen it cannot read.
 */
object NoOcrEngine : OcrEngine {
    override val available = false
    override val description = "no on-device OCR engine in this build"
    override suspend fun recognize(payload: Any?, region: Bounds?): List<OcrLine> = emptyList()
}
