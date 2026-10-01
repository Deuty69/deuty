package com.deuty.agent.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.deuty.agent.AgentApplication
import com.deuty.agent.R
import com.deuty.agent.core.json.ScreenCodecs
import com.deuty.agent.core.json.toTreeText
import com.deuty.agent.core.model.AgentStatus
import com.deuty.agent.core.model.ScreenSnapshot
import com.deuty.agent.core.util.Ids
import com.deuty.agent.core.util.Redactor
import com.deuty.agent.databinding.ActivityInspectorBinding
import com.deuty.agent.di.AgentContainer
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * The UI inspector (requirement 27).
 *
 * This screen exists for one reason: when the agent does something surprising, the
 * question is always "what did it actually see?". Every field here answers part of
 * that - the package, the window, how many nodes were readable, which rung of the
 * understanding ladder produced them, the screen signature that verification
 * compares against, the last action, its result, the match tier that produced the
 * target, and the retry/budget counters that decide when the agent gives up.
 *
 * "Dump JSON" writes the whole tree to the app's private files directory. That dump
 * can be loaded back with [ScreenCodecs.decodeSnapshot] in a JVM test, which turns
 * a bug seen once on one phone into a permanent regression test. It is not
 * uploaded anywhere: the app has no INTERNET permission.
 */
class InspectorActivity : AppCompatActivity() {

    private lateinit var binding: ActivityInspectorBinding
    private lateinit var container: AgentContainer

    private var liveJob: Job? = null
    private var live = false
    private var lastSnapshot: ScreenSnapshot? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityInspectorBinding.inflate(layoutInflater)
        setContentView(binding.root)
        container = AgentApplication.containerOf(this)

        binding.backButton.setOnClickListener { finish() }
        binding.refreshButton.setOnClickListener { refresh() }
        binding.liveToggle.setOnClickListener { toggleLive() }
        binding.copyButton.setOnClickListener { copySummary() }
        binding.dumpButton.setOnClickListener { dumpJson() }
        binding.sandboxButton.setOnClickListener { container.engine.runSandboxDemo() }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { container.engine.status.collect { renderDebug(it) } }
                launch { container.log.version.collect { renderLog() } }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // While the inspector is open the screen source keeps capturing on events,
        // otherwise "Live" would show a stale tree and lie about it.
        container.inspectorLive = true
        binding.noAccessibility.visibility =
            if (container.isServiceConnected) android.view.View.GONE else android.view.View.VISIBLE
        refresh()
    }

    override fun onPause() {
        super.onPause()
        container.inspectorLive = false
        stopLive()
    }

    // ------------------------------------------------------------------ views

    private fun refresh() {
        if (!container.isServiceConnected) {
            binding.treeDump.setText(R.string.inspector_no_accessibility)
            return
        }
        lifecycleScope.launch {
            val snapshot = runCatching { container.screenSource.observe("inspector") }.getOrElse { error ->
                binding.treeDump.text = "observe failed: ${error.javaClass.simpleName}"
                return@launch
            }
            show(snapshot)
        }
    }

    private fun toggleLive() {
        if (live) {
            stopLive()
        } else {
            live = true
            binding.liveToggle.alpha = 0.5f
            liveJob = lifecycleScope.launch {
                container.screenSource.snapshots.collect { show(it) }
            }
        }
    }

    private fun stopLive() {
        live = false
        binding.liveToggle.alpha = 1f
        liveJob?.cancel()
        liveJob = null
    }

    private fun show(snapshot: ScreenSnapshot) {
        lastSnapshot = snapshot
        binding.treeDump.text = snapshot.toTreeText()
        binding.linePackage.text = "package    ${snapshot.packageName ?: "-"}"
        binding.lineWindow.text = "window     ${snapshot.windowTitle ?: "-"}"
        binding.lineNodes.text = "nodes      ${snapshot.nodes.size} " +
            "(${snapshot.interactiveNodes.size} interactive, ${snapshot.editableNodes.size} editable)"
        binding.lineSource.text = "source     ${snapshot.source.label}  in ${snapshot.captureDurationMs}ms" +
            if (snapshot.truncated) "  [TRUNCATED]" else ""
        binding.lineSignature.text = "signature  ${snapshot.signature.ifBlank { "-" }}"
    }

    private fun renderDebug(status: AgentStatus) {
        val debug = status.debug
        val task = status.task
        if (debug != null) {
            binding.linePackage.text = "package    ${debug.packageName ?: "-"}"
            binding.lineWindow.text = "window     ${debug.windowTitle ?: "-"}"
            binding.lineNodes.text =
                "nodes      ${debug.nodeCount} (${debug.interactiveCount} interactive)"
            binding.lineSource.text = "source     ${debug.source.label}  engine=${debug.engine}"
            binding.lineSignature.text = "signature  ${debug.signature}"
            binding.lineLastAction.text = "last       ${debug.lastAction ?: "-"} " +
                (debug.lastTier?.let { "[${it.label}]" } ?: "")
            binding.lineLastResult.text = "result     ${debug.lastResult ?: "-"}"
            binding.lineLoop.text = "loop       ${debug.loopWarning}"
            binding.lineLoop.visibility =
                if (debug.loopWarning == null) android.view.View.GONE else android.view.View.VISIBLE
        }
        if (task != null) {
            val retries = debug?.totalRetries ?: 0
            val stepRetries = debug?.retryCount ?: 0
            binding.lineState.text =
                "state      ${task.state.name}  step ${task.steps.count { it.isCurrent }}/${task.steps.size}"
            binding.lineBudget.text =
                "budget     ${task.actionsUsed}/${task.maxActions} actions, ${task.elapsedMs / 1000}s elapsed\n" +
                "           retries  step=$stepRetries total=$retries  risk=${task.riskLevel.name}"
        } else {
            binding.lineState.text = "state      IDLE"
            binding.lineBudget.text = "budget     -"
        }
    }

    private fun renderLog() {
        val events = container.log.snapshot(limit = 120)
        binding.eventLog.text = if (events.isEmpty()) {
            "no events yet"
        } else {
            events.joinToString("\n") { it.line() }
        }
    }

    // ---------------------------------------------------------------- actions

    /**
     * Copies a redacted summary. Redaction is applied even when the user setting is
     * off, because the clipboard is readable by whichever app gains focus next -
     * which is a different threat model from our own private storage.
     */
    private fun copySummary() {
        val snapshot = lastSnapshot
        if (snapshot == null) {
            toast("Nothing captured yet")
            return
        }
        val raw = snapshot.toTreeText(maxNodes = 200)
        val safe = Redactor.text(raw, true) ?: raw
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clipboard?.setPrimaryClip(ClipData.newPlainText("deuty-screen", safe))
        toast(getString(R.string.inspector_copied))
    }

    private fun dumpJson() {
        val snapshot = lastSnapshot
        if (snapshot == null) {
            toast("Nothing captured yet")
            return
        }
        val name = "dump-${Ids.dump()}.json"
        val written = runCatching {
            container.store.writeText(name, ScreenCodecs.encodeSnapshot(snapshot).toString(2))
        }.getOrElse { error ->
            container.log.e("inspector", "dump failed", error)
            false
        }
        if (written) {
            val path = java.io.File(container.store.root, name).absolutePath
            binding.dumpPath.text = getString(R.string.inspector_dump_written, path)
            binding.dumpPath.visibility = android.view.View.VISIBLE
            toast("Dumped ${snapshot.nodes.size} nodes")
        } else {
            toast("Dump failed - see the event log")
        }
    }

    private fun toast(message: String) {
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show()
    }
}
