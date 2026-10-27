package org.sand.ticwatch.phone.spaceweather

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.time.Clock

/** Fetches the current space weather from the network. */
fun interface SpaceWeatherSource {
    suspend fun fetch(): SpaceWeather
}

/** GETs a URL's body as text; throws on a non-2xx answer. */
fun interface HttpTextFetcher {
    suspend fun get(url: String): String
}

class UrlConnectionTextFetcher(
    private val timeoutMs: Int = DEFAULT_TIMEOUT_MS,
) : HttpTextFetcher {
    override suspend fun get(url: String): String = withContext(Dispatchers.IO) {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = timeoutMs
            connection.readTimeout = timeoutMs
            val status = connection.responseCode
            check(status in HttpURLConnection.HTTP_OK until HttpURLConnection.HTTP_MULT_CHOICE) { "HTTP $status for $url" }
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val DEFAULT_TIMEOUT_MS = 20_000
    }
}

/** NOAA SWPC's free public products (no key): 3-hour planetary Kp with a 3-day forecast, and the 27-day outlook. */
class NoaaSpaceWeatherSource(
    private val http: HttpTextFetcher,
    private val parser: NoaaSpaceWeatherParser,
    private val assembler: SpaceWeatherAssembler,
    private val clock: Clock,
) : SpaceWeatherSource {

    override suspend fun fetch(): SpaceWeather {
        val slots = parser.kpSlots(http.get(KP_FORECAST_URL))
        // The outlook only adds days 4-5 and the radio flux; Kp alone is still worth showing without it.
        val outlook = runCatching { parser.outlook(http.get(OUTLOOK_URL)) }.getOrDefault(emptyList())
        return assembler.assemble(slots, outlook, clock.instant())
    }

    private companion object {
        const val KP_FORECAST_URL = "https://services.swpc.noaa.gov/products/noaa-planetary-k-index-forecast.json"
        const val OUTLOOK_URL = "https://services.swpc.noaa.gov/text/27-day-outlook.txt"
    }
}
