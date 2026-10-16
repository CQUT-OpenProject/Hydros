package dev.uednd.hydros.data.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import java.util.UUID

sealed interface BleConnectResult {

    data object Ready : BleConnectResult

    data object NoService : BleConnectResult

    data class Failed(val reason: String, val retryable: Boolean = true) : BleConnectResult
}

@SuppressLint("MissingPermission")
class BleController(private val context: Context) {

    companion object {
        val SERVICE_UUID: UUID = UUID.fromString("0000FF00-0000-1000-8000-00805F9B34FB")
        val NOTIFY_UUID: UUID = UUID.fromString("0000FF01-0000-1000-8000-00805F9B34FB")
        val WRITE_UUID: UUID = UUID.fromString("0000FF02-0000-1000-8000-00805F9B34FB")
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        const val CONNECT_TIMEOUT_MS = 15_000L
    }

    private val callbackHandler = Handler(Looper.getMainLooper())

    private val adapter: BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    @Volatile private var gatt: BluetoothGatt? = null
    @Volatile private var writeChar: BluetoothGattCharacteristic? = null

    @Volatile private var commands: BleCommandSession? = null
    @Volatile private var notifySignal: CompletableDeferred<Boolean>? = null

    @Volatile private var ready = false

    @Volatile private var sawNoService = false

    val isReady: Boolean get() = ready
    val isBluetoothOn: Boolean get() = adapter?.isEnabled == true

    private fun normalizeMac(raw: String): String? {
        val plain = raw.replace(":", "").replace("-", "").uppercase()
        if (plain.length != 12 || !plain.all { it in "0123456789ABCDEF" }) return null
        return plain.chunked(2).joinToString(":")
    }

    suspend fun connect(mac: String, timeoutMs: Long = CONNECT_TIMEOUT_MS): BleConnectResult {
        val a = adapter ?: return BleConnectResult.Failed("本机没有蓝牙适配器", retryable = false)
        if (!a.isEnabled) return BleConnectResult.Failed("蓝牙未开启", retryable = false)

        val normalized = normalizeMac(mac)
            ?: return BleConnectResult.Failed("设备地址无效", retryable = false).also {
                ProtocolLog.e("BLE 连接失败：MAC 格式不对（$mac）", null)
            }

        val device = try {
            a.getRemoteDevice(normalized)
        } catch (t: Throwable) {
            ProtocolLog.e("BLE 连接失败：$normalized", t)
            return BleConnectResult.Failed("设备地址无效", retryable = false)
        }

        disconnect()
        ready = false
        sawNoService = false
        val notifyDone = CompletableDeferred<Boolean>()
        notifySignal = notifyDone
        var connected = false

        return try {
            withTimeout(timeoutMs) {
                @Suppress("DEPRECATION")
                val connection = device.connectGatt(context, false, callback,
                    android.bluetooth.BluetoothDevice.TRANSPORT_LE,
                    android.bluetooth.BluetoothDevice.PHY_LE_1M_MASK, callbackHandler)
                gatt = connection
                    ?: return@withTimeout BleConnectResult.Failed("系统未能建立蓝牙连接")

                if (notifyDone.await()) { connected = true; BleConnectResult.Ready }
                else if (sawNoService) BleConnectResult.NoService
                else BleConnectResult.Failed("连接中断")
            }
        } catch (_: TimeoutCancellationException) {
            ProtocolLog.w("BLE 连接超时（${timeoutMs}ms）：$mac")

            if (sawNoService) BleConnectResult.NoService
            else BleConnectResult.Failed("连接超时，请靠近设备后重试", retryable = false)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Exception) {
            ProtocolLog.e("BLE 连接异常", t)
            BleConnectResult.Failed("蓝牙连接异常")
        } finally {
            notifySignal = null
            if (!connected || !ready) disconnect()
        }
    }

    fun disconnect() {
        ready = false
        val previous = gatt
        gatt = null
        commands?.onDisconnected()
        commands = null
        runCatching { previous?.disconnect() }
        runCatching { previous?.close() }
        writeChar = null

        notifySignal?.takeIf { !it.isCompleted }?.complete(false)
    }

    suspend fun query(timeoutMs: Long = BleCommandSession.DEFAULT_TIMEOUT_MS): DeviceQueryState? =
        commands?.query(timeoutMs)

