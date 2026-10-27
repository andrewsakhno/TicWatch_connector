package org.sand.ticwatch.watch

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.HorizontalPageIndicator
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.PageIndicatorState
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.sand.ticwatch.watch.autoworkout.AutoWorkoutMode
import org.sand.ticwatch.watch.dashboard.ColorScaleBar
import org.sand.ticwatch.watch.dashboard.GaugeScale
import org.sand.ticwatch.watch.dashboard.GaugeScales
import org.sand.ticwatch.watch.dashboard.Sparkline
import org.sand.ticwatch.watch.scores.HealthScoresSnapshot
import org.sand.ticwatch.watch.spaceweather.SpaceWeatherDaySnapshot
import org.sand.ticwatch.watch.spaceweather.SpaceWeatherSnapshot
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import org.sand.ticwatch.watch.sensors.LiveReadings
import org.sand.ticwatch.watch.sensors.SensorType
import org.sand.ticwatch.watch.sensors.TimedReading

private const val MISSING = "--"
private val NO_READING: StateFlow<Double?> = MutableStateFlow(null)
private val NO_HISTORY: StateFlow<List<TimedReading>> = MutableStateFlow(emptyList())
private val NO_SCORES: StateFlow<HealthScoresSnapshot?> = MutableStateFlow(null)
private val NO_SPACE_WEATHER: StateFlow<SpaceWeatherSnapshot?> = MutableStateFlow(null)
private val DAY_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE")
private const val FORECAST_DAYS_PER_LINE = 3
private const val PERCENT = 100

private object TrendColors {
    val HEART = Color(0xFFEF5350)
    val HRV = Color(0xFF66BB6A)
    val SPO2 = Color(0xFF42A5F5)
    val PRESSURE = Color(0xFFAB47BC)
}

private val PAGE_PADDING = 28.dp
private val SPARKLINE_HEIGHT = 40.dp
private val SECTION_GAP = 6.dp
private const val SCALE_WIDTH_FRACTION = 0.8f

