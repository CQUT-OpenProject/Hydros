package dev.uednd.hydros.data.repository

import dev.uednd.hydros.data.network.Credentials
import dev.uednd.hydros.ui.state.BillType
import dev.uednd.hydros.ui.state.BillUi
import dev.uednd.hydros.ui.state.SessionUi
import java.time.YearMonth
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class BillDetailState(val fetching: Boolean = false, val error: Exception? = null)

data class BillHistoryState(
    val month: String = YearMonth.now().toString(),
    val type: BillType = BillType.CONSUMPTION,
    val bills: List<BillUi> = emptyList(),
    val loading: Boolean = false,
    val error: Exception? = null,
    val details: Map<String, BillDetailState> = emptyMap(),
    /** Snapshot of the bill whose detail page is open, so it survives list changes. */
    val detailBill: BillUi? = null,
)

/** Main-thread owner of account-scoped queries, their cache, and the selected bill history. */
class BillHistory(
    private val wallet: WalletRepository,
    private val credentials: StateFlow<Credentials?>,
    scope: CoroutineScope,
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    // A failed query must not cancel other queries or the caller's scope.
    private val scope = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))
    private val store = BillEntityStore(nowMillis)

    private val mutable = MutableStateFlow(BillHistoryState())
    val state = mutable.asStateFlow()
    private val requests = mutableMapOf<BillQueryKey, Deferred<List<BillUi>>>()
    private val detailJobs = mutableMapOf<String, Deferred<Unit>>()
    private var selection: Job? = null
    private var owner: Credentials? = null
    private var epoch = 0

    init {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            credentials.collect { synchronizeAccount() }
        }
    }

    private fun synchronizeAccount() {
        if (owner == credentials.value) return
        owner = credentials.value
        epoch++
        selection?.cancel()
        requests.values.toList().forEach { it.cancel() }
        requests.clear()
        detailJobs.values.toList().forEach { it.cancel() }
        detailJobs.clear()
        store.clear()
        mutable.value = BillHistoryState()
    }

    fun selectMonth(month: String) {
        synchronizeAccount()
        load(BillQueryKey(month, state.value.type))
    }

    fun selectType(type: BillType) {
        synchronizeAccount()
        if (state.value.type != type) load(BillQueryKey(state.value.month, type))
    }

    fun refresh() {
        synchronizeAccount()
        load(BillQueryKey(state.value.month, state.value.type), force = true)
    }

    private fun publishBills(
        loading: Boolean = state.value.loading,
        error: Exception? = state.value.error,
        bills: List<BillUi>? = null,
    ) {
        val current = state.value
        val query = BillQueryKey(current.month, current.type)
        val projected = bills ?: store.projectedBills(query) ?: current.bills.map { store.bill(it.id) ?: it }
        mutable.value = current.copy(
            bills = projected,
            detailBill = current.detailBill?.let { store.bill(it.id) ?: it },
            loading = loading,
            error = error,
        )
    }

    private fun setDetailSync(id: String, sync: BillDetailState) {
        mutable.value = state.value.copy(details = state.value.details + (id to sync))
    }

    private fun clearDetailSync(id: String) {
        if (id !in state.value.details) return
        mutable.value = state.value.copy(details = state.value.details - id)
    }

    private fun load(query: BillQueryKey, force: Boolean = false, resetDetail: Boolean = force) {
        selection?.cancel()
        val auth = owner
        val queryChanged = state.value.month != query.month || state.value.type != query.type
        mutable.value = state.value.copy(month = query.month, type = query.type)
        if (auth == null) return
        if (force) {
            store.removeQuery(query)
            requests.remove(query)?.cancel()
        }
        if (store.isQueryFresh(query)) {
            publishBills(loading = false, error = null)
            return
        }
        val request = requests[query] ?: run {
            val startedIn = epoch
            val pending = scope.async(start = CoroutineStart.LAZY) {
                val bills = wallet.bills(auth, query.month, query.type)
                ensureActive()
                if (startedIn == epoch && credentials.value == auth) {
                    store.rememberQuery(query, bills, nowMillis(), resetDetail = resetDetail)
                }
                bills
            }
            requests[query] = pending
            pending.invokeOnCompletion { if (requests[query] === pending) requests.remove(query) }
            pending.start()
            pending
        }
        publishBills(
            loading = true,
            error = null,
            bills = if (queryChanged) emptyList() else null,
        )
        selection = scope.launch {
            try {
                request.await()
                ensureActive()
                if (credentials.value == auth) publishBills(loading = false)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (credentials.value == auth) publishBills(loading = false, error = error)
            }
        }
    }

    /**
     * Loads the month that should contain a just-finished session's bill and returns the matching
     * row, so its detail page can be opened without visiting the wallet list first.
     */
    suspend fun locateSessionBill(session: SessionUi): BillUi? {
        synchronizeAccount()
        val auth = owner ?: return null
        val consumedMonth = session.consumeDate.takeIf(String::isNotBlank)
            ?.let { runCatching { parseDate(it) }.getOrNull() }
            ?.let(YearMonth::from)
        val months = listOfNotNull(consumedMonth, YearMonth.now()).map(YearMonth::toString).distinct()
        for (month in months) {
            val query = BillQueryKey(month, BillType.CONSUMPTION)
            val bills = try {
                requests[query]?.takeIf { it.isActive }?.await() ?: wallet.bills(auth, month, BillType.CONSUMPTION)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                continue
            }
            if (credentials.value != auth) return null
            store.rememberQuery(query, bills, nowMillis())
            bills.firstOrNull { it.matchesFinishedSession(session) }?.let { bill ->
                selection?.cancel()
                mutable.value = state.value.copy(month = month, type = BillType.CONSUMPTION)
                publishBills(loading = false, error = null)
                return bill
            }
        }
        return null
    }

    /** Order identifiers are authoritative; consumeDate is the fallback for rows without one. */
    private fun BillUi.matchesFinishedSession(session: SessionUi): Boolean {
        if (session.orderNo.isNotBlank() && (id == session.orderNo || orderNo == session.orderNo)) return true
        val consumedAt = session.consumeDate.takeIf(String::isNotBlank)
            ?.let { runCatching { parseDate(it) }.getOrNull() } ?: return false
        return time == consumedAt
    }

    suspend fun loadDetail(id: String, force: Boolean = false) {
        synchronizeAccount()
        val auth = owner ?: return
        val bill = store.bill(id) ?: state.value.bills.find { it.id == id } ?: return
        mutable.value = state.value.copy(detailBill = bill)
        if (!bill.detailAvailable) return
        if (!force && store.isDetailFresh(id)) return

        detailJobs[id]?.let { existing ->
            if (existing.isActive) {
                // Share the running request; a forced retry still refreshes afterwards.
                existing.await()
                if (!force) return
            }
        }

        val query = BillQueryKey(state.value.month, state.value.type)
        val expectedVersion = store.queryVersion(query)
        val target = store.bill(id) ?: bill
        val startedIn = epoch
        val pending = scope.async(start = CoroutineStart.LAZY) {
            setDetailSync(id, BillDetailState(fetching = true))
            try {
                val detail = wallet.detail(auth, target)
                ensureActive()
                if (startedIn != epoch || credentials.value != auth) return@async
                val fetchedAt = nowMillis()
                val applied = expectedVersion != null &&
                    store.applyDetail(id, detail, query, expectedVersion, fetchedAt)
                if (!applied) store.applyDetailWithoutQuery(id, detail, fetchedAt)
                publishBills()
                clearDetailSync(id)
            } catch (error: CancellationException) {
                clearDetailSync(id)
                throw error
            } catch (error: Exception) {
                if (startedIn == epoch && credentials.value == auth) {
                    setDetailSync(id, BillDetailState(error = error))
                }
            }
        }
        detailJobs[id] = pending
        pending.invokeOnCompletion { if (detailJobs[id] === pending) detailJobs.remove(id) }
        pending.start()
        pending.await()
    }
}
