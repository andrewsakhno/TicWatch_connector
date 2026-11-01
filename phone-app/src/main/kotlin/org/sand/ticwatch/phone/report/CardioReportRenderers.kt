package org.sand.ticwatch.phone.report

import androidx.annotation.StringRes
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt
import org.sand.ticwatch.phone.R
import org.sand.ticwatch.phone.health.BiologicalSex
import org.sand.ticwatch.phone.medication.MedicationEffectAnalyzer
import org.sand.ticwatch.phone.text.TextResolver
import org.sand.ticwatch.phone.text.uiText

/** Format-neutral report layout: [CardioReportFormatter] fills it, each [CardioReportRenderer] only typesets it. */
data class ReportDocument(val title: String, val subtitle: String, val sections: List<ReportSection>, val footer: String)

data class ReportSection(
    val heading: String,
    val rows: List<ReportRow> = emptyList(),
    val table: ReportTable? = null,
    val note: String? = null,
)

data class ReportRow(val label: String, val value: String)

data class ReportTable(val headers: List<String>, val rows: List<List<String>>)

/** Turns a [CardioReport] into a [ReportDocument] (wording in the app language, units, rounding). */
class CardioReportFormatter(private val texts: TextResolver) {

    fun format(report: CardioReport): ReportDocument {
        val time = DateTimeFormatter.ofPattern(DATE_TIME_PATTERN).withZone(report.zone)
        return ReportDocument(
            title = t(R.string.report_title),
            subtitle = t(R.string.report_subtitle, time.format(report.from), time.format(report.to), report.zone.id),
            sections = listOf(
                patient(report),
                medication(report),
                pressure(report.pressure),
                heartRate(report.heartRate),
                sleep(report.sleep),
                accuracy(report.accuracy, time),
            ),
            footer = t(R.string.report_footer, time.format(report.generatedAt)),
        )
    }

    private fun patient(report: CardioReport): ReportSection {
        val patient = report.patient
        return ReportSection(
            heading = t(R.string.report_patient),
            rows = listOf(
                ReportRow(t(R.string.report_name), patient.name ?: notSet()),
                ReportRow(t(R.string.report_age), patient.ageYears?.let { t(R.string.report_age_years, it) } ?: notSet()),
                ReportRow(t(R.string.report_sex), patient.sex?.let { t(sexLabel(it)) } ?: notSet()),
                ReportRow(t(R.string.report_height), patient.heightCm?.let { t(R.string.report_height_cm, it.roundToInt()) } ?: notSet()),
                ReportRow(t(R.string.report_weight), patient.weightKg?.let { t(R.string.report_weight_kg, it) } ?: notSet()),
            ),
        )
    }

    private fun medication(report: CardioReport) = ReportSection(
        heading = t(R.string.report_medication),
        rows = listOf(ReportRow(t(R.string.report_logged_intakes), "${report.medicationIntakes}")),
    )

    private fun pressure(summary: AmbulatoryPressureSummary) = ReportSection(
        heading = t(R.string.report_ambulatory_bp),
        rows = listOf(
            ReportRow(t(R.string.report_mean_24h), mean(summary.overall)),
            ReportRow(t(R.string.report_mean_day, DayNightWindow.DAY_START.toString(), DayNightWindow.NIGHT_START.toString()), mean(summary.daytime)),
            ReportRow(t(R.string.report_mean_night, DayNightWindow.NIGHT_START.toString(), DayNightWindow.DAY_START.toString()), mean(summary.nighttime)),
            ReportRow(
                t(R.string.report_dipping),
                summary.dippingPercent?.let { t(R.string.report_dipping_value, it, summary.dippingPattern?.let { pattern -> t(pattern.label) }.orEmpty()) }
                    ?: noData(),
            ),
            ReportRow(t(R.string.report_pulse_pressure), summary.overall?.let { t(R.string.report_mmhg_int, it.pulsePressure.roundToInt()) } ?: noData()),
            ReportRow(t(R.string.report_readings), t(R.string.report_readings_value, summary.cuffReadings, summary.wristReadings)),
        ),
    )

