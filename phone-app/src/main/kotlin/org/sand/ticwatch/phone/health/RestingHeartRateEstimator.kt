package org.sand.ticwatch.phone.health

import android.content.SharedPreferences
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId

/**
 * Derives one resting-heart-rate value per local day from the live heart-rate stream:
 * the lowest [window]-long rolling mean seen that day. Health Services on the watch has
 * no resting-HR data type of its own (only continuous BPM).
 *
 * Emits a sample only when a day's minimum improves, and the Health Connect mapper
 * writes it under a per-day client record id, so each improvement replaces that day's
 * record instead of adding another one. The running minimum lives in [store] so a
 * process restart (each service builds its own `AppContainer`) can't regress it.
 */
class RestingHeartRateEstimator(
    private val store: DailyMinimumStore,
    private val zoneId: ZoneId = ZoneId.systemDefault(),
    private val window: Duration = Duration.ofMinutes(10),
) : SampleDeriver {
    private val recent = ArrayDeque<SensorSample>()

    override suspend fun derive(samples: List<SensorSample>): List<SensorSample> {
        val improvements = linkedMapOf<LocalDate, SensorSample>()
        samples.filter { it.type == SensorType.HEART_RATE }.sortedBy { it.recordedAt }.forEach { sample ->
            val mean = rollingMean(sample) ?: return@forEach
            val day = sample.recordedAt.atZone(zoneId).toLocalDate()
            val best = store.get(day)
            if (best == null || mean < best) {
                store.put(day, mean)
                improvements[day] = SensorSample(SensorType.RESTING_HEART_RATE, mean, sample.recordedAt)
            }
        }
        return improvements.values.toList()
    }

    /** Mean BPM over the trailing [window], or null until the window is filled enough to be meaningful. */
    private fun rollingMean(sample: SensorSample): Double? {
        recent.addLast(sample)
        val cutoff = sample.recordedAt.minus(window)
        while (recent.first().recordedAt.isBefore(cutoff)) recent.removeFirst()

        val covered = Duration.between(recent.first().recordedAt, sample.recordedAt)
        if (recent.size < MIN_SAMPLES || covered < window.multipliedBy(MIN_COVERAGE_PERCENT).dividedBy(PERCENT)) return null
        return recent.sumOf { it.value } / recent.size
    }

    private companion object {
        /** HR arrives about once a second; fewer readings than this means a gappy window. */
        const val MIN_SAMPLES = 60
        const val MIN_COVERAGE_PERCENT = 80L
        const val PERCENT = 100L
    }
}

/** Read side of the per-day running minimum, for consumers that only look the baseline up. */
interface DailyMinimumSource {
    fun get(day: LocalDate): Double?
}

/** Per-day running minimum, persisted across process restarts. */
interface DailyMinimumStore : DailyMinimumSource {
    fun put(day: LocalDate, value: Double)
}

class SharedPreferencesDailyMinimumStore(private val preferences: SharedPreferences) : DailyMinimumStore {

    override fun get(day: LocalDate): Double? =
        preferences.getString(day.toString(), null)?.toDoubleOrNull()

    /** Also prunes days older than [RETENTION_DAYS]; only today's (and yesterday's late) values matter. */
    override fun put(day: LocalDate, value: Double) {
        val cutoff = day.minusDays(RETENTION_DAYS)
        val editor = preferences.edit().putString(day.toString(), value.toString())
        preferences.all.keys
            .filter { key -> runCatching { LocalDate.parse(key).isBefore(cutoff) }.getOrDefault(false) }
            .forEach(editor::remove)
        editor.apply()
    }

    companion object {
        const val PREFERENCES_NAME = "resting_heart_rate"
        private const val RETENTION_DAYS = 7L
    }
}
