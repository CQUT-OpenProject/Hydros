package dev.uednd.hydros

import dev.uednd.hydros.data.ble.*
import dev.uednd.hydros.data.network.Credentials
import dev.uednd.hydros.data.repository.*
import dev.uednd.hydros.ui.state.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NearbyDiscoveryTest {
    private val auth = Credentials("token", "1", "2", "3", "13800138000")
    private val first = "AA:BB:CC:DD:EE:FF"
    private val second = "11:22:33:44:55:66"
    private fun device(id: String) = DeviceUi(id, "测试水表", "宿舍", DeviceCategory.SHOWER)

    @Test fun filtersBroadcastsNormalizesDeduplicationAndKeepsConnectionAddress() = runTest {
        val scanner = ScriptScanner()
        val requests = mutableListOf<Triple<String, String?, Int?>>()
        val discovery = NearbyDiscovery(scanner, lookup { mac, scanMac, rssi ->
            requests += Triple(mac, scanMac, rssi)
            device("same-device")
        }, MutableStateFlow(auth), backgroundScope)
        discovery.start()
        discovery.start()
        runCurrent()
        assertEquals(1, scanner.rounds.size)
        scanner.emit(first, name = "ordinary")
        scanner.emit("invalid")
        scanner.emit(first)
        scanner.emit("aabbccddeeff")
        scanner.emit(second, name = DEVICE_BROADCAST_PREFIX.lowercase())
        runCurrent()
        assertEquals(listOf(Triple("AABBCCDDEEFF", first, -50), Triple("112233445566", second, -50)), requests)
        assertEquals(listOf("same-device"), discovery.state.value.devices.map { it.id })
        advanceTimeBy(15_000)
        runCurrent()
        assertEquals(0, scanner.active)
        assertFalse(discovery.state.value.searching)
    }

    @Test fun scanWindowStopsRadioButWaitsForAcceptedRegistrationQuery() = runTest {
        val scanner = ScriptScanner()
        val gate = CompletableDeferred<DeviceUi>()
        val discovery = NearbyDiscovery(scanner, lookup { _, _, _ -> gate.await() }, MutableStateFlow(auth), backgroundScope)
        discovery.start()
        runCurrent()
        scanner.emit(first)
        runCurrent()
        advanceTimeBy(15_000)
        runCurrent()
        assertEquals(0, scanner.active)
        assertTrue(discovery.state.value.searching)
        gate.complete(device("registered"))
        runCurrent()
        assertEquals(listOf("registered"), discovery.state.value.devices.map { it.id })
        assertFalse(discovery.state.value.searching)
    }

    @Test fun lateWeakAdvertisementIsResolvedBeforeDefaultWindowCloses() = runTest {
        val scanner = ScriptScanner()
        val discovery = NearbyDiscovery(scanner, lookup { _, _, _ -> device("late-device") }, MutableStateFlow(auth), backgroundScope)
        discovery.start()
        runCurrent()
        advanceTimeBy(9_000)
        runCurrent()
        assertEquals(1, scanner.active)
        scanner.emit(first, rssi = -84)
        runCurrent()
        assertEquals(listOf("late-device"), discovery.state.value.devices.map { it.id })
        advanceTimeBy(6_000)
        runCurrent()
        assertEquals(0, scanner.active)
        assertFalse(discovery.state.value.searching)
    }

    @Test fun stoppedRoundCannotPublishLateResultOrFinishReplacementRound() = runTest {
        val scanner = ScriptScanner()
        val gate = CompletableDeferred<DeviceUi>()
        val discovery = NearbyDiscovery(scanner, lookup { mac, _, _ ->
            if (mac == "AABBCCDDEEFF") withContext(NonCancellable) { gate.await() } else device("new")
        }, MutableStateFlow(auth), backgroundScope)
        discovery.start()
        runCurrent()
        scanner.emit(first)
        runCurrent()
        discovery.stop()
        discovery.start()
        runCurrent()
        assertEquals(1, scanner.active)
        scanner.emit(second)
        runCurrent()
        gate.complete(device("stale"))
        runCurrent()
        assertEquals(listOf("new"), discovery.state.value.devices.map { it.id })
        assertTrue(discovery.state.value.searching)
        discovery.stop()
        runCurrent()
        assertEquals(0, scanner.active)
    }

    @Test fun accountChangeResetsResultsAndRejectsLateQueryError() = runTest {
        val scanner = ScriptScanner()
        val credentials = MutableStateFlow<Credentials?>(auth)
        val gate = CompletableDeferred<Unit>()
        val discovery = NearbyDiscovery(scanner, lookup { _, _, _ ->
            withContext(NonCancellable) { gate.await() }
            throw NearbyScanFailure("stale error")
        }, credentials, backgroundScope)
        discovery.start()
        runCurrent()
        scanner.emit(first)
        runCurrent()
        credentials.value = auth.copy(accountId = "another")
        runCurrent()
        assertEquals(0, scanner.active)
        assertEquals(NearbyDiscoveryState(), discovery.state.value)
        gate.complete(Unit)
        runCurrent()
        assertEquals(NearbyDiscoveryState(), discovery.state.value)
    }

    @Test fun scanFailureCancelsQueriesReleasesScannerAndCanRetry() = runTest {
        val scanner = ScriptScanner()
        var queryCancelled = false
        val discovery = NearbyDiscovery(scanner, lookup { _, _, _ ->
            try { awaitCancellation() } finally { queryCancelled = true }
        }, MutableStateFlow(auth), backgroundScope)
        discovery.start()
        runCurrent()
        scanner.emit(first)
        runCurrent()
        scanner.rounds.last().close(NearbyScanFailure("scan failed"))
        runCurrent()
        assertTrue(queryCancelled)
        assertEquals(0, scanner.active)
        assertEquals("scan failed", discovery.state.value.error?.message)
        assertFalse(discovery.state.value.searching)
        discovery.start()
        runCurrent()
        assertNull(discovery.state.value.error)
        assertEquals(1, scanner.active)
    }

    @Test fun oneUnregisteredDeviceDoesNotCancelOtherLookups() = runTest {
        val scanner = ScriptScanner()
        val discovery = NearbyDiscovery(scanner, lookup { mac, _, _ ->
            if (mac == "AABBCCDDEEFF") throw NearbyScanFailure("not registered")
            device("registered")
        }, MutableStateFlow(auth), backgroundScope)
        discovery.start()
        runCurrent()
        scanner.emit(first)
        scanner.emit(second)
        runCurrent()
        assertEquals(listOf("registered"), discovery.state.value.devices.map { it.id })
        assertTrue(discovery.state.value.searching)
        assertEquals("not registered", discovery.state.value.error?.message)
    }

    private fun lookup(find: suspend (String, String?, Int?) -> DeviceUi) = object : DeviceRepository {
        override suspend fun find(auth: Credentials, mac: String, scanMac: String?, rssi: Int?) = find(mac, scanMac, rssi)
        override suspend fun prepare(auth: Credentials, device: DeviceUi, preferBle: Boolean) = error("not used")
        override suspend fun release(keepConnection: () -> Boolean) = Unit
    }
}

class ScriptScanner : NearbyScanner {
    val rounds = mutableListOf<Channel<NearbyAdvertisement>>()
    var active = 0
        private set
    override fun advertisements() = flow {
        val channel = Channel<NearbyAdvertisement>(Channel.UNLIMITED)
        rounds += channel
        active++
        try { for (advertisement in channel) emit(advertisement) }
        finally { active--; channel.cancel() }
    }
    fun emit(mac: String, name: String = DEVICE_BROADCAST_PREFIX, rssi: Int = -50) {
        check(rounds.last().trySend(NearbyAdvertisement(name, mac, rssi)).isSuccess)
    }
}
