package dev.fughetta.dshcompose.phone.a11y

import android.content.Context
import android.graphics.Canvas
import android.graphics.Outline
import android.graphics.RectF
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import kotlin.math.roundToInt

/**
 * One rounded rect of solid surface ([rect], radius 24 dp) that the morphs animate, with the header
 * and the card body inside, cut to its outline. The view itself is [extentW] × [extentH] (the
 * window's content); the rect moves inside it. The header follows the rect's width, or stays where
 * [pin] put it while a fold cuts it; the body is laid out once at the card's width.
 */
internal class OverlaySurface(
    context: Context,
    private val c: OverlayPalette,
    private val header: View,
    private val body: View,
) : ViewGroup(context) {
    private val painter = SurfacePainter(context)
    private val radius = context.dpf(CapsuleMetrics.RADIUS_DP.toFloat())
    private val headerH = context.dp(CapsuleMetrics.HEIGHT_DP)

    /**
     * The body starts this far up into the header's lower margin: its first line reads as the
     * line's second.
     */
    private val overlap = context.dp(6)

    val rect = RectF()
    private var pinned: RectF? = null

    var extentW = 0
        private set

    var extentH = 0
        private set

    /** The body's width: the card's. */
    var cardW = 0

    init {
        setWillNotDraw(false)
        clipChildren = true
        clipToOutline = true
        outlineProvider =
            object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(
                        rect.left.roundToInt(),
                        rect.top.roundToInt(),
                        rect.right.roundToInt(),
                        rect.bottom.roundToInt(),
                        radius,
                    )
                }
            }
        softShadow(c, 6f)
        addView(header)
        addView(body)
    }

    fun setExtent(w: Int, h: Int) {
        if (w == extentW && h == extentH) return
        extentW = w
        extentH = h
        requestLayout()
    }

    fun setRect(r: RectF) = setRect(r.left, r.top, r.right, r.bottom)

    fun setRect(l: Float, t: Float, r: Float, b: Float) {
        val widthChanged = (r - l).roundToInt() != rect.width().roundToInt()
        rect.set(l, t, r, b)
        if (pinned == null) {
            // Laying the header out here, between traversals, clears its pending layout request:
            // measure it first if one is pending (a lock shown, a key hidden), or the next pass
            // would keep the stale measurement.
            if (widthChanged || header.isLayoutRequested) measureHeader()
            layoutHeader()
        }
        invalidateOutline()
        invalidate()
    }

    /** Holds the header at [at] (a fold cuts it there) or lets it follow the rect again (null). */
    fun pin(at: RectF?) {
        pinned = at
        measureHeader()
        layoutHeader()
    }

    /** The card's height for the body as it is now. */
    fun cardHeight(): Int {
        body.measure(
            MeasureSpec.makeMeasureSpec(cardW, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
        )
        return headerH - overlap + body.measuredHeight
    }

    private fun headerRect(): RectF = pinned ?: rect

    private fun measureHeader() {
        val w = headerRect().width().roundToInt().coerceAtLeast(0)
        header.measure(
            MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(headerH, MeasureSpec.EXACTLY),
        )
    }

    private fun layoutHeader() {
        val r = headerRect()
        val l = r.left.roundToInt()
        val t = r.top.roundToInt()
        header.layout(l, t, l + header.measuredWidth, t + headerH)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        measureHeader()
        if (body.visibility != View.GONE) {
            body.measure(
                MeasureSpec.makeMeasureSpec(cardW, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
            )
        }
        setMeasuredDimension(extentW, extentH)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        layoutHeader()
        if (body.visibility != View.GONE) {
            val left = (extentW - cardW) / 2
            val top = headerH - overlap
            body.layout(left, top, left + body.measuredWidth, top + body.measuredHeight)
        }
    }

    override fun onDraw(canvas: Canvas) {
        painter.draw(canvas, rect, radius, c)
    }
}
