package org.sand.ticwatch.phone

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt
import org.sand.ticwatch.phone.dashboard.BloodPressureDayChart
import org.sand.ticwatch.phone.dashboard.ColorScaleBar
import org.sand.ticwatch.phone.dashboard.GaugeScale
import org.sand.ticwatch.phone.dashboard.GaugeScales
import org.sand.ticwatch.phone.dashboard.HeartRateZoneChart
import org.sand.ticwatch.phone.dashboard.SpaceWeatherWeekChart
import org.sand.ticwatch.phone.dashboard.Sparkline
import org.sand.ticwatch.phone.dashboard.ZoneChartSize
import org.sand.ticwatch.phone.dashboard.categoryColor
import org.sand.ticwatch.phone.health.BloodPressureReading
import org.sand.ticwatch.phone.health.HeartRateZone
import org.sand.ticwatch.phone.health.SleepStageType
import org.sand.ticwatch.phone.insights.Barometer
import org.sand.ticwatch.phone.insights.DailyStressSummary
import org.sand.ticwatch.phone.insights.FatigueComponent
import org.sand.ticwatch.phone.insights.FatigueIndex
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
import org.sand.ticwatch.phone.spaceweather.KpOrigin
import org.sand.ticwatch.phone.spaceweather.SolarActivityLevel
import org.sand.ticwatch.phone.spaceweather.SpaceWeather
import org.sand.ticwatch.phone.ui.ReloadingStore
import org.sand.ticwatch.phone.ui.anyChangeOf
import org.sand.ticwatch.phone.ui.rememberStore

private const val MISSING = "--"
private const val PERCENT = 100
private const val MINUTES_PER_HOUR = 60
private const val SECONDS_PER_HOUR = 3_600.0
private val SPARKLINE_HEIGHT = 56.dp
private val SECTION_GAP = 8.dp
private val BLOOD_PRESSURE_WINDOW: Duration = Duration.ofDays(3)

private object TrendColors {
    val HRV = Color(0xFF66BB6A)
    val STRESS = Color(0xFFFFA726)
    val SPO2 = Color(0xFF42A5F5)
    val PRESSURE = Color(0xFFAB47BC)
    val LIGHT = Color(0xFFFFCA28)
}
private val SLEEP_COMPONENT_LABELS = mapOf(
    SleepScoreComponent.DURATION to R.string.sleep_component_duration,
    SleepScoreComponent.ARCHITECTURE to R.string.sleep_component_architecture,
    SleepScoreComponent.EFFICIENCY to R.string.sleep_component_efficiency,
    SleepScoreComponent.RELAXATION to R.string.sleep_component_relaxation,
    SleepScoreComponent.BREATHING to R.string.sleep_component_breathing,
)
private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())
private val UPDATED_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM HH:mm").withZone(ZoneId.systemDefault())

/** Everything the Metrics tab shows, read in one pass off the main thread. */
private data class MetricsUiState(
    val insights: HealthInsights,
    val scores: HealthScores,
    val bloodPressure: List<BloodPressureReading>,
    val bloodPressureEnd: Instant,
    val spaceWeather: SpaceWeather?,
)

private suspend fun loadMetrics(appContainer: AppContainer): MetricsUiState {
    val bloodPressureEnd = Instant.now()
    return MetricsUiState(
        insights = appContainer.healthInsights.load(),
        scores = appContainer.healthScores.load(),
        bloodPressure = appContainer.bloodPressureMonitor.since(bloodPressureEnd.minus(BLOOD_PRESSURE_WINDOW)),
        bloodPressureEnd = bloodPressureEnd,
        spaceWeather = appContainer.spaceWeatherStore.load(),
    )
}

