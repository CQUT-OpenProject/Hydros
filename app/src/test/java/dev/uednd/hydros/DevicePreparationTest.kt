package dev.uednd.hydros

import dev.uednd.hydros.data.network.CloudDeviceOffline
import dev.uednd.hydros.data.network.Credentials
import dev.uednd.hydros.data.repository.DevicePreparation
import dev.uednd.hydros.data.repository.RealAuthRepository
import dev.uednd.hydros.data.repository.RealDeviceRepository
import dev.uednd.hydros.data.repository.RealSessionRepository
import dev.uednd.hydros.ui.state.ConnectionStage
import dev.uednd.hydros.ui.state.ControlChannel
import dev.uednd.hydros.ui.state.DeviceCategory
import dev.uednd.hydros.ui.state.DeviceUi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class DevicePreparationTest {
    private val firstMac = "AA:BB:CC:DD:EE:FF"
    private val secondMac = "AA:BB:CC:DD:EE:01"
    private val thirdMac = "AA:BB:CC:DD:EE:02"

    @Test fun lostCloudCommandResponseRetainsSessionAndNeverTriesBle() = runTest {
        val fixture = fixture(backgroundScope)
        fixture.transport.script = { path, _ -> when (path) {
            "device/info/mac" -> response("""{"snCode":"water","deviceName":"热水表","macAddress":"AABBCCDDEE01"}""")
            "order/tcpDevice/downRate/rateOrder" -> throw IOException("lost response")
            else -> response()
        } }
        fixture.preparation.prepare(fixture.device(firstMac))
        runCurrent()
        assertTrue(runCatching { fixture.preparation.startSession("water") }.isFailure)
        assertEquals(dev.uednd.hydros.ui.state.SessionStage.OPEN_UNCONFIRMED, fixture.sessions.session.value?.stage)
        assertEquals(ControlChannel.CLOUD, fixture.sessions.session.value?.device?.channel)
        assertTrue(runCatching { fixture.preparation.startSession("water") }.isFailure)
        assertEquals(1, fixture.transport.requests.count { it.first == "order/tcpDevice/downRate/rateOrder" })
    }

    @Test fun offlineReplyDuringConfirmationCannotStartAnotherChannel() = runTest {
        val fixture = fixture(backgroundScope)
        fixture.transport.script = { path, _ -> when (path) {
            "device/info/mac" -> response("""{"snCode":"water","deviceName":"热水表","macAddress":"AABBCCDDEE01"}""")
            "order/tcpDevice/query/downRateResult" -> response(code = 306)
            else -> response()
        } }
        fixture.preparation.prepare(fixture.device(firstMac))
        runCurrent()
        fixture.preparation.startSession("water")
        assertEquals(dev.uednd.hydros.ui.state.SessionStage.OPEN_UNCONFIRMED, fixture.sessions.session.value?.stage)
        assertEquals(ControlChannel.CLOUD, fixture.sessions.session.value?.device?.channel)
        assertEquals(1, fixture.transport.requests.count { it.first == "order/tcpDevice/downRate/rateOrder" })
    }

    @Test fun cancellationReleasesReadyConnectionAndClearsState() = runTest {
        val fixture = fixture(backgroundScope)
        fixture.preparation.prepare(fixture.device(firstMac))
        runCurrent()
        assertEquals(ConnectionStage.READY, fixture.preparation.state.value.stage)
        assertEquals(ControlChannel.CLOUD, fixture.preparation.state.value.device?.channel)

        fixture.preparation.cancel()
        runCurrent()
        assertEquals(ConnectionStage.IDLE, fixture.preparation.state.value.stage)
        assertNull(fixture.preparation.state.value.device)
    }

    @Test fun preparingReadyDeviceAgainKeepsTheExistingConnection() = runTest {
        val fixture = fixture(backgroundScope)
        val device = fixture.device(firstMac)
        fixture.preparation.prepare(device)
        runCurrent()
        fixture.preparation.prepare(device)
        runCurrent()

        assertEquals(ConnectionStage.READY, fixture.preparation.state.value.stage)
        assertEquals(1, fixture.transport.requests.count { it.first == "order/tcpDevice/query/rateOrder/using" })
        assertTrue(runCatching { fixture.preparation.startSession("another-device") }.isFailure)
        assertNull(fixture.sessions.session.value)
    }

    @Test fun cancelledReplacementsWaitForOldCleanupAndCannotDisconnectLatestDevice() = runTest {
        val fixture = fixture(backgroundScope)
        val responseGate = CompletableDeferred<Unit>()
        val normal = fixture.transport.script
        fixture.transport.script = { path, fields ->
            if (path == "device/info/mac" && fields["macAddress"] == firstMac.replace(":", "")) {
                // A late response represents work that has already left the cancellation scope.
                withContext(NonCancellable) { responseGate.await() }
            }
            normal(path, fields)
        }

        fixture.preparation.prepare(fixture.device(firstMac))
        runCurrent()
        fixture.preparation.prepare(fixture.device(secondMac))
        fixture.preparation.prepare(fixture.device(thirdMac))
        runCurrent()
        assertEquals(ConnectionStage.CONNECTING, fixture.preparation.state.value.stage)

        responseGate.complete(Unit)
        runCurrent()
        assertEquals(ConnectionStage.READY, fixture.preparation.state.value.stage)
        assertEquals(thirdMac, fixture.preparation.state.value.device?.connectMac)
        assertEquals(ControlChannel.CLOUD, fixture.preparation.state.value.device?.channel)
    }

    @Test fun accountChangeInvalidatesPreparationAndReleasesConnection() = runTest {
        val fixture = fixture(backgroundScope)
        fixture.preparation.prepare(fixture.device(firstMac))
        runCurrent()
        fixture.auth.logout()
        runCurrent()

        assertEquals(ConnectionStage.IDLE, fixture.preparation.state.value.stage)
        assertTrue(runCatching { fixture.preparation.startSession(fixture.device(firstMac).id) }.isFailure)
        assertNull(fixture.sessions.session.value)
    }

    @Test fun failedPreparationCanBeRetriedWithoutKeepingItsConnection() = runTest {
        val fixture = fixture(backgroundScope)
        val normal = fixture.transport.script
        fixture.transport.script = { _, _ -> throw IOException("offline") }
        fixture.preparation.prepare(fixture.device(firstMac))
        runCurrent()
        assertEquals(ConnectionStage.FAILED, fixture.preparation.state.value.stage)
        assertTrue(fixture.preparation.state.value.error is dev.uednd.hydros.data.feedback.AppFailure)
        assertTrue(fixture.preparation.state.value.error?.cause is IOException)

        fixture.transport.script = normal
        fixture.preparation.prepare(fixture.device(firstMac))
        runCurrent()
        assertEquals(ConnectionStage.READY, fixture.preparation.state.value.stage)
        assertNull(fixture.preparation.state.value.error)
    }

    @Test fun startUsesPreparedDeviceAndHandsConnectionToSession() = runTest {
        val fixture = fixture(backgroundScope)
        fixture.preparation.prepare(fixture.device(firstMac))
        runCurrent()
        fixture.preparation.startSession(fixture.device(firstMac).id)
        runCurrent()

        assertEquals(ControlChannel.CLOUD, fixture.sessions.session.value?.device?.channel)
        assertEquals(ConnectionStage.IDLE, fixture.preparation.state.value.stage)
    }

    @Test fun cancellationDuringStartWaitsForOwnershipDecisionBeforeReleasing() = runTest {
        val fixture = fixture(backgroundScope)
        fixture.preparation.prepare(fixture.device(firstMac))
        runCurrent()
        val openingGate = CompletableDeferred<Unit>()
        val normal = fixture.transport.script
        fixture.transport.script = { path, fields ->
            if (path == "order/tcpDevice/downRate/rateOrder") openingGate.await()
            normal(path, fields)
        }
        val start = backgroundScope.launch { fixture.preparation.startSession(fixture.device(firstMac).id) }
        runCurrent()

        fixture.preparation.cancel()
        runCurrent()
        openingGate.complete(Unit)
        start.join()
        runCurrent()
        assertNotNull(fixture.sessions.session.value)
        assertEquals(ControlChannel.CLOUD, fixture.sessions.session.value?.device?.channel)
    }

    @Test fun cloudOfflineFailsPreparationWithoutSwitchingChannel() = runTest {
        val fixture = fixture(backgroundScope)
        fixture.transport.script = { path, fields ->
            if (path == "device/info/mac") {
                val mac = requireNotNull(fields["macAddress"])
                response("""{"snCode":"$mac","deviceName":"热水表","macAddress":"$mac"}""")
            } else response(code = 306)
        }
        fixture.preparation.prepare(fixture.device(firstMac))
        runCurrent()
        assertEquals(ConnectionStage.FAILED, fixture.preparation.state.value.stage)
        assertTrue(fixture.preparation.state.value.error is CloudDeviceOffline)
        assertNull(fixture.sessions.session.value)
        assertEquals(ControlChannel.UNKNOWN, fixture.store.account("3:2:1").devices.single().channel)
    }

    private suspend fun fixture(scope: CoroutineScope) = Fixture(scope).also { it.auth.restore() }

    private class Fixture(scope: CoroutineScope) {
        private val credentials = Credentials("token", "1", "2", "3", "13800138000")
        val store = MemoryStore().apply { auth = credentials }
        val transport = ScriptTransport().apply {
            script = { path, fields ->
                when (path) {
                    "device/info/mac" -> {
                        val mac = requireNotNull(fields["macAddress"])
                        response("""{"snCode":"$mac","deviceName":"热水表","deviceId":"7","macAddress":"$mac","bigTypeId":"4","smallTypeId":"1","isBle":true}""")
                    }
                    else -> response()
                }
            }
        }
        val auth = RealAuthRepository(transport, store)
        private val operation = Mutex()
        val sessions = RealSessionRepository(transport, store, operation)
        private val devices = RealDeviceRepository(transport, store, operation)
        val preparation = DevicePreparation(auth, devices, sessions, store, scope)

        fun device(mac: String) = DeviceUi(
            mac.replace(":", ""), "热水表", "宿舍", DeviceCategory.SHOWER,
            channel = ControlChannel.UNKNOWN, ledgerMac = mac, connectMac = mac,
        )
    }

}
