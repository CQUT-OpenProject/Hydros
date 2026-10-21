package org.cqutosp.hydros.data.network

import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

object CampusApiFactory {
    fun client(): OkHttpClient = OkHttpClient.Builder().retryOnConnectionFailure(false)
        .callTimeout(java.time.Duration.ofSeconds(15)).build()
    /** The caller supplies the service base URL, including its trailing slash. */
    fun create(baseUrl: String, client: OkHttpClient = client()): CampusApi {
        val json = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }

        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(CampusApi::class.java)
    }
}
