package dev.uednd.hydros.data.repository

import dev.uednd.hydros.data.network.ApiFailure
import dev.uednd.hydros.data.network.Credentials
import dev.uednd.hydros.data.network.CloudDeviceOffline
import dev.uednd.hydros.data.storage.HydrosStore
import dev.uednd.hydros.ui.state.ConnectionStage
import dev.uednd.hydros.ui.state.DeviceUi
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

    fun prepare(device: DeviceUi) = prepare(device, false)

    private fun prepare(device: DeviceUi, forceBle: Boolean) {
        val credentials = auth.credentials.value ?: return
        if (sessions.session.value != null) return

        val current = state.value
        if (!forceBle && current.device?.id == device.id) {
            if (current.stage == ConnectionStage.CONNECTING || current.stage == ConnectionStage.READY) return
        }

        val previous = job
        previous?.cancel()
        val next = Attempt(credentials)
        attempt = next
        mutable.value = DevicePreparationState(device, ConnectionStage.CONNECTING,
            message = if (forceBle) "设备离线，正在尝试蓝牙连接…" else null)

        job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                previous?.join()
                ensureActive()
                if (attempt !== next || auth.credentials.value != credentials) return@launch
                if (sessions.session.value != null) {
                    mutable.value = DevicePreparationState()
                    return@launch
                }
                val ready = devices.prepare(credentials, device, preferBle = forceBle)
                val saved = store.account(credentials.key)
                ensureActive()
                if (attempt !== next || auth.credentials.value != credentials) return@launch

                mutable.value = DevicePreparationState(ready, ConnectionStage.READY, saved.recent,
                    message = if (forceBle) "已切换蓝牙，请再次长按以开始使用" else null)
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
        try {
            sessions.start(owner.auth, device)
        } catch (error: CloudDeviceOffline) {
            val pending = store.account(owner.auth.key).session
            if (attempt !== owner || auth.credentials.value != owner.auth || sessions.session.value != null ||
                pending != null) throw error
            // Preparing BLE cannot open a valve. The user must hold the start button again.
            prepare(device, true)
            return
        }
        if (attempt === owner) cancel()
    }
}
