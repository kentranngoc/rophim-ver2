package com.rophim.tv

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.View

/** Con trỏ chuột ảo, điều khiển bằng phím mũi tên của remote, tự ẩn khi không dùng. */
class CursorView(context: Context) : View(context) {
    var cx = 0f
    var cy = 0f
    var cursorVisible = true
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF000000.toInt(); style = Paint.Style.STROKE; strokeWidth = 3f
    }

    init {
        isFocusable = false
        isClickable = false
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        if (cx == 0f && cy == 0f) { cx = w / 2f; cy = h / 2f }
        cx = cx.coerceIn(0f, w.toFloat()); cy = cy.coerceIn(0f, h.toFloat())
    }

    override fun onDraw(c: Canvas) {
        if (!cursorVisible) return
        val p = Path().apply {
            moveTo(cx, cy); lineTo(cx, cy + 34f); lineTo(cx + 9f, cy + 26f)
            lineTo(cx + 17f, cy + 42f); lineTo(cx + 24f, cy + 38f)
            lineTo(cx + 16f, cy + 23f); lineTo(cx + 28f, cy + 23f); close()
        }
        c.drawPath(p, fill)
        c.drawPath(p, stroke)
    }
}