/** Dashboard "Metrics" tab: cards built from the phone's sensor history and the derived scores. */
@Composable
fun HealthInsightsScreen(appContainer: AppContainer) {
    val store = rememberStore(appContainer) { scope, io ->
        ReloadingStore(scope, io, anyChangeOf(appContainer.historyChanges, appContainer.bloodPressureChanges, appContainer.cloudHistoryArrivals)) {
            loadMetrics(appContainer)
        }
    }
    val metrics by store.state.collectAsState()
    val insights = metrics?.insights
    val scores = metrics?.scores
    val bloodPressure = metrics?.bloodPressure.orEmpty()
    val bloodPressureEnd = metrics?.bloodPressureEnd ?: Instant.now()
    val spaceWeather = metrics?.spaceWeather
    var sleepJournalOpen by remember { mutableStateOf(false) }
    var workoutJournalOpen by remember { mutableStateOf(false) }
    var fatigueHistoryOpen by remember { mutableStateOf(false) }

    if (fatigueHistoryOpen) {
        FatigueHistorySheet(
            appContainer.fatigueHistory,
            anyChangeOf(appContainer.historyChanges, appContainer.cloudHistoryArrivals),
            onDismiss = { fatigueHistoryOpen = false },
        )
    }
    if (sleepJournalOpen) {
        SleepJournalSheet(appContainer.sleepJournal, appContainer.historyChanges.changes, onDismiss = { sleepJournalOpen = false })
    }
    if (workoutJournalOpen) {
        WorkoutJournalSheet(
            appContainer.workoutJournal,
            appContainer.workoutPeriodZones,
            anyChangeOf(appContainer.historyChanges, appContainer.cloudHistoryArrivals),
            onDismiss = { workoutJournalOpen = false },
        )
    }

    val workoutStore = rememberWatchWorkoutStore(appContainer.watchWorkoutCommands, appContainer.watchWorkoutActivity)

    // Lazy: a data push recomposes only the visible cards, not every chart of the tab mid-scroll.
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = MetricsItem.SNAPSHOT) { WatchSnapshotCard(appContainer.watchSnapshotRequester, appContainer.snapshotResults) }
        item(key = MetricsItem.WORKOUT) { WatchWorkoutCard(workoutStore) }
        val loaded = insights
        if (loaded == null) {
            item(key = MetricsItem.LOADING) { LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth()) }
        } else {
            val trends = loaded.trends
            val derived = scores
            item(key = MetricsItem.READINESS) { ReadinessCard(loaded.readiness, trends.readiness) }
            item(key = MetricsItem.FATIGUE) { FatigueIndexCard(derived?.fatigue, onClick = { fatigueHistoryOpen = true }) }
            item(key = MetricsItem.SLEEP) { SleepScoreCard(derived?.sleepScore, loaded.sleep, trends.sleep, onClick = { sleepJournalOpen = true }) }
            item(key = MetricsItem.HRV) { HrvStatusCard(derived?.hrvStatus) }
            item(key = MetricsItem.TRAINING_LOAD) {
                TrainingLoadCard(derived?.trainingLoad, derived?.weeklyZoneMinutes.orEmpty(), onClick = { workoutJournalOpen = true })
            }
            item(key = MetricsItem.STRESS) { StressCard(loaded.stress, derived?.stress, trends.stress) }
            item(key = MetricsItem.BLOOD_PRESSURE) { BloodPressureCard(bloodPressure, bloodPressureEnd.minus(BLOOD_PRESSURE_WINDOW), bloodPressureEnd) }
            item(key = MetricsItem.BAROMETER) { BarometerCard(loaded.barometer, trends.barometer) }
            item(key = MetricsItem.LIGHT) { LightCard(loaded.light, derived?.morningLight, trends.light) }
            item(key = MetricsItem.SPACE_WEATHER) { SpaceWeatherCard(spaceWeather) }
        }
    }
}

/** Stable keys of the Metrics tab's lazy list items. */
private enum class MetricsItem { SNAPSHOT, WORKOUT, LOADING, READINESS, FATIGUE, SLEEP, HRV, TRAINING_LOAD, STRESS, BLOOD_PRESSURE, BAROMETER, LIGHT, SPACE_WEATHER }

