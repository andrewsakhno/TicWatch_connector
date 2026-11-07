package org.sand.ticwatch.phone

import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.time.Instant
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import org.sand.ticwatch.phone.dashboard.GaugeColors
import org.sand.ticwatch.phone.dashboard.TrendChart
import org.sand.ticwatch.phone.dashboard.TrendSeries
import org.sand.ticwatch.phone.ecg.EcgRecordingKind
import org.sand.ticwatch.phone.ecg.CardiogramAnalyzer
import org.sand.ticwatch.phone.ecg.EcgAnalysis
import org.sand.ticwatch.phone.ecg.EcgBeat
import org.sand.ticwatch.phone.ecg.EcgRecording
import org.sand.ticwatch.phone.ecg.EcgRecordingLog
import org.sand.ticwatch.phone.ecg.EcgRequester
import org.sand.ticwatch.phone.ecg.EcgSessionLog
import org.sand.ticwatch.phone.ecg.EcgSessionPhase
import org.sand.ticwatch.phone.ecg.EcgSeverity
import org.sand.ticwatch.phone.ecg.EcgStrip
import org.sand.ticwatch.phone.ecg.EcgTrace
import org.sand.ticwatch.phone.ecg.EcgTrendChart
import org.sand.ticwatch.phone.ecg.EcgTrendMetric
import org.sand.ticwatch.phone.ecg.EcgTrends
import org.sand.ticwatch.phone.ecg.EcgWaveSample
import org.sand.ticwatch.phone.ecg.PulseBloodPressure
import org.sand.ticwatch.phone.ecg.PulseBloodPressureEstimator
import org.sand.ticwatch.phone.text.asString
import org.sand.ticwatch.phone.ui.rememberStore

/** What the ECG card needs. */
class EcgDependencies(
    val log: EcgRecordingLog,
    val analyzer: CardiogramAnalyzer,
    val bloodPressure: PulseBloodPressureEstimator,
    val sessions: EcgSessionLog,
    val requester: EcgRequester,
)

/** The ECG card's store, held by the screen: a lazy list disposes off-screen cards, which must not cancel a recording. */
@Composable
internal fun rememberEcgStore(dependencies: EcgDependencies): EcgStore = rememberStore(dependencies) { scope, io ->
    with(dependencies) { EcgStore(log, analyzer, bloodPressure, sessions, requester, scope, io) }
}

/**
 * Blood Pressure tab's ECG card: a strip of ECG paper, drawn live while the watch records 30 s of beats and analyzed
 * right after; tapping the paper opens the cardiograms stored on the phone.
 */
@Composable
internal fun EcgCard(store: EcgStore) {
    val state by store.state.collectAsState()
    var isListOpen by rememberSaveable { mutableStateOf(false) }
    val live = state.live
    // Night pulse strips come every 20 min in sleep; the card keeps showing the last requested strip.
    val latest = state.recordings?.firstOrNull { it.recording.kind == EcgRecordingKind.REQUESTED }
    val recording = live?.takeIf { it.phase == EcgSessionPhase.RECORDING }
    val isRecording = recording != null

    SectionCard(stringResource(R.string.card_ecg)) {
        val paperModifier = Modifier.clickable { isListOpen = true }
        when {
            live != null -> LiveEcgPaper(live.beats, state.liveAnalysis, isRecording, paperModifier)
            latest != null -> RecordedEcgPaper(latest.recording, latest.analysis, paperModifier)
            else -> EcgPaper(EcgStrip.of(emptyList(), null), emptySet(), paperModifier, minDurationMs = STRIP_MS)
        }
        Text(
            text = stringResource(R.string.ecg_paper_hint, paperLegend(live?.wave ?: latest?.recording?.wave), state.recordings?.size ?: 0),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = store::record, enabled = !isRecording) {
            Text(text = recording?.let { recordingLabel(it.requestedAt, it.beats.isEmpty()) } ?: stringResource(R.string.ecg_record))
        }
        if (isRecording) {
            Text(text = stringResource(R.string.ecg_sit_still), style = MaterialTheme.typography.bodySmall)
        }
        live?.takeIf { it.phase == EcgSessionPhase.FAILED }?.message?.let { Text(text = it.asString(), color = GaugeColors.POOR) }
        state.message?.let { Text(text = it.asString(), color = GaugeColors.POOR) }
        when {
            live?.phase == EcgSessionPhase.FINISHED -> state.liveAnalysis?.let { EcgAnalysisView(it, state.liveBloodPressure) }
            live == null && latest != null -> {
                Text(text = stringResource(R.string.ecg_last, formatTime(latest.recording.startedAt)), style = MaterialTheme.typography.bodySmall)
                EcgAnalysisView(latest.analysis, latest.bloodPressure)
            }
        }
    }

    if (isListOpen) {
        EcgRecordingsDialog(state.recordings.orEmpty(), state.trends, onDelete = store::delete) { isListOpen = false }
    }
}

