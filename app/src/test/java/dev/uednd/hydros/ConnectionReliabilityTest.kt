package dev.uednd.hydros

import dev.uednd.hydros.data.ble.*
import dev.uednd.hydros.data.network.*
import dev.uednd.hydros.data.repository.*
import dev.uednd.hydros.ui.state.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import java.util.Base64

@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionReliabilityTest {
    private val auth = Credentials("key", "1", "2", "3", "13800138000")
    private val device = DeviceUi("water", "水表", "宿舍", DeviceCategory.SHOWER,
        channel = ControlChannel.BLE, deviceId = "7", ledgerMac = "00158304D300",
        connectMac = "C0158304D300", bigTypeId = "4", smallTypeId = "1")

    private suspend fun restored(store: MemoryStore, transport: ScriptTransport, ble: BlePort,
        stage: SessionStage = SessionStage.USING, uploaded: Boolean = false,
        attempted: Boolean = false): RealSessionRepository {
        store.updateAccount(auth.key) { it.copy(session = SessionUi(device, auth.key, stage = stage,
            orderNo = "own", consumeDate = "date", rateAttempted = true,
            uploaded = uploaded, uploadAttempted = attempted)) }
        return RealSessionRepository(transport, store, ble, Mutex()).also { it.restore(auth) }
    }

    @Test fun finishInterruptsHungBackgroundQueryAndClosesWithoutItsTimeout() = runTest {
        val base = ScriptBle()
        var firstQuery = true
        val entered = CompletableDeferred<Unit>()
        val ble = object : BlePort by base {
            override suspend fun query(): DeviceQueryState? {
                if (firstQuery) { firstQuery = false; entered.complete(Unit); awaitCancellation() }
                return base.query()
            }
        }
        val repo = restored(MemoryStore(), ScriptTransport(), ble)
        val polling = launch { repo.reconcile(auth) }
        entered.await()
        val finish = launch { repo.finish(null) }
        runCurrent()
        assertEquals(1, base.closes)
        assertTrue(finish.isCompleted)
        assertTrue(polling.isCompleted)
        assertEquals(0L, testScheduler.currentTime)
        assertEquals(SessionStage.SETTLEMENT_PENDING, repo.session.value?.stage)
    }

    @Test fun backgroundProbeHasShortDeadlineAndDropsItsConnection() = runTest {
        val base = ScriptBle()
        val ble = object : BlePort by base {
            override suspend fun query(): DeviceQueryState? = awaitCancellation()
        }
        val repo = restored(MemoryStore(), ScriptTransport(), ble)
        val polling = launch { repo.reconcile(auth) }
        runCurrent()
        advanceTimeBy(4_000); runCurrent()
        assertTrue(polling.isCompleted)
        assertFalse(base.ready)
        assertEquals(SessionStage.USING, repo.session.value?.stage)
    }

    @Test fun cancellingMonitorStillPropagatesCancellationAndReleasesProbe() = runTest {
        val base = ScriptBle()
        var firstQuery = true
        val ble = object : BlePort by base {
            override suspend fun query(): DeviceQueryState? {
                if (firstQuery) { firstQuery = false; awaitCancellation() }
                return base.query()
            }
        }
        val repo = restored(MemoryStore(), ScriptTransport(), ble)
        val polling = launch { repo.reconcile(auth) }
        runCurrent()
        polling.cancelAndJoin()
        assertTrue(polling.isCancelled)
        assertFalse(base.ready)
        repo.finish(null)
        assertEquals(1, base.closes)
    }

    @Test fun cloudFinishAlsoPreemptsReadOnlyNetworkProbe() = runTest {
        val store = MemoryStore()
        val transport = ScriptTransport()
        store.updateAccount(auth.key) { it.copy(session = SessionUi(device.copy(channel = ControlChannel.CLOUD),
            auth.key, stage = SessionStage.USING, orderNo = "own")) }
        val repo = RealSessionRepository(transport, store, ScriptBle(), Mutex())
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

    @Test fun finishDoesNotCancelAnUploadAlreadyInFlight() = runTest {
        val base = ScriptBle().apply { state = state.copy(state = 3) }
        val store = MemoryStore()
        val transport = ScriptTransport()
        val uploadEntered = CompletableDeferred<Unit>()
        val uploadResult = CompletableDeferred<Unit>()
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(DeviceCrypto.aesKey, "AES"))
        val back = Base64.getEncoder().encodeToString(cipher.doFinal("prefix-AABB".toByteArray()))
        transport.script = { path, _ ->
            assertTrue(path.contains("upload/bluetooth/data"))
            uploadEntered.complete(Unit)
            uploadResult.await()
            response("""{"clData":"$back"}""")
        }
        val repo = restored(store, transport, base, SessionStage.SETTLEMENT_PENDING)
        val recovery = launch { repo.reconcile(auth) }
        uploadEntered.await()
        val finish = launch { repo.finish(auth) }
        runCurrent()
        assertFalse(recovery.isCompleted)
        assertFalse(finish.isCompleted)
        assertTrue(base.ready)
        uploadResult.complete(Unit)
        recovery.join(); finish.join()
        assertNull(repo.session.value)
        assertEquals(1, transport.requests.size)
        assertEquals(1, base.backs)
        assertEquals(0, base.closes)
    }

    @Test fun cancellationDuringRetryBackoffDoesNotOpenAnotherConnection() = runTest {
        var attempts = 0
        val ble = object : BlePort by ScriptBle() {
            override suspend fun connect(mac: String): BleConnectResult {
                attempts++
                return BleConnectResult.Failed(dev.uednd.hydros.data.feedback.FailureReason.DISCONNECTED)
            }
        }
        val connection = launch { ble.connectWithRetry(device.connectMac) }
        runCurrent()
        connection.cancelAndJoin()
        advanceTimeBy(1_000); runCurrent()
        assertEquals(1, attempts)
    }

    @Test fun connectionRetryIsBoundedAndNeverReplaysCommands() = runTest {
        val base = ScriptBle()
        var calls = 0
        val ble = object : BlePort by base {
            override suspend fun connect(mac: String): BleConnectResult {
                calls++
                return BleConnectResult.Failed(dev.uednd.hydros.data.feedback.FailureReason.DISCONNECTED)
            }
        }
        assertTrue(ble.connectWithRetry(device.connectMac) is BleConnectResult.Failed)
        assertEquals(2, calls)
        assertEquals(350L, testScheduler.currentTime)
        assertEquals(0, base.rates + base.closes + base.backs)
    }

    @Test fun retriesShareOneTotalConnectionDeadline() = runTest {
        var attempts = 0
        var disconnected = false
        val ble = object : BlePort by ScriptBle() {
            override suspend fun connect(mac: String): BleConnectResult {
                attempts++
                if (attempts == 1) { delay(10_000); return BleConnectResult.Failed(dev.uednd.hydros.data.feedback.FailureReason.DISCONNECTED) }
                awaitCancellation()
            }
            override fun disconnect() { disconnected = true }
        }
        val result = ble.connectWithRetry(device.connectMac)
        assertTrue(result is BleConnectResult.Failed && !result.retryable)
        assertEquals(2, attempts)
        assertEquals(15_000L, testScheduler.currentTime)
        assertTrue(disconnected)
    }

    @Test fun unsupportedAndPermanentFailuresDoNotRetry() = runTest {
        for (result in listOf(BleConnectResult.NoService, BleConnectResult.Failed(dev.uednd.hydros.data.feedback.FailureReason.BLUETOOTH_OFF, retryable = false))) {
            var calls = 0
            val ble = object : BlePort by ScriptBle() {
                override suspend fun connect(mac: String): BleConnectResult { calls++; return result }
            }
            assertEquals(result, ble.connectWithRetry(device.connectMac))
            assertEquals(1, calls)
        }
        assertEquals(0L, testScheduler.currentTime)
    }

    @Test fun preparationRejectsBusyPendingAndUnknownStatesAndDisconnects() = runTest {
        for (state in listOf(1, 3, 6, 255)) {
            val base = ScriptBle().apply { this.state = this.state.copy(state = state) }
            val transport = deviceTransport()
            val repo = RealDeviceRepository(transport, MemoryStore(), base, Mutex())
            assertTrue(runCatching { repo.prepare(auth, device) }.exceptionOrNull() is ApiFailure)
            assertFalse(base.ready)
            assertEquals(0, base.rates)
        }
    }

    private fun deviceTransport() = ScriptTransport().apply {
        script = { _, _ -> response("""{"deviceId":7,"snCode":"water","macAddress":"00158304D300","realMac":"04158304D300","isBle":true,"bigTypeId":4,"smallTypeId":1}""") }
    }

    @Test fun freshDiscoveryAvoidsDuplicateInfoRequestButStillQueriesDevice() = runTest {
        val transport = deviceTransport()
        val base = ScriptBle()
        var queries = 0
        val addresses = mutableListOf<String>()
        val ble = object : BlePort by base {
            override suspend fun query(): DeviceQueryState { queries++; return base.query() }
            override suspend fun connect(mac: String): BleConnectResult { addresses += mac; return base.connect(mac) }
        }
        var now = 0L
        val repo = RealDeviceRepository(transport, MemoryStore(), ble, Mutex(), now = { now })
        val discovered = repo.find(auth, device.ledgerMac, "C0158304D300")
        repo.prepare(auth, discovered)
        assertEquals(1, transport.requests.size)
        assertEquals(listOf("C0158304D300"), addresses)
        assertEquals(1, queries)
        now = 30_001
        repo.prepare(auth, discovered)
        assertEquals(2, transport.requests.size)
        // Server realMac is already a connection address: do not rewrite its high bits.
        assertEquals("04158304D300", addresses.last())
        assertEquals(2, queries)
    }

    @Test fun missingWritebackCanBeFetchedLaterWithoutUploadingAgain() = runTest {
        val transport = ScriptTransport()
        val base = ScriptBle().apply { state = state.copy(state = 3) }
        val repo = restored(MemoryStore(), transport, base, SessionStage.WRITEBACK_PENDING,
            uploaded = true, attempted = true)
        transport.script = { _, _ -> response("""{"orderNo":"own","consumeMoney":0,"clData":null}""") }
        assertTrue(runCatching { repo.finish(auth) }.isFailure)
        assertTrue(repo.session.value!!.uploaded)
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(DeviceCrypto.aesKey, "AES"))
        val back = Base64.getEncoder().encodeToString(cipher.doFinal("prefix-AABB".toByteArray()))
        transport.script = { _, _ -> response("""{"orderNo":"own","consumeMoney":0,"clData":"$back"}""") }
        repo.finish(auth)
        assertNull(repo.session.value)
        assertEquals(1, base.backs)
        assertFalse(transport.requests.any { it.first.contains("upload/bluetooth/data") })
        assertEquals(0, base.closes)
    }

    @Test fun confirmedBillingAndIdleDeviceCanCompleteWithoutAnotherWriteback() = runTest {
        val transport = ScriptTransport().apply {
            script = { _, _ -> response("""{"orderNo":"own","consumeMoney":0,"clData":null}""") }
        }
        val base = ScriptBle()
        val repo = restored(MemoryStore(), transport, base, SessionStage.SETTLEMENT_PENDING, attempted = true)
        repo.finish(auth)
        assertNull(repo.session.value)
        assertEquals(0, base.backs)
        assertFalse(transport.requests.any { it.first.contains("upload/bluetooth/data") })
    }
}