/**
 * Main app screen: swipeable pages (heart, readiness, breathing, environment - each with a colored
 * scale and/or a trend of the service's in-memory history; sleep score and recovery from the phone's
 * [healthScores], null until the phone sent them; NOAA Kp / solar flux from the phone's [spaceWeather]; [bloodPressure] readings and its "Measure" button - then settings, where
 * [onAutoWorkoutModeClick] switches the automatic walk workout to the next mode). Readings are null
 * until the service binds.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun WatchDashboardScreen(
    readings: LiveReadings?,
    healthScores: StateFlow<HealthScoresSnapshot?>?,
    spaceWeather: StateFlow<SpaceWeatherSnapshot?>?,
    bloodPressure: BloodPressurePageState,
    autoWorkoutMode: AutoWorkoutMode?,
    onAutoWorkoutModeClick: () -> Unit,
) {
    val pages: List<@Composable () -> Unit> = listOf(
        { HeartPage(readings) },
        { ReadinessPage(readings) },
        { BloodPressurePage(bloodPressure) },
        { SleepScorePage(healthScores) },
        { RecoveryPage(healthScores) },
        { BreathingPage(readings) },
        { EnvironmentPage(readings) },
        { SpaceWeatherPage(spaceWeather) },
        { SettingsPage(autoWorkoutMode, onAutoWorkoutModeClick) },
    )
    val pagerState = rememberPagerState { pages.size }
    Scaffold(
        timeText = { TimeText() },
        pageIndicator = { HorizontalPageIndicator(pageIndicatorState = pagerState.asIndicatorState()) },
    ) {
        HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page -> pages[page]() }
    }
}

@OptIn(ExperimentalFoundationApi::class)
private fun PagerState.asIndicatorState(): PageIndicatorState = object : PageIndicatorState {
    override val pageOffset: Float get() = currentPageOffsetFraction
    override val selectedPage: Int get() = currentPage
    override val pageCount: Int get() = this@asIndicatorState.pageCount
}

@Composable
private fun latest(readings: LiveReadings?, type: SensorType): Double? {
    val value by (readings?.latest(type) ?: NO_READING).collectAsState()
    return value
}

@Composable
private fun history(readings: LiveReadings?, type: SensorType): List<TimedReading> {
    val points by (readings?.history(type) ?: NO_HISTORY).collectAsState()
    return points
}

@Composable
private fun HeartPage(readings: LiveReadings?) {
    MetricPage(
        title = "Heart rate",
        value = latest(readings, SensorType.HEART_RATE),
        pattern = "%.0f bpm",
        scale = GaugeScales.HEART_RATE,
        trend = history(readings, SensorType.HEART_RATE),
        trendColor = TrendColors.HEART,
    )
}

@Composable
private fun ReadinessPage(readings: LiveReadings?) {
    MetricPage(
        title = "Readiness / HRV",
        value = latest(readings, SensorType.HRV_RMSSD),
        pattern = "%.0f ms",
        scale = GaugeScales.HRV_RMSSD,
        trend = history(readings, SensorType.HRV_RMSSD),
        trendColor = TrendColors.HRV,
    )
}

@Composable
private fun scores(healthScores: StateFlow<HealthScoresSnapshot?>?): HealthScoresSnapshot? {
    val snapshot by (healthScores ?: NO_SCORES).collectAsState()
    return snapshot
}

@Composable
private fun SleepScorePage(healthScores: StateFlow<HealthScoresSnapshot?>?) {
    val snapshot = scores(healthScores)
    val score = snapshot?.sleepScore?.toDouble()
    PageColumn {
        Caption("Sleep score")
        Text(format(score, "%.0f"), style = MaterialTheme.typography.display3)
        BandLabel(GaugeScales.SLEEP_SCORE, score)
        Spacer(Modifier.height(SECTION_GAP))
        ColorScaleBar(GaugeScales.SLEEP_SCORE, score, Modifier.fillMaxWidth(SCALE_WIDTH_FRACTION))
        Spacer(Modifier.height(SECTION_GAP))
        Text(snapshot?.date ?: MISSING, style = MaterialTheme.typography.caption3, color = MaterialTheme.colors.onSurfaceVariant)
    }
}

@Composable
private fun RecoveryPage(healthScores: StateFlow<HealthScoresSnapshot?>?) {
    val snapshot = scores(healthScores)
    val ratio = snapshot?.loadRatio
    PageColumn {
        Caption("Training load")
        Text(format(ratio, "%.2f"), style = MaterialTheme.typography.title1)
        BandLabel(GaugeScales.LOAD_RATIO, ratio)
        ColorScaleBar(GaugeScales.LOAD_RATIO, ratio, Modifier.fillMaxWidth(SCALE_WIDTH_FRACTION))
        Spacer(Modifier.height(SECTION_GAP))
        Text(
            "HRV ${snapshot?.hrvStatus ?: MISSING} · ${format(snapshot?.hrvWeeklyMs, "%.0f ms")}",
            style = MaterialTheme.typography.caption2,
            textAlign = TextAlign.Center,
        )
        Text(
            snapshot?.stressShares?.takeIf { it.isNotEmpty() }
                ?.joinToString(" ") { (label, share) -> "$label ${(share * PERCENT).toInt()}%" }
                ?: "Stress $MISSING",
            style = MaterialTheme.typography.caption3,
            textAlign = TextAlign.Center,
        )
        Text(
            "Daylight ${snapshot?.lightMinutes ?: MISSING}/${snapshot?.lightGoalMinutes ?: MISSING} min",
            style = MaterialTheme.typography.caption2,
        )
    }
}

@Composable
private fun SpaceWeatherPage(spaceWeather: StateFlow<SpaceWeatherSnapshot?>?) {
    val snapshot by (spaceWeather ?: NO_SPACE_WEATHER).collectAsState()
    val kp = snapshot?.currentKp
    val today = snapshot?.days?.firstOrNull()
    PageColumn {
        Caption("Geomagnetic")
        Text(format(kp, "Kp %.1f"), style = MaterialTheme.typography.title1)
        Text(
            snapshot?.currentLevel ?: MISSING,
            style = MaterialTheme.typography.caption2,
            color = kp?.let { GaugeScales.KP.bandOf(it).color } ?: MaterialTheme.colors.onSurfaceVariant,
        )
        ColorScaleBar(GaugeScales.KP, kp, Modifier.fillMaxWidth(SCALE_WIDTH_FRACTION))
        Spacer(Modifier.height(SECTION_GAP))
        Text(
            today?.radioFlux?.let { "Sun F10.7 $it · ${today.solarActivity ?: MISSING}" } ?: "Sun $MISSING",
            style = MaterialTheme.typography.caption2,
        )
        snapshot?.days.orEmpty().chunked(FORECAST_DAYS_PER_LINE).forEach { line ->
            Text(line.joinToString("  ", transform = ::forecastLabel), style = MaterialTheme.typography.caption3, textAlign = TextAlign.Center)
        }
    }
}

/** `Tue 4.7`: the day's maximum Kp. */
private fun forecastLabel(day: SpaceWeatherDaySnapshot): String =
    "${runCatching { DAY_FORMAT.format(LocalDate.parse(day.date)) }.getOrDefault(day.date)} %.1f".format(day.maxKp)

