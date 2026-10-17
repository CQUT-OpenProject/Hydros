package dev.uednd.hydros.ui.state

import dev.uednd.hydros.data.feedback.*
import kotlinx.serialization.Serializable

@Serializable enum class SessionRecoveryReason {
    OPEN_UNKNOWN, CLOSE_UNKNOWN, SETTLEMENT_PENDING, WRITEBACK_PENDING, LOGIN_REQUIRED,
    MISSING_ORDER, NOT_OPENED, DEVICE_CHANGED, STATE_UNKNOWN, NETWORK, DEVICE_UNREACHABLE, INVALID_DATA, PERMISSION, BLUETOOTH_OFF, UNSUPPORTED,
}
/** Runtime observation is deliberately not serialized into the saved session. */
data class SessionObservation(val deviceId: String? = null, val startedAt: Long? = null,
    val lastConfirmedAt: Long? = null, val unavailable: Boolean = false, val actionError: String? = null)

fun sessionRecoveryReason(error: Exception): SessionRecoveryReason = when ((error as? AppFailure)?.reason) {
    FailureReason.NETWORK -> SessionRecoveryReason.NETWORK
    FailureReason.TIMEOUT -> if ((error as AppFailure).stage in listOf(FailureStage.BLE_CONNECT, FailureStage.BLE_COMMAND)) SessionRecoveryReason.DEVICE_UNREACHABLE else SessionRecoveryReason.NETWORK
    FailureReason.DISCONNECTED -> SessionRecoveryReason.DEVICE_UNREACHABLE
    FailureReason.PERMISSION -> SessionRecoveryReason.PERMISSION
    FailureReason.BLUETOOTH_OFF -> SessionRecoveryReason.BLUETOOTH_OFF
    FailureReason.UNSUPPORTED -> SessionRecoveryReason.UNSUPPORTED
    FailureReason.INVALID_DATA -> SessionRecoveryReason.INVALID_DATA
    else -> if (error is java.io.IOException) SessionRecoveryReason.NETWORK else SessionRecoveryReason.STATE_UNKNOWN
}
fun sessionFeedback(value: SessionUi): UserFeedback? {
    val reason = value.recoveryReason
    val text = when {
        value.deviceUseChanged -> "设备已有新的使用，本次只核实原订单结算，请勿操作其他人的设备"
        value.stage == SessionStage.OPEN_UNCONFIRMED -> "开启结果尚未确认，请检查是否出水；应用正在自动核实，请勿重复开启"
        value.stage == SessionStage.CLOSE_UNCONFIRMED -> "设备关闭尚未确认，请检查水流并重试关闭"
        value.uploaded -> "消费已记账，设备记录会自动重试清除；请保持靠近并开启蓝牙"
        value.device.channel == ControlChannel.BLE && !value.rateAttempted && value.stage == SessionStage.SETTLEMENT_PENDING ->
            if (reason == SessionRecoveryReason.MISSING_ORDER) "设备未开启，订单恢复信息不完整，请联系服务商核实；请勿重复开启"
            else "设备未开启，订单取消结果待确认，请继续处理；请勿重复开启"
        reason == SessionRecoveryReason.MISSING_ORDER -> "原订单信息不完整，暂时无法核实结算，请联系服务商处理"
        reason == SessionRecoveryReason.LOGIN_REQUIRED -> if (value.endedAt != null) "设备已关闭，请登录原账户完成结算" else "请登录原账户核实设备状态并继续处理"
        value.endedAt != null && value.stage in listOf(SessionStage.SETTLING, SessionStage.SETTLEMENT_PENDING, SessionStage.WRITEBACK_PENDING) ->
            "设备已关闭，应用会自动核实结算，请保持网络连接"
        value.stage == SessionStage.SETTLEMENT_PENDING -> "应用正在自动核实设备状态和结算结果；请勿重复开启"
        value.stage == SessionStage.WRITEBACK_PENDING -> "设备记录待处理，应用会自动重试；请保持靠近并开启蓝牙"
        reason != null -> "暂时无法核实设备状态，请检查水流并重试"
        else -> return null
    }
    val advice = when (reason) {
        SessionRecoveryReason.NETWORK -> "请检查网络后继续处理。"
        SessionRecoveryReason.DEVICE_UNREACHABLE -> "请靠近设备并保持蓝牙连接后继续处理。"
        SessionRecoveryReason.PERMISSION -> "请在系统设置中允许附近设备权限后继续处理。"
        SessionRecoveryReason.BLUETOOTH_OFF -> "请开启蓝牙后继续处理。"
        SessionRecoveryReason.UNSUPPORTED, SessionRecoveryReason.INVALID_DATA -> "持续失败请联系服务商处理。"
        else -> null
    }
    return UserFeedback(if (advice == null) text else "$text。$advice", FeedbackSeverity.WARNING, RecoveryAction.SESSION)
}
fun sessionActionLabel(value: SessionUi): String = when (value.stage) {
    SessionStage.OPEN_UNCONFIRMED -> "核实并结束使用"
    SessionStage.CLOSE_UNCONFIRMED -> "重试关闭"
    SessionStage.SETTLEMENT_PENDING -> "继续结算"
    SessionStage.WRITEBACK_PENDING -> "清除设备记录"
    else -> "点击以结束使用"
}

fun sessionNotificationText(value: SessionUi?, observation: SessionObservation): String {
    if (value == null) return "正在准备设备会话"
    val matching = observation.deviceId == value.device.id && observation.startedAt == value.startedAt
    return if (matching && observation.actionError != null) observation.actionError
        else if (matching && observation.unavailable && value.endedAt == null) "暂时无法确认设备状态，请打开应用检查水流并恢复连接"
        else value.message ?: value.stage.label
}
