package dev.uednd.hydros.data.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.ConnectionPool
import java.net.Proxy
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resumeWithException

/** Each lease owns its callback, DNS, sockets, and connection pool. No process-wide binding. */
class AndroidNetworkAccess(context: Context, private val baseUrl: String) : NetworkAccess {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)

    override fun wifiNetwork(): Long? = connectivity.activeNetwork?.takeIf {
        connectivity.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
    }?.networkHandle

    override suspend fun cellular(): CellularLease {
        var acquired: CellularLease? = null
        try {
            return withTimeoutOrNull(3_000) { acquire().also { acquired = it } }
                ?: throw NetworkFailure("移动网络不可用，请开启移动数据后重新查询")
        } catch (error: Exception) {
            // A timeout may win just after acquire returned but before its result leaves this scope.
            acquired?.close()
            throw error
        }
    }

    private suspend fun acquire(): CellularLease = suspendCancellableCoroutine { continuation ->
        val completed = AtomicBoolean(false)
        val released = AtomicBoolean(false)
        lateinit var callback: ConnectivityManager.NetworkCallback
        fun release() {
            if (released.compareAndSet(false, true)) runCatching { connectivity.unregisterNetworkCallback(callback) }
        }
        fun unavailable() {
            if (completed.compareAndSet(false, true)) {
                release()
                continuation.resumeWithException(NetworkFailure("移动网络不可用，请开启移动数据后重新查询"))
            }
        }
        callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) return
                if (!completed.compareAndSet(false, true)) return
                val client = CampusApiFactory.client().newBuilder()
                    .socketFactory(network.socketFactory)
                    .proxy(Proxy.NO_PROXY)
                    .dns { host -> network.getAllByName(host).toList() }
                    .connectionPool(ConnectionPool())
                    .build()
                val lease = object : CellularLease {
                    private val closed = AtomicBoolean(false)
                    override val api = CampusApiFactory.create(baseUrl, client)
                    override fun close() {
                        if (!closed.compareAndSet(false, true)) return
                        client.connectionPool.evictAll()
                        client.dispatcher.executorService.shutdown()
                        release()
                    }
                }
                continuation.resume(lease) { _, value, _ -> value.close() }
            }
            override fun onUnavailable() = unavailable()
            override fun onLost(network: Network) {
                // A candidate can be withdrawn before validation. Keep waiting for another
                // network, onUnavailable, or the request timeout. A delivered network that
                // disappears fails through its own socket.
                if (completed.get()) return
            }
        }
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        try {
            connectivity.requestNetwork(request, callback, 3_000)
            continuation.invokeOnCancellation { completed.set(true); release() }
        } catch (_: RuntimeException) { unavailable() }
    }
}
