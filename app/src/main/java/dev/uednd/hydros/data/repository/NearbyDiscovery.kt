package dev.uednd.hydros.data.repository

import dev.uednd.hydros.data.ble.MacFormat
import dev.uednd.hydros.data.ble.NearbyScanner
import dev.uednd.hydros.data.network.Credentials
import dev.uednd.hydros.ui.state.DeviceUi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

data class NearbyDiscoveryState(
    val searching: Boolean = false,
    val hasSearched: Boolean = false,
    val completed: Boolean = false,
    val devices: List<DeviceUi> = emptyList(),
    val error: Exception? = null,
)

/** Main-thread owner of one scan and all its registration queries. */
class NearbyDiscovery(
    private val scanner: NearbyScanner,
    private val devices: DeviceRepository,
    private val credentials: StateFlow<Credentials?>,
    private val scope: CoroutineScope,
    private val scanWindowMillis: Long = 15_000,
) {
    private val mutable = MutableStateFlow(NearbyDiscoveryState())
    val state = mutable.asStateFlow()
    private var attempt: Any? = null
    private var job: Job? = null

    init {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            credentials.collect { reset() }
        }
    }

    fun start() {
        if (state.value.searching) return
        val auth = credentials.value ?: return
        val owner = Any()
        attempt = owner
        job?.cancel()
        mutable.value = NearbyDiscoveryState(searching = true, hasSearched = true)
        fun ownsAttempt() = attempt === owner && credentials.value == auth

        job = scope.launch {
            val seen = mutableSetOf<String>()
            try {
                coroutineScope {
                    val queries = this
                    // Allow weak or intermittent broadcasts time to arrive; accepted queries may finish afterwards.
                    withTimeoutOrNull(scanWindowMillis) {
                        scanner.advertisements().collect { advertisement ->
                            if (!ownsAttempt() || !advertisement.name.contains(DEVICE_BROADCAST_PREFIX, ignoreCase = true)) return@collect
                            val mac = MacFormat.normalize(advertisement.mac) ?: return@collect
                            if (!seen.add(mac)) return@collect
                            queries.launch {
                                try {
                                    val device = devices.find(auth, mac, advertisement.mac, advertisement.rssi)
                                    ensureActive()
                                    if (ownsAttempt()) mutable.value = mutable.value.copy(
                                        devices = mutable.value.devices.filterNot { it.id == device.id } + device,
                                    )
                                } catch (error: CancellationException) {
                                    throw error
                                } catch (error: Exception) {
                                    if (ownsAttempt()) mutable.value = mutable.value.copy(error = error)
                                }
                            }
                        }
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (ownsAttempt()) mutable.value = mutable.value.copy(error = error)
            } finally {
                if (ownsAttempt()) mutable.value = mutable.value.copy(searching = false, completed = true)
            }
        }
    }

    fun stop() {
        attempt = null
        job?.cancel()
        job = null
        mutable.value = mutable.value.copy(searching = false, completed = false)
    }

    private fun reset() {
        stop()
        mutable.value = NearbyDiscoveryState()
    }
}
