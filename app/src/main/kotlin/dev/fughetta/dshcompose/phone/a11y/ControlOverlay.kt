// Portions derived from Phase (https://github.com/CikeSeven/phase), used with the author's
// permission. See THIRD_PARTY_NOTICES.md.
// Local changes: a status control at the top in three forms (ball, capsule, card) on one morphing
// surface; separate confirm and handoff cards; buttons that refuse injected or obscured touches;
// pass-through and hide hooks for the Actuator and screenshots; blocking UserGate calls.

package dev.fughetta.dshcompose.phone.a11y

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.TextView
import dev.fughetta.dshcompose.R
import dev.fughetta.dshcompose.domain.phone.ConfirmRequest
import dev.fughetta.dshcompose.domain.phone.Decision
import dev.fughetta.dshcompose.domain.phone.HandoffCause
import dev.fughetta.dshcompose.domain.phone.HandoffResult
import dev.fughetta.dshcompose.domain.phone.PhoneUnlocks
import dev.fughetta.dshcompose.i18n.AppStrings
import dev.fughetta.dshcompose.phone.PhonePromptUi
import dev.fughetta.dshcompose.phone.PhoneRegistry
import dev.fughetta.dshcompose.phone.PromptPresentation
import dev.fughetta.dshcompose.phone.UserGate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * The main-screen [UserGate]: TYPE_ACCESSIBILITY_OVERLAY windows owned by the service (no
 * SYSTEM_ALERT_WINDOW). Blocking calls come from the bridge worker; all views live on the main
 * thread.
 *
 * While a task runs the person sees one control at the top, in three forms: the capsule with the
 * line and 暂停 / 停止 ([OverlayCapsule]), the card it grows into, and the ball it folds into
 * ([OverlayBall]). [FoldClock] decides the form; this class plays the morphs between them and keeps
 * 停止 one step away in every form.
 */
internal class ControlOverlay(internal val service: AccessibilityService) {
    val gate =
        UserGate(::confirm, ::handoff, ::status, cleanup = ::close, showPrompt = ::showPrompt)

    internal val main = Handler(Looper.getMainLooper())
    internal val wm = service.getSystemService(WindowManager::class.java)
    internal val a11y = service.getSystemService(AccessibilityManager::class.java)
    internal val ime = service.getSystemService(InputMethodManager::class.java)

    /** 暂停 is on: the driver refuses actions until 继续. */
    @Volatile
    var paused = false
        internal set

    /** The person is typing into 插话: actions wait so the keyboard isn't fought over. */
    @Volatile
    var editing = false
        internal set

    /**
     * Debug probe only: draw in the dark (true) or light (false) palette whatever the system says.
     */
    @Volatile var debugDark: Boolean? = null

    /** Debug probe only: show these lifted limits instead of the person's. */
    @Volatile var debugUnlocks: PhoneUnlocks? = null

    /** Debug probe only: the task's app, as the core would have set it. */
    @Volatile var debugApp: String? = null

    /** Debug probe only: no timer changes the form (screenshots of the card). */
    @Volatile var debugFreeze = false

    /** Debug probe only: let an injected tap reach a guarded button to observe the other checks. */
    @Volatile var debugIgnoreInjectGuard = false

    /** Last guarded-button verdict, for the debug probe. */
    @Volatile
    var lastGuardVerdict: String? = null
        private set

    // ---- main-thread state ----
    private var statusText: String? = null
    internal var line: StatusLine? = null
    internal val steps = StepHistory()
    internal var stoppedNote = false

    /** 「已发给 DSH：…」 for a moment after 插话, in the line's place. */
    internal var steerNote: String? = null
    internal var pill: OverlayCapsule? = null
    internal var confirmCard: Card? = null
    internal var handoffCard: Card? = null
    internal var pendingConfirm: Pending<Decision>? = null
    internal var pendingHandoff: Pending<HandoffResult>? = null
    @Volatile internal var sharedPrompt: PromptPresentation? = null
    internal var passThrough = false
    private val captureVisibility = CaptureVisibility()
    internal val captureHidden: Boolean
        get() = captureVisibility.hidden

    internal var darkBuilt: Boolean? = null

    // The form: FoldClock decides, the morphs follow.
    internal val fold = FoldClock()
    internal var ball: OverlayBall? = null

    /**
     * What is on screen: the capsule (or card), or the ball (lags [fold] while a fold or unfold
     * plays).
     */
    internal var ballShown = false
    internal val foldTick = Runnable { onFoldTick() }

    /** A narrower line may shrink the capsule now. */
    internal val shrinkTick = Runnable { renderPill() }

