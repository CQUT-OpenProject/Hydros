package dev.uednd.hydros

import dev.uednd.hydros.data.ble.BlePort
import dev.uednd.hydros.data.network.Credentials
import dev.uednd.hydros.data.repository.RealSessionRepository
import dev.uednd.hydros.ui.state.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

@OptIn(ExperimentalCoroutinesApi::class)
class SessionRecoveryTest {
    private val auth = Credentials("key", "1", "2", "3", "13800138000")
    private val startedAt = LocalDateTime.of(2026, 10, 16, 12, 0)
        .atZone(ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli()
    private val device = DeviceUi("water", "水表", "宿舍", DeviceCategory.SHOWER)

    private suspend fun restored(store: MemoryStore, transport: ScriptTransport, ble: BlePort,
        channel: ControlChannel, stage: SessionStage = SessionStage.USING, order: String = "own",
        uploaded: Boolean = false): RealSessionRepository {
        store.updateAccount(auth.key) { it.copy(session = SessionUi(device.copy(channel = channel), auth.key,
            startedAt = startedAt, stage = stage, orderNo = order, rateAttempted = true, uploaded = uploaded)) }
        return RealSessionRepository(transport, store, ble, Mutex(), 1, {}).also { it.restore(auth) }
    }

    @Test fun idleBleRecoversEveryUnconfirmedStageUsingBillingWithoutDeviceWrites() = runTest {
        for (stage in listOf(SessionStage.USING, SessionStage.CLOSE_UNCONFIRMED, SessionStage.OPEN_UNCONFIRMED)) {
            val store = MemoryStore()
            val transport = ScriptTransport().apply {
                script = { _, fields ->
                    assertEquals("own", fields["orderNo"])
                    response("""{"orderNo":"own","consumeMoney":0}""")
                }
            }
            val ble = ScriptBle()
            val repo = restored(store, transport, ble, ControlChannel.BLE, stage)
            repo.reconcile(auth)
            assertNull(repo.session.value)
            assertNull(store.account(auth.key).session)
            assertEquals(listOf("order/consumeOrder/result/query"), transport.requests.map { it.first })
            assertEquals(0, ble.closes + ble.rates + ble.backs)
        }
    }

    @Test fun idleBleWithUnconfirmedBillingWaitsAndResumesAcrossRestart() = runTest {
        for (data in listOf(null, """{"orderNo":"other","consumeMoney":0}""", """{"orderNo":"own"}""")) {
            val store = MemoryStore()
            val transport = ScriptTransport().apply { script = { _, _ -> response(data) } }
            var collections = 0
            val ble = object : BlePort by ScriptBle() {
                override suspend fun collect(): ByteArray { collections++; return byteArrayOf(1) }
            }
            val repo = restored(store, transport, ble, ControlChannel.BLE)
            repo.reconcile(auth)
            assertEquals(SessionStage.SETTLEMENT_PENDING, repo.session.value?.stage)
            assertNotNull(repo.session.value?.endedAt)
            val restarted = RealSessionRepository(transport, store, ble, Mutex())
            restarted.restore(auth)
            transport.script = { _, _ -> response("""{"orderNo":"own","consumeMoney":0}""") }
            restarted.reconcile(auth)
            assertNull(restarted.session.value)
            assertEquals(0, collections)
            assertFalse(transport.requests.any { it.first.contains("upload/") })
        }
    }

    @Test fun idleBleWithConfirmedBillingClearsEvenWithoutLogin() = runTest {
        val transport = ScriptTransport()
        val repo = restored(MemoryStore(), transport, ScriptBle(), ControlChannel.BLE, uploaded = true)
        repo.reconcile(null)
        assertNull(repo.session.value)
        assertTrue(transport.requests.isEmpty())
    }

    @Test fun idleBleWithoutOrderOrLoginRetainsRecoveryAndNeverUploads() = runTest {
        for (credentials in listOf(null, auth)) {
            val transport = ScriptTransport()
            val repo = restored(MemoryStore(), transport, ScriptBle(), ControlChannel.BLE, order = "")
            repo.reconcile(credentials)
            assertEquals(SessionStage.SETTLEMENT_PENDING, repo.session.value?.stage)
            assertNotNull(repo.session.value?.endedAt)
            assertFalse(repo.session.value?.message.isNullOrBlank())
            assertTrue(transport.requests.isEmpty())
        }
    }

    @Test fun activeBleAndOtherAccountNeverTriggerSettlement() = runTest {
        val transport = ScriptTransport()
        val ble = ScriptBle().apply { state = state.copy(state = 1) }
        val repo = restored(MemoryStore(), transport, ble, ControlChannel.BLE)
        repo.reconcile(auth)
        assertEquals(SessionStage.USING, repo.session.value?.stage)
        ble.state = ble.state.copy(state = 0)
        repo.reconcile(auth.copy(userId = "another"))
        assertEquals(SessionStage.USING, repo.session.value?.stage)
        assertTrue(transport.requests.isEmpty())
        assertEquals(0, ble.closes + ble.backs + ble.rates)
    }

    @Test fun idleBleBillingProbeCanBeInterruptedByFinishEvenDuringRecovery() = runTest {
        for (stage in listOf(SessionStage.USING, SessionStage.SETTLEMENT_PENDING)) {
            val entered = CompletableDeferred<Unit>()
            var first = true
            val transport = ScriptTransport().apply {
                script = { _, _ ->
                    if (first) { first = false; entered.complete(Unit); awaitCancellation() }
                    response("""{"orderNo":"own","consumeMoney":0}""")
                }
            }
            val ble = ScriptBle()
            val repo = restored(MemoryStore(), transport, ble, ControlChannel.BLE, stage)
            val polling = launch { repo.reconcile(auth) }
            entered.await()
            repo.finish(auth)
            polling.join()
            assertNull(repo.session.value)
            assertEquals(0, ble.closes)
            assertEquals(0L, testScheduler.currentTime)
        }
    }

    @Test fun lostCloseAckWithIdleDeviceCanFinishWithoutAnotherUpload() = runTest {
        val transport = ScriptTransport().apply {
            script = { _, _ -> response("""{"orderNo":"own","consumeMoney":0}""") }
        }
        val ble = ScriptBle().apply { acknowledgeClose = false }
        val repo = restored(MemoryStore(), transport, ble, ControlChannel.BLE)
        repo.finish(auth)
        assertNull(repo.session.value)
        assertEquals(1, ble.closes)
        assertFalse(transport.requests.any { it.first.contains("upload/") })
    }

    @Test fun cloudMissingOrderRecoversOpeningResultAndSettlesWithoutClosingAgain() = runTest {
        for (manual in listOf(false, true)) {
            val store = MemoryStore()
            val transport = ScriptTransport().apply {
                script = { path, _ -> when {
                    path.endsWith("downRateResult") -> response("""{"state":1,"result":0,"orderNo":"own","consumeDate":"20261016120000"}""")
                    path.contains("consumeOrder") -> response("""{"orderNo":"own","consumeMoney":0}""")
                    else -> response()
                } }
            }
            val repo = restored(store, transport, ScriptBle(), ControlChannel.CLOUD, SessionStage.OPEN_UNCONFIRMED, order = "")
            if (manual) repo.finish(auth) else repo.reconcile(auth)
            assertNull(repo.session.value)
            assertNull(store.account(auth.key).session)
            assertEquals(listOf("order/tcpDevice/query/rateOrder/using", "order/tcpDevice/query/downRateResult",
                "order/consumeOrder/result/query"), transport.requests.map { it.first })
        }
    }

    @Test fun cloudRecoveryRejectsOldMalformedOrUnconfirmedOpeningResults() = runTest {
        for (data in listOf("{}",
            """{"state":1,"result":0,"orderNo":"old","consumeDate":"20261015120000"}""",
            """{"state":1,"result":0,"orderNo":"old"}""",
            """{"state":1,"result":0,"orderNo":"old","consumeDate":"invalid"}""",
            """{"state":1,"result":0,"orderNo":"other","consumeDate":"20261016120000","isOwner":false}""",
            """{"state":1,"result":0,"orderNo":"other","consumeDate":"20261016120000","snCode":"other-device"}""",
            """{"state":0,"result":0,"orderNo":"own","consumeDate":"20261016120000"}""")) {
            val transport = ScriptTransport().apply {
                script = { path, _ -> if (path.endsWith("downRateResult")) response(data) else response() }
            }
            val repo = restored(MemoryStore(), transport, ScriptBle(), ControlChannel.CLOUD,
                SessionStage.OPEN_UNCONFIRMED, order = "")
            repo.reconcile(auth)
            assertNotNull(repo.session.value)
            assertEquals("", repo.session.value?.orderNo)
            assertFalse(repo.session.value?.message.isNullOrBlank())
            assertFalse(transport.requests.any { it.first.contains("consumeOrder") || it.first.endsWith("closeOrder") })
        }
    }

    @Test fun cloudAcceptedOrderIsDurableWhenOpeningConfirmationIsLost() = runTest {
        val store = MemoryStore()
        val transport = ScriptTransport().apply {
            script = { path, _ -> when {
                path.endsWith("downRate/rateOrder") -> response("""{"orderNo":"own","consumeDate":"20261016120000"}""")
                path.endsWith("downRateResult") -> response(code = 1)
                else -> response()
            } }
        }
        val repo = RealSessionRepository(transport, store, ScriptBle(), Mutex(), 1, {})
        repo.start(auth, device.copy(channel = ControlChannel.CLOUD))
        assertEquals(SessionStage.OPEN_UNCONFIRMED, repo.session.value?.stage)
        assertEquals("own", store.account(auth.key).session?.orderNo)
        val restarted = RealSessionRepository(transport, store, ScriptBle(), Mutex())
        restarted.restore(auth)
        transport.script = { path, _ -> if (path.contains("consumeOrder"))
            response("""{"orderNo":"own","consumeMoney":0}""") else response() }
        restarted.reconcile(auth)
        assertNull(restarted.session.value)
    }

    @Test fun cloudClosedSettlementRetryPreservesIdentityAndReleasesOnlyAfterConfirmation() = runTest {
        val store = MemoryStore()
        val transport = ScriptTransport().apply {
            script = { path, _ -> if (path.contains("consumeOrder")) response(code = 1) else response() }
        }
        val repo = restored(store, transport, ScriptBle(), ControlChannel.CLOUD)
        repo.finish(auth)
        assertEquals(SessionStage.SETTLEMENT_PENDING, repo.session.value?.stage)
        assertNotNull(repo.session.value?.endedAt)
        val restarted = RealSessionRepository(transport, store, ScriptBle(), Mutex())
        restarted.restore(auth)
        transport.script = { _, fields ->
            assertEquals("own", fields["orderNo"])
            response("""{"orderNo":"own","consumeMoney":0}""")
        }
        restarted.reconcile(auth)
        assertNull(restarted.session.value)
    }

    @Test fun cloudAnotherRunningOrderDoesNotPreventSettlingTheOldOrder() = runTest {
        val transport = ScriptTransport().apply {
            script = { path, fields -> if (path.endsWith("using")) response("""{"orderNo":"another","isOwner":false}""")
                else {
                    assertEquals("own", fields["orderNo"])
                    response("""{"orderNo":"own","consumeMoney":0}""")
                }
            }
        }
        val repo = restored(MemoryStore(), transport, ScriptBle(), ControlChannel.CLOUD)
        repo.reconcile(auth)
        assertNull(repo.session.value)
        assertFalse(transport.requests.any { it.first.endsWith("closeOrder") })
    }
}
