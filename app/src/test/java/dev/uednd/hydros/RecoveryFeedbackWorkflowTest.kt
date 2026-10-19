package dev.uednd.hydros

import dev.uednd.hydros.data.network.Credentials
import dev.uednd.hydros.data.repository.RealSessionRepository
import dev.uednd.hydros.ui.state.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class RecoveryFeedbackWorkflowTest {
    @Test fun lostAndRecoveredObservationDoesNotRewriteSession() = runTest {
        val auth = Credentials("token", "1", "2", "3", "13800138000")
        val device = DeviceUi("device", "水表", "宿舍", DeviceCategory.SHOWER, channel = ControlChannel.CLOUD)
        val store = MemoryStore()
        var unavailable = true
        val transport = ScriptTransport().apply {
            script = { path, _ ->
                if (unavailable) throw IOException("offline")
                if (path.endsWith("using")) response("""{"orderNo":"own","isOwner":true}""") else response()
            }
        }
        store.updateAccount(auth.key) { it.copy(session = SessionUi(device, auth.key, stage = SessionStage.USING, orderNo = "own")) }
        val repo = RealSessionRepository(transport, store, Mutex())
        repo.restore(auth)
        assertNull(repo.observation.value.lastConfirmedAt)
        runCatching { repo.reconcile(auth) }
        assertTrue(repo.observation.value.unavailable)
        assertNull(repo.observation.value.lastConfirmedAt)
        assertEquals(SessionStage.USING, repo.session.value!!.stage)
        unavailable = false
        repo.reconcile(auth)
        assertNotNull(repo.observation.value.lastConfirmedAt)
        assertFalse(repo.observation.value.unavailable)
        assertEquals(SessionStage.USING, repo.session.value!!.stage)
        repo.restore(auth)
        assertNull(repo.observation.value.lastConfirmedAt)
    }

    @Test fun settlementNetworkFailureKeepsClosedSessionForRetry() = runTest {
        val auth = Credentials("token", "1", "2", "3", "13800138000")
        val device = DeviceUi("device", "水表", "宿舍", DeviceCategory.SHOWER, channel = ControlChannel.CLOUD)
        val store = MemoryStore()
        val transport = ScriptTransport().apply { script = { _, _ -> throw IOException("offline") } }
        store.updateAccount(auth.key) { it.copy(session = SessionUi(device, auth.key, stage = SessionStage.SETTLEMENT_PENDING,
            orderNo = "own", endedAt = 123)) }
        val repo = RealSessionRepository(transport, store, Mutex())
        repo.restore(auth)
        repeat(2) { runCatching { repo.reconcile(auth) } }
        assertTrue(repo.session.value!!.message!!.contains("设备已关闭"))
        assertEquals(SessionStage.SETTLEMENT_PENDING, repo.session.value!!.stage)
        assertEquals(2, transport.requests.count { it.first.contains("consumeOrder") })
    }
}