    suspend fun writeRate(downData: ByteArray, timeoutMs: Long = BleCommandSession.DEFAULT_TIMEOUT_MS): Boolean =
        commands?.writeRate(downData, timeoutMs) == true

    suspend fun closeValve(timeoutMs: Long = BleCommandSession.DEFAULT_TIMEOUT_MS): Boolean =
        commands?.closeValve(timeoutMs) == true

    suspend fun collect(timeoutMs: Long = BleCommandSession.DEFAULT_TIMEOUT_MS): ByteArray? =
        commands?.collect(timeoutMs)

    suspend fun writeBack(data: ByteArray, timeoutMs: Long = BleCommandSession.DEFAULT_TIMEOUT_MS): Boolean =
        commands?.writeBack(data, timeoutMs) == true

    private fun writeChunk(
        g: BluetoothGatt,
        c: BluetoothGattCharacteristic,
        chunk: ByteArray
    ): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {

        g.writeCharacteristic(c, chunk, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) ==
            android.bluetooth.BluetoothStatusCodes.SUCCESS
    } else {
        @Suppress("DEPRECATION")
        c.value = chunk
        @Suppress("DEPRECATION")
        c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        @Suppress("DEPRECATION")
        g.writeCharacteristic(c)
    }

    private val callback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (g != gatt) return
            if (status != BluetoothGatt.GATT_SUCCESS) { disconnect(); return }
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    ProtocolLog.i("BLE 已连接，开始发现服务")
                    if (runCatching { g.discoverServices() }.getOrDefault(false) != true) disconnect()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    ProtocolLog.w("BLE 已断开（status=$status）")
                    disconnect()
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (g != gatt) return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                ProtocolLog.w("BLE 服务发现失败 status=$status")
                notifySignal?.takeIf { !it.isCompleted }?.complete(false)
                return
            }
            val svc = g.getService(SERVICE_UUID)
            val notify = svc?.getCharacteristic(NOTIFY_UUID)
            val write = svc?.getCharacteristic(WRITE_UUID)
            if (svc == null || notify == null || write == null) {

                ProtocolLog.w("BLE 未找到 FF00/FF01/FF02 特征——这台设备没有水控协议，不是蓝牙表")
                sawNoService = true
                notifySignal?.takeIf { !it.isCompleted }?.complete(false)
                return
            }
            writeChar = write
            if (runCatching { g.setCharacteristicNotification(notify, true) }.getOrDefault(false) != true) {
                notifySignal?.complete(false)
                return
            }

            val cccd = notify.getDescriptor(CCCD_UUID)
            if (cccd == null) {
                ProtocolLog.w("BLE 未找到 CCCD 描述符")
                notifySignal?.takeIf { !it.isCompleted }?.complete(false)
                return
            }
            val accepted = runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == android.bluetooth.BluetoothStatusCodes.SUCCESS
                } else {
                    @Suppress("DEPRECATION")
                    cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    @Suppress("DEPRECATION")
                    g.writeDescriptor(cccd)
                }
            }.getOrDefault(false)
            if (!accepted) notifySignal?.complete(false)
        }

        override fun onDescriptorWrite(
            g: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            if (g != gatt || descriptor.uuid != CCCD_UUID || descriptor.characteristic.uuid != NOTIFY_UUID) return
            val ok = status == BluetoothGatt.GATT_SUCCESS
            if (ok) {
                val characteristic = writeChar ?: run { notifySignal?.complete(false); return }
                commands = BleCommandSession(
                    writeChunk = { chunk -> ready && g == gatt && writeChunk(g, characteristic, chunk) },
                    disconnect = { if (g == gatt) disconnect() },
                )
            }
            ready = ok
            ProtocolLog.i("BLE 通知开启${if (ok) "成功，可以发命令" else "失败 status=$status"}")
            notifySignal?.takeIf { !it.isCompleted }?.complete(ok)
        }

        override fun onCharacteristicWrite(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (g != gatt || characteristic.uuid != WRITE_UUID) return
            commands?.onWriteCompleted(status == BluetoothGatt.GATT_SUCCESS)
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            if(g == gatt && characteristic.uuid == NOTIFY_UUID) commands?.onNotification(value)
        }

        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (g != gatt || characteristic.uuid != NOTIFY_UUID) return
            val bytes = characteristic.value ?: return
            commands?.onNotification(bytes)
        }
    }

}
