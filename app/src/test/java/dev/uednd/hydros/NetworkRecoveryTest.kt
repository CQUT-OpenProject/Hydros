package dev.uednd.hydros

import dev.uednd.hydros.data.network.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.JsonElement
import org.junit.Assert.*
import org.junit.Test
import retrofit2.Response
import java.net.ConnectException
import javax.net.ssl.SSLHandshakeException
import okhttp3.ResponseBody.Companion.toResponseBody

@OptIn(ExperimentalCoroutinesApi::class)
class NetworkRecoveryTest {
    private class Api : CampusApi {
        var calls = 0
        var script: suspend () -> Response<ApiResponse<JsonElement>> = { Response.success(response()) }
        override suspend fun get(path: String, fields: Map<String, String>, headers: Map<String, String>) = run()
        override suspend fun post(path: String, fields: Map<String, String>, headers: Map<String, String>) = run()
        private suspend fun run(): Response<ApiResponse<JsonElement>> { calls++; return script() }
    }
    private class Access(val api: Api = Api()) : NetworkAccess {
        var wifi: Long? = 1L
        var acquisitions = 0
        var releases = 0
        var acquire: suspend () -> Unit = {}
        override fun wifiNetwork() = wifi
        override suspend fun cellular(): CellularLease {
            acquisitions++
            acquire()
            return object : CellularLease {
                override val api = this@Access.api
                override fun close() { releases++ }
            }
        }
    }
    private fun transport(api: Api, recovery: NetworkRecovery) = HttpCampusTransport(api, recovery = recovery) { fail("expiry") }

    @Test fun wifiFailureRetriesReadOnCellularThenRoutesCommandBeforeSending() = runTest {
        val default = Api().apply { script = { throw ConnectException() } }
        val access = Access()
        val events = mutableListOf<NetworkAttempt>()
        val recovery = NetworkRecovery(access, now = { testScheduler.currentTime }, diagnose = events::add)
        val transport = transport(default, recovery)
        assertTrue(transport.get("device/info/mac").ok)
        assertTrue(transport.post("order/downRate/bluetooth/rateOrder").ok)
        assertEquals(1, default.calls)
        assertEquals(2, access.api.calls)
        assertEquals(2, access.releases)
        assertEquals(listOf(false, true, true), events.map { it.cellular })
        access.api.script = { throw ConnectException() }
        assertTrue(runCatching { transport.post("order/tcpDevice/downRate/rateOrder") }.exceptionOrNull()?.cause is NetworkFailure)
        assertEquals(1, default.calls) // Failed mutation cannot be replayed on another route.
        assertEquals(3, access.api.calls)
        assertEquals(3, access.releases)
        assertEquals(0, recovery.cellularRequests.value)
    }

    @Test fun mutationsSmsTlsAndBusinessFailuresNeverTriggerFallback() = runTest {
        for (post in listOf(false, true)) {
            val default = Api().apply { script = { throw ConnectException() } }
            val access = Access()
            val transport = transport(default, NetworkRecovery(access))
            val paths = if (post) listOf("order/tcpDevice/downRate/rateOrder", "order/downRate/bluetooth/rateOrder",
                "order/upload/bluetooth/data", "order/tcpDevice/closeOrder") else listOf("user/verification/code/get")
            for (path in paths) assertTrue(runCatching { if (post) transport.post(path) else transport.get(path) }.isFailure)
            assertEquals(0, access.acquisitions)
        }
        val default = Api().apply { script = { throw SSLHandshakeException("certificate") } }
        val access = Access()
        val transport = transport(default, NetworkRecovery(access))
        assertTrue(runCatching { transport.get("device/info/mac") }.exceptionOrNull()?.cause is SSLHandshakeException)
        default.script = { Response.success(response(code = 306)) }
        assertEquals(306, transport.get("device/info/mac").errorCode)
        default.script = { Response.error(503, "".toResponseBody()) }
        assertEquals(503, (runCatching { transport.get("device/info/mac") }.exceptionOrNull()?.cause as ApiFailure).code)
        assertEquals(0, access.acquisitions)
    }

    @Test fun readOnlyPostHasDeadlineAndCancellationReleasesCellular() = runTest {
        val default = Api().apply { script = { awaitCancellation() } }
        val access = Access()
        val recovery = NetworkRecovery(access, now = { testScheduler.currentTime })
        val transport = transport(default, recovery)
        assertTrue(transport.post("order/tcpDevice/query/rateOrder/using").ok)
        assertEquals(5_000L, testScheduler.currentTime)
        access.api.script = { awaitCancellation() }
        val request = launch { transport.get("device/info/mac") }
        runCurrent()
        assertEquals(1, recovery.cellularRequests.value)
        request.cancelAndJoin()
        assertEquals(2, access.releases)
        assertEquals(0, recovery.cellularRequests.value)
    }

    @Test fun defaultCellularAndChangedWifiDoNotReuseOldPreference() = runTest {
        val default = Api().apply { script = { throw ConnectException() } }
        val access = Access().apply { wifi = null }
        val transport = transport(default, NetworkRecovery(access, now = { testScheduler.currentTime }))
        assertTrue(runCatching { transport.get("device/info/mac") }.isFailure)
        assertEquals(0, access.acquisitions)
        access.wifi = 1L
        transport.get("device/info/mac")
        access.wifi = 2L
        default.script = { Response.success(response()) }
        transport.get("device/info/mac")
        assertEquals(1, access.acquisitions)
        advanceTimeBy(60_001)
        transport.get("device/info/mac")
        assertEquals(1, access.acquisitions)
    }

    @Test fun accountChangeWhileAcquiringCellularPreventsRequest() = runTest {
        val default = Api().apply { script = { throw ConnectException() } }
        val access = Access()
        var current = true
        access.acquire = { current = false }
        val transport = HttpCampusTransport(default, isCurrent = { current }, recovery = NetworkRecovery(access)) {}
        val auth = Credentials("token", "1", "2", "3", "13800138000")
        assertTrue((runCatching { transport.get("device/info/mac", auth = auth) }.exceptionOrNull() as ApiFailure).expired)
        assertEquals(0, access.api.calls)
        assertEquals(1, access.releases)
    }

    @Test fun unavailableCellularAndCellularDeadlineDoNotLoop() = runTest {
        val default = Api().apply { script = { throw ConnectException() } }
        val access = Access().apply { acquire = { throw NetworkFailure("移动网络不可用") } }
        val recovery = NetworkRecovery(access, now = { testScheduler.currentTime })
        val transport = transport(default, recovery)
        assertTrue(runCatching { transport.get("device/info/mac") }.exceptionOrNull()?.cause is NetworkFailure)
        assertEquals(0, recovery.cellularRequests.value)
        access.acquire = {}
        access.api.script = { awaitCancellation() }
        assertTrue(runCatching { transport.get("device/info/mac") }.exceptionOrNull()?.cause is NetworkFailure)
        assertEquals(5_000L, testScheduler.currentTime)
        assertEquals(2, default.calls)
        assertEquals(1, access.api.calls)
        assertEquals(1, access.releases)
        assertEquals(0, recovery.cellularRequests.value)
    }
}
