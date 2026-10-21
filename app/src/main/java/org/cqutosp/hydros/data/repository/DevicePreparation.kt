package org.cqutosp.hydros.data.repository

import org.cqutosp.hydros.data.network.ApiFailure
import org.cqutosp.hydros.data.network.Credentials
import org.cqutosp.hydros.data.storage.HydrosStore
import org.cqutosp.hydros.ui.state.ConnectionStage
import org.cqutosp.hydros.ui.state.DeviceUi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class DevicePreparationState(
    val device: DeviceUi? = null,
    val stage: ConnectionStage = ConnectionStage.IDLE,
    val recentDeviceIds: List<String> = emptyList(),
    val error: Exception? = null,
    val message: String? = null,
)

/** Owns a preparation until it is cancelled or handed to a durable session. */
class DevicePreparation(
    private val auth: AuthRepository,
    private val devices: DeviceRepository,
    private val sessions: SessionRepository,
    private val store: HydrosStore,
    private val scope: CoroutineScope,
    private val ensureWatch: () -> Unit = {},
) {
    private class Attempt(val auth: Credentials)

    private val mutable = MutableStateFlow(DevicePreparationState())
    val state = mutable.asStateFlow()
    private var attempt: Attempt? = null
    private var job: Job? = null

    init {
        scope.launch {
            auth.credentials.collect { currentAuth ->
                val owner = attempt ?: return@collect
                if (owner.auth != currentAuth) cancel()
            }
        }
    }

    fun prepare(device: DeviceUi) {
        val credentials = auth.credentials.value ?: return
        if (sessions.session.value != null) return

        val current = state.value
        if (current.device?.id == device.id) {
            if (current.stage == ConnectionStage.CONNECTING || current.stage == ConnectionStage.READY) return
        }

        val previous = job
        previous?.cancel()
        val next = Attempt(credentials)
        attempt = next
        mutable.value = DevicePreparationState(device, ConnectionStage.CONNECTING,
            message = "正在获取设备信息并连接，暂时失败时会自动重试…")

        job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                previous?.join()
                ensureActive()
                if (attempt !== next || auth.credentials.value != credentials) return@launch
                if (sessions.session.value != null) {
                    mutable.value = DevicePreparationState()
                    return@launch
                }
                val ready = devices.prepare(credentials, device)
                val saved = store.account(credentials.key)
                ensureActive()
                if (attempt !== next || auth.credentials.value != credentials) return@launch

                mutable.value = DevicePreparationState(ready, ConnectionStage.READY, saved.recent)
                // Keep ownership until cancellation; a completed coroutine cannot clean up later.
                awaitCancellation()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (attempt === next) {
                    mutable.value = DevicePreparationState(device, ConnectionStage.FAILED, error = error)
                }
            } finally {
                withContext(NonCancellable) {
                    // Even a cancelled replacement must wait for its predecessor's cleanup.
                    previous?.join()
                    devices.release { sessions.session.value != null }
                }
            }
        }
    }

    fun cancel() {
        attempt = null
        mutable.value = DevicePreparationState()
        job?.cancel()
    }

    suspend fun startSession(deviceId: String) {
        val owner = attempt ?: throw ApiFailure(null, "请先连接设备")
        val ready = state.value
        val device = ready.device ?: throw ApiFailure(null, "请先连接设备")
        if (device.id != deviceId) throw ApiFailure(null, "请先连接设备")
        if (ready.stage != ConnectionStage.READY) throw ApiFailure(null, "请先连接设备")
        if (auth.credentials.value != owner.auth) throw ApiFailure(null, "请重新连接设备")

        ensureWatch()
        sessions.start(owner.auth, device)
        if (attempt === owner) cancel()
    }
}
