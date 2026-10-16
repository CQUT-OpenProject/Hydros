package dev.uednd.hydros

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.uednd.hydros.data.HydrosApplication
import dev.uednd.hydros.data.HydrosDependencies
import dev.uednd.hydros.data.network.Credentials
import dev.uednd.hydros.data.network.OrderPush
import dev.uednd.hydros.data.network.CampusTransport
import dev.uednd.hydros.data.network.ApiResponse
import dev.uednd.hydros.data.ble.*
import dev.uednd.hydros.data.repository.AuthRepository
import dev.uednd.hydros.data.repository.SessionRepository
import dev.uednd.hydros.data.repository.RealAuthRepository
import dev.uednd.hydros.data.repository.RealSessionRepository
import dev.uednd.hydros.data.storage.HydrosStore
import dev.uednd.hydros.data.storage.SavedAccount
import dev.uednd.hydros.service.SessionWatchService
import dev.uednd.hydros.ui.state.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class SessionWatchServiceTest {
    @Test fun freshServiceGraphRestoresCredentialsAndSessionBeforeMonitoringAndFinishing() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        val application=context.applicationContext as HydrosApplication
        val original=application.dependencies
        val credentials=Credentials("test-only","1","2","3","13800138000")
        val device=DeviceUi("water","测试水表","宿舍",DeviceCategory.SHOWER,channel=ControlChannel.CLOUD)
        var saved=SavedAccount(session=SessionUi(device,credentials.key,stage=SessionStage.STOPPING,orderNo="cold-order"))
        val monitored=CountDownLatch(1); val settled=CountDownLatch(1)
        val closes=AtomicInteger(); var stopped=false
        val store=object : HydrosStore by original.store {
            override suspend fun credentials()=credentials
            override suspend fun account(key: String)=if(key==credentials.key) saved else SavedAccount()
            override suspend fun updateAccount(key: String, transform: (SavedAccount)->SavedAccount): SavedAccount =
                transform(account(key)).also { saved=it; if(it.session==null) settled.countDown() }
        }
        val transport=object : CampusTransport {
            override suspend fun get(path: String, fields: Map<String,String>, auth: Credentials?): ApiResponse<JsonElement> = error(path)
            override suspend fun post(path: String, fields: Map<String,String>, auth: Credentials?): ApiResponse<JsonElement> {
                assertEquals(credentials,auth)
                assertEquals(credentials,application.dependencies.auth.credentials.value)
                val data=when {
                    path.endsWith("using") -> { monitored.countDown(); if(stopped) "null" else """{"orderNo":"cold-order","isOwner":true}""" }
                    path.endsWith("closeOrder") -> { closes.incrementAndGet(); stopped=true; "null" }
                    path.contains("consumeOrder") -> """{"orderNo":"cold-order","consumeMoney":0}"""
                    else -> error("恢复不能重放开启请求：$path")
                }
                return ApiResponse(success=true,errorCode=0,data=Json.parseToJsonElement(data))
            }
        }
        val ble=object : BlePort {
            override val ready=false
            override val connectedMac: String?=null
            override suspend fun connect(mac: String): BleConnectResult=error("云端恢复不能连接 BLE")
            override suspend fun query(): DeviceQueryState?=error("unexpected BLE query")
            override suspend fun writeRate(bytes: ByteArray): Boolean=error("unexpected BLE rate")
            override suspend fun closeValve(): Boolean=error("unexpected BLE close")
            override suspend fun collect(): ByteArray?=error("unexpected BLE collect")
            override suspend fun writeBack(bytes: ByteArray): Boolean=error("unexpected BLE writeback")
            override fun disconnect()=Unit
        }
        val auth=RealAuthRepository(transport,store)
        val sessions=RealSessionRepository(transport,store,ble,Mutex())
        // The Activity owns the original graph. Only the service can initialize this fresh graph.
        ActivityScenario.launch(MainActivity::class.java).use {
            try {
                instrumentation.runOnMainSync {
                    application.dependencies=HydrosDependencies(auth,original.account,original.wallet,original.devices,
                        sessions,store,original.scope)
                    assertNull(auth.credentials.value)
                    assertNull(sessions.session.value)
                    SessionWatchService.start(context)
                }
                assertTrue("服务应自行恢复并查询订单",monitored.await(5,TimeUnit.SECONDS))
                assertEquals(credentials,auth.credentials.value)
                context.startService(Intent(context,SessionWatchService::class.java).setAction("finish"))
                assertTrue("通知操作应使用恢复后的订单结算",settled.await(5,TimeUnit.SECONDS))
                instrumentation.waitForIdleSync()
                assertNull(sessions.session.value)
                assertEquals(1,closes.get())
            } finally {
                context.stopService(Intent(context,SessionWatchService::class.java))
                instrumentation.waitForIdleSync()
                instrumentation.runOnMainSync { application.dependencies=original }
            }
        }
    }

    @Test fun realForegroundHostUsesInjectedPushAndClosesItOnDestruction() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val application = context.applicationContext as HydrosApplication
        val original = application.dependencies
        val subscribed = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val reconciliations = AtomicInteger()
        val auth = Credentials("test-only", "1", "2", "3", "13800138000")
        val device = DeviceUi("water", "测试水表", "宿舍", DeviceCategory.SHOWER, channel = ControlChannel.CLOUD)
        val sessions = object : SessionRepository {
            override val session = MutableStateFlow<SessionUi?>(SessionUi(device, auth.key, stage = SessionStage.USING, orderNo = "offline-order"))
            override suspend fun restore(auth: Credentials?) = Unit
            override suspend fun start(auth: Credentials, device: DeviceUi) = Unit
            override suspend fun finish(auth: Credentials?) = Unit
            override suspend fun reconcile(auth: Credentials?) { reconciliations.incrementAndGet() }
        }
        var signal: (() -> Unit)? = null
        val push = object : OrderPush {
            override suspend fun subscribe(auth: Credentials, orderNo: String, onSignal: () -> Unit) {
                assertEquals("offline-order", orderNo)
                signal = onSignal
                subscribed.countDown()
            }
            override fun close() { if (subscribed.count == 0L) closed.countDown() }
        }
        // Keep the Activity on the offline graph; only the foreground host receives this graph.
        ActivityScenario.launch(MainActivity::class.java).use {
            try {
                instrumentation.runOnMainSync {
                    application.dependencies = HydrosDependencies(
                        auth = object : AuthRepository by original.auth {
                            override val credentials = MutableStateFlow<Credentials?>(auth)
                        },
                        account = original.account, wallet = original.wallet, devices = original.devices,
                        sessions = sessions, store = original.store, scope = original.scope,
                        orderPush = { push },
                    )
                    SessionWatchService.start(context)
                }
                assertTrue("后台宿主应使用注入的推送 adapter", subscribed.await(5, TimeUnit.SECONDS))
                assertTrue(reconciliations.get() > 0)
                context.stopService(Intent(context, SessionWatchService::class.java))
                assertTrue("销毁宿主应关闭订阅", closed.await(5, TimeUnit.SECONDS))
                val before = reconciliations.get()
                instrumentation.runOnMainSync { signal?.invoke() }
                instrumentation.waitForIdleSync()
                assertEquals(before, reconciliations.get())
            } finally {
                context.stopService(Intent(context, SessionWatchService::class.java))
                instrumentation.waitForIdleSync()
                instrumentation.runOnMainSync { application.dependencies = original }
            }
        }
    }
}
