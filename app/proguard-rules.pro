# Rules are prepared but inert while isMinifyEnabled = false.
# If R8 is enabled, these keep the parts of the app the *system* instantiates
# or reads reflectively.

# The AccessibilityService is instantiated by the system from the manifest.
-keep class com.deuty.agent.android.AgentAccessibilityService { *; }

# Anything reachable from an accessibility_service_config.xml attribute.
-keep class * extends android.accessibilityservice.AccessibilityService { *; }

# Models are serialised by hand with org.json (no reflection), but keep the
# names so crash reports stay readable.
-keepnames class com.deuty.agent.core.model.** { *; }
-keepnames class com.deuty.agent.agent.executor.** { *; }

# Kotlin metadata + coroutines
-keepattributes *Annotation*, InnerClasses, Signature, Exceptions
-dontwarn kotlinx.coroutines.**
-dontwarn org.jetbrains.annotations.**
