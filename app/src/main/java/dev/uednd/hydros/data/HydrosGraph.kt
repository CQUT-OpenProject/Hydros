package dev.uednd.hydros.data

import android.app.Application
import dev.uednd.hydros.BuildConfig
import dev.uednd.hydros.data.ble.AndroidNearbyScanner
import dev.uednd.hydros.data.ble.NearbyScanner
import dev.uednd.hydros.data.ble.NoNearbyScanner
import dev.uednd.hydros.data.network.ApiFailure
import dev.uednd.hydros.data.network.CampusApiFactory
import dev.uednd.hydros.data.network.HttpCampusTransport
import dev.uednd.hydros.data.network.AndroidNetworkAccess
import dev.uednd.hydros.data.network.NetworkRecovery
import dev.uednd.hydros.data.network.MqttOrderPush
import dev.uednd.hydros.data.network.NoOrderPush
import dev.uednd.hydros.data.network.OrderPush
import dev.uednd.hydros.data.repository.*
import dev.uednd.hydros.data.storage.HydrosStore
import dev.uednd.hydros.data.storage.PreferencesHydrosStore
import dev.uednd.hydros.data.storage.AppDataControl
import dev.uednd.hydros.data.storage.AndroidAppDataControl
import dev.uednd.hydros.service.SessionWatchService
import dev.uednd.hydros.data.update.AppInstaller
import dev.uednd.hydros.data.update.AppUpdateSource
import dev.uednd.hydros.data.update.GitHubAppUpdates
import dev.uednd.hydros.data.update.AndroidUpdateManager
import dev.uednd.hydros.data.analytics.AnalyticsTracker
import dev.uednd.hydros.data.analytics.CrashReporter
import dev.uednd.hydros.data.analytics.FirebaseAnalyticsTracker
import dev.uednd.hydros.data.analytics.NoOpAnalyticsTracker
import dev.uednd.hydros.data.analytics.ResultCode
import dev.uednd.hydros.data.analytics.failureCategory
import dev.uednd.hydros.data.analytics.toRecoveryStage
import com.google.firebase.analytics.FirebaseAnalytics
import androidx.camera.camera2.Camera2Config
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraXConfig
import dev.uednd.hydros.ui.scanner.preloadScanner
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
    val analytics: AnalyticsTracker = NoOpAnalyticsTracker,
) {
    private val restoration = SessionRestoration(auth, sessions)
    suspend fun restore() = restoration.restore()
    fun sessionMonitor() = SessionMonitor(auth.credentials, sessions, orderPush(),
        onRecoveryFailure = { stage, error ->
            stage.toRecoveryStage()?.let { recoveryStage ->
                analytics.sessionRecoveryResult(recoveryStage, ResultCode.FAILURE,
                    sessions.session.value?.recoveryReason, failureCategory(error))
            }
        })
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
        // Manifest defaults are on; gate immediately until the persisted choice is read.
        dependencies.analytics.setCollectionEnabled(false)
        CrashReporter.setCollectionEnabled(false)
        restoreTelemetryConsent()
        warmUpScanner()
    }

    /**
     * Collection follows the manifest default until onboarding and the analytics toggle are applied.
     * The ViewModel reapplies the same choice after its state load and on every user change.
     */
    private fun restoreTelemetryConsent() {
        dependencies.scope.launch {
            val enabled = runCatching {
                dependencies.store.onboardingCompleted() && dependencies.store.analyticsEnabled()
            }.getOrDefault(false)
            dependencies.analytics.setCollectionEnabled(enabled)
            CrashReporter.setCollectionEnabled(enabled)
        }
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
    val analytics: AnalyticsTracker = if (BuildConfig.DEBUG) NoOpAnalyticsTracker else FirebaseAnalyticsTracker(FirebaseAnalytics.getInstance(app))
    val operation = Mutex()
    lateinit var auth: RealAuthRepository
    lateinit var transport: HttpCampusTransport
    val recovery = NetworkRecovery(AndroidNetworkAccess(app, BuildConfig.CAMPUS_BASE_URL), diagnose = {
        android.util.Log.i("HydrosNetwork", "path=${it.path} stage=${it.stage} network=${if (it.cellular) "cellular" else "default"} elapsedMs=${it.elapsedMillis} http=${it.httpCode} business=${it.businessCode} failure=${it.failure ?: "none"}")
    }, onCellularSuccess = { transport.reportRecovery(it) })
    transport = HttpCampusTransport(CampusApiFactory.create(BuildConfig.CAMPUS_BASE_URL),
        isCurrent = { auth.credentials.value == it }, recovery = recovery, onExpired = { auth.expire(it) },
        onDiagnostic = { operation, failure, httpClass, result -> analytics.networkFailure(operation, failure, httpClass, result) })
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
        analytics = analytics,
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
