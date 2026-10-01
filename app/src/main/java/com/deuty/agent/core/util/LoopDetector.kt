package com.deuty.agent.core.util

/**
 * Loop detection (requirement 20).
 *
 * The failure mode this prevents is the worst one an automation agent has: an
 * infinite cycle that quietly burns battery and may keep tapping "Send".
 *
 * Two independent signals:
 *  1. REPEAT   - the same (screen, action) pair occurs >= threshold times in a
 *                sliding window. Catches A -> tap -> B -> tap -> A -> tap -> B.
 *  2. NO_PROGRESS - many actions executed but no new screen signature appeared.
 *                Catches taps that do nothing at all (dead element, dialog we
 *                cannot dismiss, gesture swallowed by a WebView).
 *
 * It is deliberately stateful per task and reset on every new task, retry and
 * successful step: a workflow that legitimately visits the same screen three
 * times *with progress in between* must not be stopped.
 */
class LoopDetector(
    private val window: Int = 16,
    private val repeatThreshold: Int = 3,
    private val noProgressThreshold: Int = 8
) {
    private data class Entry(val signature: String, val action: String)

    private val entries = ArrayDeque<Entry>()
    private val seenSignatures = LinkedHashSet<String>()
    private var lastWarning: String? = null
    private var stepsSinceProgress = 0

    val warning: String? get() = lastWarning

    fun reset() {
        entries.clear()
        seenSignatures.clear()
        lastWarning = null
        stepsSinceProgress = 0
    }

    /** Call once per executed action, with the screen signature observed *before* it. */
    fun record(signature: String?, action: String): LoopVerdict {
        val sig = signature ?: "unknown"
        val isNewScreen = seenSignatures.add(sig)
        stepsSinceProgress = if (isNewScreen) 0 else stepsSinceProgress + 1

        entries.addLast(Entry(sig, action))
        while (entries.size > window) entries.removeFirst()

        val key = Entry(sig, action)
        val repeats = entries.count { it == key }

        return when {
            repeats >= repeatThreshold -> verdict(
                "I appear to be stuck between two screens: \"$action\" on screen $sig " +
                    "has now produced the same state $repeats times."
            )

            stepsSinceProgress >= noProgressThreshold -> verdict(
                "Nothing has changed for $stepsSinceProgress actions in a row " +
                    "(current screen $sig). Either the taps are not landing or this " +
                    "screen needs you."
            )

            else -> {
                lastWarning = null
                LoopVerdict(false, null, if (repeats > 1) repeats else null)
            }
        }
    }

    private fun verdict(message: String): LoopVerdict {
        lastWarning = message
        return LoopVerdict(true, message, entries.count { it == entries.lastOrNull() })
    }

    /** Read-only view for the debug panel. */
    fun snapshot(): List<String> = entries.map { "${it.signature} <- ${it.action}" }

    fun distinctScreens(): Int = seenSignatures.size

    fun actionsRecorded(): Int = entries.size
}

data class LoopVerdict(
    val isLoop: Boolean,
    val message: String?,
    /** How many times the current (screen, action) pair has repeated. */
    val repeats: Int?
)
