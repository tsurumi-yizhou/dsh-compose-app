// Portions derived from Phase (https://github.com/CikeSeven/phase), used with the author's
// permission. See THIRD_PARTY_NOTICES.md.
// Local changes: a status control at the top in three forms (ball, capsule, card) on one morphing
// surface; separate confirm and handoff cards; buttons that refuse injected or obscured touches;
// pass-through and hide hooks for the Actuator and screenshots; blocking UserGate calls.

package dev.fughetta.dshcompose.phone.a11y

import android.content.res.ColorStateList
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.util.Log
import android.view.Gravity
import android.view.Surface
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.TextView
import dev.fughetta.dshcompose.feature.accessibility.R
import dev.fughetta.dshcompose.phone.MainThreadCall
import dev.fughetta.dshcompose.phone.a11y.ControlOverlay.Companion.DIM_FADE_MS
import dev.fughetta.dshcompose.phone.a11y.ControlOverlay.Companion.SWAP_MS
import dev.fughetta.dshcompose.phone.a11y.ControlOverlay.Companion.TAG
import dev.fughetta.dshcompose.phone.a11y.ControlOverlay.Companion.UNDIM_FADE_MS
import dev.fughetta.dshcompose.phone.a11y.ControlOverlay.Companion.UNFOLD_GLIDE_MS

internal fun ControlOverlay.now() = SystemClock.uptimeMillis()

internal fun ControlOverlay.scheduleFold() {
    main.removeCallbacks(foldTick)
    if (debugFreeze) return
    val at = fold.nextAt ?: return
    main.postAtTime(foldTick, at)
}

internal fun ControlOverlay.onFoldTick() {
    if (debugFreeze) return
    when (fold.tick(now())) {
        FoldClock.Tick.FOLD -> foldPill(animationsOn())
        FoldClock.Tick.COLLAPSE -> pill?.collapse(animationsOn())
        FoldClock.Tick.DIM -> applyIdle(animated = true)
        FoldClock.Tick.NONE -> Unit
    }
    scheduleFold()
}

internal fun ControlOverlay.onPillTouched() {
    // A touch on a capsule that is still shrinking into the ball opens it again.
    if (fold.folded && !ballShown) {
        fold.unfold(now())
        pill?.cancelMorph()
    } else if (fold.touch(now())) applyIdle(animated = true)
    scheduleFold()
}

internal fun ControlOverlay.onBallTouched() {
    // A finger stops an unfold's glide: the capsule it had brought up (transparent) goes again.
    syncWindows()
    fold.touch(now())
    applyIdle(animated = true)
    scheduleFold()
}

internal fun ControlOverlay.onBallTapped() {
    if (fold.unfold(now())) unfoldPill(animationsOn())
    applyIdle(animated = true)
    scheduleFold()
}

/** The mark (or the card's 收成小球): folded until the ball is tapped, for the rest of this task. */
internal fun ControlOverlay.collapseByHand() {
    endEditing()
    if (fold.foldByHand(now())) foldPill(animationsOn())
    scheduleFold()
}

internal fun ControlOverlay.holdFold(hold: FoldClock.Hold) {
    // A handoff hides both anyway: change form without a show.
    if (fold.hold(hold, now())) syncForm(animationsOn() && hold != FoldClock.Hold.HANDOFF)
    applyIdle(animated = true)
    scheduleFold()
}

internal fun ControlOverlay.releaseFold(hold: FoldClock.Hold) {
    fold.release(hold, now())
    scheduleFold()
}

/** Brings what is on screen to the form [fold] says. */
internal fun ControlOverlay.syncForm(animated: Boolean) {
    val p = pill ?: return
    when (fold.form) {
        FoldClock.Form.BALL -> if (!ballShown) foldPill(animated)
        FoldClock.Form.CAPSULE ->
            when {
                ballShown -> unfoldPill(animated)
                p.shape == OverlayCapsule.Shape.CARD -> {
                    endEditing()
                    p.collapse(animated)
                }
            }
        FoldClock.Form.CARD ->
            if (!ballShown && p.shape != OverlayCapsule.Shape.CARD) p.expand(animated)
    }
}

internal fun ControlOverlay.onPrefsChanged() {
    val p = OverlayPrefs.from(overlayPrefs)
    idleOpacity = p.idleOpacity
    fold.setAutoFold(p.autoFold, now())
    // A new 不透明度 shows at once on a faded ball or capsule.
    applyIdle(animated = false)
    scheduleFold()
}

