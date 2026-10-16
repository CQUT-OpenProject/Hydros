package dev.uednd.hydros.data.repository

import dev.uednd.hydros.data.ble.*
import dev.uednd.hydros.data.network.*
import dev.uednd.hydros.data.storage.HydrosStore
import dev.uednd.hydros.ui.state.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import java.time.Instant
import java.time.ZoneId

interface SessionRepository {
    val session: kotlinx.coroutines.flow.StateFlow<SessionUi?>
    suspend fun restore(auth: Credentials?)
    suspend fun start(auth: Credentials, device: DeviceUi)
    suspend fun finish(auth: Credentials?)
    suspend fun reconcile(auth: Credentials?)
}
class RealSessionRepository(private val transport: CampusTransport, private val store: HydrosStore,
    private val ble: BlePort, private val operation: Mutex, private val confirmationAttempts: Int = 10,
    private val pause: suspend () -> Unit = { delay(800) }) : SessionRepository {
    private val current = MutableStateFlow<SessionUi?>(null)
    override val session = current.asStateFlow()
    private val finishRequests = AtomicInteger()
    @Volatile private var activeProbe: Deferred<*>? = null

    // Only read-only probes may be interrupted. Billing and device writes retain transaction ownership.
    private suspend fun <T> backgroundProbe(localBle: Boolean = false, block: suspend () -> T): T? = coroutineScope {
        val probe = async(start = CoroutineStart.LAZY) {
            var completed = false
            try {
                // HTTP probes need room for default network + cellular acquisition + one retry.
                // Finish still preempts them immediately; BLE retains its shorter local deadline.
                withTimeoutOrNull(if (localBle) 4_000 else 15_000) { block() }.also { completed = it != null }
            } finally {
                // A cancelled command must not leave a late reply on a reused connection.
                if (localBle && !completed) ble.disconnect()
            }
        }
        activeProbe = probe
        try {
            if (finishRequests.get() > 0) probe.cancel()
            probe.await()
        } catch (error: CancellationException) {
            currentCoroutineContext().ensureActive()
            if (finishRequests.get() == 0) throw error
            null
        } finally {
            activeProbe = null
        }
    }
    override suspend fun restore(auth: Credentials?) = operation.withLock {
        val key=auth?.key ?: current.value?.ownerKey ?: store.lastAccount()
        val restored=key?.let { store.account(it).session }
        current.value=restored?.copy(stage=when(restored.stage) {
            SessionStage.OPENING -> SessionStage.OPEN_UNCONFIRMED
            SessionStage.STOPPING -> SessionStage.CLOSE_UNCONFIRMED
            SessionStage.SETTLING -> SessionStage.SETTLEMENT_PENDING
            else -> restored.stage
        })
    }
    private suspend fun save(value: SessionUi) {
        store.updateAccount(value.ownerKey) { it.copy(session=value) }
        current.value = value
    }
    private suspend fun clear(value: SessionUi) {
        store.updateAccount(value.ownerKey) { it.copy(session=null) }; current.value = null; ble.disconnect()
    }
    override suspend fun start(auth: Credentials, device: DeviceUi) = operation.withLock {
        if(current.value != null || store.account(auth.key).session != null) throw ApiFailure(null, "请先处理当前会话")
        if(device.channel == ControlChannel.UNKNOWN) throw ApiFailure(null, "请先核实设备连接")
        if(device.channel == ControlChannel.BLE) startBle(auth, device) else startCloud(auth, device)
    }
    private suspend fun startCloud(auth: Credentials, device: DeviceUi) {
        val existing = transport.post("order/tcpDevice/query/rateOrder/using", mapOf("snCode" to device.id, "xfModel" to "0"), auth)
        if (!existing.ok && existing.errorCode == 306) throw CloudDeviceOffline()
        when(val running = runningOrder(existing)) {
            is RunningOrder.Running -> {
                if(running.owner != true || running.orderNo.isNullOrBlank()) throw ApiFailure(307, "设备已被使用，无法确认订单属于当前账户")
                save(SessionUi(device, auth.key, stage=SessionStage.USING, orderNo=running.orderNo)); return
            }
            RunningOrder.Unknown -> throw ApiFailure(null, "设备状态未确认，未发送开启指令")
            RunningOrder.Ended -> Unit
        }
        var value = SessionUi(device, auth.key)
        save(value) // Intent persisted before any command can leave the process.
        try {
            val response = transport.post("order/tcpDevice/downRate/rateOrder", mapOf("snCode" to device.id, "xfModel" to "0"), auth)
            if(!response.ok) {
                clear(value)
                if (response.errorCode == 306) throw CloudDeviceOffline()
                response.requireSuccess()
            }
            val accepted = response.data as? JsonObject
            value = value.copy(orderNo = accepted?.text("orderNo").orEmpty(),
                consumeDate = accepted?.text("consumeDate").orEmpty())
            save(value)
            val confirmation=withTimeoutOrNull(12_000) {
                repeat(confirmationAttempts) {
                    val result=transport.post("order/tcpDevice/query/downRateResult", mapOf("snCode" to device.id), auth)
                    val obj=result.data as? JsonObject
                    val order=obj?.text("orderNo")?.takeIf(String::isNotBlank)
                    if(result.ok && obj?.number("state")==1 && obj.number("result")==0 && order!=null) return@withTimeoutOrNull obj
                    pause()
                }
                null
            }
            if(confirmation != null) {
                save(value.copy(stage=SessionStage.USING, orderNo=requireNotNull(confirmation.text("orderNo")), consumeDate=confirmation.text("consumeDate") ?: ""))
                return
            }
            value = value.copy(stage=SessionStage.OPEN_UNCONFIRMED, message="开启结果待确认，请勿重复开启")
            save(value)
        } catch(e: Exception) {
            withContext(NonCancellable) {
                if(current.value != null) save(value.copy(stage=SessionStage.OPEN_UNCONFIRMED, message="开启结果待确认，请核实设备状态"))
            }
            throw e
        }
    }
    private suspend fun connect(value: SessionUi) {
        if(!ble.ready && ble.connectWithRetry(value.device.connectMac) !is BleConnectResult.Ready) throw ApiFailure(null, "蓝牙无法连接，请靠近设备重试")
    }
    private suspend fun startBle(auth: Credentials, device: DeviceUi) {
        val initial = SessionUi(device, auth.key)
        connect(initial)
        val state = ble.query() ?: throw ApiFailure(null, "无法读取设备状态")
        if(state.state != 0) throw ApiFailure(307, "设备正在使用或存在未结算记录；当前账户没有可恢复的订单")
        if(device.deviceId.isBlank() || device.bigTypeId == null || device.smallTypeId == null) throw ApiFailure(null, "设备缺少费率请求参数")
        val signature = BleSignBuilder.signRateOrder(auth.loginCode, auth.phone, device.deviceId, "0", state.randomNumber) ?: throw ApiFailure(null, "费率签名失败")
        val data = transport.post("order/downRate/bluetooth/rateOrder", mapOf("deviceId" to device.deviceId,
            "macAddress" to requireNotNull(MacFormat.normalize(device.ledgerMac)), "macType" to BleSignBuilder.macType(state.devType, state.a1),
            "bigTypeId" to device.bigTypeId, "smallTypeId" to device.smallTypeId, "protocolType" to state.protocolType,
            "randomNumber" to state.randomNumber.lowercase(), "xfModel" to "0", "signature" to signature), auth).requireData()
        val consumeDate = data.text("consumeDate")?.takeIf(String::isNotBlank) ?: throw ApiFailure(null, "费率响应缺少恢复所需消费时间")
        var value = initial.copy(consumeDate=consumeDate, orderNo=data.text("orderNo") ?: data.text("liquidOrderNo") ?: "",
            protocolType=state.protocolType, randomNumber=state.randomNumber)
        save(value)
        val bytes = data.text("downData")?.let(BleFrame::fromHex)?.takeIf { it.isNotEmpty() }
        if(bytes == null) { save(value.copy(stage=SessionStage.SETTLEMENT_PENDING, failureReportPending=true, message="费率数据无效，未写入设备")); reportFailure(auth); return }
        try {
            value=value.copy(rateAttempted=true); save(value)
            val confirmed = ble.writeRate(bytes)
            save(value.copy(stage=if(confirmed) SessionStage.USING else SessionStage.OPEN_UNCONFIRMED,
                message=if(confirmed) null else "费率已发送，开启待确认；请勿重复开启"))
        } catch(e: Exception) {
            save(value.copy(stage=SessionStage.OPEN_UNCONFIRMED, message="蓝牙开启结果未知，请重新核实")); throw e
        }
    }
    override suspend fun finish(auth: Credentials?) {
        val owner = current.value?.ownerKey
        if (auth != null && owner != null && auth.key != owner) throw ApiFailure(null, "请登录原账户恢复订单")
        finishRequests.incrementAndGet()
        try {
            activeProbe?.cancel()
            finishLocked(auth)
        } finally {
            finishRequests.decrementAndGet()
        }
    }
    private suspend fun finishLocked(auth: Credentials?) = operation.withLock {
        val value = current.value ?: return@withLock
        if(auth != null && auth.key != value.ownerKey) throw ApiFailure(null, "请登录原账户恢复订单")
        try {
            if(value.device.channel == ControlChannel.BLE) finishBle(auth, value)
            else { val credentials = auth ?: throw ApiFailure(null, "请重新登录后关闭设备"); finishCloud(credentials, value) }
        } catch(e: Exception) {
            val pending=current.value
            if(pending != null && e !is CancellationException) {
                if(pending.stage==SessionStage.STOPPING) save(pending.copy(stage=SessionStage.CLOSE_UNCONFIRMED, message="设备关闭尚未确认，请重试"))
                if(pending.stage==SessionStage.SETTLING) save(pending.copy(stage=SessionStage.SETTLEMENT_PENDING, message="设备结算待恢复，请重试"))
            }
            throw e
        }
    }
    private suspend fun finishCloud(auth: Credentials, original: SessionUi) {
        var value = original
        if(value.stage !in listOf(SessionStage.SETTLING, SessionStage.SETTLEMENT_PENDING)) {
            if(value.orderNo.isBlank()) {
                val running = transport.running(auth, value.device)
                when {
                    running is RunningOrder.Running && running.owner == true && !running.orderNo.isNullOrBlank() -> value = value.copy(orderNo=running.orderNo)
                    running == RunningOrder.Ended -> { settleEndedCloud(auth, value); return }
                    else -> { save(value.copy(stage=SessionStage.CLOSE_UNCONFIRMED, message="未取得本人订单号，无法确认关闭")); return }
                }
            }
            save(value.copy(stage=SessionStage.STOPPING))
            try {
                val close = transport.post("order/tcpDevice/closeOrder", mapOf("snCode" to value.device.id, "orderNo" to value.orderNo), auth)
                val closingOrOk = close.ok || close.errorCode == 307 || close.displayMessage?.contains("已在") == true
                if (!closingOrOk) close.requireSuccess()
            }
            catch(e: Exception) {
                withContext(NonCancellable) {
                    save(value.copy(stage=SessionStage.CLOSE_UNCONFIRMED, message="关闭请求未确认，可重新核实"))
                }
                throw e
            }
            val closed=withTimeoutOrNull(12_000) {
                repeat(confirmationAttempts) {
                    when(val running=transport.running(auth, value.device)) {
                        RunningOrder.Ended -> return@withTimeoutOrNull true
                        is RunningOrder.Running -> if(running.orderNo!=null && running.orderNo!=value.orderNo) return@withTimeoutOrNull true
                        RunningOrder.Unknown -> Unit
                    }
                    pause()
                }
                false
            } ?: false
            if(!closed) { save(value.copy(stage=SessionStage.CLOSE_UNCONFIRMED, message="设备关闭待确认，请检查水流并重试")); return }
            value = value.copy(stage=SessionStage.SETTLING, endedAt=System.currentTimeMillis()); save(value)
        }
        settleCloud(auth, value)
    }
    private suspend fun settleEndedCloud(auth: Credentials, original: SessionUi, background: Boolean = false) {
        var value = original
        if (value.orderNo.isBlank()) {
            val query = suspend { transport.post("order/tcpDevice/query/downRateResult", mapOf("snCode" to value.device.id), auth) }
            val result = if (background) backgroundProbe { query() } else query()
            if (result == null || background && finishRequests.get() > 0) return
            val data = result.data as? JsonObject
            val order = data?.text("orderNo")?.takeIf(String::isNotBlank)
            // This device's last result may belong to an earlier use. Recover only a matching opening.
            val date = data?.text("consumeDate")
            val matches = date != null && if (value.consumeDate.isNotBlank()) date == value.consumeDate else {
                val at = runCatching { parseDate(date).atZone(ZoneId.of("Asia/Shanghai")).toInstant().epochSecond }.getOrNull()
                at != null && at in (value.startedAt / 1000)..Instant.now().epochSecond
            }
            if (!result.ok || data?.number("state") != 1 || data.number("result") != 0 || order == null || !matches ||
                data.flag("isOwner") == false || data.text("snCode")?.let { it != value.device.id } == true) {
                save(value.copy(message="当前没有使用中订单，原开启结果仍待核实；请稍后重试，持续失败请联系服务商"))
                return
            }
            value = value.copy(orderNo=order, consumeDate=date.orEmpty())
        }
        value = value.copy(stage=SessionStage.SETTLING, endedAt=value.endedAt ?: System.currentTimeMillis())
        save(value)
        settleCloud(auth, value)
    }
    private suspend fun settleCloud(auth: Credentials, value: SessionUi) {
        try {
            val response = transport.post("order/consumeOrder/result/query", mapOf("snCode" to value.device.id, "orderNo" to value.orderNo), auth)
            val data = response.data as? JsonObject
            if(response.ok && data?.text("orderNo") == value.orderNo && milli(data.text("consumeMoney")) != null) { clear(value); return }
            save(value.copy(stage=SessionStage.SETTLEMENT_PENDING, message="设备已关闭，服务端结算待完成"))
        } catch(e: Exception) { save(value.copy(stage=SessionStage.SETTLEMENT_PENDING, message="设备已关闭，结算查询失败，可重试")); throw e }
    }
    private suspend fun finishBle(auth: Credentials?, original: SessionUi, background: Boolean = false) {
        var value = original
        if(!value.rateAttempted) {
            if(value.consumeDate.isBlank()) throw ApiFailure(null, "费率订单信息未确认，设备未执行开启；请核实服务端订单")
            if(auth != null) { reportFailure(auth); if(current.value?.failureReportPending == false) clear(value) }
            return
        }
        connect(value)
        if(value.stage !in listOf(SessionStage.SETTLING, SessionStage.SETTLEMENT_PENDING, SessionStage.WRITEBACK_PENDING)) {
            save(value.copy(stage=SessionStage.STOPPING))
            if(!ble.closeValve()) {
                // A lost acknowledgement can follow a successful close, including an already idle device.
                connect(value)
                if (ble.query()?.state == 0) { recoverIdleBle(auth, value, background); return }
                save(value.copy(stage=SessionStage.CLOSE_UNCONFIRMED, message="关阀未确认，请靠近设备重试", failureReportPending=true))
                if(auth != null) reportFailure(auth)
                return
            }
            value = value.copy(stage=SessionStage.SETTLING, endedAt=System.currentTimeMillis()); save(value)
            ble.disconnect(); connect(value) // Use fresh post-close encrypted consumption state.
        }
        val state = ble.query() ?: throw ApiFailure(null, "设备状态读取失败，恢复信息已保留")
        if(state.state == 1) { save(value.copy(stage=SessionStage.CLOSE_UNCONFIRMED, endedAt=null, message="设备仍在使用，请重试关阀")); return }
        if(state.state !in listOf(0, 3, 6)) throw ApiFailure(null, "设备状态未知，未执行结算")
        if (state.state == 0) { recoverIdleBle(auth, value, background); return }
        value = value.copy(endedAt = value.endedAt ?: System.currentTimeMillis())
        if(auth == null) { save(value.copy(stage=SessionStage.SETTLEMENT_PENDING, message="设备已关闭，请登录原账户上传消费")); return }
        try {
            if((value.uploadAttempted && !value.uploaded) || (value.uploaded && value.writeBackHex.isNullOrBlank())) {
                val result=if(value.orderNo.isNotBlank()) transport.post("order/consumeOrder/result/query",
                    mapOf("snCode" to value.device.id, "orderNo" to value.orderNo), auth) else null
                val data=result?.data as? JsonObject
                if(result?.ok == true && data?.text("orderNo") == value.orderNo && milli(data.text("consumeMoney")) != null) {
                    val back=data.text("clData")?.let(DeviceCrypto::decryptByAes)?.substringAfter('-', "")
                        ?.takeIf { BleFrame.fromHex(it)?.isNotEmpty() == true }
                    value=value.copy(uploaded=true, writeBackHex=back, stage=SessionStage.WRITEBACK_PENDING)
                    save(value)
                } else throw ApiFailure(null, "上次消费上传结果未知，等待服务端核实；不会重复上传")
            }
            if(!value.uploaded) {
                val payload = ble.collect()?.takeIf { it.isNotEmpty() } ?: throw ApiFailure(null, "消费采集失败")
                val hex = BleFrame.toHex(payload).lowercase()
                val signature = BleSignBuilder.signUpload(auth.loginCode, auth.phone, hex) ?: throw ApiFailure(null, "消费签名失败")
                value=value.copy(uploadAttempted=true, xfDataHex=hex); save(value)
                val response = transport.post("order/upload/bluetooth/data", mapOf("protocolType" to state.protocolType,
                    "randomNumber" to state.randomNumber.lowercase(), "xfData" to hex, "signature" to signature), auth)
                // Nonzero legacy codes are not proof of billing; retain the session for reconciliation.
                if(!response.ok && response.errorCode !in listOf(206,209)) {
                    value=value.copy(uploadAttempted=false); save(value)
                    response.requireSuccess()
                }
                response.requireSuccess()
                val data = response.data as? JsonObject ?: throw ApiFailure(null, "消费上传返回缺少写回数据")
                val back = data.text("clData")?.let(DeviceCrypto::decryptByAes)?.substringAfter('-', "")
                    ?.takeIf { BleFrame.fromHex(it)?.isNotEmpty() == true }
                value = value.copy(uploaded=true, writeBackHex=back, stage=SessionStage.WRITEBACK_PENDING, failureReportPending=false,
                    message="服务端已记账，设备记录待清除")
                save(value) // Never upload again after this point, even after a crash.
            }
            val bytes = value.writeBackHex?.let(BleFrame::fromHex)?.takeIf { it.isNotEmpty() }
                ?: throw ApiFailure(null, "设备已关闭并记账，但服务端未提供有效清除数据；请稍后重试，持续失败请联系服务商")
            if(!ble.writeBack(bytes) || ble.query()?.state != 0) throw ApiFailure(null, "设备记录尚未清除，请重试写回")
            clear(value)
        } catch(e: Exception) {
            if(e is CancellationException) throw e
            save(value.copy(stage=if(value.uploaded) SessionStage.WRITEBACK_PENDING else SessionStage.SETTLEMENT_PENDING,
                message=e.message ?: "结算待恢复", failureReportPending=!value.uploaded && !value.uploadAttempted))
            if(!value.uploaded && !value.uploadAttempted) reportFailure(auth)
            throw e
        }
    }
    private suspend fun recoverIdleBle(auth: Credentials?, original: SessionUi, background: Boolean = false) {
        val value = original.copy(stage=SessionStage.SETTLEMENT_PENDING,
            endedAt=original.endedAt ?: System.currentTimeMillis(), message="设备已空闲，原订单结算待核实")
        // An idle device has no consumption record to collect or clear. Never upload empty/stale data.
        if (value.uploaded) { clear(value); return }
        save(value)
        if (auth == null) { save(value.copy(message="设备已空闲，请登录原账户核实结算")); return }
        if (value.orderNo.isBlank()) {
            save(value.copy(message="设备已空闲，但缺少原订单号，无法核实结算；请联系服务商处理"))
            return
        }
        val query = suspend { transport.post("order/consumeOrder/result/query",
            mapOf("snCode" to value.device.id, "orderNo" to value.orderNo), auth) }
        val result = if (background) backgroundProbe { query() } else query()
        if (result == null || background && finishRequests.get() > 0) return
        val data = result.data as? JsonObject
        if (result.ok && data?.text("orderNo") == value.orderNo && milli(data.text("consumeMoney")) != null) clear(value)
    }
    private suspend fun reportFailure(auth: Credentials) {
        val value = current.value ?: return
        if(!value.failureReportPending || value.consumeDate.isBlank() || value.ownerKey != auth.key) return
        val signature = BleSignBuilder.signFail(auth.loginCode, auth.phone, value.consumeDate) ?: return
        try {
            transport.post("order/upload/bluetooth/fail", mapOf("consumeDate" to value.consumeDate, "signature" to signature), auth).requireSuccess()
            save(value.copy(failureReportPending=false))
        } catch(e: CancellationException) { throw e } catch(_: Exception) { /* Retry from the durable flag on next reconciliation. */ }
    }
    override suspend fun reconcile(auth: Credentials?) {
        if(finishRequests.get() > 0 || !operation.tryLock()) return
        try {
            if (finishRequests.get() > 0) return
            val value = current.value ?: return
            if(auth != null && value.ownerKey != auth.key) return
            if(value.device.channel == ControlChannel.BLE) {
                if(value.stage in listOf(SessionStage.SETTLEMENT_PENDING, SessionStage.WRITEBACK_PENDING, SessionStage.SETTLING)) finishBle(auth, current.value ?: return, background=true)
                else if(value.stage == SessionStage.USING || value.stage == SessionStage.CLOSE_UNCONFIRMED) {
                    val state = backgroundProbe(localBle = true) { connect(value); ble.query() } ?: return
                    if (finishRequests.get() > 0) return
                    if (state.state == 0) recoverIdleBle(auth, value, background=true)
                    else if(state.state in listOf(3,6)) {
                        val closed = value.copy(stage=SessionStage.SETTLEMENT_PENDING, endedAt=value.endedAt ?: System.currentTimeMillis())
                        save(closed)
                        finishBle(auth, closed, background=true)
                    }
                }
                else if(value.stage == SessionStage.OPENING || value.stage == SessionStage.OPEN_UNCONFIRMED) {
                    val state = backgroundProbe(localBle = true) { connect(value); ble.query() } ?: return
                    if (finishRequests.get() > 0) return
                    if(state.state == 1) save(value.copy(stage=SessionStage.USING, message=null))
                    else if (state.state == 0) recoverIdleBle(auth, value, background=true)
                    else if(state.state in listOf(3, 6)) save(value.copy(stage=SessionStage.SETTLEMENT_PENDING, message="设备有消费记录，待结算"))
                }
                return
            }
            if(auth == null) return
            if(value.stage in listOf(SessionStage.SETTLING, SessionStage.SETTLEMENT_PENDING)) { settleCloud(auth, value); return }
            when(val running = backgroundProbe { transport.running(auth, value.device) } ?: return) {
                is RunningOrder.Running -> when {
                    value.orderNo.isNotBlank() && !running.orderNo.isNullOrBlank() && value.orderNo != running.orderNo ->
                        settleEndedCloud(auth, value, background=true)
                    running.owner == true && !running.orderNo.isNullOrBlank() -> save(value.copy(orderNo=running.orderNo,
                        stage=if(value.stage in listOf(SessionStage.OPENING, SessionStage.OPEN_UNCONFIRMED)) SessionStage.USING else value.stage, message=null))
                }
                RunningOrder.Ended -> settleEndedCloud(auth, value, background=true)
                RunningOrder.Unknown -> Unit
            }
        } finally { operation.unlock() }
    }
}
