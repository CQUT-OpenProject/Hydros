package dev.uednd.hydros

import dev.uednd.hydros.ui.state.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class SessionFeedbackTest {
    private val device = DeviceUi("id", "设备", "宿舍", DeviceCategory.SHOWER, channel = ControlChannel.BLE)
    @Test fun uncertainOpeningCannotBePresentedAsNetworkFailureOrSafeToRepeat() {
        val value = SessionUi(device, "owner", stage = SessionStage.OPEN_UNCONFIRMED, recoveryReason = SessionRecoveryReason.NETWORK)
        assertTrue(value.message!!.contains("请勿重复开启"))
        assertEquals("核实并结束使用", sessionActionLabel(value))
    }
    @Test fun settlementCopyUsesConfirmedEvidenceOnly() {
        val value = SessionUi(device, "owner", stage = SessionStage.SETTLEMENT_PENDING, rateAttempted = true)
        assertFalse(value.message!!.contains("设备已关闭"))
        assertTrue(value.copy(endedAt = 123).message!!.contains("设备已关闭"))
        assertTrue(value.copy(uploaded = true).message!!.contains("消费已记账"))
        assertEquals("清除设备记录", sessionActionLabel(value.copy(stage = SessionStage.WRITEBACK_PENDING)))
    }
    @Test fun durableSessionStoresReasonRatherThanRenderedOrRawMessages() {
        val value = SessionUi(device, "owner", stage = SessionStage.OPEN_UNCONFIRMED, recoveryReason = SessionRecoveryReason.OPEN_UNKNOWN)
        val encoded = Json.encodeToString(value)
        assertFalse(encoded.contains("message")); assertFalse(encoded.contains("开启结果"))
        assertEquals(value.message, Json.decodeFromString<SessionUi>(encoded).message)
    }
    @Test fun notificationUsesSameRecoveryCopyAndIgnoresUnrelatedObservation() {
        val value = SessionUi(device, "owner", stage = SessionStage.CLOSE_UNCONFIRMED)
        assertEquals(value.message, sessionNotificationText(value, SessionObservation("other", unavailable = true, actionError = "other error")))
        val observation = SessionObservation(device.id, value.startedAt, unavailable = true)
        val using = value.copy(stage = SessionStage.USING)
        assertTrue(sessionNotificationText(using, observation).contains("暂时无法确认"))
        assertEquals("重试关闭", sessionActionLabel(value))
    }

    @Test fun timeoutAdviceRespectsTransportAndPermissionSurvivesSettlement() {
        val network = dev.uednd.hydros.data.feedback.AppFailure(dev.uednd.hydros.data.feedback.FailureOperation.START,
            dev.uednd.hydros.data.feedback.FailureStage.NETWORK, dev.uednd.hydros.data.feedback.FailureReason.TIMEOUT)
        assertEquals(SessionRecoveryReason.NETWORK, sessionRecoveryReason(network))
        val ble = dev.uednd.hydros.data.feedback.AppFailure(dev.uednd.hydros.data.feedback.FailureOperation.START,
            dev.uednd.hydros.data.feedback.FailureStage.BLE_COMMAND, dev.uednd.hydros.data.feedback.FailureReason.TIMEOUT)
        assertEquals(SessionRecoveryReason.DEVICE_UNREACHABLE, sessionRecoveryReason(ble))
        val value = SessionUi(device, "owner", stage = SessionStage.SETTLEMENT_PENDING, rateAttempted = true,
            endedAt = 123, recoveryReason = SessionRecoveryReason.PERMISSION)
        assertTrue(value.message!!.contains("设备已关闭"))
        assertTrue(value.message!!.contains("允许附近设备权限"))
    }

}
