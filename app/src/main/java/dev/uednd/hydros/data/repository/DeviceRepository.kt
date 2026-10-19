package dev.uednd.hydros.data.repository

import dev.uednd.hydros.data.feedback.*
import dev.uednd.hydros.data.ble.MacFormat
import dev.uednd.hydros.data.network.*
import dev.uednd.hydros.data.storage.HydrosStore
import dev.uednd.hydros.ui.state.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.net.SocketTimeoutException
import kotlinx.serialization.json.JsonObject

internal const val DEVICE_INFO_TIMEOUT_MS = 18_000L

sealed interface RunningOrder {
    data class Running(val orderNo: String?, val owner: Boolean?) : RunningOrder
    data object Ended : RunningOrder
    data object Unknown : RunningOrder
}
fun runningOrder(response: ApiResponse<kotlinx.serialization.json.JsonElement>): RunningOrder {
    val data = response.data as? JsonObject
    val order = data?.text("orderNo")?.takeIf(String::isNotBlank)
    return when {
        response.errorCode == 307 || response.ok && order != null -> RunningOrder.Running(order, data?.flag("isOwner"))
        response.ok -> RunningOrder.Ended
        else -> RunningOrder.Unknown
    }
}
suspend fun CampusTransport.running(auth: Credentials, device: DeviceUi) = runningOrder(post("order/tcpDevice/query/rateOrder/using", mapOf("snCode" to device.id, "xfModel" to "0"), auth))
val DEVICE_BROADCAST_PREFIX: String = String(byteArrayOf(75, 76, 67, 88, 75, 74, 45, 87, 97, 116, 101, 114), Charsets.UTF_8)
fun qrMac(raw: String): String? {
    if (!raw.contains(DEVICE_BROADCAST_PREFIX, ignoreCase = true)) return null
    return raw.split(',').reversed().firstNotNullOfOrNull { MacFormat.normalize(it.trim()) }
}
interface DeviceRepository {
    suspend fun find(auth: Credentials, mac: String, scanMac: String? = null, rssi: Int? = null): DeviceUi
    suspend fun prepare(auth: Credentials, device: DeviceUi): DeviceUi
    suspend fun release(keepConnection: () -> Boolean)
}
class RealDeviceRepository(
    private val transport: CampusTransport,
    private val store: HydrosStore,
    private val operation: Mutex,
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
) : DeviceRepository {
    private data class DeviceInfoSnapshot(
        val auth: Credentials, val device: DeviceUi, val lookupMac: String, val at: Long,
    )
    private data class LookupKey(val auth: Credentials, val mac: String)
    private class LookupGate(val mutex: Mutex = Mutex(), var users: Int = 0)
    private val infoCache = linkedMapOf<String, DeviceInfoSnapshot>()
    private val lookups = mutableMapOf<LookupKey, LookupGate>()

    private fun DeviceInfoSnapshot.isFresh(auth: Credentials): Boolean =
        this.auth == auth && now() - at in 0..30_000

    override suspend fun find(auth: Credentials, mac: String, scanMac: String?, rssi: Int?): DeviceUi =
        failureBoundary(FailureOperation.SCAN, FailureStage.DEVICE_INFO) { withTimeoutOrNull(DEVICE_INFO_TIMEOUT_MS) { findInfo(auth, mac, scanMac, rssi) }
            ?: throw NetworkFailure("设备查询超时，可重新查询，无需再次扫码", SocketTimeoutException()) }

    /** Same-address callers share the successful lookup; cancellation leaves no detached request. */
    private suspend fun findInfo(auth: Credentials, mac: String, scanMac: String? = null,
        rssi: Int? = null, fallbackConnectMac: String? = null): DeviceUi {
        val canonical = MacFormat.normalize(mac) ?: throw ApiFailure(null, "设备地址无效")
        val key = LookupKey(auth, canonical)
        val gate = synchronized(infoCache) {
            lookups.getOrPut(key) { LookupGate() }.also { it.users++ }
        }
        try {
            return gate.mutex.withLock {
                val cached = synchronized(infoCache) {
                    infoCache.values.lastOrNull {
                        it.isFresh(auth) && (canonical == it.lookupMac ||
                            canonical == MacFormat.normalize(it.device.ledgerMac) ||
                            canonical == MacFormat.normalize(it.device.connectMac))
                    }
                }
                if (cached == null) return@withLock loadInfo(auth, canonical, scanMac, rssi, fallbackConnectMac)
                val device = cached.device.copy(
                    connectMac = scanMac ?: cached.device.connectMac,
                    signal = rssi?.let { "$it dBm" } ?: cached.device.signal,
                )
                if (device != cached.device) {
                    store.updateAccount(auth.key) { account -> account.copy(
                        devices = account.devices.filterNot { it.id == device.id } + device,
                    ) }
                    synchronized(infoCache) {
                        // Observing another advertisement must not extend server-data freshness.
                        if (infoCache[device.id] === cached) infoCache[device.id] = cached.copy(device = device)
                    }
                }
                device
            }
        } finally {
            synchronized(infoCache) { if (--gate.users == 0) lookups.remove(key) }
        }
    }

    private suspend fun loadInfo(auth: Credentials, mac: String, scanMac: String? = null,
        rssi: Int? = null, fallbackConnectMac: String? = null): DeviceUi {
        val canonical = MacFormat.normalize(mac) ?: throw ApiFailure(null, "设备地址无效")
        var data: JsonObject? = null
        for(candidate in MacFormat.lookupVariants(canonical)) {
            val result = transport.get("device/info/mac", mapOf("macAddress" to candidate), auth)
            if(result.ok && result.data is JsonObject) { data = result.data; break }
            if(!result.ok && result.errorCode != 4004) result.requireSuccess()
        }
        val obj = data ?: throw ApiFailure(4004, "设备未登记或二维码无法匹配")
        val sn = obj.text("snCode")?.takeIf(String::isNotBlank) ?: throw ApiFailure(null, "设备缺少序列号")
        val name = obj.text("deviceName") ?: "用水设备"
        val saved = store.account(auth.key).devices.find { it.id == sn }
        val ledger = obj.text("macAddress") ?: canonical
        val device = DeviceUi(sn, name, name.substringAfter('-'), category(name, obj.number("bigTypeId")),
            when(obj.number("onlineStatusId")) { 1 -> DeviceStatus.AVAILABLE; 0 -> DeviceStatus.OFFLINE; else -> DeviceStatus.UNKNOWN },
            rssi?.let { "$it dBm" } ?: "未测量", saved?.channel ?: ControlChannel.UNKNOWN,
            yuanMilli(obj.text("withholdMoney")), obj.text("deviceId") ?: "", ledger,
            scanMac ?: obj.text("realMac")?.let(MacFormat::normalize) ?: saved?.connectMac ?: fallbackConnectMac?.takeIf(String::isNotBlank) ?: MacFormat.connectAddressFor(ledger),
            obj.text("bigTypeId"), obj.text("smallTypeId"), obj.flag("isBle"))
        store.updateAccount(auth.key) { it.copy(devices = it.devices.filterNot { d -> d.id == sn } + device) }
        synchronized(infoCache) {
            infoCache[device.id] = DeviceInfoSnapshot(auth, device, canonical, now())
            if (infoCache.size > 32) infoCache.remove(infoCache.keys.first())
        }
        return device
    }
    override suspend fun prepare(auth: Credentials, device: DeviceUi): DeviceUi = operation.withLock {
        if (store.account(auth.key).session != null) throw ApiFailure(null, "请先处理当前设备会话")
        val cached = synchronized(infoCache) { infoCache[device.id] }
        val fresh = cached?.takeIf {
            it.isFresh(auth) && it.device.ledgerMac == device.ledgerMac
        }?.device ?: withTimeoutOrNull(DEVICE_INFO_TIMEOUT_MS) {
            failureBoundary(FailureOperation.DEVICE, FailureStage.DEVICE_INFO) { findInfo(auth, device.ledgerMac, fallbackConnectMac = device.connectMac) }
        } ?: throw NetworkFailure("设备查询超时，请重新连接")
        val response = failureBoundary(FailureOperation.DEVICE, FailureStage.CLOUD_STATUS) { transport.post("order/tcpDevice/query/rateOrder/using", mapOf("snCode" to fresh.id, "xfModel" to "0"), auth) }
        when(val state = runningOrder(response)) {
            is RunningOrder.Running -> remember(auth, fresh.copy(channel=ControlChannel.CLOUD, status=DeviceStatus.BUSY))
            RunningOrder.Ended -> remember(auth, fresh.copy(channel=ControlChannel.CLOUD,
                status=if(fresh.status == DeviceStatus.OFFLINE) DeviceStatus.OFFLINE else DeviceStatus.AVAILABLE))
            RunningOrder.Unknown -> if (response.errorCode == 306) throw CloudDeviceOffline()
                else throw ApiFailure(response.errorCode, response.displayMessage ?: "设备状态暂无法确认")
        }
    }
    override suspend fun release(keepConnection: () -> Boolean) = Unit
    private suspend fun remember(auth: Credentials, device: DeviceUi): DeviceUi {
        store.updateAccount(auth.key) { it.copy(devices=it.devices.filterNot { d -> d.id == device.id } + device,
            recent=listOf(device.id) + it.recent.filterNot { id -> id == device.id }) }
        synchronized(infoCache) {
            infoCache[device.id]?.takeIf { it.auth == auth }?.let {
                infoCache[device.id] = it.copy(device = device)
            }
        }
        return device
    }
}
