package dev.uednd.hydros.data.network

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull
import java.io.EOFException
import java.io.IOException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

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
    private val queryTimeoutMillis: Long = 5_000,
    private val onCellularSuccess: (String) -> Unit = {},
) {
    private val active = MutableStateFlow(0)
    val cellularRequests = active.asStateFlow()
    private data class Preference(val wifi: Long?, val at: Long)
    @Volatile private var preference: Preference? = null

    suspend fun <T : Any> execute(api: CampusApi, path: String, post: Boolean, request: suspend (CampusApi) -> T, canFallback: () -> Boolean = { true }): T {
        val readOnly = isReadOnlyCampusRequest(path, post)
        val wifi = access.wifiNetwork()
        val preferred = preference?.let { wifi != null && it.wifi == wifi && now() - it.at in 0..60_000 } == true
        suspend fun attempt(selected: CampusApi, cellular: Boolean): T {
            val started = now()
            var failure: String? = null
            var response: retrofit2.Response<*>? = null
            try {
                val result = if (readOnly) withTimeoutOrNull(queryTimeoutMillis) { request(selected) }
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
        suspend fun viaCellular(): T {
            active.update { it + 1 }
            try {
                val started = now()
                val lease = try { access.cellular() } catch (error: Exception) {
                    diagnose(NetworkAttempt(path, true, now() - started, error.javaClass.simpleName,
                        null, null, "acquire"))
                    throw error
                }
                try {
                    val result = attempt(lease.api, true)
                    onCellularSuccess(path)
                    if (access.wifiNetwork() == wifi) preference = Preference(wifi, now())
                    return result
                } catch (error: IOException) {
                    throw NetworkFailure("移动网络请求失败，请检查移动数据后重新查询", error)
                } finally { lease.close() }
            } finally { active.update { it - 1 } }
        }
        // A previously proven route is selected BEFORE sending even a non-replayable command.
        if (preferred) return viaCellular()
        try {
            return attempt(api, false)
        } catch (error: IOException) {
            if (!readOnly || wifi == null || !recoverable(error) || !canFallback()) throw error
            return viaCellular()
        }
    }

    private fun recoverable(error: IOException) = error is SocketException || error is SocketTimeoutException ||
        error is UnknownHostException || error is EOFException

}
