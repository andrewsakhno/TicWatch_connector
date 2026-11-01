package org.sand.ticwatch.phone.spaceweather

import androidx.annotation.StringRes
import java.time.Instant
import java.time.LocalDate
import kotlin.math.roundToInt
import org.sand.ticwatch.phone.R

/** How NOAA obtained a 3-hour Kp value (its `observed` field). */
enum class KpOrigin(val noaaName: String) {
    OBSERVED("observed"),
    ESTIMATED("estimated"),
    PREDICTED("predicted");

    companion object {
        fun of(noaaName: String): KpOrigin? = entries.firstOrNull { it.noaaName == noaaName }
    }
}

/** Planetary Kp of the 3-hour UTC slot starting at [start]. */
data class KpSlot(val start: Instant, val kp: Double, val origin: KpOrigin)

/** One UTC day of NOAA's 27-day outlook: 10.7 cm radio flux (sfu), planetary A index, largest Kp. */
data class OutlookDay(val date: LocalDate, val radioFlux: Int, val apIndex: Int, val maxKp: Int)

/**
 * NOAA G-scale by Kp, compared after rounding to the nearest integer (thirds: 4.67 = "5-" already counts as G1).
 * Ascending; [of] picks the highest level reached.
 */
enum class GeomagneticLevel(@StringRes val label: Int, val minKp: Int) {
    QUIET(R.string.geomagnetic_quiet, 0),
    UNSETTLED(R.string.geomagnetic_unsettled, 3),
    ACTIVE(R.string.geomagnetic_active, 4),
    G1(R.string.geomagnetic_g1, 5),
    G2(R.string.geomagnetic_g2, 6),
    G3(R.string.geomagnetic_g3, 7),
    G4(R.string.geomagnetic_g4, 8),
    G5(R.string.geomagnetic_g5, 9);

    companion object {
        fun of(kp: Double): GeomagneticLevel = entries.last { kp.roundToInt() >= it.minKp }
    }
}

/** Solar activity by F10.7 radio flux (sfu); [maxFlux] is the band's exclusive upper edge. */
enum class SolarActivityLevel(@StringRes val label: Int, val maxFlux: Int) {
    VERY_LOW(R.string.solar_very_low, 70),
    LOW(R.string.solar_low, 100),
    MODERATE(R.string.solar_moderate, 150),
    HIGH(R.string.solar_high, 200),
    VERY_HIGH(R.string.solar_very_high, Int.MAX_VALUE);

    companion object {
        fun of(flux: Int): SolarActivityLevel = entries.first { flux < it.maxFlux }
    }
}

/**
 * One local day: the highest Kp expected/observed ([forecast] when any part of it is still predicted) and the
 * outlook's radio flux, null where NOAA's products don't reach.
 */
data class SpaceWeatherDay(val date: LocalDate, val maxKp: Double, val radioFlux: Int?, val forecast: Boolean) {
    val level: GeomagneticLevel get() = GeomagneticLevel.of(maxKp)
    val solarActivity: SolarActivityLevel? get() = radioFlux?.let(SolarActivityLevel::of)
}

/** What the phone last fetched: the current slot, today + the next days, and every 3-hour slot NOAA returned. */
data class SpaceWeather(
    val fetchedAt: Instant,
    val current: KpSlot?,
    val days: List<SpaceWeatherDay>,
    val slots: List<KpSlot>,
)