/** The complexes are illustrative; with a pulse wave their timing follows the real heartbeats. */
@Composable
private fun paperLegend(wave: List<EcgWaveSample>?): String =
    stringResource(if (wave.isNullOrEmpty()) R.string.ecg_legend_illustrative else R.string.ecg_legend_pulse)

/** Ticking "Recording..." label: waits for the pulse to lock on, then counts the strip's seconds. */
@Composable
private fun recordingLabel(requestedAt: Instant, waitingForPulse: Boolean): String {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(requestedAt) {
        while (true) {
            delay(TICK_MS)
            now = System.currentTimeMillis()
        }
    }
    val elapsed = ((now - requestedAt.toEpochMilli()) / MS_PER_SECOND).coerceAtLeast(0)
    return stringResource(if (waitingForPulse) R.string.ecg_finding_pulse else R.string.ecg_recording, elapsed)
}

/**
 * The live strip: a heart beats over blank paper for the whole recording; the cardiogram is drawn once the strip is
 * finished and its pulse wave analyzed, so the beats sit where the heart beat rather than where the batches arrived.
 */
@Composable
private fun LiveEcgPaper(beats: List<EcgBeat>, analysis: EcgAnalysis?, isRecording: Boolean, modifier: Modifier) {
    val strip = remember(beats, analysis, isRecording) { EcgStrip.of(if (isRecording) emptyList() else beats, analysis?.pulse) }
    Box(contentAlignment = Alignment.Center) {
        EcgPaper(strip, analysis?.prematureBeatIndices.orEmpty(), modifier, minDurationMs = STRIP_MS)
        if (isRecording) CollectingPulseAnimation()
    }
}

@Composable
private fun RecordedEcgPaper(recording: EcgRecording, analysis: EcgAnalysis, modifier: Modifier = Modifier) {
    val strip = remember(recording, analysis) { EcgStrip.of(recording.beats, analysis.pulse) }
    EcgPaper(strip, analysis.prematureBeatIndices, modifier)
}

/** A beating heart with a scan line sweeping the paper: the watch is collecting the pulse. */
@Composable
private fun BoxScope.CollectingPulseAnimation() {
    val transition = rememberInfiniteTransition(label = "findingPulse")
    val beat by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(durationMillis = HEARTBEAT_MS, easing = LinearEasing)),
        label = "heartbeat",
    )
    val sweep by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(durationMillis = SWEEP_MS, easing = LinearEasing)),
        label = "sweep",
    )
    Canvas(modifier = Modifier.matchParentSize()) {
        val x = size.width * sweep
        val scanWidth = size.width * SCAN_WIDTH
        drawRect(
            Brush.horizontalGradient(listOf(Color.Transparent, SCAN), startX = x - scanWidth, endX = x),
            topLeft = Offset(x - scanWidth, 0f),
            size = Size(scanWidth, size.height),
        )
        drawHeart(center, size.minDimension * HEART_SIZE * heartScale(beat))
    }
}

/** Lub-dub: two quick swells per cycle, then rest. */
private fun heartScale(phase: Float): Float {
    fun swell(at: Float, height: Float) = (1f - abs(phase - at) / SWELL_WIDTH).coerceAtLeast(0f) * height
    return 1f + swell(LUB_AT, LUB_SWELL) + swell(DUB_AT, DUB_SWELL)
}

