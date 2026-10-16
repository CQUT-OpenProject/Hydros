package dev.uednd.hydros.data.repository

import dev.uednd.hydros.data.network.Credentials
import dev.uednd.hydros.ui.state.BillType
import dev.uednd.hydros.ui.state.BillUi
import java.time.YearMonth
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class BillHistoryState(
    val month: String = YearMonth.now().toString(),
    val type: BillType = BillType.CONSUMPTION,
    val bills: List<BillUi> = emptyList(),
    val loading: Boolean = false,
    val error: Exception? = null,
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
    private data class Query(val month: String, val type: BillType)
    private data class CachedBills(val bills: List<BillUi>, val loadedAt: Long, val version: Any = Any())

    private val mutable = MutableStateFlow(BillHistoryState())
    val state = mutable.asStateFlow()
    // Memory only: five minutes from a successful response, at most 24 month/type queries.
    private val cache = linkedMapOf<Query, CachedBills>()
    private val requests = mutableMapOf<Query, Deferred<List<BillUi>>>()
    private val details = mutableSetOf<Deferred<Unit>>()
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
        details.toList().forEach { it.cancel() }
        details.clear()
        cache.clear()
        mutable.value = BillHistoryState()
    }

    fun selectMonth(month: String) {
        synchronizeAccount()
        load(Query(month, state.value.type))
    }

    fun selectType(type: BillType) {
        synchronizeAccount()
        if (state.value.type != type) load(Query(state.value.month, type))
    }

    fun refresh() {
        synchronizeAccount()
        load(Query(state.value.month, state.value.type), force = true)
    }

    private fun load(query: Query, force: Boolean = false) {
        selection?.cancel()
        val auth = owner
        mutable.value = state.value.copy(month = query.month, type = query.type)
        if (auth == null) return
        if (force) {
            cache.remove(query)
            requests.remove(query)?.cancel()
        }
        cache[query]?.let { cached ->
            if (nowMillis() - cached.loadedAt in 0 until 5 * 60_000L) {
                mutable.value = state.value.copy(bills = cached.bills, loading = false, error = null)
                return
            }
            cache.remove(query)
        }
        val request = requests[query] ?: run {
            val startedIn = epoch
            val pending = scope.async(start = CoroutineStart.LAZY) {
                val bills = wallet.bills(auth, query.month, query.type)
                ensureActive()
                if (startedIn == epoch && credentials.value == auth) {
                    cache[query] = CachedBills(bills, nowMillis())
                    while (cache.size > 24) cache.remove(cache.keys.first())
                }
                bills
            }
            requests[query] = pending
            pending.invokeOnCompletion { if (requests[query] === pending) requests.remove(query) }
            pending.start()
            pending
        }
        mutable.value = state.value.copy(bills = emptyList(), loading = true, error = null)
        selection = scope.launch {
            try {
                val bills = request.await()
                ensureActive()
                if (credentials.value == auth) mutable.value = state.value.copy(bills = bills, loading = false)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (credentials.value == auth) mutable.value = state.value.copy(error = error, loading = false)
            }
        }
    }

    suspend fun loadDetail(id: String) {
        synchronizeAccount()
        val auth = owner ?: return
        val bill = state.value.bills.find { it.id == id && it.detailAvailable } ?: return
        val query = Query(state.value.month, state.value.type)
        val version = cache[query]?.version ?: return
        val startedIn = epoch
        val pending = scope.async(start = CoroutineStart.LAZY) {
            val detail = wallet.detail(auth, bill)
            ensureActive()
            val current = cache[query]
            if (startedIn == epoch && credentials.value == auth && current?.version === version) {
                // Detail updates preserve the query version, so concurrent details can both be retained.
                val updated = current.copy(bills = current.bills.map { if (it.id == id) detail else it })
                cache[query] = updated
                if (state.value.month == query.month && state.value.type == query.type) {
                    mutable.value = state.value.copy(bills = updated.bills)
                }
            }
        }
        details += pending
        pending.invokeOnCompletion { details.remove(pending) }
        try {
            pending.await()
        } finally {
            pending.cancel()
        }
    }
}
