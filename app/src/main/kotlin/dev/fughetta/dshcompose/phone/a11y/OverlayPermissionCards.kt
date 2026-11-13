// Portions derived from Phase (https://github.com/CikeSeven/phase), used with the author's
// permission. See THIRD_PARTY_NOTICES.md.
// Local changes: a status control at the top in three forms (ball, capsule, card) on one morphing
// surface; separate confirm and handoff cards; buttons that refuse injected or obscured touches;
// pass-through and hide hooks for the Actuator and screenshots; blocking UserGate calls.

package dev.fughetta.dshcompose.phone.a11y

import android.graphics.Typeface
import android.os.SystemClock
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.fughetta.dshcompose.R
import dev.fughetta.dshcompose.domain.phone.ConfirmRequest
import dev.fughetta.dshcompose.domain.phone.Decision
import dev.fughetta.dshcompose.domain.phone.HandoffCause
import dev.fughetta.dshcompose.domain.phone.HandoffResult
import dev.fughetta.dshcompose.i18n.AppStrings
import dev.fughetta.dshcompose.phone.PhonePromptUi
import dev.fughetta.dshcompose.phone.a11y.ControlOverlay.Card
import dev.fughetta.dshcompose.phone.a11y.ControlOverlay.Companion.ARM_MS
import dev.fughetta.dshcompose.phone.a11y.ControlOverlay.GuardedButton
import dev.fughetta.dshcompose.phone.a11y.ControlOverlay.Pending

