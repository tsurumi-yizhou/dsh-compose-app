// Portions derived from Phase (https://github.com/CikeSeven/phase), used with the author's
// permission. See THIRD_PARTY_NOTICES.md.
// Local changes: blocking, one owner per task (the bridge serialises calls on one worker
// thread); a UiAutomation control-tree read rendered to the screen text format, so text-only
// models can use 不打扰模式 too; IME policy HIDE; JPEG capture with bounded first-frame readiness;
// display
// sized to the phone's aspect (short side 720). No commands, paths or display ids are accepted
// from callers.
package dev.fughetta.dshcompose.phone.background

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.UiAutomation
import android.content.Context
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import dev.fughetta.dshcompose.phone.background.PrivilegedDisplayCore.Fault
import java.lang.reflect.Proxy

internal class TreeConnection(private val context: Context, private val target: String) {
    private val clientType =
        Class.forName("android.accessibilityservice.IAccessibilityServiceClient")
    private val managerType = Class.forName("android.view.accessibility.IAccessibilityManager")
    private val manager =
        Class.forName("android.view.accessibility.IAccessibilityManager\$Stub")
            .getMethod("asInterface", IBinder::class.java)
            .invoke(
                null,
                Class.forName("android.os.ServiceManager")
                    .getMethod("getService", String::class.java)
                    .invoke(null, Context.ACCESSIBILITY_SERVICE),
            )
    private val token = Binder()
    private var client: Any? = null
    private var automation: UiAutomation? = null

    fun open(display: Display): UiAutomation {
        if (Build.VERSION.SDK_INT < 34) throw Fault("textConnectionUnavailable")
        val connectionType = Class.forName("android.app.IUiAutomationConnection")
        val connection =
            Proxy.newProxyInstance(connectionType.classLoader, arrayOf(connectionType)) {
                proxy,
                method,
                args ->
                when (method.name) {
                    "asBinder" -> token
                    "connect" -> {
                        runCatching { register(args!![0]!!) }
                        null
                    }
                    "disconnect" -> {
                        unregister()
                        null
                    }
                    "toString" -> "TreeConnection"
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args?.get(0)
                    else -> throw UnsupportedOperationException("node only")
                }
            }
        val value =
            UiAutomation::class
                .java
                .getConstructor(Context::class.java, connectionType)
                .newInstance(context.createDisplayContext(display), connection) as UiAutomation
        automation = value
        UiAutomation::class
            .java
            .getMethod(
                "connectWithTimeout",
                Int::class.javaPrimitiveType,
                Long::class.javaPrimitiveType,
            )
            .invoke(value, UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES, 3000L)
        return value
    }

    private fun register(value: Any) {
        val info =
            AccessibilityServiceInfo().apply {
                packageNames = arrayOf(target)
                eventTypes = AccessibilityEvent.TYPES_ALL_MASK
                feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
                flags =
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                        AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
                        AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
            }
        AccessibilityServiceInfo::class
            .java
            .getMethod("setCapabilities", Int::class.javaPrimitiveType)
            .invoke(info, AccessibilityServiceInfo.CAPABILITY_CAN_RETRIEVE_WINDOW_CONTENT)
        val userId =
            Class.forName("android.os.UserHandle")
                .getMethod("getUserId", Int::class.javaPrimitiveType)
                .invoke(null, context.applicationInfo.uid)
        managerType
            .getMethod(
                "registerUiTestAutomationService",
                IBinder::class.java,
                clientType,
                AccessibilityServiceInfo::class.java,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
            )
            .invoke(
                manager,
                token,
                value,
                info,
                userId,
                UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES,
            )
        client = value
    }

    private fun unregister() {
        val value = client ?: return
        runCatching {
            managerType
                .getMethod("unregisterUiTestAutomationService", clientType)
                .invoke(manager, value)
        }
        client = null
    }

    fun close() {
        runCatching {
            automation?.let { UiAutomation::class.java.getMethod("disconnect").invoke(it) }
        }
        automation = null
        unregister()
    }
}
