package org.cqutosp.hydros.data.repository

import org.cqutosp.hydros.data.ble.MacFormat
import org.cqutosp.hydros.data.ble.NearbyScanFailure
import org.cqutosp.hydros.data.ble.NearbyScanner
import org.cqutosp.hydros.data.network.Credentials
import org.cqutosp.hydros.ui.state.DeviceUi
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
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull

data class NearbyDiscoveryState(
    val scanGeneration: Long = 0,
    val searching: Boolean = false,
    val hasSearched: Boolean = false,
    val completed: Boolean = false,
    val devices: List<DeviceUi> = emptyList(),
    val error: Exception? = null,
    val failedLookups: Int = 0,
    val scanFailed: Boolean = false,
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
        val generation = state.value.scanGeneration + 1
        mutable.value = NearbyDiscoveryState(scanGeneration = generation, searching = true, hasSearched = true)
        fun ownsAttempt() = attempt === owner && credentials.value == auth

        job = scope.launch {
            val seen = mutableSetOf<String>()
            // Leave network capacity available for foreground connection/account requests.
            val lookupSlots = Semaphore(4)
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
                                    // Queueing shares the lookup deadline, so a dense scan cannot add
                                    // an unbounded tail after the radio window has closed.
                                    val device = withTimeoutOrNull(DEVICE_INFO_TIMEOUT_MS) {
                                        lookupSlots.withPermit {
                                            devices.find(auth, mac, advertisement.mac, advertisement.rssi)
                                        }
                                    } ?: throw NearbyScanFailure("设备查询超时，请重新扫描")
                                    ensureActive()
                                    if (ownsAttempt()) mutable.value = mutable.value.copy(
                                        devices = mutable.value.devices.filterNot { it.id == device.id } + device,
                                    )
                                } catch (error: CancellationException) {
                                    throw error
                                } catch (error: Exception) {
                                    if (ownsAttempt()) mutable.value = mutable.value.copy(error = error, failedLookups = mutable.value.failedLookups + 1)
                                }
                            }
                        }
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (ownsAttempt()) mutable.value = mutable.value.copy(error = error, scanFailed = true)
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
        // Keep the generation monotonic across account switches: observers compare it with their
        // last reported value, which does not reset with the credentials.
        val generation = mutable.value.scanGeneration
        stop()
        mutable.value = NearbyDiscoveryState(scanGeneration = generation)
    }
}
