package org.sand.ticwatch.phone.spaceweather

import java.time.Instant
import java.time.ZoneId

/**
 * Joins NOAA's 3-hour Kp (past week + 3-day forecast) with the 27-day outlook into local days, today first.
 * A day the 3-hour slots don't fully cover also takes the outlook's largest Kp (its UTC date is matched to the
 * local date - close enough for a daily maximum); radio flux comes only from the outlook.
 */
class SpaceWeatherAssembler(private val zone: ZoneId, private val days: Int = DEFAULT_DAYS) {

    fun assemble(slots: List<KpSlot>, outlook: List<OutlookDay>, now: Instant): SpaceWeather {
        val outlookByDate = outlook.associateBy { it.date }
        val today = now.atZone(zone).toLocalDate()
        val dayList = (0 until days).mapNotNull { offset ->
            val date = today.plusDays(offset.toLong())
            val start = date.atStartOfDay(zone).toInstant()
            val end = date.plusDays(1).atStartOfDay(zone).toInstant()
            val daySlots = slots.filter { it.start >= start && it.start < end }
            val covered = daySlots.size >= SLOTS_PER_DAY
            val outlookDay = outlookByDate[date]
            val maxKp = listOfNotNull(
                daySlots.maxOfOrNull { it.kp },
                outlookDay?.takeUnless { covered }?.maxKp?.toDouble(),
            ).maxOrNull() ?: return@mapNotNull null
            val observed = covered && daySlots.all { it.origin == KpOrigin.OBSERVED }
            SpaceWeatherDay(date, maxKp, outlookDay?.radioFlux, forecast = !observed)
        }
        return SpaceWeather(
            fetchedAt = now,
            current = slots.lastOrNull { it.start <= now },
            days = dayList,
            slots = slots,
        )
    }

    private companion object {
        const val DEFAULT_DAYS = 6
        const val SLOTS_PER_DAY = 8
    }
}
