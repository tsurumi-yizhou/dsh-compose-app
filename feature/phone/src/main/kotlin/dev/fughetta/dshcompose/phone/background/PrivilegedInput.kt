// Portions derived from Phase (https://github.com/CikeSeven/phase), used with the author's
// permission. See THIRD_PARTY_NOTICES.md.
// Local changes: blocking, one owner per task (the bridge serialises calls on one worker
// thread); a UiAutomation control-tree read rendered to the screen text format, so text-only
// models can use 不打扰模式 too; IME policy HIDE; JPEG capture with bounded first-frame readiness;
// display
// sized to the phone's aspect (short side 720). No commands, paths or display ids are accepted
// from callers.
package dev.fughetta.dshcompose.phone.background

import android.annotation.SuppressLint
import android.graphics.Rect
import android.hardware.input.InputManager
import android.os.Bundle
import android.os.SystemClock
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import dev.fughetta.dshcompose.domain.phone.BackgroundTree
import dev.fughetta.dshcompose.phone.background.PrivilegedDisplayCore.Companion.KEYS
import dev.fughetta.dshcompose.phone.background.PrivilegedDisplayCore.Fault
import java.lang.reflect.InvocationTargetException

internal fun PrivilegedDisplayCore.resolveElement(pkg: String, request: Bundle): BgNode {
    val index = request.getInt(Proto.ELEMENT, -1)
    if (index < 1) throw Fault("invalidArguments")
    val elements = readTree(displayId(), pkg).elements
    val e = elements.getOrNull(index - 1) ?: throw Fault("elementMissing")
    val expectRole = request.getString(Proto.EXPECT_ROLE)
    val expectLabel = request.getString(Proto.EXPECT_LABEL)
    if (expectRole != null && expectRole != e.role) throw Fault("staleSnapshot")
    if (
        expectLabel != null && expectLabel.isNotEmpty() && !e.label.startsWith(expectLabel.take(12))
    )
        throw Fault("staleSnapshot")
    return e
}

internal fun PrivilegedDisplayCore.tap(pkg: String, request: Bundle) {
    val e = resolveElement(pkg, request)
    val cx = (e.bounds.left + e.bounds.right) / 2
    val cy = (e.bounds.top + e.bounds.bottom) / 2
    val long = request.getBoolean(Proto.LONG)
    injectTap(cx, cy, long)
}

internal fun PrivilegedDisplayCore.scroll(request: Bundle) {
    val dir = request.getString(Proto.DIR) ?: "down"
    val cx = width / 2
    val cy = height / 2
    val span = height / 3
    val p =
        when (dir) {
            "up" -> intArrayOf(cx, cy - span, cx, cy + span)
            "down" -> intArrayOf(cx, cy + span, cx, cy - span)
            "left" -> intArrayOf(cx + span, cy, cx - span, cy)
            "right" -> intArrayOf(cx - span, cy, cx + span, cy)
            else -> throw Fault("invalidArguments")
        }
    injectSwipe(p[0], p[1], p[2], p[3], 400)
}

internal fun PrivilegedDisplayCore.globalKey(key: String?) {
    val code = KEYS[key] ?: throw Fault("invalidArguments")
    val now = SystemClock.uptimeMillis()
    injectKey(KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0))
    sleepChecked(4)
    injectKey(KeyEvent(SystemClock.uptimeMillis(), now, KeyEvent.ACTION_UP, code, 0))
}

internal fun PrivilegedDisplayCore.typeText(pkg: String, request: Bundle, response: Bundle) {
    val text = request.getString(Proto.TEXT).orEmpty()
    val index = request.getInt(Proto.ELEMENT, -1)
    if (index < 1 || text.isEmpty() || text.length > 500 || '\u0000' in text)
        throw Fault("invalidArguments")
    val vd = display ?: throw Fault("displayMissing")
    val conn = TreeConnection(context, pkg)
    try {
        val automation =
            try {
                conn.open(vd.display)
            } catch (_: Throwable) {
                throw Fault("textConnectionUnavailable")
            }
        val node =
            readTree(automation, vd.display.displayId, pkg, index).target
                ?: throw Fault("elementMissing")
        try {
            if (!node.refresh()) throw Fault("textTargetChanged")
            val current = toElement(node, bestText(node), Rect(), 0)
            val expectRole = request.getString(Proto.EXPECT_ROLE)
            val expectLabel = request.getString(Proto.EXPECT_LABEL)
            val expectViewId = request.getString(Proto.EXPECT_VIEW_ID)
            if (
                expectRole != null && expectRole != current.role ||
                    expectLabel != null && expectLabel != current.label ||
                    expectViewId != null && expectViewId != current.viewId
            )
                throw Fault("staleSnapshot")
            if (isSecret(node) && !request.getBoolean(Proto.ALLOW_SECRET, false))
                throw Fault("textRequiresManual")
            if (
                !node.isEditable ||
                    node.actionList.none { it.id == AccessibilityNodeInfo.ACTION_SET_TEXT }
            )
                throw Fault("textUnsupported")
            val submit = request.getBoolean(Proto.SUBMIT)
            if (submit) {
                if (!node.isFocused && !node.performAction(AccessibilityNodeInfo.ACTION_FOCUS))
                    throw Fault("textFocusMissing")
                if (!node.refresh() || !node.isFocused) throw Fault("textTargetChanged")
            }
            // type(id, text) replaces the entire value, regardless of the old selection.
            val accepted =
                node.performAction(
                    AccessibilityNodeInfo.ACTION_SET_TEXT,
                    Bundle().apply {
                        putCharSequence(
                            AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                            text,
                        )
                    },
                )
            response.putBoolean(Proto.ACCEPTED, accepted)
            if (!accepted) throw Fault("inputRejected")
            val deadline = SystemClock.uptimeMillis() + 1500
            while (true) {
                runCatching { invoke(automation, "clearCache") }
                if (!node.refresh()) throw Fault("textTargetChanged")
                val now = if (node.isShowingHintText) "" else node.text?.toString().orEmpty()
                if (now == text) break
                if (SystemClock.uptimeMillis() >= deadline) throw Fault("textReadbackMismatch")
                sleepChecked(50)
            }
            response.putString(Proto.READBACK, BackgroundTree.cap(text))
            if (submit) {
                if (!node.isFocused) throw Fault("textTargetChanged")
                globalKey("enter")
            }
        } finally {
            // AccessibilityNodeInfo.recycle() is a no-op since API 33; nodes are GC-managed.
        }
    } finally {
        conn.close()
    }
}