@Composable
private fun ReadinessCard(readiness: Readiness?, trend: Trend) =
    InsightCard(stringResource(R.string.card_daily_readiness), TrendSection(trend, TrendColors.HRV, stringResource(R.string.trend_rmssd_daily))) {
    if (readiness == null) return@InsightCard NoData()
    ScaledValue("${readiness.score}%", GaugeScales.READINESS, readiness.score.toDouble())
    Detail(stringResource(R.string.night_rmssd), readiness.nightlyRmssdMs?.let { stringResource(R.string.unit_ms, it) })
    Detail(stringResource(R.string.resting_hr), readiness.restingHeartRateBpm?.let { stringResource(R.string.unit_bpm, it) })
    Detail(stringResource(R.string.skin_temp_delta), readiness.skinTemperatureDeltaCelsius?.let { stringResource(R.string.unit_celsius_delta, it) })
}

@Composable
private fun FatigueIndexCard(fatigue: FatigueIndex?, onClick: () -> Unit) = InsightCard(stringResource(R.string.card_fatigue_index), trend = null, onClick = onClick) {
    if (fatigue == null) {
        NoData()
    } else {
        ScaledValue("${fatigue.percent}%", GaugeScales.FATIGUE_INDEX, fatigue.percent.toDouble())
        FatigueComponent.entries.forEach { component ->
            Detail(stringResource(component.label), fatigue.components[component]?.let(::formatShare))
        }
    }
    Spacer(Modifier.height(SECTION_GAP))
    Text(text = stringResource(R.string.fatigue_history_open), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun StressCard(stress: Stress?, daily: DailyStressSummary?, trend: Trend) =
    InsightCard(stringResource(R.string.card_stress), TrendSection(trend, TrendColors.STRESS, stringResource(R.string.trend_rmssd_24h))) {
    if (stress == null) return@InsightCard NoData()
    ScaledValue(stringResource(R.string.unit_ms, stress.rmssdMs), GaugeScales.STRESS_RMSSD, stress.rmssdMs)
    Detail(stringResource(R.string.rmssd_at, TIME_FORMAT.format(stress.at)), stringResource(R.string.unit_ms, stress.rmssdMs))
    Detail(
        stringResource(R.string.stress_today),
        daily?.let { summary -> StressLevel.entries.map { "${stringResource(it.label)} ${formatShare(summary.share(it))}" }.joinToString(" · ") },
    )
}

@Composable
private fun SleepScoreCard(score: SleepScore?, sleep: SleepInsight?, trend: Trend, onClick: () -> Unit) =
    InsightCard(stringResource(R.string.card_sleep_score), TrendSection(trend, TrendColors.SPO2, stringResource(R.string.trend_spo2_night)), onClick) {
    if (score == null) {
        NoData()
    } else {
        ScaledValue("${score.score}", GaugeScales.SLEEP_SCORE, score.score.toDouble())
        SleepScoreComponent.entries.forEach { component ->
            Detail(stringResource(SLEEP_COMPONENT_LABELS.getValue(component)), score.components[component]?.let(::formatShare))
        }
        Detail(stringResource(R.string.awakenings), score.inputs.awakenings.toString())
    }
    Spacer(Modifier.height(SECTION_GAP))
    sleep?.let { SleepNightDetails(it) }
    Text(text = stringResource(R.string.sleep_journal_open), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun SleepNightDetails(sleep: SleepInsight) {
    ScaledValue(formatDuration(sleep.duration), GaugeScales.SLEEP_HOURS, sleep.duration.seconds / SECONDS_PER_HOUR)
    Text(text = "${TIME_FORMAT.format(sleep.start)} - ${TIME_FORMAT.format(sleep.end)}", style = MaterialTheme.typography.bodySmall)
    Detail(stringResource(R.string.stage_deep), sleep.share(SleepStageType.DEEP)?.let(::formatShare))
    Detail(stringResource(R.string.stage_rem), sleep.share(SleepStageType.REM)?.let(::formatShare))
    Detail(stringResource(R.string.odi), sleep.desaturationIndexPerHour?.let { stringResource(R.string.unit_per_hour, it) })
    Detail(stringResource(R.string.snoring), sleep.snoringMinutes?.let { stringResource(R.string.unit_minutes, it) })
    sleep.advanced?.let { SleepInsightsSummary(it) }
    Spacer(Modifier.height(SECTION_GAP))
}

@Composable
private fun HrvStatusCard(status: HrvStatus?) = InsightCard(stringResource(R.string.card_hrv_status), trend = null) {
    val weekly = status?.weeklyMean ?: return@InsightCard NoData()
    val baseline = status.baseline
    Text(text = stringResource(R.string.unit_ms, weekly), style = MaterialTheme.typography.headlineSmall)
    Text(text = status.level?.let { stringResource(it.label) } ?: stringResource(R.string.hrv_building_baseline, baseline?.nights ?: 0), style = MaterialTheme.typography.labelLarge)
    if (baseline != null) {
        Spacer(Modifier.height(SECTION_GAP))
        ColorScaleBar(GaugeScales.hrvCorridor(baseline), weekly, Modifier.fillMaxWidth())
        Spacer(Modifier.height(SECTION_GAP))
    }
    Detail(stringResource(R.string.nights_this_week), status.weeklyNights.toString())
    Detail(stringResource(R.string.normal_range), baseline?.let { stringResource(R.string.range_ms, it.low, it.high) })
}

@Composable
private fun TrainingLoadCard(load: TrainingLoadBalance?, weeklyZones: Map<HeartRateZone, Double>, onClick: () -> Unit) =
    InsightCard(stringResource(R.string.card_training_load), trend = null, onClick = onClick) {
    if (load == null) return@InsightCard NoData()
    val ratio = load.ratio
    val gaugeRatio = load.gaugeRatio
    if (ratio != null && gaugeRatio != null) ScaledValue("%.2f".format(ratio), GaugeScales.LOAD_RATIO, gaugeRatio) else NoData()
    Detail(stringResource(R.string.load_acute), "%.0f".format(load.acuteLoad))
    Detail(stringResource(R.string.load_chronic), "%.0f".format(load.chronicWeeklyLoad))
    Detail(stringResource(R.string.today), "%.0f".format(load.dayLoad))
    Spacer(Modifier.height(SECTION_GAP))
    Text(text = stringResource(R.string.zones_7d), style = MaterialTheme.typography.labelLarge)
    HeartRateZoneChart(weeklyZones, ZoneChartSize.REGULAR, Modifier.fillMaxWidth())
    Spacer(Modifier.height(SECTION_GAP))
    Text(text = stringResource(R.string.workout_journal_open), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun BloodPressureCard(readings: List<BloodPressureReading>, start: Instant, end: Instant) =
    InsightCard(stringResource(R.string.card_blood_pressure), trend = null) {
    val latest = readings.lastOrNull()
    if (latest == null) {
        NoData()
    } else {
        Text(text = stringResource(R.string.unit_mmhg_pair, latest.systolic.roundToInt(), latest.diastolic.roundToInt()), style = MaterialTheme.typography.headlineSmall)
        Text(text = stringResource(latest.category.label), style = MaterialTheme.typography.labelLarge, color = categoryColor(latest.category))
        Detail(stringResource(R.string.readings_days, BLOOD_PRESSURE_WINDOW.toDays()), readings.size.toString())
    }
    Spacer(Modifier.height(SECTION_GAP))
    BloodPressureDayChart(readings, start, end)
}

@Composable
private fun BarometerCard(barometer: Barometer?, trend: Trend) =
    InsightCard(stringResource(R.string.card_barometer), TrendSection(trend, TrendColors.PRESSURE, stringResource(R.string.trend_pressure_24h))) {
    if (barometer == null) return@InsightCard NoData()
    Text(text = stringResource(R.string.unit_hpa, barometer.currentHpa), style = MaterialTheme.typography.headlineSmall)
    Detail(stringResource(R.string.change_24h), barometer.dayChangeHpa?.let { stringResource(R.string.unit_hpa_delta, it) })
    Detail(stringResource(R.string.trend), barometer.trend?.let { stringResource(it.label) })
}

@Composable
private fun LightCard(light: Light?, morning: MorningLightExposure?, trend: Trend) =
    InsightCard(stringResource(R.string.card_light), TrendSection(trend, TrendColors.LIGHT, stringResource(R.string.trend_light_24h))) {
    if (light == null) return@InsightCard NoData()
    Text(text = stringResource(R.string.unit_lux, light.lux), style = MaterialTheme.typography.headlineSmall)
    Detail(stringResource(R.string.context_at, TIME_FORMAT.format(light.at)), stringResource(light.context.label))
    if (morning != null) {
        val minutes = morning.bright.toMinutes().toDouble()
        Spacer(Modifier.height(SECTION_GAP))
        Detail(stringResource(R.string.morning_daylight), stringResource(R.string.minutes_of_goal, minutes, morning.goal.toMinutes()))
        ColorScaleBar(GaugeScales.morningLight(morning.goal.toMinutes().toDouble()), minutes, Modifier.fillMaxWidth())
    }
}

@Composable
private fun SpaceWeatherCard(weather: SpaceWeather?) = InsightCard(stringResource(R.string.card_space_weather), trend = null) {
    val current = weather?.current ?: return@InsightCard NoData()
    ScaledValue("Kp %.2f".format(current.kp), GaugeScales.KP, current.kp)
    Detail(stringResource(R.string.slot_from, TIME_FORMAT.format(current.start)), stringResource(kpOriginLabel(current.origin)))
    Detail(stringResource(R.string.updated), UPDATED_FORMAT.format(weather.fetchedAt))
    Spacer(Modifier.height(SECTION_GAP))
    SpaceWeatherWeekChart(weather.days, Modifier.fillMaxWidth())
    Spacer(Modifier.height(SECTION_GAP))
    Text(text = solarFluxLegend(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Text(text = stringResource(R.string.kp_chart_legend), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** What the "☀" number under each bar is: the Sun's 10.7 cm radio flux, NOAA's daily index of solar activity. */
@Composable
private fun solarFluxLegend(): String {
    val levels = SolarActivityLevel.entries
    val bands = levels.zipWithNext { level, _ -> stringResource(R.string.flux_below, level.maxFlux, stringResource(level.label).lowercase()) } +
        levels.zipWithNext { level, next -> stringResource(R.string.flux_from, level.maxFlux, stringResource(next.label).lowercase()) }.last()
    return stringResource(R.string.solar_flux_legend, bands.joinToString(LEGEND_SEPARATOR))
}

private fun kpOriginLabel(origin: KpOrigin): Int = when (origin) {
    KpOrigin.OBSERVED -> R.string.kp_origin_observed
    KpOrigin.ESTIMATED -> R.string.kp_origin_estimated
    KpOrigin.PREDICTED -> R.string.kp_origin_predicted
}

private const val LEGEND_SEPARATOR = ", "

/** A card with an optional mini graph under its content, tappable when [onClick] is set. */
@Composable
private fun InsightCard(title: String, trend: TrendSection?, onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        val tappable = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier
        Column(modifier = tappable.fillMaxWidth().padding(16.dp)) {
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
    Text(text = stringResource(band.label), style = MaterialTheme.typography.labelLarge, color = band.color)
    Spacer(Modifier.height(SECTION_GAP))
    ColorScaleBar(scale, value, Modifier.fillMaxWidth())
    Spacer(Modifier.height(SECTION_GAP))
}

@Composable
private fun NoData() {
    Text(text = MISSING, style = MaterialTheme.typography.headlineSmall)
}

@Composable
internal fun Detail(label: String, value: String?) {
    Text(text = stringResource(R.string.detail_line, label, value ?: MISSING), style = MaterialTheme.typography.bodyMedium)
}

internal fun formatShare(share: Double): String = "${(share * PERCENT).roundToInt()}%"

@Composable
internal fun formatDuration(duration: Duration): String =
    stringResource(R.string.duration_hm, duration.toHours(), duration.toMinutes() % MINUTES_PER_HOUR)