private fun DrawScope.drawHeart(center: Offset, size: Float) {
    val half = size / 2
    val path = Path().apply {
        moveTo(center.x, center.y + half)
        cubicTo(center.x - size, center.y, center.x - half, center.y - size * HEART_LOBE, center.x, center.y - half * HEART_NOTCH)
        cubicTo(center.x + half, center.y - size * HEART_LOBE, center.x + size, center.y, center.x, center.y + half)
        close()
    }
    drawPath(path, MARK)
}

/**
 * ECG paper: 1 mm minor and 5 mm major grid, [ROW_MS] per row at 25 mm/s, 10 mm/mV. [markedBeats] (premature) get a red dot under their beat.
 */
@Composable
internal fun EcgPaper(
    strip: EcgStrip,
    markedBeats: Set<Int>,
    modifier: Modifier = Modifier,
    minDurationMs: Long = ROW_MS,
) {
    val rows = ceil(maxOf(strip.durationMs, minDurationMs, ROW_MS) / ROW_MS.toDouble()).toInt()
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val height = maxWidth * (rows * ROW_HEIGHT_MM / ROW_WIDTH_MM)
        Canvas(modifier = Modifier.fillMaxWidth().height(height)) {
            val mm = size.width / ROW_WIDTH_MM
            drawRect(PAPER)
            drawGrid(mm)
            val end = strip.durationMs.coerceAtMost(rows * ROW_MS)
            for (row in 0 until rows) drawTraceRow(strip.trace, row, mm, end)
            markedBeats.forEach { index ->
                val time = strip.beatTimeMs(index)
                if (time in 0..end) drawCircle(MARK, radius = mm * MARK_RADIUS_MM, center = beatPoint(time, mm, MARK_DEPTH_MM))
            }
        }
    }
}

private fun DrawScope.drawGrid(mm: Float) {
    var index = 0
    var x = 0f
    while (x <= size.width) {
        val major = index % MAJOR_EVERY == 0
        drawLine(if (major) GRID_MAJOR else GRID_MINOR, Offset(x, 0f), Offset(x, size.height), strokeWidth = if (major) MAJOR_STROKE else 1f)
        x += mm
        index++
    }
    index = 0
    var y = 0f
    while (y <= size.height) {
        val major = index % MAJOR_EVERY == 0
        drawLine(if (major) GRID_MAJOR else GRID_MINOR, Offset(0f, y), Offset(size.width, y), strokeWidth = if (major) MAJOR_STROKE else 1f)
        y += mm
        index++
    }
}

private fun DrawScope.drawTraceRow(trace: EcgTrace, row: Int, mm: Float, endMs: Long) {
    val rowStart = row * ROW_MS
    if (rowStart >= endMs) return
    val rowEnd = minOf(rowStart + ROW_MS, endMs)
    val baseline = (row * ROW_HEIGHT_MM + BASELINE_MM) * mm
    val stepMs = MS_PER_MM / SAMPLES_PER_MM
    val path = Path()
    var penDown = false
    var t = rowStart.toDouble()
    while (t <= rowEnd) {
        val millivolts = trace.millivoltsAt(t)
        if (millivolts != null) {
            val x = ((t - rowStart) / MS_PER_MM * mm).toFloat()
            val y = baseline - (millivolts * MM_PER_MV * mm).toFloat()
            if (penDown) path.lineTo(x, y) else path.moveTo(x, y)
        }
        penDown = millivolts != null
        t += stepMs
    }
    drawPath(path, TRACE, style = Stroke(width = mm * TRACE_WIDTH_MM))
}

private fun rowBaseline(timeMs: Long, mm: Float): Float = ((timeMs / ROW_MS) * ROW_HEIGHT_MM + BASELINE_MM) * mm

private fun beatPoint(timeMs: Long, mm: Float, depthMm: Float): Offset =
    Offset(((timeMs % ROW_MS) / MS_PER_MM * mm).toFloat(), rowBaseline(timeMs, mm) + depthMm * mm)

