package com.deuty.agent.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.deuty.agent.core.model.Workflow
import com.deuty.agent.databinding.ItemWorkflowBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Saved workflows, with run and delete. */
class WorkflowAdapter(
    private val onRun: (Workflow) -> Unit,
    private val onDelete: (Workflow) -> Unit
) : RecyclerView.Adapter<WorkflowAdapter.Holder>() {

    private var items: List<Workflow> = emptyList()
    private val dateFormat = SimpleDateFormat("d MMM HH:mm", Locale.getDefault())

    fun submit(next: List<Workflow>) {
        items = next.sortedByDescending { it.updatedAtMs }
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder = Holder(
        ItemWorkflowBinding.inflate(LayoutInflater.from(parent.context), parent, false)
    )

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(items[position])

    inner class Holder(private val binding: ItemWorkflowBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(workflow: Workflow) {
            val context = binding.root.context
            binding.workflowName.text = workflow.name
            binding.workflowMeta.text = buildString {
                append(context.getString(com.deuty.agent.R.string.workflow_steps, workflow.steps.size))
                append(" · ").append(workflow.source.name.lowercase())
                append(" · risk ").append(workflow.riskLevel.name)
                append(" · ").append(dateFormat.format(Date(workflow.updatedAtMs)))
                if (workflow.runCount > 0) {
                    append(" · run ${workflow.runCount}x (${workflow.successCount} ok)")
                }
                if (workflow.hasBrittleSteps) append(" · brittle steps")
            }
            binding.workflowSteps.text = workflow.steps.take(4)
                .joinToString("\n") { it.title }
                .let { text ->
                    if (workflow.steps.size > 4) text + "\n+${workflow.steps.size - 4} more" else text
                }
            binding.runButton.setOnClickListener { onRun(workflow) }
            binding.deleteButton.setOnClickListener { onDelete(workflow) }
        }
    }
}
