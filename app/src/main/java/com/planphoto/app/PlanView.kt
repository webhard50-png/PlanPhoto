package com.planphoto.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import androidx.core.content.ContextCompat
import kotlin.math.hypot
import kotlin.math.min

/** PDF 페이지 비트맵을 확대/이동하며 보여주고 핀을 그린다. */
class PlanView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {

    interface Listener {
        fun onPlanTap(nx: Float, ny: Float)
        fun onPinTap(pin: Pin)
        fun onPinLongPress(pin: Pin)
        /** 방향 조절 중 손가락을 뗐을 때 */
        fun onDirectionSet(pin: Pin)
    }

    var listener: Listener? = null

    var bitmap: Bitmap? = null
        set(value) {
            field = value
            fit()
            invalidate()
        }

    var pins: List<Pin> = emptyList()
        set(value) { field = value; invalidate() }

    /** A1 좌표 격자 (null 이면 표시하지 않음) */
    var cellDims: CellGrid.Dims? = null
        set(value) { field = value; invalidate() }

    /** 위치 이동 중인 핀 (파란 테두리) */
    var highlightPinId: String? = null
        set(value) { field = value; invalidate() }

    /** 방향 조절 중인 핀: 한 손가락으로 끌면 그쪽을 향함 (두 손가락 확대는 그대로) */
    var directionPin: Pin? = null
        set(value) { field = value; invalidate() }
    private var dirDragging = false

    private var scale = 1f
    private var minScale = 1f
    private var tx = 0f
    private var ty = 0f

    private val density = resources.displayMetrics.density
    private val pinR = 17f * density
    private val bgColor = ContextCompat.getColor(ctx, R.color.plan_bg)
    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val sheetShadow = Paint().apply { color = 0x33000000 }
    private fun fit() {
        val b = bitmap ?: return
        if (width == 0 || height == 0) return
        scale = min(width / b.width.toFloat(), height / b.height.toFloat()) * 0.94f
        minScale = scale
        tx = (width - b.width * scale) / 2f
        ty = (height - b.height * scale) / 2f
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        fit()
    }

