package dev.uednd.hydros

import androidx.lifecycle.ViewModelStore
import dev.uednd.hydros.data.HydrosDependencies
import dev.uednd.hydros.data.network.Credentials
import dev.uednd.hydros.data.repository.RealAccountRepository
import dev.uednd.hydros.data.repository.RealAuthRepository
import dev.uednd.hydros.data.repository.RealDeviceRepository
import dev.uednd.hydros.data.repository.RealSessionRepository
import dev.uednd.hydros.data.repository.RealWalletRepository
import dev.uednd.hydros.data.storage.SavedAccount
import dev.uednd.hydros.ui.state.ConnectionStage
import dev.uednd.hydros.ui.state.ControlChannel
import dev.uednd.hydros.ui.state.DeviceCategory
import dev.uednd.hydros.ui.state.DeviceUi
import dev.uednd.hydros.ui.state.HydrosViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DevicePreparationViewModelTest {
    @Test fun uiReceivesPreparedDeviceAndNavigationCancellationKeepsActiveSessionConnected() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val viewModels = ViewModelStore()
        try {
            val auth = Credentials("token", "1", "2", "3", "13800138000")
            val device = DeviceUi(
                "AABBCCDDEEFF", "热水表", "宿舍", DeviceCategory.SHOWER,
                ledgerMac = "AABBCCDDEEFF", connectMac = "AA:BB:CC:DD:EE:FF",
            )
            val store = MemoryStore().apply {
                this.auth = auth
                records[auth.key] = SavedAccount(devices = listOf(device))
            }
            val transport = ScriptTransport().apply {
                script = { path, _ ->
                    when (path) {
                        "device/info/mac" -> response("""{"snCode":"AABBCCDDEEFF","deviceName":"热水表","deviceId":"7","bigTypeId":"4","smallTypeId":"1","isBle":true}""")
                        "order/tcpDevice/query/rateOrder/using" -> response(code = 306)
                        "order/downRate/bluetooth/rateOrder" -> response("""{"downData":"0011","consumeDate":"20261014120000","orderNo":"own"}""")
                        "order/query/account/bill/list" -> response("[]")
                        else -> response("{}")
                    }
                }
            }
            val ble = ScriptBle()
            val operation = Mutex()
            val sessions = RealSessionRepository(transport, store, ble, operation)
            var watchStarts = 0
            val graph = HydrosDependencies(
                auth = RealAuthRepository(transport, store),
                account = RealAccountRepository(transport),
                wallet = RealWalletRepository(transport),
                devices = RealDeviceRepository(transport, store, ble, operation),
                sessions = sessions,
                store = store,
                scope = backgroundScope,
                ensureWatch = { watchStarts++ },
            )
            val model = HydrosViewModel(graph)
            viewModels.put("hydros", model)
            runCurrent()
            model.connect(device)
            runCurrent()
            assertEquals(ConnectionStage.READY, model.state.value.connectionStage)
            assertEquals(ControlChannel.BLE, model.state.value.devices.single().channel)
            assertEquals(listOf(device.id), model.state.value.connectedDeviceIds)

            model.start(device.copy(id = "another-device"))
            runCurrent()
            assertEquals(0, watchStarts)
            assertNull(sessions.session.value)

            model.start(device)
            runCurrent()
            model.cancelConnection()
            runCurrent()
            assertEquals(ConnectionStage.IDLE, model.state.value.connectionStage)
            assertEquals(ControlChannel.BLE, model.state.value.session?.device?.channel)
            assertTrue(ble.ready)
            assertEquals(1, ble.rates)
            assertEquals(1, watchStarts)
        } finally {
            viewModels.clear()
            runCurrent()
            Dispatchers.resetMain()
        }
    }
}
