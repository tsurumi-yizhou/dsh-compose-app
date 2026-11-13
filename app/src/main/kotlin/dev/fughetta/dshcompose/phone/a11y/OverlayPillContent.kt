// Portions derived from Phase (https://github.com/CikeSeven/phase), used with the author's
// permission. See THIRD_PARTY_NOTICES.md.
// Local changes: a status control at the top in three forms (ball, capsule, card) on one morphing
// surface; separate confirm and handoff cards; buttons that refuse injected or obscured touches;
// pass-through and hide hooks for the Actuator and screenshots; blocking UserGate calls.

package dev.fughetta.dshcompose.phone.a11y

import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.util.Log
import android.view.WindowManager
import dev.fughetta.dshcompose.R
import dev.fughetta.dshcompose.domain.phone.Decision
import dev.fughetta.dshcompose.domain.phone.HandoffResult
import dev.fughetta.dshcompose.domain.phone.PhoneUnlocks
import dev.fughetta.dshcompose.links.LinkTrampolineActivity
import dev.fughetta.dshcompose.phone.PhoneControl
import dev.fughetta.dshcompose.phone.PhoneRegistry
import dev.fughetta.dshcompose.phone.a11y.ControlOverlay.Companion.TAG
import dev.fughetta.dshcompose.phone.a11y.ControlOverlay.Companion.UNLOCKS_LINK

/** What the capsule, the card and the ball say now; [animated] cross-fades what changed. */
internal fun ControlOverlay.renderPill(animated: Boolean = true) {
    val p = pill ?: return
    val base = line ?: return
    val u = unlocks()
    val look =
        when {
            stoppedNote -> MarkLook.STOPPED
            waiting() -> MarkLook.WAITING
            paused -> MarkLook.PAUSED
            else -> MarkLook.RUNNING
        }
    val text =
        when {
            stoppedNote -> s(R.string.overlay_stopped)
            waiting() -> s(R.string.overlay_waiting)
            paused -> s(R.string.overlay_paused_short)
            else -> steerNote ?: base.text
        }
    val sep = s(R.string.overlay_meta_separator)
    val app = debugApp ?: PhoneControl.peek()?.liveTask()?.app
    val meta =
        listOfNotNull(app, if (base.waived) s(R.string.overlay_waived) else null)
            .joinToString(sep)
            .ifEmpty { null }
    val state =
        CapsuleState(
            line = text,
            look = look,
            paused = paused,
            stopped = stoppedNote,
            expandable = !stoppedNote && !waiting(),
            unlocked = u.any,
            unlockedLine = if (u.any) unlockedLine(u) else null,
            meta = meta,
            steps = steps.before(base, StepHistory.CAPACITY),
        )
    p.render(state, animated && p.root.isShown && !ballShown, now())
    main.removeCallbacks(shrinkTick)
    p.shrinkAt?.let { main.postAtTime(shrinkTick, it + 1) }
    ball?.setLook(
        look,
        s(
            when (look) {
                MarkLook.STOPPED -> R.string.overlay_ball_stopped
                MarkLook.WAITING -> R.string.overlay_ball_waiting
                MarkLook.PAUSED -> R.string.overlay_ball_paused
                MarkLook.RUNNING -> R.string.overlay_ball_running
            },
        ),
        badge = u.any && !stoppedNote,
        stopLabel = if (stoppedNote) null else s(R.string.overlay_stop_description),
    )
}

