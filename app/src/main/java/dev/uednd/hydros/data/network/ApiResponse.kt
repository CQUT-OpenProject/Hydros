package dev.uednd.hydros.data.network

import kotlinx.serialization.Serializable

@Serializable
data class ApiResponse<T>(
    val success: Boolean = false,
    val errorCode: Int? = null,
    val errorMessage: String? = null,
    val data: T? = null,
    val msg: String? = null,
)