/**
 * The ball or the capsule fades, all of it alike, to the idle opacity, or comes back to full:
 * slowly going idle, quickly on a touch. The card never fades. The form not on screen is kept at
 * full, ready for the next fold or unfold.
 */
internal fun ControlOverlay.applyIdle(animated: Boolean) {
    val target = if (fold.dimmed) idleOpacity else 1f
    val durationMs = if (target < 1f) DIM_FADE_MS else UNDIM_FADE_MS
    ball?.setIdle(if (fold.folded) target else 1f, animated, durationMs)
    pill?.setIdle(if (fold.folded) 1f else target, animated, durationMs)
}

/**
 * The ball's window for this control, added (hidden) on first use; null when the system refused it.
 */
internal fun ControlOverlay.ensureBall(): OverlayBall? {
    ball?.let {
        return it
    }
    val b =
        OverlayBall(
            service,
            wm,
            ::safeArea,
            colors(),
            ::onBallTapped,
            ::stopFromPill,
            ::onBallTouched,
        )
    if (!b.attach(s(R.string.overlay_window_title), passThrough, b.dockPos())) return null
    ball = b
    renderPill(animated = false)
    return b
}

/** The ball window over the surface's circle at ([circle]) in the capsule window. */
internal fun ControlOverlay.ballOver(
    p: OverlayCapsule,
    b: OverlayBall,
    circle: Pair<Int, Int>,
): WindowPos =
    BallGeometry.ballOver(
        windowX = BallGeometry.centredX(safeArea().screenW, p.params.width),
        windowY = p.params.y,
        padX = p.padX,
        padTop = p.padTop,
        circleLeft = circle.first,
        circleTop = circle.second,
        pad = b.pad,
    )

internal fun ControlOverlay.foldPill(animated: Boolean) {
    val p = pill ?: return
    if (ballShown) return
    endEditing()
    val b = ensureBall()
    if (b == null) {
        // No second window: the capsule stays as it was.
        fold.unfold(now())
        scheduleFold()
        return
    }
    // Folding is a change worth seeing: at full from here, fading again later.
    applyIdle(animated = false)
    val shown = animated && p.root.isShown && p.surface.width > 0
    if (!shown) {
        p.cancelMorph()
        p.settle(OverlayCapsule.Shape.CAPSULE)
        b.move(b.dockPos())
        b.fadeMark(1f, animated = false)
        ballShown = true
        syncWindows()
        renderPill(animated = false)
        return
    }
    val side = b.dock.side
    b.move(ballOver(p, b, p.endCircle(side)))
    p.foldTo(side, animated = true) {
        ballShown = true
        b.fadeMark(0f, animated = false)
        showWindow(b.root)
        b.fadeMark(1f, animated = true)
        // The capsule goes (and gets its plain layout back) once the ball is surely drawn over
        // it.
        b.root.postDelayed(
            {
                if (pill === p && ballShown) {
                    syncWindows()
                    p.settle(OverlayCapsule.Shape.CAPSULE)
                }
            },
            SWAP_MS,
        )
        renderPill(animated = false)
        b.glideTo(b.dockPos(), animated = true)
    }
}

internal fun ControlOverlay.unfoldPill(animated: Boolean) {
    val p = pill ?: return
    val b = ball
    if (b == null || !ballShown) {
        ballShown = false
        syncWindows()
        renderPill()
        return
    }
    if (!animated || captureHidden || !b.root.isShown) {
        p.settle(OverlayCapsule.Shape.CAPSULE)
        b.move(b.dockPos())
        ballShown = false
        syncWindows()
        renderPill(animated = false)
        return
    }
    // Only the ball shows from here (a fold may have just ended with both up).
    syncWindows()
    val side = b.dock.side
    // The capsule window comes up now, transparent, as the circle the ball is heading for:
    // drawn before the swap, so the morph starts on its first frame instead of popping in late.
    renderPill(animated = false)
    p.prepareUnfold(side)
    p.root.alpha = 0f
    p.root.visibility = if (captureHidden) View.INVISIBLE else View.VISIBLE
    // The ball goes back to the capsule's end first, its mark fading.
    b.fadeMark(0f, animated = true, durationMs = UNFOLD_GLIDE_MS)
    b.glideTo(
        ballOver(p, b, p.capsuleEndCircle(side)),
        animated = true,
        durationMs = UNFOLD_GLIDE_MS,
    ) {
        if (pill !== p || !ballShown) return@glideTo
        ballShown = false
        showWindow(p.root)
        p.unfoldFrom(side, animated = true) {}
        // The ball goes once the capsule is surely drawn over it, back to its dock for next
        // time.
        b.root.postDelayed(
            {
                if (pill === p && !ballShown) {
                    syncWindows()
                    b.move(b.dockPos())
                    b.fadeMark(1f, animated = false)
                }
            },
            SWAP_MS,
        )
    }
}

