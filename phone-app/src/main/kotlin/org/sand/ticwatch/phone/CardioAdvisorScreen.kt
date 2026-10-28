package org.sand.ticwatch.phone

import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import org.sand.ticwatch.phone.advisor.Advice
import org.sand.ticwatch.phone.advisor.AdvisorIntent
import org.sand.ticwatch.phone.advisor.CardioAdvisorStore
import org.sand.ticwatch.phone.advisor.CardioReadinessSource
import org.sand.ticwatch.phone.advisor.ReadinessAssessment
import org.sand.ticwatch.phone.advisor.ReadinessFactor
import org.sand.ticwatch.phone.advisor.ReadinessForecastHour
import org.sand.ticwatch.phone.advisor.ReadinessPalette
import org.sand.ticwatch.phone.advisor.ReadinessState
import org.sand.ticwatch.phone.advisor.Vitals
import org.sand.ticwatch.phone.health.SampleSource
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

private const val MAX_SCORE = 100f
private const val SWEEP_DEGREES = 270f
private const val START_DEGREES = 135f
private const val GAUGE_ANIMATION_MS = 900
private const val ADVICE_TINT_ALPHA = 0.18f
private const val MISSING_VALUE = "--"
private val GAUGE_SIZE = 180.dp
private val GAUGE_STROKE = 16.dp
private val HOUR_CELL_WIDTH = 44.dp
private val HOUR_BAR_HEIGHT = 28.dp
private val HOUR_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH")
private val WINDOW_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
private const val WALK_MARK = "🚶"

private fun ReadinessState.color(): Color = Color(ReadinessPalette.argb(this))

/** Dashboard "Advisor" tab: cardio readiness now, the advice derived from it, its factors and the next hours. */
@Composable
internal fun CardioAdvisorScreen(source: CardioReadinessSource) {
    val scope = rememberCoroutineScope()
    val store = remember(source) { CardioAdvisorStore(source, scope, Dispatchers.IO) }
    val state by store.state.collectAsState()
    LaunchedEffect(store) { store.dispatch(AdvisorIntent.Refresh) }

    Column(
        modifier = Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (state.loading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        state.error?.let { Text(text = "Could not load: $it", color = MaterialTheme.colorScheme.error) }
        state.readiness?.let { readiness ->
            ReadinessGaugeCard(readiness.assessment)
            AdviceCard(readiness.assessment.advice, readiness.assessment.state)
            VitalsCard(readiness.vitals)
            FactorsCard(readiness.assessment, state.factorsExpanded) { store.dispatch(AdvisorIntent.ToggleFactors) }
            ForecastCard(readiness.forecast)
        }
        OutlinedButton(onClick = { store.dispatch(AdvisorIntent.Refresh) }, enabled = !state.loading) { Text(text = "Refresh") }
        Text(
            text = "Heuristic guidance from wrist signals, not a diagnosis. Follow your doctor's advice.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun ReadinessGaugeCard(assessment: ReadinessAssessment) = SectionCard("Cardio readiness") {
    val progress by animateFloatAsState(
        targetValue = assessment.score / MAX_SCORE,
        animationSpec = tween(GAUGE_ANIMATION_MS),
        label = "readiness",
    )
    val color = assessment.state.color()
    val track = MaterialTheme.colorScheme.surfaceVariant
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.size(GAUGE_SIZE)) {
            val stroke = Stroke(width = GAUGE_STROKE.toPx(), cap = StrokeCap.Round)
            drawArc(track, START_DEGREES, SWEEP_DEGREES, useCenter = false, style = stroke)
            drawArc(color, START_DEGREES, SWEEP_DEGREES * progress, useCenter = false, style = stroke)
        }
        Text(text = "${(progress * MAX_SCORE).roundToInt()}%", fontSize = 40.sp, fontWeight = FontWeight.Bold)
    }
    Text(
        text = "${assessment.score}% - ${assessment.state.label}",
        style = MaterialTheme.typography.titleMedium,
        color = color,
        modifier = Modifier.align(Alignment.CenterHorizontally),
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
                Text(text = advice.action.title, style = MaterialTheme.typography.titleMedium)
                Text(text = advice.text, style = MaterialTheme.typography.bodyMedium)
                advice.rationale?.let { Text(text = it, style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}

@Composable
private fun VitalsCard(vitals: Vitals) = SectionCard("Latest vitals") {
    val pressure = vitals.pressure
    Text(
        text = "Blood pressure: " + if (pressure == null) MISSING_VALUE else {
            val origin = if (pressure.source == SampleSource.BLOOD_PRESSURE_CUFF) "cuff" else "wrist"
            "${pressure.systolic.roundToInt()}/${pressure.diastolic.roundToInt()} mmHg ($origin, ${formatTime(pressure.measuredAt)})"
        },
    )
    Text(text = "Pulse: ${vitals.heartRateBpm?.let { "${it.roundToInt()} bpm" } ?: MISSING_VALUE}")
    Text(text = "Body temperature: ${vitals.bodyTemperatureCelsius?.let { "%.1f °C".format(it) } ?: MISSING_VALUE}")
    Text(text = "SpO2: ${vitals.spo2Percent?.let { "${it.roundToInt()} %" } ?: MISSING_VALUE}")
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FactorsCard(assessment: ReadinessAssessment, expanded: Boolean, onToggle: () -> Unit) = SectionCard("Factors") {
    val limiting = assessment.limitingFactors.size
    Text(
        text = (if (limiting == 0) "Nothing limits you right now" else "$limiting limiting") + if (expanded) "  ▲" else "  ▼",
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
        label = { Text(text = "${factor.kind.label}: ${factor.detail}$points") },
        colors = AssistChipDefaults.assistChipColors(containerColor = container),
    )
}

@Composable
private fun ForecastCard(hours: List<ReadinessForecastHour>) = SectionCard("Next hours") {
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
            "No favourable walking window expected in the next ${hours.size} hours."
        } else {
            val end = window.last().start.plusSeconds(SECONDS_PER_HOUR)
            "Best walking window: ${WINDOW_FORMAT.format(window.first().start.atZone(zone))}-${WINDOW_FORMAT.format(end.atZone(zone))}"
        },
        style = MaterialTheme.typography.bodyMedium,
    )
    Text(
        text = "Projected from your medication timing, the hourly pulse profile, the morning pressure surge and the Kp forecast.",
        style = MaterialTheme.typography.bodySmall,
    )
}

private const val SECONDS_PER_HOUR = 3_600L
