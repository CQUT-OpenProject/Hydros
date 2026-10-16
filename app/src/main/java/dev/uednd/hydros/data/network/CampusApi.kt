package dev.uednd.hydros.data.network

import kotlinx.serialization.json.JsonElement
import retrofit2.Response
import retrofit2.http.*

/** Endpoint paths and authentication policy are owned by CampusTransport's callers. */
interface CampusApi {
    @GET suspend fun get(@Url path: String, @QueryMap fields: Map<String, String>, @HeaderMap headers: Map<String, String>): Response<ApiResponse<JsonElement>>
    @FormUrlEncoded @POST suspend fun post(@Url path: String, @FieldMap fields: Map<String, String>, @HeaderMap headers: Map<String, String>): Response<ApiResponse<JsonElement>>
}
