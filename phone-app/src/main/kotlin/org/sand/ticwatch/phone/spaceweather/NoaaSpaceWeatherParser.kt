package org.sand.ticwatch.phone.spaceweather

import org.json.JSONArray
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Parses NOAA SWPC's planetary Kp forecast JSON and 27-day outlook text; malformed rows are skipped. */
class NoaaSpaceWeatherParser {

    /** `[{"time_tag":"2026-10-20T00:00:00","kp":1.00,"observed":"observed",...}, ...]`, UTC. */
    fun kpSlots(json: String): List<KpSlot> {
        val rows = JSONArray(json)
        return (0 until rows.length()).mapNotNull { index ->
            val row = rows.optJSONObject(index) ?: return@mapNotNull null
            val origin = KpOrigin.of(row.optString(ORIGIN)) ?: return@mapNotNull null
            val kp = row.optDouble(KP).takeUnless { it.isNaN() } ?: return@mapNotNull null
            runCatching { LocalDateTime.parse(row.getString(TIME_TAG)).toInstant(ZoneOffset.UTC) }
                .getOrNull()
                ?.let { KpSlot(it, kp, origin) }
        }.sortedBy { it.start }
    }

    /** Data lines of `27-day-outlook.txt`: `2026 Oct 27      84           5          2`. */
    fun outlook(text: String): List<OutlookDay> = text.lineSequence()
        .filterNot { it.startsWith(COMMENT) || it.startsWith(HEADER) }
        .mapNotNull { line ->
            val fields = line.trim().split(WHITESPACE)
            if (fields.size != OUTLOOK_FIELDS) return@mapNotNull null
            val date = runCatching { LocalDate.parse(fields.take(DATE_FIELDS).joinToString(" "), OUTLOOK_DATE) }
                .getOrNull() ?: return@mapNotNull null
            val flux = fields[FLUX_FIELD].toIntOrNull() ?: return@mapNotNull null
            val ap = fields[AP_FIELD].toIntOrNull() ?: return@mapNotNull null
            val kp = fields[KP_FIELD].toIntOrNull() ?: return@mapNotNull null
            OutlookDay(date, flux, ap, kp)
        }
        .toList()

    private companion object {
        const val TIME_TAG = "time_tag"
        const val KP = "kp"
        const val ORIGIN = "observed"
        const val COMMENT = "#"
        const val HEADER = ":"
        val WHITESPACE = Regex("\\s+")
        const val DATE_FIELDS = 3
        const val FLUX_FIELD = 3
        const val AP_FIELD = 4
        const val KP_FIELD = 5
        const val OUTLOOK_FIELDS = 6
        val OUTLOOK_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy MMM dd", Locale.US)
    }
}
