package com.deuty.agent.core.util

import com.deuty.agent.core.model.ScreenSnapshot
import com.deuty.agent.core.model.ScreenSource
import com.deuty.agent.core.model.UiNode

/**
 * FNV-1a 64-bit.
 *
 * Chosen over String.hashCode() because it has far better avalanche behaviour
 * (two screens that differ by one node must not collide) and over SHA-256
 * because it is allocation-free and runs in microseconds on a 4 GB device.
 * This is an identity check, not a security hash.
 */
object StableHash {
    private const val OFFSET_BASIS = -0x340d631b7bdddcdbL // 0xcbf29ce484222325
    private const val PRIME = 0x100000001b3L

    fun hash64(input: String): Long {
        var h = OFFSET_BASIS
        for (i in input.indices) {
            h = h xor input[i].code.toLong()
            h *= PRIME
        }
        return h
    }

    fun toHex(value: Long): String {
        val chars = CharArray(16)
        var v = value
        for (i in 15 downTo 0) {
            val nibble = (v.toInt() and 0xF)
            chars[i] = if (nibble < 10) ('0' + nibble) else ('a' + nibble - 10)
            v = v ushr 4
        }
        return String(chars)
    }

    fun shortHex(input: String): String = toHex(hash64(input)).take(10)
}

/**
 * Screen identity.
 *
 * Used for three things: "did the screen change after my tap?", "have I seen
 * this screen before?" (loop detection) and "is the replayed workflow on the
 * screen the recording was made on?".
 *
 * It must therefore be *stable under noise*: a blinking cursor, a ticking clock
 * in the status bar or a progress spinner must not change the signature, or
 * every verification would report "screen changed" and loop detection would
 * never fire. That is why node text is truncated, coordinates are quantised to
 * a grid, and only a bounded number of labelled/interactive nodes contribute.
 */
object ScreenSignature {

    /** Quantisation grid, in pixels. 12dp-ish at xxhdpi. */
    private const val GRID = 24
    private const val MAX_NODES = 48
    private const val TEXT_LEN = 16

    private fun quantize(value: Int): Int = (value / GRID) * GRID

    /** Nodes that say something about *which* screen this is. */
    private fun salientNodes(snapshot: ScreenSnapshot): List<UiNode> {
        val candidates = snapshot.nodes.filter { it.isOnScreen && (it.isInteractive || it.label != null) }
        // Sort by position so the signature is independent of tree order, which
        // differs between apps and even between frames in lazy lists.
        return candidates
            .sortedWith(compareBy({ quantize(it.bounds.top) }, { quantize(it.bounds.left) }, { it.key }))
            .take(MAX_NODES)
    }

    fun compute(snapshot: ScreenSnapshot): String {
        if (snapshot.source == ScreenSource.UNAVAILABLE) return "unavailable"
        val sb = StringBuilder(128)
        sb.append(snapshot.packageName ?: "?").append('|')
        sb.append(normalize(snapshot.windowTitle ?: "").take(TEXT_LEN)).append('|')
        sb.append(snapshot.nodes.size).append('|')
        salientNodes(snapshot).forEach { n ->
            sb.append(n.idName ?: "-").append(':')
            sb.append(normalize(n.label ?: "").take(TEXT_LEN)).append(':')
            sb.append(n.simpleClassName ?: "-").append(':')
            sb.append(quantize(n.bounds.top)).append(',').append(quantize(n.bounds.left)).append(';')
        }
        if (snapshot.ocrLines.isNotEmpty()) {
            snapshot.ocrLines.take(12).forEach { sb.append("o:").append(normalize(it.text).take(TEXT_LEN)).append(';') }
        }
        return StableHash.shortHex(sb.toString())
    }

    /** Collapse whitespace + case so "Wi-Fi" and "wi  fi" are the same thing. */
    fun normalize(text: String): String =
        text.trim().replace('\u00A0', ' ').split(WHITESPACE).filter { it.isNotEmpty() }
            .joinToString(" ") { it }.lowercase()

    private val WHITESPACE = Regex("\\s+")

    /** A coarser signature used to ask "is this broadly the same screen?". */
    fun coarse(snapshot: ScreenSnapshot): String =
        StableHash.shortHex("${snapshot.packageName}|${normalize(snapshot.windowTitle ?: "")}")
}

/** Returns a copy with [ScreenSnapshot.signature] filled in. */
fun ScreenSnapshot.withSignature(): ScreenSnapshot =
    if (signature.isNotEmpty()) this else copy(signature = ScreenSignature.compute(this))
