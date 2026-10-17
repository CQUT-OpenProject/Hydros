package dev.uednd.hydros.data.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.emptyFlow

data class NearbyAdvertisement(val name: String, val mac: String, val rssi: Int)

fun interface NearbyScanner {
    /** Each collection owns its scanner callback and releases it on cancellation. */
    fun advertisements(): Flow<NearbyAdvertisement>
}

/** Offline graphs never obtain an Android Bluetooth scanner. */
object NoNearbyScanner : NearbyScanner {
    override fun advertisements(): Flow<NearbyAdvertisement> = emptyFlow()
}

class NearbyScanFailure(override val message: String) : Exception(message)

class AndroidNearbyScanner(context: Context) : NearbyScanner {
    private val context = context.applicationContext

    @SuppressLint("MissingPermission")
    override fun advertisements(): Flow<NearbyAdvertisement> = callbackFlow {
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter?.isEnabled != true) throw NearbyScanFailure("蓝牙未开启，请开启后重新扫描")
        val scanner = adapter.bluetoothLeScanner ?: throw NearbyScanFailure("蓝牙扫描不可用，请重试")
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                try {
                    val name = result.scanRecord?.deviceName ?: result.device.name ?: return
                    trySend(NearbyAdvertisement(name, result.device.address, result.rssi))
                } catch (error: SecurityException) {
                    close(error)
                }
            }

            override fun onScanFailed(errorCode: Int) {
                close(NearbyScanFailure("蓝牙扫描未完成，请稍后重新扫描"))
            }
        }
        try {
            scanner.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), callback)
            awaitClose { }
        } finally {
            runCatching { scanner.stopScan(callback) }
        }
    }
}