    private fun heartRate(summary: HeartRateSummary) = ReportSection(
        heading = t(R.string.report_hr_section),
        rows = listOf(
            ReportRow(
                t(R.string.report_night_resting_hr),
                summary.nightRestingHeartRate?.let { t(R.string.report_night_resting_hr_value, it.roundToInt(), summary.nights) } ?: noData(),
            ),
            ReportRow(t(R.string.report_morning_surge), summary.morningSurge?.let { t(R.string.report_bpm_signed, it.roundToInt()) } ?: noData()),
            ReportRow(
                t(R.string.report_tachy_episodes, HeartRateReportCalculator.TACHYCARDIA_THRESHOLD.roundToInt()),
                t(R.string.report_tachy_value, summary.tachycardiaEpisodes, duration(summary.tachycardiaDuration)),
            ),
            ReportRow(
                t(R.string.report_onset),
                summary.medicationOnset?.let { t(R.string.report_onset_value, it.toMinutes(), summary.intakesWithOnset, summary.intakes) }
                    ?: if (summary.intakes == 0) t(R.string.report_no_intakes) else t(R.string.report_no_hr_drop, summary.intakes),
            ),
        ),
        note = t(
            R.string.report_hr_note, HeartRateReportCalculator.MIN_EPISODE.toMinutes(), MedicationEffectAnalyzer.ONSET_HEART_RATE_DROP.roundToInt(),
        ),
    )

    private fun sleep(summary: SleepReportSummary) = ReportSection(
        heading = t(R.string.report_sleep_section),
        rows = listOf(
            ReportRow(t(R.string.report_nights), "${summary.nights}"),
            ReportRow(t(R.string.report_waso), summary.meanWaso?.let { t(R.string.report_minutes, it.toMinutes()) } ?: noData()),
            ReportRow(t(R.string.report_awakenings), summary.meanAwakenings?.let { "%.1f".format(it) } ?: noData()),
            ReportRow(
                t(R.string.report_vrr),
                summary.meanVagalRecoveryRatio?.let {
                    t(R.string.report_vrr_value, it, summary.recoveryStatus?.let { status -> t(status.label) }.orEmpty(), summary.recoveryNights)
                } ?: noData(),
            ),
            ReportRow(
                t(R.string.report_snoring),
                t(
                    R.string.report_snoring_value, t(summary.snoreImpact.label), summary.snoreEpisodes,
                    summary.snoreCardiacSpikes, summary.assessedSnoreEpisodes,
                ),
            ),
        ),
        note = t(R.string.report_sleep_note),
    )

    private fun accuracy(accuracy: CalibrationAccuracy, time: DateTimeFormatter) = ReportSection(
        heading = t(R.string.report_accuracy),
        rows = listOf(
            ReportRow(t(R.string.report_cuff_readings), accuracy.cuffReadings.size.toString()),
            ReportRow(
                t(R.string.report_mean_abs_diff),
                if (accuracy.comparisons.isEmpty()) noData() else t(
                    R.string.report_mean_abs_diff_value,
                    accuracy.meanAbsoluteSystolicDelta?.roundToInt().toString(), accuracy.meanAbsoluteDiastolicDelta?.roundToInt().toString(),
                ),
            ),
        ),
        table = ReportTable(
            headers = listOf(
                t(R.string.report_col_time), t(R.string.report_col_cuff), t(R.string.report_col_pulse),
                t(R.string.report_col_wrist), t(R.string.report_col_delta),
            ),
            rows = accuracy.cuffReadings.map { cuff ->
                val comparison = accuracy.comparisons.firstOrNull { it.cuff == cuff }
                listOf(
                    time.format(cuff.measuredAt),
                    pressure(cuff.systolic, cuff.diastolic),
                    cuff.pulse?.roundToInt()?.toString() ?: "-",
                    comparison?.let { pressure(it.wrist.systolic, it.wrist.diastolic) } ?: "-",
                    comparison?.let { "%+d / %+d".format(it.systolicDelta.roundToInt(), it.diastolicDelta.roundToInt()) } ?: "-",
                )
            },
        ).takeIf { accuracy.cuffReadings.isNotEmpty() },
        note = t(R.string.report_accuracy_note, CalibrationAccuracyCalculator.MAX_GAP.toMinutes()),
    )

    private fun mean(mean: PressureMean?): String =
        mean?.let { t(R.string.report_mean_value, pressure(it.systolic, it.diastolic), it.count) } ?: noData()

