package org.fossify.calendar.views

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.animation.LinearInterpolator
import org.fossify.commons.extensions.adjustAlpha

class StarDismissView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {
    companion object {
        private const val STAR_POINTS = 5
        private const val VERTEX_COUNT = STAR_POINTS * 2
        private const val INNER_RADIUS_RATIO = 0.5f
        private const val CORNER_CUT_RATIO = 0.35f
        private const val BOB_AMPLITUDE_RATIO = 0.035f
        private const val MORPH_AMPLITUDE = 0.06f
        private const val PHASE_STEP_PER_VERTEX = 0.7f
        private const val MAX_ROTATION_DEGREES = 14f
        private const val ANIMATION_DURATION_MS = 5200L
        private const val DRAG_SCALE = 0.55f
        private const val DISMISS_DRAG_RATIO = 0.28f
        private const val SPRING_DURATION_MS = 280L
        private const val DISMISS_DURATION_MS = 220L
        private const val FULL_ALPHA = 255
        private const val GLOW_RADIUS_RATIO = 1.7f
        private const val GLOW_ALPHA = 0.28f
    }

    var dismissal: (() -> Unit)? = null

    private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFD54A")
        style = Paint.Style.FILL
    }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val starPath = Path()
    private val vertexCoordinates = FloatArray(VERTEX_COUNT * 2)

    private var animatorPhase = 0f
    private var bobOffset = 0f
    private var dragOffsetX = 0f
    private var isDragging = false
    private var lastTouchX = 0f
    private var halfParentWidth = 0f

    private val idleAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = ANIMATION_DURATION_MS
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            animatorPhase = it.animatedValue as Float
            bobOffset = currentBobOffset()
            if (!isDragging) {
                invalidate()
            }
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        idleAnimator.start()
    }

    override fun onDetachedFromWindow() {
        idleAnimator.cancel()
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        halfParentWidth = (parent as? View)?.width?.div(2f) ?: (w * DISMISS_DRAG_RATIO * 2)
    }

    private fun currentBobOffset() =
        (Math.sin((animatorPhase * Math.PI * 2).toDouble()) * height * BOB_AMPLITUDE_RATIO).toFloat()

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                isDragging = true
                lastTouchX = event.x
                parent?.requestDisallowInterceptTouchEvent(true)
            }

            MotionEvent.ACTION_MOVE -> {
                dragOffsetX += (event.x - lastTouchX) * DRAG_SCALE
                lastTouchX = event.x
                if (Math.abs(dragOffsetX) > halfParentWidth * DISMISS_DRAG_RATIO) {
                    isDragging = false
                    animateDismissal()
                } else {
                    invalidate()
                }
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isDragging = false
                parent?.requestDisallowInterceptTouchEvent(false)
                springBack()
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun springBack() {
        ValueAnimator.ofFloat(dragOffsetX, 0f).apply {
            duration = SPRING_DURATION_MS
            addUpdateListener {
                dragOffsetX = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun animateDismissal() {
        val startOffset = dragOffsetX
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = DISMISS_DURATION_MS
            addUpdateListener {
                val fraction = it.animatedValue as Float
                dragOffsetX = startOffset * (1f + fraction)
                bodyPaint.alpha = (FULL_ALPHA * (1f - fraction)).toInt()
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    dragOffsetX = 0f
                    bodyPaint.alpha = FULL_ALPHA
                    dismissal?.invoke()
                }
            })
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val centerX = width / 2f
        val centerY = height / 2f
        val starRadius = minOf(centerX, centerY) / (1f + INNER_RADIUS_RATIO)

        val wobblePhase = (animatorPhase * Math.PI * 2).toFloat()
        val rotation = Math.sin(animatorPhase * Math.PI).toFloat() * MAX_ROTATION_DEGREES
        val glowCenterX = centerX + dragOffsetX
        val glowCenterY = centerY + bobOffset

        glowPaint.shader = RadialGradient(
            glowCenterX,
            glowCenterY,
            starRadius * GLOW_RADIUS_RATIO,
            bodyPaint.color.adjustAlpha(GLOW_ALPHA),
            Color.TRANSPARENT,
            Shader.TileMode.CLAMP
        )

        canvas.save()
        canvas.translate(0f, bobOffset)
        canvas.drawCircle(glowCenterX, centerY, starRadius * GLOW_RADIUS_RATIO, glowPaint)
        canvas.rotate(rotation, glowCenterX, centerY)
        buildStarPath(starPath, glowCenterX, centerY, starRadius, wobblePhase)
        canvas.drawPath(starPath, bodyPaint)
        canvas.restore()
    }

    private fun buildStarPath(path: Path, cx: Float, cy: Float, radius: Float, wobblePhase: Float) {
        updateVertices(cx, cy, radius, wobblePhase)

        path.reset()
        val cornerCut = radius * CORNER_CUT_RATIO
        var previousVertex = VERTEX_COUNT - 1
        for (i in 0 until VERTEX_COUNT) {
            val previousX = vertexCoordinates[previousVertex * 2]
            val previousY = vertexCoordinates[previousVertex * 2 + 1]
            val currentX = vertexCoordinates[i * 2]
            val currentY = vertexCoordinates[i * 2 + 1]
            val nextX = vertexCoordinates[(i + 1) % VERTEX_COUNT * 2]
            val nextY = vertexCoordinates[((i + 1) % VERTEX_COUNT * 2) + 1]

            val towardsBeforeX = currentX - normalizeX(currentX - previousX, currentY - previousY) * cornerCut
            val towardsBeforeY = currentY - normalizeY(currentX - previousX, currentY - previousY) * cornerCut
            val towardsAfterX = currentX + normalizeX(nextX - currentX, nextY - currentY) * cornerCut
            val towardsAfterY = currentY + normalizeY(nextX - currentX, nextY - currentY) * cornerCut

            if (i == 0) {
                path.moveTo(towardsBeforeX, towardsBeforeY)
            } else {
                path.lineTo(towardsBeforeX, towardsBeforeY)
            }
            path.quadTo(currentX, currentY, towardsAfterX, towardsAfterY)

            previousVertex = i
        }
        path.close()
    }

    private fun updateVertices(cx: Float, cy: Float, radius: Float, wobblePhase: Float) {
        for (i in 0 until VERTEX_COUNT) {
            val angle = -Math.PI / 2 + i * Math.PI / STAR_POINTS
            val morph = 1f + MORPH_AMPLITUDE *
                Math.sin((wobblePhase + i * PHASE_STEP_PER_VERTEX).toDouble()).toFloat()
            val vertexRadius =
                if (i % 2 == 0) radius * morph else radius * INNER_RADIUS_RATIO * morph
            vertexCoordinates[i * 2] = cx + (vertexRadius * Math.cos(angle)).toFloat()
            vertexCoordinates[i * 2 + 1] = cy + (vertexRadius * Math.sin(angle)).toFloat()
        }
    }

    private fun normalizeX(dx: Float, dy: Float) = (dx / Math.hypot(dx.toDouble(), dy.toDouble())).toFloat()

    private fun normalizeY(dx: Float, dy: Float) = (dy / Math.hypot(dx.toDouble(), dy.toDouble())).toFloat()

    fun setStarColor(color: Int) {
        bodyPaint.color = color
        invalidate()
    }
}
