package dev.uednd.hydros

import androidx.lifecycle.ViewModelStore
import dev.uednd.hydros.data.HydrosDependencies
import dev.uednd.hydros.data.network.*
import dev.uednd.hydros.data.repository.*
import dev.uednd.hydros.data.storage.*
import dev.uednd.hydros.ui.state.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class FeedbackViewModelTest {
    @Test fun walletKeepsConfirmedBalanceAndLocalSaveNeverSuggestsNetwork() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val models = ViewModelStore()
        try {
            val credentials = Credentials("token", "1", "2", "3", "13800138000")
            val device = DeviceUi("id", "水表", "宿舍", DeviceCategory.SHOWER)
            val saved = MemoryStore().apply {
                auth = credentials
                records[credentials.key] = SavedAccount(devices = listOf(device), recent = listOf(device.id), favorites = setOf(device.id))
            }
            var failSave = false
            val store = object : HydrosStore by saved {
                override suspend fun updateAccount(key: String, transform: (SavedAccount) -> SavedAccount): SavedAccount {
                    if (failSave) throw IOException("local disk error")
                    return saved.updateAccount(key, transform)
                }
            }
            var failWallet = false
            val transport = ScriptTransport().apply { script = { path, _ -> when {
                path == "account/wallet" -> { if (failWallet) throw IOException("network error"); response("""{"money":"12.34"}""") }
                path.endsWith("bill/list") -> response("[]")
                else -> response("{}")
            } } }
            val auth = RealAuthRepository(transport, store)
            val ble = ScriptBle(); val lock = Mutex()
            val model = HydrosViewModel(HydrosDependencies(auth, RealAccountRepository(transport), RealWalletRepository(transport),
                RealDeviceRepository(transport, store, ble, lock), RealSessionRepository(transport, store, ble, lock), store, backgroundScope))
            models.put("hydros", model); runCurrent()
            assertEquals(12_340L, model.state.value.wallet.totalMilli)
            failWallet = true; model.refresh(); runCurrent()
            assertEquals(12_340L, model.state.value.wallet.totalMilli)
            assertNotNull(model.state.value.walletError); assertFalse(model.state.value.walletLoading)
            failWallet = false; model.refresh(); runCurrent()
            assertNull(model.state.value.walletError)
            failSave = true; model.toggleFavorite(device.id); runCurrent()
            assertTrue(model.state.value.message!!.contains("保存收藏"))
            assertFalse(model.state.value.message!!.contains("网络"))
            assertFalse(model.saveDeviceRemark(device.id, "备注"))
            assertTrue(model.state.value.message!!.contains("保存备注"))
            assertFalse(model.state.value.message!!.contains("网络"))
        } finally { models.clear(); runCurrent(); Dispatchers.resetMain() }
    }
}
