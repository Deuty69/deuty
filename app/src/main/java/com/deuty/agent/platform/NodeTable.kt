package com.deuty.agent.platform

import android.view.accessibility.AccessibilityNodeInfo
import com.deuty.agent.core.util.AgentLog

/**
 * Live handle table.
 *
 * A [com.deuty.agent.core.model.UiNode] is a *copy*: it has no way to perform an
 * action. To click a node, set its text or scroll it, we need the live
 * AccessibilityNodeInfo. This class maps `UiNode.key -> live node` for the
 * current capture generation only.
 *
 * Lifecycle invariant (important, and the reason this class exists):
 *   - [beginGeneration] is called at the start of every tree capture.
 *   - The previous generation's nodes are recycled there, which is safe because
 *     the engine is strictly sequential: capture -> resolve -> act -> capture.
 *     An action is never in flight while a new capture starts.
 *   - Handles can go stale if the app redraws. Every use site checks the return
 *     value of performAction() and the caller re-observes on failure; a stale
 *     handle can never cause a wrong tap, only a failed one.
 *
 * recycle() is a no-op from API 33 (the node pool was removed) but is required
 * on API 26-32, where leaking nodes exhausts a fixed-size pool and starts
 * returning null trees.
 */
class NodeTable(private val log: AgentLog) {

    private val live = HashMap<Int, AccessibilityNodeInfo>()

    @Volatile
    var generation: Int = 0
        private set

    val size: Int get() = live.size

    /** Recycles the previous generation and starts a new one. */
    fun beginGeneration(): Int {
        recycleAll()
        generation += 1
        return generation
    }

    fun put(key: Int, node: AccessibilityNodeInfo) {
        live[key] = node
    }

    fun get(key: Int): AccessibilityNodeInfo? = live[key]

    fun clear() = recycleAll()

    private fun recycleAll() {
        if (live.isEmpty()) return
        val count = live.size
        live.values.forEach { node ->
            @Suppress("DEPRECATION")
            runCatching { node.recycle() }
        }
        live.clear()
        log.d(TAG, "recycled $count node handle(s)")
    }

    companion object {
        private const val TAG = "nodetable"
    }
}
