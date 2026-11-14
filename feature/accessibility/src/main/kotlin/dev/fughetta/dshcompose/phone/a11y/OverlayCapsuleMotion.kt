package dev.fughetta.dshcompose.phone.a11y

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.graphics.RectF
import android.view.View
import android.view.animation.Interpolator
import dev.fughetta.dshcompose.phone.a11y.OverlayCapsule.Shape
import kotlin.math.roundToInt

/** The steady layout of [to]: extent, rect, what shows, the window's size. */
internal fun OverlayCapsule.settle(to: Shape) {
    morph = null
    surface.pin(null)
    header.alpha = 1f
    shape = to
    val capW = width.width.takeIf { it > 0 } ?: cardW
    when (to) {
        Shape.CAPSULE -> {
            body.visibility = View.GONE
            body.alpha = 0f
            body.translationY = 0f
            chevron.turn = 0f
            surface.setExtent(capW, headerH)
            surface.setRect(0f, 0f, capW.toFloat(), headerH.toFloat())
        }
        Shape.CARD -> {
            body.visibility = View.VISIBLE
            body.alpha = 1f
            body.translationY = 0f
            chevron.turn = 180f
            val h = surface.cardHeight()
            surface.setExtent(cardW, h)
            surface.setRect(0f, 0f, cardW.toFloat(), h.toFloat())
        }
    }
    fitWindow()
}

/** Ends a morph where it stands and lays out the form it was in. */
internal fun OverlayCapsule.cancelMorph() {
    val m = morph
    morph = null
    m?.cancel()
    settle(shape)
}

/** The capsule grows into the card: down and out, the body fading in behind. */
internal fun OverlayCapsule.expand(animated: Boolean, then: () -> Unit = {}) {
    val from = steadyRectInCard()
    morph?.let {
        it.cancel()
        morph = null
    }
    // From a collapse cut short, carry on from where the body and the chevron are.
    val wasOpen = body.visibility == View.VISIBLE
    val startAlpha = if (wasOpen) body.alpha else 0f
    val startTurn = if (wasOpen) chevron.turn else 0f
    shape = Shape.CARD
    body.alpha = startAlpha
    body.visibility = View.VISIBLE
    val h = surface.cardHeight()
    val start = if (wasOpen && surface.extentW == cardW) RectF(surface.rect) else from
    surface.setExtent(cardW, h)
    surface.setRect(start)
    fitWindow()
    val shift = ctx.dpf(-6f)
    animate(
        RectF(0f, 0f, cardW.toFloat(), h.toFloat()),
        OverlayMotion.MORPH_MS,
        OverlayMotion.emphasized,
        animated,
        frame = { t ->
            val k = ((t - 0.3f) / 0.7f).coerceIn(0f, 1f)
            body.alpha = startAlpha + (1f - startAlpha) * k
            body.translationY = shift * (1f - k) * (1f - startAlpha)
            chevron.turn = startTurn + (180f - startTurn) * t
        },
    ) {
        settle(Shape.CARD)
        then()
    }
}

/** The card shrinks back into the capsule, the body fading first. */
internal fun OverlayCapsule.collapse(animated: Boolean, then: () -> Unit = {}) {
    if (shape != Shape.CARD) {
        then()
        return
    }
    morph?.let {
        it.cancel()
        morph = null
    }
    val capW = width.width.takeIf { it > 0 } ?: cardW
    val left = (surface.extentW - capW) / 2f
    val startAlpha = body.alpha
    animate(
        RectF(left, 0f, left + capW, headerH.toFloat()),
        OverlayMotion.MORPH_MS,
        OverlayMotion.emphasized,
        animated,
        frame = { t ->
            body.alpha = startAlpha * (1f - t / 0.4f).coerceIn(0f, 1f)
            chevron.turn = 180f * (1f - t)
        },
    ) {
        settle(Shape.CAPSULE)
        then()
    }
}

/** The capsule follows a new width, centred, its keys travelling with its end. */
internal fun OverlayCapsule.resize(animated: Boolean) {
    val to = width.width
    val from = surface.rect.width().roundToInt()
    if (to == from || to <= 0) return
    val extent = maxOf(to, from)
    val start = (extent - from) / 2f
    surface.setExtent(extent, headerH)
    surface.setRect(start, 0f, start + from, headerH.toFloat())
    fitWindow()
    val left = (extent - to) / 2f
    animate(
        RectF(left, 0f, left + to, headerH.toFloat()),
        OverlayMotion.RESIZE_MS,
        OverlayMotion.glide,
        animated,
    ) {
        settle(Shape.CAPSULE)
    }
}

/**
 * The open card's height follows its content (a step more, a lifted limit, a second line typed).
 */
internal fun OverlayCapsule.refitCard(animated: Boolean) {
    val h = surface.cardHeight()
    if (h == surface.rect.height().roundToInt() && surface.extentW == cardW) return
    val from = RectF(surface.rect)
    surface.setExtent(cardW, maxOf(h, from.bottom.roundToInt()))
    fitWindow()
    animate(
        RectF(0f, 0f, cardW.toFloat(), h.toFloat()),
        OverlayMotion.RESIZE_MS,
        OverlayMotion.glide,
        animated,
    ) {
        settle(Shape.CARD)
    }
}

