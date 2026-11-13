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
import android.app.ActivityManager
import android.app.ActivityOptions
import android.app.KeyguardManager
import android.app.PendingIntent
import android.app.UiAutomation
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Process
import android.os.SystemClock
import android.view.Display
import android.view.KeyEvent
import android.view.MotionEvent
import dev.fughetta.dshcompose.domain.phone.BackgroundPolicy
import java.io.ByteArrayOutputStream
import java.lang.reflect.InvocationTargetException
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Runs as shell uid (inside the Shizuku UserService, or the adb-launched app_process). Owns at most
 * one trusted virtual display at a time (one per task) and all the privileged primitives against
 * it. Thread-safe via a single lock; every op has its own deadline. Never throws across the call
 * boundary: faults become a stable [Proto.ERROR] code.
 */
class PrivilegedDisplayCore(internal val context: Context) {

    internal val privileged: Context by lazy {
        if (Process.myUid() == Process.SHELL_UID)
            context.createPackageContext("com.android.shell", Context.CONTEXT_IGNORE_SECURITY)
        else context
    }
    internal val displays by lazy { privileged.getSystemService(DisplayManager::class.java) }
    private val activities by lazy { privileged.getSystemService(ActivityManager::class.java) }
    private val keyguard by lazy { privileged.getSystemService(KeyguardManager::class.java) }
    private val taskDisplayIdField by lazy {
        runCatching { ActivityManager.RunningTaskInfo::class.java.getField("displayId") }
            .getOrNull()
    }

    private val lock = Any()
    @Volatile private var owner: String? = null
    @Volatile internal var display: VirtualDisplay? = null
    @Volatile private var reader: ImageReader? = null
    internal var width = 0
    internal var height = 0
    private val released = AtomicBoolean(false)

    internal class Fault(val code: String) : Exception()

    /** The single entry point for both transports. Always returns a response Bundle. */
    fun handle(request: Bundle): Bundle {
        val response = Bundle()
        val op = request.getString(Proto.OP)
        try {
            if (op == Proto.OP_PROBE) {
                response.putBoolean(Proto.OK, true)
                response.putInt(Proto.UID, Process.myUid())
                response.putString("revision", Proto.REVISION)
                return response
            }
            if (op == Proto.OP_DESTROY) {
                releaseAll()
                response.putBoolean(Proto.OK, true)
                return response
            }
            if (op == Proto.OP_GATE) {
                synchronized(lock) { gateProbe(response) }
                if (!response.containsKey(Proto.ERROR)) response.putBoolean(Proto.OK, true)
                return response
            }
            if (op == Proto.OP_GRANT_SECURE_SETTINGS) {
                grantSecureSettings(request.getInt(Proto.USER))
                response.putBoolean(Proto.OK, true)
                return response
            }
            synchronized(lock) { dispatch(op, request, response) }
            if (!response.containsKey(Proto.ERROR)) response.putBoolean(Proto.OK, true)
        } catch (e: Throwable) {
            response.putBoolean(Proto.OK, false)
            response.putString(Proto.ERROR, failureCode(e))
            failureDetail(e)?.let { response.putString(Proto.ERROR_DETAIL, it) }
        }
        return response
    }

    private fun failureCode(e: Throwable): String =
        when (e) {
            is Fault -> e.code
            is InputProbe.Failure -> e.code
            is InterruptedException -> "cancelled"
            is SecurityException -> "systemOperationDenied"
            else -> "deviceUnavailable"
        }

    private fun failureDetail(e: Throwable): String? =
        if (e is InputProbe.Failure) e.observation?.encode()
        else if (e is Fault || e is InterruptedException || e is SecurityException) null
        else e.javaClass.simpleName

