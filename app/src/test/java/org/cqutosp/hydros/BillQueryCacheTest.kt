package org.cqutosp.hydros

import org.cqutosp.hydros.data.network.Credentials
import org.cqutosp.hydros.data.repository.BillHistory
import org.cqutosp.hydros.data.repository.RealWalletRepository
import org.cqutosp.hydros.ui.state.BillType
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BillQueryCacheTest {
    private class Fixture(scope: TestScope) {
        val credentials = MutableStateFlow<Credentials?>(Credentials("token", "1", "2", "3", "13800138000"))
        val transport = ScriptTransport().apply {
            script = { path, _ -> response(if (path.endsWith("bill/list")) "[]" else "{}") }
        }
        val history = BillHistory(RealWalletRepository(transport), credentials, scope.backgroundScope) {
            scope.testScheduler.currentTime
        }.apply { refresh() }
        val queries get() = transport.requests.filter { it.first.endsWith("bill/list") }.map { it.second }
    }

    private fun testCache(block: suspend TestScope.(Fixture) -> Unit) = runTest {
        val fixture = Fixture(this)
        runCurrent()
        block(fixture)
    }

    @Test fun monthAndTypeSwitchesReuseSuccessfulResultsIncludingEmptyLists() = testCache { f ->
        val month = f.history.state.value.month
        f.history.selectType(BillType.RECHARGE)
        runCurrent()
        f.history.selectMonth("2025-01")
        runCurrent()
        f.history.selectMonth(month)
        runCurrent()
        f.history.selectType(BillType.CONSUMPTION)
        runCurrent()
        assertEquals(3, f.queries.size)
        assertFalse(f.history.state.value.loading)
        assertNull(f.history.state.value.error)
        assertTrue(f.history.state.value.bills.isEmpty())
    }

    @Test fun cacheExpiresAtFiveMinutesAndManualRefreshBypassesIt() = testCache { f ->
        val month = f.history.state.value.month
        advanceTimeBy(299_999)
        f.history.selectMonth(month)
        runCurrent()
        assertEquals(1, f.queries.size)
        advanceTimeBy(1)
        f.history.selectMonth(month)
        runCurrent()
        assertEquals(2, f.queries.size)
        f.history.refresh()
        runCurrent()
        assertEquals(3, f.queries.size)
        f.history.selectMonth(month)
        runCurrent()
        assertEquals(3, f.queries.size)
    }

    @Test fun switchingBackDuringARequestSharesItAndOlderSelectionCannotReplaceCurrentState() = testCache { f ->
        val pending = CompletableDeferred<Unit>()
        f.transport.script = { path, fields ->
            if (path.endsWith("bill/list")) {
                if (fields["month"] == "2025-01") pending.await()
                response("""[{"consumeBillDTO":{"orderId":"${fields["month"]}","consumeDate":"${fields["month"]}-01 12:00:00","consumeMoney":"0.50"}}]""")
            } else response("{}")
        }
        f.history.selectMonth("2025-01")
        runCurrent()
        f.history.selectMonth("2025-02")
        runCurrent()
        f.history.selectMonth("2025-01")
        runCurrent()
        assertEquals(1, f.queries.count { it["month"] == "2025-01" })
        f.history.selectMonth("2025-02")
        pending.complete(Unit)
        runCurrent()
        assertEquals("2025-02", f.history.state.value.bills.single().id)
        f.history.selectMonth("2025-01")
        runCurrent()
        assertEquals("2025-01", f.history.state.value.bills.single().id)
        assertEquals(3, f.queries.size)
    }

    @Test fun failedQueriesCanBeRetriedImmediately() = testCache { f ->
        var fail = true
        f.transport.script = { path, _ ->
            if (path.endsWith("bill/list")) {
                if (fail) throw java.io.IOException("offline")
                response("[]")
            } else response("{}")
        }
        f.history.selectMonth("2025-01")
        runCurrent()
        assertNotNull(f.history.state.value.error)
        fail = false
        f.history.selectMonth("2025-01")
        runCurrent()
        assertNull(f.history.state.value.error)
        assertEquals(2, f.queries.count { it["month"] == "2025-01" })
    }

    @Test fun manualRefreshCannotBeOverwrittenByAnOlderRequest() = testCache { f ->
        val pending = CompletableDeferred<Unit>()
        var calls = 0
        f.transport.script = { path, fields ->
            if (path.endsWith("bill/list")) {
                val id = if (++calls == 1) {
                    withContext(NonCancellable) { pending.await() }
                    "old"
                } else "new"
                response("""[{"consumeBillDTO":{"orderId":"$id","consumeDate":"${fields["month"]}-01 12:00:00","consumeMoney":"0.50"}}]""")
            } else response("{}")
        }
        f.history.selectMonth("2025-01")
        runCurrent()
        f.history.refresh()
        runCurrent()
        assertEquals("new", f.history.state.value.bills.single().id)
        pending.complete(Unit)
        runCurrent()
        f.history.selectMonth("2025-01")
        runCurrent()
        assertEquals("new", f.history.state.value.bills.single().id)
        assertEquals(2, calls)
    }

    @Test fun cachedListsRetainLoadedBillDetails() = testCache { f ->
        val month = f.history.state.value.month
        f.transport.script = { path, fields -> response(when {
            path.endsWith("bill/list") && fields["billRequestType"] == "1" -> "[]"
            path.endsWith("bill/list") -> """[{"consumeBillDTO":{"orderId":"bill","consumeDate":"$month-01 12:00:00","consumeMoney":"0.50"}}]"""
            path.endsWith("bill/detail") -> """{"consumeMoney":"0.50","usingTimeDisplay":"3分钟"}"""
            else -> "{}"
        }) }
        f.history.refresh()
        runCurrent()
        f.history.loadDetail("bill")
        runCurrent()
        f.history.selectType(BillType.RECHARGE)
        runCurrent()
        f.history.selectType(BillType.CONSUMPTION)
        assertEquals("3分钟", f.history.state.value.bills.single().durationText)
        runCurrent()
        assertEquals("3分钟", f.history.state.value.bills.single().durationText)
    }

    @Test fun accountChangesAndLogoutDiscardCachedQueries() = testCache { f ->
        f.history.selectMonth("2025-01")
        runCurrent()
        f.credentials.value = Credentials("other-token", "4", "5", "3", "13900139000")
        runCurrent()
        f.history.selectMonth("2025-01")
        runCurrent()
        assertEquals(2, f.queries.count { it["month"] == "2025-01" })
        val account = f.credentials.value
        f.credentials.value = null
        runCurrent()
        f.credentials.value = account
        runCurrent()
        f.history.selectMonth("2025-01")
        runCurrent()
        assertEquals(3, f.queries.count { it["month"] == "2025-01" })
    }

    @Test fun cacheKeepsAtMostTwentyFourQueries() = testCache { f ->
        repeat(24) { index ->
            f.history.selectMonth(java.time.YearMonth.of(2024, 1).plusMonths(index.toLong()).toString())
            runCurrent()
        }
        f.history.selectMonth("2024-01")
        runCurrent()
        assertEquals(1, f.queries.count { it["month"] == "2024-01" })
        f.history.selectMonth("2023-12")
        runCurrent()
        f.history.selectMonth("2024-01")
        runCurrent()
        assertEquals(2, f.queries.count { it["month"] == "2024-01" })
    }

    @Test fun lateDetailAddsDurationWithoutOverwritingARefreshedList() = testCache { f ->
        val pending = CompletableDeferred<Unit>()
        var amount = "0.50"
        f.transport.script = { path, fields ->
            if (path.endsWith("bill/detail")) {
                pending.await()
                response("""{"consumeMoney":"0.50","usingTimeDisplay":"旧详情"}""")
            } else if (fields["billRequestType"] == "1") response("[]")
            else billList(fields.getValue("month"), amount = amount)
        }
        f.history.refresh()
        runCurrent()
        val detail = launch { f.history.loadDetail("bill") }
        runCurrent()
        amount = "1.00"
        f.history.refresh()
        runCurrent()
        pending.complete(Unit)
        detail.join()
        // The refreshed list keeps its own fields; only the detail-only fields are merged in.
        assertEquals(1000L, f.history.state.value.bills.single().amountMilli)
        assertEquals("旧详情", f.history.state.value.bills.single().durationText)
        f.history.selectType(BillType.RECHARGE)
        runCurrent()
        f.history.selectType(BillType.CONSUMPTION)
        assertEquals(1000L, f.history.state.value.bills.single().amountMilli)
        assertEquals("旧详情", f.history.state.value.bills.single().durationText)
    }

    @Test fun concurrentDetailsMergeWithoutDiscardingEarlierUpdates() = testCache { f ->
        val pending = CompletableDeferred<Unit>()
        f.transport.script = { path, fields ->
            if (path.endsWith("bill/detail")) {
                pending.await()
                response("""{"consumeMoney":"0.50","usingTimeDisplay":"${fields.getValue("orderId")}"}""")
            } else response("""[
                {"consumeBillDTO":{"orderId":"a","consumeDate":"2025-01-01 12:00:00","consumeMoney":"0.50"}},
                {"consumeBillDTO":{"orderId":"b","consumeDate":"2025-01-02 12:00:00","consumeMoney":"0.50"}}
            ]""")
        }
        f.history.refresh()
        runCurrent()
        val first = launch { f.history.loadDetail("a") }
        val second = launch { f.history.loadDetail("b") }
        runCurrent()
        pending.complete(Unit)
        joinAll(first, second)
        assertEquals(listOf("a", "b"), f.history.state.value.bills.map { it.durationText })
        f.history.selectMonth("2025-01")
        runCurrent()
        f.history.selectMonth(java.time.YearMonth.now().toString())
        assertEquals(listOf("a", "b"), f.history.state.value.bills.map { it.durationText })
    }

    @Test fun detailAppliesToEntityAcrossMonthQueries() = testCache { f ->
        val pending = CompletableDeferred<Unit>()
        f.transport.script = { path, fields ->
            if (path.endsWith("bill/detail")) {
                pending.await()
                response("""{"consumeMoney":"0.50","usingTimeDisplay":"3分钟"}""")
            } else billList(fields.getValue("month"), orderId = "bill-${fields.getValue("month")}")
        }
        f.history.selectMonth("2025-01")
        runCurrent()
        val detail = launch { f.history.loadDetail("bill-2025-01") }
        runCurrent()
        f.history.selectMonth("2025-02")
        runCurrent()
        pending.complete(Unit)
        detail.join()
        assertEquals("2025-02", f.history.state.value.month)
        assertNull(f.history.state.value.bills.single().durationText)
        f.history.selectMonth("2025-01")
        assertEquals("3分钟", f.history.state.value.bills.single().durationText)
        assertEquals(1, f.queries.count { it["month"] == "2025-01" })
    }

    @Test fun detailFreshWithinTtlSkipsNetwork() = testCache { f ->
        val month = f.history.state.value.month
        var detailCalls = 0
        f.transport.script = { path, fields ->
            if (path.endsWith("bill/detail")) {
                detailCalls++
                response("""{"consumeMoney":"0.50","usingTimeDisplay":"3分钟"}""")
            } else if (fields["billRequestType"] == "1") response("[]")
            else billList(fields.getValue("month"))
        }
        f.history.refresh()
        runCurrent()
        f.history.loadDetail("bill")
        runCurrent()
        assertEquals(1, detailCalls)
        f.history.loadDetail("bill")
        runCurrent()
        assertEquals(1, detailCalls)
        advanceTimeBy(299_999)
        f.history.loadDetail("bill")
        runCurrent()
        assertEquals(1, detailCalls)
        advanceTimeBy(2)
        f.history.loadDetail("bill")
        runCurrent()
        assertEquals(2, detailCalls)
    }

    @Test fun detailStatusShowsFetchingThenClearsOnSuccess() = testCache { f ->
        val pending = CompletableDeferred<Unit>()
        f.transport.script = { path, fields ->
            if (path.endsWith("bill/detail")) {
                pending.await()
                response("""{"consumeMoney":"0.50","usingTimeDisplay":"3分钟"}""")
            } else if (fields["billRequestType"] == "1") response("[]")
            else billList(fields.getValue("month"))
        }
        f.history.refresh()
        runCurrent()
        val detail = launch { f.history.loadDetail("bill") }
        runCurrent()
        assertEquals(true, f.history.state.value.details.getValue("bill").fetching)
        pending.complete(Unit)
        detail.join()
        assertNull(f.history.state.value.details["bill"])
        assertEquals("3分钟", f.history.state.value.detailBill?.durationText)
    }

    @Test fun detailFailureIsReportedAndForcedRetryRecovers() = testCache { f ->
        var failing = true
        var detailCalls = 0
        f.transport.script = { path, fields ->
            if (path.endsWith("bill/detail")) {
                detailCalls++
                if (failing) throw java.io.IOException("offline")
                response("""{"consumeMoney":"0.50","usingTimeDisplay":"3分钟"}""")
            } else if (fields["billRequestType"] == "1") response("[]")
            else billList(fields.getValue("month"))
        }
        f.history.refresh()
        runCurrent()
        f.history.loadDetail("bill")
        runCurrent()
        val failed = f.history.state.value.details.getValue("bill")
        assertNotNull(failed.error)
        assertFalse(failed.fetching)
        failing = false
        f.history.loadDetail("bill", force = true)
        runCurrent()
        assertNull(f.history.state.value.details["bill"])
        assertEquals("3分钟", f.history.state.value.detailBill?.durationText)
        assertEquals(2, detailCalls)
    }

    @Test fun incompleteDetailWithoutDurationIsQueriedAgainOnReopen() = testCache { f ->
        var detailCalls = 0
        f.transport.script = { path, fields ->
            if (path.endsWith("bill/detail")) {
                detailCalls++
                response("""{"consumeMoney":"0.50"}""")
            } else if (fields["billRequestType"] == "1") response("[]")
            else billList(fields.getValue("month"))
        }
        f.history.refresh()
        runCurrent()
        f.history.loadDetail("bill")
        runCurrent()
        f.history.loadDetail("bill")
        runCurrent()
        assertEquals(2, detailCalls)
    }

    @Test fun forcedDetailReloadBypassesTheFreshWindow() = testCache { f ->
        var detailCalls = 0
        f.transport.script = { path, fields ->
            if (path.endsWith("bill/detail")) {
                detailCalls++
                response("""{"consumeMoney":"0.50","usingTimeDisplay":"3分钟"}""")
            } else if (fields["billRequestType"] == "1") response("[]")
            else billList(fields.getValue("month"))
        }
        f.history.refresh()
        runCurrent()
        f.history.loadDetail("bill")
        runCurrent()
        f.history.loadDetail("bill", force = true)
        runCurrent()
        assertEquals(2, detailCalls)
    }

    @Test fun concurrentDetailsForOneBillShareASingleRequest() = testCache { f ->
        val pending = CompletableDeferred<Unit>()
        var detailCalls = 0
        f.transport.script = { path, fields ->
            if (path.endsWith("bill/detail")) {
                detailCalls++
                pending.await()
                response("""{"consumeMoney":"0.50","usingTimeDisplay":"3分钟"}""")
            } else if (fields["billRequestType"] == "1") response("[]")
            else billList(fields.getValue("month"))
        }
        f.history.refresh()
        runCurrent()
        val first = launch { f.history.loadDetail("bill") }
        val second = launch { f.history.loadDetail("bill") }
        runCurrent()
        assertEquals(1, detailCalls)
        pending.complete(Unit)
        joinAll(first, second)
        assertEquals(1, detailCalls)
        assertEquals("3分钟", f.history.state.value.detailBill?.durationText)
    }

    @Test fun detailBillSnapshotSurvivesMonthSwitchAndRefresh() = testCache { f ->
        f.transport.script = { path, fields ->
            if (path.endsWith("bill/detail")) {
                response("""{"consumeMoney":"0.50","usingTimeDisplay":"3分钟"}""")
            } else if (fields["billRequestType"] == "1") response("[]")
            else billList(fields.getValue("month"), orderId = "bill-${fields.getValue("month")}")
        }
        f.history.selectMonth("2025-01")
        runCurrent()
        f.history.loadDetail("bill-2025-01")
        runCurrent()
        f.history.selectMonth("2025-02")
        runCurrent()
        assertEquals("bill-2025-01", f.history.state.value.detailBill?.id)
        assertEquals("3分钟", f.history.state.value.detailBill?.durationText)
        f.history.refresh()
        runCurrent()
        assertEquals("bill-2025-01", f.history.state.value.detailBill?.id)
        assertEquals("3分钟", f.history.state.value.detailBill?.durationText)
    }

    @Test fun forcedRefreshClearsRememberedDetailsSoTheNextOpenRefetches() = testCache { f ->
        var detailCalls = 0
        f.transport.script = { path, fields ->
            if (path.endsWith("bill/detail")) {
                detailCalls++
                response("""{"consumeMoney":"0.50","usingTimeDisplay":"3分钟"}""")
            } else if (fields["billRequestType"] == "1") response("[]")
            else billList(fields.getValue("month"))
        }
        f.history.refresh()
        runCurrent()
        f.history.loadDetail("bill")
        runCurrent()
        f.history.refresh()
        runCurrent()
        f.history.loadDetail("bill")
        runCurrent()
        assertEquals(2, detailCalls)
    }

    @Test fun logoutCancelsDetailsAndCannotRestoreThePreviousAccountsData() = testCache { f ->
        val pending = CompletableDeferred<Unit>()
        f.transport.script = { path, fields ->
            if (path.endsWith("bill/detail")) {
                withContext(NonCancellable) { pending.await() }
                response("""{"consumeMoney":"9.00","usingTimeDisplay":"旧账户"}""")
            } else billList(fields.getValue("month"))
        }
        f.history.refresh()
        runCurrent()
        val detail = launch { f.history.loadDetail("bill") }
        runCurrent()
        val account = f.credentials.value
        f.credentials.value = null
        runCurrent()
        assertTrue(f.history.state.value.bills.isEmpty())
        f.credentials.value = account
        runCurrent()
        f.history.refresh()
        runCurrent()
        pending.complete(Unit)
        detail.join()
        assertTrue(detail.isCancelled)
        assertEquals(500L, f.history.state.value.bills.single().amountMilli)
        assertNull(f.history.state.value.bills.single().durationText)
    }

    @Test fun accountChangeRejectsAnUncancellableOlderList() = testCache { f ->
        val pending = CompletableDeferred<Unit>()
        var old = true
        f.transport.script = { _, fields ->
            val amount = if (old) {
                withContext(NonCancellable) { pending.await() }
                "9.00"
            } else "0.50"
            billList(fields.getValue("month"), amount = amount)
        }
        f.history.selectMonth("2025-01")
        runCurrent()
        f.credentials.value = Credentials("other-token", "4", "5", "3", "13900139000")
        runCurrent()
        old = false
        f.history.selectMonth("2025-01")
        runCurrent()
        pending.complete(Unit)
        runCurrent()
        assertEquals(500L, f.history.state.value.bills.single().amountMilli)
        f.history.selectMonth("2025-01")
        assertEquals(500L, f.history.state.value.bills.single().amountMilli)
        assertEquals(2, f.queries.count { it["month"] == "2025-01" })
    }

    @Test fun loadDetailSucceedsEvenIfQueryCacheWasEvictedOrExpired() = testCache { f ->
        f.transport.script = { path, _ ->
            if (path.endsWith("bill/detail")) {
                response("""{"consumeMoney":"0.50","usingTimeDisplay":"3分钟"}""")
            } else billList("2025-01")
        }
        f.history.selectMonth("2025-01")
        runCurrent()
        assertEquals(1, f.history.state.value.bills.size)
        // Evict the query from cache by populating 25 distinct months
        for (i in 1..25) {
            val m = "2024-%02d".format(if (i <= 12) i else i - 12) + "-$i"
            f.history.selectMonth(m)
            runCurrent()
        }
        // Return to 2025-01 month but with bills already rendered on screen
        f.history.selectMonth("2025-01")
        runCurrent()
        assertNull(f.history.state.value.bills.single().durationText)

        // Advance 6 minutes so cache entry expires if present
        advanceTimeBy(360_000)
        // Now call loadDetail when cache is expired/missing
        f.history.loadDetail("bill")
        runCurrent()
        assertEquals("3分钟", f.history.state.value.bills.single().durationText)
    }

    @Test fun refreshSameMonthRetainsExistingBillsDuringLoading() = testCache { f ->
        val pending = CompletableDeferred<Unit>()
        f.transport.script = { _, fields ->
            pending.await()
            billList(fields.getValue("month"), amount = "1.50")
        }
        f.history.selectMonth("2025-01")
        pending.complete(Unit)
        runCurrent()
        assertEquals(1500L, f.history.state.value.bills.single().amountMilli)

        val nextPending = CompletableDeferred<Unit>()
        f.transport.script = { _, fields ->
            nextPending.await()
            billList(fields.getValue("month"), amount = "2.00")
        }
        f.history.refresh()
        runCurrent()
        // During loading, existing bills must NOT be wiped to emptyList()
        assertTrue(f.history.state.value.loading)
        assertEquals(1, f.history.state.value.bills.size)
        assertEquals(1500L, f.history.state.value.bills.single().amountMilli)

        nextPending.complete(Unit)
        runCurrent()
        assertFalse(f.history.state.value.loading)
        assertEquals(2000L, f.history.state.value.bills.single().amountMilli)
    }

    @Test fun switchMonthClearsBillsDuringLoading() = testCache { f ->
        val pending = CompletableDeferred<Unit>()
        f.transport.script = { _, fields ->
            pending.await()
            billList(fields.getValue("month"), amount = "1.50")
        }
        f.history.selectMonth("2025-01")
        pending.complete(Unit)
        runCurrent()
        assertEquals(1, f.history.state.value.bills.size)

        val nextPending = CompletableDeferred<Unit>()
        f.transport.script = { _, fields ->
            nextPending.await()
            billList(fields.getValue("month"), amount = "2.00")
        }
        f.history.selectMonth("2025-02")
        runCurrent()
        // During loading for a different month, existing bills must be cleared
        assertTrue(f.history.state.value.loading)
        assertTrue(f.history.state.value.bills.isEmpty())

        nextPending.complete(Unit)
        runCurrent()
        assertFalse(f.history.state.value.loading)
        assertEquals(1, f.history.state.value.bills.size)
    }

    private fun billList(month: String, amount: String = "0.50", orderId: String = "bill") = response("""
        [{"consumeBillDTO":{"orderId":"$orderId","consumeDate":"$month-01 12:00:00","consumeMoney":"$amount"}}]
    """.trimIndent())
}
