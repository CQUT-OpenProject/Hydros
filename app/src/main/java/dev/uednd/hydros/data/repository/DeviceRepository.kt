package dev.uednd.hydros.data.repository

import dev.uednd.hydros.data.ble.*
import dev.uednd.hydros.data.network.*
import dev.uednd.hydros.data.storage.HydrosStore
import dev.uednd.hydros.ui.state.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.net.SocketTimeoutException
import kotlinx.serialization.json.JsonObject

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
    suspend fun prepare(auth: Credentials, device: DeviceUi, preferBle: Boolean = false): DeviceUi
    suspend fun release(keepConnection: () -> Boolean)
}
class RealDeviceRepository(
    private val transport: CampusTransport,
    private val store: HydrosStore,
    private val ble: BlePort,
    private val operation: Mutex,
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
) : DeviceRepository {
    private data class DeviceInfoSnapshot(val auth: Credentials, val device: DeviceUi, val at: Long)
    private val infoCache = linkedMapOf<String, DeviceInfoSnapshot>()

    override suspend fun find(auth: Credentials, mac: String, scanMac: String?, rssi: Int?): DeviceUi =
        withTimeoutOrNull(18_000) { loadInfo(auth, mac, scanMac, rssi) }
            ?: throw NetworkFailure("设备查询超时，可重新查询，无需再次扫码", SocketTimeoutException())

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
            infoCache[device.id] = DeviceInfoSnapshot(auth, device, now())
            if (infoCache.size > 32) infoCache.remove(infoCache.keys.first())
        }
        return device
    }
    override suspend fun prepare(auth: Credentials, device: DeviceUi, preferBle: Boolean): DeviceUi = operation.withLock {
        val cached = synchronized(infoCache) { infoCache[device.id] }
        val fresh = cached?.takeIf {
            it.auth == auth && it.device.ledgerMac == device.ledgerMac && now() - it.at in 0..30_000
        }?.device ?: withTimeoutOrNull(18_000) {
            loadInfo(auth, device.ledgerMac, fallbackConnectMac = device.connectMac)
        } ?: throw NetworkFailure("设备查询超时，请重新连接")
        val knownBle = fresh.isBle == true || fresh.channel == ControlChannel.BLE || preferBle
        if(!knownBle) {
            val response = transport.post("order/tcpDevice/query/rateOrder/using", mapOf("snCode" to fresh.id, "xfModel" to "0"), auth)
            when(val state = runningOrder(response)) {
                is RunningOrder.Running -> return@withLock remember(auth, fresh.copy(channel=ControlChannel.CLOUD, status=DeviceStatus.BUSY))
                RunningOrder.Ended -> return@withLock remember(auth, fresh.copy(channel=ControlChannel.CLOUD,
                    status=if(fresh.status == DeviceStatus.OFFLINE) DeviceStatus.OFFLINE else DeviceStatus.AVAILABLE))
                RunningOrder.Unknown -> if(response.errorCode != 306) throw ApiFailure(response.errorCode, response.displayMessage ?: "设备状态暂无法确认")
            }
        }
        try {
            when (val result = ble.connectWithRetry(fresh.connectMac)) {
                BleConnectResult.Ready -> Unit
                BleConnectResult.NoService -> {
                    // Only positive service discovery disproves an earlier remembered BLE choice.
                    store.updateAccount(auth.key) { account -> account.copy(devices = account.devices.map {
                        if (it.id == fresh.id) it.copy(channel = ControlChannel.UNKNOWN) else it
                    }) }
                    synchronized(infoCache) { infoCache.remove(fresh.id) }
                    throw ApiFailure(null, "设备离线，且不支持蓝牙水控，请稍后重新连接")
                }
                is BleConnectResult.Failed -> throw ApiFailure(null, result.reason)
            }
            val state = ble.query() ?: throw ApiFailure(null, "无法读取设备状态，请靠近设备重试")
            when (state.state) {
                0 -> Unit
                1 -> throw ApiFailure(307, "设备正在使用；当前账户没有可恢复的本地订单")
                3, 6 -> throw ApiFailure(null, "设备有未结算记录，请先在原使用账户或服务商官方应用中完成结算")
                else -> throw ApiFailure(null, "设备状态暂不支持开始使用，请稍后重试")
            }
        } catch (error: Exception) {
            ble.disconnect()
            throw error
        }
        remember(auth, fresh.copy(channel=ControlChannel.BLE, status=DeviceStatus.AVAILABLE))
    }
    override suspend fun release(keepConnection: () -> Boolean) = operation.withLock {
        // Session creation and this ownership check use the same operation lock.
        if (!keepConnection()) ble.disconnect()
    }
    private suspend fun remember(auth: Credentials, device: DeviceUi): DeviceUi {
        store.updateAccount(auth.key) { it.copy(devices=it.devices.filterNot { d -> d.id == device.id } + device,
            recent=listOf(device.id) + it.recent.filterNot { id -> id == device.id }) }
        return device
    }
}
