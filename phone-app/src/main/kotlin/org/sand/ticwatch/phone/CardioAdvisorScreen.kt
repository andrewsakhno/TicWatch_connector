package org.sand.ticwatch.phone

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.pullToRefresh
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import org.sand.ticwatch.phone.advisor.Advice
import org.sand.ticwatch.phone.advisor.AdvisorIntent
import org.sand.ticwatch.phone.advisor.AutonomicBalance
import org.sand.ticwatch.phone.advisor.AutonomicComponent
import org.sand.ticwatch.phone.advisor.AutonomicTone
import org.sand.ticwatch.phone.advisor.CardioAdvisorStore
import org.sand.ticwatch.phone.advisor.CardioReadinessSource
import org.sand.ticwatch.phone.advisor.ReadinessAssessment
import org.sand.ticwatch.phone.advisor.ReadinessFactor
import org.sand.ticwatch.phone.advisor.ReadinessForecastHour
import org.sand.ticwatch.phone.advisor.ReadinessGaugeArc
import org.sand.ticwatch.phone.advisor.ReadinessPalette
import org.sand.ticwatch.phone.advisor.ReadinessState
import org.sand.ticwatch.phone.advisor.Vitals
import org.sand.ticwatch.phone.health.SampleSource
import org.sand.ticwatch.phone.insights.FatigueComponent
import org.sand.ticwatch.phone.insights.FatigueIndex
import org.sand.ticwatch.phone.insights.FatigueLevel
import org.sand.ticwatch.phone.medication.MedicationProtection
import org.sand.ticwatch.phone.sleep.VagalRecovery
import org.sand.ticwatch.phone.sleep.VagalRecoveryStatus
import org.sand.ticwatch.phone.text.asString
import org.sand.ticwatch.phone.ui.rememberStore

private const val MAX_SCORE = ReadinessGaugeArc.MAX_SCORE
private const val GAUGE_ANIMATION_MS = 900
private const val ADVICE_TINT_ALPHA = 0.18f
private const val MISSING_VALUE = "--"
private const val PULSE_MS = 800
private const val PULSE_MIN_ALPHA = 0.35f
private val GAUGE_SIZE = 220.dp
private val GAUGE_STROKE = 12.dp
private val GAUGE_RING_GAP = 4.dp
private val OUTER_SCORE_SIZE = 32.sp
private val INNER_SCORE_SIZE = 22.sp
/** Material's default is 80 dp; the pull is damped by half, so this takes about 280 dp of finger travel. */
private val PULL_REFRESH_THRESHOLD = 140.dp
private val HOUR_CELL_WIDTH = 44.dp
private val HOUR_BAR_HEIGHT = 28.dp
private val HOUR_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH")
private val WINDOW_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
private const val WALK_MARK = "🚶"

private fun ReadinessState.color(): Color = Color(ReadinessPalette.argb(this))

