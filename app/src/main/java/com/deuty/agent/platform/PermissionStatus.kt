package com.deuty.agent.platform

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.text.TextUtils
import com.deuty.agent.core.util.AgentLog

/**
 * Permission checks and the intents that lead to them.
 *
 * Two Android realities shape the onboarding flow:
 *  1. Accessibility enablement cannot be requested at runtime - there is no
 *     dialog. The only thing an app can do is open the system screen and explain.
 *  2. On Android 13+, an app installed *outside* Play Store has its accessibility
 *     toggle blocked until the user chooses "Allow restricted settings" from the
 *     app-info overflow menu. Every sideloaded automation app hits this, users
 *     read it as "the app is broken", so the UI says it explicitly.
 */
object PermissionStatus {

    private const val TAG = "perms"

    fun isAccessibilityEnabled(context: Context): Boolean {
        val expected = ComponentName(context, AgentAccessibilityService::class.java)
            .flattenToString()
        return runCatching {
            val enabledServices = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return@runCatching false
            val splitter = TextUtils.SimpleStringSplitter(':')
            splitter.setString(enabledServices)
            var found = false
            while (splitter.hasNext()) {
                if (splitter.next().equals(expected, ignoreCase = true)) {
                    found = true
                    break
                }
            }
            val accessibilityOn = Settings.Secure.getInt(
                context.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0
            ) == 1
            found && accessibilityOn
        }.getOrElse { error ->
            // Some OEM builds throw when reading Secure settings from a service
            // context; fall back to "is our service instance alive".
            false
        }
    }

    /** The service instance is alive, which is the strongest signal available. */
    fun isServiceConnected(): Boolean = AgentAccessibilityService.instance != null

    fun canDrawOverlays(context: Context): Boolean = runCatching {
        Settings.canDrawOverlays(context)
    }.getOrDefault(false)

    fun openAccessibilitySettings(context: Context) {
        launch(context, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    fun openOverlaySettings(context: Context) {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}")
        )
        // Some OEM builds do not implement the per-app overlay screen.
        if (!launch(context, intent)) {
            launch(context, Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
        }
    }

    fun openAppDetails(context: Context) {
        launch(
            context,
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
        )
    }

    private fun launch(context: Context, intent: Intent): Boolean {
        return runCatching {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            true
        }.getOrElse { error ->
            android.util.Log.w(TAG, "could not open ${intent.action}: ${error.message}")
            false
        }
    }
}
