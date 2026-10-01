package com.deuty.agent.core.model

/**
 * A captured frame.
 *
 * [payload] is the one deliberately type-erased value in the system: at runtime
 * it holds an android.graphics.Bitmap, but core/ and agent/ must stay free of
 * framework types so they can be unit-tested on a plain JVM and reused by the
 * sandbox. Only platform/ and vision/ are allowed to cast it.
 *
 * If a capture is not available (API < 30, user setting off, FLAG_SECURE screen)
 * the engine returns no ScreenCapture at all instead of an empty bitmap, so a
 * null payload can never be mistaken for "a black screen".
 */
data class ScreenCapture(
    val width: Int,
    val height: Int,
    val capturedAtMs: Long,
    val region: Bounds?,
    /** Scale factor reported by the framework (screenshot may be downscaled). */
    val scale: Float = 1f,
    val payload: Any? = null
) {
    val hasPixels: Boolean get() = payload != null && width > 0 && height > 0
}
