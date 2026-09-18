package com.example.fivethreeonelifter

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import kotlin.math.min

class HeatmapView(context: Context) : View(context) {
    var counts: Map<LocalDate, Int> = emptyMap()
        set(value) {
            field = value
            invalidate()
        }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(105, 111, 128)
        textSize = 11f * resources.displayMetrics.scaledDensity
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desiredHeight = (142 * resources.displayMetrics.density).toInt()
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), resolveSize(desiredHeight, heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val density = resources.displayMetrics.density
        val left = 30f * density
        val top = 12f * density
        val gap = 3f * density
        val columns = 12
        val rows = 7
        val usable = width - left - 6f * density
        val cell = min((usable - gap * (columns - 1)) / columns, 15f * density)

        val today = LocalDate.now()
        val thisMonday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val firstMonday = thisMonday.minusWeeks((columns - 1).toLong())

        listOf("M", "W", "F", "S").forEachIndexed { i, label ->
            val row = when (i) { 0 -> 0; 1 -> 2; 2 -> 4; else -> 6 }
            canvas.drawText(label, 5f * density, top + row * (cell + gap) + cell * 0.78f, labelPaint)
        }

        for (week in 0 until columns) {
            for (day in 0 until rows) {
                val date = firstMonday.plusWeeks(week.toLong()).plusDays(day.toLong())
                val count = if (date.isAfter(today)) 0 else (counts[date] ?: 0)
                paint.color = when {
                    date.isAfter(today) -> Color.TRANSPARENT
                    count <= 0 -> Color.rgb(232, 233, 241)
                    count == 1 -> Color.rgb(206, 198, 255)
                    count == 2 -> Color.rgb(139, 123, 238)
                    else -> Color.rgb(93, 79, 219)
                }
                val x = left + week * (cell + gap)
                val y = top + day * (cell + gap)
                canvas.drawRoundRect(x, y, x + cell, y + cell, 4f * density, 4f * density, paint)
            }
        }
    }
}
