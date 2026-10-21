package dev.uednd.hydros

import dev.uednd.hydros.data.HydrosDependencies
import dev.uednd.hydros.data.network.Credentials
import dev.uednd.hydros.data.analytics.AnalyticsTracker
import dev.uednd.hydros.data.analytics.*
import dev.uednd.hydros.data.repository.*
import dev.uednd.hydros.ui.state.HydrosViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun onboardingStateFollowsStoreAndCanBeCompleted() = runTest(dispatcher) {
        val store = MemoryStore().apply { onboarding = false }
        val transport = ScriptTransport()
        val lock = Mutex()
        val graph = HydrosDependencies(
            auth = RealAuthRepository(transport, store),
            account = RealAccountRepository(transport),
            wallet = RealWalletRepository(transport),
            devices = RealDeviceRepository(transport, store, lock),
            sessions = RealSessionRepository(transport, store, lock),
            store = store,
            scope = this
        )

        val model = HydrosViewModel(graph)
        runCurrent()

        assertFalse("首次启动时 onboardingCompleted 应当为 false", model.state.value.onboardingCompleted)
        assertFalse("未登录且未完成引导时 signedIn 应为 false", model.state.value.signedIn)

        model.completeOnboarding()
        runCurrent()

        assertTrue("调用 completeOnboarding 后 UI 状态应当为 true", model.state.value.onboardingCompleted)
        assertTrue("存储中的 onboardingCompleted 应当被更新为 true", store.onboardingCompleted())
    }

    @Test
    fun initializedWithCompletedOnboardingRetainsTrue() = runTest(dispatcher) {
        val store = MemoryStore().apply { onboarding = true }
        val transport = ScriptTransport()
        val lock = Mutex()
        val graph = HydrosDependencies(
            auth = RealAuthRepository(transport, store),
            account = RealAccountRepository(transport),
            wallet = RealWalletRepository(transport),
            devices = RealDeviceRepository(transport, store, lock),
            sessions = RealSessionRepository(transport, store, lock),
            store = store,
            scope = this
        )

        val model = HydrosViewModel(graph)
        runCurrent()

        assertTrue("若已完成过引导，初始化后 onboardingCompleted 应直接为 true", model.state.value.onboardingCompleted)
    }

    @Test
    fun declinedAnalyticsStaysOffAndAcceptedChoicePersistsAndCanBeRevoked() = runTest(dispatcher) {
        val store = MemoryStore()
        val analytics = RecordingAnalyticsTracker()
        val transport = ScriptTransport(); val lock = Mutex()
        val graph = HydrosDependencies(RealAuthRepository(transport, store), RealAccountRepository(transport),
            RealWalletRepository(transport), RealDeviceRepository(transport, store, lock),
            RealSessionRepository(transport, store, lock), store, this, analytics = analytics)
        val model = HydrosViewModel(graph)
        runCurrent()
        assertTrue(store.analyticsEnabled()) // persisted default is on; collection still waits for onboarding

        model.completeOnboarding(analyticsEnabled = false)
        runCurrent()
        assertFalse(analytics.enabledState)
        assertFalse(store.analyticsEnabled())

        model.setAnalyticsEnabled(true)
        assertTrue("启用选择应立即放开 Analytics 收集", analytics.enabledState)
        runCurrent()
        assertEquals(listOf(true), analytics.consents)
        assertEquals(listOf(true), analytics.consentStates)
        assertTrue(store.analyticsEnabled())
        assertTrue(analytics.enabledState)

        val restartedModel = HydrosViewModel(graph)
        runCurrent()
        assertTrue(restartedModel.state.value.analyticsEnabled)
        assertTrue(analytics.enabledState)

        model.setAnalyticsEnabled(false)
        assertFalse("撤回选择应立即停止 Analytics 收集", analytics.enabledState)
        runCurrent()
        assertFalse(analytics.enabledState)
        assertFalse(store.analyticsEnabled())
        assertEquals(listOf(true, false), analytics.consents)
        // The opt-out is recorded while collection is still on; it could not be sent afterwards.
        assertEquals(listOf(true, true), analytics.consentStates)
    }
}
