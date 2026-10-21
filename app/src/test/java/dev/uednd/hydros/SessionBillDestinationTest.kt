package dev.uednd.hydros

import androidx.lifecycle.ViewModelStore
import dev.uednd.hydros.data.HydrosDependencies
import dev.uednd.hydros.data.analytics.PostSessionNavResult
import dev.uednd.hydros.data.network.ApiResponse
import dev.uednd.hydros.data.network.Credentials
import dev.uednd.hydros.data.repository.BillHistory
import dev.uednd.hydros.data.repository.RealAccountRepository
import dev.uednd.hydros.data.repository.RealAuthRepository
import dev.uednd.hydros.data.repository.RealDeviceRepository
import dev.uednd.hydros.data.repository.RealSessionRepository
import dev.uednd.hydros.data.repository.RealWalletRepository
import dev.uednd.hydros.data.storage.HydrosStore
import dev.uednd.hydros.data.storage.PostSessionDestination
import dev.uednd.hydros.data.storage.SavedAccount
import dev.uednd.hydros.ui.state.*
import java.time.YearMonth
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.JsonElement
import org.junit.Assert.*
import org.junit.Test

/**
 * Covers the "end of use" destination feature without a device:
 * [BillHistory.locateSessionBill] resolves the bill for a finished session, and [HydrosViewModel]
 * publishes the resulting navigation state (bill id + resolved month) only when configured.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionBillDestinationTest {
    private val auth = Credentials("token", "1", "2", "3", "13800138000")
    private val device = DeviceUi("AABBCCDDEEFF", "测试水表", "测试宿舍", DeviceCategory.SHOWER,
        channel = ControlChannel.CLOUD, deviceId = "7", ledgerMac = "AABBCCDDEEFF", connectMac = "AA:BB:CC:DD:EE:FF",
        bigTypeId = "4", smallTypeId = "1")

    private fun session(orderNo: String = "", consumeDate: String = "") = SessionUi(
        device, auth.key, stage = SessionStage.USING, orderNo = orderNo, consumeDate = consumeDate,
    )

    // region BillHistory.locateSessionBill

    private class HistoryFixture(scope: TestScope) {
        val credentials = MutableStateFlow<Credentials?>(null)
        val transport = ScriptTransport()
        val history = BillHistory(RealWalletRepository(transport), credentials, scope.backgroundScope) {
            scope.testScheduler.currentTime
        }
        val months get() = transport.requests.filter { it.first.endsWith("bill/list") }
            .map { it.second.getValue("month") }
    }

    private fun testHistory(block: suspend TestScope.(HistoryFixture) -> Unit) = runTest {
        val fixture = HistoryFixture(this)
        fixture.credentials.value = auth
        runCurrent()
        block(fixture)
    }

    @Test fun locatesByOrderNoAndPublishesTheMatchedMonthAndBills() = testHistory { fixture ->
        val month = YearMonth.now()
        val consumeDate = "$month-15 12:00:00"
        fixture.transport.script = { path, _ ->
            if (path.endsWith("bill/list")) response("""[
                {"consumeBillDTO":{"orderId":"bill-other","consumeDate":"$month-01 12:00:00","consumeMoney":"0.10"}},
                {"consumeBillDTO":{"orderId":"bill-finished","orderNo":"order-1","consumeDate":"$consumeDate","consumeMoney":"0.50"}}
            ]""") else response("{}")
        }

        val bill = fixture.history.locateSessionBill(session(orderNo = "order-1", consumeDate = consumeDate))

        assertEquals("bill-finished", bill?.id)
        assertEquals(month.toString(), fixture.history.state.value.month)
        assertEquals(BillType.CONSUMPTION, fixture.history.state.value.type)
        assertEquals(listOf("bill-other", "bill-finished"), fixture.history.state.value.bills.map { it.id })
        assertFalse(fixture.history.state.value.loading)
        assertNull(fixture.history.state.value.error)
        assertEquals(listOf(month.toString()), fixture.months)
    }

    @Test fun matchesTheOrderIdentifierWhenTheBillCarriesNoSeparateOrderNo() = testHistory { fixture ->
        val month = YearMonth.now()
        val consumeDate = "$month-08 20:00:00"
        fixture.transport.script = { path, _ ->
            if (path.endsWith("bill/list")) response("""[
                {"consumeBillDTO":{"orderId":"order-2","consumeDate":"$month-08 20:00:00","consumeMoney":"0.50"}}
            ]""") else response("{}")
        }

        val bill = fixture.history.locateSessionBill(session(orderNo = "order-2", consumeDate = ""))

        assertEquals("order-2", bill?.id)
        assertEquals(month.toString(), fixture.history.state.value.month)
        assertTrue(fixture.history.state.value.bills.any { it.id == "order-2" })
    }

    @Test fun fallsBackToConsumeDateWhenNoOrderIdentifierMatches() = testHistory { fixture ->
        val month = YearMonth.now()
        val consumeDate = "$month-20 08:00:00"
        fixture.transport.script = { path, _ ->
            if (path.endsWith("bill/list")) response("""[
                {"consumeBillDTO":{"orderId":"unrelated","consumeDate":"$month-19 08:00:00","consumeMoney":"0.10"}},
                {"consumeBillDTO":{"orderId":"by-time","consumeDate":"$consumeDate","consumeMoney":"0.50"}}
            ]""") else response("{}")
        }

        val bill = fixture.history.locateSessionBill(session(orderNo = "missing-order", consumeDate = consumeDate))

        assertEquals("by-time", bill?.id)
        assertTrue(fixture.history.state.value.bills.any { it.id == "by-time" })
    }

    @Test fun crossMonthConsumeDateQueriesAndPublishesThePreviousMonth() = testHistory { fixture ->
        val previous = YearMonth.now().minusMonths(1)
        val consumeDate = "$previous-25 09:30:00"
        fixture.transport.script = { path, fields ->
            if (path.endsWith("bill/list")) response(
                if (fields["month"] == previous.toString())
                    """[{"consumeBillDTO":{"orderId":"bill-finished","orderNo":"order-1","consumeDate":"$consumeDate","consumeMoney":"0.50"}}]"""
                else "[]"
            ) else response("{}")
        }

        val bill = fixture.history.locateSessionBill(session(orderNo = "order-1", consumeDate = consumeDate))

        assertEquals("bill-finished", bill?.id)
        assertEquals(previous.toString(), fixture.history.state.value.month)
        assertEquals(listOf(previous.toString()), fixture.months)
        // The located month is cached, so re-selecting it does not issue another request.
        fixture.history.selectMonth(previous.toString())
        runCurrent()
        assertEquals(1, fixture.months.size)
        assertEquals("bill-finished", fixture.history.state.value.bills.single().id)
    }

    @Test fun noMatchLeavesTheBrowsedSelectionUntouched() = testHistory { fixture ->
        val browsed = YearMonth.of(2025, 1)
        fixture.transport.script = { path, _ ->
            if (path.endsWith("bill/list"))
                response("""[{"consumeBillDTO":{"orderId":"browsed","consumeDate":"2025-01-01 12:00:00","consumeMoney":"0.50"}}]""")
            else response("{}")
        }
        fixture.history.selectMonth(browsed.toString())
        runCurrent()
        assertEquals("browsed", fixture.history.state.value.bills.single().id)

        val bill = fixture.history.locateSessionBill(
            session(orderNo = "missing", consumeDate = "${YearMonth.now()}-15 12:00:00"),
        )

        assertNull(bill)
        assertEquals(browsed.toString(), fixture.history.state.value.month)
        assertEquals("browsed", fixture.history.state.value.bills.single().id)
        assertFalse(fixture.history.state.value.loading)
        assertNull(fixture.history.state.value.error)
    }

    @Test fun continuesToTheNextMonthWhenAnEarlierQueryFails() = testHistory { fixture ->
        val previous = YearMonth.now().minusMonths(1)
        val current = YearMonth.now()
        val consumeDate = "$previous-25 09:30:00"
        fixture.transport.script = { path, fields ->
            if (path.endsWith("bill/list")) when (fields["month"]) {
                previous.toString() -> throw java.io.IOException("offline")
                current.toString() -> response(
                    """[{"consumeBillDTO":{"orderId":"bill-finished","orderNo":"order-1","consumeDate":"$consumeDate","consumeMoney":"0.50"}}]"""
                )
                else -> response("[]")
            } else response("{}")
        }

        val bill = fixture.history.locateSessionBill(session(orderNo = "order-1", consumeDate = consumeDate))

        assertEquals("bill-finished", bill?.id)
        assertEquals(current.toString(), fixture.history.state.value.month)
        assertEquals(listOf(previous.toString(), current.toString()), fixture.months)
    }

    @Test fun failedQueryReturnsNullWithoutRewritingTheBrowsedState() = testHistory { fixture ->
        val browsed = YearMonth.of(2025, 2)
        fixture.transport.script = { path, _ ->
            if (path.endsWith("bill/list"))
                response("""[{"consumeBillDTO":{"orderId":"browsed","consumeDate":"2025-02-01 12:00:00","consumeMoney":"0.50"}}]""")
            else response("{}")
        }
        fixture.history.selectMonth(browsed.toString())
        runCurrent()
        fixture.transport.script = { _, _ -> throw java.io.IOException("offline") }

        val bill = fixture.history.locateSessionBill(
            session(orderNo = "missing", consumeDate = "${YearMonth.now()}-15 12:00:00"),
        )

        assertNull(bill)
        assertEquals(browsed.toString(), fixture.history.state.value.month)
        assertEquals("browsed", fixture.history.state.value.bills.single().id)
        assertFalse(fixture.history.state.value.loading)
        assertNull(fixture.history.state.value.error)
    }

    // endregion

    // region HydrosViewModel end-of-session navigation state

    private class DestinationStore(private val memory: MemoryStore) : HydrosStore by memory {
        var destination = PostSessionDestination.DEVICE
        override suspend fun postSessionDestination() = destination
        override suspend fun postSessionDestination(value: PostSessionDestination) { destination = value }
    }

    private class ViewModelFixture(
        val model: HydrosViewModel,
        val store: DestinationStore,
        val transport: ScriptTransport,
        val sessions: RealSessionRepository,
        val analytics: RecordingAnalyticsTracker,
    )

    private fun createModel(
        scope: TestScope,
        consumeDate: String,
        postSessionDestination: PostSessionDestination = PostSessionDestination.DEVICE,
        analytics: RecordingAnalyticsTracker = RecordingAnalyticsTracker(),
        billList: suspend (Map<String, String>) -> ApiResponse<JsonElement>,
    ): ViewModelFixture {
        val credentials = auth
        val savedDevice = device
        val memory = MemoryStore().apply {
            this.auth = credentials
            records[credentials.key] = SavedAccount(
                devices = listOf(savedDevice),
                recent = listOf(savedDevice.id),
                session = SessionUi(savedDevice, credentials.key, stage = SessionStage.USING,
                    orderNo = "order-1", consumeDate = consumeDate),
            )
        }
        val store = DestinationStore(memory).apply { destination = postSessionDestination }
        val transport = ScriptTransport().apply {
            script = { path, fields ->
                when {
                    path == "order/tcpDevice/closeOrder" -> response("{}")
                    path == "order/tcpDevice/query/rateOrder/using" -> response("{}")
                    path == "order/consumeOrder/result/query" ->
                        response("""{"orderNo":"order-1","consumeMoney":"0.50","snCode":"AABBCCDDEEFF"}""")
                    path.endsWith("bill/list") -> billList(fields)
                    path == "account/wallet" -> response("""{"money":"12.34"}""")
                    path == "account/info" -> response("""{"name":"测试学生","idCardNumber":"1"}""")
                    path == "project/info/triple" -> response("""{"projectName":"测试学校"}""")
                    else -> response("{}")
                }
            }
        }
        val lock = Mutex()
        val sessions = RealSessionRepository(transport, store, lock, confirmationAttempts = 2, pause = {})
        val graph = HydrosDependencies(RealAuthRepository(transport, store), RealAccountRepository(transport),
            RealWalletRepository(transport), RealDeviceRepository(transport, store, lock), sessions, store,
            scope.backgroundScope, analytics = analytics)
        return ViewModelFixture(HydrosViewModel(graph), store, transport, sessions, analytics)
    }

    @Test fun billDestinationPublishesTheResolvedBillAndMonthForDirectNavigation() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val models = ViewModelStore()
        try {
            val previous = YearMonth.now().minusMonths(1)
            val consumeDate = "$previous-25 09:30:00"
            val fixture = createModel(this, consumeDate, PostSessionDestination.BILL) { fields ->
                if (fields["month"] == previous.toString())
                    response("""[{"consumeBillDTO":{"orderId":"bill-finished","orderNo":"order-1","consumeDate":"$consumeDate","consumeMoney":"0.50"}}]""")
                else response("[]")
            }
            models.put("hydros", fixture.model)
            advanceUntilIdle()
            assertEquals(SessionStage.USING, fixture.model.state.value.session?.stage)
            assertEquals(PostSessionDestination.BILL, fixture.model.state.value.postSessionDestination)

            fixture.sessions.finish(auth)
            advanceUntilIdle()
            val state = fixture.model.state.value
            assertNull(state.session)
            assertNull(fixture.sessions.session.value)
            assertEquals("bill-finished", state.postSessionBillId)
            assertFalse(state.resolvingPostSessionDestination)
            assertEquals(previous.toString(), state.month)
            assertEquals(BillType.CONSUMPTION, state.billType)
            assertFalse(state.loadingBills)
            assertTrue("bill/{id} must find its row in state.bills on first composition",
                state.bills.any { it.id == "bill-finished" })
        } finally {
            models.clear(); runCurrent(); Dispatchers.resetMain()
        }
    }

    @Test fun deviceDestinationNeverResolvesOrLeaksABillId() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val models = ViewModelStore()
        try {
            val previous = YearMonth.now().minusMonths(1)
            val consumeDate = "$previous-25 09:30:00"
            val fixture = createModel(this, consumeDate) { fields ->
                if (fields["month"] == previous.toString())
                    response("""[{"consumeBillDTO":{"orderId":"bill-finished","orderNo":"order-1","consumeDate":"$consumeDate","consumeMoney":"0.50"}}]""")
                else response("[]")
            }
            models.put("hydros", fixture.model)
            advanceUntilIdle()
            assertEquals(PostSessionDestination.DEVICE, fixture.model.state.value.postSessionDestination)

            fixture.sessions.finish(auth)
            advanceUntilIdle()

            val state = fixture.model.state.value
            assertNull(state.session)
            assertNull(state.postSessionBillId)
            assertFalse(state.resolvingPostSessionDestination)
            // The device destination must not perform the cross-month bill lookup.
            assertFalse("DEVICE must not query another month",
                fixture.transport.requests.any { it.first.endsWith("bill/list") && it.second["month"] == previous.toString() })
        } finally {
            models.clear(); runCurrent(); Dispatchers.resetMain()
        }
    }

    @Test fun billDestinationWithoutAMatchFallsBackWithoutLeavingTheUiResolving() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val models = ViewModelStore()
        try {
            val currentMonth = YearMonth.now()
            val fixture = createModel(this, "$currentMonth-15 12:00:00", PostSessionDestination.BILL) { _ -> response("[]") }
            models.put("hydros", fixture.model)
            advanceUntilIdle()

            fixture.sessions.finish(auth)
            advanceUntilIdle()

            val state = fixture.model.state.value
            assertNull(state.session)
            assertNull(state.postSessionBillId)
            assertFalse(state.resolvingPostSessionDestination)
            assertEquals(currentMonth.toString(), state.month)
            assertTrue(state.bills.isEmpty())
            assertEquals("a completed lookup without a match is not a timeout",
                listOf(PostSessionDestination.BILL to PostSessionNavResult.NOT_FOUND), fixture.analytics.postSessionNav)
        } finally {
            models.clear(); runCurrent(); Dispatchers.resetMain()
        }
    }

    @Test fun dismissPostSessionNavigationPreventsPublishingABillAfterUserLeaves() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val models = ViewModelStore()
        try {
            val previous = YearMonth.now().minusMonths(1)
            val consumeDate = "$previous-25 09:30:00"
            val hanging = CompletableDeferred<Unit>()
            val fixture = createModel(this, consumeDate, PostSessionDestination.BILL) { fields ->
                if (fields["month"] == previous.toString()) {
                    hanging.await()
                    response("[]")
                } else response("[]")
            }
            models.put("hydros", fixture.model)
            advanceUntilIdle()
            fixture.sessions.finish(auth)
            advanceUntilIdle()
            assertNull(fixture.model.state.value.session)
            assertTrue(fixture.transport.requests.any { it.first.endsWith("bill/list") })

            fixture.model.dismissPostSessionNavigation()
            hanging.complete(Unit)
            advanceUntilIdle()

            val state = fixture.model.state.value
            assertFalse(state.resolvingPostSessionDestination)
            assertNull(state.postSessionBillId)
        } finally {
            models.clear(); runCurrent(); Dispatchers.resetMain()
        }
    }

    @Test fun billLookupTimeoutClearsResolvingInsteadOfBlockingNavigation() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val models = ViewModelStore()
        try {
            val currentMonth = YearMonth.now()
            val hanging = CompletableDeferred<Unit>()
            val fixture = createModel(this, "$currentMonth-15 12:00:00", PostSessionDestination.BILL) { _ ->
                hanging.await()
                response("[]")
            }
            models.put("hydros", fixture.model)
            advanceUntilIdle()
            fixture.sessions.finish(auth)
            advanceUntilIdle()
            assertNull(fixture.model.state.value.session)
            assertTrue(fixture.transport.requests.any { it.first.endsWith("bill/list") })

            advanceTimeBy(8_001)
            advanceUntilIdle()

            val state = fixture.model.state.value
            assertFalse(state.resolvingPostSessionDestination)
            assertNull(state.postSessionBillId)
            assertEquals(listOf(PostSessionDestination.BILL to PostSessionNavResult.TIMEOUT),
                fixture.analytics.postSessionNav)
        } finally {
            models.clear(); runCurrent(); Dispatchers.resetMain()
        }
    }

    @Test fun leavingWithoutAPendingBillResolutionDoesNotReportDismissal() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val models = ViewModelStore()
        try {
            val currentMonth = YearMonth.now()
            val fixture = createModel(this, "$currentMonth-15 12:00:00") { _ -> response("[]") }
            models.put("hydros", fixture.model)
            advanceUntilIdle()
            fixture.sessions.finish(auth)
            advanceUntilIdle()

            fixture.model.dismissPostSessionNavigation()
            advanceUntilIdle()

            assertFalse(fixture.analytics.postSessionNav.any { it.second == PostSessionNavResult.DISMISSED })
        } finally {
            models.clear(); runCurrent(); Dispatchers.resetMain()
        }
    }

    // endregion
}
