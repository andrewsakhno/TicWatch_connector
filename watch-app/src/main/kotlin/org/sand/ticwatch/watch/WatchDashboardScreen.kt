package org.sand.ticwatch.watch

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.foundation.pager.HorizontalPager
import androidx.wear.compose.foundation.pager.rememberPagerState
import androidx.wear.compose.foundation.rotary.RotaryScrollableDefaults
import androidx.wear.compose.foundation.rotary.rotaryScrollable
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.HorizontalPageIndicator
import androidx.wear.compose.material3.HorizontalPagerScaffold
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SwitchButton
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TimeText
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.sand.ticwatch.watch.autoworkout.AutoWorkoutMode
import org.sand.ticwatch.watch.dashboard.ColorScaleBar
import org.sand.ticwatch.watch.dashboard.GaugeScale
import org.sand.ticwatch.watch.dashboard.GaugeScales
import org.sand.ticwatch.watch.dashboard.Sparkline
import org.sand.ticwatch.watch.readiness.CardioReadinessSnapshot
import org.sand.ticwatch.watch.scores.HealthScoresSnapshot
import org.sand.ticwatch.watch.screenshot.DashboardPageNavigator
import org.sand.ticwatch.watch.sensors.LiveReadings
import org.sand.ticwatch.watch.sensors.SensorType
import org.sand.ticwatch.watch.sensors.TimedReading
import org.sand.ticwatch.watch.settings.SnoreMicrophone
import org.sand.ticwatch.watch.spaceweather.SpaceWeatherDaySnapshot
import org.sand.ticwatch.watch.spaceweather.SpaceWeatherSnapshot
import org.sand.ticwatch.watch.text.inCurrentLocale
import org.sand.ticwatch.watch.ui.DomedDialSurface

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
private val SETTINGS_HORIZONTAL_PADDING = 10.dp
private val SETTINGS_VERTICAL_PADDING = 24.dp
private const val SCALE_WIDTH_FRACTION = 0.8f

