package dev.uednd.hydros

import androidx.lifecycle.ViewModelStore
import dev.uednd.hydros.data.HydrosDependencies
import dev.uednd.hydros.data.network.Credentials
import dev.uednd.hydros.data.repository.*
import dev.uednd.hydros.data.storage.SavedAccount
import dev.uednd.hydros.ui.state.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DeviceRemarkTest {
    @Test fun remarksSurviveRefreshAndRestartRemainAccountScopedAndCanBeRemoved() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val models = ViewModelStore()
        try {
            val auth = Credentials("token", "1", "2", "3", "13800138000")
            val other = auth.copy(accountId = "another")
            val device = DeviceUi("AABBCCDDEEFF", "原始水表", "宿舍", DeviceCategory.SHOWER, ledgerMac = "AABBCCDDEEFF")
            val store = MemoryStore().apply {
                this.auth = auth
                records[auth.key] = SavedAccount(devices = listOf(device), recent = listOf(device.id))
                records[other.key] = SavedAccount(devices = listOf(device), recent = listOf(device.id), favorites = setOf(device.id), deviceRemarks = mapOf(device.id to "另一个账号"))
            }
            val remote = ScriptTransport().apply { script = { path, _ ->
                when (path) {
                    "device/info/mac" -> response("""{"snCode":"AABBCCDDEEFF","deviceName":"服务端新名称","macAddress":"AABBCCDDEEFF","onlineStatusId":1}""")
                    "order/query/account/bill/list" -> response("[]")
                    else -> response("{}")
                }
            } }
            val ble = ScriptBle()
            val lock = Mutex()
            val graph = HydrosDependencies(RealAuthRepository(remote, store), RealAccountRepository(remote), RealWalletRepository(remote),
                RealDeviceRepository(remote, store, ble, lock), RealSessionRepository(remote, store, ble, lock), store, backgroundScope)
            var model = HydrosViewModel(graph)
            models.put("model", model)
            runCurrent()
            assertFalse(model.saveDeviceRemark(device.id, "未收藏不能保存"))
            assertTrue(store.account(auth.key).deviceRemarks.isEmpty())
            model.toggleFavorite(device.id)
            runCurrent()
            assertTrue(model.saveDeviceRemark(device.id, "  宿舍花洒  "))
            // A stale UI snapshot must not bypass the persisted favorite check.
            store.updateAccount(auth.key) { it.copy(favorites = emptySet(), deviceRemarks = emptyMap()) }
            assertFalse(model.saveDeviceRemark(device.id, "绕过界面"))
            model.toggleFavorite(device.id)
            runCurrent()
            assertTrue(model.saveDeviceRemark(device.id, "宿舍花洒"))
            assertEquals("宿舍花洒", model.state.value.devices.single().displayName(model.state.value.deviceRemarks))
            assertFalse(model.saveDeviceRemark(device.id, "长".repeat(17)))
            assertEquals("宿舍花洒", store.account(auth.key).deviceRemarks[device.id])
            model.scan(device.ledgerMac)
            model.connect(model.state.value.devices.single())
            runCurrent()
            assertEquals("服务端新名称", model.state.value.devices.single().name)
            assertEquals("宿舍花洒", model.state.value.devices.single().displayName(model.state.value.deviceRemarks))
            assertEquals("宿舍花洒", store.account(auth.key).deviceRemarks[device.id])
            models.clear()
            runCurrent()
            model = HydrosViewModel(graph)
            models.put("model", model)
            runCurrent()
            assertEquals("宿舍花洒", model.state.value.deviceRemarks[device.id])
            store.auth = other
            graph.auth.restore()
            runCurrent()
            assertEquals("另一个账号", model.state.value.deviceRemarks[device.id])
            assertTrue(model.saveDeviceRemark(device.id, "   "))
            assertTrue(store.account(other.key).deviceRemarks.isEmpty())
            assertEquals("宿舍花洒", store.account(auth.key).deviceRemarks[device.id])
            assertEquals(device.name, device.displayName(model.state.value.deviceRemarks))
            store.auth = auth
            graph.auth.restore()
            runCurrent()
            model.toggleFavorite(device.id)
            runCurrent()
            assertFalse(device.id in store.account(auth.key).favorites)
            assertFalse(device.id in store.account(auth.key).deviceRemarks)
            assertFalse(device.id in model.state.value.favoriteDeviceIds)
            assertFalse(device.id in model.state.value.deviceRemarks)
            model.toggleFavorite(device.id)
            runCurrent()
            assertTrue(device.id in model.state.value.favoriteDeviceIds)
            assertTrue(model.state.value.deviceRemarks.isEmpty())
        } finally {
            models.clear()
            runCurrent()
            Dispatchers.resetMain()
        }
    }

    @Test fun savedRemarksRoundTripWithoutReplacingNames() {
        val device = DeviceUi("id", "服务端名称", "位置", DeviceCategory.SHOWER)
        val saved = SavedAccount(devices = listOf(device), recent = listOf(device.id),
            favorites = setOf(device.id), deviceRemarks = mapOf(device.id to "备注"))
        val restored = Json.decodeFromString<SavedAccount>(Json.encodeToString(saved))
        assertEquals("服务端名称", restored.devices.single().name)
        assertEquals("备注", restored.devices.single().displayName(restored.deviceRemarks))
    }
}
