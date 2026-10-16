package dev.uednd.hydros.preview

import dev.uednd.hydros.data.storage.FeedbackSettings
import android.content.Context
import dev.uednd.hydros.data.HydrosDependencies
import dev.uednd.hydros.data.network.*
import dev.uednd.hydros.data.repository.*
import dev.uednd.hydros.data.storage.HydrosStore
import dev.uednd.hydros.data.storage.SavedAccount
import dev.uednd.hydros.ui.state.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.*
import java.time.LocalDateTime
import java.time.YearMonth

private val previewCredentials = Credentials("test-only", "1", "2", "3", "13800138000")
private val previewDevices = listOf(
    DeviceUi("AABBCCDDEEFF", "测试用水表", "测试宿舍 · 1 楼", DeviceCategory.SHOWER,
        status = DeviceStatus.AVAILABLE, channel = ControlChannel.CLOUD, depositMilli = 5_000,
        deviceId = "7", ledgerMac = "AABBCCDDEEFF", connectMac = "AA:BB:CC:DD:EE:FF"),
    DeviceUi("AABBCCDDEE01", "测试饮水机", "测试教学楼 · 2 楼", DeviceCategory.DRINK,
        status = DeviceStatus.AVAILABLE, channel = ControlChannel.CLOUD, depositMilli = 1_000,
        deviceId = "8", ledgerMac = "AABBCCDDEE01", connectMac = "AA:BB:CC:DD:EE:01")
)

internal fun createUiPreviewDependencies(context: Context, billCount: Int = 2): HydrosDependencies {
    val store = UiPreviewStore(context)
    val auth = RealAuthRepository(UiPreviewTransport(), store)
    return HydrosDependencies(
        auth = auth,
        account = object : AccountRepository {
            override suspend fun load(auth: Credentials) = AccountUi("测试学生", auth.phone, "1234", "测试学校")
        },
        wallet = object : WalletRepository {
            override suspend fun load(auth: Credentials) = WalletUi(12_340)
            override suspend fun bills(auth: Credentials, month: String, type: BillType): List<BillUi> {
                val time = YearMonth.parse(month).atDay(1).atTime(12, 30)
                val examples = if (type == BillType.RECHARGE) listOf(
                    BillUi("preview-recharge-1", "充值", "充值", null, 10_000,
                        time, time.toString(), recharge = true, detailAvailable = false),
                    BillUi("preview-recharge-2", "活动赠送", "活动赠送", null, 2_000,
                        time.minusHours(1), time.minusHours(1).toString(), recharge = true, detailAvailable = false)
                ) else listOf(
                    BillUi("preview-shower", "洗浴用水", "测试宿舍", DeviceCategory.SHOWER, 1_230,
                        time, time.toString(), "preview-order", "3分钟"),
                    BillUi("preview-drink", "饮水", "测试教学楼", DeviceCategory.DRINK, 300,
                        time.minusHours(1), time.minusHours(1).toString(), "preview-drink-order", "30秒")
                )
                return if (billCount == 2) examples else List(billCount) { index ->
                    examples[index % examples.size].let { it.copy(id = "${it.id}-$index") }
                }
            }
            override suspend fun detail(auth: Credentials, bill: BillUi) = bill
        },
        devices = object : DeviceRepository {
            override suspend fun find(auth: Credentials, mac: String, scanMac: String?, rssi: Int?) =
                previewDevices.firstOrNull { it.ledgerMac.equals(mac.replace(":", "").replace("-", ""), true) }
                    ?: throw ApiFailure(null, "UI 预览中只提供本地示例设备")
            override suspend fun prepare(auth: Credentials, device: DeviceUi, preferBle: Boolean) = device
            override suspend fun release(keepConnection: () -> Boolean) = Unit
        },
        sessions = object : SessionRepository {
            private val current = MutableStateFlow<SessionUi?>(null)
            override val session = current.asStateFlow()
            override suspend fun restore(auth: Credentials?) { current.value = store.record.session }
            override suspend fun start(auth: Credentials, device: DeviceUi) {
                val value = SessionUi(device, auth.key, stage = SessionStage.USING,
                    orderNo = "preview-order", consumeDate = LocalDateTime.now().toString())
                store.updateAccount(auth.key) { it.copy(session = value) }
                current.value = value
            }
            override suspend fun finish(auth: Credentials?) {
                store.record = store.record.copy(session = null)
                current.value = null
            }
            override suspend fun reconcile(auth: Credentials?) = Unit
        },
        store = store,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    )
}

private class UiPreviewStore(context: Context) : HydrosStore {
    private val settings = context.getSharedPreferences("ui-preview", Context.MODE_PRIVATE)
    private var auth: Credentials? = previewCredentials
    var record = SavedAccount(devices = previewDevices, recent = previewDevices.map { it.id },
        favorites = setOf(previewDevices.first().id))
    override suspend fun credentials() = auth
    override suspend fun credentials(value: Credentials?) { auth = value }
    override suspend fun account(key: String) = record
    override suspend fun updateAccount(key: String, transform: (SavedAccount) -> SavedAccount) = transform(record).also { record = it }
    override suspend fun theme() = settings.getString("theme", "SYSTEM") ?: "SYSTEM"
    override suspend fun theme(value: String) { settings.edit().putString("theme", value).apply() }
    override suspend fun fontSizeScale() = settings.getFloat("fontSizeScale", 1f)
    override suspend fun fontSizeScale(value: Float) { settings.edit().putFloat("fontSizeScale", value).apply() }
    override suspend fun feedback() = FeedbackSettings(
        hapticFeedbackEnabled = settings.getBoolean("hapticFeedbackEnabled", true),
        reduceMotionEnabled = settings.getBoolean("reduceMotionEnabled", false)
    )
    override suspend fun updateFeedback(transform: (FeedbackSettings) -> FeedbackSettings): FeedbackSettings =
        transform(feedback()).also {
            settings.edit().putBoolean("hapticFeedbackEnabled", it.hapticFeedbackEnabled)
                .putBoolean("reduceMotionEnabled", it.reduceMotionEnabled).apply()
        }
}

/** No HTTP, SMS, Bluetooth, MQTT, or foreground session service is used by this graph. */
private class UiPreviewTransport : CampusTransport {
    override suspend fun get(path: String, fields: Map<String, String>, auth: Credentials?): ApiResponse<JsonElement> =
        if (path == "user/verification/code/get") ApiResponse(success = true, data = JsonNull)
        else throw ApiFailure(null, "UI 预览不支持此操作")

    override suspend fun post(path: String, fields: Map<String, String>, auth: Credentials?): ApiResponse<JsonElement> {
        if (path == "user/login" || path == "user/registerAndLogin") {
            val valid = fields["telephone"] == previewCredentials.phone &&
                if (path == "user/login") fields["password"] == passwordHash("server123") else fields["smsCode"] == "654321"
            if (!valid) return ApiResponse(success = false, errorCode = 12, errorMessage = "测试号码 13800138000，密码 server123，验证码 654321")
            return ApiResponse(success = true, data = Json.parseToJsonElement(
                """{"loginCode":"test-only","userId":1,"userAccount":{"accountId":2,"projectId":3}}"""))
        }
        if (path in setOf("user/phone/update", "user/password/update", "user/password/forget")) {
            return ApiResponse(success = true, data = JsonNull)
        }
        throw ApiFailure(null, "UI 预览不支持此操作")
    }
}