/**
 * Capsule and ball visibility from the state: the handoff card hides both, a screenshot makes them
 * invisible for its moment, otherwise the one [ballShown] says. GONE windows take no touches, so
 * the hidden one is never in the way.
 */
internal fun ControlOverlay.syncWindows() {
    val p = pill ?: return
    val handoff = handoffCard != null
    val shown = if (captureHidden) View.INVISIBLE else View.VISIBLE
    p.root.visibility = if (handoff || ballShown) View.GONE else shown
    ball?.root?.visibility = if (handoff || !ballShown) View.GONE else shown
}

internal fun ControlOverlay.showWindow(root: View) {
    root.alpha = 1f
    root.visibility = if (captureHidden) View.INVISIBLE else View.VISIBLE
}

/** The screen and its bars and cutout, in px (the ball's dock is kept clear of them). */
internal fun ControlOverlay.safeArea(): SafeArea {
    if (Build.VERSION.SDK_INT >= 30) {
        val m = wm.currentWindowMetrics
        val i =
            m.windowInsets.getInsetsIgnoringVisibility(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout(),
            )
        return SafeArea(m.bounds.width(), m.bounds.height(), i.left, i.top, i.right, i.bottom)
    }
    val dm = DisplayMetrics()
    @Suppress("DEPRECATION") val display = wm.defaultDisplay
    @Suppress("DEPRECATION") display.getRealMetrics(dm)
    val res = Resources.getSystem()
    fun dimen(name: String) =
        res.getIdentifier(name, "dimen", "android")
            .takeIf { it != 0 }
            ?.let(res::getDimensionPixelSize) ?: 0
    val status = dimen("status_bar_height")
    val nav = dimen("navigation_bar_height")
    return when (display.rotation) {
        Surface.ROTATION_90 -> SafeArea(dm.widthPixels, dm.heightPixels, 0, status, nav, 0)
        Surface.ROTATION_270 -> SafeArea(dm.widthPixels, dm.heightPixels, nav, status, 0, 0)
        else -> SafeArea(dm.widthPixels, dm.heightPixels, 0, status, 0, nav)
    }
}

// ---- window plumbing ------------------------------------------------------------------------

internal fun ControlOverlay.params(top: Boolean, keepScreenOn: Boolean) =
    WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                (if (keepScreenOn) WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON else 0) or
                (if (passThrough) WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE else 0),
            PixelFormat.TRANSLUCENT,
        )
        .apply {
            gravity = (if (top) Gravity.TOP else Gravity.BOTTOM) or Gravity.CENTER_HORIZONTAL
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
            title = s(R.string.overlay_window_title)
            if (Build.VERSION.SDK_INT >= 30) setFitInsetsTypes(0)
        }

/** The confirm and handoff cards: nearly the screen's width, at the top or the bottom. */
internal fun ControlOverlay.place(root: View, params: WindowManager.LayoutParams, top: Boolean) {
    val (screenW, insetTop, insetBottom) = metrics()
    params.width = minOf(screenW - dp(12), dp(if (top) 492 else 472))
    params.y = if (top) insetTop + dp(2) else insetBottom + dp(6)
    if (root.isAttachedToWindow) update(root, params)
}

internal fun ControlOverlay.applyPassThrough(on: Boolean): Boolean {
    if (passThrough == on) return false
    passThrough = on
    var any = false
    for ((root, params) in windows()) {
        params.flags =
            if (on) params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            else params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        update(root, params)
        any = true
    }
    return any
}

internal fun ControlOverlay.windows(): List<Pair<View, WindowManager.LayoutParams>> =
    listOfNotNull(
        pill?.let { it.root to it.params },
        ball?.let { it.root to it.params },
        confirmCard?.let { it.root to it.params },
        handoffCard?.let { it.root to it.params },
    )

internal fun ControlOverlay.add(view: View, params: WindowManager.LayoutParams): Boolean =
    try {
        wm.addView(view, params)
        true
    } catch (e: Exception) {
        Log.w(TAG, "overlay addView failed", e)
        false
    }

