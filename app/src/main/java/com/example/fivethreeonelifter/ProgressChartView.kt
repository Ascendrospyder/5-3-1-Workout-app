package com.example.fivethreeonelifter

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.MotionEvent
import android.view.View
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

class ProgressChartView(context: Context, private val palette: AppPalette = AppPalette.LIGHT) : View(context) {
    var points: List<ExerciseProgressPoint> = emptyList()
        set(value) { field = value; invalidate() }
    var metric: Int = 0
        set(value) { field = value; invalidate() }
    var onSelected: ((ExerciseProgressPoint) -> Unit)? = null
    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var selected = -1
    private fun value(point: ExerciseProgressPoint): Double = when (metric) {
        1 -> point.reps.toDouble()
        2 -> point.estimatedOneRepMax
        else -> point.weight
    }
    private fun x(index: Int): Float {
        val left = 54 * density
        val available = (width - left - 16 * density).coerceAtLeast(1f)
        val span = if (points.isEmpty()) 0L else points.last().at - points.first().at
        val fraction = if (span > 0) (points[index].at - points.first().at).toDouble() / span else if (points.size > 1) index.toDouble() / (points.size - 1) else 0.5
        return left + available * fraction.toFloat()
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (points.isEmpty()) return
        val top = 16 * density
        val bottom = height - 32 * density
        val maximum = (points.maxOf { value(it) } * 1.1).coerceAtLeast(1.0)
        paint.textSize = 11 * resources.displayMetrics.scaledDensity
        paint.strokeWidth = density
        paint.style = Paint.Style.FILL
        repeat(4) { index ->
            val y = bottom - (bottom - top) * index / 3f
            paint.color = palette.border
            canvas.drawLine(54 * density, y, width - 16 * density, y, paint)
            paint.color = palette.muted
            canvas.drawText(String.format(Locale.US, "%.0f", maximum * index / 3), 3 * density, y + 4 * density, paint)
        }
        val path = Path()
        points.forEachIndexed { index, point ->
            val y = bottom - (bottom - top) * (value(point) / maximum).toFloat()
            if (index == 0) path.moveTo(x(index), y) else path.lineTo(x(index), y)
        }
        paint.color = palette.primary
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2 * density
        canvas.drawPath(path, paint)
        paint.style = Paint.Style.FILL
        points.forEachIndexed { index, point ->
            canvas.drawCircle(x(index), bottom - (bottom - top) * (value(point) / maximum).toFloat(),
                (if (index == selected) 6 else 3) * density, paint)
        }
        paint.color = palette.muted
        val format = DateTimeFormatter.ofPattern("d MMM yy").withZone(ZoneId.systemDefault())
        canvas.drawText(format.format(Instant.ofEpochMilli(points.first().at)), 54 * density, height - 8 * density, paint)
        if (points.size > 1) {
            val last = format.format(Instant.ofEpochMilli(points.last().at))
            canvas.drawText(last, width - 16 * density - paint.measureText(last), height - 8 * density, paint)
        }
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (points.isEmpty()) return false
        if (event.action == MotionEvent.ACTION_DOWN) return true
        if (event.action == MotionEvent.ACTION_UP) {
            selected = points.indices.minByOrNull { kotlin.math.abs(x(it) - event.x) } ?: -1
            if (selected >= 0) onSelected?.invoke(points[selected])
            invalidate()
            performClick()
            return true
        }
        return super.onTouchEvent(event)
    }
    override fun performClick(): Boolean { super.performClick(); return true }
}
