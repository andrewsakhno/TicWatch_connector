package org.sand.ticwatch.phone

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.sand.ticwatch.phone.dashboard.ColorScaleBar
import org.sand.ticwatch.phone.dashboard.GaugeScale
import org.sand.ticwatch.phone.dashboard.GaugeScales
import org.sand.ticwatch.phone.dashboard.Sparkline
import org.sand.ticwatch.phone.dashboard.BloodPressureDayChart
import org.sand.ticwatch.phone.dashboard.categoryColor
import org.sand.ticwatch.phone.health.BloodPressureReading
import org.sand.ticwatch.phone.health.HeartRateZone
import org.sand.ticwatch.phone.health.SleepStageType
import org.sand.ticwatch.phone.insights.Barometer
import org.sand.ticwatch.phone.insights.DailyStressSummary
import org.sand.ticwatch.phone.insights.HealthInsights
import org.sand.ticwatch.phone.insights.HealthScores
import org.sand.ticwatch.phone.insights.HrvStatus
import org.sand.ticwatch.phone.insights.Light
import org.sand.ticwatch.phone.insights.MorningLightExposure
import org.sand.ticwatch.phone.insights.Readiness
import org.sand.ticwatch.phone.insights.SleepInsight
import org.sand.ticwatch.phone.insights.SleepScore
import org.sand.ticwatch.phone.insights.SleepScoreComponent
import org.sand.ticwatch.phone.insights.Stress
import org.sand.ticwatch.phone.insights.StressLevel
import org.sand.ticwatch.phone.insights.TrainingLoadBalance
import org.sand.ticwatch.phone.insights.Trend
import org.sand.ticwatch.phone.spaceweather.SpaceWeather
import org.sand.ticwatch.phone.spaceweather.SpaceWeatherDay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

private const val MISSING = "--"
private const val PERCENT = 100
private const val MINUTES_PER_HOUR = 60
private const val SECONDS_PER_HOUR = 3_600.0
private val SPARKLINE_HEIGHT = 56.dp
private val SECTION_GAP = 8.dp
private val BLOOD_PRESSURE_WINDOW: Duration = Duration.ofHours(24)

private object TrendColors {
    val HRV = Color(0xFF66BB6A)
    val STRESS = Color(0xFFFFA726)
    val SPO2 = Color(0xFF42A5F5)
    val PRESSURE = Color(0xFFAB47BC)
    val LIGHT = Color(0xFFFFCA28)
}
private val SLEEP_COMPONENT_LABELS = mapOf(
    SleepScoreComponent.DURATION to "Duration",
    SleepScoreComponent.ARCHITECTURE to "Deep / REM",
    SleepScoreComponent.EFFICIENCY to "Efficiency",
    SleepScoreComponent.RELAXATION to "HR relaxation",
    SleepScoreComponent.BREATHING to "Breathing",
)
private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())
private val UPDATED_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM HH:mm").withZone(ZoneId.systemDefault())
private val DAY_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE dd.MM")

/** Dashboard "Metrics" tab: cards built from the phone's sensor history and the derived scores. */
@Composable
fun HealthInsightsScreen(appContainer: AppContainer) {
    var insights by remember { mutableStateOf<HealthInsights?>(null) }
    var scores by remember { mutableStateOf<HealthScores?>(null) }
    var bloodPressure by remember { mutableStateOf<List<BloodPressureReading>>(emptyList()) }
    var bloodPressureEnd by remember { mutableStateOf(Instant.now()) }
    var spaceWeather by remember { mutableStateOf<SpaceWeather?>(null) }

    // The history store runs its queries on Dispatchers.IO; this effect is cancelled with the composition.
    LaunchedEffect(Unit) {
        insights = appContainer.healthInsights.load()
        scores = appContainer.healthScores.load()
        bloodPressureEnd = Instant.now()
        bloodPressure = appContainer.bloodPressureMonitor.since(bloodPressureEnd.minus(BLOOD_PRESSURE_WINDOW))
        spaceWeather = withContext(Dispatchers.IO) { appContainer.spaceWeatherStore.load() }
    }

    Column(
        modifier = Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        WatchSnapshotCard(appContainer.watchSnapshotRequester, appContainer.snapshotResults)
        val loaded = insights
        if (loaded == null) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        } else {
            val trends = loaded.trends
            val derived = scores
            ReadinessCard(loaded.readiness, trends.readiness)
            SleepScoreCard(derived?.sleepScore)
            HrvStatusCard(derived?.hrvStatus)
            TrainingLoadCard(derived?.trainingLoad, derived?.weeklyZoneMinutes.orEmpty())
            StressCard(loaded.stress, derived?.stress, trends.stress)
            SleepCard(loaded.sleep, trends.sleep)
            BloodPressureCard(bloodPressure, bloodPressureEnd.minus(BLOOD_PRESSURE_WINDOW), bloodPressureEnd)
            BarometerCard(loaded.barometer, trends.barometer)
            LightCard(loaded.light, derived?.morningLight, trends.light)
            SpaceWeatherCard(spaceWeather)
        }
    }
}

