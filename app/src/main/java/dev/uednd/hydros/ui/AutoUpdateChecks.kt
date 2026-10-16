package dev.uednd.hydros.ui

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import dev.uednd.hydros.ui.state.AppInfoViewModel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import java.time.Duration
import java.time.Instant

/** Foreground/online checks, including reconnect and retry after failure. */
@Composable
internal fun AutoUpdateChecks(model: AppInfoViewModel) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(model, lifecycle, context) {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            var sawOffline = false
            callbackFlow {
                fun online() = connectivity.getNetworkCapabilities(connectivity.activeNetwork)
                    ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
                val callback = object : ConnectivityManager.NetworkCallback() {
                    override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) { trySend(online()) }
                    override fun onLost(network: Network) { trySend(online()) }
                }
                connectivity.registerDefaultNetworkCallback(callback)
                trySend(online())
                awaitClose { connectivity.unregisterNetworkCallback(callback) }
            }.distinctUntilChanged().collectLatest { online ->
                if (!online) sawOffline = true
                if (online) {
                    val last = model.updates.value.lastChecked
                    if (sawOffline || last == null || Duration.between(last, Instant.now()).seconds >= 300 || model.updates.value.error != null) model.checkForUpdates()
                    sawOffline = false
                    while (true) {
                        delay(60_000)
                        val state = model.updates.value
                        if (state.error != null || state.lastChecked?.let { Duration.between(it, Instant.now()).seconds >= 300 } != false) model.checkForUpdates()
                    }
                }
            }
        }
    }
}
