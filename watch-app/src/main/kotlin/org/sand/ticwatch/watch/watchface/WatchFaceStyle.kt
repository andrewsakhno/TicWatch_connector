package org.sand.ticwatch.watch.watchface

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF

/**
 * Colours of one draw mode. Ambient is dim, grey and background-free to stay within the
 * always-on display's power and burn-in limits.
 */
data class WatchFacePalette(
    val backgroundCenter: Int,
    val backgroundEdge: Int,
    val minuteTick: Int,
    val hourTick: Int,
    val time: Int,
    val date: Int,
    val label: Int,
    val heart: Int,
    val thermometer: Int,
    val divider: Int,
    val glyphStyle: Paint.Style,
) {
    companion object {
        val INTERACTIVE = WatchFacePalette(
            backgroundCenter = Color.parseColor("#1B2340"),
            backgroundEdge = Color.parseColor("#05060A"),
            minuteTick = Color.parseColor("#2E3655"),
            hourTick = Color.parseColor("#8C9BC8"),
            time = Color.WHITE,
            date = Color.parseColor("#3DDCFF"),
            label = Color.parseColor("#8C9BC8"),
            heart = Color.parseColor("#FF4D6D"),
            thermometer = Color.parseColor("#FFB547"),
            divider = Color.parseColor("#2E3655"),
            glyphStyle = Paint.Style.FILL,
        )

        val AMBIENT = WatchFacePalette(
            backgroundCenter = Color.BLACK,
            backgroundEdge = Color.BLACK,
            minuteTick = Color.BLACK,
            hourTick = Color.parseColor("#5A5A5A"),
            time = Color.parseColor("#D0D0D0"),
            date = Color.parseColor("#8A8A8A"),
            label = Color.parseColor("#6A6A6A"),
            heart = Color.parseColor("#9A9A9A"),
            thermometer = Color.parseColor("#9A9A9A"),
            divider = Color.BLACK,
            glyphStyle = Paint.Style.STROKE,
        )
    }
}

/** Vector glyphs for the readings, drawn into a square of side `size` centred on (x, y). */
object ReadingGlyphs {

    private val heart = Path().apply {
        moveTo(0f, 0.38f)
        cubicTo(-0.62f, -0.05f, -0.38f, -0.62f, 0f, -0.26f)
        cubicTo(0.38f, -0.62f, 0.62f, -0.05f, 0f, 0.38f)
        close()
    }

    private val thermometer = Path().apply {
        addRoundRect(RectF(-0.12f, -0.5f, 0.12f, 0.2f), 0.12f, 0.12f, Path.Direction.CW)
        addCircle(0f, 0.3f, 0.2f, Path.Direction.CW)
    }

    fun drawHeart(canvas: Canvas, x: Float, y: Float, size: Float, paint: Paint) = draw(canvas, heart, x, y, size, paint)

    fun drawThermometer(canvas: Canvas, x: Float, y: Float, size: Float, paint: Paint) =
        draw(canvas, thermometer, x, y, size, paint)

    private fun draw(canvas: Canvas, glyph: Path, x: Float, y: Float, size: Float, paint: Paint) {
        canvas.save()
        canvas.translate(x, y)
        canvas.scale(size, size)
        // Stroke width is in unit-square space once scaled.
        val strokeWidth = paint.strokeWidth
        paint.strokeWidth = strokeWidth / size
        canvas.drawPath(glyph, paint)
        paint.strokeWidth = strokeWidth
        canvas.restore()
    }
}
