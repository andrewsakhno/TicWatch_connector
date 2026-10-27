package org.sand.ticwatch.phone.spaceweather

import java.time.Instant
import java.time.LocalDate
import kotlin.math.roundToInt

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
enum class GeomagneticLevel(val label: String, val minKp: Int) {
    QUIET("Quiet", 0),
    UNSETTLED("Unsettled", 3),
    ACTIVE("Active", 4),
    G1("Minor storm G1", 5),
    G2("Moderate storm G2", 6),
    G3("Strong storm G3", 7),
    G4("Severe storm G4", 8),
    G5("Extreme storm G5", 9);

    companion object {
        fun of(kp: Double): GeomagneticLevel = entries.last { kp.roundToInt() >= it.minKp }
    }
}

/** Solar activity by F10.7 radio flux (sfu); [maxFlux] is the band's exclusive upper edge. */
enum class SolarActivityLevel(val label: String, val maxFlux: Int) {
    VERY_LOW("Very low", 70),
    LOW("Low", 100),
    MODERATE("Moderate", 150),
    HIGH("High", 200),
    VERY_HIGH("Very high", Int.MAX_VALUE);

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
