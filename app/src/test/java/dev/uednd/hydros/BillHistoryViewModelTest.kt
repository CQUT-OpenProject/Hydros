package dev.uednd.hydros

import androidx.lifecycle.ViewModelStore
import dev.uednd.hydros.data.HydrosDependencies
import dev.uednd.hydros.data.network.Credentials
import dev.uednd.hydros.data.repository.*
import dev.uednd.hydros.ui.state.BillType
import dev.uednd.hydros.ui.state.HydrosViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class BillHistoryViewModelTest {
    @Test fun projectsSelectionDetailsErrorsAndAccountResetIntoUiState() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = MemoryStore().apply { auth = Credentials("token", "1", "2", "3", "13800138000") }
        val transport = ScriptTransport().apply {
            script = { path, fields -> response(when {
                path.endsWith("bill/detail") -> """{"consumeMoney":"0.50","usingTimeDisplay":"3分钟"}"""
                path.endsWith("bill/list") && fields["month"] == "2025-02" -> throw java.io.IOException("offline")
                path.endsWith("bill/list") && fields["billRequestType"] == "1" -> "[]"
                path.endsWith("bill/list") -> """[{"consumeBillDTO":{"orderId":"bill","consumeDate":"2025-01-01 12:00:00","consumeMoney":"0.50"}}]"""
                else -> "{}"
            }) }
        }
        val auth = RealAuthRepository(transport, store)
        val ble = ScriptBle()
        val lock = Mutex()
        val model = HydrosViewModel(HydrosDependencies(auth, RealAccountRepository(transport),
            RealWalletRepository(transport), RealDeviceRepository(transport, store, ble, lock),
            RealSessionRepository(transport, store, ble, lock), store, backgroundScope))
        val models = ViewModelStore().apply { put("hydros", model) }
        try {
            runCurrent()
            assertEquals("bill", model.state.value.bills.single().id)
            model.loadDetail("bill")
            runCurrent()
            assertEquals("3分钟", model.state.value.bills.single().durationText)
            assertFalse(model.state.value.working)
            model.selectBillType(BillType.RECHARGE)
            runCurrent()
            assertEquals(BillType.RECHARGE, model.state.value.billType)
            assertTrue(model.state.value.bills.isEmpty())
            model.selectBillType(BillType.CONSUMPTION)
            runCurrent()
            assertEquals("3分钟", model.state.value.bills.single().durationText)
            model.loadMonth("2025-02")
            runCurrent()
            assertEquals("2025-02", model.state.value.month)
            assertEquals("网络连接失败，请检查网络后重试", model.state.value.billsError)
            assertFalse(model.state.value.loadingBills)
            auth.logout()
            runCurrent()
            assertFalse(model.state.value.signedIn)
            assertTrue(model.state.value.bills.isEmpty())
            assertNull(model.state.value.billsError)
        } finally {
            models.clear()
            runCurrent()
            Dispatchers.resetMain()
        }
    }
}