internal fun ControlOverlay.showConfirm(
    request: ConfirmRequest,
    pending: Pending<Decision>,
): Boolean {
    if (sharedPrompt != null || pending.value != null) return false
    pendingConfirm?.resolve(Decision.DENY)
    dismissConfirm()
    val c = colors()
    darkBuilt = isDark()
    val deadline = SystemClock.uptimeMillis() + request.timeoutMs
    val root =
        FrameLayout(service).apply {
            setPadding(dp(6), dp(8), dp(6), dp(6))
            clipToPadding = false
        }
    val card =
        LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            background = surface(c, dp(24).toFloat())
            elevation = dp(8).toFloat()
            setPadding(dp(18), dp(16), dp(18), dp(14))
        }
    val head = LinearLayout(service).apply { gravity = Gravity.CENTER_VERTICAL }
    val app = request.appLabel?.takeIf { it.isNotBlank() }
    head.addView(
        TextView(service).apply {
            text =
                if (request.firstInApp && app != null)
                    AppStrings.get(service, R.string.overlay_first_app, app)
                else AppStrings.get(service, R.string.overlay_action_title)
            textSize = 12f
            setTextColor(c.accent)
            typeface = Typeface.DEFAULT_BOLD
        },
        LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
    )
    val countdown =
        TextView(service).apply {
            textSize = 12f
            setTextColor(c.muted)
        }
    head.addView(countdown)
    card.addView(head)
    val body =
        TextView(service).apply {
            text = request.sentence
            textSize = 16f
            setTextColor(c.ink)
            setLineSpacing(0f, 1.3f)
            setPadding(0, dp(8), 0, dp(4))
        }
    card.addView(
        ScrollView(service).apply {
            addView(body)
            isVerticalScrollBarEnabled = false
        },
        LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ),
    )
    card.addView(
        TextView(service).apply {
            text =
                if (request.offerTaskWaiver) {
                    AppStrings.get(service, R.string.overlay_waiver_hint)
                } else {
                    AppStrings.get(service, R.string.overlay_timeout_hint)
                }
            textSize = 12f
            setTextColor(c.faint)
            setLineSpacing(0f, 1.2f)
            setPadding(0, 0, 0, dp(10))
        },
    )

    val armedAt = SystemClock.uptimeMillis() + ARM_MS
    val guarded = mutableListOf<GuardedButton>()
    fun decide(d: Decision) {
        if (pending.resolve(d)) main.post { if (pendingConfirm === pending) dismissConfirm() }
    }
    // 本任务免确认: the main choice waives the rest of the task. Otherwise, on the first action
    // in an app, the main choice allows that app for the rest of the task so later steps there
    // don't ask again. "Only this step" stays available as the quieter option either way.
    val wide = request.offerTaskWaiver || request.firstInApp
    if (request.offerTaskWaiver) {
        val all4task =
            GuardedButton(service, armedAt).apply {
                text = AppStrings.get(service, R.string.overlay_allow_remaining)
                style(this, c.accent, c.onAccent, c)
                setOnClickListener { decide(Decision.ALLOW_ALL_FOR_TASK) }
            }
        guarded += all4task
        card.addView(
            all4task,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(46)).apply {
                bottomMargin = dp(8)
            },
        )
    } else if (request.firstInApp) {
        val app4task =
            GuardedButton(service, armedAt).apply {
                text =
                    if (app != null) AppStrings.get(service, R.string.overlay_allow_app_task, app)
                    else AppStrings.get(service, R.string.overlay_allow_this_app_task)
                style(this, c.accent, c.onAccent, c)
                setOnClickListener { decide(Decision.ALLOW_APP_FOR_TASK) }
            }
        guarded += app4task
        card.addView(
            app4task,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(46)).apply {
                bottomMargin = dp(8)
            },
        )
    }
    val row = LinearLayout(service).apply { gravity = Gravity.CENTER_VERTICAL }
    row.addView(
        textButton(AppStrings.get(service, R.string.overlay_stop), c.danger, c) {
            decide(Decision.STOP)
        },
    )
    row.addView(View(service), LinearLayout.LayoutParams(0, 1, 1f))
    row.addView(
        textButton(AppStrings.get(service, R.string.overlay_decline), c.ink, c) {
                decide(Decision.DENY)
            }
            .apply { background = outlined(c) },
        LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(46)).apply {
            marginEnd = dp(8)
        },
    )
    val allow =
        GuardedButton(service, armedAt).apply {
            text =
                if (wide) AppStrings.get(service, R.string.overlay_allow_step)
                else AppStrings.get(service, R.string.overlay_allow_once)
            if (wide) style(this, c.accentSoft, c.onAccentSoft, c)
            else style(this, c.accent, c.onAccent, c)
            setOnClickListener { decide(Decision.ALLOW_ONCE) }
        }
    guarded += allow
    row.addView(
        allow,
        LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(46)),
    )
    card.addView(row)
    root.addView(
        card,
        FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
        ),
    )

    guarded.forEach { it.alpha = 0.5f }
    main.postDelayed(
        { guarded.forEach { it.animate().alpha(1f).setDuration(120).start() } },
        ARM_MS,
    )

    val tick =
        object : Runnable {
            override fun run() {
                val left = deadline - SystemClock.uptimeMillis()
                if (left <= 0) {
                    decide(Decision.DENY)
                    return
                }
                countdown.text =
                    AppStrings.plural(
                        service,
                        R.plurals.overlay_seconds,
                        ((left + 999) / 1000).toInt(),
                        (left + 999) / 1000,
                    )
                main.postDelayed(this, 1_000)
            }
        }
    val params = params(top = false, keepScreenOn = true)
    place(root, params, top = false)
    if (!add(root, params)) return false
    if (pending.value != null) {
        remove(root)
        return false
    }
    confirmCard = Card(root, params, tick)
    pendingConfirm = pending
    tick.run()
    enter(root)
    holdFold(FoldClock.Hold.CONFIRM)
    renderPill()
    return true
}

internal fun ControlOverlay.dismissConfirm() {
    val card = confirmCard ?: return
    card.tick?.let(main::removeCallbacks)
    remove(card.root)
    confirmCard = null
    pendingConfirm = null
    releaseFold(FoldClock.Hold.CONFIRM)
    renderPill()
}

// ---- handoff --------------------------------------------------------------------------------

