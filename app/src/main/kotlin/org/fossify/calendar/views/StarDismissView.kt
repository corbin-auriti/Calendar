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
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.animation.LinearInterpolator

class StarDismissView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {
    companion object {
        private const val STAR_POINTS = 5
        private const val VERTEX_COUNT = STAR_POINTS * 2
        private const val DEFAULT_STAR_COLOR = 0xFFFFD54A.toInt()
        private const val DEFAULT_RANGE_COLOR = 0x22FFFFFF
        private const val INNER_RADIUS_RATIO = 0.52f
        private const val CORNER_CUT_RATIO = 0.72f
        private const val MAX_CORNER_CUT_EDGE_RATIO = 0.48f
        private const val RANGE_RADIUS_RATIO = 1f
        private const val STAR_RADIUS_RATIO = 0.3f
        private const val BOB_AMPLITUDE_RATIO = 0.035f
        private const val MORPH_AMPLITUDE = 0.11f
        private const val PHASE_STEP_PER_VERTEX = 0.7f
        private const val CYCLE_DURATION_MS = 1300L
        private const val DEGREES_PER_CYCLE = 72f
        private const val DRAG_SCALE = 0.85f
        private const val VISUAL_CLAMP_RATIO = 0.52f
        private const val DISMISS_RATIO = 0.58f
        private const val SPRING_DECAY_PER_FRAME = 0.82f
        private const val SPRING_DURATION_MS = 260L
        private const val DISMISS_DURATION_MS = 200L
        private const val FULL_ALPHA = 255
    }

    var dismissal: (() -> Unit)? = null

    private val rangePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = DEFAULT_RANGE_COLOR
        style = Paint.Style.FILL
    }
    private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = DEFAULT_STAR_COLOR
        style = Paint.Style.FILL
    }
    private val starPath = Path()
    private val vertexCoordinates = FloatArray(VERTEX_COUNT * 2)

    private var animatorPhase = 0f
    private var bobOffset = 0f
    private var dragOffsetX = 0f
    private var dragOffsetY = 0f
    private var isDragging = false
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var centerX = 0f
    private var centerY = 0f
    private var rangeRadius = 0f
    private var starRadius = 0f
    private var maxDragDistance = 0f
    private var startedAtMs = 0L

    private val idleAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = CYCLE_DURATION_MS
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
        startedAtMs = System.currentTimeMillis()
        idleAnimator.start()
    }

    override fun onDetachedFromWindow() {
        idleAnimator.cancel()
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        centerX = w / 2f
        centerY = h / 2f
        rangeRadius = minOf(centerX, centerY) * RANGE_RADIUS_RATIO
        starRadius = rangeRadius * STAR_RADIUS_RATIO
        maxDragDistance = rangeRadius * DISMISS_RATIO
    }

    private fun currentBobOffset() =
        (Math.sin((animatorPhase * Math.PI * 2).toDouble()) * rangeRadius * BOB_AMPLITUDE_RATIO).toFloat()

    private fun currentRotationDegrees() =
        ((System.currentTimeMillis() - startedAtMs).toFloat() / CYCLE_DURATION_MS) * DEGREES_PER_CYCLE

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                isDragging = true
                lastTouchX = event.x
                lastTouchY = event.y
                parent?.requestDisallowInterceptTouchEvent(true)
            }

            MotionEvent.ACTION_MOVE -> {
                dragOffsetX += (event.x - lastTouchX) * DRAG_SCALE
                dragOffsetY += (event.y - lastTouchY) * DRAG_SCALE
                lastTouchX = event.x
                lastTouchY = event.y
                if (currentDragDistance() > maxDragDistance) {
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

    private fun currentDragDistance() =
        Math.hypot(dragOffsetX.toDouble(), dragOffsetY.toDouble()).toFloat()

    private fun springBack() {
        ValueAnimator.ofFloat(1f, 0f).apply {
            duration = SPRING_DURATION_MS
            addUpdateListener {
                val fraction = SPRING_DECAY_PER_FRAME + (it.animatedValue as Float) * (1f - SPRING_DECAY_PER_FRAME)
                dragOffsetX *= fraction
                dragOffsetY *= fraction
                invalidate()
            }
            start()
        }
    }

    private fun animateDismissal() {
        val startX = dragOffsetX
        val startY = dragOffsetY
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = DISMISS_DURATION_MS
            addUpdateListener {
                val fraction = it.animatedValue as Float
                dragOffsetX = startX * (1f + fraction)
                dragOffsetY = startY * (1f + fraction)
                bodyPaint.alpha = (FULL_ALPHA * (1f - fraction)).toInt()
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    dragOffsetX = 0f
                    dragOffsetY = 0f
                    bodyPaint.alpha = FULL_ALPHA
                    dismissal?.invoke()
                }
            })
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val wobblePhase = (animatorPhase * Math.PI * 2).toFloat()

        canvas.drawCircle(centerX, centerY, rangeRadius, rangePaint)

        val clampedDrag = clampedDrag()
        canvas.save()
        canvas.translate(clampedDrag.first, bobOffset + clampedDrag.second)
        canvas.rotate(currentRotationDegrees(), centerX, centerY)
        buildStarPath(starPath, centerX, centerY, starRadius, wobblePhase)
        canvas.drawPath(starPath, bodyPaint)
        canvas.restore()
    }

    private fun clampedDrag(): Pair<Float, Float> {
        val maxOffset = rangeRadius * VISUAL_CLAMP_RATIO
        val distance = currentDragDistance()
        if (distance <= maxOffset || distance == 0f) {
            return Pair(dragOffsetX, dragOffsetY)
        }
        val scale = maxOffset / distance
        return Pair(dragOffsetX * scale, dragOffsetY * scale)
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

            val edgeBefore = Math.hypot(
                (currentX - previousX).toDouble(),
                (currentY - previousY).toDouble()
            ).toFloat()
            val edgeAfter = Math.hypot(
                (nextX - currentX).toDouble(),
                (nextY - currentY).toDouble()
            ).toFloat()
            val limitedCornerCut = minOf(
                cornerCut,
                edgeBefore * MAX_CORNER_CUT_EDGE_RATIO,
                edgeAfter * MAX_CORNER_CUT_EDGE_RATIO
            )
            val towardsBeforeX = currentX - normalizeX(currentX - previousX, currentY - previousY) * limitedCornerCut
            val towardsBeforeY = currentY - normalizeY(currentX - previousX, currentY - previousY) * limitedCornerCut
            val towardsAfterX = currentX + normalizeX(nextX - currentX, nextY - currentY) * limitedCornerCut
            val towardsAfterY = currentY + normalizeY(nextX - currentX, nextY - currentY) * limitedCornerCut

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

    private fun normalizeX(dx: Float, dy: Float) =
        (dx / Math.hypot(dx.toDouble(), dy.toDouble())).toFloat()

    private fun normalizeY(dx: Float, dy: Float) =
        (dy / Math.hypot(dx.toDouble(), dy.toDouble())).toFloat()

    fun setStarColor(color: Int) {
        bodyPaint.color = color
        invalidate()
    }

    fun setRangeCircleColor(color: Int) {
        rangePaint.color = color
        invalidate()
    }
}
