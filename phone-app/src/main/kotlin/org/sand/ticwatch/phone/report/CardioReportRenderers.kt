package org.sand.ticwatch.phone.report

import org.sand.ticwatch.phone.medication.MedicationEffectAnalyzer
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

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

/** Turns a [CardioReport] into a [ReportDocument] (wording, units, rounding). */
class CardioReportFormatter {

    fun format(report: CardioReport): ReportDocument {
        val time = DateTimeFormatter.ofPattern(DATE_TIME_PATTERN).withZone(report.zone)
        return ReportDocument(
            title = TITLE,
            subtitle = "${time.format(report.from)} - ${time.format(report.to)} (${report.zone.id})",
            sections = listOf(
                patient(report),
                therapy(report),
                pressure(report.pressure),
                heartRate(report.heartRate),
                accuracy(report.accuracy, time),
            ),
            footer = "Generated ${time.format(report.generatedAt)} by TicWatch Connector. Wrist values are calibrated " +
                "cuffless estimates (trends only); cuff readings are from the Bioland monitor.",
        )
    }

    private fun patient(report: CardioReport): ReportSection {
        val patient = report.patient
        return ReportSection(
            heading = "Patient",
            rows = listOf(
                ReportRow("Name", patient.name ?: NOT_SET),
                ReportRow("Age", patient.ageYears?.let { "$it years" } ?: NOT_SET),
                ReportRow("Sex", patient.sex?.key ?: NOT_SET),
                ReportRow("Height", patient.heightCm?.let { "${it.roundToInt()} cm" } ?: NOT_SET),
                ReportRow("Weight", patient.weightKg?.let { "%.1f kg".format(it) } ?: NOT_SET),
            ),
        )
    }

    private fun therapy(report: CardioReport) = ReportSection(
        heading = "Current therapy",
        rows = report.therapy.map { ReportRow(it.name, "${it.doseDescription()} once daily, morning") },
    )

    private fun pressure(summary: AmbulatoryPressureSummary) = ReportSection(
        heading = "Ambulatory blood pressure",
        rows = listOf(
            ReportRow("24 h mean", mean(summary.overall)),
            ReportRow("Daytime mean (${DayNightWindow.DAY_START}-${DayNightWindow.NIGHT_START})", mean(summary.daytime)),
            ReportRow("Nighttime mean (${DayNightWindow.NIGHT_START}-${DayNightWindow.DAY_START})", mean(summary.nighttime)),
            ReportRow(
                "Nocturnal dipping",
                summary.dippingPercent?.let { "%.1f%% - %s".format(it, summary.dippingPattern?.label) } ?: NO_DATA,
            ),
            ReportRow("Mean pulse pressure", summary.overall?.let { "${it.pulsePressure.roundToInt()} mmHg" } ?: NO_DATA),
            ReportRow("Readings", "${summary.cuffReadings} cuff, ${summary.wristReadings} wrist estimates"),
        ),
    )

    private fun heartRate(summary: HeartRateSummary) = ReportSection(
        heading = "Heart rate and tachycardia",
        rows = listOf(
            ReportRow("Night resting heart rate", summary.nightRestingHeartRate?.let { "${it.roundToInt()} bpm (${summary.nights} night(s))" } ?: NO_DATA),
            ReportRow("Morning surge (first hour after waking)", summary.morningSurge?.let { "%+d bpm".format(it.roundToInt()) } ?: NO_DATA),
            ReportRow(
                "Daytime episodes > ${HeartRateReportCalculator.TACHYCARDIA_THRESHOLD.roundToInt()} bpm",
                "${summary.tachycardiaEpisodes}, total ${duration(summary.tachycardiaDuration)}",
            ),
            ReportRow(
                "Onset of effect after the morning dose",
                summary.medicationOnset?.let { "${it.toMinutes()} min (median of ${summary.intakesWithOnset} of ${summary.intakes} logged intakes)" }
                    ?: if (summary.intakes == 0) "no intakes logged" else "no heart-rate drop detected (${summary.intakes} intakes)",
            ),
        ),
        note = "Episodes count runs of >= ${HeartRateReportCalculator.MIN_EPISODE.toMinutes()} min outside recorded workouts. " +
            "Onset = first 15-min heart-rate median >= ${MedicationEffectAnalyzer.ONSET_HEART_RATE_DROP.roundToInt()} bpm " +
            "below the 30 min before the intake.",
    )

    private fun accuracy(accuracy: CalibrationAccuracy, time: DateTimeFormatter) = ReportSection(
        heading = "Accuracy and calibration",
        rows = listOf(
            ReportRow("Cuff reference readings", accuracy.cuffReadings.size.toString()),
            ReportRow(
                "Mean absolute wrist-cuff difference",
                if (accuracy.comparisons.isEmpty()) NO_DATA else
                    "SYS ${accuracy.meanAbsoluteSystolicDelta?.roundToInt()} / DIA ${accuracy.meanAbsoluteDiastolicDelta?.roundToInt()} mmHg",
            ),
        ),
        table = ReportTable(
            headers = listOf("Time", "Cuff", "Pulse", "Wrist", "Delta"),
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
        note = "Wrist estimate = nearest within ${CalibrationAccuracyCalculator.MAX_GAP.toMinutes()} min; delta = wrist - cuff.",
    )

    private fun mean(mean: PressureMean?): String =
        mean?.let { "${pressure(it.systolic, it.diastolic)} mmHg (n = ${it.count})" } ?: NO_DATA

    private fun pressure(systolic: Double, diastolic: Double): String = "${systolic.roundToInt()}/${diastolic.roundToInt()}"

    private fun duration(duration: Duration): String = "%d h %02d min".format(duration.toHours(), duration.toMinutes() % MINUTES_PER_HOUR)

    private companion object {
        const val TITLE = "Ambulatory cardio report"
        const val DATE_TIME_PATTERN = "dd.MM.yyyy HH:mm"
        const val NOT_SET = "not set"
        const val NO_DATA = "no data"
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