/** 「已解除限制」 in bold over what is lifted (「系统设置、密码和验证码」), a size smaller. */
internal fun ControlOverlay.unlockedLine(u: PhoneUnlocks): CharSequence {
    val names =
        listOfNotNull(
                if (u.systemPages) s(R.string.overlay_unlock_system) else null,
                if (u.secretFields) s(R.string.overlay_unlock_secrets) else null,
                if (u.ownApp) s(R.string.overlay_unlock_own_app) else null,
            )
            .joinToString(s(R.string.overlay_list_separator))
    val title = s(R.string.overlay_unlocked)
    return SpannableStringBuilder(title).apply {
        setSpan(StyleSpan(Typeface.BOLD), 0, title.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        append('\n')
        val at = length
        append(names)
        setSpan(RelativeSizeSpan(12f / 13f), at, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
}

/** Takes the control away; [animated]: it fades first (a task that ended, not a rebuild). */
internal fun ControlOverlay.removePill(animated: Boolean) {
    val p = pill ?: return
    endEditing()
    main.removeCallbacks(foldTick)
    main.removeCallbacks(shrinkTick)
    fold.reset()
    val b = ball
    pill = null
    ball = null
    ballShown = false
    steerNote = null
    p.cancelMorph()
    val roots = listOfNotNull(p.root.takeIf { it.isShown }, b?.root?.takeIf { it.isShown })
    if (!animated || !animationsOn() || roots.isEmpty()) {
        remove(p.root)
        b?.detach()
        return
    }
    b?.cancelGlide()
    var left = roots.size
    for (r in roots) {
        r.animate()
            .alpha(0f)
            .scaleX(0.96f)
            .scaleY(0.96f)
            .setDuration(180)
            .setInterpolator(OverlayMotion.emphasized)
            .withEndAction {
                if (--left == 0) {
                    remove(p.root)
                    b?.detach()
                }
            }
            .start()
    }
}

internal fun ControlOverlay.buildPill(): OverlayCapsule {
    val c = colors()
    val strings =
        OverlayCapsule.Strings(
            fold = s(R.string.overlay_fold),
            expand = s(R.string.overlay_expand),
            collapse = s(R.string.overlay_collapse),
            pause = s(R.string.overlay_pause_description),
            resume = s(R.string.overlay_resume_description),
            stop = s(R.string.overlay_stop_description),
            steerHint = s(R.string.overlay_steer_hint),
            send = s(R.string.overlay_send),
            unlocksDescription = s(R.string.overlay_unlocked_description),
            review = s(R.string.overlay_unlocks_review),
            foldRow = s(R.string.overlay_fold_row),
            holdHint = s(R.string.overlay_hold_hint),
        )
    lateinit var capsule: OverlayCapsule
    val actions =
        OverlayCapsule.Actions(
            touched = ::onPillTouched,
            mark = ::collapseByHand,
            line = ::onLineTapped,
            pause = ::togglePause,
            stop = ::stopFromPill,
            unlocks = ::openUnlocks,
            steer = ::startSteer,
            send = ::sendInterjection,
            inputDone = ::endEditing,
        )
    capsule = OverlayCapsule(service, c, strings, actions) { update(capsule.root, capsule.params) }
    capsule.params = params(top = true, keepScreenOn = true)
    placeCapsule(capsule)
    return capsule
}

/** Centred at the top, [CapsuleMetrics.TOP_GAP_DP] under the status bar; sizes for this screen. */
internal fun ControlOverlay.placeCapsule(p: OverlayCapsule) {
    val a = safeArea()
    p.params.y = a.top + dp(CapsuleMetrics.TOP_GAP_DP) - p.padTop
    p.place(a.screenW, a.screenH)
    update(p.root, p.params)
}

/** The 「已解除限制」 row: 设置 › 手机操控 › 解除限制, through the app's own page link. */
internal fun ControlOverlay.openUnlocks() {
    val intent =
        Intent(Intent.ACTION_VIEW, Uri.parse(UNLOCKS_LINK))
            .setClass(service, LinkTrampolineActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        service.startActivity(intent)
    } catch (e: Exception) {
        Log.w(TAG, "unlocks page: ${e.javaClass.simpleName}")
    }
}

internal fun ControlOverlay.togglePause() {
    if (stoppedNote) return
    paused = !paused
    renderPill()
    PhoneRegistry.controlListener?.onPauseChanged(paused)
    announce(s(if (paused) R.string.overlay_paused_short else R.string.overlay_resume_description))
}

/** 停止 on the capsule or the card, or the ball held: everything pending answers STOP. */
internal fun ControlOverlay.stopFromPill() {
    if (stoppedNote) return
    sharedPrompt?.ticket?.stop()
    pendingConfirm?.resolve(Decision.STOP)
    pendingHandoff?.resolve(HandoffResult.STOP)
    dismissConfirm()
    dismissHandoff()
    paused = false
    endEditing()
    setStopped(true)
    renderPill()
    clearStoppedLater()
    announce(s(R.string.overlay_stopped))
    // l10n:keep agent stop reason
    PhoneRegistry.controlListener?.onStopRequested("你在悬浮条上点了停止")
}

// Android 16 deprecates both View.announceForAccessibility and
// AccessibilityEvent.TYPE_ANNOUNCEMENT with no replacement API; keep the
// spoken announcement for screen-reader users and pin the deprecation here.
@Suppress("DEPRECATION")
internal fun ControlOverlay.announce(text: String) {
    val v = if (ballShown) ball?.root else pill?.root
    if (a11y.isEnabled) v?.announceForAccessibility(text)
}

/** The line: the capsule opens into the card, the card closes. */
internal fun ControlOverlay.onLineTapped() {
    val p = pill ?: return
    if (stoppedNote || ballShown) return
    if (fold.expanded) {
        endEditing()
        if (fold.collapse(now())) p.collapse(animationsOn())
    } else if (fold.expand(now())) {
        applyIdle(animated = true)
        renderPill(animated = false)
        p.expand(animationsOn())
    }
    scheduleFold()
}

/** The steer field in the card: the window takes the keyboard until the text goes or is sent. */
internal fun ControlOverlay.startSteer() {
    val p = pill ?: return
    if (stoppedNote || !fold.expanded || editing) return
    editing = true
    holdFold(FoldClock.Hold.EDITING)
    p.params.flags = p.params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
    update(p.root, p.params)
    p.startEditing()
    p.input.post { if (editing) ime.showSoftInput(p.input, 0) }
}

internal fun ControlOverlay.endEditing() {
    val p = pill
    if (p != null && (editing || p.editing)) {
        ime.hideSoftInputFromWindow(p.input.windowToken, 0)
        p.stopEditing()
        if (p.params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE == 0) {
            p.params.flags = p.params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            update(p.root, p.params)
        }
    }
    editing = false
    releaseFold(FoldClock.Hold.EDITING)
}

internal fun ControlOverlay.sendInterjection() {
    val p = pill ?: return
    val text = p.input.text?.toString()?.trim().orEmpty()
    if (text.isEmpty()) return
    p.input.setText("")
    endEditing()
    PhoneRegistry.controlListener?.onInterject(text)
    val note = s(R.string.overlay_steer_sent, text)
    steerNote = note
    // Back to the capsule: the note shows where the line is.
    if (fold.collapse(now())) p.collapse(animationsOn())
    renderPill()
    scheduleFold()
    main.postDelayed(
        {
            if (pill === p && steerNote == note) {
                steerNote = null
                renderPill()
            }
        },
        2_500,
    )
}
