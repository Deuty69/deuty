package com.deuty.agent.ui

import android.os.Bundle
import android.widget.EditText
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.deuty.agent.AgentApplication
import com.deuty.agent.R
import com.deuty.agent.core.model.Workflow
import com.deuty.agent.databinding.ActivityWorkflowsBinding
import com.deuty.agent.di.AgentContainer
import kotlinx.coroutines.launch

/**
 * Saved workflows (requirements 9, 18).
 *
 * Recording is started and stopped from here as well as from the capsule and the
 * chat screen, because "I want to teach it this" is usually what brings a user to
 * this screen in the first place.
 */
class WorkflowsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityWorkflowsBinding
    private lateinit var container: AgentContainer
    private lateinit var adapter: WorkflowAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityWorkflowsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        container = AgentApplication.containerOf(this)

        adapter = WorkflowAdapter(onRun = ::run, onDelete = ::confirmDelete)
        binding.workflowList.layoutManager = LinearLayoutManager(this)
        binding.workflowList.adapter = adapter
        binding.workflowList.itemAnimator = null

        binding.backButton.setOnClickListener { finish() }
        binding.recordButton.setOnClickListener { toggleRecording() }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    container.workflows.workflows.collect { list ->
                        adapter.submit(list)
                        binding.workflowCount.text =
                            if (list.isEmpty()) "none saved" else "${list.size} saved"
                        binding.emptyText.visibility =
                            if (list.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
                    }
                }
                launch {
                    container.engine.status.collect { status ->
                        val recording = status.recording
                        binding.recordingBanner.visibility =
                            if (recording) android.view.View.VISIBLE else android.view.View.GONE
                        binding.recordingBanner.text =
                            getString(R.string.workflow_recording) + " (${status.recordedSteps})"
                        binding.recordButton.setText(
                            if (recording) R.string.quick_stop_record else R.string.quick_record
                        )
                    }
                }
            }
        }

        // Workflows may not have been loaded yet if the service never started.
        lifecycleScope.launch {
            runCatching { container.workflows.load() }
        }
    }

    private fun run(workflow: Workflow) {
        container.engine.runWorkflow(workflow.id)
        // Leave the list so the user can watch the run in the chat screen; the
        // capsule shows progress either way.
        finish()
    }

    private fun confirmDelete(workflow: Workflow) {
        AlertDialog.Builder(this)
            .setTitle(workflow.name)
            .setMessage("Delete this workflow? This cannot be undone.")
            .setPositiveButton(R.string.workflow_delete) { _, _ ->
                container.engine.deleteWorkflow(workflow.id)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun toggleRecording() {
        if (container.recorder.isRecording) {
            promptForName()
        } else {
            container.engine.startRecording()
        }
    }

    private fun promptForName() {
        val field = EditText(this).apply {
            hint = getString(R.string.workflow_record_hint)
            setPadding(48, 32, 48, 32)
        }
        val wrapper = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)
            addView(field)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.workflow_record_title)
            .setView(wrapper)
            .setPositiveButton(R.string.workflow_save) { _, _ ->
                container.engine.stopRecording(field.text?.toString()?.trim())
            }
            .setNegativeButton(R.string.workflow_discard) { _, _ ->
                container.engine.cancelRecording()
            }
            .show()
    }
}
