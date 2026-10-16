package dev.uednd.hydros

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.uednd.hydros.ui.components.HydrosSnackbarProvider
import dev.uednd.hydros.ui.components.HoldActionButton
import dev.uednd.hydros.ui.components.HoldActionHost
import dev.uednd.hydros.ui.screens.DeviceDetailScreen
import dev.uednd.hydros.ui.screens.SessionScreen
import dev.uednd.hydros.ui.state.*
import dev.uednd.hydros.ui.theme.HydrosTheme
import dev.uednd.hydros.ui.theme.ThemeMode
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class DeviceDetailTest {
    @get:Rule val ui = createComposeRule()
    private val device = DeviceUi("test-device", "原始水表名", "测试宿舍", DeviceCategory.SHOWER,
        status = DeviceStatus.AVAILABLE, channel = ControlChannel.CLOUD, depositMilli = 5_000)

    @Test fun settledDeviceRestrictionExplainsPendingWorkAndCanReturnToRecovery() {
        var stage by mutableStateOf(SessionStage.SETTLEMENT_PENDING)
        var returns = 0
        ui.setContent {
            HydrosTheme { HydrosSnackbarProvider { HoldActionHost {
                DeviceDetailScreen(device, HydrosUiState(session = SessionUi(device.copy(id = "other-device"), "owner", stage = stage)),
                    {}, {}, { returns++ }, {}, {}, { _, _ -> true })
            } } }
        }
        ui.onNodeWithText("原设备结算尚未完成，请先返回处理").assertExists()
        ui.onNodeWithText("已有设备正在使用，请先结束当前设备").assertDoesNotExist()
        ui.onNodeWithText("返回使用页面").performScrollTo().performClick()
        assertEquals(1, returns)
        ui.runOnIdle { stage = SessionStage.WRITEBACK_PENDING }
        ui.onNodeWithText("原设备消费记录待清除，请先返回处理").assertExists()
        ui.onNodeWithText("连接设备").assertDoesNotExist()
    }

    @Test fun activeSessionOnSameDeviceDoesNotTriggerInUseRestriction() {
        var currentDevice by mutableStateOf(device)
        var sessionStage by mutableStateOf(SessionStage.USING)
        ui.setContent {
            HydrosTheme { HydrosSnackbarProvider { HoldActionHost {
                DeviceDetailScreen(currentDevice, HydrosUiState(session = SessionUi(device, "owner", stage = sessionStage)),
                    {}, {}, {}, {}, {}, { _, _ -> true })
            } } }
        }
        ui.onNodeWithText("已有设备正在使用，请先结束当前设备").assertDoesNotExist()
        ui.onNodeWithText("返回使用页面").assertExists()

        ui.runOnIdle { sessionStage = SessionStage.SETTLEMENT_PENDING }
        ui.onNodeWithText("原设备结算尚未完成，请先返回处理").assertDoesNotExist()
        ui.onNodeWithText("返回使用页面").assertExists()

        val other = device.copy(id = "other-device")
        ui.runOnIdle { currentDevice = other }
        ui.onNodeWithText("原设备结算尚未完成，请先返回处理").assertExists()
        ui.onNodeWithText("返回使用页面").assertExists()
    }

    @Test fun closedSessionOffersSettlementAndWritebackRetriesAndStopsTimer() {
        var session by mutableStateOf(SessionUi(device, "owner", startedAt = System.currentTimeMillis() - 10_000,
            stage = SessionStage.USING))
        var retries = 0
        ui.setContent {
            HydrosTheme { HydrosSnackbarProvider { HoldActionHost {
                SessionScreen(session, { retries++ }, {})
            } } }
        }
        ui.runOnIdle { session = session.copy(stage = SessionStage.SETTLEMENT_PENDING, endedAt = session.startedAt + 5_000) }
        ui.onNodeWithText("00:05").assertExists()
        ui.onNodeWithText("重试结算").performScrollTo().performClick()
        ui.mainClock.advanceTimeBy(2_000)
        ui.onNodeWithText("00:05").assertExists()
        ui.runOnIdle { session = session.copy(stage = SessionStage.WRITEBACK_PENDING) }
        ui.onNodeWithText("重试清除设备记录").performScrollTo().performClick()
        assertEquals(2, retries)
    }

    @Test fun remarkEditorLimitsLengthRetainsOriginalAndCanClearOrCancel() {
        var state by mutableStateOf(HydrosUiState(devices = listOf(device), favoriteDeviceIds = setOf(device.id)))
        ui.setContent {
            HydrosTheme { HydrosSnackbarProvider { HoldActionHost {
                DeviceDetailScreen(device, state, {}, {}, {}, {}, {}, { id, value ->
                    state = state.copy(deviceRemarks = if (value.isBlank()) emptyMap() else mapOf(id to value.trim()))
                    true
                })
            } } }
        }
        ui.onNodeWithText("设置备注").performClick()
        val field = ui.onNodeWithContentDescription("备注")
        val full = "自".repeat(16)
        field.performTextInput(full)
        field.performTextInput("多")
        field.assertTextEquals(full)
        ui.onNodeWithText("16/16").assertExists()
        ui.onNodeWithText("保存").performClick()
        ui.onNodeWithText(full).assertExists()
        ui.onNodeWithText("原始设备名").assertExists()
        ui.onNodeWithText(device.name).assertExists()
        ui.onNodeWithText("修改备注").performClick()
        field.performTextReplacement("未保存的修改")
        ui.onNodeWithText("取消").performClick()
        ui.onNodeWithText(full).assertExists()
        ui.onNodeWithText("修改备注").performClick()
        field.performTextClearance()
        ui.onNodeWithText("保存").performClick()
        assertTrue(state.deviceRemarks.isEmpty())
        ui.onNodeWithText("设置备注").assertExists()
    }

    @Test fun failedRemarkSaveRetainsInputAndExplainsFailure() {
        ui.setContent {
            HydrosTheme { HydrosSnackbarProvider { HoldActionHost {
                DeviceDetailScreen(device, HydrosUiState(favoriteDeviceIds = setOf(device.id)), {}, {}, {}, {}, {}, { _, _ -> false })
            } } }
        }
        ui.onNodeWithText("设置备注").performClick()
        ui.onNodeWithContentDescription("备注").performTextInput("宿舍水表")
        ui.onNodeWithText("保存").performClick()
        ui.onNodeWithContentDescription("备注").assertTextEquals("宿舍水表")
        ui.onNodeWithText("保存失败，请重试").assertExists()
    }

    @Test fun onlyFavoritesCanEditAndUnfavoriteClosesEditor() {
        var state by mutableStateOf(HydrosUiState(devices = listOf(device), connectedDeviceIds = listOf(device.id)))
        ui.setContent {
            HydrosTheme { HydrosSnackbarProvider { HoldActionHost {
                DeviceDetailScreen(device, state, {}, {}, {}, {}, {
                    state = state.copy(favoriteDeviceIds = setOf(device.id))
                }, { _, _ -> true })
            } } }
        }
        ui.onNodeWithText("设置备注").assertDoesNotExist()
        ui.onNodeWithText("收藏设备").performClick()
        ui.onNodeWithText("设置备注").performClick()
        ui.onNodeWithContentDescription("备注").performTextInput("尚未保存")
        ui.runOnIdle { state = state.copy(favoriteDeviceIds = emptySet()) }
        ui.onNodeWithContentDescription("备注").assertDoesNotExist()
        ui.onNodeWithText("设置备注").assertDoesNotExist()
    }

    @Test fun connectionStatusTracksCurrentDeviceRatherThanHistory() {
        var state by mutableStateOf(HydrosUiState(connectedDeviceIds = listOf(device.id)))
        ui.setContent {
            HydrosTheme { HydrosSnackbarProvider { HoldActionHost {
                DeviceDetailScreen(device, state, {}, {}, {}, {}, {}, { _, _ -> true })
            } } }
        }
        ui.onAllNodesWithText("连接状态").assertCountEquals(1)
        ui.onNodeWithText("未连接").assertExists()
        listOf(ConnectionStage.CONNECTING to "连接中", ConnectionStage.FAILED to "连接失败", ConnectionStage.READY to "已连接").forEach { (stage, label) ->
            ui.runOnIdle { state = state.copy(connectingId = device.id, connectionStage = stage) }
            ui.onNode(hasText("连接状态") and hasText(label)).assertExists()
        }
        ui.runOnIdle { state = state.copy(connectingId = "other-device") }
        ui.onNodeWithText("未连接").assertExists()
        ui.onNodeWithText("已连接").assertDoesNotExist()
    }

    @Test fun balanceBelowAdvertisedDepositStillAllowsConnectionAndStart() {
        val waterDevice = device.copy(depositMilli = 10_000)
        var state by mutableStateOf(HydrosUiState(wallet = WalletUi(5_928)))
        var connections = 0
        var starts = 0
        ui.setContent {
            HydrosTheme { HydrosSnackbarProvider { HoldActionHost {
                DeviceDetailScreen(waterDevice, state, onConnect = {
                    connections++
                    state = state.copy(connectingId = it.id, connectionStage = ConnectionStage.READY)
                }, onStart = { starts++ }, onSession = {}, onBack = {}, onFavorite = {}, onSaveRemark = { _, _ -> true })
            } } }
        }
        ui.onNodeWithText("钱包余额不足，请在服务商官方渠道处理后刷新钱包").assertDoesNotExist()
        ui.onNodeWithText("连接设备").performScrollTo().performClick()
        assertEquals(1, connections)
        val button = ui.onNodeWithTag("hold-to-start")
        button.performScrollTo().assertIsEnabled()
        ui.mainClock.autoAdvance = false
        button.performTouchInput { down(center) }
        ui.mainClock.advanceTimeBy(2_100)
        button.performTouchInput { up() }
        assertEquals(1, starts)
        ui.mainClock.autoAdvance = true
    }

    private var buttonColor = Color.Unspecified

    private fun holdButton(onStart: () -> Unit) {
        ui.setContent {
            HydrosTheme(ThemeMode.DARK) {
                buttonColor = MaterialTheme.colorScheme.primary
                HoldActionHost {
                var starting by remember { mutableStateOf(false) }
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Bottom) {
                        Box(Modifier.padding(16.dp)) {
                            HoldActionButton(device.id, !starting, starting) { starting = true; onStart() }
                        }
                    }
                }
                }
            }
        }
        ui.waitForIdle()
        ui.mainClock.autoAdvance = false
    }

    private fun progress(): Float = ui.onNodeWithTag("hold-expansion")
        .fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].current

    @Test fun holdExpandsForTwelveHundredMillisecondsAndStartsOnlyOnceEvenOutsideOriginalButton() {
        var starts = 0
        holdButton { starts++ }
        val button = ui.onNodeWithTag("hold-to-start")
        button.assertTextContains("长按以开始使用")
        button.performTouchInput { down(center) }
        ui.mainClock.advanceTimeByFrame()
        ui.mainClock.advanceTimeBy(576)
        assertEquals(0, starts)
        assertTrue(progress() in 0.45f..0.55f)
        if (androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("screenshots") == "true") {
            val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
            java.io.File(context.cacheDir, "hold-wave-without-bubbles.png").outputStream().use {
                ui.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
        }
        ui.onNodeWithText("继续按住").assertExists()
        ui.onAllNodes(hasText("秒", substring = true)).assertCountEquals(0)
        button.performTouchInput { moveTo(center.copy(y = -100f)) }
        ui.mainClock.advanceTimeBy(576)
        assertEquals(0, starts)
        ui.mainClock.advanceTimeBy(64)
        assertEquals(1, starts)
        ui.onNodeWithTag("hold-expansion").assert(SemanticsMatcher.expectValue(
            SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo(1f, 0f..1f)))
        val pixels = ui.onRoot().captureToImage().toPixelMap()
        listOf(0 to 0, (pixels.width - 1) to 0, 0 to (pixels.height - 1),
            (pixels.width - 1) to (pixels.height - 1)).forEach { (x, y) ->
            assertEquals("Expansion must cover every window corner", buttonColor.toArgb(), pixels[x, y].toArgb())
        }
        ui.mainClock.advanceTimeBy(3_000)
        assertEquals(1, starts)
        button.performTouchInput { up() }
        ui.mainClock.autoAdvance = true
    }

    @Test fun releasingEarlyRetractsAndNextPressStartsFromZero() {
        var starts = 0
        holdButton { starts++ }
        val button = ui.onNodeWithTag("hold-to-start")
        button.performTouchInput { down(center) }
        ui.mainClock.advanceTimeBy(1_000)
        button.performTouchInput { up() }
        ui.mainClock.advanceTimeBy(256)
        assertEquals(0, starts)
        ui.onNodeWithTag("hold-expansion").assertDoesNotExist()
        button.performTouchInput { down(center) }
        ui.mainClock.advanceTimeBy(288)
        assertTrue(progress() < 0.3f)
        assertEquals(0, starts)
        button.performTouchInput { up() }
        ui.mainClock.advanceTimeBy(256)
        ui.onNodeWithTag("hold-expansion").assertDoesNotExist()
        ui.mainClock.autoAdvance = true
    }

    @Test fun backgroundingAndLeavingPageCancelPendingStart() {
        var starts = 0
        val owner = object : LifecycleOwner {
            val registry = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
            override val lifecycle: Lifecycle get() = registry
        }
        var showButton by mutableStateOf(true)
        ui.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                HydrosTheme { HydrosSnackbarProvider { HoldActionHost {
                    if (showButton) HoldActionButton(device.id, true, false) { starts++ }
                } } }
            }
        }
        ui.mainClock.autoAdvance = false
        val button = ui.onNodeWithTag("hold-to-start")
        button.performTouchInput { down(center) }
        ui.mainClock.advanceTimeBy(750)
        ui.runOnIdle { owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE) }
        ui.mainClock.advanceTimeBy(2_100)
        ui.onNodeWithTag("hold-expansion").assertDoesNotExist()
        assertEquals(0, starts)
        button.performTouchInput { up() }
        ui.runOnIdle { owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME) }
        button.performTouchInput { down(center) }
        ui.mainClock.advanceTimeBy(750)
        ui.runOnIdle { showButton = false }
        ui.mainClock.advanceTimeBy(2_100)
        ui.onNodeWithTag("hold-expansion").assertDoesNotExist()
        assertEquals(0, starts)
        ui.mainClock.autoAdvance = true
    }

    @Test fun backAndWindowResizeCancelPendingStart() {
        var starts = 0
        lateinit var host: ComponentActivity
        var narrow by mutableStateOf(false)
        ui.setContent {
            host = LocalActivity.current as ComponentActivity
            HydrosTheme { Box(if (narrow) Modifier.width(240.dp) else Modifier.fillMaxWidth()) {
                HoldActionHost { HoldActionButton(device.id, true, false) { starts++ } }
            } }
        }
        ui.mainClock.autoAdvance = false
        val button = ui.onNodeWithTag("hold-to-start")
        button.performTouchInput { down(center) }
        ui.mainClock.advanceTimeBy(750)
        ui.runOnIdle { host.onBackPressedDispatcher.onBackPressed() }
        ui.mainClock.advanceTimeBy(256)
        ui.onNodeWithTag("hold-expansion").assertDoesNotExist()
        button.performTouchInput { up() }
        button.performTouchInput { down(center) }
        ui.mainClock.advanceTimeBy(750)
        ui.runOnIdle { narrow = true }
        ui.mainClock.advanceTimeBy(2_100)
        ui.onNodeWithTag("hold-expansion").assertDoesNotExist()
        assertEquals(0, starts)
        button.performTouchInput { up() }
        ui.mainClock.autoAdvance = true
    }

    @Test fun finishClickSubmitsImmediatelyAndBlocksRepeatedClicksWhileClosing() {
        var finishes = 0
        var session by mutableStateOf(SessionUi(device, "owner", stage = SessionStage.USING))
        ui.setContent {
            HydrosTheme { HoldActionHost {
                SessionScreen(session, onFinish = {
                    finishes++
                    session = session.copy(stage = SessionStage.STOPPING)
                }, onHome = {})
            } }
        }
        ui.onNodeWithText("点击以结束使用").performScrollTo().performClick()
        assertEquals(1, finishes)
        ui.onNode(hasText("正在关闭") and hasClickAction()).assertIsNotEnabled().performTouchInput { click() }
        assertEquals(1, finishes)
        ui.onNodeWithTag("hold-expansion").assertDoesNotExist()
        ui.onNodeWithText("继续按住").assertDoesNotExist()
        ui.onNodeWithText("确认结束").assertDoesNotExist()
    }

    @Test fun finishFailureCanRetryAndReturnHomeKeepsTheSession() {
        var finishes = 0
        var home = 0
        val session = SessionUi(device, "owner", stage = SessionStage.CLOSE_UNCONFIRMED)
        ui.setContent {
            HydrosTheme { HydrosSnackbarProvider { HoldActionHost {
                SessionScreen(session, onFinish = { finishes++ }, onHome = { home++ })
            } } }
        }
        val button = ui.onNodeWithText("点击以结束使用")
        button.performScrollTo()
        repeat(2) {
            button.performClick()
            ui.onNodeWithTag("hold-expansion").assertDoesNotExist()
        }
        assertEquals(2, finishes)
        ui.onNodeWithText("返回首页").performScrollTo().performClick()
        assertEquals(1, home)
        ui.onNodeWithText("关闭待确认").assertExists()
    }

    @Test fun finishIsDisabledDuringOpeningClosingAndSettlement() {
        var stage by mutableStateOf(SessionStage.OPENING)
        ui.setContent {
            HydrosTheme { HydrosSnackbarProvider { HoldActionHost {
                SessionScreen(SessionUi(device, "owner", stage = stage), {}, {})
            } } }
        }
        listOf(SessionStage.OPENING, SessionStage.STOPPING, SessionStage.SETTLING).forEach {
            ui.runOnIdle { stage = it }
            ui.onNode((hasText("正在开启") or hasText("正在关闭") or hasText("设备已关闭，正在结算")) and hasClickAction()).assertIsNotEnabled()
        }
        ui.runOnIdle { stage = SessionStage.USING }
        ui.onNodeWithText("点击以结束使用").assertIsEnabled()
    }

    @Test fun synchronousStartFailureReturnsToButtonAndAllowsRetry() {
        var starts = 0
        ui.setContent {
            HydrosTheme { HydrosSnackbarProvider { HoldActionHost {
                HoldActionButton(device.id, true, false) { starts++ }
            } } }
        }
        ui.mainClock.autoAdvance = false
        val button = ui.onNodeWithTag("hold-to-start")
        repeat(2) {
            button.performTouchInput { down(center) }
            ui.mainClock.advanceTimeBy(2_100)
            button.performTouchInput { up() }
            ui.mainClock.advanceTimeBy(256)
            ui.onNodeWithTag("hold-expansion").assertDoesNotExist()
        }
        assertEquals(2, starts)
        ui.mainClock.autoAdvance = true
    }

    @Test fun offlineDeviceWhenReadyRendersHoldActionButtonWithoutSnackbarRestriction() {
        val offlineDevice = device.copy(status = DeviceStatus.OFFLINE, channel = ControlChannel.BLE)
        var stage by mutableStateOf(ConnectionStage.IDLE)
        ui.setContent {
            HydrosTheme { HydrosSnackbarProvider { HoldActionHost {
                DeviceDetailScreen(offlineDevice, HydrosUiState(connectingId = offlineDevice.id, connectionStage = stage),
                    {}, {}, {}, {}, {}, { _, _ -> true })
            } } }
        }
        ui.onNodeWithText("连接设备").assertExists()
        ui.onNodeWithText("设备暂时离线，请稍后重试或选择其他设备").assertExists()

        ui.runOnIdle { stage = ConnectionStage.READY }
        ui.onNodeWithTag("hold-to-start").assertExists()
        ui.onNodeWithText("设备暂时离线，请稍后重试或选择其他设备").assertDoesNotExist()
    }

    @Test fun busyDeviceOnlyRendersRecoverActionWhenThisDeviceIsReady() {
        val busyDevice = device.copy(status = DeviceStatus.BUSY)
        var connectingId by mutableStateOf<String?>(null)
        var stage by mutableStateOf(ConnectionStage.IDLE)
        ui.setContent {
            HydrosTheme { HydrosSnackbarProvider { HoldActionHost {
                DeviceDetailScreen(busyDevice, HydrosUiState(connectingId = connectingId, connectionStage = stage),
                    {}, {}, {}, {}, {}, { _, _ -> true })
            } } }
        }
        ui.onNodeWithText("连接设备").assertExists()
        ui.onNodeWithText("核实并恢复本人订单").assertDoesNotExist()

        ui.runOnIdle {
            connectingId = "other-device"
            stage = ConnectionStage.READY
        }
        ui.onNodeWithText("核实并恢复本人订单").assertDoesNotExist()
        ui.onNodeWithText("连接设备").assertExists()

        ui.runOnIdle {
            connectingId = busyDevice.id
            stage = ConnectionStage.READY
        }
        ui.onNodeWithText("核实并恢复本人订单").assertExists()
    }
}