internal fun ControlOverlay.showHandoff(
    reason: String,
    timeoutMs: Long,
    pending: Pending<HandoffResult>,
    cause: HandoffCause?,
): Boolean {
    if (sharedPrompt != null || pending.value != null) return false
    pendingHandoff?.resolve(HandoffResult.STOP)
    dismissHandoff()
    val c = colors()
    darkBuilt = isDark()
    val deadline = SystemClock.uptimeMillis() + timeoutMs
    val root =
        FrameLayout(service).apply {
            setPadding(dp(6), dp(4), dp(6), dp(8))
            clipToPadding = false
        }
    val card =
        LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            background = surface(c, dp(22).toFloat())
            elevation = dp(8).toFloat()
            setPadding(dp(18), dp(14), dp(14), dp(10))
        }
    val head = LinearLayout(service).apply { gravity = Gravity.CENTER_VERTICAL }
    head.addView(
        TextView(service).apply {
            text =
                cause?.let { PhonePromptUi.handoffCause(service, it) }
                    ?: AppStrings.get(service, R.string.overlay_handoff_title)
            textSize = 12f
            setTextColor(c.accent)
            typeface = Typeface.DEFAULT_BOLD
        },
        LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
    )
    val countdown =
        TextView(service).apply {
            textSize = 12f
            setTextColor(c.muted)
        }
    head.addView(countdown)
    card.addView(head)
    card.addView(
        TextView(service).apply {
            text = reason
            textSize = 15f
            setTextColor(c.ink)
            maxLines = 4
            ellipsize = TextUtils.TruncateAt.END
            setLineSpacing(0f, 1.25f)
            setPadding(0, dp(6), 0, dp(2))
        },
    )
    card.addView(
        TextView(service).apply {
            text = AppStrings.get(service, R.string.overlay_handoff_hint)
            textSize = 12f
            setTextColor(c.faint)
            setPadding(0, 0, 0, dp(6))
        },
    )
    fun finish(r: HandoffResult) {
        if (pending.resolve(r)) main.post { if (pendingHandoff === pending) dismissHandoff() }
    }
    val row = LinearLayout(service).apply { gravity = Gravity.CENTER_VERTICAL }
    row.addView(
        textButton(AppStrings.get(service, R.string.overlay_stop), c.danger, c) {
            finish(HandoffResult.STOP)
        },
    )
    row.addView(View(service), LinearLayout.LayoutParams(0, 1, 1f))
    val armedAt = SystemClock.uptimeMillis() + ARM_MS
    val go =
        GuardedButton(service, armedAt).apply {
            text = AppStrings.get(service, R.string.overlay_continue)
            style(this, c.accent, c.onAccent, c)
            setOnClickListener { finish(HandoffResult.CONTINUE) }
        }
    row.addView(go, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(44)))
    card.addView(row)
    root.addView(
        card,
        FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
        ),
    )

    val tick =
        object : Runnable {
            override fun run() {
                val left = deadline - SystemClock.uptimeMillis()
                if (left <= 0) {
                    finish(HandoffResult.TIMEOUT)
                    return
                }
                val s = (left + 999) / 1000
                countdown.text =
                    if (s >= 60)
                        AppStrings.get(
                            service,
                            R.string.overlay_time_remaining,
                            "${s / 60}:${"%02d".format(s % 60)}",
                        )
                    else
                        AppStrings.plural(
                            service,
                            R.plurals.overlay_seconds_remaining,
                            s.toInt(),
                            s,
                        )
                main.postDelayed(this, 1_000)
            }
        }
    val params = params(top = true, keepScreenOn = true)
    place(root, params, top = true)
    if (!add(root, params)) return false
    if (pending.value != null) {
        remove(root)
        return false
    }
    handoffCard = Card(root, params, tick)
    pendingHandoff = pending
    // The card says it all: capsule and ball step aside, and the capsule comes back after.
    holdFold(FoldClock.Hold.HANDOFF)
    syncWindows()
    tick.run()
    enter(root)
    return true
}

internal fun ControlOverlay.dismissHandoff() {
    val card = handoffCard ?: return
    card.tick?.let(main::removeCallbacks)
    remove(card.root)
    handoffCard = null
    pendingHandoff = null
    syncWindows()
    releaseFold(FoldClock.Hold.HANDOFF)
}
