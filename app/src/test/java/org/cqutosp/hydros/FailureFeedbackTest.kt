package org.cqutosp.hydros

import org.cqutosp.hydros.data.feedback.*
import org.cqutosp.hydros.data.network.ApiFailure
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class FailureFeedbackTest {
    @Test fun originDeterminesIoAdvice() {
        val error = IOException("secret diagnostic")
        assertTrue(failureFeedback(error, FailureOperation.WALLET).text.contains("检查网络"))
        val local = failureFeedback(error, FailureOperation.REMARK, FailureStage.STORAGE).text
        assertTrue(local.contains("保存备注")); assertFalse(local.contains("网络"))
        val ble = failureFeedback(AppFailure(FailureOperation.DEVICE, FailureStage.BLE_CONNECT, FailureReason.DISCONNECTED, error), FailureOperation.DEVICE).text
        assertTrue(ble.contains("靠近")); assertFalse(ble.contains("网络"))
    }
    @Test fun businessTextIsPreservedButDiagnosticsAreNot() {
        assertTrue(failureFeedback(ApiFailure(0, "服务返回缺少必要数据"), FailureOperation.LOGIN).text.contains("数据异常"))
        listOf("账号或密码错误", "验证码错误", "余额不足").forEach {
            assertEquals(it, failureFeedback(ApiFailure(12, it), FailureOperation.LOGIN).text)
        }
        listOf("费率数据无效，未写入设备", "消费采集失败", "网络 IO 错误", "网络IO错误", "蓝牙GATT错误", "java.io.IOException: 网络错误", "java.lang.IllegalStateException错误", "返回 {\"error\":1}", "错误 https://example.com", "服务返回缺少必要数据", "SQL 查询失败", "a".repeat(101)).forEach {
            assertNull(it, readableBusinessMessage(it))
        }
    }
}
