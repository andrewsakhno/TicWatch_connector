package org.sand.ticwatch.phone.dashboard

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import java.time.Duration
import java.time.Instant
import org.sand.ticwatch.phone.health.SleepStageType
import org.sand.ticwatch.phone.history.SleepStage

/** Top-to-bottom order of the chart's rows; an unspecific "sleeping" stage shares the light row. */
private val STAGE_ROWS = mapOf(
    SleepStageType.AWAKE to 0,
    SleepStageType.REM to 1,
    SleepStageType.LIGHT to 2,
    SleepStageType.SLEEPING to 2,
    SleepStageType.DEEP to 3,
)
private const val ROW_COUNT = 4
private val CHART_HEIGHT = 72.dp
private val ROW_GAP = 1.dp
private val MIN_BAR_WIDTH = 1.dp

fun sleepStageColor(type: SleepStageType): Color = when (type) {
    SleepStageType.AWAKE -> Color(0xFFFFA726)
    SleepStageType.REM -> Color(0xFF26C6DA)
    SleepStageType.LIGHT -> Color(0xFF64B5F6)
    SleepStageType.DEEP -> Color(0xFF5C6BC0)
    SleepStageType.SLEEPING -> Color(0xFF90A4AE)
}

/** Each stage of one sleep as a bar on its depth's row, across [start]..[end]. */
@Composable
fun SleepHypnogram(stages: List<SleepStage>, start: Instant, end: Instant, modifier: Modifier = Modifier) {
    val span = Duration.between(start, end).toMillis().toFloat()
    Canvas(modifier.fillMaxWidth().height(CHART_HEIGHT)) {
        if (span <= 0f) return@Canvas
        val rowHeight = size.height / ROW_COUNT
        val gap = ROW_GAP.toPx()
        stages.forEach { stage ->
            val x = Duration.between(start, stage.start).toMillis() / span * size.width
            val width = Duration.between(stage.start, stage.end).toMillis() / span * size.width
            drawRect(
                color = sleepStageColor(stage.type),
                topLeft = Offset(x, STAGE_ROWS.getValue(stage.type) * rowHeight + gap),
                size = Size(maxOf(width, MIN_BAR_WIDTH.toPx()), rowHeight - 2 * gap),
            )
        }
    }
}
