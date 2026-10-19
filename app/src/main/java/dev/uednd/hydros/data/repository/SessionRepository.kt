package dev.uednd.hydros.data.repository

import dev.uednd.hydros.data.feedback.*
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

private val EMPTY_SESSION_OBSERVATION = MutableStateFlow(SessionObservation()).asStateFlow()

interface SessionRepository {
    val observation: kotlinx.coroutines.flow.StateFlow<SessionObservation> get() = EMPTY_SESSION_OBSERVATION
    fun reportActionFailure(error: Exception) {}
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
    private val observed = MutableStateFlow(SessionObservation())
    override val observation = observed.asStateFlow()
    override fun reportActionFailure(error: Exception) {
        val value = current.value ?: return
        if (value.endedAt == null) updateObservation(false)
        if (observed.value.deviceId != value.device.id || observed.value.startedAt != value.startedAt)
            observed.value = SessionObservation(value.device.id, value.startedAt)
        observed.value = observed.value.copy(actionError = value.message ?: failureFeedback(error, FailureOperation.FINISH).text)
    }
    private fun updateObservation(success: Boolean) {
        val value = current.value ?: return
        val old = observed.value.takeIf { it.deviceId == value.device.id && it.startedAt == value.startedAt } ?: SessionObservation(value.device.id, value.startedAt)
        observed.value = old.copy(lastConfirmedAt = if (success) System.currentTimeMillis() else old.lastConfirmedAt,
            unavailable = !success, actionError = if (success) null else old.actionError)
    }
    private fun markDeviceUnavailable() {
        if (current.value?.endedAt == null) updateObservation(false)
    }
    private val finishRequests = AtomicInteger()
    @Volatile private var activeProbe: Deferred<*>? = null

