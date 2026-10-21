package org.cqutosp.hydros

import android.net.ConnectivityManager
import androidx.test.platform.app.InstrumentationRegistry
import org.cqutosp.hydros.data.network.*
import org.cqutosp.hydros.data.storage.PreferencesHydrosStore
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import retrofit2.Response
import java.net.ConnectException

/** Explicit opt-in: authenticated device-info GET only; never creates orders or controls hardware. */
class CellularRecoveryTest {
    @Test fun realCellularQueryRecoversSimulatedWifiFailureWithoutChangingDefaultNetwork() = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString("hydrosLiveNetwork") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val access = AndroidNetworkAccess(context, BuildConfig.CAMPUS_BASE_URL)
        assumeTrue(access.wifiNetwork() != null)
        val defaultNetwork = connectivity.activeNetwork
        val store = PreferencesHydrosStore(context)
        val auth = requireNotNull(store.credentials()) { "A signed-in test device is required" }
        val mac = requireNotNull(arguments.getString("hydrosMac"))
        var attempts = 0
        val failedWifi = object : CampusApi {
            override suspend fun get(path: String, fields: Map<String, String>, headers: Map<String, String>): Response<ApiResponse<JsonElement>> {
                attempts++
                throw ConnectException("Simulated Wi-Fi failure")
            }
            override suspend fun post(path: String, fields: Map<String, String>, headers: Map<String, String>): Response<ApiResponse<JsonElement>> =
                error("No mutating requests are permitted in this test")
        }
        val events = mutableListOf<NetworkAttempt>()
        val recovery = NetworkRecovery(access, diagnose = events::add)
        val transport = HttpCampusTransport(failedWifi, recovery = recovery) { error("Authentication expired") }
        val result = transport.get("device/info/mac", mapOf("macAddress" to mac), auth)
        assertTrue("Device information must be available via cellular", result.ok)
        assertEquals(1, attempts)
        assertEquals(listOf(false, true), events.map { it.cellular })
        assertEquals(0, recovery.cellularRequests.value)
        assertEquals(defaultNetwork, connectivity.activeNetwork)
    }
}
