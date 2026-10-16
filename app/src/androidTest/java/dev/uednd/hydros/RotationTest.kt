package dev.uednd.hydros

import dev.uednd.hydros.data.storage.FeedbackSettings
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import dev.uednd.hydros.data.*
import dev.uednd.hydros.data.ble.*
import dev.uednd.hydros.data.network.*
import dev.uednd.hydros.data.repository.*
import dev.uednd.hydros.data.storage.*
import dev.uednd.hydros.ui.state.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.json.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain

class RotationTest {
    private val ui=createAndroidComposeRule<MainActivity>()
    private lateinit var original: HydrosDependencies
    private lateinit var injected: HydrosDependencies
    private lateinit var application: HydrosApplication
    private val lifecycle=object : ExternalResource() {
        override fun before() {
            application=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as HydrosApplication
            original=application.dependencies
            val auth=Credentials("test-only","1","2","3","13800138000")
            val device=DeviceUi("test-device","测试水表","测试宿舍",DeviceCategory.SHOWER,channel=ControlChannel.CLOUD)
            val store=object : HydrosStore {
                var current: Credentials?=auth; var saved=SavedAccount(session=SessionUi(device,auth.key,stage=SessionStage.OPEN_UNCONFIRMED))
                override suspend fun credentials()=current
                override suspend fun credentials(value: Credentials?) { current=value }
                override suspend fun account(key: String)=saved
                override suspend fun updateAccount(key: String, transform: (SavedAccount)->SavedAccount)=transform(saved).also { saved=it }
                override suspend fun theme()="SYSTEM"
                override suspend fun theme(value: String)=Unit
                private var fontScale = 1f
                override suspend fun fontSizeScale() = fontScale
                override suspend fun fontSizeScale(value: Float) { fontScale = value }
                private var feedbackSettings = FeedbackSettings()
                override suspend fun feedback() = feedbackSettings
                override suspend fun updateFeedback(transform: (FeedbackSettings) -> FeedbackSettings): FeedbackSettings =
                    transform(feedbackSettings).also { feedbackSettings = it }
            }
            val remote=object : CampusTransport {
                override suspend fun get(path: String, fields: Map<String,String>, auth: Credentials?): ApiResponse<JsonElement> = when(path) {
                    "account/info" -> ApiResponse(success=true,data=Json.parseToJsonElement("""{"name":"测试学生"}"""))
                    "project/info/triple" -> ApiResponse(success=true,data=Json.parseToJsonElement("""{"projectName":"测试学校"}"""))
                    "order/query/account/bill/list" -> ApiResponse(success=true,data=JsonArray(emptyList()))
                    else -> ApiResponse(success=true,data=JsonObject(emptyMap()))
                }
                override suspend fun post(path: String, fields: Map<String,String>, auth: Credentials?)=ApiResponse<JsonElement>(success=false,errorCode=1)
            }
            val ble=object : BlePort {
                override val ready=false
                override suspend fun connect(mac: String)=BleConnectResult.NoService
                override suspend fun query(): DeviceQueryState?=null
                override suspend fun writeRate(bytes: ByteArray)=false
                override suspend fun closeValve()=false
                override suspend fun collect(): ByteArray?=null
                override suspend fun writeBack(bytes: ByteArray)=false
                override fun disconnect()=Unit
            }
            val operation=Mutex()
            injected=HydrosDependencies(RealAuthRepository(remote,store),RealAccountRepository(remote),RealWalletRepository(remote),
                RealDeviceRepository(remote,store,ble,operation),RealSessionRepository(remote,store,ble,operation),store,
                CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate))
            application.dependencies=injected
        }
        override fun after() { application.dependencies=original; injected.scope.cancel() }
    }
    @get:Rule val rules: RuleChain=RuleChain.outerRule(lifecycle).around(ui)
    @Test fun activityRecreationPreservesSessionAndCurrentPage() {
        ui.waitUntil(8_000) { ui.onAllNodesWithText("开启待确认").fetchSemanticsNodes().isNotEmpty() }
        ui.activityRule.scenario.recreate()
        ui.waitUntil(8_000) { ui.onAllNodesWithText("开启待确认").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithText("返回首页").performScrollTo().performClick()
        ui.onNodeWithText("钱包",substring=false).performClick()
        ui.activityRule.scenario.recreate()
        ui.waitUntil(8_000) { ui.onAllNodesWithText("账单记录").fetchSemanticsNodes().isNotEmpty() }
    }
}