    private fun pinTip(p: Pin, b: Bitmap) = Pair(tx + p.x * b.width * scale, ty + p.y * b.height * scale)

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(bgColor)
        val b = bitmap ?: return
        val r = b.width * scale
        val bt = b.height * scale
        canvas.drawRect(tx + 3 * density, ty + 3 * density, tx + r + 3 * density, ty + bt + 3 * density, sheetShadow)
        canvas.save()
        canvas.translate(tx, ty)
        canvas.scale(scale, scale)
        canvas.drawBitmap(b, 0f, 0f, bmpPaint)
        canvas.restore()
        cellDims?.let { drawCells(canvas, b, it) }
        val coneR = pinR * 3.6f   // 방향 부채꼴 (20% 축소)
        for (p in pins) {
            val d = p.dir ?: continue
            val (x, y) = pinTip(p, b)
            PinMarker.drawCone(canvas, x, y, coneR, d, active = p.id == directionPin?.id)
        }
        for (p in pins) {
            val (x, y) = pinTip(p, b)
            PinMarker.draw(canvas, x, y, pinR, p.label, filled = p.photos.isNotEmpty(), count = p.photos.size,
                highlight = p.id == highlightPinId)
        }
    }

    private val gridLine = Paint().apply { color = 0x402F80ED; strokeWidth = 1f }
    private val labelBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xD91F2328.toInt() }
    private val labelText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt(); textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.DEFAULT_BOLD; textSize = 11f * density
    }

    /** 격자선과 칸 이름. 이름은 화면 위쪽(A, B, C…)과 왼쪽(1, 2, 3…)에 붙여 확대해도 보이게 한다 */
    private fun drawCells(canvas: Canvas, b: Bitmap, d: CellGrid.Dims) {
        val left = tx
        val top = ty
        val w = b.width * scale
        val h = b.height * scale
        val cw = w / d.cols
        val ch = h / d.rows
        // 칸이 너무 작으면 몇 칸 걸러 표시
        val minPx = 22 * density
        val stepC = kotlin.math.max(1, kotlin.math.ceil(minPx / cw).toInt())
        val stepR = kotlin.math.max(1, kotlin.math.ceil(minPx / ch).toInt())
        if (cw >= 4 * density) for (i in 1 until d.cols) {
            val x = left + i * cw
            if (x in 0f..width.toFloat()) canvas.drawLine(x, top, x, top + h, gridLine)
        }
        if (ch >= 4 * density) for (j in 1 until d.rows) {
            val y = top + j * ch
            if (y in 0f..height.toFloat()) canvas.drawLine(left, y, left + w, y, gridLine)
        }
        val band = 18 * density
        val labelY = top.coerceIn(0f, height - band)          // 위쪽 띠
        val labelX = left.coerceIn(0f, width - band * 1.6f)  // 왼쪽 띠
        canvas.drawRect(kotlin.math.max(left, 0f), labelY, kotlin.math.min(left + w, width.toFloat()), labelY + band, labelBg)
        canvas.drawRect(labelX, kotlin.math.max(top, 0f), labelX + band * 1.6f, kotlin.math.min(top + h, height.toFloat()), labelBg)
        val base = -(labelText.ascent() + labelText.descent()) / 2f
        for (i in 0 until d.cols step stepC) {
            val cx = left + (i + 0.5f) * cw
            if (cx < labelX + band * 1.6f || cx > width) continue
            canvas.drawText(CellGrid.colName(i), cx, labelY + band / 2f + base, labelText)
        }
        for (j in 0 until d.rows step stepR) {
            val cy = top + (j + 0.5f) * ch
            if (cy < labelY + band || cy > height) continue
            canvas.drawText((j + 1).toString(), labelX + band * 0.8f, cy + base, labelText)
        }
    }

    private fun hitPin(x: Float, y: Float): Pin? {
        val b = bitmap ?: return null
        for (p in pins.asReversed()) {
            val (tx0, ty0) = pinTip(p, b)
            val cy = PinMarker.headCenterY(ty0, pinR)
            if (hypot(x - tx0, y - cy) < pinR * 1.6f || hypot(x - tx0, y - ty0) < pinR * 0.9f) return p
        }
        return null
    }

    private fun clampPan() {
        val b = bitmap ?: return
        val bw = b.width * scale
        val bh = b.height * scale
        tx = tx.coerceIn(width * 0.5f - bw, width * 0.5f)
        ty = ty.coerceIn(height * 0.5f - bh, height * 0.5f)
    }

    private fun zoomAt(newScale: Float, fx: Float, fy: Float) {
        val ns = newScale.coerceIn(minScale * 0.8f, minScale * 16f)
        val f = ns / scale
        tx = fx - (fx - tx) * f
        ty = fy - (fy - ty) * f
        scale = ns
        clampPan()
        invalidate()
    }

    private val scaleDetector = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            zoomAt(scale * d.scaleFactor, d.focusX, d.focusY)
            return true
        }
    })

    private val gestures = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            if (scaleDetector.isInProgress) return false
            tx -= dx; ty -= dy
            clampPan()
            invalidate()
            return true
        }

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            val pin = hitPin(e.x, e.y)
            if (pin != null) { listener?.onPinTap(pin); return true }
            val b = bitmap ?: return false
            val nx = (e.x - tx) / (b.width * scale)
            val ny = (e.y - ty) / (b.height * scale)
            if (nx in 0f..1f && ny in 0f..1f) listener?.onPlanTap(nx, ny)
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (hitPin(e.x, e.y) != null) return false
            zoomAt(if (scale < minScale * 2f) minScale * 3f else minScale, e.x, e.y)
            if (scale == minScale) fit().also { invalidate() }
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            hitPin(e.x, e.y)?.let {
                performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                listener?.onPinLongPress(it)
            }
        }
    })

    private fun directionTouch(pin: Pin, e: MotionEvent): Boolean {
        val b = bitmap ?: return false
        if (e.pointerCount > 1 || scaleDetector.isInProgress) { dirDragging = false; return false }
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                val (x, y) = pinTip(pin, b)
                val dx = e.x - x
                val dy = e.y - y
                if (hypot(dx, dy) < pinR * 0.8f) return true
                val deg = Math.toDegrees(kotlin.math.atan2(dx.toDouble(), (-dy).toDouble())).toFloat()
                pin.dir = ((Math.round(deg / 5f) * 5f) + 360f) % 360f   // 5° 단위
                dirDragging = true
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                if (dirDragging) listener?.onDirectionSet(pin)
                dirDragging = false
            }
            MotionEvent.ACTION_CANCEL -> dirDragging = false
        }
        return true
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        directionPin?.let { if (directionTouch(it, event)) return true }
        gestures.onTouchEvent(event)
        return true
    }
}
