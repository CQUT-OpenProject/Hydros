package org.cqutosp.hydros.data

import android.app.Application
import org.cqutosp.hydros.BuildConfig
import org.cqutosp.hydros.data.ble.AndroidNearbyScanner
import org.cqutosp.hydros.data.ble.NearbyScanner
import org.cqutosp.hydros.data.ble.NoNearbyScanner
import org.cqutosp.hydros.data.network.ApiFailure
import org.cqutosp.hydros.data.network.CampusApiFactory
import org.cqutosp.hydros.data.network.HttpCampusTransport
import org.cqutosp.hydros.data.network.AndroidNetworkAccess
import org.cqutosp.hydros.data.network.NetworkRecovery
import org.cqutosp.hydros.data.network.MqttOrderPush
import org.cqutosp.hydros.data.network.NoOrderPush
import org.cqutosp.hydros.data.network.OrderPush
import org.cqutosp.hydros.data.repository.*
import org.cqutosp.hydros.data.storage.HydrosStore
import org.cqutosp.hydros.data.storage.PreferencesHydrosStore
import org.cqutosp.hydros.data.storage.AppDataControl
import org.cqutosp.hydros.data.storage.AndroidAppDataControl
import org.cqutosp.hydros.service.SessionWatchService
import org.cqutosp.hydros.data.update.AppInstaller
import org.cqutosp.hydros.data.update.AppUpdateSource
import org.cqutosp.hydros.data.update.GitHubAppUpdates
import org.cqutosp.hydros.data.update.AndroidUpdateManager
import androidx.camera.camera2.Camera2Config
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraXConfig
import org.cqutosp.hydros.ui.scanner.preloadScanner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex

class HydrosDependencies(
    val auth: AuthRepository,
    val account: AccountRepository,
    val wallet: WalletRepository,
    val devices: DeviceRepository,
    val sessions: SessionRepository,
    val store: HydrosStore,
    val scope: CoroutineScope,
    val ensureWatch: () -> Unit = {},
    val nearbyScanner: NearbyScanner = NoNearbyScanner,
    private val orderPush: () -> OrderPush = { NoOrderPush },
    val networkRecovery: NetworkRecovery? = null,
) {
    private val restoration = SessionRestoration(auth, sessions)
    suspend fun restore() = restoration.restore()
    fun sessionMonitor() = SessionMonitor(auth.credentials, sessions, orderPush())
}

open class HydrosApplication : Application(), CameraXConfig.Provider {
    lateinit var dependencies: HydrosDependencies
        internal set
    open val appUpdates: AppUpdateSource by lazy { GitHubAppUpdates() }
    open val appInstaller: AppInstaller by lazy { AndroidUpdateManager.get(this) }
    open val appData: AppDataControl by lazy { AndroidAppDataControl(this) }

    override fun getCameraXConfig(): CameraXConfig =
        CameraXConfig.Builder.fromConfig(Camera2Config.defaultConfig())
            .setAvailableCamerasLimiter(CameraSelector.DEFAULT_BACK_CAMERA)
            .build()

    protected open fun buildDependencies(): HydrosDependencies = createDependencies(this)

    override fun onCreate() {
        super.onCreate()
        dependencies = buildDependencies()
        warmUpScanner()
    }

    protected open fun warmUpScanner() {
        dependencies.scope.launch(Dispatchers.Default) {
            preloadScanner(this@HydrosApplication)
        }
    }
}

fun createDependencies(app: Application): HydrosDependencies {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val store = PreferencesHydrosStore(app)
    val operation = Mutex()
    lateinit var auth: RealAuthRepository
    val recovery = NetworkRecovery(AndroidNetworkAccess(app, BuildConfig.CAMPUS_BASE_URL), diagnose = {
        android.util.Log.i("HydrosNetwork", "path=${it.path} stage=${it.stage} network=${if (it.cellular) "cellular" else "default"} elapsedMs=${it.elapsedMillis} http=${it.httpCode} business=${it.businessCode} failure=${it.failure ?: "none"}")
    })
    val transport = HttpCampusTransport(CampusApiFactory.create(BuildConfig.CAMPUS_BASE_URL),
        isCurrent = { auth.credentials.value == it }, recovery = recovery, onExpired = { auth.expire(it) })
    val sessions = RealSessionRepository(transport, store, operation)
    auth = RealAuthRepository(transport, store) { candidate ->
        val pending = sessions.session.value
        if (pending != null && pending.ownerKey != candidate.key) {
            throw ApiFailure(null, "请登录原账户先处理当前设备会话")
        }
    }

    return HydrosDependencies(
        auth = auth,
        account = RealAccountRepository(transport),
        wallet = RealWalletRepository(transport),
        devices = RealDeviceRepository(transport, store, operation),
        sessions = sessions,
        store = store,
        scope = scope,
        ensureWatch = { SessionWatchService.start(app) },
        nearbyScanner = AndroidNearbyScanner(app),
        orderPush = { MqttOrderPush() },
        networkRecovery = recovery,
    )
}

suspend fun safely(action: suspend () -> Unit) {
    try {
        action()
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        // Durable session state remains available for retry.
    }
}