/** The end circle on [side] of the surface now, in the surface's own coordinates (left, top). */
internal fun OverlayCapsule.endCircle(side: Side): Pair<Int, Int> {
    val r = surface.rect
    return BallGeometry.circleLeft(r.left.roundToInt(), r.right.roundToInt(), ball, side) to
        r.top.roundToInt()
}

/** The end circle a steady capsule would have on [side] (left, top in the surface). */
internal fun OverlayCapsule.capsuleEndCircle(side: Side): Pair<Int, Int> {
    val capW = width.width.takeIf { it > 0 } ?: cardW
    val left = (surface.extentW - capW) / 2
    return BallGeometry.circleLeft(left, left + capW, ball, side) to 0
}

/**
 * Folding: the surface narrows (and, from the card, shortens) to the circle at its end on [side],
 * the rows pinned where they were, fading in the first 40 %. [then] when it is the ball's size and
 * place.
 */
internal fun OverlayCapsule.foldTo(side: Side, animated: Boolean, then: () -> Unit) {
    morph?.let {
        it.cancel()
        morph = null
    }
    val r = RectF(surface.rect)
    surface.pin(RectF(r.left, r.top, r.right, r.top + headerH))
    val left =
        BallGeometry.circleLeft(r.left.roundToInt(), r.right.roundToInt(), ball, side).toFloat()
    val bodyAlpha = body.alpha
    animate(
        RectF(left, r.top, left + ball, r.top + headerH),
        OverlayMotion.MORPH_MS,
        OverlayMotion.emphasized,
        animated,
        frame = { t ->
            header.alpha = (1f - t / 0.4f).coerceIn(0f, 1f)
            body.alpha = bodyAlpha * (1f - t / 0.3f).coerceIn(0f, 1f)
        },
    ) {
        then()
    }
}

/**
 * The capsule as the circle on [side], its row hidden: what [unfoldFrom] starts from. The owner
 * shows the window like this (transparent) while the ball is still on its way, so the window has
 * drawn before the swap and the morph starts on its first frame.
 */
internal fun OverlayCapsule.prepareUnfold(side: Side) {
    morph?.let {
        it.cancel()
        morph = null
    }
    settle(Shape.CAPSULE)
    val full = RectF(surface.rect)
    surface.pin(RectF(full))
    val left =
        BallGeometry.circleLeft(full.left.roundToInt(), full.right.roundToInt(), ball, side)
            .toFloat()
    surface.setRect(left, 0f, left + ball, headerH.toFloat())
    header.alpha = 0f
}

/**
 * Unfolding: from the circle on [side] ([prepareUnfold]) the capsule grows out, its row fading in
 * over the last half.
 */
internal fun OverlayCapsule.unfoldFrom(side: Side, animated: Boolean, then: () -> Unit) {
    if (morph != null || shape != Shape.CAPSULE || surface.rect.width().roundToInt() != ball)
        prepareUnfold(side)
    val capW = width.width.takeIf { it > 0 } ?: cardW
    val full = RectF(0f, 0f, capW.toFloat(), headerH.toFloat())
    animate(
        full,
        OverlayMotion.MORPH_MS,
        OverlayMotion.emphasized,
        animated,
        frame = { t -> header.alpha = ((t - 0.5f) / 0.5f).coerceIn(0f, 1f) },
    ) {
        settle(Shape.CAPSULE)
        then()
    }
}

internal fun OverlayCapsule.steadyRectInCard(): RectF {
    val capW = width.width.takeIf { it > 0 } ?: cardW
    val left = (cardW - capW) / 2f
    return RectF(left, 0f, left + capW, headerH.toFloat())
}

internal fun OverlayCapsule.animate(
    to: RectF,
    ms: Long,
    curve: Interpolator,
    animated: Boolean,
    frame: ((Float) -> Unit)? = null,
    then: () -> Unit,
) {
    val from = RectF(surface.rect)
    if (!animated || !OverlayMotion.on()) {
        surface.setRect(to)
        frame?.invoke(1f)
        then()
        return
    }
    val now = RectF()
    morph =
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = ms
            interpolator = curve
            addUpdateListener { a ->
                val t = a.animatedValue as Float
                now.set(
                    from.left + (to.left - from.left) * t,
                    from.top + (to.top - from.top) * t,
                    from.right + (to.right - from.right) * t,
                    from.bottom + (to.bottom - from.bottom) * t,
                )
                surface.setRect(now)
                frame?.invoke(t)
            }
            addListener(
                object : AnimatorListenerAdapter() {
                    private var cancelled = false

                    override fun onAnimationCancel(animation: Animator) {
                        cancelled = true
                    }

                    override fun onAnimationEnd(animation: Animator) {
                        if (cancelled) return
                        if (morph === animation) morph = null
                        then()
                    }
                },
            )
            start()
        }
}
