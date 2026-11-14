package dev.fughetta.dshcompose.phone.a11y

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.RippleDrawable
import android.text.InputFilter
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/** What the capsule and its card show; ControlOverlay builds it from the task and the line. */
internal data class CapsuleState(
    /** The header's line: what DSH does now, or 已暂停 / 等你确认 / 已停止. */
    val line: String,
    val look: MarkLook,
    val paused: Boolean,
    val stopped: Boolean,
    /**
     * The line opens the card (not while a confirm waits, nor once stopped): it shows its chevron.
     */
    val expandable: Boolean,
    /** Any limit lifted: a lock before the line, a row in the card. */
    val unlocked: Boolean,
    /** The card's 已解除限制 row (which limits). */
    val unlockedLine: CharSequence?,
    /** The card's first line under the header: 「微信 · 免确认」. */
    val meta: String?,
    /** The steps before this one, newest first. */
    val steps: List<String>,
)

/**
 * The control's open window (TYPE_ACCESSIBILITY_OVERLAY): one solid surface that is a capsule — the
 * default, the line and 暂停 / 停止 — or the card that grows out of it, and shrinks to a circle when it
 * folds into the ball. The surface is one view drawing one rounded rect that the morphs animate
 * ([OverlaySurface]); the rows inside never re-flow during a fold, they are cut by the outline and
 * fade. The header row is the same in both forms, so 停止 stays at the right end.
 *
 * Main thread only. The owner ([ControlOverlay]) adds and removes the window, decides the form and
 * runs the ball; this class draws, lays out and morphs.
 */
