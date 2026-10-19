package dev.uednd.hydros

import dev.uednd.hydros.data.network.*
import dev.uednd.hydros.data.repository.*
import dev.uednd.hydros.ui.state.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionReliabilityTest {
    private val auth = Credentials("key", "1", "2", "3", "13800138000")
    private val device = DeviceUi("water", "水表", "宿舍", DeviceCategory.SHOWER,
        channel = ControlChannel.CLOUD, deviceId = "7", ledgerMac = "00158304D300",
        connectMac = "C0158304D300", bigTypeId = "4", smallTypeId = "1")

    @Test fun cloudFinishAlsoPreemptsReadOnlyNetworkProbe() = runTest {
        val store = MemoryStore()
        val transport = ScriptTransport()
        store.updateAccount(auth.key) { it.copy(session = SessionUi(device.copy(channel = ControlChannel.CLOUD),
            auth.key, stage = SessionStage.USING, orderNo = "own")) }
        val repo = RealSessionRepository(transport, store, Mutex())
        repo.restore(auth)
        var firstQuery = true
        val entered = CompletableDeferred<Unit>()
        transport.script = { path, _ ->
            when {
                path.endsWith("using") && firstQuery -> { firstQuery = false; entered.complete(Unit); awaitCancellation() }
                path.contains("consumeOrder") -> response("""{"orderNo":"own","consumeMoney":0}""")
                else -> response()
            }
        }
        val polling = launch { repo.reconcile(auth) }
        entered.await()
        repo.finish(auth)
        polling.join()
        assertNull(repo.session.value)
        assertEquals(0L, testScheduler.currentTime)
        assertEquals(1, transport.requests.count { it.first.endsWith("closeOrder") })
    }
}
