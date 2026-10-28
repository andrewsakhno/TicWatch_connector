package org.sand.ticwatch.phone.advisor.widget

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import org.sand.ticwatch.phone.R
import org.sand.ticwatch.phone.advisor.CardioReadiness
import org.sand.ticwatch.phone.advisor.ReadinessPalette
import org.sand.ticwatch.phone.health.SampleSource
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

/** Builds the widget's [RemoteViews]; a tap opens [openIntent] (the Advisor tab). */
class CardioAdvisorWidgetRenderer(private val context: Context, private val openIntent: Intent, private val zone: ZoneId) {

    fun render(readiness: CardioReadiness, now: Instant): RemoteViews = views().apply {
        val assessment = readiness.assessment
        val vitals = readiness.vitals
        setTextViewText(R.id.advisor_widget_score, "${assessment.score}%")
        setTextColor(R.id.advisor_widget_score, ReadinessPalette.argb(assessment.state).toInt())
        setTextViewText(R.id.advisor_widget_state, assessment.state.label)
        setTextViewText(R.id.advisor_widget_icon, assessment.advice.action.icon)
        setTextViewText(R.id.advisor_widget_advice, assessment.advice.action.title)
        setTextViewText(
            R.id.advisor_widget_pressure,
            vitals.pressure?.let {
                val origin = if (it.source == SampleSource.BLOOD_PRESSURE_CUFF) "cuff" else "wrist"
                "BP ${it.systolic.roundToInt()}/${it.diastolic.roundToInt()} mmHg · $origin ${time(it.measuredAt)}"
            } ?: "BP $MISSING",
        )
        setTextViewText(
            R.id.advisor_widget_vitals,
            listOf(
                "$HEART ${vitals.heartRateBpm?.let { "${it.roundToInt()} bpm" } ?: MISSING}",
                "$THERMOMETER ${vitals.bodyTemperatureCelsius?.let { "%.1f °C".format(it) } ?: MISSING}",
                "SpO2 ${vitals.spo2Percent?.let { "${it.roundToInt()}%" } ?: MISSING}",
            ).joinToString("   "),
        )
        val walk = readiness.nextWalkWindow?.let { "$WALKER ${time(it.start)}" } ?: "$WALKER $MISSING"
        setTextViewText(R.id.advisor_widget_footer, "$walk · updated ${time(now)}")
    }

    fun unavailable(reason: String): RemoteViews = views().apply {
        setTextViewText(R.id.advisor_widget_score, MISSING)
        setTextViewText(R.id.advisor_widget_state, reason)
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
        val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    }
}
