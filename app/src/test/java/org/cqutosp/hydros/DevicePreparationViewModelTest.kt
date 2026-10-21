package org.cqutosp.hydros

import androidx.lifecycle.ViewModelStore
import org.cqutosp.hydros.data.HydrosDependencies
import org.cqutosp.hydros.data.network.Credentials
import org.cqutosp.hydros.data.repository.RealAccountRepository
import org.cqutosp.hydros.data.repository.RealAuthRepository
import org.cqutosp.hydros.data.repository.RealDeviceRepository
import org.cqutosp.hydros.data.repository.RealSessionRepository
import org.cqutosp.hydros.data.repository.RealWalletRepository
import org.cqutosp.hydros.data.storage.SavedAccount
import org.cqutosp.hydros.ui.state.ConnectionStage
import org.cqutosp.hydros.ui.state.ControlChannel
import org.cqutosp.hydros.ui.state.DeviceCategory
import org.cqutosp.hydros.ui.state.DeviceUi
import org.cqutosp.hydros.ui.state.HydrosViewModel
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
                        "device/info/mac" -> response("""{"snCode":"AABBCCDDEEFF","deviceName":"热水表","deviceId":"7","bigTypeId":"4","smallTypeId":"1"}""")
                        "order/query/account/bill/list" -> response("[]")
                        else -> response("{}")
                    }
                }
            }
            val operation = Mutex()
            val sessions = RealSessionRepository(transport, store, operation)
            var watchStarts = 0
            val graph = HydrosDependencies(
                auth = RealAuthRepository(transport, store),
                account = RealAccountRepository(transport),
                wallet = RealWalletRepository(transport),
                devices = RealDeviceRepository(transport, store, operation),
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
            assertEquals(ControlChannel.CLOUD, model.state.value.devices.single().channel)
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
            assertEquals(ControlChannel.CLOUD, model.state.value.session?.device?.channel)
            assertEquals(1, watchStarts)
        } finally {
            viewModels.clear()
            runCurrent()
            Dispatchers.resetMain()
        }
    }
}