/** Dashboard "Advisor" tab: cardio readiness now, the advice derived from it, its factors and the next hours. */
@Composable
internal fun CardioAdvisorScreen(source: CardioReadinessSource, changes: Flow<*>) {
    val store = rememberStore(source) { scope, io -> CardioAdvisorStore(source, changes, scope, io) }
    val state by store.state.collectAsState()

    SwipeDownToRefresh(
        refresh = {
            store.dispatch(AdvisorIntent.Refresh)
            store.state.first { !it.loading }
        },
    ) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (state.loading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            state.error?.let { Text(text = stringResource(R.string.could_not_load, it), color = MaterialTheme.colorScheme.error) }
            state.readiness?.let { readiness ->
                ReadinessGaugeCard(readiness.assessment, readiness.fatigue, readiness.autonomic)
                AdviceCard(readiness.assessment.advice, readiness.assessment.state)
                AutonomicToneCard(readiness.autonomic)
                ProtectionCard(readiness.medication, readiness.recovery)
                VitalsCard(readiness.vitals)
                FactorsCard(readiness.assessment, state.factorsExpanded) { store.dispatch(AdvisorIntent.ToggleFactors) }
                ForecastCard(readiness.forecast)
            }
            Text(
                text = stringResource(R.string.advisor_disclaimer),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/**
 * Swipe-down refresh with a raised threshold. The spinner shows only for a pull, not for the store's reloads on data
 * pushes. The indicator hides only when `isRefreshing` goes from true to false, so [refresh] runs from an effect: the
 * modifier has already seen true by then, however fast the load finishes.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeDownToRefresh(refresh: suspend () -> Unit, content: @Composable () -> Unit) {
    val pullState = rememberPullToRefreshState()
    var refreshing by remember { mutableStateOf(false) }
    LaunchedEffect(refreshing) {
        if (refreshing) {
            refresh()
            refreshing = false
        }
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .pullToRefresh(
                isRefreshing = refreshing,
                state = pullState,
                threshold = PULL_REFRESH_THRESHOLD,
                onRefresh = { refreshing = true },
            ),
    ) {
        content()
        PullToRefreshDefaults.Indicator(
            state = pullState,
            isRefreshing = refreshing,
            threshold = PULL_REFRESH_THRESHOLD,
            modifier = Modifier.align(Alignment.TopCenter),
        )
    }
}

/**
 * One gauge with three rings: cardio readiness outside, load readiness (the inverse of the fatigue index, itemised below) in
 * the middle, nervous tone inside - pulsing amber while a post-stress window is active.
 */
@Composable
private fun ReadinessGaugeCard(assessment: ReadinessAssessment, fatigue: FatigueIndex?, autonomic: AutonomicBalance?) = SectionCard(stringResource(R.string.card_readiness_gauge)) {
    val cardioColor = assessment.state.color()
    val loadColor = fatigue?.level.gaugeColor()
    val autonomicColor = autonomic?.tone.gaugeColor()
    RingGauge(
        listOf(
            GaugeRing(assessment.score, cardioColor),
            GaugeRing(fatigue?.loadReadinessPercent, loadColor),
            GaugeRing(autonomic?.percent, autonomicColor, pulsing = autonomic?.postStressActive == true),
        ),
    )
    RingCaption(stringResource(R.string.ring_cardio_value, assessment.score, stringResource(assessment.state.label)), cardioColor)
    RingCaption(
        fatigue?.let { stringResource(R.string.ring_load_value, it.loadReadinessPercent, stringResource(it.level.label)) }
            ?: stringResource(R.string.ring_load_no_data),
        loadColor,
    )
    RingCaption(
        autonomic?.let { stringResource(R.string.ring_autonomic_value, it.percent, stringResource(it.tone.label)) }
            ?: stringResource(R.string.ring_autonomic_no_data),
        autonomicColor,
    )
    fatigue?.let { index ->
        FatigueComponent.entries.forEach { component ->
            Detail(stringResource(component.label), index.components[component]?.let(::formatShare))
        }
    }
}

private fun FatigueLevel?.gaugeColor(): Color = this?.let { Color(ReadinessPalette.argb(it)) } ?: Color.LightGray

private fun AutonomicTone?.gaugeColor(): Color = this?.let { Color(ReadinessPalette.argb(it)) } ?: Color.LightGray

/** A 0..100 ring of the gauge; a null [score] leaves it empty, [pulsing] makes it breathe to draw attention. */
private data class GaugeRing(val score: Int?, val color: Color, val pulsing: Boolean = false)

/** Concentric 0..100 arcs, outermost first, with their scores in the middle, the outer one's on top and largest. */
@Composable
private fun RingGauge(rings: List<GaugeRing>) {
    val progress = rings.map { animatedProgress(it.score) }
    val pulse = pulseAlpha()
    val track = MaterialTheme.colorScheme.surfaceVariant
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.size(GAUGE_SIZE)) {
            val strokeWidth = GAUGE_STROKE.toPx()
            val step = strokeWidth + GAUGE_RING_GAP.toPx()
            rings.forEachIndexed { index, ring ->
                val color = if (ring.pulsing) ring.color.copy(alpha = pulse) else ring.color
                drawRing(color, track, progress[index], strokeWidth / 2 + index * step, strokeWidth)
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            rings.forEachIndexed { index, ring ->
                RingScore(ring, progress[index], if (index == 0) OUTER_SCORE_SIZE else INNER_SCORE_SIZE)
            }
        }
    }
}

@Composable
private fun pulseAlpha(): Float {
    val alpha by rememberInfiniteTransition(label = "pulse").animateFloat(
        initialValue = 1f,
        targetValue = PULSE_MIN_ALPHA,
        animationSpec = infiniteRepeatable(tween(PULSE_MS), RepeatMode.Reverse),
        label = "pulseAlpha",
    )
    return alpha
}

@Composable
private fun animatedProgress(score: Int?): Float {
    val progress by animateFloatAsState(
        targetValue = (score ?: 0) / MAX_SCORE,
        animationSpec = tween(GAUGE_ANIMATION_MS),
        label = "score",
    )
    return progress
}

private fun DrawScope.drawRing(color: Color, track: Color, progress: Float, inset: Float, strokeWidth: Float) {
    val topLeft = Offset(inset, inset)
    val arcSize = Size(size.width - 2 * inset, size.height - 2 * inset)
    val stroke = Stroke(width = strokeWidth, cap = StrokeCap.Round)
    drawArc(track, ReadinessGaugeArc.START_DEGREES, ReadinessGaugeArc.SWEEP_DEGREES, useCenter = false, topLeft = topLeft, size = arcSize, style = stroke)
    drawArc(color, ReadinessGaugeArc.START_DEGREES, ReadinessGaugeArc.SWEEP_DEGREES * progress, useCenter = false, topLeft = topLeft, size = arcSize, style = stroke)
}

@Composable
private fun RingScore(ring: GaugeRing, progress: Float, fontSize: TextUnit) {
    Text(
        text = if (ring.score == null) MISSING_VALUE else stringResource(R.string.percent_value, (progress * MAX_SCORE).roundToInt()),
        color = ring.color,
        fontSize = fontSize,
        fontWeight = FontWeight.Bold,
    )
}

@Composable
private fun RingCaption(text: String, color: Color) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = color,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun AdviceCard(advice: Advice, state: ReadinessState) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(containerColor = state.color().copy(alpha = ADVICE_TINT_ALPHA)),
    ) {
        Row(modifier = Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(text = advice.action.icon, fontSize = 44.sp)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(text = stringResource(advice.action.title), style = MaterialTheme.typography.titleMedium)
                Text(text = advice.text.asString(), style = MaterialTheme.typography.bodyMedium)
                advice.rationale?.let { Text(text = it.asString(), style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}

/**
 * The post-stress phase spelled out: tone, what opened the window and until when it lasts, the components, and - while the
 * tail is active - the breathing pause and the advice to hold off conversations and load.
 */
@Composable
private fun AutonomicToneCard(autonomic: AutonomicBalance?) {
    autonomic ?: return
    val tone = autonomic.tone
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(containerColor = tone.gaugeColor().copy(alpha = ADVICE_TINT_ALPHA)),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = stringResource(R.string.detail_line, stringResource(R.string.card_autonomic), stringResource(tone.label)),
                style = MaterialTheme.typography.titleMedium,
            )
            tone.explanation()?.let { Text(text = stringResource(it), style = MaterialTheme.typography.bodyMedium) }
            if (autonomic.postStressActive) {
                POST_STRESS_ADVICE.forEach {
                    Text(text = stringResource(R.string.bullet, stringResource(it)), style = MaterialTheme.typography.bodyMedium)
                }
            }
            Text(
                text = autonomic.window?.let {
                    stringResource(R.string.autonomic_window, stringResource(it.trigger.label), formatTime(it.triggerEndedAt), formatTime(it.endsAt))
                } ?: stringResource(R.string.autonomic_no_window),
                style = MaterialTheme.typography.bodySmall,
            )
            if (autonomic.window != null && autonomic.vulnerability > 1.0) {
                Text(text = stringResource(R.string.autonomic_vulnerability, autonomic.vulnerability), style = MaterialTheme.typography.bodySmall)
            }
            AutonomicComponent.entries.forEach { component ->
                Detail(stringResource(component.label), autonomic.components[component]?.let(::formatShare))
            }
        }
    }
}

private val POST_STRESS_ADVICE = listOf(R.string.advice_breathing_pause, R.string.advice_pause_before_talks, R.string.advice_pause_before_load)

private fun AutonomicTone.explanation(): Int? = when (this) {
    AutonomicTone.POST_STRESS -> R.string.autonomic_post_stress_explanation
    AutonomicTone.RECOVERING -> R.string.autonomic_recovering_explanation
    AutonomicTone.STRAINED -> R.string.autonomic_strained_explanation
    AutonomicTone.CALM -> null
}

@Composable
private fun ProtectionCard(medication: MedicationProtection, recovery: VagalRecovery?) = SectionCard(stringResource(R.string.card_medication_recovery)) {
    MedicationPhaseText(medication)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text = stringResource(R.string.night_recovery), style = MaterialTheme.typography.bodyMedium)
        Text(
            text = recovery?.let { stringResource(R.string.recovery_vrr, stringResource(it.status.label), it.ratio) } ?: stringResource(R.string.no_rmssd_data),
            style = MaterialTheme.typography.labelLarge,
            color = Color.Black,
            modifier = Modifier.background(recovery?.status.badgeColor(), RoundedCornerShape(12.dp)).padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

/** Badge colours reuse the readiness palette: excellent = optimal ... hidden stress = limited. */
private fun VagalRecoveryStatus?.badgeColor(): Color = when (this) {
    VagalRecoveryStatus.EXCELLENT -> ReadinessState.OPTIMAL.color()
    VagalRecoveryStatus.MODERATE, VagalRecoveryStatus.BALANCED -> ReadinessState.MODERATE.color()
    VagalRecoveryStatus.HIDDEN_STRESS -> ReadinessState.LIMITED.color()
    null -> Color.LightGray
}

@Composable
private fun VitalsCard(vitals: Vitals) = SectionCard(stringResource(R.string.card_latest_vitals)) {
    val pressure = vitals.pressure
    Text(
        text = stringResource(
            R.string.vital_blood_pressure,
            if (pressure == null) MISSING_VALUE else {
                val origin = if (pressure.source == SampleSource.BLOOD_PRESSURE_CUFF) R.string.origin_cuff else R.string.origin_wrist
                stringResource(
                    R.string.bp_with_origin, pressure.systolic.roundToInt(), pressure.diastolic.roundToInt(),
                    stringResource(origin), formatTime(pressure.measuredAt),
                )
            },
        ),
    )
    Text(text = stringResource(R.string.vital_pulse, vitals.heartRateBpm?.let { stringResource(R.string.bpm_int, it.roundToInt()) } ?: MISSING_VALUE))
    Text(text = stringResource(R.string.vital_body_temperature, vitals.bodyTemperatureCelsius?.let { stringResource(R.string.celsius_value, it) } ?: MISSING_VALUE))
    Text(text = stringResource(R.string.vital_spo2, vitals.spo2Percent?.let { stringResource(R.string.percent_spaced, it.roundToInt()) } ?: MISSING_VALUE))
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FactorsCard(assessment: ReadinessAssessment, expanded: Boolean, onToggle: () -> Unit) = SectionCard(stringResource(R.string.card_factors)) {
    val limiting = assessment.limitingFactors.size
    Text(
        text = (if (limiting == 0) stringResource(R.string.nothing_limits) else stringResource(R.string.limiting_count, limiting)) + if (expanded) "  ▲" else "  ▼",
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onToggle),
    )
    if (expanded) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            assessment.factors.forEach { FactorChip(it) }
        }
    }
}

