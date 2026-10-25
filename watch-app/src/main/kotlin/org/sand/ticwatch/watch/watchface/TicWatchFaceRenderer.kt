package org.sand.ticwatch.watch.watchface

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.graphics.Typeface
import android.view.SurfaceHolder
import androidx.wear.watchface.CanvasType
import androidx.wear.watchface.DrawMode
import androidx.wear.watchface.Renderer
import androidx.wear.watchface.WatchState
import androidx.wear.watchface.style.CurrentUserStyleRepository
import org.sand.ticwatch.watch.sensors.LatestReadings
import org.sand.ticwatch.watch.sensors.SensorType
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin

/**
 * Draws the face: a dial of minute ticks with a gradient seconds arc, the time and date in the
 * middle, and heart rate / ambient temperature side by side below. Every size is a fraction of
 * the dial radius, so the layout scales to any round screen. Paints are allocated once; only
 * their colours change per frame.
 */
class TicWatchFaceRenderer(
    surfaceHolder: SurfaceHolder,
    watchState: WatchState,
    currentUserStyleRepository: CurrentUserStyleRepository,
    private val readings: LatestReadings,
    private val is24Hour: () -> Boolean,
) : Renderer.CanvasRenderer2<TicWatchFaceRenderer.Assets>(
    surfaceHolder,
    currentUserStyleRepository,
    watchState,
    CanvasType.HARDWARE,
    FRAME_PERIOD_MS,
    clearWithBackgroundTintBeforeRenderingHighlightLayer = false,
) {
    class Assets : SharedAssets {
        override fun onDestroy() = Unit
    }

    private val backgroundPaint = Paint()
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val timePaint = textPaint(Typeface.create(TIME_FONT, Typeface.NORMAL))
    private val datePaint = textPaint(Typeface.create(LABEL_FONT, Typeface.BOLD)).apply { letterSpacing = DATE_LETTER_SPACING }
    private val valuePaint = textPaint(Typeface.create(VALUE_FONT, Typeface.BOLD))
    private val unitPaint = textPaint(Typeface.create(LABEL_FONT, Typeface.BOLD)).apply { letterSpacing = DATE_LETTER_SPACING }
    private val glyphPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeJoin = Paint.Join.ROUND }
    private val arcBounds = RectF()

    private var shadedBounds: Rect? = null

    override suspend fun createSharedAssets() = Assets()

    override fun render(canvas: Canvas, bounds: Rect, zonedDateTime: ZonedDateTime, sharedAssets: Assets) {
        val ambient = renderParameters.drawMode == DrawMode.AMBIENT
        val palette = if (ambient) WatchFacePalette.AMBIENT else WatchFacePalette.INTERACTIVE
        val radius = bounds.width() / 2f
        val cx = bounds.exactCenterX()
        val cy = bounds.exactCenterY()

        drawBackground(canvas, bounds, palette, ambient)
        drawTicks(canvas, cx, cy, radius, palette)
        if (!ambient) drawSecondsArc(canvas, cx, cy, radius, zonedDateTime)
        drawTimeAndDate(canvas, cx, cy, radius, zonedDateTime, palette)
        drawReadings(canvas, cx, cy, radius, palette)
    }

    override fun renderHighlightLayer(canvas: Canvas, bounds: Rect, zonedDateTime: ZonedDateTime, sharedAssets: Assets) {
        renderParameters.highlightLayer?.let { canvas.drawColor(it.backgroundTint) }
    }

    private fun drawBackground(canvas: Canvas, bounds: Rect, palette: WatchFacePalette, ambient: Boolean) {
        if (ambient) {
            canvas.drawColor(palette.backgroundEdge)
            return
        }
        if (shadedBounds != bounds) {
            backgroundPaint.shader = RadialGradient(
                bounds.exactCenterX(), bounds.exactCenterY() - bounds.height() * GLOW_OFFSET, bounds.width() * GLOW_REACH,
                palette.backgroundCenter, palette.backgroundEdge, Shader.TileMode.CLAMP,
            )
            arcPaint.shader = SweepGradient(bounds.exactCenterX(), bounds.exactCenterY(), ARC_COLORS, null)
            shadedBounds = Rect(bounds)
        }
        canvas.drawRect(bounds, backgroundPaint)
    }

    private fun drawTicks(canvas: Canvas, cx: Float, cy: Float, radius: Float, palette: WatchFacePalette) {
        for (tick in 0 until TICKS) {
            val hour = tick % TICKS_PER_HOUR_MARK == 0
            tickPaint.color = if (hour) palette.hourTick else palette.minuteTick
            tickPaint.strokeWidth = radius * if (hour) HOUR_TICK_WIDTH else MINUTE_TICK_WIDTH
            val inner = radius * if (hour) HOUR_TICK_INNER else MINUTE_TICK_INNER
            val outer = radius * TICK_OUTER
            val angle = Math.toRadians(tick * DEGREES_PER_TICK).toFloat()
            canvas.drawLine(
                cx + inner * sin(angle), cy - inner * cos(angle),
                cx + outer * sin(angle), cy - outer * cos(angle),
                tickPaint,
            )
        }
    }

    private fun drawSecondsArc(canvas: Canvas, cx: Float, cy: Float, radius: Float, time: ZonedDateTime) {
        val arcRadius = radius * ARC_RADIUS
        arcPaint.strokeWidth = radius * ARC_WIDTH
        arcBounds.set(cx - arcRadius, cy - arcRadius, cx + arcRadius, cy + arcRadius)
        val seconds = time.second + time.nano / NANOS_PER_SECOND
        canvas.save()
        // The sweep gradient starts at 3 o'clock; rotate so both it and the arc start at 12.
        canvas.rotate(ARC_START_DEGREES, cx, cy)
        canvas.drawArc(arcBounds, 0f, (seconds / SECONDS_PER_MINUTE * FULL_CIRCLE).toFloat(), false, arcPaint)
        canvas.restore()
    }

    private fun drawTimeAndDate(
        canvas: Canvas, cx: Float, cy: Float, radius: Float, time: ZonedDateTime, palette: WatchFacePalette,
    ) {
        timePaint.color = palette.time
        timePaint.textSize = radius * TIME_SIZE
        val timeText = time.format(if (is24Hour()) TIME_24H else TIME_12H)
        canvas.drawText(timeText, cx, cy + radius * TIME_BASELINE, timePaint)

        datePaint.color = palette.date
        datePaint.textSize = radius * DATE_SIZE
        canvas.drawText(time.format(DATE).uppercase(Locale.getDefault()), cx, cy - radius * DATE_RAISE, datePaint)
    }

    private fun drawReadings(canvas: Canvas, cx: Float, cy: Float, radius: Float, palette: WatchFacePalette) {
        val heartRate = ReadingText.heartRate(readings)
        val ambient = ReadingText.ambientTemperature(readings)

        val glyphY = cy + radius * GLYPH_DROP
        val glyphSize = radius * GLYPH_SIZE
        glyphPaint.style = palette.glyphStyle
        glyphPaint.strokeWidth = radius * GLYPH_STROKE
        glyphPaint.color = palette.heart
        ReadingGlyphs.drawHeart(canvas, cx - radius * READING_SPREAD, glyphY, glyphSize, glyphPaint)
        glyphPaint.color = palette.thermometer
        ReadingGlyphs.drawThermometer(canvas, cx + radius * READING_SPREAD, glyphY, glyphSize, glyphPaint)

        drawReading(canvas, cx - radius * READING_SPREAD, cy, radius, heartRate, ReadingText.HEART_RATE_UNIT, palette)
        drawReading(canvas, cx + radius * READING_SPREAD, cy, radius, ambient, ReadingText.TEMPERATURE_UNIT, palette)

        tickPaint.color = palette.divider
        tickPaint.strokeWidth = radius * MINUTE_TICK_WIDTH
        canvas.drawLine(cx, cy + radius * DIVIDER_TOP, cx, cy + radius * DIVIDER_BOTTOM, tickPaint)
    }

    private fun drawReading(
        canvas: Canvas, x: Float, cy: Float, radius: Float, value: String, unit: String, palette: WatchFacePalette,
    ) {
        valuePaint.color = palette.time
        valuePaint.textSize = radius * VALUE_SIZE
        canvas.drawText(value, x, cy + radius * VALUE_BASELINE, valuePaint)
        unitPaint.color = palette.label
        unitPaint.textSize = radius * UNIT_SIZE
        canvas.drawText(unit, x, cy + radius * UNIT_BASELINE, unitPaint)
    }

    private fun textPaint(face: Typeface) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = face
        textAlign = Paint.Align.CENTER
    }

    private companion object {
        /** The seconds arc moves smoothly at ~15 fps; ambient mode redraws once a minute regardless. */
        const val FRAME_PERIOD_MS = 66L

        const val TIME_FONT = "sans-serif-thin"
        const val VALUE_FONT = "sans-serif-condensed"
        const val LABEL_FONT = "sans-serif-medium"
        val TIME_24H: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
        val TIME_12H: DateTimeFormatter = DateTimeFormatter.ofPattern("h:mm")
        val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM")

        val ARC_COLORS = intArrayOf(
            android.graphics.Color.parseColor("#3DDCFF"),
            android.graphics.Color.parseColor("#8A6CFF"),
            android.graphics.Color.parseColor("#FF4D6D"),
        )

        const val TICKS = 60
        const val TICKS_PER_HOUR_MARK = 5
        const val DEGREES_PER_TICK = 6.0
        const val TICK_OUTER = 0.97f
        const val MINUTE_TICK_INNER = 0.93f
        const val HOUR_TICK_INNER = 0.88f
        const val MINUTE_TICK_WIDTH = 0.012f
        const val HOUR_TICK_WIDTH = 0.025f

        const val ARC_RADIUS = 0.83f
        const val ARC_WIDTH = 0.03f
        const val ARC_START_DEGREES = -90f
        const val FULL_CIRCLE = 360.0
        const val SECONDS_PER_MINUTE = 60.0
        const val NANOS_PER_SECOND = 1_000_000_000.0

        const val GLOW_OFFSET = 0.15f
        const val GLOW_REACH = 0.75f

        const val TIME_SIZE = 0.44f
        const val TIME_BASELINE = 0.1f
        const val DATE_SIZE = 0.085f
        const val DATE_RAISE = 0.36f
        const val DATE_LETTER_SPACING = 0.12f

        const val READING_SPREAD = 0.3f
        const val GLYPH_DROP = 0.3f
        const val GLYPH_SIZE = 0.12f
        const val GLYPH_STROKE = 0.012f
        const val VALUE_SIZE = 0.16f
        const val VALUE_BASELINE = 0.54f
        const val UNIT_SIZE = 0.06f
        const val UNIT_BASELINE = 0.64f
        const val DIVIDER_TOP = 0.26f
        const val DIVIDER_BOTTOM = 0.62f
    }
}