internal class OverlayCapsule(
    internal val ctx: Context,
    private val c: OverlayPalette,
    private val strings: Strings,
    private val actions: Actions,
    /** Applies [params] to the window (after the surface changed size). */
    internal val relayout: () -> Unit,
) {
    class Strings(
        val fold: String,
        val expand: String,
        val collapse: String,
        val pause: String,
        val resume: String,
        val stop: String,
        val steerHint: String,
        val send: String,
        val unlocksDescription: String,
        val review: String,
        val foldRow: String,
        val holdHint: String,
    )

    class Actions(
        /** Any finger came down on the window. */
        val touched: () -> Unit,
        val mark: () -> Unit,
        val line: () -> Unit,
        val pause: () -> Unit,
        val stop: () -> Unit,
        val unlocks: () -> Unit,
        val steer: () -> Unit,
        val send: () -> Unit,
        val inputDone: () -> Unit,
    )

    enum class Shape {
        CAPSULE,
        CARD,
    }

    internal val headerH = ctx.dp(CapsuleMetrics.HEIGHT_DP)
    internal val ball = headerH

    /** Room round the surface for its shadow (transparent, inside the window). */
    val padX = ctx.dp(PAD_X_DP)
    val padTop = ctx.dp(PAD_TOP_DP)
    val padBottom = ctx.dp(PAD_BOTTOM_DP)

    // ---- header
    // -----------------------------------------------------------------------------------

    val mark =
        MarkView(ctx, c).apply {
            contentDescription = strings.fold
            foreground =
                RippleDrawable(
                    ColorStateList.valueOf(c.ripple),
                    null,
                    InsetDrawable(
                        GradientDrawable().apply {
                            shape = GradientDrawable.OVAL
                            setColor(Color.WHITE)
                        },
                        ctx.dp(4),
                    ),
                )
            setOnClickListener { actions.mark() }
        }

    private val lock =
        GlyphView(ctx, Glyph.LOCK, CapsuleMetrics.LOCK_DP.toFloat()).apply {
            color = c.danger
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            visibility = View.GONE
        }

    val text =
        TextView(ctx).apply {
            textSize = 14f
            setTextColor(c.ink)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            includeFontPadding = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }

    internal val chevron =
        GlyphView(ctx, Glyph.CHEVRON, CapsuleMetrics.CHEVRON_DP.toFloat()).apply {
            color = c.faint
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }

    /** The line with its lock and chevron: one key that opens and closes the card. */
    private val lineKey =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            setPadding(0, 0, ctx.dp(CapsuleMetrics.LINE_END_DP), 0)
            foreground =
                RippleDrawable(
                    ColorStateList.valueOf(c.ripple),
                    null,
                    InsetDrawable(
                        GradientDrawable().apply {
                            cornerRadius = ctx.dpf(18f)
                            setColor(Color.WHITE)
                        },
                        0,
                        ctx.dp(6),
                        0,
                        ctx.dp(6),
                    ),
                )
            addView(
                lock,
                LinearLayout.LayoutParams(
                        ctx.dp(CapsuleMetrics.LOCK_DP),
                        ctx.dp(CapsuleMetrics.LOCK_DP),
                    )
                    .apply { marginEnd = ctx.dp(CapsuleMetrics.LOCK_GAP_DP) },
            )
            addView(text, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(
                chevron,
                LinearLayout.LayoutParams(
                        ctx.dp(CapsuleMetrics.CHEVRON_DP),
                        ctx.dp(CapsuleMetrics.CHEVRON_DP),
                    )
                    .apply { marginStart = ctx.dp(CapsuleMetrics.CHEVRON_GAP_DP) },
            )
            setOnClickListener { actions.line() }
            accessibilityDelegate =
                object : View.AccessibilityDelegate() {
                    override fun onInitializeAccessibilityNodeInfo(
                        host: View,
                        info: AccessibilityNodeInfo,
                    ) {
                        super.onInitializeAccessibilityNodeInfo(host, info)
                        info.className = android.widget.Button::class.java.name
                        info.addAction(
                            AccessibilityNodeInfo.AccessibilityAction(
                                AccessibilityNodeInfo.ACTION_CLICK,
                                if (shape == Shape.CARD) strings.collapse else strings.expand,
                            ),
                        )
                    }
                }
        }

    val pause =
        RoundKey(ctx, CapsuleMetrics.DISC_DP, 18).apply {
            disc = c.tonal
            ink = c.ink
            setRipple(c.ripple)
            contentDescription = strings.pause
            setOnClickListener { actions.pause() }
        }

    val stop =
        RoundKey(ctx, CapsuleMetrics.DISC_DP, 16).apply {
            disc = c.dangerSoft
            ink = c.danger
            setGlyph(Glyph.STOP, animated = false)
            setRipple(c.ripple)
            contentDescription = strings.stop
            setOnClickListener { actions.stop() }
        }

    private val keyGap = View(ctx)

    internal val header =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val key = ctx.dp(CapsuleMetrics.KEY_DP)
            addView(mark, LinearLayout.LayoutParams(key, key))
            addView(lineKey, LinearLayout.LayoutParams(0, key, 1f))
            addView(pause, LinearLayout.LayoutParams(key, key))
            addView(keyGap, LinearLayout.LayoutParams(ctx.dp(CapsuleMetrics.KEY_GAP_DP), 1))
            addView(stop, LinearLayout.LayoutParams(key, key))
        }

    // ---- card body
    // ----------------------------------------------------------------------------------

    /** Where the card's text starts: level with the line, the mark's column as a gutter. */
    private val column = ctx.dp(CapsuleMetrics.KEY_DP + 4)
    private val gutter = ctx.dpf(CapsuleMetrics.KEY_DP / 2f)

    private val meta =
        TextView(ctx).apply {
            textSize = 12f
            setTextColor(c.faint)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(column, 0, ctx.dp(16), ctx.dp(4))
        }

    private val stepRows = List(StepHistory.CAPACITY) { StepRow() }

    private val unlockText =
        TextView(ctx).apply {
            textSize = 13f
            setTextColor(c.onDangerSoft)
            maxLines = 3
            ellipsize = TextUtils.TruncateAt.END
            setLineSpacing(0f, 1.15f)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }

    private val unlocks =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = ctx.dp(48)
            isClickable = true
            isFocusable = true
            contentDescription = strings.unlocksDescription
            val r = ctx.dpf(INNER_RADIUS_DP)
            background =
                GradientDrawable().apply {
                    cornerRadius = r
                    setColor(c.dangerSoft)
                }
            foreground =
                RippleDrawable(
                    ColorStateList.valueOf(c.ripple),
                    null,
                    GradientDrawable().apply {
                        cornerRadius = r
                        setColor(Color.WHITE)
                    },
                )
            setPadding(0, ctx.dp(8), ctx.dp(12), ctx.dp(8))
            // The lock in the mark's column (the row sits 12 dp in, its text level with the
            // card's).
            val inset = ctx.dp(BODY_INSET_DP)
            addView(
                GlyphView(ctx, Glyph.LOCK, 16f).apply { color = c.danger },
                LinearLayout.LayoutParams(ctx.dp(CapsuleMetrics.KEY_DP) - 2 * inset, ctx.dp(16))
                    .apply { marginEnd = column - (ctx.dp(CapsuleMetrics.KEY_DP) - inset) },
            )
            addView(
                unlockText,
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
            )
            addView(
                TextView(ctx).apply {
                    text = strings.review
                    textSize = 13f
                    setTextColor(c.danger)
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                    setPadding(ctx.dp(12), 0, 0, 0)
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                },
            )
            addView(
                GlyphView(ctx, Glyph.CHEVRON, 12f).apply {
                    color = c.danger
                    turn = -90f
                },
                LinearLayout.LayoutParams(ctx.dp(12), ctx.dp(12)).apply { marginStart = ctx.dp(2) },
            )
            setOnClickListener { actions.unlocks() }
        }

    /**
     * 插话: a field that only takes the keyboard once tapped (the window is not focusable till then).
     */
    val input: EditText =
        object : EditText(ctx) {
                override fun onKeyPreIme(keyCode: Int, event: KeyEvent?): Boolean {
                    if (keyCode == KeyEvent.KEYCODE_BACK && event?.action == KeyEvent.ACTION_UP) {
                        actions.inputDone()
                        return true
                    }
                    return super.onKeyPreIme(keyCode, event)
                }
            }
            .apply {
                textSize = 14f
                setTextColor(c.ink)
                setHintTextColor(c.faint)
                hint = strings.steerHint
                background = null
                setPadding(ctx.dp(16), ctx.dp(11), ctx.dp(8), ctx.dp(11))
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                imeOptions = EditorInfo.IME_ACTION_SEND or EditorInfo.IME_FLAG_NO_EXTRACT_UI
                maxLines = 3
                filters = arrayOf(InputFilter.LengthFilter(2000))
                setOnEditorActionListener { _, action, _ ->
                    if (action == EditorInfo.IME_ACTION_SEND) {
                        actions.send()
                        true
                    } else false
                }
                addTextChangedListener(
                    object : android.text.TextWatcher {
                        override fun beforeTextChanged(
                            s: CharSequence?,
                            start: Int,
                            count: Int,
                            after: Int,
                        ) {}

                        override fun onTextChanged(
                            s: CharSequence?,
                            start: Int,
                            before: Int,
                            count: Int,
                        ) {}

                        override fun afterTextChanged(s: android.text.Editable?) {
                            syncSend()
                        }
                    },
                )
            }

    private val send =
        RoundKey(ctx, 32, 16).apply {
            setGlyph(Glyph.SEND, animated = false)
            setRipple(c.ripple)
            contentDescription = strings.send
            setOnClickListener { actions.send() }
        }

    /** Taps on the field before typing starts go to [Actions.steer], not the text. */
    var editing = false
        private set

    private val steer =
        object : LinearLayout(ctx) {
                override fun onInterceptTouchEvent(ev: MotionEvent): Boolean = !editing

                override fun onTouchEvent(ev: MotionEvent): Boolean {
                    if (!editing && ev.actionMasked == MotionEvent.ACTION_UP) actions.steer()
                    return !editing || super.onTouchEvent(ev)
                }
            }
            .apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = ctx.dp(44)
                background =
                    GradientDrawable().apply {
                        cornerRadius = ctx.dpf(22f)
                        setColor(c.field)
                    }
                setPadding(0, 0, ctx.dp(6), 0)
                addView(
                    input,
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
                )
                addView(send, LinearLayout.LayoutParams(ctx.dp(40), ctx.dp(40)))
            }

    private val footer =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = ctx.dp(48)
            isClickable = true
            isFocusable = true
            foreground =
                RippleDrawable(
                    ColorStateList.valueOf(c.ripple),
                    null,
                    InsetDrawable(
                        GradientDrawable().apply {
                            cornerRadius = ctx.dpf(INNER_RADIUS_DP)
                            setColor(Color.WHITE)
                        },
                        ctx.dp(BODY_INSET_DP),
                        0,
                        ctx.dp(BODY_INSET_DP),
                        0,
                    ),
                )
            contentDescription = "${strings.foldRow}. ${strings.holdHint}"
            addView(
                GlyphView(ctx, Glyph.BALL, 14f).apply { color = c.faint },
                LinearLayout.LayoutParams(ctx.dp(CapsuleMetrics.KEY_DP), ctx.dp(14)).apply {
                    marginEnd = column - ctx.dp(CapsuleMetrics.KEY_DP)
                },
            )
            addView(
                LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(
                        TextView(ctx).apply {
                            text = strings.foldRow
                            textSize = 13f
                            setTextColor(c.muted)
                        },
                    )
                    addView(
                        TextView(ctx).apply {
                            text = strings.holdHint
                            textSize = 11f
                            setTextColor(c.faint)
                            setPadding(0, ctx.dp(1), 0, 0)
                        },
                    )
                },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = ctx.dp(16)
                },
            )
            setOnClickListener { actions.mark() }
        }

    internal val body =
        object : LinearLayout(ctx) {
                private val rule =
                    Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = c.faint
                        alpha = 0x40
                        strokeWidth = ctx.dpf(1f)
                    }

                /** The timeline: from under the mark down to the last step's dot. */
                override fun dispatchDraw(canvas: Canvas) {
                    val last = stepRows.lastOrNull {
                        it.visibility == View.VISIBLE && it.parent === this
                    }
                    if (last != null)
                        canvas.drawLine(
                            gutter,
                            ctx.dpf(2f),
                            gutter,
                            last.top + last.height / 2f,
                            rule,
                        )
                    super.dispatchDraw(canvas)
                }
            }
            .apply {
                orientation = LinearLayout.VERTICAL
                setWillNotDraw(false)
                setPadding(0, 0, 0, ctx.dp(8))
                visibility = View.GONE
                addView(meta)
                stepRows.forEach { addView(it) }
                val m = ctx.dp(BODY_INSET_DP)
                addView(
                    unlocks,
                    LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                        )
                        .apply { setMargins(m, ctx.dp(8), m, 0) },
                )
                addView(
                    steer,
                    LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                        )
                        .apply { setMargins(m, ctx.dp(10), m, 0) },
                )
                addView(
                    footer,
                    LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                        )
                        .apply { topMargin = ctx.dp(4) },
                )
            }

    /** One past step: a dot on the timeline in the mark's column, the step level with the line. */
    private inner class StepRow : TextView(ctx) {
        private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = c.faint }

        init {
            textSize = 13f
            setTextColor(c.muted)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER_VERTICAL
            minHeight = ctx.dp(26)
            setPadding(column, 0, ctx.dp(16), 0)
            visibility = View.GONE
        }

        override fun onDraw(canvas: Canvas) {
            canvas.drawCircle(gutter, height / 2f, ctx.dpf(2.5f), dot)
            super.onDraw(canvas)
        }
    }

    // ---- window
    // ---------------------------------------------------------------------------------------

    val surface = OverlaySurface(ctx, c, header, body)

    /**
     * The whole capsule's opacity: the 闲置时不透明度 when idle, full otherwise. On [surface], so the
     * window's own alpha stays free for the unfold's swap.
     */
    fun setIdle(alpha: Float, animated: Boolean, durationMs: Long) =
        OverlayMotion.fadeIdle(surface, alpha, animated, durationMs)

    val root: FrameLayout =
        object : FrameLayout(ctx) {
                override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
                    if (ev.actionMasked == MotionEvent.ACTION_DOWN) actions.touched()
                    return super.dispatchTouchEvent(ev)
                }
            }
            .apply {
                setPadding(padX, padTop, padX, padBottom)
                clipToPadding = false
                clipChildren = false
                addView(
                    surface,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        Gravity.TOP or Gravity.CENTER_HORIZONTAL,
                    ),
                )
                setOnTouchListener { _, e ->
                    if (e.actionMasked == MotionEvent.ACTION_OUTSIDE && editing) actions.inputDone()
                    false
                }
            }

    lateinit var params: android.view.WindowManager.LayoutParams

    // ---- state
    // ------------------------------------------------------------------------------------------

    var shape = Shape.CAPSULE
        internal set

    internal var morph: ValueAnimator? = null
    private var state: CapsuleState? = null
    private var screenW = 0
    private var screenHdp = 800f
    internal val width = CapsuleWidth(slack = ctx.dp(24))

    /** The card's width (and the capsule's widest). */
    internal var cardW = 0

    val morphing: Boolean
        get() = morph != null

    /** A new screen size (first placement, rotation): widths again, at once. */
    fun place(screenW: Int, screenH: Int) {
        this.screenW = screenW
        screenHdp = screenH / ctx.resources.displayMetrics.density
        cardW = CapsuleMetrics.maxPx(screenW, ctx.resources.displayMetrics.density)
        surface.cardW = cardW
        cancelMorph()
        width.reset()
        state?.let { width.force(wantedWidth(it)) }
        settle(shape)
    }

    /** Shows [s]; [animated] cross-fades the line and lets the capsule follow its width. */
    fun render(s: CapsuleState, animated: Boolean, now: Long) {
        val old = state
        state = s
        val anim = animated && OverlayMotion.on()
        setLine(s.line, anim && old != null)
        lineKey.contentDescription = s.line
        mark.setLook(s.look, anim)
        lock.visibility = if (s.unlocked && !s.stopped) View.VISIBLE else View.GONE
        // The open card keeps its chevron (it closes it); a capsule that can't open hides it.
        chevron.visibility = if (s.expandable || shape == Shape.CARD) View.VISIBLE else View.GONE
        lineKey.isClickable = s.expandable || shape == Shape.CARD
        val keys = if (s.stopped) View.GONE else View.VISIBLE
        pause.visibility = keys
        keyGap.visibility = keys
        stop.visibility = keys
        pause.setGlyph(if (s.paused) Glyph.PLAY else Glyph.PAUSE, anim)
        pause.disc = if (s.paused) c.accentSoft else c.tonal
        pause.ink = if (s.paused) c.accent else c.ink
        pause.contentDescription = if (s.paused) strings.resume else strings.pause
        mark.contentDescription = strings.fold
        mark.isClickable = !s.stopped
        bindBody(s)
        // The width: a state change (stopped) at once; a new line grows now, shrinks later.
        val wanted = wantedWidth(s)
        val changed =
            if (
                old == null ||
                    old.stopped != s.stopped ||
                    old.unlocked != s.unlocked ||
                    old.expandable != s.expandable
            )
                width.force(wanted)
            else width.offer(wanted, now)
        if (shape == Shape.CAPSULE && changed && morph == null) resize(anim)
        if (shape == Shape.CARD && morph == null) refitCard(anim)
    }

    /**
     * When a narrower line may shrink the capsule (the owner re-renders then); null if nothing
     * waits.
     */
    val shrinkAt: Long?
        get() = width.shrinkAt

    private fun wantedWidth(s: CapsuleState): Int {
        val density = ctx.resources.displayMetrics.density
        val textPx = text.paint.measureText(s.line)
        val content =
            CapsuleMetrics.contentDp(
                textPx / density,
                s.unlocked && !s.stopped,
                s.stopped,
                s.expandable || shape == Shape.CARD,
            ) * density
        return CapsuleMetrics.widthPx(content, screenW, density)
    }

    private fun setLine(line: String, animated: Boolean) {
        if (text.text?.toString() == line) return
        text.animate().cancel()
        if (!animated) {
            text.text = line
            text.alpha = 1f
            return
        }
        text
            .animate()
            .alpha(0f)
            .setDuration(70)
            .setInterpolator(OverlayMotion.emphasized)
            .withEndAction {
                text.text = line
                text
                    .animate()
                    .alpha(1f)
                    .setDuration(OverlayMotion.QUICK_MS)
                    .setInterpolator(OverlayMotion.emphasized)
                    .start()
            }
            .start()
    }

    private fun bindBody(s: CapsuleState) {
        meta.text = s.meta.orEmpty()
        meta.visibility = if (s.meta.isNullOrBlank()) View.GONE else View.VISIBLE
        val n = CapsuleMetrics.steps(screenHdp)
        stepRows.forEachIndexed { i, row ->
            val step = s.steps.getOrNull(i)?.takeIf { i < n }
            row.visibility = if (step == null) View.GONE else View.VISIBLE
            row.text = step.orEmpty()
        }
        unlocks.visibility = if (s.unlocked && s.unlockedLine != null) View.VISIBLE else View.GONE
        unlockText.text = s.unlockedLine
    }

    private fun syncSend() {
        val has = !input.text.isNullOrBlank()
        send.disc = if (has) c.accent else Color.TRANSPARENT
        send.ink = if (has) c.onAccent else c.faint
        send.isEnabled = has
    }

    init {
        syncSend()
        // The field grows a line while typing: the card follows.
        body.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            if (shape == Shape.CARD && morph == null && bottom - top != oldBottom - oldTop)
                root.post { if (shape == Shape.CARD && morph == null) refitCard(false) }
        }
    }

    // ---- typing
    // ---------------------------------------------------------------------------------------

    fun startEditing() {
        editing = true
        input.isFocusableInTouchMode = true
        input.requestFocus()
    }

    fun stopEditing() {
        editing = false
        input.clearFocus()
    }

    // ---- morphs
    // ---------------------------------------------------------------------------------------

    internal fun fitWindow() {
        if (!::params.isInitialized) return
        val w = surface.extentW + 2 * padX
        val h = surface.extentH + padTop + padBottom
        if (params.width == w && params.height == h) return
        params.width = w
        params.height = h
        relayout()
    }

    companion object {
        /** The window's room round the surface for its shadow. */
        const val PAD_X_DP = 12
        const val PAD_TOP_DP = 4
        const val PAD_BOTTOM_DP = 16

        /**
         * Rows inside the card sit this far in; their corners are the card's less that (24 − 12).
         */
        const val BODY_INSET_DP = 12
        const val INNER_RADIUS_DP = 12f
    }
}