@Composable
private fun ReadinessCard(readiness: Readiness?, trend: Trend) =
    InsightCard("Daily readiness", TrendSection(trend, TrendColors.HRV, "RMSSD, daily average, 14 days")) {
    if (readiness == null) return@InsightCard NoData()
    ScaledValue("${readiness.score}%", GaugeScales.READINESS, readiness.score.toDouble())
    Detail("Night RMSSD", readiness.nightlyRmssdMs?.let { "%.0f ms".format(it) })
    Detail("Resting HR", readiness.restingHeartRateBpm?.let { "%.0f bpm".format(it) })
    Detail("Skin temp. delta", readiness.skinTemperatureDeltaCelsius?.let { "%+.1f °C".format(it) })
}

@Composable
private fun StressCard(stress: Stress?, daily: DailyStressSummary?, trend: Trend) =
    InsightCard("Stress", TrendSection(trend, TrendColors.STRESS, "RMSSD, last 24 h")) {
    if (stress == null) return@InsightCard NoData()
    ScaledValue("%.0f ms".format(stress.rmssdMs), GaugeScales.STRESS_RMSSD, stress.rmssdMs)
    Detail("RMSSD at ${TIME_FORMAT.format(stress.at)}", "%.0f ms".format(stress.rmssdMs))
    Detail(
        "Today (awake, still)",
        daily?.let { summary -> StressLevel.entries.joinToString(" · ") { "${it.label} ${formatShare(summary.share(it))}" } },
    )
}

@Composable
private fun SleepScoreCard(score: SleepScore?) = InsightCard("Sleep score", trend = null) {
    if (score == null) return@InsightCard NoData()
    ScaledValue("${score.score}", GaugeScales.SLEEP_SCORE, score.score.toDouble())
    SleepScoreComponent.entries.forEach { component ->
        Detail(SLEEP_COMPONENT_LABELS.getValue(component), score.components[component]?.let(::formatShare))
    }
    Detail("Awakenings", score.inputs.awakenings.toString())
}

@Composable
private fun HrvStatusCard(status: HrvStatus?) = InsightCard("HRV status", trend = null) {
    val weekly = status?.weeklyMean ?: return@InsightCard NoData()
    val baseline = status.baseline
    Text(text = "%.0f ms".format(weekly), style = MaterialTheme.typography.headlineSmall)
    Text(text = status.level?.label ?: "Building baseline (${baseline?.nights ?: 0} nights)", style = MaterialTheme.typography.labelLarge)
    if (baseline != null) {
        Spacer(Modifier.height(SECTION_GAP))
        ColorScaleBar(GaugeScales.hrvCorridor(baseline), weekly, Modifier.fillMaxWidth())
        Spacer(Modifier.height(SECTION_GAP))
    }
    Detail("Nights this week", status.weeklyNights.toString())
    Detail("Normal range", baseline?.let { "%.0f-%.0f ms".format(it.low, it.high) })
}

@Composable
private fun TrainingLoadCard(load: TrainingLoadBalance?, weeklyZones: Map<HeartRateZone, Double>) =
    InsightCard("Training load", trend = null) {
    if (load == null) return@InsightCard NoData()
    val ratio = load.ratio
    if (ratio != null) ScaledValue("%.2f".format(ratio), GaugeScales.LOAD_RATIO, ratio) else NoData()
    Detail("Acute (7 d)", "%.0f".format(load.acuteLoad))
    Detail("Chronic (weekly)", "%.0f".format(load.chronicWeeklyLoad))
    Detail("Today", "%.0f".format(load.dayLoad))
    Detail("Zones, 7 d", HeartRateZone.entries.joinToString(" · ") { "Z${it.number} %.0f".format(weeklyZones[it] ?: 0.0) } + " min")
}

@Composable
private fun SleepCard(sleep: SleepInsight?, trend: Trend) =
    InsightCard("Sleep & breathing", TrendSection(trend, TrendColors.SPO2, "SpO2 during the night")) {
    if (sleep == null) return@InsightCard NoData()
    ScaledValue(formatDuration(sleep.duration), GaugeScales.SLEEP_HOURS, sleep.duration.seconds / SECONDS_PER_HOUR)
    Text(text = "${TIME_FORMAT.format(sleep.start)} - ${TIME_FORMAT.format(sleep.end)}", style = MaterialTheme.typography.bodySmall)
    Detail("Deep", sleep.share(SleepStageType.DEEP)?.let(::formatShare))
    Detail("REM", sleep.share(SleepStageType.REM)?.let(::formatShare))
    Detail("ODI", sleep.desaturationIndexPerHour?.let { "%.1f /h".format(it) })
    Detail("Snoring", sleep.snoringMinutes?.let { "%.0f min".format(it) })
}

