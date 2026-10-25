package org.sand.ticwatch.phone.network

import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

/** Builds the [BackendApi] client. `baseUrl` is configurable since it points at a
 *  developer machine or self-hosted server, never an app-store-fixed endpoint. */
object BackendApiFactory {
    fun create(baseUrl: String): BackendApi =
        Retrofit.Builder()
            .baseUrl(baseUrl)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(BackendApi::class.java)
}