    // 闲置时不透明度 (the ball's) and 「闲置时自动收起」 (设置 → 手机操控 → 悬浮条), live while showing.
    internal val overlayPrefs = OverlayPrefs.prefs(service)
    internal var idleOpacity = OverlayPrefs.from(overlayPrefs).idleOpacity
    private val preferenceScope =
        kotlinx.coroutines.CoroutineScope(
            kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main.immediate,
        )

    init {
        fold.setAutoFold(OverlayPrefs.from(overlayPrefs).autoFold, SystemClock.uptimeMillis())
        preferenceScope.launch { overlayPrefs.data.collect { onPrefsChanged() } }
    }

    internal class Pending<T : Any> {
        private val latch = CountDownLatch(1)
        @Volatile
        var value: T? = null
            private set

        fun resolve(v: T): Boolean {
            synchronized(this) {
                if (value != null) return false
                value = v
            }
            latch.countDown()
            return true
        }

        fun await(ms: Long): T? {
            latch.await(ms, TimeUnit.MILLISECONDS)
            return value
        }
    }

    internal class Card(
        val root: FrameLayout,
        val params: WindowManager.LayoutParams,
        val tick: Runnable?,
    )

    // ---- UserGate
    // --------------------------------------------------------------------------------

    fun status(text: String?) {
        main.post {
            statusText = text
            setStopped(false)
            if (text == null) {
                paused = false
                line = null
                steps.clear()
                removePill(animated = true)
            } else {
                // The core's formats in the app language: the prefixes it puts on, taken off here.
                val l =
                    StatusLines(
                            AppStrings.get(service, R.string.phone_waived_status),
                            AppStrings.get(service, R.string.phone_acting),
                        )
                        .parse(text)
                line = l
                steps.add(l)
            }
        }
    }

    /** The turn that drove the phone is over: every window goes, pending questions answer STOP. */
    fun close(ifCurrent: (() -> Unit) -> Unit) {
        val shared = sharedPrompt
        val confirm = pendingConfirm
        val handoff = pendingHandoff
        main.post {
            ifCurrent {
                // A new pre-tool engine prompt can share an inactive task id. Remove only the
                // questions captured by this cleanup, even when the task itself has not started.
                if (sharedPrompt === shared) {
                    shared?.ticket?.stop()
                    if (shared != null) {
                        sharedPrompt = null
                        dismissConfirm()
                    }
                }
                if (sharedPrompt == null && pendingConfirm === confirm) {
                    confirm?.resolve(Decision.STOP)
                    dismissConfirm()
                }
                if (pendingHandoff === handoff) {
                    handoff?.resolve(HandoffResult.STOP)
                    dismissHandoff()
                }
                statusText = null
                line = null
                steps.clear()
                setStopped(false)
                paused = false
                removePill(animated = true)
            }
        }
    }

    fun confirm(request: ConfirmRequest): Decision {
        PhoneRegistry.promptGate?.let {
            return it.confirm(request)
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            Log.w(TAG, "confirm() on the main thread: denied")
            return Decision.DENY
        }
        val pending = Pending<Decision>()
        val shown = onMain(1_500) { showConfirm(request, pending) } == true
        if (!shown) {
            pending.resolve(Decision.UNAVAILABLE)
            main.post { if (pendingConfirm === pending) dismissConfirm() }
            Log.w(TAG, "confirm overlay could not be shown; falling back to the notification gate")
            return PhoneRegistry.notificationGate?.confirm(request) ?: Decision.DENY
        }
        val decision =
            try {
                pending.await(request.timeoutMs + 1_500)
                    ?: run {
                        pending.resolve(Decision.DENY)
                        pending.value!!
                    }
            } catch (_: InterruptedException) {
                // The core's stop interrupts the worker: answer STOP and keep the flag for the
                // caller.
                pending.resolve(Decision.STOP)
                Thread.currentThread().interrupt()
                pending.value!!
            }
        main.post { if (pendingConfirm === pending) dismissConfirm() }
        return decision
    }

    fun handoff(reason: String, timeoutMs: Long): HandoffResult = handoff(reason, timeoutMs, null)

