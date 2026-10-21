package dev.uednd.hydros

import dev.uednd.hydros.data.HydrosDependencies
import dev.uednd.hydros.data.analytics.ResultCode
import dev.uednd.hydros.data.analytics.SessionRecoveryStage
import dev.uednd.hydros.data.analytics.SettingsKey
import dev.uednd.hydros.data.network.Credentials
import dev.uednd.hydros.data.repository.*
import dev.uednd.hydros.data.storage.SavedAccount
import dev.uednd.hydros.ui.state.*
import dev.uednd.hydros.ui.theme.ThemeMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AnalyticsEventsViewModelTest {
    private val auth = Credentials("token", "1", "2", "3", "13800138000")
    private val device = DeviceUi("AABBCCDDEEFF", "测试水表", "测试宿舍", DeviceCategory.SHOWER,
        channel = ControlChannel.CLOUD, deviceId = "7", ledgerMac = "AABBCCDDEEFF",
        connectMac = "AA:BB:CC:DD:EE:FF", bigTypeId = "4", smallTypeId = "1")

    private fun dependencies(store: MemoryStore, transport: ScriptTransport,
        analytics: RecordingAnalyticsTracker, scope: kotlinx.coroutines.CoroutineScope) = HydrosDependencies(
        RealAuthRepository(transport, store), RealAccountRepository(transport),
        RealWalletRepository(transport), RealDeviceRepository(transport, store, Mutex()),
        RealSessionRepository(transport, store, Mutex(), confirmationAttempts = 1, pause = {}),
        store, scope, analytics = analytics,
    )

    @Test fun closeRecoveryIsReportedWithTheOriginalStageEvenThroughSettling() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val credentials = auth
            val store = MemoryStore().apply {
                this.auth = credentials
                records[credentials.key] = SavedAccount(devices = listOf(device), recent = listOf(device.id),
                    session = SessionUi(device, credentials.key, stage = SessionStage.CLOSE_UNCONFIRMED,
                        orderNo = "own", recoveryReason = SessionRecoveryReason.CLOSE_UNKNOWN))
            }
            val transport = ScriptTransport().apply {
                script = { path, _ ->
                    when {
                        path == "order/consumeOrder/result/query" ->
                            response("""{"orderNo":"own","consumeMoney":"0.50","snCode":"AABBCCDDEEFF"}""")
                        else -> response("{}")
                    }
                }
            }
            val analytics = RecordingAnalyticsTracker()
            val graph = dependencies(store, transport, analytics, backgroundScope)
            val model = HydrosViewModel(graph)
            advanceUntilIdle()
            assertEquals(SessionStage.CLOSE_UNCONFIRMED, model.state.value.session?.stage)

            graph.sessions.reconcile(auth)
            advanceUntilIdle()

            assertEquals(listOf(Triple(SessionRecoveryStage.CLOSE_UNCONFIRMED, ResultCode.SUCCESS,
                SessionRecoveryReason.CLOSE_UNKNOWN)), analytics.recoveryResults)
        } finally { Dispatchers.resetMain() }
    }

    @Test fun settingsChangesAreReportedWithTheirBuckets() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val analytics = RecordingAnalyticsTracker()
            val model = HydrosViewModel(dependencies(MemoryStore(), ScriptTransport(), analytics, backgroundScope))
            advanceUntilIdle()

            model.theme(ThemeMode.DARK)
            // Actions run on the dependency scope (the test's background scope).
            runCurrent()
            model.fontSizeScale(1.15f)
            advanceUntilIdle()

            assertEquals(listOf(SettingsKey.THEME to "DARK", SettingsKey.FONT_SCALE to "LARGE"), analytics.settings)
        } finally { Dispatchers.resetMain() }
    }
}