    private fun dispatch(op: String?, request: Bundle, response: Bundle) {
        val ownerId = request.getString(Proto.OWNER).orEmpty()
        if (op == null || ownerId.isEmpty()) throw Fault("invalidArguments")
        if (op == Proto.OP_RELEASE) {
            if (owner == ownerId) releaseAll()
            response.putBoolean("released", true)
            return
        }
        if (owner != null && owner != ownerId) throw Fault("identityChanged")
        if (keyguard?.isKeyguardLocked == true) throw Fault("locked")
        val pkg = request.getString(Proto.PKG).orEmpty()
        if (!PACKAGE.matches(pkg)) throw Fault("invalidArguments")

        when (op) {
            Proto.OP_CREATE,
            Proto.OP_LAUNCH -> {
                owner = ownerId
                if (display == null) createDisplay(request)
                response.putInt(Proto.DISPLAY_ID, displayId())
                launchPackage(pkg)
                sleepChecked(300)
                observe(pkg, request.getBoolean(Proto.WANT_IMAGE), response)
            }
            Proto.OP_OBSERVE -> {
                requireDisplay()
                requireForeground(pkg)
                observe(pkg, request.getBoolean(Proto.WANT_IMAGE), response, requireTree = true)
            }
            Proto.OP_TAP -> {
                requireDisplay()
                requireForeground(pkg)
                tap(pkg, request)
                sleepChecked(250)
                observe(pkg, false, response)
            }
            Proto.OP_SCROLL -> {
                requireDisplay()
                requireForeground(pkg)
                scroll(request)
                sleepChecked(250)
                observe(pkg, false, response)
            }
            Proto.OP_KEY -> {
                requireDisplay()
                requireForeground(pkg)
                globalKey(request.getString(Proto.DIR))
                sleepChecked(250)
                observe(pkg, false, response)
            }
            Proto.OP_TEXT -> {
                requireDisplay()
                requireForeground(pkg)
                typeText(pkg, request, response)
                sleepChecked(200)
                observe(pkg, false, response)
            }
            else -> throw Fault("invalidArguments")
        }
    }

    /** Test a real input receiver, not an empty display. Never launches or touches a user app. */
    private fun gateProbe(response: Bundle) {
        // A settings probe must not replace or release an active task's display.
        if (owner != null || display != null) throw Fault("probeBusy")
        try {
            owner = "__gate__"
            createDisplay(
                Bundle().apply {
                    putInt(Proto.SHORT_SIDE, 360)
                    putInt(Proto.ASPECT_W, 9)
                    putInt(Proto.ASPECT_H, 20)
                },
                probe = true,
            )
            response.putInt(Proto.DISPLAY_ID, displayId())
            var probeDetail: String? = null
            val injection = runCatching {
                val window = BackgroundInputProbe(privileged, checkNotNull(display).display)
                try {
                    window.verify { target, time ->
                        sendMotion(
                            MotionEvent.ACTION_DOWN,
                            target.x,
                            target.y,
                            time,
                            waitForFinish = false,
                        )
                        sendMotion(
                            MotionEvent.ACTION_UP,
                            target.x,
                            target.y,
                            time,
                            waitForFinish = false,
                        )
                    }
                } finally {
                    // Cleanup failure must replace a delivery failure, not be hidden by use().
                    try {
                        window.close()
                    } finally {
                        probeDetail = window.failureDetail
                    }
                }
            }
            response.putBoolean(Proto.INJECT_OK, injection.isSuccess)
            injection.exceptionOrNull()?.let {
                response.putString(Proto.INJECT_ERROR, failureCode(it))
                (probeDetail ?: failureDetail(it))?.let { detail ->
                    response.putString(Proto.ERROR_DETAIL, detail)
                }
            }
        } finally {
            releaseAll()
        }
    }

    // ---- display lifecycle -------------------------------------------------

    internal fun displayId(): Int = display?.display?.displayId ?: throw Fault("displayMissing")

    private fun requireDisplay() {
        if (display == null) throw Fault("displayMissing")
    }

