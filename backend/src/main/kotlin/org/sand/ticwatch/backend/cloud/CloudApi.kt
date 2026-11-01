package org.sand.ticwatch.backend.cloud

/** REST contract shared with phone-app's `cloud/CloudApi.kt` - keep both in sync. */
object CloudApi {
    const val BASE = "/api/v1"
    const val PING = "$BASE/ping"
    const val SAMPLES = "$BASE/samples"
    const val ROLLUPS = "$BASE/rollups"
    const val ROUTE_POINTS = "$BASE/route-points"
    const val ECG_RECORDINGS = "$BASE/ecg-recordings"

    /** Identifies the uploading phone install; stored with every row, never used to filter reads. */
    const val DEVICE_HEADER = "X-Device-Id"

    /** What [PING] answers in `service`, so a subnet scan never mistakes another HTTP server for this one. */
    const val SERVICE_NAME = "ticwatch-cloud"
    const val API_VERSION = 1

    const val MAX_PAGE_SIZE = 5000
}
