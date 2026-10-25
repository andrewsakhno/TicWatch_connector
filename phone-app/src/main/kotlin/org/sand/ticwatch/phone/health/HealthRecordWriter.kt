package org.sand.ticwatch.phone.health

import java.time.Instant

/** A single sensor sample synced from the watch, in a transport-neutral shape. */
data class SensorSample(val type: SensorType, val value: Double, val recordedAt: Instant)

enum class SensorType { HEART_RATE, STEPS, CALORIES, RESTING_HEART_RATE }

/**
 * Writes watch-sourced sensor samples into Google Health. Abstracted behind an
 * interface (DIP/OCP) so the Health Connect SDK dependency stays isolated to
 * [HealthConnectRecordWriter], and so sync logic can be unit-tested against a fake.
 */
interface HealthRecordWriter {
    val requiredPermissions: Set<String>
    suspend fun hasRequiredPermissions(): Boolean
    suspend fun write(samples: List<SensorSample>): Result<Unit>
}