    private fun createDisplay(request: Bundle, probe: Boolean = false) {
        val shortSide = request.getInt(Proto.SHORT_SIDE, 720).coerceIn(360, 1080)
        val aspectW = request.getInt(Proto.ASPECT_W, 9).coerceAtLeast(1)
        val aspectH = request.getInt(Proto.ASPECT_H, 20).coerceAtLeast(1)
        val size = BackgroundPolicy.displaySize(shortSide, aspectW, aspectH)
        val w = size[0]
        val h = size[1]
        val ir = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        try {
            // Every flag must resolve; never silently make a focus-stealing display on an old ROM.
            // SECURE is deliberately not requested, so protected content stays protected.
            val flags =
                try {
                    FLAG_NAMES.fold(0) { bits, name ->
                        bits or
                            DisplayManager::class
                                .java
                                .getDeclaredField("VIRTUAL_DISPLAY_FLAG_$name")
                                .getInt(null)
                    }
                } catch (_: ReflectiveOperationException) {
                    throw Fault("displayUnsupported")
                }
            // Presentation requires this flag for the gate's inert receiver. Actual tasks keep
            // their existing display flags; this tests delivery, not every target app's policy.
            val vd =
                displays.createVirtualDisplay(
                    "dsh-background",
                    w,
                    h,
                    320,
                    ir.surface,
                    flags or if (probe) DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION else 0,
                )
            if (vd.display.displayId <= Display.DEFAULT_DISPLAY) {
                vd.release()
                throw Fault("displayUnsupported")
            }
            width = w
            height = h
            reader = ir
            display = vd
            // Keep the virtual display's keyboard off the main screen: IME policy HIDE.
            runCatching { setImePolicyHide(vd.display.displayId) }
        } catch (e: Throwable) {
            ir.close()
            if (e is Fault) throw e else throw Fault("displayUnsupported")
        }
    }

    private fun launchPackage(pkg: String) {
        val display = this.display ?: throw Fault("displayMissing")
        val intent =
            privileged.packageManager.getLaunchIntentForPackage(pkg) ?: throw Fault("notLaunchable")
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
        val options =
            ActivityOptions.makeBasic().apply { launchDisplayId = display.display.displayId }
        // A shell/UserService is not an ActivityManager-attached app process: a PendingIntent
        // avoids Context.startActivity's caller check (Phase, after Aether).
        var pending: PendingIntent? = null
        try {
            pending =
                PendingIntent.getActivity(
                    privileged.createDisplayContext(display.display),
                    display.display.displayId,
                    intent,
                    PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE,
                )
            pending.send(privileged, 0, null, null, null, null, options.toBundle())
        } catch (_: SecurityException) {
            throw Fault("launchDenied")
        } catch (_: PendingIntent.CanceledException) {
            throw Fault("launchCancelled")
        } finally {
            pending?.let { runCatching { it.cancel() } }
        }
        // Poll for the target becoming foreground on the owned display.
        val deadline = SystemClock.uptimeMillis() + 4000
        while (true) {
            if (foregroundPackage() == pkg) break
            if (SystemClock.uptimeMillis() >= deadline) throw Fault("displayTargetChanged")
            sleepChecked(120)
        }
    }

    private fun requireForeground(pkg: String) {
        if (foregroundPackage() != pkg) throw Fault("displayTargetChanged")
    }

    private fun foregroundPackage(): String? {
        val id = display?.display?.displayId ?: return null
        val field = taskDisplayIdField ?: return null
        @Suppress("DEPRECATION")
        val task =
            activities.getRunningTasks(50).firstOrNull {
                runCatching { field.getInt(it) }.getOrDefault(-1) == id
            }
        return task?.topActivity?.packageName
    }

    private fun releaseAll() =
        synchronized(lock) {
            owner = null
            var failed = false
            try {
                display?.release()
            } catch (_: Throwable) {
                failed = true
            } finally {
                display = null
            }
            try {
                reader?.close()
            } catch (_: Throwable) {
                failed = true
            } finally {
                reader = null
            }
            if (failed) {
                /* best effort */
            }
        }

