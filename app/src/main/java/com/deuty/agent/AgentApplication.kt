package com.deuty.agent

import android.app.Application
import com.deuty.agent.di.AgentContainer

/**
 * Owns the single process-scoped [AgentContainer].
 *
 * The container is created here rather than in the accessibility service so that
 * the chat UI works - and can explain what is missing - before the user has
 * granted anything, and so that toggling the service off and on does not rebuild
 * the engine and lose a task that was mid-flight.
 *
 * `onCreate` does no I/O on the main thread beyond constructing objects: loading
 * messages, workflows and an interrupted task happens inside `engine.start()`, on
 * a background dispatcher.
 */
class AgentApplication : Application() {

    lateinit var container: AgentContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AgentContainer(this)
    }

    companion object {
        fun from(context: android.content.Context): AgentApplication =
            context.applicationContext as AgentApplication

        fun containerOf(context: android.content.Context): AgentContainer = from(context).container
    }
}
