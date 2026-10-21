package org.cqutosp.hydros.data.repository

import org.cqutosp.hydros.data.feedback.*
import org.cqutosp.hydros.data.network.*
import org.cqutosp.hydros.data.storage.HydrosStore
import org.cqutosp.hydros.ui.state.*
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
    private val operation: Mutex, private val confirmationAttempts: Int = 10,
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
    private suspend fun <T> backgroundProbe(block: suspend () -> T): T? = coroutineScope {
        val probe = async(start = CoroutineStart.LAZY) {
            // HTTP probes use the read recovery budget: default attempt, same-network retry,
            // cellular acquisition, and one cellular request.
            withTimeoutOrNull(READ_QUERY_RECOVERY_MILLIS) { block() }
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
        store.updateAccount(value.ownerKey) { it.copy(session=null) }; current.value = null
    }
    override suspend fun start(auth: Credentials, device: DeviceUi) = operation.withLock {
        if(current.value != null || store.account(auth.key).session != null) throw ApiFailure(null, "请先处理当前会话")
        if(device.channel == ControlChannel.UNKNOWN) throw ApiFailure(null, "请先核实设备连接")
        startCloud(auth, device)
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
            val credentials = auth ?: throw ApiFailure(null, "请重新登录后关闭设备")
            finishCloud(credentials, value)
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
    override suspend fun reconcile(auth: Credentials?) {
        if(finishRequests.get() > 0 || !operation.tryLock()) return
        try {
            if (finishRequests.get() > 0) return
            val value = current.value ?: return
            if(auth == null || value.ownerKey != auth.key) return
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
}