    // ---- observation (tree + optional screenshot) --------------------------

    private fun observe(
        pkg: String,
        wantImage: Boolean,
        response: Bundle,
        requireTree: Boolean = false,
    ) {
        val id = displayId()
        response.putInt(Proto.DISPLAY_ID, id)
        response.putString(Proto.FG_PKG, pkg)
        response.putBoolean(Proto.PORTRAIT, height >= width)
        val captureResult = runCatching { capture() }
        val captureFailure = captureResult.exceptionOrNull()
        if (
            captureFailure is InterruptedException ||
                captureFailure is Fault && captureFailure.code == "cancelled"
        )
            throw captureFailure
        // Other capture faults retain the existing tree-only observation path.
        val shot = captureResult.getOrNull()
        val label = runCatching { appLabel(pkg) }.getOrNull()
        label?.let { response.putString(Proto.APP_LABEL, it) }
        val treeResult = runCatching { readTree(id, pkg) }
        val failure = treeResult.exceptionOrNull()
        if (failure is InterruptedException || failure is Fault && failure.code == "cancelled")
            throw failure
        // Pixels alone never identify FLAG_SECURE. Keep an explicit secure failure authoritative.
        val secure = failure is Fault && failure.code == "secureWindow"
        response.putBoolean(Proto.SECURE, secure)
        if (secure) {
            response.putBoolean(Proto.POOR, false)
            response.putInt(Proto.COUNT, 0)
            return
        }
        // A read must report its failure; observation after an already-dispatched write
        // must not turn that write into a failed action that a caller might replay.
        val tree = if (requireTree) treeResult.getOrThrow() else treeResult.getOrNull()
        if (captureResult.isSuccess && shot == null) {
            if (requireTree) throw Fault("frameNotReady")
            // The action already happened. Never make the caller replay it for an absent frame.
            response.putBoolean(Proto.FRAME_NOT_READY, true)
            response.putBoolean(Proto.POOR, true)
            response.putInt(Proto.COUNT, 0)
            return
        }
        // The screenshot contains the whole viewport, including nodes beyond the 120-line
        // text limit. Never attach it when secrets were found or the scan was incomplete.
        // The typing unlock changes permission to write, never permission to reveal a secret.
        if (wantImage && shot != null) {
            if (tree != null && tree.complete && !tree.hasSecrets) {
                response.putByteArray("imageBytes", shot.jpeg)
                response.putString("imageMime", "image/jpeg")
            } else {
                response.putBoolean(Proto.IMAGE_OMITTED, true)
            }
        }
        emitElements(tree?.elements.orEmpty(), response)
    }

    private fun emitElements(elements: List<BgNode>, response: Bundle) {
        val capped = elements.take(120)
        val n = capped.size
        response.putInt(Proto.COUNT, n)
        response.putBoolean(Proto.POOR, elements.count { it.label.isNotEmpty() } < 5)
        val role = arrayOfNulls<String>(n)
        val label = arrayOfNulls<String>(n)
        val viewIds = arrayOfNulls<String>(n)
        val states = arrayOfNulls<String>(n)
        val flags = IntArray(n)
        val region = IntArray(n)
        val indent = IntArray(n)
        for (i in 0 until n) {
            val e = capped[i]
            role[i] = e.role
            label[i] = e.label
            states[i] = e.states.joinToString("\u0001")
            viewIds[i] = e.viewId
            flags[i] = e.flags
            region[i] = e.region
            indent[i] = e.indent
        }
        response.putStringArray(Proto.A_ROLE, role)
        response.putStringArray(Proto.A_LABEL, label)
        response.putStringArray(Proto.A_VIEW_ID, viewIds)
        response.putStringArray(Proto.A_STATES, states)
        response.putIntArray(Proto.A_FLAGS, flags)
        response.putIntArray(Proto.A_REGION, region)
        response.putIntArray(Proto.A_INDENT, indent)
    }

