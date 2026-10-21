package org.cqutosp.hydros.data.repository

import org.cqutosp.hydros.ui.state.BillType
import org.cqutosp.hydros.ui.state.BillUi

data class BillQueryKey(val month: String, val type: BillType)

internal data class BillEntity(
    val bill: BillUi,
    val detailFetchedAt: Long? = null,
)

internal data class QueryCache(
    val billIds: List<String>,
    val loadedAt: Long,
    val version: Any = Any(),
)

/**
 * Account-scoped bill entities and month/type list query metadata.
 *
 * Entities deliberately outlive query entries: a bill opened from a detail route must stay
 * available after its month query was evicted or expired.
 */
internal class BillEntityStore(private val nowMillis: () -> Long) {
    private val entities = linkedMapOf<String, BillEntity>()
    private val queries = linkedMapOf<BillQueryKey, QueryCache>()

    fun clear() {
        entities.clear()
        queries.clear()
    }

    fun bill(id: String): BillUi? = entities[id]?.bill

    fun queryVersion(query: BillQueryKey): Any? = queries[query]?.version

    fun isQueryFresh(query: BillQueryKey): Boolean {
        val loadedAt = queries[query]?.loadedAt ?: return false
        return nowMillis() - loadedAt in 0 until QUERY_TTL_MS
    }

    fun projectedBills(query: BillQueryKey): List<BillUi>? {
        val ids = queries[query]?.billIds ?: return null
        return ids.mapNotNull { entities[it]?.bill }
    }

    fun rememberQuery(query: BillQueryKey, rows: List<BillUi>, loadedAt: Long, resetDetail: Boolean = false): Any {
        val version = Any()
        rows.forEach { incoming ->
            val existing = entities[incoming.id]
            // A forced list refresh drops all detail-derived fields together, so the entity never
            // keeps half of a stale detail.
            val bill = if (resetDetail) incoming else incoming.copy(
                durationText = incoming.durationText ?: existing?.bill?.durationText,
                orderNo = incoming.orderNo ?: existing?.bill?.orderNo,
            )
            entities[incoming.id] = BillEntity(
                bill = bill,
                detailFetchedAt = if (resetDetail) null else existing?.detailFetchedAt,
            )
        }
        queries[query] = QueryCache(rows.map { it.id }, loadedAt, version)
        trimQueries()
        return version
    }

    fun removeQuery(query: BillQueryKey) {
        queries.remove(query)
    }

    fun isDetailFresh(id: String): Boolean {
        val entity = entities[id] ?: return false
        val fetchedAt = entity.detailFetchedAt ?: return false
        // Settlement may finalize the duration a little after the bill appears; a consumption
        // detail without one is incomplete, so reopening the page queries again.
        if (!entity.bill.recharge && entity.bill.durationText == null) return false
        return nowMillis() - fetchedAt in 0 until DETAIL_TTL_MS
    }

    fun applyDetail(id: String, detail: BillUi, query: BillQueryKey, expectedVersion: Any, fetchedAt: Long): Boolean {
        val cache = queries[query] ?: return false
        if (cache.version !== expectedVersion) {
            // The list was refreshed while this detail was in flight: keep the newer list fields
            // and take only what the detail endpoint adds.
            val current = entities[id] ?: return false
            entities[id] = BillEntity(
                bill = current.bill.copy(
                    durationText = detail.durationText ?: current.bill.durationText,
                    orderNo = detail.orderNo ?: current.bill.orderNo,
                ),
                detailFetchedAt = fetchedAt,
            )
            return true
        }
        entities[id] = BillEntity(detail, detailFetchedAt = fetchedAt)
        return true
    }

    fun applyDetailWithoutQuery(id: String, detail: BillUi, fetchedAt: Long) {
        entities[id] = BillEntity(detail, detailFetchedAt = fetchedAt)
    }

    /** Evicts in insertion order; entities are intentionally retained for detail lookups. */
    private fun trimQueries() {
        while (queries.size > MAX_QUERIES) queries.remove(queries.keys.first())
    }

    companion object {
        const val QUERY_TTL_MS = 5 * 60_000L
        const val DETAIL_TTL_MS = 5 * 60_000L
        private const val MAX_QUERIES = 24
    }
}
