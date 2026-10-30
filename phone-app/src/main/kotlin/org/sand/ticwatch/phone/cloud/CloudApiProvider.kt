package org.sand.ticwatch.phone.cloud

import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** A [CloudApi] client per host ("192.168.1.20" or "host:port"; the port defaults to [CloudApi.PORT]). */
fun interface CloudApiProvider {
    fun api(host: String): CloudApi
}

/**
 * Retrofit clients sharing one OkHttp pool; every request carries the install's [CloudApi.DEVICE_HEADER]
 * and, when a token is configured, `Authorization: Bearer` (future non-local deployments).
 */
class RetrofitCloudApiProvider(
    private val deviceId: () -> String,
    private val apiToken: () -> String,
) : CloudApiProvider {

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val request = chain.request().newBuilder().header(CloudApi.DEVICE_HEADER, deviceId())
                apiToken().takeIf { it.isNotBlank() }?.let {
                    request.header(CloudApi.AUTHORIZATION_HEADER, CloudApi.BEARER_PREFIX + it)
                }
                chain.proceed(request.build())
            }
            .build()
    }

    private val apis = ConcurrentHashMap<String, CloudApi>()

    override fun api(host: String): CloudApi = apis.getOrPut(host) {
        Retrofit.Builder()
            .baseUrl(baseUrl(host))
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(CloudApi::class.java)
    }

    companion object {
        private const val CONNECT_TIMEOUT_SECONDS = 3L
        private const val READ_TIMEOUT_SECONDS = 30L
        private const val PORT_SEPARATOR = ':'

        fun baseUrl(host: String): String {
            val address = if (PORT_SEPARATOR in host) host else "$host$PORT_SEPARATOR${CloudApi.PORT}"
            return "http://$address/"
        }
    }
}