/**
 * Main app screen: swipeable pages ([cardioReadiness] from the phone first, then heart, readiness, breathing, environment - each with a colored
 * scale and/or a trend of the service's in-memory history; sleep score and recovery from the phone's
 * [healthScores], null until the phone sent them; NOAA Kp / solar flux from the phone's [spaceWeather]; [bloodPressure] readings and its "Measure" button, the [snapshot] button - then settings, where
 * [onAutoWorkoutModeClick] switches the automatic walk workout to the next mode and [onLogCaptureChange] toggles
 * the on-watch log capture; [snoreAnalysis] switches sleep snoring analysis and picks its microphone). Readings are null
 * until the service binds.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun WatchDashboardScreen(
    readings: LiveReadings?,
    cardioReadiness: StateFlow<CardioReadinessSnapshot?>?,
    healthScores: StateFlow<HealthScoresSnapshot?>?,
    spaceWeather: StateFlow<SpaceWeatherSnapshot?>?,
    bloodPressure: BloodPressurePageState,
    autoWorkoutMode: AutoWorkoutMode?,
    onAutoWorkoutModeClick: () -> Unit,
    logCapture: Boolean?,
    onLogCaptureChange: (Boolean) -> Unit,
    snoreAnalysis: SnoreAnalysisState,
    snapshot: SnapshotPageState,
    pageNavigator: DashboardPageNavigator? = null,
) {
    // Each page gets whether it is the pager's current one (scrollable pages take the crown's focus then).
    val pages: List<@Composable (Boolean) -> Unit> = listOf(
        { CardioReadinessPage(cardioReadiness) },
        { HeartPage(readings) },
        { ReadinessPage(readings) },
        { BloodPressurePage(bloodPressure) },
        { SnapshotPage(snapshot) },
        { SleepScorePage(healthScores) },
        { FatiguePage(healthScores) },
        { RecoveryPage(healthScores) },
        { BreathingPage(readings) },
        { EnvironmentPage(readings) },
        { SpaceWeatherPage(spaceWeather) },
        { active -> SettingsPage(active, autoWorkoutMode, onAutoWorkoutModeClick, logCapture, onLogCaptureChange, snoreAnalysis) },
    )
    val pagerState = rememberPagerState { pages.size }
    if (pageNavigator != null) {
        LaunchedEffect(pageNavigator, pages.size) {
            pageNavigator.pageCount = pages.size
            pageNavigator.requests.collect { pagerState.scrollToPage(it) }
        }
    }
    DomedDialSurface {
        AppScaffold(
            timeText = { TimeText() },
            containerColor = Color.Transparent,
            contentColor = MaterialTheme.colorScheme.onBackground,
        ) {
            HorizontalPagerScaffold(pagerState = pagerState, pageIndicator = { HorizontalPageIndicator(pagerState = pagerState) }) {
                HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page -> pages[page](page == pagerState.currentPage) }
            }
        }
    }
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
        title = stringResource(R.string.heart_rate),
        value = latest(readings, SensorType.HEART_RATE),
        pattern = stringResource(R.string.pattern_bpm),
        scale = GaugeScales.HEART_RATE,
        trend = history(readings, SensorType.HEART_RATE),
        trendColor = TrendColors.HEART,
    )
}

@Composable
private fun ReadinessPage(readings: LiveReadings?) {
    MetricPage(
        title = stringResource(R.string.readiness_hrv),
        value = latest(readings, SensorType.HRV_RMSSD),
        pattern = stringResource(R.string.pattern_ms),
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
        Caption(stringResource(R.string.sleep_score))
        Text(format(score, "%.0f"), style = MaterialTheme.typography.numeralSmall)
        BandLabel(GaugeScales.SLEEP_SCORE, score)
        Spacer(Modifier.height(SECTION_GAP))
        ColorScaleBar(GaugeScales.SLEEP_SCORE, score, Modifier.fillMaxWidth(SCALE_WIDTH_FRACTION))
        Spacer(Modifier.height(SECTION_GAP))
        Text(snapshot?.date ?: MISSING, style = MaterialTheme.typography.bodyExtraSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun FatiguePage(healthScores: StateFlow<HealthScoresSnapshot?>?) {
    val snapshot = scores(healthScores)
    val percent = snapshot?.fatiguePercent?.toDouble()
    PageColumn {
        Caption(stringResource(R.string.fatigue_index))
        Text(format(percent, "%.0f%%"), style = MaterialTheme.typography.numeralSmall)
        BandLabel(GaugeScales.FATIGUE_INDEX, percent)
        Spacer(Modifier.height(SECTION_GAP))
        ColorScaleBar(GaugeScales.FATIGUE_INDEX, percent, Modifier.fillMaxWidth(SCALE_WIDTH_FRACTION))
        Spacer(Modifier.height(SECTION_GAP))
        Text(snapshot?.date ?: MISSING, style = MaterialTheme.typography.bodyExtraSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun RecoveryPage(healthScores: StateFlow<HealthScoresSnapshot?>?) {
    val snapshot = scores(healthScores)
    val ratio = snapshot?.loadRatio
    PageColumn {
        Caption(stringResource(R.string.training_load))
        Text(format(ratio, "%.2f"), style = MaterialTheme.typography.displaySmall)
        BandLabel(GaugeScales.LOAD_RATIO, snapshot?.loadGaugeRatio)
        ColorScaleBar(GaugeScales.LOAD_RATIO, snapshot?.loadGaugeRatio, Modifier.fillMaxWidth(SCALE_WIDTH_FRACTION))
        Spacer(Modifier.height(SECTION_GAP))
        Text(
            stringResource(R.string.hrv_line, snapshot?.hrvStatus ?: MISSING, format(snapshot?.hrvWeeklyMs, stringResource(R.string.pattern_ms))),
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
        )
        Text(
            snapshot?.stressShares?.takeIf { it.isNotEmpty() }
                ?.map { (label, share) -> "${stringResource(label)} ${(share * PERCENT).toInt()}%" }?.joinToString(" ")
                ?: stringResource(R.string.stress_missing, MISSING),
            style = MaterialTheme.typography.bodyExtraSmall,
            textAlign = TextAlign.Center,
        )
        Text(
            stringResource(R.string.daylight_line, snapshot?.lightMinutes?.toString() ?: MISSING, snapshot?.lightGoalMinutes?.toString() ?: MISSING),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun SpaceWeatherPage(spaceWeather: StateFlow<SpaceWeatherSnapshot?>?) {
    val snapshot by (spaceWeather ?: NO_SPACE_WEATHER).collectAsState()
    val kp = snapshot?.currentKp
    val today = snapshot?.days?.firstOrNull()
    PageColumn {
        Caption(stringResource(R.string.geomagnetic))
        Text(format(kp, "Kp %.1f"), style = MaterialTheme.typography.displaySmall)
        Text(
            snapshot?.currentLevel ?: MISSING,
            style = MaterialTheme.typography.bodySmall,
            color = kp?.let { GaugeScales.KP.bandOf(it).color } ?: MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ColorScaleBar(GaugeScales.KP, kp, Modifier.fillMaxWidth(SCALE_WIDTH_FRACTION))
        Spacer(Modifier.height(SECTION_GAP))
        Text(
            today?.radioFlux?.let { stringResource(R.string.sun_flux, it, today.solarActivity ?: MISSING) } ?: stringResource(R.string.sun_missing, MISSING),
            style = MaterialTheme.typography.bodySmall,
        )
        snapshot?.days.orEmpty().chunked(FORECAST_DAYS_PER_LINE).forEach { line ->
            Text(line.joinToString("  ", transform = ::forecastLabel), style = MaterialTheme.typography.bodyExtraSmall, textAlign = TextAlign.Center)
        }
    }
}

/** `Tue 4.7`: the day's maximum Kp. */
private fun forecastLabel(day: SpaceWeatherDaySnapshot): String =
    "${runCatching { DAY_FORMAT.inCurrentLocale().format(LocalDate.parse(day.date)) }.getOrDefault(day.date)} %.1f".format(day.maxKp)

