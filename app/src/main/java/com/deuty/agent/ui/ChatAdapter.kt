package com.deuty.agent.ui

import android.view.Gravity
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.recyclerview.widget.RecyclerView
import com.deuty.agent.R
import com.deuty.agent.core.model.ChatMessage
import com.deuty.agent.core.model.Role
import com.deuty.agent.databinding.ItemMessageBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Chat bubbles.
 *
 * One layout, four roles: the adapter swaps background, gravity and label. Using
 * four view types would mean four XML files that drift apart the first time
 * someone changes the padding on one of them.
 *
 * Updates are incremental on purpose. A long conversation re-bound from scratch on
 * every message would scroll the list and cost a full layout pass; appending one
 * item costs one bind.
 */
class ChatAdapter : RecyclerView.Adapter<ChatAdapter.Holder>() {

    private var items: List<ChatMessage> = emptyList()
    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

    /** Returns the index to scroll to, or -1 when nothing changed. */
    fun submit(next: List<ChatMessage>): Int {
        val previousSize = items.size
        val previousLastId = items.lastOrNull()?.id
        items = next
        return when {
            next.isEmpty() -> {
                notifyDataSetChanged()
                -1
            }

            next.size == previousSize + 1 && previousLastId != null &&
                next.getOrNull(previousSize - 1)?.id == previousLastId -> {
                notifyItemInserted(previousSize)
                previousSize
            }

            else -> {
                notifyDataSetChanged()
                next.size - 1
            }
        }
    }

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder = Holder(
        ItemMessageBinding.inflate(LayoutInflater.from(parent.context), parent, false)
    )

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(items[position])

    inner class Holder(private val binding: ItemMessageBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(message: ChatMessage) {
            val context = binding.root.context
            val user = message.role == Role.USER
            val params = binding.bubble.layoutParams as FrameLayout.LayoutParams
            params.gravity = if (user) Gravity.END else Gravity.START
            binding.bubble.layoutParams = params

            binding.bubble.setBackgroundResource(
                if (user) R.drawable.bg_bubble_user else R.drawable.bg_bubble_agent
            )
            binding.roleLabel.setText(
                when (message.role) {
                    Role.USER -> R.string.role_user
                    Role.AGENT -> R.string.role_agent
                    Role.SYSTEM -> R.string.role_system
                    Role.ERROR -> R.string.role_system
                }
            )
            binding.roleLabel.setTextColor(
                context.getColor(
                    when (message.role) {
                        Role.ERROR -> R.color.status_error
                        Role.SYSTEM -> R.color.text_secondary
                        Role.USER -> R.color.brand_dark
                        Role.AGENT -> R.color.text_secondary
                    }
                )
            )
            binding.messageText.text = message.text
            binding.messageTime.text = timeFormat.format(Date(message.atMs))
        }
    }
}
