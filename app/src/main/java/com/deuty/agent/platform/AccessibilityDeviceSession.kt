package com.deuty.agent.platform

import android.accessibilityservice.AccessibilityService
import com.deuty.agent.agent.port.AppDirectory
import com.deuty.agent.agent.port.DeviceSession
import com.deuty.agent.agent.port.InteractionEngine
import com.deuty.agent.agent.port.ScreenSource
import com.deuty.agent.agent.port.VisionService

/**
 * The real device, as a [DeviceSession].
 *
 * The session holds no strong reference to the service: [serviceRef] is a lambda
 * owned by the container, which nulls it out in `onDestroy`. That matters because
 * an accessibility service is destroyed whenever the user toggles it off, and a
 * session that kept it alive would keep a destroyed Service - and its Context -
 * reachable from a long-lived engine. That is the classic leak in this category
 * of app, and it ends in a crash the first time the user flips the switch.
 *
 * [available] is therefore *live*: the engine asks before every task rather than
 * trusting the session it was handed at startup.
 */
class AccessibilityDeviceSession(
    private val serviceRef: () -> AccessibilityService?,
    override val interaction: InteractionEngine,
    override val screens: ScreenSource,
    override val apps: AppDirectory,
    override val vision: VisionService
) : DeviceSession {

    override val id: String = "accessibility"

    override val available: Boolean
        get() = serviceRef() != null && interaction.available && screens.available
}