@Composable
private fun BloodPressureCard(readings: List<BloodPressureReading>, start: Instant, end: Instant) =
    InsightCard("Blood pressure", trend = null) {
    val latest = readings.lastOrNull()
    if (latest == null) {
        NoData()
    } else {
        Text(text = "${latest.systolic.roundToInt()}/${latest.diastolic.roundToInt()} mmHg", style = MaterialTheme.typography.headlineSmall)
        Text(text = latest.category.label, style = MaterialTheme.typography.labelLarge, color = categoryColor(latest.category))
        Detail("Readings, 24 h", readings.size.toString())
    }
    Spacer(Modifier.height(SECTION_GAP))
    BloodPressureDayChart(readings, start, end)
}

@Composable
private fun BarometerCard(barometer: Barometer?, trend: Trend) =
    InsightCard("Barometer", TrendSection(trend, TrendColors.PRESSURE, "Pressure, last 24 h")) {
    if (barometer == null) return@InsightCard NoData()
    Text(text = "%.1f hPa".format(barometer.currentHpa), style = MaterialTheme.typography.headlineSmall)
    Detail("24 h change", barometer.dayChangeHpa?.let { "%+.1f hPa".format(it) })
    Detail("Trend", barometer.trend?.label)
}

@Composable
private fun LightCard(light: Light?, morning: MorningLightExposure?, trend: Trend) =
    InsightCard("Light & circadian", TrendSection(trend, TrendColors.LIGHT, "Light, last 24 h")) {
    if (light == null) return@InsightCard NoData()
    Text(text = "%.0f lux".format(light.lux), style = MaterialTheme.typography.headlineSmall)
    Detail("Context at ${TIME_FORMAT.format(light.at)}", light.context.label)
    if (morning != null) {
        val minutes = morning.bright.toMinutes().toDouble()
        Spacer(Modifier.height(SECTION_GAP))
        Detail("Morning daylight (06-12)", "%.0f / %d min".format(minutes, morning.goal.toMinutes()))
        ColorScaleBar(GaugeScales.morningLight(morning.goal.toMinutes().toDouble()), minutes, Modifier.fillMaxWidth())
    }
}

@Composable
private fun SpaceWeatherCard(weather: SpaceWeather?) = InsightCard("Geomagnetic & solar activity", trend = null) {
    val current = weather?.current ?: return@InsightCard NoData()
    ScaledValue("Kp %.2f".format(current.kp), GaugeScales.KP, current.kp)
    Detail("Slot from ${TIME_FORMAT.format(current.start)}", current.origin.noaaName)
    Detail("Updated", UPDATED_FORMAT.format(weather.fetchedAt))
    Spacer(Modifier.height(SECTION_GAP))
    weather.days.forEach { SpaceWeatherDayRow(it) }
    Text(text = "NOAA SWPC; days 4-5 from the 27-day outlook", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun SpaceWeatherDayRow(day: SpaceWeatherDay) {
    val flux = day.radioFlux?.let { "F10.7 $it (${day.solarActivity?.label})" } ?: "F10.7 $MISSING"
    val origin = if (day.forecast) "forecast" else "observed"
    Text(
        text = "${DAY_FORMAT.format(day.date)}  Kp %.1f  ${day.level.label}  ·  $flux  ·  $origin".format(day.maxKp),
        style = MaterialTheme.typography.bodySmall,
        color = GaugeScales.kpColor(day.level),
    )
}

/** A card with an optional mini graph under its content. */
@Composable
private fun InsightCard(title: String, trend: TrendSection?, content: @Composable ColumnScope.() -> Unit) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            content()
            if (trend != null) {
                Spacer(Modifier.height(SECTION_GAP))
                Sparkline(trend.trend, trend.color, Modifier.fillMaxWidth().height(SPARKLINE_HEIGHT))
                Text(text = trend.caption, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** A card's mini graph: what is plotted, in which color, and what it shows. */
private class TrendSection(val trend: Trend, val color: Color, val caption: String)

/** Big value, its band's label in the band's color, and the colored scale with a marker at [value]. */
@Composable
private fun ScaledValue(text: String, scale: GaugeScale, value: Double) {
    val band = scale.bandOf(value)
    Text(text = text, style = MaterialTheme.typography.headlineSmall)
    Text(text = band.label, style = MaterialTheme.typography.labelLarge, color = band.color)
    Spacer(Modifier.height(SECTION_GAP))
    ColorScaleBar(scale, value, Modifier.fillMaxWidth())
    Spacer(Modifier.height(SECTION_GAP))
}

@Composable
private fun NoData() {
    Text(text = MISSING, style = MaterialTheme.typography.headlineSmall)
}

@Composable
private fun Detail(label: String, value: String?) {
    Text(text = "$label: ${value ?: MISSING}", style = MaterialTheme.typography.bodyMedium)
}

private fun formatShare(share: Double): String = "${(share * PERCENT).roundToInt()}%"

private fun formatDuration(duration: Duration): String = "%dh %02dm".format(duration.toHours(), duration.toMinutes() % MINUTES_PER_HOUR)
