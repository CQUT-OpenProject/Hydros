package org.cqutosp.hydros.data.network

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ProtocolException
import java.net.SocketTimeoutException
import javax.net.ssl.SSLException

/** GET is not proof of safety: sending an SMS is also implemented as GET. */
internal fun isReadOnlyCampusRequest(path: String, post: Boolean): Boolean = if (post) path in setOf(
    "order/tcpDevice/query/rateOrder/using", "order/tcpDevice/query/downRateResult", "order/consumeOrder/result/query",
) else path in setOf(
    "device/info/mac", "account/info", "project/info/triple", "account/wallet",
    "order/query/account/bill/list", "order/query/account/bill/detail",
)

/** Kept distinct from business error codes, even when their numeric values happen to match. */
internal class QueryHttpFailure(val status: Int, val retryAfterMillis: Long? = null) : ApiFailure(status, "HTTP query rejected")
internal fun transientHttpStatus(status: Int): Boolean = status in setOf(408, 429, 502, 503, 504)

internal const val READ_ROUTE_DEADLINE_MILLIS = 10_000L
/** Default attempt, same-network retry, cellular acquisition, and one cellular attempt. */
internal const val READ_QUERY_RECOVERY_MILLIS = 24_000L

/** One budget counts every actual request, including Wi-Fi-to-cellular fallback. No nested retry multiplication. */
internal class QueryRetryBudget(private val limit: Int = 3) {
    var attempts: Int = 0
        private set
    private var lastFailure: Exception? = null
    var routeExhausted: Boolean = false
    val available: Boolean get() = attempts < limit
    /** True when this attempt can still be followed by a cellular request. */
    val fallbackRoom: Boolean get() = attempts < limit - 1

    suspend fun <T> attempt(block: suspend () -> T): T {
        if (!available) throw checkNotNull(lastFailure)
        attempts++
        return try { block() }
        catch (error: CancellationException) { throw error }
        catch (error: Exception) { lastFailure = error; throw error }
    }
}
private fun retryDelay(error: Exception, attempts: Int, routeExhausted: Boolean = false): Long? = when {
    routeExhausted -> null
    else -> retryDelayFor(error, attempts)
}
private fun retryDelayFor(error: Exception, attempts: Int): Long? = when (error) {
    is QueryHttpFailure -> when {
        !transientHttpStatus(error.status) -> null
        error.status == 429 && error.retryAfterMillis == null -> null
        (error.retryAfterMillis ?: 0) > 3_000 -> null
        else -> maxOf(700L * attempts.coerceAtMost(2), error.retryAfterMillis ?: 0)
    }
    is NetworkFailure -> if (error.cause is Exception) retryDelay(error.cause as Exception, attempts) else 700L * attempts.coerceAtMost(2)
    is SSLException, is ProtocolException -> null
    is SocketTimeoutException -> 700L * attempts.coerceAtMost(2)
    is InterruptedIOException -> null
    is IOException -> 700L * attempts.coerceAtMost(2)
    else -> null
}
private data class QueryResult<T>(val value: T)

/** Only callers that have positively identified a read may use this policy. */
internal suspend fun <T> retryReadQuery(block: suspend (QueryRetryBudget) -> T): T {
    val budget = QueryRetryBudget()
    val result = withTimeoutOrNull<QueryResult<T>>(READ_QUERY_RECOVERY_MILLIS) {
        var rounds = 0
        while (true) {
            rounds++
            try { return@withTimeoutOrNull QueryResult(block(budget)) }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                val backoff = retryDelay(error, budget.attempts.coerceAtLeast(1), budget.routeExhausted)
                if (rounds >= 3 || !budget.available || backoff == null) throw error
                delay(backoff)
            }
        }
        @Suppress("UNREACHABLE_CODE") error("unreachable")
    } ?: throw SocketTimeoutException("Read query recovery deadline exceeded")
    return result.value
}