    // Only read-only probes may be interrupted. Billing and device writes retain transaction ownership.
    private suspend fun <T> backgroundProbe(localBle: Boolean = false, block: suspend () -> T): T? = coroutineScope {
        val probe = async(start = CoroutineStart.LAZY) {
            var completed = false
            try {
                // HTTP probes use the read recovery budget: default attempt, same-network retry,
                // cellular acquisition, and one cellular request.
                // A BLE connection gets its own 15-second budget; only its query gets 4 seconds.
                withTimeoutOrNull(if (localBle) BleController.CONNECT_TIMEOUT_MS + 4_000 else READ_QUERY_RECOVERY_MILLIS) { block() }
                    .also { completed = it != null }
            } finally {
                // A cancelled command must not leave a late reply on a reused connection.
                if (localBle && !completed) ble.disconnect()
            }
        }
        activeProbe = probe
        try {
            if (finishRequests.get() > 0) probe.cancel()
            probe.await().also { if (it == null && finishRequests.get() == 0) markDeviceUnavailable() }
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
        observed.value = SessionObservation()
        current.value=restored?.copy(stage=when(restored.stage) {
            SessionStage.OPENING -> SessionStage.OPEN_UNCONFIRMED
            SessionStage.STOPPING -> SessionStage.CLOSE_UNCONFIRMED
            SessionStage.SETTLING -> SessionStage.SETTLEMENT_PENDING
            else -> restored.stage
        })
    }
    private suspend fun save(value: SessionUi) {
        val previous = current.value
        store.updateAccount(value.ownerKey) { it.copy(session=value) }
        current.value = value
        if (value.stage == SessionStage.USING || value.endedAt != null && previous?.endedAt == null) updateObservation(true)
    }
    private suspend fun clear(value: SessionUi) {
        observed.value = SessionObservation()
        store.updateAccount(value.ownerKey) { it.copy(session=null) }; current.value = null; ble.disconnect()
    }
    override suspend fun start(auth: Credentials, device: DeviceUi) = operation.withLock {
        if(current.value != null || store.account(auth.key).session != null) throw ApiFailure(null, "请先处理当前会话")
        if(device.channel == ControlChannel.UNKNOWN) throw ApiFailure(null, "请先核实设备连接")
        if (store.forceBluetoothConnection() && device.channel != ControlChannel.BLE) {
            throw ApiFailure(null, "已启用强制蓝牙连接，请重新连接设备")
        }
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
                    confirmedOpening(auth, value, result)?.let { confirmed ->
                        val running = transport.running(auth, device)
                        if (running is RunningOrder.Running && running.owner == true &&
                            running.orderNo == confirmed.text("orderNo")) return@withTimeoutOrNull confirmed
                    }
                    pause()
                }
                null
            }
            if(confirmation != null) {
                save(value.copy(stage=SessionStage.USING, orderNo=requireNotNull(confirmation.text("orderNo")), consumeDate=confirmation.text("consumeDate") ?: ""))
                return
            }
            value = value.copy(stage=SessionStage.OPEN_UNCONFIRMED, recoveryReason=SessionRecoveryReason.OPEN_UNKNOWN)
            save(value)
        } catch(e: Exception) {
            withContext(NonCancellable) {
                if(current.value != null) save(value.copy(stage=SessionStage.OPEN_UNCONFIRMED, recoveryReason=sessionRecoveryReason(e)))
            }
            throw e
        }
    }
    private suspend fun connect(value: SessionUi, operation: FailureOperation = FailureOperation.FINISH) {
        if (!ble.isConnectedTo(value.device.connectMac)) failureBoundary(operation, FailureStage.BLE_CONNECT) { ble.connectWithRetry(value.device.connectMac) }.requireReady(operation)
        if (!ble.isConnectedTo(value.device.connectMac)) throw ApiFailure(null, "蓝牙连接目标不匹配，请重新连接")
    }
    private suspend fun startBle(auth: Credentials, device: DeviceUi) {
        var value = SessionUi(device, auth.key)
        connect(value, FailureOperation.START)
        val state = ble.query() ?: throw ble.commandError(FailureOperation.START)
        when (state.state) {
            0 -> Unit
            1 -> throw ApiFailure(307, "设备正在使用，请选择其他设备")
            3, 6 -> throw ApiFailure(null, "设备有未结算记录，请在原使用账户或服务商官方应用完成结算")
            else -> throw ApiFailure(null, "暂时无法确认设备状态，请稍后重新连接")
        }
        if (device.deviceId.isBlank()) throw ApiFailure(null, "设备资料不完整，请重新扫描后再试")
        val signature = BleSignBuilder.signRateOrder(auth.loginCode, auth.phone, device.deviceId, "0", state.randomNumber) ?: throw ApiFailure(null, "费率签名失败")
        val ledgerMac = MacFormat.normalize(device.ledgerMac) ?: throw ApiFailure(null, "设备地址无效")
        save(value) // Allocation can reserve money even when no BLE command is ever sent.
        try {
            val response = transport.post("order/downRate/bluetooth/rateOrder", mapOf("deviceId" to device.deviceId,
                "macAddress" to ledgerMac, "macType" to BleSignBuilder.macType(state.devType, state.a1),
                "bigTypeId" to (device.bigTypeId ?: "0"), "smallTypeId" to (device.smallTypeId ?: "0"), "protocolType" to state.protocolType,
                "randomNumber" to state.randomNumber.lowercase(), "xfModel" to "0", "signature" to signature), auth)
            if (!response.ok) { clear(value); response.requireSuccess() }
            val data = response.requireData()
            value = value.copy(consumeDate=data.text("consumeDate").orEmpty(),
                orderNo=listOfNotNull(data.text("orderNo"), data.text("liquidOrderNo")).firstOrNull(String::isNotBlank).orEmpty(),
                failureReportPending=true)
            save(value)
            val bytes = data.text("downData")?.let(BleFrame::fromHex)?.takeIf { it.size in 1..BleFrame.MAX_PAYLOAD_BYTES }
            val invalid = when {
                value.consumeDate.isBlank() -> "费率响应缺少恢复所需消费时间，未写入设备"
                value.orderNo.isBlank() -> "费率响应缺少可恢复的订单号，未写入设备"
                bytes == null -> "费率数据无效，未写入设备"
                else -> null
            }
            if (invalid != null) {
                cancelUnusedBle(auth, value)
                throw ApiFailure(null, invalid)
            }
            value=value.copy(rateAttempted=true, failureReportPending=false); save(value)
            val confirmed = ble.writeRate(requireNotNull(bytes))
            save(value.copy(stage=if(confirmed) SessionStage.USING else SessionStage.OPEN_UNCONFIRMED,
                recoveryReason=if(confirmed) null else sessionRecoveryReason(ble.commandError(FailureOperation.START))))
        } catch(e: Exception) {
            withContext(NonCancellable) {
                current.value?.let { pending ->
                    save(pending.copy(stage=if(pending.rateAttempted) SessionStage.OPEN_UNCONFIRMED else SessionStage.SETTLEMENT_PENDING,
                        recoveryReason=if(pending.rateAttempted) sessionRecoveryReason(e) else SessionRecoveryReason.NOT_OPENED))
                }
            }
            throw e
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
                if(pending.stage==SessionStage.STOPPING) save(pending.copy(stage=SessionStage.CLOSE_UNCONFIRMED, recoveryReason=sessionRecoveryReason(e)))
                if(pending.stage==SessionStage.SETTLING) save(pending.copy(stage=SessionStage.SETTLEMENT_PENDING, recoveryReason=sessionRecoveryReason(e)))
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
                    else -> { save(value.copy(stage=SessionStage.CLOSE_UNCONFIRMED, recoveryReason=SessionRecoveryReason.MISSING_ORDER)); return }
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
                    save(value.copy(stage=SessionStage.CLOSE_UNCONFIRMED, recoveryReason=sessionRecoveryReason(e)))
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
            if(!closed) { save(value.copy(stage=SessionStage.CLOSE_UNCONFIRMED, recoveryReason=SessionRecoveryReason.CLOSE_UNKNOWN)); return }
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
            val data = confirmedOpening(auth, value, result)
            if (data == null) {
                save(value.copy(recoveryReason=SessionRecoveryReason.OPEN_UNKNOWN))
                return
            }
            value = value.copy(orderNo=requireNotNull(data.text("orderNo")), consumeDate=data.text("consumeDate").orEmpty())
        }
        value = value.copy(stage=SessionStage.SETTLING, endedAt=value.endedAt ?: System.currentTimeMillis())
        save(value)
        settleCloud(auth, value)
    }
    private suspend fun settleCloud(auth: Credentials, value: SessionUi) {
        try {
            val response = transport.post("order/consumeOrder/result/query", mapOf("snCode" to value.device.id, "orderNo" to value.orderNo), auth)
            val data = response.data as? JsonObject
            if(response.ok && data?.text("orderNo") == value.orderNo && matchesSessionIdentity(auth, value.device, data) &&
                yuanMilli(data.text("consumeMoney")) != null) { clear(value); return }
            save(value.copy(stage=SessionStage.SETTLEMENT_PENDING, recoveryReason=SessionRecoveryReason.SETTLEMENT_PENDING))
        } catch(e: Exception) { save(value.copy(stage=SessionStage.SETTLEMENT_PENDING, recoveryReason=SessionRecoveryReason.SETTLEMENT_PENDING)); throw e }
    }
    private suspend fun finishBle(auth: Credentials?, original: SessionUi, background: Boolean = false) {
        var value = original
        if(!value.rateAttempted) {
            cancelUnusedBle(auth, value)
            return
        }
        if (value.deviceUseChanged) { settleReplacedBle(auth, value, background); return }
        connect(value)
        if(value.stage !in listOf(SessionStage.SETTLING, SessionStage.SETTLEMENT_PENDING, SessionStage.WRITEBACK_PENDING)) {
            save(value.copy(stage=SessionStage.STOPPING))
            if(!ble.closeValve()) {
                // A lost acknowledgement can follow a successful close, including an already idle device.
                connect(value)
                if (ble.query()?.state == 0) { recoverIdleBle(auth, value, background); return }
                save(value.copy(stage=SessionStage.CLOSE_UNCONFIRMED, recoveryReason=SessionRecoveryReason.CLOSE_UNKNOWN))
                return
            }
            value = value.copy(stage=SessionStage.SETTLING); save(value)
            ble.disconnect(); connect(value) // Use fresh post-close encrypted consumption state.
        }
        val state = ble.query() ?: throw ble.commandError(FailureOperation.FINISH)
        if(state.state == 1) {
            if (value.uploaded || value.uploadAttempted || value.endedAt != null) {
                observeReplacedBle(auth, value, background)
            } else save(value.copy(stage=SessionStage.CLOSE_UNCONFIRMED, recoveryReason=SessionRecoveryReason.CLOSE_UNKNOWN))
            return
        }
        if(state.state !in listOf(0, 3, 6)) throw ApiFailure(null, "设备状态未知，未执行结算")
        if (state.state == 0) { recoverIdleBle(auth, value, background); return }
        value = value.copy(endedAt = value.endedAt ?: System.currentTimeMillis())
        if(auth == null) { save(value.copy(stage=SessionStage.SETTLEMENT_PENDING, recoveryReason=SessionRecoveryReason.LOGIN_REQUIRED)); return }
        try {
            if((value.uploadAttempted && !value.uploaded) || (value.uploaded && value.writeBackHex.isNullOrBlank())) {
                val result=if(value.orderNo.isNotBlank()) transport.post("order/consumeOrder/result/query",
                    mapOf("snCode" to value.device.id, "orderNo" to value.orderNo), auth) else null
                val data=result?.data as? JsonObject
                if(result?.ok == true && data?.text("orderNo") == value.orderNo && matchesSessionIdentity(auth, value.device, data) &&
                    yuanMilli(data.text("consumeMoney")) != null) {
                    val back=data.text("clData")?.let(DeviceCrypto::decryptByAes)?.substringAfter('-', "")
                        ?.takeIf { BleFrame.fromHex(it)?.size in 1..BleFrame.MAX_PAYLOAD_BYTES }
                    value=value.copy(uploaded=true, writeBackHex=back, stage=SessionStage.WRITEBACK_PENDING)
                    save(value)
                } else throw ApiFailure(null, "上次消费上传结果未知，等待服务端核实；不会重复上传")
            }
            if(!value.uploaded) {
                val payload = ble.collect()?.takeIf { it.isNotEmpty() } ?: throw ble.commandError(FailureOperation.FINISH)
                val hex = BleFrame.toHex(payload).lowercase()
                val signature = BleSignBuilder.signUpload(auth.loginCode, auth.phone, hex) ?: throw ApiFailure(null, "消费签名失败")
                value=value.copy(uploadAttempted=true); save(value)
                val response = transport.post("order/upload/bluetooth/data", mapOf("protocolType" to state.protocolType,
                    "randomNumber" to state.randomNumber.lowercase(), "xfData" to hex, "signature" to signature), auth)
                // Nonzero legacy codes are not proof of billing; retain the session for reconciliation.
                if(!response.ok && response.errorCode !in listOf(206,209)) {
                    value=value.copy(uploadAttempted=false); save(value)
                    response.requireSuccess()
                }
                response.requireSuccess()
                val data = response.data as? JsonObject ?: throw ApiFailure(null, "消费上传返回缺少写回数据")
                if (!matchesSessionIdentity(auth, value.device, data) ||
                    data.text("orderNo")?.takeIf(String::isNotBlank)?.let { it != value.orderNo } == true)
                    throw ApiFailure(null, "消费上传结果与原订单不匹配，待核实")
                val back = data.text("clData")?.let(DeviceCrypto::decryptByAes)?.substringAfter('-', "")
                    ?.takeIf { BleFrame.fromHex(it)?.size in 1..BleFrame.MAX_PAYLOAD_BYTES }
                value = value.copy(uploaded=true, writeBackHex=back, stage=SessionStage.WRITEBACK_PENDING, failureReportPending=false,
                    recoveryReason=SessionRecoveryReason.WRITEBACK_PENDING)
                save(value) // Never upload again after this point, even after a crash.
            }
            val bytes = value.writeBackHex?.let(BleFrame::fromHex)?.takeIf { it.size in 1..BleFrame.MAX_PAYLOAD_BYTES }
                ?: throw ApiFailure(null, "设备已关闭并记账，但服务端未提供有效清除数据；请稍后重试，持续失败请联系服务商")
            when (ble.query()?.state) {
                0 -> { clear(value); return }
                1 -> { observeReplacedBle(auth, value, background); return }
                3, 6 -> Unit
                else -> throw ApiFailure(null, "写回前无法确认设备已关闭，恢复信息已保留")
            }
            if (!ble.writeBack(bytes)) throw ble.commandError(FailureOperation.FINISH)
            val cleared = ble.query() ?: throw ble.commandError(FailureOperation.FINISH)
            if (cleared.state != 0) throw AppFailure(FailureOperation.FINISH, FailureStage.BLE_COMMAND, FailureReason.REJECTED)
            clear(value)
        } catch(e: Exception) {
            if(e is CancellationException) throw e
            save(value.copy(stage=if(value.uploaded) SessionStage.WRITEBACK_PENDING else SessionStage.SETTLEMENT_PENDING,
                recoveryReason=sessionRecoveryReason(e), failureReportPending=false))
            throw e
        }
    }
    private suspend fun recoverIdleBle(auth: Credentials?, original: SessionUi, background: Boolean = false) {
        val value = original.copy(stage=SessionStage.SETTLEMENT_PENDING,
            endedAt=original.endedAt ?: System.currentTimeMillis(), recoveryReason=SessionRecoveryReason.SETTLEMENT_PENDING)
        // An idle device has no consumption record to collect or clear. Never upload empty/stale data.
        if (value.uploaded) { clear(value); return }
        save(value)
        if (auth == null) { save(value.copy(recoveryReason=SessionRecoveryReason.LOGIN_REQUIRED)); return }
        if (value.orderNo.isBlank()) {
            save(value.copy(recoveryReason=SessionRecoveryReason.MISSING_ORDER))
            return
        }
        val query = suspend { transport.post("order/consumeOrder/result/query",
            mapOf("snCode" to value.device.id, "orderNo" to value.orderNo), auth) }
        val result = if (background) backgroundProbe { query() } else query()
        if (result == null || background && finishRequests.get() > 0) return
        val data = result.data as? JsonObject
        if (result.ok && data?.text("orderNo") == value.orderNo && matchesSessionIdentity(auth, value.device, data) &&
            yuanMilli(data.text("consumeMoney")) != null) clear(value)
    }
    private suspend fun cancelUnusedBle(auth: Credentials?, original: SessionUi) {
        val value = original.copy(stage=SessionStage.SETTLEMENT_PENDING, failureReportPending=true,
            recoveryReason=if(original.consumeDate.isBlank()) SessionRecoveryReason.MISSING_ORDER else SessionRecoveryReason.NOT_OPENED)
        if (value != original) save(value)
        if (auth == null || value.consumeDate.isBlank()) return
        val signature = BleSignBuilder.signFail(auth.loginCode, auth.phone, value.consumeDate)
            ?: throw ApiFailure(null, "取消费率签名失败")
        transport.post("order/upload/bluetooth/fail", mapOf("consumeDate" to value.consumeDate, "signature" to signature), auth).requireSuccess()
        withContext(NonCancellable) { clear(value) }
    }
    private suspend fun settleReplacedBle(auth: Credentials?, value: SessionUi, background: Boolean) {
        // Once a later use is observed, this local transaction can never control that device again.
        if (value.uploaded) { clear(value); return }
        if (auth == null || value.orderNo.isBlank()) return
        val query = suspend { settleCloud(auth, value) }
        if (background) backgroundProbe { query() } else query()
    }
    private suspend fun observeReplacedBle(auth: Credentials?, value: SessionUi, background: Boolean) {
        val changed = value.copy(deviceUseChanged=true, stage=SessionStage.SETTLEMENT_PENDING,
            recoveryReason=SessionRecoveryReason.DEVICE_CHANGED)
        save(changed)
        settleReplacedBle(auth, changed, background)
    }
    override suspend fun reconcile(auth: Credentials?) {
        if(finishRequests.get() > 0 || !operation.tryLock()) return
        try {
            if (finishRequests.get() > 0) return
            val value = current.value ?: return
            if(auth != null && value.ownerKey != auth.key) return
            if(value.device.channel == ControlChannel.BLE) {
                if(!value.rateAttempted || value.deviceUseChanged || value.stage in listOf(SessionStage.SETTLEMENT_PENDING, SessionStage.WRITEBACK_PENDING, SessionStage.SETTLING)) finishBle(auth, current.value ?: return, background=true)
                else if(value.stage == SessionStage.USING || value.stage == SessionStage.CLOSE_UNCONFIRMED) {
                    val state = probeBle(value) ?: return
                    if (finishRequests.get() > 0) return
                    if (state.state == 0) recoverIdleBle(auth, value, background=true)
                    else if(state.state in listOf(3,6)) {
                        val closed = value.copy(stage=SessionStage.SETTLEMENT_PENDING, endedAt=value.endedAt ?: System.currentTimeMillis())
                        save(closed)
                        finishBle(auth, closed, background=true)
                    }
                }
                else if(value.stage == SessionStage.OPENING || value.stage == SessionStage.OPEN_UNCONFIRMED) {
                    val state = probeBle(value) ?: return
                    if (finishRequests.get() > 0) return
                    if(state.state == 1) save(value.copy(stage=SessionStage.USING, recoveryReason=null))
                    else if (state.state == 0) recoverIdleBle(auth, value, background=true)
                    else if(state.state in listOf(3, 6)) save(value.copy(stage=SessionStage.SETTLEMENT_PENDING, recoveryReason=SessionRecoveryReason.SETTLEMENT_PENDING))
                }
                return
            }
            if(auth == null) return
            if(value.stage in listOf(SessionStage.SETTLING, SessionStage.SETTLEMENT_PENDING)) { settleCloud(auth, value); return }
            val running = backgroundProbe { transport.running(auth, value.device) } ?: return
            updateObservation(running != RunningOrder.Unknown)
            when(running) {
                is RunningOrder.Running -> when {
                    value.orderNo.isNotBlank() && !running.orderNo.isNullOrBlank() && value.orderNo != running.orderNo ->
                        settleEndedCloud(auth, value, background=true)
                    running.owner == true && !running.orderNo.isNullOrBlank() -> save(value.copy(orderNo=running.orderNo,
                        stage=if(value.stage in listOf(SessionStage.OPENING, SessionStage.OPEN_UNCONFIRMED)) SessionStage.USING else value.stage, recoveryReason=null))
                }
                RunningOrder.Ended -> settleEndedCloud(auth, value, background=true)
                RunningOrder.Unknown -> Unit
            }
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) { markDeviceUnavailable(); throw error }
        finally { operation.unlock() }
    }
    private suspend fun probeBle(value: SessionUi): DeviceQueryState? = backgroundProbe(localBle=true) {
        connect(value)
        val state = withTimeoutOrNull(4_000) { ble.query() }
        updateObservation(state != null && state.state in listOf(0, 1, 3, 6))
        state
    }
}