/** The analyzer's reading of one strip. */
@Composable
internal fun EcgAnalysisView(analysis: EcgAnalysis, bloodPressure: PulseBloodPressure?) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = stringResource(analysis.rhythm.label),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = severityColor(analysis.rhythm.severity),
        )
        analysis.meanHeartRate?.let { mean ->
            val range = if (analysis.minHeartRate != null && analysis.maxHeartRate != null) {
                stringResource(R.string.ecg_hr_range, analysis.minHeartRate.roundToInt(), analysis.maxHeartRate.roundToInt())
            } else {
                ""
            }
            Text(text = stringResource(R.string.ecg_hr_line, mean.roundToInt(), range, analysis.beatCount), style = MaterialTheme.typography.bodyMedium)
        }
        if (analysis.rmssdMs != null && analysis.sdnnMs != null) {
            Text(
                text = stringResource(
                    R.string.ecg_hrv_line, analysis.rmssdMs.roundToInt(), analysis.sdnnMs.roundToInt(), ((analysis.pnn50 ?: 0.0) * PERCENT).roundToInt(),
                ),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Text(
            text = stringResource(
                R.string.ecg_counts_line, analysis.prematureBeats, analysis.pauses,
                (analysis.signalQuality * PERCENT).roundToInt(), (analysis.confidence * PERCENT).roundToInt(),
            ),
            style = MaterialTheme.typography.bodySmall,
        )
        bloodPressure?.let { PulseBloodPressureLine(it) }
        analysis.findings.forEach { finding ->
            Text(text = stringResource(R.string.bullet, finding.text.asString()), style = MaterialTheme.typography.bodySmall, color = severityColor(finding.severity))
        }
    }
}

/** The personal pulse-wave blood pressure, or how many cuff pairs it still needs. */
@Composable
private fun PulseBloodPressureLine(pressure: PulseBloodPressure) {
    when (pressure) {
        is PulseBloodPressure.Estimate -> Text(
            text = stringResource(
                R.string.ecg_pulse_bp, pressure.systolic.roundToInt(), pressure.diastolic.roundToInt(), pressure.pairs,
                pressure.systolicErrorMmHg.roundToInt(),
            ),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
        )
        is PulseBloodPressure.NeedsCalibration -> Text(
            text = stringResource(R.string.ecg_pulse_bp_learning, pressure.pairs, pressure.required),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun severityColor(severity: EcgSeverity): Color = when (severity) {
    EcgSeverity.NORMAL -> MaterialTheme.colorScheme.onSurface
    EcgSeverity.NOTICE -> NOTICE
    EcgSeverity.WARNING -> GaugeColors.POOR
}

/** Full-screen list of stored cardiograms, newest first; a tap opens one with its paper and full analysis. */
@Composable
private fun EcgRecordingsDialog(recordings: List<AnalyzedEcg>, trends: EcgTrends?, onDelete: (EcgRecording) -> Unit, onDismiss: () -> Unit) {
    var selectedId by rememberSaveable { mutableStateOf<Long?>(null) }
    var isTrendsOpen by rememberSaveable { mutableStateOf(false) }
    val selected = recordings.firstOrNull { it.recording.id == selectedId }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize()) {
            if (isTrendsOpen && trends != null) {
                EcgTrendsPage(trends) { isTrendsOpen = false }
            } else if (selected == null) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(text = stringResource(R.string.ecg_cardiograms), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                        if (recordings.isNotEmpty() && trends != null) {
                            TextButton(onClick = { isTrendsOpen = true }) { Text(text = stringResource(R.string.ecg_trends)) }
                        }
                        TextButton(onClick = onDismiss) { Text(text = stringResource(R.string.close)) }
                    }
                    if (recordings.isEmpty()) Text(text = stringResource(R.string.ecg_none))
                    LazyColumn {
                        items(recordings, key = { it.recording.id }) { item ->
                            Column(modifier = Modifier.fillMaxWidth().clickable { selectedId = item.recording.id }.padding(vertical = 12.dp)) {
                                Text(text = stripTitle(item.recording), style = MaterialTheme.typography.titleSmall)
                                Text(
                                    text = listOfNotNull(
                                        item.analysis.meanHeartRate?.let { stringResource(R.string.bpm_int, it.roundToInt()) },
                                        stringResource(item.analysis.rhythm.label),
                                    ).joinToString(" · "),
                                    color = severityColor(item.analysis.rhythm.severity),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                            HorizontalDivider()
                        }
                    }
                }
            } else {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(text = stripTitle(selected.recording), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                        TextButton(onClick = { selectedId = null }) { Text(text = stringResource(R.string.back)) }
                    }
                    RecordedEcgPaper(selected.recording, selected.analysis)
                    Text(
                        text = stringResource(R.string.ecg_premature_dots, paperLegend(selected.recording.wave)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    EcgAnalysisView(selected.analysis, selected.bloodPressure)
                    TextButton(onClick = {
                        onDelete(selected.recording)
                        selectedId = null
                    }) { Text(text = stringResource(R.string.delete)) }
                }
            }
        }
    }
}

/** Charts of every stored strip's rhythm regularity, pulse wave and variability, oldest to newest. */
@Composable
private fun EcgTrendsPage(trends: EcgTrends, onBack: () -> Unit) {
    Column(
        modifier = Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text = stringResource(R.string.ecg_trends), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            TextButton(onClick = onBack) { Text(text = stringResource(R.string.back)) }
        }
        EcgTrendChart.entries.forEach { chart ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(text = stringResource(chart.title), style = MaterialTheme.typography.titleSmall)
                TrendChart(
                    series = chart.metrics.map { metric ->
                        TrendSeries(stringResource(metric.label), trends.series.getValue(metric), TREND_COLORS.getValue(metric))
                    },
                    formatTime = ::formatTime,
                    modifier = Modifier.fillMaxWidth().height(TREND_CHART_HEIGHT),
                )
            }
        }
    }
}

