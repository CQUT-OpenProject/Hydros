package org.cqutosp.hydros.ui.state

import org.cqutosp.hydros.R
import kotlinx.serialization.Serializable
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

@Serializable enum class DeviceCategory(val label: String, val icon: Int) {
    SHOWER("洗浴", R.drawable.ic_shower), DRINK("饮水", R.drawable.ic_water), WASH("洗漱", R.drawable.ic_faucet),
}
@Serializable enum class DeviceStatus(val label: String) { AVAILABLE("可使用"), BUSY("使用中"), OFFLINE("离线"), UNKNOWN("待核实") }
enum class ConnectionStage { IDLE, CONNECTING, READY, FAILED }
@Serializable enum class ControlChannel { UNKNOWN, CLOUD, BLE }
@Serializable enum class SessionStage(val label: String) {
    OPENING("正在开启"), OPEN_UNCONFIRMED("开启待确认"), USING("正在使用"), STOPPING("正在关闭"),
    CLOSE_UNCONFIRMED("关闭待确认"), SETTLING("正在结算"), SETTLEMENT_PENDING("结算待完成"), WRITEBACK_PENDING("设备记录待清除"),
}
@Serializable data class AccountUi(val name: String = "—", val phone: String = "", val studentId: String = "—", val school: String = "—")
data class WalletUi(val totalMilli: Long? = null) {
    val totalCents get() = totalMilli?.let(::displayCents)
}
@Serializable data class DeviceUi(val id: String, val name: String, val location: String, val category: DeviceCategory,
    val status: DeviceStatus = DeviceStatus.UNKNOWN, val signal: String = "未测量", val channel: ControlChannel = ControlChannel.UNKNOWN,
    val depositMilli: Long? = null, val deviceId: String = "", val ledgerMac: String = "", val connectMac: String = "",
    val bigTypeId: String? = null, val smallTypeId: String? = null, val isBle: Boolean? = null) {
    val connection get() = when(channel) { ControlChannel.BLE -> "蓝牙直连"; ControlChannel.CLOUD -> "云端连接"; else -> "待核实" }
    val depositCents get() = depositMilli?.let(::displayCents)
}
const val DEVICE_REMARK_MAX_LENGTH = 16
fun DeviceUi.displayName(remarks: Map<String, String>): String = remarks[id]?.takeIf(String::isNotBlank) ?: name

@Serializable data class SessionUi(val device: DeviceUi, val ownerKey: String, val startedAt: Long = System.currentTimeMillis(),
    val stage: SessionStage = SessionStage.OPENING, val orderNo: String = "", val consumeDate: String = "",
    val uploaded: Boolean = false, val rateAttempted: Boolean = false, val uploadAttempted: Boolean = false,
    val writeBackHex: String? = null, val failureReportPending: Boolean = false,
    val deviceUseChanged: Boolean = false,
    val recoveryReason: SessionRecoveryReason? = null, val endedAt: Long? = null) {
    val message: String? get() = sessionFeedback(this)?.text
}
enum class BillType(val requestType: String, val label: String) {
    CONSUMPTION("2", "消费记录"), RECHARGE("1", "充值记录")
}
data class BillUi(val id: String, val title: String, val location: String, val category: DeviceCategory?, val amountMilli: Long?,
    val time: LocalDateTime, val consumeDate: String, val orderNo: String? = null, val durationText: String? = null,
    val recharge: Boolean = false, val detailAvailable: Boolean = true) {
    val amountCents get() = amountMilli?.let(::displayCents)
    val month: YearMonth get() = YearMonth.from(time)
}
/** Per-bill detail status, with any failure already mapped to user-facing text. */
data class BillDetailUi(val fetching: Boolean = false, val error: String? = null)
fun displayCents(milli: Long): Long = BigDecimal.valueOf(milli, 1).setScale(0, RoundingMode.HALF_UP).longValueExact()
fun money(cents: Long?): String = cents?.let { BigDecimal.valueOf(it, 2).setScale(2).toPlainString() } ?: "—"
fun elapsed(seconds: Long): String = "%02d:%02d".format(Locale.CHINA, seconds / 60, seconds % 60)
val BillTimeFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("MM月dd日 HH:mm")