@Composable
private fun Caption(text: String) {
    Text(text, style = MaterialTheme.typography.caption1, color = MaterialTheme.colors.onSurfaceVariant)
}

@Composable
private fun BandLabel(scale: GaugeScale, value: Double?) {
    val band = value?.let(scale::bandOf)
    Text(band?.label ?: MISSING, style = MaterialTheme.typography.caption2, color = band?.color ?: MaterialTheme.colors.onSurfaceVariant)
}

@Composable
private fun BreathingPage(readings: LiveReadings?) {
    val respiration = latest(readings, SensorType.RESPIRATION_RATE)
    MetricPage(
        title = "SpO2",
        value = latest(readings, SensorType.SPO2),
        pattern = "%.0f%%",
        scale = GaugeScales.SPO2,
        trend = history(readings, SensorType.SPO2),
        trendColor = TrendColors.SPO2,
        footer = "Breathing ${format(respiration, "%.1f /min")}",
    )
}

@Composable
private fun EnvironmentPage(readings: LiveReadings?) {
    val light = latest(readings, SensorType.AMBIENT_LIGHT)
    val case = latest(readings, SensorType.AMBIENT_TEMPERATURE)
    val skin = latest(readings, SensorType.SKIN_TEMPERATURE)
    MetricPage(
        title = "Barometer",
        value = latest(readings, SensorType.BAROMETRIC_PRESSURE),
        pattern = "%.1f hPa",
        trend = history(readings, SensorType.BAROMETRIC_PRESSURE),
        trendColor = TrendColors.PRESSURE,
        footer = "${format(light, "%.0f lux")}\nCase ${format(case, "%.1f °C")} · Skin ${format(skin, "%.1f °C")}",
    )
}

@Composable
private fun SettingsPage(autoWorkoutMode: AutoWorkoutMode?, onAutoWorkoutModeClick: () -> Unit) {
    PageColumn {
        Text("Settings", style = MaterialTheme.typography.caption1, color = MaterialTheme.colors.onSurfaceVariant)
        Spacer(Modifier.height(SECTION_GAP))
        Chip(
            onClick = onAutoWorkoutModeClick,
            label = { Text("Auto walk workout") },
            secondaryLabel = { Text(autoWorkoutMode?.label ?: MISSING) },
            colors = ChipDefaults.secondaryChipColors(),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** Title, big value, the value's band on [scale] (if any), its trend, and an optional footer. */
@Composable
private fun MetricPage(
    title: String,
    value: Double?,
    pattern: String,
    trend: List<TimedReading>,
    trendColor: Color,
    scale: GaugeScale? = null,
    footer: String? = null,
) {
    PageColumn {
        Caption(title)
        Text(format(value, pattern), style = MaterialTheme.typography.display3)
        if (scale != null) {
            BandLabel(scale, value)
            Spacer(Modifier.height(SECTION_GAP))
            ColorScaleBar(scale, value, Modifier.fillMaxWidth(SCALE_WIDTH_FRACTION))
        }
        Spacer(Modifier.height(SECTION_GAP))
        Sparkline(
            points = trend,
            color = trendColor,
            window = LiveReadings.DEFAULT_HISTORY_WINDOW,
            modifier = Modifier.fillMaxWidth().height(SPARKLINE_HEIGHT),
        )
        Text("last 6 h", style = MaterialTheme.typography.caption3, color = MaterialTheme.colors.onSurfaceVariant)
        if (footer != null) {
            Spacer(Modifier.height(SECTION_GAP))
            Text(footer, style = MaterialTheme.typography.caption2, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun PageColumn(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = PAGE_PADDING),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) { content() }
}

private fun format(value: Double?, pattern: String): String = value?.let { pattern.format(it) } ?: MISSING