// ---- injection shims (hidden APIs via reflection) ----------------------

internal fun PrivilegedDisplayCore.injectTap(x: Int, y: Int, long: Boolean) {
    val down = SystemClock.uptimeMillis()
    sendMotion(MotionEvent.ACTION_DOWN, x, y, down)
    sleepChecked(if (long) 650 else 60)
    sendMotion(MotionEvent.ACTION_UP, x, y, down)
}

internal fun PrivilegedDisplayCore.injectSwipe(sx: Int, sy: Int, ex: Int, ey: Int, duration: Int) {
    val down = SystemClock.uptimeMillis()
    sendMotion(MotionEvent.ACTION_DOWN, sx, sy, down)
    val steps = (duration / 16).coerceAtLeast(1)
    for (step in 1..steps) {
        sleepChecked((duration / steps).toLong())
        val f = step.toFloat() / steps
        val mx = (sx + (ex - sx) * f).toInt()
        val my = (sy + (ey - sy) * f).toInt()
        if (step < steps) sendMotion(MotionEvent.ACTION_MOVE, mx, my, down)
    }
    sendMotion(MotionEvent.ACTION_UP, ex, ey, down)
}

internal fun PrivilegedDisplayCore.sendMotion(
    action: Int,
    x: Int,
    y: Int,
    downTime: Long,
    waitForFinish: Boolean = true,
) {
    val event =
        MotionEvent.obtain(
            downTime,
            SystemClock.uptimeMillis(),
            action,
            x.toFloat(),
            y.toFloat(),
            0,
        )
    event.source = InputDevice.SOURCE_TOUCHSCREEN
    try {
        inject(event, waitForFinish)
    } finally {
        event.recycle()
    }
}

internal fun PrivilegedDisplayCore.injectKey(src: KeyEvent) {
    val event =
        KeyEvent(
            src.downTime,
            src.eventTime,
            src.action,
            src.keyCode,
            0,
            src.metaState,
            KeyCharacterMap.VIRTUAL_KEYBOARD,
            src.scanCode,
            src.flags,
            InputDevice.SOURCE_KEYBOARD,
        )
    inject(event)
}

// Runs in the shell-uid privileged process (Shizuku UserService or app_process),
// which the app hidden-API policy that lint models doesn't apply to.
@SuppressLint("BlockedPrivateApi")
internal fun PrivilegedDisplayCore.inject(event: InputEvent, waitForFinish: Boolean = true) {
    val id = display?.display?.displayId ?: throw Fault("displayMissing")
    // WAIT_FOR_FINISH can time out after the event was delivered. A false result is
    // not proof of rejection: never replay the same event with another mode.
    val accepted =
        try {
            InputEvent::class
                .java
                .getDeclaredMethod("setDisplayId", Int::class.javaPrimitiveType)
                .apply { isAccessible = true }
                .invoke(event, id)
            // Android 17 removed InputManager.getInstance(); the Context service works on every
            // version.
            val manager = privileged.getSystemService(InputManager::class.java)
            InputManager::class
                .java
                .getMethod(
                    "injectInputEvent",
                    InputEvent::class.java,
                    Int::class.javaPrimitiveType,
                )
                .invoke(manager, event, if (waitForFinish) 2 else 0) == true
        } catch (e: InvocationTargetException) {
            throw e.targetException // the system's own answer, e.g. SecurityException
        } catch (_: ReflectiveOperationException) {
            throw Fault("inputApiUnavailable") // the hidden API is missing or blocked here
        }
    // Only the gate uses ASYNC: its window receipt, not this boolean, decides delivery.
    if (waitForFinish && !accepted) throw Fault("inputRejected")
}
