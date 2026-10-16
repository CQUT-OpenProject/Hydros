package dev.uednd.hydros.data.ble

/** Injectable boundary: tests never connect to real hardware. Callers serialize complete transactions. */
interface BlePort {
    val ready: Boolean
    suspend fun connect(mac: String): BleConnectResult
    suspend fun query(): DeviceQueryState?
    suspend fun writeRate(bytes: ByteArray): Boolean
    suspend fun closeValve(): Boolean
    suspend fun collect(): ByteArray?
    suspend fun writeBack(bytes: ByteArray): Boolean
    fun disconnect()
}
class AndroidBlePort(private val controller: BleController) : BlePort {
    override val ready get() = controller.isReady
    override suspend fun connect(mac: String) = controller.connect(mac)
    override suspend fun query() = controller.query()
    override suspend fun writeRate(bytes: ByteArray) = controller.writeRate(bytes)
    override suspend fun closeValve() = controller.closeValve()
    override suspend fun collect() = controller.collect()
    override suspend fun writeBack(bytes: ByteArray) = controller.writeBack(bytes)
    override fun disconnect() = controller.disconnect()
}

/** Retry transport setup only; never replay rate, close, upload or writeback commands. */
suspend fun BlePort.connectWithRetry(mac: String): BleConnectResult =
    kotlinx.coroutines.withTimeoutOrNull(BleController.CONNECT_TIMEOUT_MS) {
        val first = connect(mac)
        if (first !is BleConnectResult.Failed || !first.retryable) return@withTimeoutOrNull first
        disconnect()
        kotlinx.coroutines.delay(350)
        connect(mac)
    } ?: BleConnectResult.Failed("连接超时，请靠近设备后重试", retryable = false).also { disconnect() }
