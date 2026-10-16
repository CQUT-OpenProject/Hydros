package dev.uednd.hydros

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import dev.uednd.hydros.ui.components.*
import dev.uednd.hydros.ui.screens.LoginScreen
import dev.uednd.hydros.ui.theme.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import android.graphics.Bitmap
import android.graphics.Rect
import android.view.inspector.WindowInspector
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File

class SnackbarTest {
    @get:Rule val ui = createComposeRule()

    @Test fun resolvedAndRemovedMessagesClearOnlyTheirOwnNotice() {
        val state = HydrosSnackbarState()
        var message by mutableStateOf<String?>("设备离线")
        var mounted by mutableStateOf(true)
        ui.setContent { HydrosTheme { HydrosSnackbarProvider(state) {
            if (mounted) SnackbarMessage(message)
        } } }
        ui.onNodeWithText("设备离线").assertExists()
        ui.runOnIdle { message = null }
        ui.onNodeWithText("设备离线").assertDoesNotExist()

        ui.runOnIdle { message = "旧提示" }
        ui.onNodeWithText("旧提示").assertExists()
        ui.runOnIdle { state.show("其他操作失败"); message = null }
        ui.onNodeWithText("其他操作失败").assertExists()

        ui.runOnIdle { message = "页面提示" }
        ui.onNodeWithText("页面提示").assertExists()
        ui.runOnIdle { mounted = false }
        ui.onNodeWithText("页面提示").assertDoesNotExist()
    }

    @Test fun latestReplacesAndSameTextRestartsTimeout() {
        val state = HydrosSnackbarState()
        ui.setContent { HydrosTheme { HydrosSnackbarProvider(state) { Surface(Modifier.fillMaxSize()) {} } } }
        ui.mainClock.autoAdvance = false
        ui.runOnIdle { state.show("第一条") }
        ui.mainClock.advanceTimeBy(500)
        ui.onNodeWithText("第一条").assertIsDisplayed()
        ui.runOnIdle { state.show("第二条") }
        ui.mainClock.advanceTimeBy(3_000)
        ui.onNodeWithText("第一条").assertDoesNotExist()
        ui.onNodeWithText("第二条").assertIsDisplayed()
        ui.runOnIdle { state.show("第二条") }
        ui.mainClock.advanceTimeBy(3_000)
        ui.onNodeWithText("第二条").assertIsDisplayed()
        ui.mainClock.advanceTimeBy(1_200)
        ui.onNodeWithText("第二条").assertDoesNotExist()
        ui.mainClock.autoAdvance = true
    }

    @Test fun outsideLockDoesNotBlockTheUnderlyingControl() {
        val state = HydrosSnackbarState()
        var clicks = 0
        ui.setContent { HydrosTheme { HydrosSnackbarProvider(state) {
            Column(Modifier.fillMaxSize()) { Button(onClick = { clicks++ }, modifier = Modifier.testTag("outside")) { Text("页面操作") } }
        } } }
        ui.mainClock.autoAdvance = false
        ui.runOnIdle { state.show("提示") }
        ui.mainClock.advanceTimeBy(500)
        ui.onNodeWithTag("outside").performClick()
        ui.onNodeWithText("提示").assertIsDisplayed()
        assertEquals(1, clicks)
        ui.mainClock.advanceTimeBy(700)
        ui.onNodeWithTag("outside").performClick()
        ui.mainClock.advanceTimeByFrame()
        ui.onNodeWithText("提示").assertDoesNotExist()
        assertEquals(2, clicks)
        ui.mainClock.autoAdvance = true
    }

    @Test fun actionDismissesAndExecutesOnce() {
        val state = HydrosSnackbarState()
        var retried = 0
        ui.setContent { HydrosTheme { HydrosSnackbarProvider(state) { Surface(Modifier.fillMaxSize()) {} } } }
        ui.runOnIdle { state.show("加载失败", SnackbarAction("重试") { retried++ }, error = true) }
        ui.onNodeWithText("重试").performClick()
        assertEquals(1, retried)
        ui.onNodeWithText("加载失败").assertDoesNotExist()
    }

    @Test fun snackbarIsAboveDialogAndUsesTheSameState() {
        val state = HydrosSnackbarState()
        var modal by mutableStateOf(true)
        var actions = 0
        ui.setContent { HydrosTheme { HydrosSnackbarProvider(state) {
            Surface(Modifier.fillMaxSize()) {}
            if (modal) HydrosDialog(title = "编辑", onDismiss = { modal = false }, confirmButton = {
                TextButton(onClick = { state.show("保存失败", SnackbarAction("再试一次") { actions++ }, error = true) }) { Text("保存") }
            }) { Text("弹窗正文") }
        } } }
        ui.onNodeWithText("保存").performClick()
        ui.onAllNodesWithText("保存失败").assertCountEquals(1)
        ui.onNodeWithText("再试一次").performClick()
        assertEquals(1, actions)
        ui.onNodeWithText("弹窗正文").assertIsDisplayed()
        ui.runOnIdle { state.show("跨窗口提示"); modal = false }
        ui.onAllNodesWithText("跨窗口提示").assertCountEquals(1)
    }

    @Test fun bottomSheetMessagesRemainActionableInDarkTheme() {
        val state = HydrosSnackbarState()
        var actions = 0
        ui.setContent { HydrosTheme(ThemeMode.DARK) { HydrosSnackbarProvider(state) {
            Surface(Modifier.fillMaxSize()) {}
            HydrosBottomSheet("测试操作", "确认内容", {}) {
                TextButton(onClick = { state.show("无法完成", SnackbarAction("重试") { actions++ }, error = true) }) { Text("提交") }
            }
        } } }
        ui.onNodeWithText("提交").performClick()
        ui.onAllNodesWithText("无法完成").assertCountEquals(1)
        ui.onNodeWithText("重试").performClick()
        assertEquals(1, actions)
    }

    @Test fun loginValidationIsSnackbarAndKeepsFieldErrorSemantics() {
        ui.setContent { HydrosTheme { HydrosSnackbarProvider { Surface { LoginScreen() } } } }
        ui.onNode(hasText("登录") and hasClickAction()).performScrollTo().performClick()
        ui.onAllNodesWithText("请输入 11 位手机号").assertCountEquals(1)
        ui.onNodeWithContentDescription("手机号").assert(SemanticsMatcher.expectValue(SemanticsProperties.Error, "请输入 11 位手机号"))
        ui.onNodeWithContentDescription("手机号").performClick().performTextInput("13800138000")
        ui.onNode(hasText("登录") and hasClickAction()).performScrollTo().performClick()
        ui.onNodeWithText("请输入 6–32 位密码").assertIsDisplayed()
        ui.onNodeWithText("请输入 11 位手机号").assertDoesNotExist()
        // Check actual window coordinates: semantics alone cannot detect a keyboard covering a Popup.
        ui.waitUntil(5_000) {
            val roots = WindowInspector.getGlobalWindowViews()
            val page = roots.firstOrNull { it.javaClass.simpleName == "DecorView" }
            val popup = roots.firstOrNull { it.javaClass.simpleName == "PopupLayout" }
            if (page == null || popup == null) false else {
                val visible = Rect().also(page::getWindowVisibleDisplayFrame)
                val location = IntArray(2).also(popup::getLocationOnScreen)
                location[1] + popup.height <= visible.bottom + 2
            }
        }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val screenshot = instrumentation.uiAutomation.takeScreenshot()
        File(instrumentation.targetContext.cacheDir, "snackbar-keyboard.png").outputStream().use {
            screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        screenshot.recycle()
    }
}
