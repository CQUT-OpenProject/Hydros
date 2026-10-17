package dev.uednd.hydros

import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import dev.uednd.hydros.ui.components.HydrosSnackbarProvider
import dev.uednd.hydros.ui.components.HydrosSnackbarState
import dev.uednd.hydros.ui.HydrosNotificationEffects
import dev.uednd.hydros.ui.screens.*
import dev.uednd.hydros.ui.state.*
import dev.uednd.hydros.ui.theme.HydrosTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.time.LocalDateTime

class NotificationScreensTest {
    @get:Rule val ui = createComposeRule()
    @Test fun backgroundFailuresDoNotReplaceUserOrDeviceFeedback() {
        val snackbar = HydrosSnackbarState()
        var state by mutableStateOf(HydrosUiState(message = "操作失败"))
        var updates by mutableStateOf(AppUpdateUiState())
        ui.setContent { HydrosTheme { HydrosSnackbarProvider(snackbar) {
            HydrosNotificationEffects(state, updates, { state = state.copy(message = null) }, {}, {}, {})
            Surface { androidx.compose.material3.Text("其他页面") }
        } } }
        ui.onNodeWithText("操作失败").assertIsDisplayed()
        val first = snackbar.notice.value!!.id
        ui.runOnIdle { assertNull(state.message); state = state.copy(message = "操作失败") }
        ui.onNodeWithText("操作失败").assertIsDisplayed()
        ui.runOnIdle { assertTrue(snackbar.notice.value!!.id > first); state = state.copy(walletError = "后台钱包错误") }
        ui.onNodeWithText("后台钱包错误").assertDoesNotExist()
        ui.onNodeWithText("操作失败").assertIsDisplayed()
        val device = DeviceUi("test-device", "水表", "宿舍", DeviceCategory.SHOWER)
        ui.runOnIdle { state = state.copy(session = SessionUi(device, "owner", stage = SessionStage.OPEN_UNCONFIRMED, recoveryReason = dev.uednd.hydros.ui.state.SessionRecoveryReason.OPEN_UNKNOWN)) }
        ui.onNodeWithText("开启结果尚未确认", substring = true).assertIsDisplayed()
        ui.runOnIdle { updates = AppUpdateUiState(error = "后台更新错误") }
        ui.onNodeWithText("后台更新错误").assertDoesNotExist()
        ui.onNodeWithText("开启结果尚未确认", substring = true).assertIsDisplayed()
        ui.onNodeWithText("其他页面").assertExists()
    }
    @Test fun walletFailureAndOldBalanceRemainVisibleUntilSuccessfulRefresh() {
        var retries = 0
        var state by mutableStateOf(HydrosUiState(walletError = "钱包网络错误", wallet = WalletUi(12_340)))
        ui.setContent { HydrosTheme { HydrosSnackbarProvider { Surface {
            HydrosNotificationEffects(state, AppUpdateUiState(), {}, { retries++ }, {}, {})
            WalletScreen(state, onRefresh = { retries++ }, onBill = {})
        } } } }
        ui.onAllNodesWithText("钱包网络错误").assertCountEquals(1)
        ui.onNodeWithText("钱包信息加载失败").assertDoesNotExist()
        ui.onNodeWithText("重新刷新").performScrollTo().performClick()
        assertEquals(1, retries)
        ui.onNodeWithText("钱包网络错误").assertExists()
        ui.onNodeWithText("刷新失败，显示上次结果").assertExists()
        ui.onNodeWithText("刷新", substring = false).performScrollTo().performClick()
        assertEquals(2, retries)
        ui.runOnIdle { state = state.copy(walletError = null) }
        ui.runOnIdle { state = state.copy(walletError = "钱包网络错误") }
        ui.onNodeWithText("钱包网络错误").performScrollTo().assertIsDisplayed()
    }
    @Test fun walletBillTypeSwitchShowsOnlySelectedKindAndRechargeRowsAreNotClickable() {
        val consume = BillUi("consume-1", "宿舍热水", "宿舍", DeviceCategory.SHOWER, 500L,
            LocalDateTime.of(2026, 10, 15, 12, 30), "2026-10-15 12:30:00")
        val recharge = BillUi("recharge:1", "充值", "充值", null, 50_000L,
            LocalDateTime.of(2026, 10, 15, 13, 0), "2026-10-15 13:00:00", recharge = true, detailAvailable = false)
        var state by mutableStateOf(HydrosUiState(
            initializing = false,
            month = "2026-10",
            bills = listOf(consume),
        ))
        var openedBill: String? = null
        ui.setContent { HydrosTheme { Surface {
            WalletScreen(
                state,
                onBillType = { type -> state = state.copy(billType = type, bills = listOf(recharge)) },
                onBill = { openedBill = it },
            )
        } } }

        ui.onNodeWithText("消费记录").performScrollTo().assertIsSelected()
        ui.onNodeWithText("宿舍热水").assertExists()
        ui.onNodeWithText("充值记录").performScrollTo().performClick()
        ui.onNodeWithText("充值", substring = false).performScrollTo().assertHasNoClickAction()
        ui.onNodeWithText("宿舍热水").assertDoesNotExist()
        ui.runOnIdle {
            assertEquals(BillType.RECHARGE, state.billType)
            assertEquals("2026-10", state.month)
            assertNull(openedBill)
        }
    }
    @Test fun updateFailureStaysOnPageWithRetry() {
        var checks = 0
        ui.setContent { HydrosTheme { HydrosSnackbarProvider { Surface {
            val updates = AppUpdateUiState(error = "检查失败，请检查网络后重试")
            HydrosNotificationEffects(HydrosUiState(), updates, {}, {}, {}, { checks++ }, showUpdateErrors = false)
            SoftwareUpdateScreen(updates) { checks++ }
        } } } }
        ui.onAllNodesWithText("检查失败，请检查网络后重试").assertCountEquals(1)
        ui.onNodeWithText("重试").performClick()
        assertEquals(1, checks)
        ui.onNodeWithText("检查失败，请检查网络后重试").assertExists()
        ui.onNodeWithText("更新历史").assertExists()
    }
    @Test fun blockedLogoutUsesSnackbarWithRecoveryRatherThanConfirmationDialog() {
        var sessions = 0
        val device = DeviceUi("test-device", "水表", "宿舍", DeviceCategory.SHOWER)
        val state = HydrosUiState(session = SessionUi(device, "owner", stage = SessionStage.OPEN_UNCONFIRMED))
        ui.setContent { HydrosTheme { HydrosSnackbarProvider { Surface {
            ManageAccountScreen(state, onPhone = {}, onPassword = {}, onLogout = {}, onSession = { sessions++ })
        } } } }
        ui.onNodeWithText("退出账户").performScrollTo().performClick()
        ui.onNodeWithText("请先结束当前会话，再退出账户。").assertIsDisplayed()
        ui.onNodeWithText("设备仍在使用").assertDoesNotExist()
        ui.onNodeWithText("返回使用页面").performClick()
        assertEquals(1, sessions)
    }
}
