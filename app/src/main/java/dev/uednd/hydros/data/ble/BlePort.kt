package dev.uednd.hydros.data.ble

import dev.uednd.hydros.data.feedback.*

/** Injectable boundary: tests never connect to real hardware. Callers serialize complete transactions. */
interface BlePort {
    val commandFailure: BleCommandFailure? get() = null
    val ready: Boolean
    /** Actual air address of the ready connection; absent while disconnected or connecting. */
    val connectedMac: String?
    suspend fun connect(mac: String): BleConnectResult
    suspend fun query(): DeviceQueryState?
    suspend fun writeRate(bytes: ByteArray): Boolean
    suspend fun closeValve(): Boolean
    suspend fun collect(): ByteArray?
    suspend fun writeBack(bytes: ByteArray): Boolean
    fun disconnect()
}
class AndroidBlePort(private val controller: BleController) : BlePort {
    override val commandFailure get() = controller.commandFailure
    override val ready get() = controller.isReady
    override val connectedMac get() = controller.connectedMac
    override suspend fun connect(mac: String) = controller.connect(mac)
    override suspend fun query() = controller.query()
    override suspend fun writeRate(bytes: ByteArray) = controller.writeRate(bytes)
    override suspend fun closeValve() = controller.closeValve()
    override suspend fun collect() = controller.collect()
    override suspend fun writeBack(bytes: ByteArray) = controller.writeBack(bytes)
    override fun disconnect() = controller.disconnect()
}

fun BlePort.isConnectedTo(mac: String): Boolean = ready &&
    MacFormat.normalize(mac)?.let { it == connectedMac?.let(MacFormat::normalize) } == true

/** Retry transport setup only; never replay rate, close, upload or writeback commands. */
suspend fun BlePort.connectWithRetry(mac: String): BleConnectResult =
    kotlinx.coroutines.withTimeoutOrNull(BleController.CONNECT_TIMEOUT_MS) {
        suspend fun attempt(): BleConnectResult = kotlinx.coroutines.withTimeoutOrNull(7_000) { connect(mac) }
            ?: BleConnectResult.Failed(FailureReason.TIMEOUT).also { disconnect() }
        val first = attempt()
        if (first !is BleConnectResult.Failed || !first.retryable) return@withTimeoutOrNull first
        disconnect()
        kotlinx.coroutines.delay(350)
        attempt()
    } ?: BleConnectResult.Failed(FailureReason.TIMEOUT, retryable = false).also { disconnect() }

/** A complete command is serialized by the repository; the failure belongs to its last result. */
enum class BleCommandFailure { DISCONNECTED, TIMEOUT, SEND_FAILED, INVALID_RESPONSE, REJECTED, PERMISSION }
fun BleConnectResult.requireReady(operation: FailureOperation) {
    when (this) {
        BleConnectResult.Ready -> Unit
        BleConnectResult.NoService -> throw AppFailure(operation, FailureStage.BLE_CONNECT, FailureReason.UNSUPPORTED)
        is BleConnectResult.Failed -> throw AppFailure(operation, FailureStage.BLE_CONNECT, reason)
    }
}
fun BlePort.commandError(operation: FailureOperation): AppFailure = AppFailure(operation, FailureStage.BLE_COMMAND,
    when (commandFailure) {
        BleCommandFailure.DISCONNECTED -> FailureReason.DISCONNECTED
        BleCommandFailure.TIMEOUT -> FailureReason.TIMEOUT
        BleCommandFailure.PERMISSION -> FailureReason.PERMISSION
        BleCommandFailure.INVALID_RESPONSE -> FailureReason.INVALID_DATA
        BleCommandFailure.REJECTED -> FailureReason.REJECTED
        else -> FailureReason.UNKNOWN
    }, businessMessage = when (commandFailure) {
        BleCommandFailure.REJECTED -> "设备未接受操作，请重新核实设备状态"
        BleCommandFailure.INVALID_RESPONSE -> "设备响应异常，请重新连接；持续失败请联系服务商"
        else -> null
    })