    fun handoff(reason: String, timeoutMs: Long, cause: HandoffCause?): HandoffResult {
        PhoneRegistry.promptGate?.let {
            return it.handoff(reason, timeoutMs, cause)
        }
        if (Looper.myLooper() == Looper.getMainLooper()) return HandoffResult.STOP
        val limit = timeoutMs.coerceIn(5_000, 10 * 60_000)
        val pending = Pending<HandoffResult>()
        val shown = onMain(1_500) { showHandoff(reason, limit, pending, cause) } == true
        if (!shown) {
            pending.resolve(HandoffResult.STOP)
            main.post { if (pendingHandoff === pending) dismissHandoff() }
            return PhoneRegistry.notificationGate?.handoff(reason, limit, cause)
                ?: HandoffResult.STOP
        }
        val result =
            try {
                pending.await(limit + 1_500)
                    ?: run {
                        pending.resolve(HandoffResult.TIMEOUT)
                        pending.value!!
                    }
            } catch (_: InterruptedException) {
                pending.resolve(HandoffResult.STOP)
                Thread.currentThread().interrupt()
                pending.value!!
            }
        main.post { if (pendingHandoff === pending) dismissHandoff() }
        return result
    }

    // ---- hooks for the driver
    // ---------------------------------------------------------------------

    /**
     * Makes every overlay window untouchable (true) so an injected stroke reaches the app below.
     */
    fun setPassThrough(on: Boolean) {
        // Cleanup must survive a busy main thread or an interrupted gesture worker.
        // Gesture calls are serialized; this stays ahead of the next queued enable.
        if (!on) {
            main.post { applyPassThrough(false) }
            return
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            applyPassThrough(on)
            return
        }
        val changed = onMain(500) { applyPassThrough(on) } == true
        if (changed) SystemClock.sleep(80) // input windows update a frame after the layout
    }

