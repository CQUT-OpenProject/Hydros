package dev.uednd.hydros.data.network

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull
import java.io.EOFException
import java.io.IOException
import java.net.ProtocolException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

interface CellularLease : AutoCloseable {
    val api: CampusApi
}

interface NetworkAccess {
    /** Identity of the default Wi-Fi network; null means cellular recovery is unnecessary. */
    fun wifiNetwork(): Long?
    suspend fun cellular(): CellularLease
}

class NetworkFailure(override val message: String, cause: Throwable? = null) : IOException(message, cause)

data class NetworkAttempt(val path: String, val cellular: Boolean, val elapsedMillis: Long, val failure: String?,
    val httpCode: Int?, val businessCode: Int?, val stage: String = "request")

/** Retry policy belongs to the operation, including read-only operations implemented as POST. */
class NetworkRecovery(
    private val access: NetworkAccess,
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
    private val diagnose: (NetworkAttempt) -> Unit = {},
    private val queryTimeoutMillis: Long = READ_ROUTE_DEADLINE_MILLIS,
    private val onCellularSuccess: (String) -> Unit = {},
) {
    private val active = MutableStateFlow(0)
    val cellularRequests = active.asStateFlow()
    private data class Preference(val wifi: Long?, val at: Long)
    @Volatile private var preference: Preference? = null

    suspend fun <T : Any> execute(
        api: CampusApi,
        path: String,
        post: Boolean,
        request: suspend (CampusApi) -> T,
        canFallback: () -> Boolean = { true },
        canArmDeadline: () -> Boolean = { true },
        onRouteExhausted: () -> Unit = {},
    ): T {
        val readOnly = isReadOnlyCampusRequest(path, post)
        val wifi = access.wifiNetwork()
        val preferred = preference?.let { wifi != null && it.wifi == wifi && now() - it.at in 0..60_000 } == true
        suspend fun attempt(selected: CampusApi, cellular: Boolean, deadline: Boolean): T {
            val started = now()
            var failure: String? = null
            var response: retrofit2.Response<*>? = null
            try {
                val result = if (deadline) withTimeoutOrNull(queryTimeoutMillis) { request(selected) }
                    ?: throw SocketTimeoutException("Query deadline exceeded") else request(selected)
                response = result as? retrofit2.Response<*>
                return result
            } catch (error: Exception) {
                failure = error.javaClass.simpleName
                throw error
            } finally {
                // Never include URLs, query strings, bodies, exception messages, or credentials.
                diagnose(NetworkAttempt(path, cellular, now() - started, failure,
                    response?.code(), (response?.body() as? ApiResponse<*>)?.errorCode))
            }
        }
        fun delivered(result: T): T {
            preference = null
            return result
        }
        suspend fun viaCellular(deadline: Boolean): T {
            active.update { it + 1 }
            try {
                val started = now()
                val lease = try { access.cellular() } catch (error: Exception) {
                    diagnose(NetworkAttempt(path, true, now() - started, error.javaClass.simpleName,
                        null, null, "acquire"))
                    preference = null
                    throw error
                }
                try {
                    val result = attempt(lease.api, true, deadline)
                    onCellularSuccess(path)
                    if (access.wifiNetwork() == wifi) preference = Preference(wifi, now())
                    return result
                } catch (error: IOException) {
                    preference = null
                    throw NetworkFailure("移动网络请求失败，请检查移动数据后重新查询", error)
                } finally { lease.close() }
            } finally { active.update { it - 1 } }
        }
        // Acquisition never sent a request, so that failure keeps the default-route error.
        // A failed cellular request still reports the mobile-network result.
        suspend fun recover(original: IOException, sameRouteRetried: Boolean): T = try {
            viaCellular(deadline = true)
        } catch (error: CancellationException) {
            throw error
        } catch (error: NetworkFailure) {
            // A same-network retry plus cellular is the whole cycle. Do not start another one.
            if (sameRouteRetried) onRouteExhausted()
            if (error.cause != null) throw error
            throw original
        }
        // Reads always try the current default network. The preference is only safe for a command,
        // which cannot be replayed once it has been sent on the wrong route.
        if (preferred && !readOnly) return viaCellular(deadline = false)
        try {
            // The short deadline exists only to leave time for cellular after a stalled Wi-Fi read.
            return delivered(attempt(api, false, deadline = readOnly && wifi != null && canArmDeadline()))
        } catch (error: IOException) {
            if (!readOnly) throw error
            when (val route = sameRoute(error)) {
                SameRoute.None -> Unit
                SameRoute.Only, SameRoute.ThenCellular -> if (canFallback()) {
                    try {
                        return delivered(attempt(api, false, deadline = false))
                    } catch (again: IOException) {
                        if (route == SameRoute.Only || wifi == null || !recoverable(again) || !canFallback()) throw again
                        return recover(again, sameRouteRetried = true)
                    }
                }
            }
            if (wifi == null || !recoverable(error) || !canFallback()) throw error
            return recover(error, sameRouteRetried = false)
        }
    }

    private fun sameRoute(error: IOException) = when (error) {
        is SSLException, is ProtocolException -> SameRoute.Only
        is EOFException, is SocketException -> SameRoute.ThenCellular
        else -> SameRoute.None
    }

    private fun recoverable(error: IOException) = error is SocketException || error is SocketTimeoutException ||
        error is UnknownHostException || error is EOFException

    private enum class SameRoute { None, Only, ThenCellular }
}
