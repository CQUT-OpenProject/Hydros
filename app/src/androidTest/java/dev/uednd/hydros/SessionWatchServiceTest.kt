package dev.uednd.hydros

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.uednd.hydros.data.HydrosApplication
import dev.uednd.hydros.data.HydrosDependencies
import dev.uednd.hydros.data.network.Credentials
import dev.uednd.hydros.data.network.OrderPush
import dev.uednd.hydros.data.repository.AuthRepository
import dev.uednd.hydros.data.repository.SessionRepository
import dev.uednd.hydros.service.SessionWatchService
import dev.uednd.hydros.ui.state.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class SessionWatchServiceTest {
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