/** Paper geometry: real ECG paper is printed at 25 mm/s and 10 mm/mV. */
private const val ROW_MS = 6_000L
private const val STRIP_MS = 30_000L
private const val MS_PER_MM = 40.0
private const val ROW_WIDTH_MM = ROW_MS / MS_PER_MM.toFloat()
private const val ROW_HEIGHT_MM = 20f
private const val BASELINE_MM = 13f
private const val MM_PER_MV = 10.0
private const val MAJOR_EVERY = 5
private const val MAJOR_STROKE = 2f
private const val SAMPLES_PER_MM = 2.0
private const val TRACE_WIDTH_MM = 0.35f
private const val MARK_RADIUS_MM = 0.7f
private const val MARK_DEPTH_MM = 4f

private const val TICK_MS = 1_000L

/** Collecting-the-pulse animation: a 75 bpm lub-dub and a 2 s scan sweep. */
private const val HEARTBEAT_MS = 800
private const val SWEEP_MS = 2_000
private const val SCAN_WIDTH = 0.15f
private const val HEART_SIZE = 0.28f
private const val HEART_LOBE = 0.75f
private const val HEART_NOTCH = 0.4f
private const val LUB_AT = 0.1f
private const val DUB_AT = 0.3f
private const val SWELL_WIDTH = 0.1f
private const val LUB_SWELL = 0.25f
private const val DUB_SWELL = 0.12f
private const val MS_PER_SECOND = 1_000L
private const val PERCENT = 100

private val PAPER = Color(0xFFFFF4F1)
private val GRID_MINOR = Color(0xFFF6CDC6)
private val GRID_MAJOR = Color(0xFFE58C80)
private val TRACE = Color(0xFF1B1B1B)
private val MARK = Color(0xFFD32F2F)
private val SCAN = Color(0x55D32F2F)

/** Darker than the gauges' amber, which is unreadable as text on a light card. */
private val NOTICE = Color(0xFFE65100)

private val TREND_CHART_HEIGHT = 140.dp
private val TREND_COLORS = mapOf(
    EcgTrendMetric.REGULAR_RHYTHM to GaugeColors.GOOD,
    EcgTrendMetric.PERFUSION_INDEX to Color(0xFFD32F2F),
    EcgTrendMetric.RISE_TIME to Color(0xFF7E57C2),
    EcgTrendMetric.RMSSD to GaugeColors.REST,
    EcgTrendMetric.SDNN to Color(0xFFFF7043),
    EcgTrendMetric.PNN50 to Color(0xFF26A69A),
)

/** The strip's time, marked when the watch recorded it in sleep for the pulse-wave blood pressure. */
@Composable
private fun stripTitle(recording: EcgRecording): String = when (recording.kind) {
    EcgRecordingKind.REQUESTED -> formatTime(recording.startedAt)
    EcgRecordingKind.NIGHT_PULSE -> stringResource(R.string.ecg_night_pulse_title, formatTime(recording.startedAt))
}
