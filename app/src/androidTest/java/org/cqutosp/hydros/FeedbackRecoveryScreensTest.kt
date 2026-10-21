package org.cqutosp.hydros

import androidx.compose.material3.Surface
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import org.cqutosp.hydros.ui.components.HydrosSnackbarProvider
import org.cqutosp.hydros.ui.screens.DeviceDetailScreen
import org.cqutosp.hydros.ui.screens.SessionScreen
import org.cqutosp.hydros.ui.state.*
import org.cqutosp.hydros.ui.theme.HydrosTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class FeedbackRecoveryScreensTest {
    @get:Rule val ui = createComposeRule()
    private val device = DeviceUi("id", "水表", "宿舍", DeviceCategory.SHOWER)
    @Test fun connectionErrorRemainsOnPageAndReconnectUsesSameDevice() {
        var retries = 0
        ui.setContent { HydrosTheme { HydrosSnackbarProvider { Surface {
            DeviceDetailScreen(device, HydrosUiState(connectingId = device.id, connectionStage = ConnectionStage.FAILED,
                connectionMessage = "设备未响应，请靠近设备后重试"), { assertEquals(device.id, it.id); retries++ }, {}, {}, {}, {}, { _, _ -> true })
        } } } }
        ui.onNodeWithText("设备未响应，请靠近设备后重试").performScrollTo().assertIsDisplayed()
        ui.mainClock.advanceTimeBy(5_000)
        ui.onNodeWithText("设备未响应，请靠近设备后重试").assertExists()
        ui.onNodeWithText("重新连接").performClick()
        assertEquals(1, retries)
    }
    @Test fun lostObservationKeepsLastStateAndOffersCloseInsteadOfOpeningAgain() {
        var finishes = 0
        val value = SessionUi(device, "owner", stage = SessionStage.USING, startedAt = 123)
        ui.setContent { HydrosTheme { HydrosSnackbarProvider { Surface {
            SessionScreen(value, { finishes++ }, {}, observation = SessionObservation(device.id, 123, unavailable = true))
        } } } }
        ui.onNodeWithText("暂时无法确认设备状态，请检查水流并恢复连接", substring = true).assertIsDisplayed()
        ui.onNodeWithText("点击以结束使用").performScrollTo().performClick()
        assertEquals(1, finishes)
        ui.onNodeWithText("连接设备").assertDoesNotExist()
    }
}
