package dev.uednd.hydros

import dev.uednd.hydros.data.HydrosDependencies
import dev.uednd.hydros.data.analytics.*
import dev.uednd.hydros.data.network.ApiFailure
import dev.uednd.hydros.data.repository.*
import dev.uednd.hydros.ui.state.HydrosViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LoginAnalyticsTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    @Test fun failedLoginEmitsLoginResult() = runTest(dispatcher) {
        val store = MemoryStore().apply { onboarding = true }
        val analytics = RecordingLoginAnalytics()
        val transport = ScriptTransport()
        transport.script = { _, _ -> throw ApiFailure(401, "rejected") }
        val lock = Mutex()
        val graph = HydrosDependencies(
            auth = RealAuthRepository(transport, store),
            account = RealAccountRepository(transport),
            wallet = RealWalletRepository(transport),
            devices = RealDeviceRepository(transport, store, lock),
            sessions = RealSessionRepository(transport, store, lock),
            store = store,
            scope = this,
            analytics = analytics,
        )
        val model = HydrosViewModel(graph)
        runCurrent()
        model.login("13800138000", "secret123", sms = false)
        runCurrent()
        advanceUntilIdle()
        assertEquals(1, analytics.loginEvents.size)
        assertEquals(ResultCode.FAILURE, analytics.loginEvents.single().result)
        assertEquals(LoginMethod.PASSWORD, analytics.loginEvents.single().method)
    }
}

private data class LoginEvent(val result: ResultCode, val method: LoginMethod)

private class RecordingLoginAnalytics : AnalyticsTracker by NoOpAnalyticsTracker {
    val loginEvents = mutableListOf<LoginEvent>()
    override fun loginResult(result: ResultCode, method: LoginMethod, failure: FailureCategory?) {
        loginEvents += LoginEvent(result, method)
    }
}
