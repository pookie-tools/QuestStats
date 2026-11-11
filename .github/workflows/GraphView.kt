package com.example.queststats

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.View

/** Small scrolling line graph (FPS by default). */
class GraphView(ctx: Context) : View(ctx) {
    private val values = ArrayList<Float>()
    var maxValue = 90f
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.GREEN; style = Paint.Style.STROKE; strokeWidth = 3f
    }
    private val grid = Paint().apply { color = Color.argb(80, 255, 255, 255); strokeWidth = 1f }

    fun push(v: Float) {
        values.add(v)
        while (values.size > 90) values.removeAt(0)
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        val w = width.toFloat()
        val h = height.toFloat()
        c.drawLine(0f, h / 2, w, h / 2, grid)
        if (values.size < 2) return
        val path = Path()
        for (i in values.indices) {
            val x = w * i / 89f
            val y = h - (values[i] / maxValue).coerceIn(0f, 1f) * h
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        c.drawPath(path, line)
    }
}
