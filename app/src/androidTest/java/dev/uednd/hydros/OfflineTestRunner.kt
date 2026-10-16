package dev.uednd.hydros

import dev.uednd.hydros.data.storage.FeedbackSettings
import android.app.Application
import android.content.Context
import androidx.test.runner.AndroidJUnitRunner
import dev.uednd.hydros.data.HydrosApplication
import dev.uednd.hydros.data.HydrosDependencies
import dev.uednd.hydros.data.ble.*
import dev.uednd.hydros.data.network.*
import dev.uednd.hydros.data.repository.*
import dev.uednd.hydros.data.storage.*
import dev.uednd.hydros.data.update.AppUpdateSource
import dev.uednd.hydros.ui.state.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.json.*

/** Replace the app before onCreate: Activities and recreation never see a production graph. */
class OfflineTestRunner : AndroidJUnitRunner() {
    override fun newApplication(cl: ClassLoader?, className: String?, context: Context?): Application =
        super.newApplication(cl, OfflineTestApplication::class.java.name, context)
}

class OfflineTestApplication : HydrosApplication() {
    override val appUpdates = AppUpdateSource { null }
    override val appData = OfflineAppDataControl()
    override fun buildDependencies(): HydrosDependencies {
        val store = object : HydrosStore {
            var auth: Credentials? = null
            var saved = SavedAccount()
            var mode = "SYSTEM"
            var onboarding = true
            override suspend fun credentials() = auth
            override suspend fun credentials(value: Credentials?) { auth = value }
            override suspend fun account(key: String) = saved
            override suspend fun updateAccount(key: String, transform: (SavedAccount) -> SavedAccount) =
                transform(saved).also { saved = it }
            override suspend fun theme() = mode
            override suspend fun theme(value: String) { mode = value }
            private var fontScale = 1f
            override suspend fun fontSizeScale() = fontScale
            override suspend fun fontSizeScale(value: Float) { fontScale = value }
            private var feedbackSettings = FeedbackSettings()
            override suspend fun feedback() = feedbackSettings
            override suspend fun updateFeedback(transform: (FeedbackSettings) -> FeedbackSettings): FeedbackSettings =
                transform(feedbackSettings).also { feedbackSettings = it }
            override suspend fun onboardingCompleted() = onboarding
            override suspend fun onboardingCompleted(value: Boolean) { onboarding = value }
        }
        val transport = object : CampusTransport {
            fun data(value: String) = ApiResponse(success = true, data = Json.parseToJsonElement(value))
            override suspend fun get(path: String, fields: Map<String, String>, auth: Credentials?): ApiResponse<JsonElement> =
                when (path) {
                    "user/verification/code/get" -> data("null")
                    "account/info" -> data("""{"name":"测试学生"}""")
                    "project/info/triple" -> data("""{"projectName":"测试学校"}""")
                    "account/wallet" -> data("""{"money":"12.34","accountRealMoney":"10.00","accountGivenMoney":"2.34"}""")
                    "order/query/account/bill/list" -> data("[]")
                    else -> error("UI 测试未配置本地响应：$path")
                }
            override suspend fun post(path: String, fields: Map<String, String>, auth: Credentials?): ApiResponse<JsonElement> {
                if (path in setOf("user/login", "user/registerAndLogin")) {
                    val valid = fields["telephone"] == "13800138000" &&
                        if (path == "user/login") fields["password"] == passwordHash("server123") else fields["smsCode"] == "654321"
                    return if (valid) data("""{"loginCode":"test-only","userId":1,"userAccount":{"accountId":2,"projectId":3}}""")
                    else ApiResponse(success = false, errorCode = 12, errorMessage = "账号或验证码错误")
                }
                if (path in setOf("user/phone/update", "user/password/update", "user/password/forget")) return data("null")
                error("UI 测试未配置本地响应：$path")
            }
        }
        val ble = object : BlePort {
            override val ready = false
            override val connectedMac: String? = null
            override suspend fun connect(mac: String) = BleConnectResult.NoService
            override suspend fun query(): DeviceQueryState? = null
            override suspend fun writeRate(bytes: ByteArray) = false
            override suspend fun closeValve() = false
            override suspend fun collect(): ByteArray? = null
            override suspend fun writeBack(bytes: ByteArray) = false
            override fun disconnect() = Unit
        }
        val lock = Mutex()
        return HydrosDependencies(RealAuthRepository(transport, store), RealAccountRepository(transport),
            RealWalletRepository(transport), RealDeviceRepository(transport, store, ble, lock),
            RealSessionRepository(transport, store, ble, lock), store,
            CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate))
    }
}

/** Never erase real device data during instrumentation tests. */
class OfflineAppDataControl : AppDataControl {
    var clearRequests = 0
    override suspend fun sizeBytes() = 2_621_440L
    override fun clearAllData(): Boolean { clearRequests++; return true }
}