@Composable
internal fun Caption(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun BandLabel(scale: GaugeScale, value: Double?) {
    val band = value?.let(scale::bandOf)
    Text(band?.let { stringResource(it.label) } ?: MISSING, style = MaterialTheme.typography.bodySmall, color = band?.color ?: MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun BreathingPage(readings: LiveReadings?) {
    val respiration = latest(readings, SensorType.RESPIRATION_RATE)
    MetricPage(
        title = stringResource(R.string.spo2),
        value = latest(readings, SensorType.SPO2),
        pattern = "%.0f%%",
        scale = GaugeScales.SPO2,
        trend = history(readings, SensorType.SPO2),
        trendColor = TrendColors.SPO2,
        footer = stringResource(R.string.breathing_line, format(respiration, stringResource(R.string.pattern_per_min))),
    )
}

@Composable
private fun EnvironmentPage(readings: LiveReadings?) {
    val light = latest(readings, SensorType.AMBIENT_LIGHT)
    val case = latest(readings, SensorType.AMBIENT_TEMPERATURE)
    val skin = latest(readings, SensorType.SKIN_TEMPERATURE)
    MetricPage(
        title = stringResource(R.string.barometer),
        value = latest(readings, SensorType.BAROMETRIC_PRESSURE),
        pattern = stringResource(R.string.pattern_hpa),
        trend = history(readings, SensorType.BAROMETRIC_PRESSURE),
        trendColor = TrendColors.PRESSURE,
        footer = stringResource(
            R.string.environment_footer, format(light, stringResource(R.string.pattern_lux)),
            format(case, stringResource(R.string.pattern_celsius)), format(skin, stringResource(R.string.pattern_celsius)),
        ),
    )
}

/**
 * Settings as a [ScalingLazyColumn]: scrolls by finger and, while [active] (the pager's current page), by the
 * watch's rotating crown/bezel, with a position indicator on the edge.
 */
@Composable
private fun SettingsPage(
    active: Boolean,
    autoWorkoutMode: AutoWorkoutMode?,
    onAutoWorkoutModeClick: () -> Unit,
    logCapture: Boolean?,
    onLogCaptureChange: (Boolean) -> Unit,
    snoreAnalysis: SnoreAnalysisState,
) {
    val listState = rememberScalingLazyListState()
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(active) { if (active) focusRequester.requestFocus() }
    ScreenScaffold(scrollState = listState) {
        ScalingLazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .rotaryScrollable(RotaryScrollableDefaults.behavior(listState), focusRequester),
            contentPadding = PaddingValues(horizontal = SETTINGS_HORIZONTAL_PADDING, vertical = SETTINGS_VERTICAL_PADDING),
            verticalArrangement = Arrangement.spacedBy(SECTION_GAP),
            rotaryScrollableBehavior = null,
        ) {
            item { Caption(stringResource(R.string.settings)) }
            item {
                Button(
                    onClick = onAutoWorkoutModeClick,
                    label = { Text(stringResource(R.string.auto_walk_workout)) },
                    secondaryLabel = { Text(autoWorkoutMode?.let { stringResource(it.label) } ?: MISSING) },
                    colors = ButtonDefaults.filledTonalButtonColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                SwitchButton(
                    checked = logCapture == true,
                    onCheckedChange = onLogCaptureChange,
                    enabled = logCapture != null,
                    label = { Text(stringResource(R.string.log_capture)) },
                    secondaryLabel = { Text(stringResource(R.string.log_capture_hint)) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                SwitchButton(
                    checked = snoreAnalysis.enabled == true,
                    onCheckedChange = snoreAnalysis.onEnabledChange,
                    enabled = snoreAnalysis.enabled != null,
                    label = { Text(stringResource(R.string.snore_analysis)) },
                    secondaryLabel = { Text(stringResource(R.string.snore_analysis_hint)) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                Button(
                    onClick = snoreAnalysis.onMicrophoneClick,
                    enabled = snoreAnalysis.microphone != null,
                    label = { Text(stringResource(R.string.snore_microphone)) },
                    secondaryLabel = { Text(snoreAnalysis.microphone?.let { stringResource(it.label) } ?: MISSING) },
                    colors = ButtonDefaults.filledTonalButtonColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/** Snore analysis switch and microphone choice (the watch's costs battery); null values until the settings load. */
class SnoreAnalysisState(
    val enabled: Boolean?,
    val microphone: SnoreMicrophone?,
    val onEnabledChange: (Boolean) -> Unit,
    val onMicrophoneClick: () -> Unit,
)

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
        Text(format(value, pattern), style = MaterialTheme.typography.numeralSmall)
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
        Text(stringResource(R.string.last_6h), style = MaterialTheme.typography.bodyExtraSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (footer != null) {
            Spacer(Modifier.height(SECTION_GAP))
            Text(footer, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
        }
    }
}

@Composable
internal fun PageColumn(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = PAGE_PADDING),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) { content() }
}

private fun format(value: Double?, pattern: String): String = value?.let { pattern.format(it) } ?: MISSING