internal fun ControlOverlay.update(view: View, params: WindowManager.LayoutParams) {
    try {
        if (view.isAttachedToWindow) wm.updateViewLayout(view, params)
    } catch (e: Exception) {
        Log.w(TAG, "overlay update failed", e)
    }
}

internal fun ControlOverlay.remove(view: View) {
    try {
        wm.removeView(view)
    } catch (_: Exception) {}
}

internal fun ControlOverlay.enter(view: View) {
    if (!animationsOn()) return
    view.alpha = 0f
    view.translationY = -dp(6).toFloat()
    view.scaleX = 0.96f
    view.scaleY = 0.96f
    view
        .animate()
        .alpha(1f)
        .translationY(0f)
        .scaleX(1f)
        .scaleY(1f)
        .setDuration(220)
        .setInterpolator(OverlayMotion.emphasized)
        .start()
}

/** Runs [block] on the main thread and waits for it; null on timeout. */
internal fun <T> ControlOverlay.onMain(timeoutMs: Long, block: () -> T): T? {
    if (Looper.myLooper() == Looper.getMainLooper()) return block()
    val call = MainThreadCall {
        try {
            block()
        } catch (e: Exception) {
            Log.w(TAG, "overlay main-thread call failed", e)
            null
        }
    }
    main.post { call.run() }
    return call.await(timeoutMs)
}

internal fun ControlOverlay.metrics(): Triple<Int, Int, Int> {
    if (Build.VERSION.SDK_INT >= 30) {
        val m = wm.currentWindowMetrics
        val insets =
            m.windowInsets.getInsetsIgnoringVisibility(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout(),
            )
        return Triple(m.bounds.width(), insets.top, insets.bottom)
    }
    val res = Resources.getSystem()
    fun dimen(name: String) =
        res.getIdentifier(name, "dimen", "android")
            .takeIf { it != 0 }
            ?.let(res::getDimensionPixelSize) ?: 0
    @Suppress("DEPRECATION") val width = wm.defaultDisplay.width
    return Triple(width, dimen("status_bar_height"), dimen("navigation_bar_height"))
}

// ---- look -----------------------------------------------------------------------------------

internal fun ControlOverlay.isDark() =
    debugDark
        ?: (service.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES)

internal fun ControlOverlay.colors() = if (isDark()) OverlayPalette.DARK else OverlayPalette.LIGHT

/** The confirm and handoff cards' surface: the control's, as a drawable. */
internal fun ControlOverlay.surface(c: OverlayPalette, radius: Float) =
    GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(c.surfaceTop, c.surfaceBottom),
        )
        .apply {
            cornerRadius = radius
            setStroke(1, c.hairline)
        }

internal fun ControlOverlay.outlined(c: OverlayPalette) =
    RippleDrawable(
        ColorStateList.valueOf(c.ripple),
        GradientDrawable().apply {
            cornerRadius = dp(14).toFloat()
            setStroke(dp(1), c.outline)
            setColor(Color.TRANSPARENT)
        },
        null,
    )

internal fun ControlOverlay.style(button: TextView, fill: Int, ink: Int, c: OverlayPalette) {
    button.textSize = 15f
    button.setTextColor(ink)
    button.typeface = Typeface.DEFAULT_BOLD
    button.setPadding(dp(18), 0, dp(18), 0)
    button.background =
        RippleDrawable(
            ColorStateList.valueOf(c.ripple),
            GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(fill)
            },
            null,
        )
}

internal fun ControlOverlay.textButton(
    label: String,
    ink: Int,
    c: OverlayPalette,
    action: () -> Unit,
) =
    TextView(service).apply {
        text = label
        textSize = 14f
        setTextColor(ink)
        gravity = Gravity.CENTER
        minimumHeight = dp(48)
        minimumWidth = dp(48)
        setPadding(dp(12), 0, dp(12), 0)
        background =
            RippleDrawable(
                ColorStateList.valueOf(c.ripple),
                null,
                GradientDrawable().apply {
                    cornerRadius = dp(14).toFloat()
                    setColor(Color.WHITE)
                },
            )
        isClickable = true
        isFocusable = true
        setOnClickListener { action() }
    }

internal fun ControlOverlay.animationsOn() = OverlayMotion.on()

internal fun ControlOverlay.dp(v: Int) = service.dp(v)