@Composable
private fun FactorChip(factor: ReadinessFactor) {
    val points = when {
        factor.points > 0 -> " (+${factor.points})"
        factor.points < 0 -> " (${factor.points})"
        else -> ""
    }
    val container = when {
        factor.isLimiting -> MaterialTheme.colorScheme.errorContainer
        factor.points > 0 -> MaterialTheme.colorScheme.tertiaryContainer
        else -> MaterialTheme.colorScheme.surface
    }
    AssistChip(
        onClick = {},
        label = { Text(text = stringResource(R.string.factor_chip, stringResource(factor.kind.label), factor.detail.asString()) + points) },
        colors = AssistChipDefaults.assistChipColors(containerColor = container),
    )
}

@Composable
private fun ForecastCard(hours: List<ReadinessForecastHour>) = SectionCard(stringResource(R.string.card_next_hours)) {
    val zone = ZoneId.systemDefault()
    Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        hours.forEach { hour ->
            Column(modifier = Modifier.width(HOUR_CELL_WIDTH), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(text = HOUR_FORMAT.format(hour.start.atZone(zone)), style = MaterialTheme.typography.labelSmall)
                Box(
                    modifier = Modifier.fillMaxWidth().height(HOUR_BAR_HEIGHT)
                        .background(hour.state.color(), RoundedCornerShape(6.dp)),
                    contentAlignment = Alignment.Center,
                ) { Text(text = "${hour.score}", style = MaterialTheme.typography.labelMedium, color = Color.Black) }
                Text(text = if (hour.walkWindow) WALK_MARK else " ", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
    val window = hours.dropWhile { !it.walkWindow }.takeWhile { it.walkWindow }
    Text(
        text = if (window.isEmpty()) {
            stringResource(R.string.no_walk_window, hours.size)
        } else {
            val end = window.last().start.plusSeconds(SECONDS_PER_HOUR)
            stringResource(R.string.best_walk_window, WINDOW_FORMAT.format(window.first().start.atZone(zone)), WINDOW_FORMAT.format(end.atZone(zone)))
        },
        style = MaterialTheme.typography.bodyMedium,
    )
    Text(
        text = stringResource(R.string.forecast_basis),
        style = MaterialTheme.typography.bodySmall,
    )
}

private const val SECONDS_PER_HOUR = 3_600L