    private fun appLabel(pkg: String): String? {
        val pm = privileged.packageManager
        return runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }
            .getOrNull()
    }

    private class CaptureResult(val jpeg: ByteArray)

    /** Null means only absent/black frames; other capture failures remain distinguishable. */
    private fun capture(): CaptureResult? {
        val ir = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        try {
            synchronized(lock) {
                val vd = display ?: throw Fault("displayMissing")
                val previous = reader
                vd.surface = ir.surface
                reader = ir
                previous?.close()
            }
            val deadline = SystemClock.uptimeMillis() + BackgroundFrameReadiness.CAPTURE_TIMEOUT_MS
            for (attempt in 0..1) {
                if (released.get() || Thread.currentThread().isInterrupted) throw Fault("cancelled")
                if (attempt > 0 && SystemClock.uptimeMillis() >= deadline) return null
                var image = ir.acquireLatestImage()
                while (image == null) {
                    val remaining = deadline - SystemClock.uptimeMillis()
                    if (remaining <= 0) return null
                    sleepChecked(minOf(40L, remaining))
                    image = ir.acquireLatestImage()
                }
                try {
                    val plane = image.planes.singleOrNull() ?: throw Fault("screenshotFailed")
                    if (plane.pixelStride != 4 || plane.rowStride < width * 4)
                        throw Fault("screenshotFailed")
                    val pixels = ByteBuffer.allocate(width * height * 4)
                    val src = plane.buffer.duplicate()
                    for (row in 0 until height) {
                        val start = row * plane.rowStride
                        src.limit(start + width * 4)
                        src.position(start)
                        pixels.put(src)
                    }
                    pixels.flip()
                    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    try {
                        bitmap.copyPixelsFromBuffer(pixels)
                        val remaining = deadline - SystemClock.uptimeMillis()
                        when (
                            BackgroundFrameReadiness.next(isMostlyBlack(bitmap), attempt, remaining)
                        ) {
                            BackgroundFrameReadiness.Next.NOT_READY -> return null
                            BackgroundFrameReadiness.Next.RETRY -> Unit
                            BackgroundFrameReadiness.Next.READY -> {
                                val buffer = ByteArrayOutputStream()
                                bitmap.compress(Bitmap.CompressFormat.JPEG, 80, buffer)
                                return CaptureResult(buffer.toByteArray())
                            }
                        }
                    } finally {
                        bitmap.recycle()
                    }
                } finally {
                    image.close()
                }
                // Release the first Image before taking one more from this same reader/surface.
                sleepChecked(
                    BackgroundFrameReadiness.retryWaitMs(deadline - SystemClock.uptimeMillis()),
                )
            }
            return null
        } finally {
            /* reader kept for next frame; closed on release */
        }
    }

    private fun isMostlyBlack(bitmap: Bitmap): Boolean {
        val stepX = (bitmap.width / 24).coerceAtLeast(1)
        val stepY = (bitmap.height / 48).coerceAtLeast(1)
        var dark = 0
        var total = 0
        var y = 0
        while (y < bitmap.height) {
            var x = 0
            while (x < bitmap.width) {
                val c = bitmap.getPixel(x, y)
                val luma =
                    ((c shr 16 and 0xff) * 30 + (c shr 8 and 0xff) * 59 + (c and 0xff) * 11) / 100
                if (luma < 12) dark++
                total++
                x += stepX
            }
            y += stepY
        }
        return total > 0 && dark * 100 / total >= 98
    }

    // ---- UiAutomation control-tree read ------------------------------------

    private fun setImePolicyHide(displayId: Int) {
        val sm = Class.forName("android.os.ServiceManager")
        val binder =
            sm.getMethod("getService", String::class.java).invoke(null, "window") as IBinder
        val wm =
            Class.forName("android.view.IWindowManager\$Stub")
                .getMethod("asInterface", IBinder::class.java)
                .invoke(null, binder)
        // DISPLAY_IME_POLICY_HIDE == 2
        wm.javaClass
            .getMethod(
                "setDisplayImePolicy",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
            )
            .invoke(wm, displayId, 2)
    }

    /**
     * `pm grant` of WRITE_SECURE_SETTINGS to this app, through the binder `pm` itself uses:
     * IPackageManager up to Android 10, IPermissionManager from 11 (Android 15 added a device id
     * before the user). Fixed permission, fixed package.
     */
    @SuppressLint("PrivateApi")
    private fun grantSecureSettings(userId: Int) {
        val (service, type) =
            if (Build.VERSION.SDK_INT >= 30)
                "permissionmgr" to "android.permission.IPermissionManager"
            else "package" to "android.content.pm.IPackageManager"
        val binder =
            Class.forName("android.os.ServiceManager")
                .getMethod("getService", String::class.java)
                .invoke(null, service) as IBinder
        val manager =
            Class.forName("$type\$Stub")
                .getMethod("asInterface", IBinder::class.java)
                .invoke(null, binder)
        val grant = manager.javaClass.methods.first { it.name == "grantRuntimePermission" }
        val permission = SecureSettingsGrant.PERMISSION
        val args =
            when {
                grant.parameterTypes.size == 3 ->
                    arrayOf<Any>(Proto.APP_PACKAGE, permission, userId)
                // VirtualDeviceManager.PERSISTENT_DEVICE_ID_DEFAULT, or Context.DEVICE_ID_DEFAULT.
                grant.parameterTypes[2] == String::class.java ->
                    arrayOf<Any>(Proto.APP_PACKAGE, permission, "default:0", userId)
                else -> arrayOf<Any>(Proto.APP_PACKAGE, permission, 0, userId)
            }
        try {
            grant.invoke(manager, *args)
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }
    }

    internal fun invoke(target: Any, method: String): Any? =
        UiAutomation::class.java.getMethod(method).invoke(target)

    internal fun sleepChecked(ms: Long) {
        if (ms <= 0) return
        if (released.get()) throw Fault("cancelled")
        try {
            Thread.sleep(ms)
        } catch (_: InterruptedException) {
            throw Fault("cancelled")
        }
    }

    /**
     * A short-lived, node-only UiAutomation bound to the owned display (after Phase's
     * DisplayTextConnection): FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES, so it does not evict the
     * user's other services; a hand-rolled IUiAutomationConnection so disconnect never restores the
     * main screen's rotation; only the node connection is exposed (no shell/input/permission).
     */
    companion object {
        private val PACKAGE = Regex("[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z0-9_]+)+")
        private val FLAG_NAMES =
            listOf(
                "PUBLIC",
                "OWN_CONTENT_ONLY",
                "SUPPORTS_TOUCH",
                "DESTROY_CONTENT_ON_REMOVAL",
                "TRUSTED",
                "OWN_FOCUS",
                "STEAL_TOP_FOCUS_DISABLED",
            )
        internal val KEYS =
            mapOf(
                "back" to KeyEvent.KEYCODE_BACK,
                "home" to KeyEvent.KEYCODE_HOME,
                "enter" to KeyEvent.KEYCODE_ENTER,
                "delete" to KeyEvent.KEYCODE_DEL,
                "tab" to KeyEvent.KEYCODE_TAB,
                "escape" to KeyEvent.KEYCODE_ESCAPE,
                "up" to KeyEvent.KEYCODE_DPAD_UP,
                "down" to KeyEvent.KEYCODE_DPAD_DOWN,
                "left" to KeyEvent.KEYCODE_DPAD_LEFT,
                "right" to KeyEvent.KEYCODE_DPAD_RIGHT,
            )
    }
}

/** Live-tree node captured during a read; carries what the driver needs to build a TargetInfo. */
class BgNode(
    val role: String,
    val label: String,
    val states: List<String>,
    val flags: Int,
    val indent: Int,
    val bounds: android.graphics.Rect,
    val packageName: String?,
    val viewId: String? = null,
) {
    var region: Int = 1
}