    /** Paired hides for full-display screenshots; captures may overlap. */
    fun hideForCapture(hide: Boolean) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            // The main-thread caller schedules its own delay before it shoots.
            applyCaptureHidden(hide)
            return
        }
        // Both halves run on the main queue in the order they were asked for and are never
        // cancelled: a release can't overtake its own hide, and recovery still runs if the
        // screenshot worker times out or is interrupted.
        val applied = CountDownLatch(1)
        var settle = false
        main.post {
            try {
                settle = applyCaptureHidden(hide)
            } catch (e: Exception) {
                Log.w(TAG, "overlay main-thread call failed", e)
            } finally {
                applied.countDown()
            }
        }
        // Blocking agent captures wait for the windows to go; a busy main thread costs only the
        // wait.
        if (hide && applied.await(500, TimeUnit.MILLISECONDS) && settle) SystemClock.sleep(120)
    }

    private fun applyCaptureHidden(hide: Boolean): Boolean {
        if (captureVisibility.update(hide)) {
            val v = if (captureHidden) View.INVISIBLE else View.VISIBLE
            syncWindows()
            confirmCard?.root?.visibility = v
            handoffCard?.root?.visibility = v
        }
        return hide && (pill != null || confirmCard != null || handoffCard != null)
    }

    /** Screen off, lock, 停止 elsewhere, service going away: answer everything pending with STOP. */
    fun stopAll() {
        val shared = sharedPrompt
        val confirm = pendingConfirm
        val handoff = pendingHandoff
        shared?.ticket?.stop()
        main.post {
            if (sharedPrompt === shared) {
                if (shared != null) {
                    sharedPrompt = null
                    dismissConfirm()
                }
            }
            if (sharedPrompt == null && pendingConfirm === confirm) {
                confirm?.resolve(Decision.STOP)
                dismissConfirm()
            }
            if (pendingHandoff === handoff) {
                handoff?.resolve(HandoffResult.STOP)
                dismissHandoff()
            }
            paused = false
            endEditing()
            if (statusText != null) {
                setStopped(true)
                renderPill()
                clearStoppedLater()
            }
        }
    }

    /**
     * "已停止" stays briefly; if the core doesn't clear or replace the status, the control (and its
     * KEEP_SCREEN_ON) goes.
     */
    internal fun clearStoppedLater() {
        main.postDelayed(
            {
                if (stoppedNote) {
                    statusText = null
                    line = null
                    steps.clear()
                    setStopped(false)
                    paused = false
                    removePill(animated = true)
                }
            },
            4_000,
        )
    }

    /** The 已停止 note: holds the capsule (or the ball) as it is, the card folding back first. */
    internal fun setStopped(on: Boolean) {
        stoppedNote = on
        if (on) holdFold(FoldClock.Hold.STOPPED) else releaseFold(FoldClock.Hold.STOPPED)
    }

    /** Debug probe: screen centre of the visible button labelled [label], if any. */
    fun debugButtonCenter(label: String): Pair<Int, Int>? =
        onMain(1_000) {
            fun find(v: View): View? {
                if (
                    (v is TextView && v.text?.toString() == label) ||
                        v.contentDescription?.toString() == label
                )
                    return v
                if (v is android.view.ViewGroup)
                    for (i in 0 until v.childCount) find(v.getChildAt(i))?.let {
                        return it
                    }
                return null
            }
            val roots =
                listOfNotNull(
                    confirmCard?.root,
                    handoffCard?.root,
                    pill?.root?.takeIf { it.isShown },
                    ball?.root?.takeIf { it.isShown },
                )
            roots
                .firstNotNullOfOrNull { find(it) }
                ?.let { v ->
                    val loc = IntArray(2)
                    v.getLocationOnScreen(loc)
                    (loc[0] + v.width / 2) to (loc[1] + v.height / 2)
                }
        }

    /** Debug probe: puts the control in [form] (ball, capsule, card) as the person's taps would. */
    fun debugForm(form: String) {
        main.post {
            when (form) {
                "ball" -> if (!fold.folded) collapseByHand()
                "capsule" -> if (fold.folded) onBallTapped() else if (fold.expanded) onLineTapped()
                "card" ->
                    if (fold.folded) {
                        onBallTapped()
                        main.postDelayed({ onLineTapped() }, 700)
                    } else if (!fold.expanded) onLineTapped()
            }
        }
    }

    /** Debug probe: 暂停 / 继续 / 停止 / the steer field, as the keys do. */
    fun debugKey(key: String) {
        main.post {
            when (key) {
                "pause" -> togglePause()
                "stop" -> stopFromPill()
                "steer" -> startSteer()
                "done" -> endEditing()
            }
        }
    }

    /** Debug probe: what the control is showing. */
    fun debugState(): String =
        onMain(1_000) {
            "form=${fold.form} ballShown=$ballShown shape=${pill?.shape} paused=$paused stopped=$stoppedNote line=${line?.text} " +
                "window=${pill?.params?.let { "${it.width}x${it.height}@${it.y}" }} ball=${ball?.let { "${it.pos}" }} steps=${steps.before(line, 3)}"
        } ?: "timeout"

    fun onConfigurationChanged() {
        main.post {
            val dark = isDark()
            if (darkBuilt != null && darkBuilt != dark) {
                // Rebuild in the new palette; pending questions keep their latches.
                pill?.let { removePill(animated = false) }
            } else {
                pill?.let { p ->
                    placeCapsule(p)
                    ball?.let { b -> if (ballShown) b.move(b.dockPos()) }
                }
                confirmCard?.let { place(it.root, it.params, top = false) }
                handoffCard?.let { place(it.root, it.params, top = true) }
            }
        }
    }

    fun destroy() {
        sharedPrompt?.ticket?.stop()
        pendingConfirm?.resolve(Decision.STOP)
        pendingHandoff?.resolve(HandoffResult.STOP)
        preferenceScope.cancel()
        main.post {
            dismissConfirm()
            dismissHandoff()
            removePill(animated = false)
        }
    }

    // ---- capsule
    // ----------------------------------------------------------------------------------

    internal fun s(id: Int, vararg args: Any): String = AppStrings.get(service, id, *args)

    internal fun unlocks(): PhoneUnlocks = debugUnlocks ?: PhoneRegistry.unlocks()

    /** A confirm (or a shared prompt) is up: the line points at it. */
    internal fun waiting() = pendingConfirm != null || sharedPrompt != null

    // ---- confirm --------------------------------------------------------------------------------

    /** Called only by the shared main-thread coordinator; no blocking or implicit fallback. */
    fun showPrompt(presentation: PromptPresentation): (() -> Unit)? {
        val p = presentation
        if (
            Looper.myLooper() != Looper.getMainLooper() ||
                !p.live ||
                confirmCard != null ||
                handoffCard != null
        )
            return null
        val root =
            FrameLayout(service).apply {
                setPadding(dp(6), dp(6), dp(6), dp(6))
                addView(
                    PhonePromptUi.card(service, p),
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                    ),
                )
            }
        val layout = params(top = false, keepScreenOn = true)
        place(root, layout, top = false)
        if (!p.live || !add(root, layout)) return null
        if (!p.markShown()) {
            remove(root)
            return null
        }
        sharedPrompt = p
        confirmCard = Card(root, layout, null)
        holdFold(FoldClock.Hold.CONFIRM)
        renderPill()
        return {
            if (sharedPrompt === p) {
                sharedPrompt = null
                dismissConfirm()
            }
            p.retire()
        }
    }

    // ---- guarded buttons ------------------------------------------------------------------------

    /**
     * A confirm button that only real fingers can press:
     * - not in its first [ARM_MS] (a tap already in flight when the card appeared);
     * - not while this service is injecting a gesture (InjectGuard);
     * - not when the touch came through another window on top (tapjacking,
     *   FLAG_WINDOW_IS_OBSCURED);
     * - not when it looks like an accessibility service's stroke: a virtual device (id ≤ 0) and
     *   either FLAG_IS_ACCESSIBILITY_EVENT (0x800) or tool type UNKNOWN — unless TalkBack-style
     *   touch exploration is on, whose double-tap takes that same path;
     * - ACTION_CLICK from any accessibility service is refused unless touch exploration is on. And
     *   the Actuator makes the overlay untouchable for every stroke it injects. Measured on the API
     *   35 emulator (debug probe): our dispatchGesture tap = flags 0x800, device -1, tool UNKNOWN;
     *   a hardware-touchscreen tap = flags 0x800 (!), device 12, tool FINGER; shell `input tap` =
     *   flags 0, device -1, tool FINGER. So 0x800 alone would lock real fingers out; the device id
     *   is what separates them.
     */
    internal inner class GuardedButton(context: Context, private val armedAt: Long) :
        TextView(context) {
        private var rejected = false

        init {
            filterTouchesWhenObscured = true
            isClickable = true
            isFocusable = true
            gravity = Gravity.CENTER
        }

        override fun dispatchTouchEvent(event: MotionEvent): Boolean {
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                val why = rejectReason(event)
                rejected = why != null
                lastGuardVerdict =
                    "${why ?: "accepted"} flags=0x${Integer.toHexString(event.flags)} device=${event.deviceId} source=0x${Integer.toHexString(event.source)} tool=${event.getToolType(0)}"
                Log.i(TAG, "guarded '$text': $lastGuardVerdict")
            }
            if (rejected) {
                if (
                    event.actionMasked == MotionEvent.ACTION_UP ||
                        event.actionMasked == MotionEvent.ACTION_CANCEL
                )
                    isPressed = false
                return true
            }
            return super.dispatchTouchEvent(event)
        }

        override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean {
            if (
                (action == AccessibilityNodeInfo.ACTION_CLICK ||
                    action == AccessibilityNodeInfo.ACTION_LONG_CLICK) &&
                    !a11y.isTouchExplorationEnabled
            ) {
                lastGuardVerdict = "accessibility-action"
                Log.i(TAG, "guarded '$text': refused accessibility click")
                return false
            }
            return super.performAccessibilityAction(action, arguments)
        }

        private fun rejectReason(event: MotionEvent): String? =
            when {
                SystemClock.uptimeMillis() < armedAt -> "too-early"
                InjectGuard.active() && !debugIgnoreInjectGuard -> "injecting"
                event.flags and MotionEvent.FLAG_WINDOW_IS_OBSCURED != 0 -> "obscured"
                event.deviceId <= 0 &&
                    (event.flags and FLAG_IS_ACCESSIBILITY_EVENT != 0 ||
                        event.getToolType(0) == MotionEvent.TOOL_TYPE_UNKNOWN) &&
                    !a11y.isTouchExplorationEnabled -> "accessibility-injected"
                else -> null
            }
    }

    // ---- forms
    // -----------------------------------------------------------------------------------
    //
    // Folding: the surface narrows to the circle at its end on the ball's side, the row fading
    // first; the ball takes its place and glides to its dock. Unfolding: the ball glides back to
    // that end and the capsule grows out of it. Capsule ↔ card: the same surface grows down and
    // out, the body fading in behind (OverlayCapsule). One window shows at a time, except a few
    // frames of overlap at the swap (both are the same circle there), so neither ever blinks.

    companion object {
        internal const val TAG = "Accessibility"
        internal const val ARM_MS = 600L

        /** The app's page link for 解除限制. */
        internal const val UNLOCKS_LINK = "dsh://open/phone-unlocks"

        /** The ball's way back to the capsule on a tap: quicker than the way out. */
        internal const val UNFOLD_GLIDE_MS = 220L

        /** Frames both windows show at a swap, so the one going is surely covered (≈ 3 frames). */
        internal const val SWAP_MS = 48L

        /** Going idle is slow and quiet; a touch brings it back at once. */
        internal const val DIM_FADE_MS = 400L
        internal const val UNDIM_FADE_MS = 120L

        /**
         * MotionEvent.FLAG_IS_ACCESSIBILITY_EVENT (hidden, API 31+). Not decisive alone, see
         * GuardedButton.
         */
        private const val FLAG_IS_ACCESSIBILITY_EVENT = 0x800
    }
}
