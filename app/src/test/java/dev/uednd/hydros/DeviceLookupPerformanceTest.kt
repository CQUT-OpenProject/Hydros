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
class DeviceLookupPerformanceTest {
    private val auth = Credentials("token", "1", "2", "3", "13800138000")
    private val mac = "C0158304D300"
    private fun info(address: String = mac, isBle: Boolean? = true) = response(
        """{"snCode":"$address","macAddress":"$address","deviceName":"水表","isBle":$isBle}""",
    )

    @Test fun simultaneousLookupsShareOneRequestAndRepeatedSearchUsesFreshSnapshot() = runTest {
        val responseGate = CompletableDeferred<Unit>()
        val transport = ScriptTransport().apply {
            script = { _, _ -> responseGate.await(); info() }
        }
        val store = MemoryStore()
        val repo = RealDeviceRepository(transport, store, Mutex(), now = { testScheduler.currentTime })
        val results = List(20) { async { repo.find(auth, "c0:15:83:04:d3:00") } }
        runCurrent()
        assertEquals(1, transport.requests.size)
        advanceTimeBy(500)
        responseGate.complete(Unit)
        results.awaitAll()
        val observed = repo.find(auth, mac, "C0:15:83:04:D3:00", -72)
        assertEquals(1, transport.requests.size)
        assertEquals(500L, testScheduler.currentTime)
        assertEquals("-72 dBm", observed.signal)
        assertEquals("C0:15:83:04:D3:00", observed.connectMac)
        assertEquals(observed, store.account(auth.key).devices.single())
    }

    @Test fun repeatedAdvertisementsDoNotExtendServerDataFreshness() = runTest {
        val transport = ScriptTransport().apply { script = { _, _ -> info() } }
        var now = 0L
        val repo = RealDeviceRepository(transport, MemoryStore(), Mutex(), now = { now })
        repo.find(auth, mac, mac, -40)
        now = 29_999
        repo.find(auth, mac, mac, -60)
        assertEquals(1, transport.requests.size)
        now = 30_001
        repo.find(auth, mac, mac, -80)
        assertEquals(2, transport.requests.size)
    }

    @Test fun cacheIsScopedToFullCredentialsIncludingRefreshedLogin() = runTest {
        val transport = ScriptTransport().apply { script = { _, _ -> info() } }
        val repo = RealDeviceRepository(transport, MemoryStore(), Mutex())
        repo.find(auth, mac)
        repo.find(auth.copy(accountId = "another"), mac)
        repo.find(auth.copy(loginCode = "refreshed"), mac)
        assertEquals(3, transport.requests.size)
    }

    @Test fun unrelatedAddressesDoNotWaitOnOneGlobalNetworkLock() = runTest {
        val responseGate = CompletableDeferred<Unit>()
        val transport = ScriptTransport().apply {
            script = { _, fields -> responseGate.await(); info(fields.getValue("macAddress")) }
        }
        val repo = RealDeviceRepository(transport, MemoryStore(), Mutex())
        val first = async { repo.find(auth, mac) }
        val second = async { repo.find(auth, "112233445566") }
        runCurrent()
        assertEquals(2, transport.requests.size)
        responseGate.complete(Unit)
        assertNotEquals(first.await().id, second.await().id)
    }

    @Test fun cancellingLeaderLetsWaiterRetryAndCancellingWaiterDoesNotCancelLeader() = runTest {
        val responseGate = CompletableDeferred<Unit>()
        var calls = 0
        var cancelled = 0
        val transport = ScriptTransport().apply {
            script = { _, _ ->
                if (++calls == 1) {
                    try { responseGate.await() } catch (error: CancellationException) { cancelled++; throw error }
                }
                info()
            }
        }
        val repo = RealDeviceRepository(transport, MemoryStore(), Mutex())
        val leader = async { repo.find(auth, mac) }
        val cancelledWaiter = async { repo.find(auth, mac) }
        val waiter = async { repo.find(auth, mac) }
        runCurrent()
        cancelledWaiter.cancelAndJoin()
        assertFalse(leader.isCancelled)
        assertEquals(1, calls)
        leader.cancelAndJoin()
        assertEquals(mac, waiter.await().id)
        assertEquals(1, cancelled)
        assertEquals(2, calls)
        repo.find(auth, mac)
        assertEquals(2, calls)
    }

    @Test fun failedLookupIsNotCachedAndCanBeRetried() = runTest {
        val transport = ScriptTransport().apply { script = { _, _ -> response(code = 4004) } }
        val repo = RealDeviceRepository(transport, MemoryStore(), Mutex())
        assertTrue(runCatching { repo.find(auth, mac) }.exceptionOrNull()?.cause is ApiFailure)
        val failedCalls = transport.requests.size
        transport.script = { _, _ -> info() }
        repo.find(auth, mac)
        assertEquals(failedCalls + 1, transport.requests.size)
    }

    @Test fun preparationSharesInFlightInfoRequest() = runTest {
        val responseGate = CompletableDeferred<Unit>()
        val transport = ScriptTransport().apply {
            script = { path, _ -> responseGate.await(); if (path.endsWith("using")) response() else info() }
        }
        val repo = RealDeviceRepository(transport, MemoryStore(), Mutex())
        val device = DeviceUi(mac, "水表", "宿舍", DeviceCategory.SHOWER, ledgerMac = mac, connectMac = mac)
        val finding = async { repo.find(auth, mac) }
        val preparing = async { repo.prepare(auth, device) }
        runCurrent()
        assertEquals(1, transport.requests.size)
        responseGate.complete(Unit)
        val found = finding.await()
        val ready = preparing.await()
        assertEquals(found.id, ready.id)
        assertEquals(ControlChannel.CLOUD, ready.channel)
        assertEquals(1, transport.requests.count { it.first == "device/info/mac" })
        assertEquals(1, transport.requests.count { it.first.endsWith("using") })
    }

    @Test fun cloudOfflineFailsPreparationWithoutRememberingBluetooth() = runTest {
        val transport = ScriptTransport().apply {
            script = { path, _ -> if (path == "device/info/mac") info(isBle = null) else response(code = 306) }
        }
        val repo = RealDeviceRepository(transport, MemoryStore(), Mutex())
        val found = repo.find(auth, mac)
        assertTrue(runCatching { repo.prepare(auth, found) }.exceptionOrNull() is CloudDeviceOffline)
        assertEquals(ControlChannel.UNKNOWN, repo.find(auth, mac).channel)
        assertEquals(1, transport.requests.count { it.first.endsWith("using") })
    }
}
