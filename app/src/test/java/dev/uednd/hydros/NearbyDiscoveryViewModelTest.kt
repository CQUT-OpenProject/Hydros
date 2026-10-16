package dev.uednd.hydros

import androidx.lifecycle.ViewModelStore
import dev.uednd.hydros.data.HydrosDependencies
import dev.uednd.hydros.data.network.Credentials
import dev.uednd.hydros.data.repository.*
import dev.uednd.hydros.ui.state.HydrosViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NearbyDiscoveryViewModelTest {
    @Test fun resolvedDevicesReachNavigationStateAndLogoutClearsDiscovery() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val viewModels = ViewModelStore()
        try {
            val credentials = Credentials("token", "1", "2", "3", "13800138000")
            val store = MemoryStore().apply { auth = credentials }
            val transport = ScriptTransport().apply {
                script = { path, _ -> response(when (path) {
                    "device/info/mac" -> """{"snCode":"registered","deviceName":"测试水表"}"""
                    "order/query/account/bill/list" -> "[]"
                    else -> "{}"
                }) }
            }
            val ble = ScriptBle()
            val lock = Mutex()
            val scanner = ScriptScanner()
            val auth = RealAuthRepository(transport, store)
            val graph = HydrosDependencies(auth, RealAccountRepository(transport), RealWalletRepository(transport),
                RealDeviceRepository(transport, store, ble, lock), RealSessionRepository(transport, store, ble, lock),
                store, backgroundScope, nearbyScanner = scanner)
            val model = HydrosViewModel(graph)
            viewModels.put("hydros", model)
            runCurrent()
            model.discoverNearby()
            runCurrent()
            scanner.emit("AA:BB:CC:DD:EE:FF")
            runCurrent()
            assertEquals("registered", model.state.value.nearby.devices.single().id)
            assertEquals("registered", model.state.value.devices.single().id)
            model.stopDiscovery()
            runCurrent()
            assertEquals(0, scanner.active)
            assertEquals("registered", model.state.value.nearby.devices.single().id)
            model.discoverNearby()
            runCurrent()
            auth.logout()
            runCurrent()
            assertEquals(0, scanner.active)
            assertFalse(model.state.value.signedIn)
            assertTrue(model.state.value.nearby.devices.isEmpty())
            assertFalse(model.state.value.nearby.hasSearched)
        } finally {
            viewModels.clear()
            runCurrent()
            Dispatchers.resetMain()
        }
    }
}
