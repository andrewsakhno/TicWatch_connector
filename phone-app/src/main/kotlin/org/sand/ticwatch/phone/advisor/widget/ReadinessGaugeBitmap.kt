package org.sand.ticwatch.phone.advisor.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import org.sand.ticwatch.phone.R
import org.sand.ticwatch.phone.advisor.ReadinessGaugeArc

/** A 0..100 ring of the widget's gauge; a null [score] leaves it empty. */
data class WidgetGaugeRing(val score: Int?, val argb: Int)

/** Paints the Advisor's three-ring gauge (outermost first) into a bitmap, since [android.widget.RemoteViews] can't host a custom drawing. */
class ReadinessGaugeBitmap(private val context: Context) {

    fun draw(rings: List<WidgetGaugeRing>): Bitmap {
        val resources = context.resources
        val size = resources.getDimensionPixelSize(R.dimen.advisor_widget_gauge_size)
        val stroke = resources.getDimension(R.dimen.advisor_widget_gauge_stroke)
        val gap = resources.getDimension(R.dimen.advisor_widget_gauge_ring_gap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeWidth = stroke
        }
        val track = context.getColor(R.color.advisor_widget_gauge_track)
        return Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also { bitmap ->
            val canvas = Canvas(bitmap)
            rings.forEachIndexed { index, ring ->
                val inset = stroke / 2 + index * (stroke + gap)
                canvas.drawRing(ring, track, paint, RectF(inset, inset, size - inset, size - inset))
            }
        }
    }

    private fun Canvas.drawRing(ring: WidgetGaugeRing, track: Int, paint: Paint, bounds: RectF) {
        paint.color = track
        drawArc(bounds, ReadinessGaugeArc.START_DEGREES, ReadinessGaugeArc.SWEEP_DEGREES, false, paint)
        val score = ring.score ?: return
        paint.color = ring.argb
        drawArc(bounds, ReadinessGaugeArc.START_DEGREES, ReadinessGaugeArc.SWEEP_DEGREES * score / ReadinessGaugeArc.MAX_SCORE, false, paint)
    }
}
