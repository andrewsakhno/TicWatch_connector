package org.sand.ticwatch.phone.advisor.widget

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import org.sand.ticwatch.phone.R
import org.sand.ticwatch.phone.advisor.CardioReadiness
import org.sand.ticwatch.phone.advisor.ReadinessPalette
import org.sand.ticwatch.phone.health.SampleSource
import org.sand.ticwatch.phone.watchbattery.WatchBatteryStatus
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

/** Builds the widget's [RemoteViews]; a tap opens [openIntent] (the Advisor tab). */
class CardioAdvisorWidgetRenderer(
    private val context: Context,
    private val openIntent: Intent,
    private val zone: ZoneId,
    private val gauge: ReadinessGaugeBitmap,
) {

    fun render(readiness: CardioReadiness, battery: WatchBatteryStatus?, now: Instant): RemoteViews = views().apply {
        val assessment = readiness.assessment
        val vitals = readiness.vitals
        val cardio = WidgetGaugeRing(assessment.score, ReadinessPalette.argb(assessment.state).toInt())
        val load = readiness.fatigue?.let { WidgetGaugeRing(it.loadReadinessPercent, ReadinessPalette.argb(it.level).toInt()) } ?: emptyRing()
        renderGauge(cardio, load)
        setTextViewText(R.id.advisor_widget_state, context.getString(assessment.state.label))
        setTextViewText(R.id.advisor_widget_icon, assessment.advice.action.icon)
        setTextViewText(R.id.advisor_widget_advice, context.getString(assessment.advice.action.title))
        setTextViewText(
            R.id.advisor_widget_pressure,
            vitals.pressure?.let {
                val origin = if (it.source == SampleSource.BLOOD_PRESSURE_CUFF) R.string.origin_cuff else R.string.origin_wrist
                context.getString(
                    R.string.widget_bp, it.systolic.roundToInt(), it.diastolic.roundToInt(), context.getString(origin), time(it.measuredAt),
                )
            } ?: context.getString(R.string.widget_bp_missing, MISSING),
        )
        setTextViewText(
            R.id.advisor_widget_vitals,
            listOf(
                "$HEART ${vitals.heartRateBpm?.let { context.getString(R.string.bpm_int, it.roundToInt()) } ?: MISSING}",
                "$THERMOMETER ${vitals.bodyTemperatureCelsius?.let { context.getString(R.string.celsius_value, it) } ?: MISSING}",
                "SpO2 ${vitals.spo2Percent?.let { "${it.roundToInt()}%" } ?: MISSING}",
            ).joinToString("   "),
        )
        val walk = readiness.nextWalkWindow?.let { "$WALKER ${time(it.start)}" } ?: "$WALKER $MISSING"
        val batteryAge = battery?.let { " · $WATCH ${it.ageLabel(now).resolve(context.resources)}" }.orEmpty()
        setTextViewText(R.id.advisor_widget_footer, context.getString(R.string.widget_updated, "$walk$batteryAge", time(now)))
        renderBattery(battery, now)
    }

    /** The Advisor's gauge: cardio readiness on the outer ring, load readiness on the inner one, their scores in the middle. */
    private fun RemoteViews.renderGauge(outer: WidgetGaugeRing, inner: WidgetGaugeRing) {
        setImageViewBitmap(R.id.advisor_widget_gauge, gauge.draw(outer, inner))
        renderRingScore(R.id.advisor_widget_score, outer)
        renderRingScore(R.id.advisor_widget_load_score, inner)
    }

    private fun RemoteViews.renderRingScore(viewId: Int, ring: WidgetGaugeRing) {
        setTextViewText(viewId, ring.score?.let { "$it%" } ?: MISSING)
        setTextColor(viewId, ring.argb)
    }

    private fun emptyRing() = WidgetGaugeRing(null, context.getColor(R.color.advisor_widget_secondary_text))

    /** "⌚ 64%" in the header; greyed out once the last report is stale. */
    private fun RemoteViews.renderBattery(battery: WatchBatteryStatus?, now: Instant) {
        setTextViewText(R.id.advisor_widget_battery, "$WATCH ${battery?.levelLabel() ?: MISSING}")
        val stale = battery == null || battery.isStale(now)
        val color = if (stale) R.color.advisor_widget_secondary_text else R.color.advisor_widget_text
        setTextColor(R.id.advisor_widget_battery, context.getColor(color))
    }

    fun unavailable(): RemoteViews = views().apply {
        renderGauge(emptyRing(), emptyRing())
        setTextViewText(R.id.advisor_widget_state, context.getString(R.string.widget_no_data))
    }

    private fun views(): RemoteViews = RemoteViews(context.packageName, R.layout.widget_cardio_advisor).apply {
        val pending = PendingIntent.getActivity(
            context, REQUEST_CODE, openIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        setOnClickPendingIntent(R.id.advisor_widget_root, pending)
    }

    private fun time(instant: Instant): String = TIME_FORMAT.format(instant.atZone(zone))

    private companion object {
        const val REQUEST_CODE = 0
        const val MISSING = "--"
        const val HEART = "♥"
        const val THERMOMETER = "🌡"
        const val WALKER = "🚶"
        const val WATCH = "⌚"
        val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    }
}
