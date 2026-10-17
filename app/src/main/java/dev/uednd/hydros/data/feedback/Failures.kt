package dev.uednd.hydros.data.feedback

import dev.uednd.hydros.data.network.ApiFailure
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import java.io.IOException
import java.net.SocketTimeoutException

/** Origin and stage are supplied at the boundary, never inferred from the exception's wording. */
enum class FailureOperation(val label: String) {
    LOGIN("登录"), PROFILE("获取账户资料"), CODE("发送验证码"), ACCOUNT("更新账户"), DEVICE("连接设备"), SCAN("查询设备"),
    START("开启设备"), FINISH("结束使用"), WALLET("刷新余额"), BILLS("加载账单"), DETAIL("加载账单详情"),
    FAVORITE("保存收藏"), REMARK("保存备注"), SETTINGS("保存设置"), RESTORE("读取应用数据"), UPDATE("检查更新"), INSTALL("安装更新")
}
enum class FailureStage { NETWORK, DEVICE_INFO, CLOUD_STATUS, BLE_CONNECT, BLE_COMMAND, STORAGE, RESPONSE, INSTALL }
@Serializable enum class FailureReason { NETWORK, TIMEOUT, BLUETOOTH_OFF, PERMISSION, DISCONNECTED, UNSUPPORTED, REJECTED, INVALID_DATA, STORAGE, UNKNOWN }
class AppFailure(
    val operation: FailureOperation,
    val stage: FailureStage,
    val reason: FailureReason,
    cause: Throwable? = null,
    val businessMessage: String? = null,
) : Exception(null, cause)

enum class FeedbackSeverity { SUCCESS, INFO, WARNING, ERROR }
enum class RecoveryAction { RETRY, RECONNECT, ENABLE_BLUETOOTH, SETTINGS, QUERY, RESCAN, SESSION, CONTACT_SUPPORT }
data class UserFeedback(val text: String, val severity: FeedbackSeverity = FeedbackSeverity.ERROR, val action: RecoveryAction = RecoveryAction.RETRY)

private val technical = Regex(
    "https?://|www\\.|[{}<>]|(?<![A-Za-z])(?:IO|I/O|SQL|JSON|HTML|HTTP|GATT|CCCD|AES|MAC|FF0[012])(?![A-Za-z])|" +
        "[A-Za-z_$][\\w.$]*(?:Exception|Error)|\\bat\\s+[\\w.$]+\\(|" +
        "select\\s+.+from|insert\\s+into|update\\s+\\w+\\s+set|" +
        "snCode|loginCode|accountId|consumeDate|downData|clData|protocolType|" +
        "缺少.*(?:参数|字段|数据|凭证|序列号)|格式不正确|格式无法识别|签名|费率|写回|消费采集|序列号|随机数|设备地址无效|蓝牙连接目标不匹配",
    RegexOption.IGNORE_CASE,
)
fun readableBusinessMessage(raw: String?): String? {
    val value = raw?.trim()?.replace(Regex("\\s+"), " ") ?: return null
    return value.takeIf { it.length in 1..100 && it.any { char -> char in '\u4e00'..'\u9fff' } && !technical.containsMatchIn(it) }
}
fun classifyFailure(error: Exception, operation: FailureOperation, stage: FailureStage): AppFailure {
    if (error is AppFailure) return if (error.stage == FailureStage.NETWORK && stage in listOf(FailureStage.DEVICE_INFO, FailureStage.CLOUD_STATUS))
        AppFailure(operation, stage, error.reason, error, error.businessMessage) else error
    val reason = when {
        stage == FailureStage.STORAGE -> FailureReason.STORAGE
        error is SecurityException -> FailureReason.PERMISSION
        error is SocketTimeoutException -> FailureReason.TIMEOUT
        error is IOException -> FailureReason.NETWORK
        error is ApiFailure -> if (error.code != null && error.code != 0) FailureReason.REJECTED else FailureReason.INVALID_DATA
        error is kotlinx.serialization.SerializationException -> FailureReason.INVALID_DATA
        else -> FailureReason.UNKNOWN
    }
    return AppFailure(operation, stage, reason, error, when (error) { is ApiFailure -> error.message; is dev.uednd.hydros.data.network.NetworkFailure -> error.message; else -> null })
}
suspend fun <T> failureBoundary(operation: FailureOperation, stage: FailureStage, block: suspend () -> T): T = try {
    block()
} catch (error: CancellationException) { throw error }
catch (error: Exception) {
    if (error is ApiFailure && (error.expired || stage == FailureStage.STORAGE)) throw error
    throw classifyFailure(error, operation, stage)
}

fun failureFeedback(error: Exception, operation: FailureOperation, stage: FailureStage = if (operation in listOf(FailureOperation.FAVORITE, FailureOperation.REMARK, FailureOperation.SETTINGS, FailureOperation.RESTORE)) FailureStage.STORAGE else FailureStage.NETWORK): UserFeedback {
    val failure = classifyFailure(error, operation, stage)
    // Caller context names the user's operation; the boundary retains where it actually failed.
    val label = when (failure.stage) {
        FailureStage.DEVICE_INFO -> "获取设备信息"
        FailureStage.CLOUD_STATUS -> "查询设备状态"
        else -> operation.label
    }
    val localOrBusiness = readableBusinessMessage(failure.businessMessage)
    val text = when (failure.reason) {
        FailureReason.BLUETOOTH_OFF -> "蓝牙未开启，请开启蓝牙后重新连接"
        FailureReason.PERMISSION -> if (failure.stage in listOf(FailureStage.BLE_CONNECT, FailureStage.BLE_COMMAND)) "请允许附近设备权限后重新连接" else "$label 未完成，请检查应用权限后重试"
        FailureReason.UNSUPPORTED -> "此设备不支持蓝牙连接，请选择其他设备或联系服务商"
        FailureReason.DISCONNECTED -> "蓝牙连接已中断，请靠近设备后重新连接"
        FailureReason.TIMEOUT -> if (failure.stage in listOf(FailureStage.BLE_CONNECT, FailureStage.BLE_COMMAND)) "设备未响应，请靠近设备后重试" else "${label}超时，请检查网络后重试"
        FailureReason.NETWORK -> localOrBusiness ?: "${label}失败，请检查网络后重试"
        FailureReason.STORAGE -> "${operation.label}失败，无法读写本地数据，请稍后重试"
        FailureReason.INVALID_DATA -> localOrBusiness ?: if (operation in listOf(FailureOperation.DEVICE, FailureOperation.SCAN, FailureOperation.START)) "设备资料异常，暂时无法使用，请重新查询；持续失败请联系服务商" else "${label}失败，服务返回的数据异常，请稍后重试"
        FailureReason.REJECTED -> localOrBusiness ?: "${label}未完成，服务暂时无法处理，请稍后重试"
        FailureReason.UNKNOWN -> if (failure.stage in listOf(FailureStage.BLE_CONNECT, FailureStage.BLE_COMMAND)) "蓝牙连接未完成，请靠近设备后重新连接" else "${label}未完成，请稍后重试"
    }
    return UserFeedback(text, action = when (failure.reason) {
        FailureReason.BLUETOOTH_OFF -> RecoveryAction.ENABLE_BLUETOOTH
        FailureReason.PERMISSION -> RecoveryAction.SETTINGS
        FailureReason.UNSUPPORTED -> RecoveryAction.CONTACT_SUPPORT
        FailureReason.DISCONNECTED -> RecoveryAction.RECONNECT
        else -> RecoveryAction.RETRY
    })
}
