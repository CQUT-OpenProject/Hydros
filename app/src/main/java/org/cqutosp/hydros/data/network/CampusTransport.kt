package org.cqutosp.hydros.data.network

import org.cqutosp.hydros.data.feedback.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.math.BigDecimal

@Serializable data class Credentials(val loginCode: String, val userId: String, val accountId: String, val projectId: String, val phone: String) {
    val key get() = "$projectId:$accountId:$userId"
    fun fields() = mapOf("loginCode" to loginCode, "userId" to userId, "accountId" to accountId,
        "projectId" to projectId, "telephone" to phone, "telPhone" to phone, "phoneSystem" to "android", "version" to "6.5.28")
    fun headers() = mapOf("Config-Project" to projectId, "Config-Keys" to
        "module_list,advertise_type,question_list,service_phone_list,banner_list_app,activity_list_app,aliCard_popup_config")
}
open class ApiFailure(val code: Int?, override val message: String, val expired: Boolean = false) : Exception(message)
/** Only an explicit rejection before a potentially successful command permits a channel change. */
class CloudDeviceOffline : ApiFailure(306, "设备无法通过云端开启")
interface CampusTransport {
    suspend fun get(path: String, fields: Map<String, String> = emptyMap(), auth: Credentials? = null): ApiResponse<JsonElement>
    suspend fun post(path: String, fields: Map<String, String> = emptyMap(), auth: Credentials? = null): ApiResponse<JsonElement>
}
class HttpCampusTransport(private val api: CampusApi, private val isCurrent: (Credentials) -> Boolean = { true },
    private val recovery: NetworkRecovery? = null,
    private val onExpired: suspend (Credentials) -> Unit) : CampusTransport {
    override suspend fun get(path: String, fields: Map<String, String>, auth: Credentials?) = call(false, path, fields, auth)
    override suspend fun post(path: String, fields: Map<String, String>, auth: Credentials?) = call(true, path, fields, auth)
    private suspend fun call(post: Boolean, path: String, fields: Map<String, String>, auth: Credentials?): ApiResponse<JsonElement> {
        try {
        if (auth != null && !isCurrent(auth)) throw ApiFailure(null, "登录状态已变化", true)
        // Business telephone overrides authentication telephone only for SMS to a new number.
        val params = (auth?.fields() ?: emptyMap()) + fields
        suspend fun execute(budget: QueryRetryBudget?): retrofit2.Response<ApiResponse<JsonElement>> {
            val request: suspend (CampusApi) -> retrofit2.Response<ApiResponse<JsonElement>> = { selected ->
                if (auth != null && !isCurrent(auth)) throw ApiFailure(null, "登录状态已变化", true)
                val send: suspend () -> retrofit2.Response<ApiResponse<JsonElement>> = {
                    val response = if (post) selected.post(path, params, auth?.headers() ?: emptyMap())
                        else selected.get(path, params, auth?.headers() ?: emptyMap())
                    if (budget != null && transientHttpStatus(response.code())) {
                        response.errorBody()?.close()
                        throw QueryHttpFailure(response.code(), response.headers()["Retry-After"]?.toLongOrNull()
                            ?.takeIf { it in 0..3 }?.times(1_000)
                            ?: response.headers()["Retry-After"]?.let { Long.MAX_VALUE })
                    }
                    response
                }
                if (budget == null) send() else budget.attempt(send)
            }
            return recovery?.execute(api, path, post, request,
                canFallback = { budget?.available != false },
                canArmDeadline = { budget?.fallbackRoom != false },
                onRouteExhausted = { budget?.routeExhausted = true }) ?: request(api)
        }
        val response = if (isReadOnlyCampusRequest(path, post)) retryReadQuery { execute(it) } else execute(null)
        if (auth != null && !isCurrent(auth)) throw ApiFailure(null, "登录状态已变化", true)
        if (auth != null && response.code() in listOf(401, 403)) { onExpired(auth); throw ApiFailure(response.code(), "登录已失效，请重新登录", true) }
        if (!response.isSuccessful) throw AppFailure(feedbackOperationFor(path), FailureStage.RESPONSE, FailureReason.REJECTED, ApiFailure(response.code(), "HTTP rejection"))
        val body = response.body() ?: throw ApiFailure(null, "服务返回为空")
        if (auth != null && !body.ok && isSessionExpiry(body.displayMessage)) {
            onExpired(auth); throw ApiFailure(body.errorCode, "登录已失效，请重新登录", true)
        }
        return body
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: Exception) {
            if (error is QueryHttpFailure)
                throw AppFailure(feedbackOperationFor(path), FailureStage.RESPONSE, FailureReason.REJECTED, error)
            if (error is java.io.IOException || error is kotlinx.serialization.SerializationException)
                throw classifyFailure(error, feedbackOperationFor(path), FailureStage.NETWORK)
            throw error
        }
    }

    private fun feedbackOperationFor(path: String) = when {
        path == "user/verification/code/get" -> FailureOperation.CODE
        path.startsWith("user/phone") || path.startsWith("user/password") -> FailureOperation.ACCOUNT
        path.startsWith("user/") -> FailureOperation.LOGIN
        path == "account/wallet" -> FailureOperation.WALLET
        path.endsWith("bill/detail") -> FailureOperation.DETAIL
        path.contains("bill") -> FailureOperation.BILLS
        path.startsWith("device/") -> FailureOperation.SCAN
        path.contains("query/rateOrder/using") -> FailureOperation.DEVICE
        path.contains("downRate") -> FailureOperation.START
        path.startsWith("order/") -> FailureOperation.FINISH
        else -> FailureOperation.PROFILE
    }
}
val ApiResponse<*>.ok get() = success && (errorCode == null || errorCode == 0)
fun ApiResponse<JsonElement>.requireData(): JsonObject { requireSuccess(); return data as? JsonObject ?: throw ApiFailure(errorCode, "服务返回缺少必要数据") }
val ApiResponse<*>.displayMessage get() = errorMessage?.takeIf(String::isNotBlank) ?: msg
fun ApiResponse<*>.requireSuccess() { if (!ok) throw ApiFailure(errorCode, displayMessage ?: "操作未完成") }
fun isSessionExpiry(message: String?): Boolean = message?.let {
    Regex("未登录|请.*登录|登录.*(失效|过期|超时|其他|异常)|账号.*(其他|别处)|token.*(失效|过期)|认证.*(失效|过期|失败)", RegexOption.IGNORE_CASE).containsMatchIn(it)
} ?: false
fun JsonObject.text(key: String): String? = (get(key) as? JsonPrimitive)?.contentOrNull?.takeUnless { it == "null" }
fun JsonObject.flag(key: String): Boolean? = (get(key) as? JsonPrimitive)?.booleanOrNull
fun JsonObject.number(key: String): Int? = text(key)?.toIntOrNull()
fun JsonObject.obj(key: String): JsonObject? = get(key) as? JsonObject
fun yuanMilli(value: String?): Long? = value?.toBigDecimalOrNull()?.takeIf { it >= BigDecimal.ZERO }?.let {
    runCatching { it.movePointRight(3).longValueExact() }.getOrNull()
}
