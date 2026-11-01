package org.sand.ticwatch.phone

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import org.sand.ticwatch.phone.sleep.ApneaRiskLevel
import org.sand.ticwatch.phone.sleep.SleepAdvancedInsights
import org.sand.ticwatch.phone.sleep.SnoreCardiacArousal

private val SleepAdvancedInsights.hasSnoring: Boolean get() = snore.episodes > 0

/** False for a night past the raw and rollup retention, whose section would be just its title. */
private val SleepAdvancedInsights.hasAnalysis: Boolean
    get() = hasSnoring || apneaRisk != ApneaRiskLevel.UNKNOWN || recovery != null || continuity != null

/** The Metrics sleep card's short version: verdicts only, next to the card's own ODI and snoring minutes. */
@Composable
internal fun SleepInsightsSummary(insights: SleepAdvancedInsights) {
    if (insights.hasSnoring) SnoreImpactDetail(insights.snore)
    NightVerdictDetails(insights)
}

/** The sleep journal's titled section with the numbers behind the verdicts; nothing when the night has no analysis. */
@Composable
internal fun SleepInsightsSection(insights: SleepAdvancedInsights, modifier: Modifier = Modifier) {
    if (!insights.hasAnalysis) return
    Column(modifier = modifier) {
        Text(text = stringResource(R.string.sleep_analysis_title), style = MaterialTheme.typography.titleSmall)
        if (insights.hasSnoring) {
            insights.snoringMinutes?.let { Detail(stringResource(R.string.snoring), stringResource(R.string.unit_minutes, it)) }
            SnoreImpactDetail(insights.snore)
            SnoreCardiacDetails(insights.snore)
        }
        NightVerdictDetails(insights)
    }
}

@Composable
private fun SnoreImpactDetail(snore: SnoreCardiacArousal) {
    Detail(stringResource(R.string.snore_cardiac_impact), stringResource(snore.impact.label))
}

@Composable
private fun SnoreCardiacDetails(snore: SnoreCardiacArousal) {
    if (snore.cardiacSpikes > 0) Detail(stringResource(R.string.snore_pulse_spikes), snore.cardiacSpikes.toString())
    val snoring = snore.snoringRmssdMs
    val quiet = snore.quietRmssdMs
    if (snoring != null && quiet != null) {
        Text(text = stringResource(R.string.snore_rmssd_vs_quiet, snoring, quiet), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun NightVerdictDetails(insights: SleepAdvancedInsights) {
    if (insights.apneaRisk != ApneaRiskLevel.UNKNOWN) {
        Detail(stringResource(R.string.apnea_risk_title), stringResource(insights.apneaRisk.label))
    }
    insights.recovery?.let { recovery ->
        Detail(
            stringResource(R.string.night_autonomic_balance),
            stringResource(R.string.recovery_vrr, stringResource(recovery.status.label), recovery.ratio),
        )
    }
    insights.continuity?.let { Detail(stringResource(R.string.sleep_fragmentation), formatShare(it.fragmentationIndex)) }
}
