package dev.uednd.hydros

import dev.uednd.hydros.data.ble.*
import dev.uednd.hydros.data.network.Credentials
import dev.uednd.hydros.data.repository.RealSessionRepository
import dev.uednd.hydros.ui.state.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class RecoveryFeedbackWorkflowTest {
    @Test fun lostAndRecoveredObservationDoesNotRewriteSessionOrReplayCommands() = runTest {
        val auth = Credentials("token", "1", "2", "3", "13800138000")
        val device = DeviceUi("device", "水表", "宿舍", DeviceCategory.SHOWER, channel = ControlChannel.BLE, connectMac = "AA:BB:CC:DD:EE:FF")
        val store = MemoryStore()
        val base = ScriptBle().apply { state = state.copy(state = 1) }
        var unavailable = true
        val ble = object : BlePort by base {
            override suspend fun query(): DeviceQueryState? = if (unavailable) null else base.query()
        }
        store.updateAccount(auth.key) { it.copy(session = SessionUi(device, auth.key, stage = SessionStage.USING, rateAttempted = true)) }
        val repo = RealSessionRepository(ScriptTransport(), store, ble, Mutex())
        repo.restore(auth)
        assertNull(repo.observation.value.lastConfirmedAt)
        repo.reconcile(auth)
        assertTrue(repo.observation.value.unavailable)
        assertNull(repo.observation.value.lastConfirmedAt)
        assertEquals(SessionStage.USING, repo.session.value!!.stage)
        unavailable = false
        repo.reconcile(auth)
        val confirmed = repo.observation.value.lastConfirmedAt
        assertNotNull(confirmed); assertFalse(repo.observation.value.unavailable)
        unavailable = true; repo.reconcile(auth)
        assertTrue(repo.observation.value.unavailable)
        assertEquals(confirmed, repo.observation.value.lastConfirmedAt)
        assertEquals(0, base.rates); assertEquals(0, base.closes); assertEquals(0, base.backs)
        repo.restore(auth)
        assertNull(repo.observation.value.lastConfirmedAt)
    }
    @Test fun settlementNetworkFailureDoesNotInvalidateConfirmedCloseOrReplayUpload() = runTest {
        val auth = Credentials("token", "1", "2", "3", "13800138000")
        val device = DeviceUi("device", "水表", "宿舍", DeviceCategory.SHOWER, channel = ControlChannel.BLE, connectMac = "AA:BB:CC:DD:EE:FF")
        val store = MemoryStore()
        val ble = ScriptBle().apply { state = state.copy(state = 3) }
        val transport = ScriptTransport().apply { script = { _, _ -> throw java.io.IOException("offline") } }
        store.updateAccount(auth.key) { it.copy(session = SessionUi(device, auth.key, stage = SessionStage.SETTLEMENT_PENDING,
            rateAttempted = true, orderNo = "own", consumeDate = "20261017120000", endedAt = 123)) }
        val repo = RealSessionRepository(transport, store, ble, Mutex())
        repo.restore(auth)
        repeat(2) { runCatching { repo.reconcile(auth) } }
        assertTrue(repo.session.value!!.message!!.contains("设备已关闭"))
        assertFalse(repo.observation.value.unavailable)
        assertEquals(1, transport.requests.count { it.first == "order/upload/bluetooth/data" })
        assertEquals(0, ble.closes); assertEquals(0, ble.rates); assertEquals(0, ble.backs)
    }

}