    private fun pressure(systolic: Double, diastolic: Double): String = "${systolic.roundToInt()}/${diastolic.roundToInt()}"

    private fun duration(duration: Duration): String =
        t(R.string.report_duration, duration.toHours(), duration.toMinutes() % MINUTES_PER_HOUR)

    private fun t(@StringRes id: Int, vararg args: Any): String = texts.resolve(uiText(id, *args))

    private fun notSet(): String = t(R.string.report_not_set)

    private fun noData(): String = t(R.string.no_data)

    private fun sexLabel(sex: BiologicalSex): Int = when (sex) {
        BiologicalSex.MALE -> R.string.sex_male
        BiologicalSex.FEMALE -> R.string.sex_female
    }

    private companion object {
        const val DATE_TIME_PATTERN = "dd.MM.yyyy HH:mm"
        const val MINUTES_PER_HOUR = 60
    }
}

/** Typesets a [ReportDocument] in one output format. */
fun interface CardioReportRenderer {
    fun render(document: ReportDocument): String
}

class MarkdownCardioReportRenderer : CardioReportRenderer {
    override fun render(document: ReportDocument): String = buildString {
        appendLine("# ${document.title}").appendLine().appendLine("_${document.subtitle}_").appendLine()
        document.sections.forEach { section ->
            appendLine("## ${section.heading}").appendLine()
            section.rows.forEach { appendLine("- **${it.label}:** ${it.value}") }
            section.table?.let { table ->
                appendLine()
                appendLine(table.headers.joinToString(" | ", "| ", " |"))
                appendLine(table.headers.joinToString(" | ", "| ", " |") { "---" })
                table.rows.forEach { appendLine(it.joinToString(" | ", "| ", " |")) }
            }
            section.note?.let { appendLine().appendLine("_${it}_") }
            appendLine()
        }
        appendLine("---").appendLine(document.footer)
    }
}

/** Printable HTML (the WebView behind [AndroidCardioReportExporter.print] and "Save as PDF"). */
class HtmlCardioReportRenderer : CardioReportRenderer {
    override fun render(document: ReportDocument): String = buildString {
        append("<!DOCTYPE html><html><head><meta charset=\"utf-8\"><title>").append(escape(document.title)).append("</title>")
        append("<style>").append(STYLE).append("</style></head><body>")
        append("<h1>").append(escape(document.title)).append("</h1><p class=\"sub\">").append(escape(document.subtitle)).append("</p>")
        document.sections.forEach { section ->
            append("<h2>").append(escape(section.heading)).append("</h2>")
            if (section.rows.isNotEmpty()) {
                append("<table class=\"kv\">")
                section.rows.forEach { append("<tr><th>").append(escape(it.label)).append("</th><td>").append(escape(it.value)).append("</td></tr>") }
                append("</table>")
            }
            section.table?.let { table ->
                append("<table class=\"grid\"><tr>")
                table.headers.forEach { append("<th>").append(escape(it)).append("</th>") }
                append("</tr>")
                table.rows.forEach { row ->
                    append("<tr>")
                    row.forEach { append("<td>").append(escape(it)).append("</td>") }
                    append("</tr>")
                }
                append("</table>")
            }
            section.note?.let { append("<p class=\"note\">").append(escape(it)).append("</p>") }
        }
        append("<p class=\"footer\">").append(escape(document.footer)).append("</p></body></html>")
    }

    private fun escape(text: String): String =
        text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private companion object {
        const val STYLE = "body{font-family:sans-serif;font-size:11pt;color:#111;margin:16px}" +
            "h1{font-size:18pt;margin-bottom:0}h2{font-size:13pt;border-bottom:1px solid #999;margin-top:18px}" +
            ".sub,.note,.footer{color:#555;font-size:9pt}table{border-collapse:collapse;width:100%}" +
            ".kv th{text-align:left;font-weight:normal;color:#444;width:55%;padding:2px 0}.kv td{font-weight:bold}" +
            ".grid th,.grid td{border:1px solid #bbb;padding:3px 6px;text-align:left}.grid th{background:#eee}"
    }
}

/** File-name stamp for an export. */
internal fun reportFileStamp(at: Instant, zone: ZoneId): String = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm").withZone(zone).format(at)
