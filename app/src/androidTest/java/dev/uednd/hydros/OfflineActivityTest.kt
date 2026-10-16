package dev.uednd.hydros

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import dev.uednd.hydros.data.HydrosApplication
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain

/** Exercises the normal Activity's default wiring, rather than only an injected composable. */
class OfflineActivityTest {
    private val ui = createAndroidComposeRule<MainActivity>()
    private val setup = object : ExternalResource() {
        override fun before() {
            val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
            assertTrue(app is OfflineTestApplication)
            runBlocking { (app as HydrosApplication).dependencies.auth.logout() }
        }
        override fun after() {
            val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as HydrosApplication
            runBlocking { app.dependencies.auth.logout() }
        }
    }
    @get:Rule val rules: RuleChain = RuleChain.outerRule(setup).around(ui)
    private fun waitFor(text: String) = ui.waitUntil(5_000) {
        ui.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    }
    @Test fun normalActivityUsesLocalPasswordLoginSmsAndUpdates() {
        waitFor("欢迎回到 Hydros")
        ui.onNodeWithContentDescription("手机号").performTextInput("13800138000")
        ui.onNodeWithContentDescription("密码").performTextInput("server123")
        ui.onNode(hasText("登录") and hasClickAction()).performClick()
        waitFor("扫码连接")
        ui.onNode(hasText("我的") and hasClickAction()).performClick()
        ui.onNodeWithText("管理账户").performClick()
        ui.onNodeWithText("退出账户").performScrollTo().performClick()
        ui.onNodeWithText("退出").performClick()
        waitFor("欢迎回到 Hydros")
        ui.onNodeWithText("验证码登录").performClick()
        ui.onNodeWithContentDescription("手机号").performTextReplacement("13800138000")
        ui.onNodeWithText("获取验证码").performClick()
        ui.waitUntil(5_000) {
            ui.onAllNodesWithText("秒后重新获取", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        ui.onNodeWithContentDescription("验证码").performTextInput("654321")
        // The two local logins still use the real repository's three-second throttle.
        ui.waitUntil(5_000) {
            ui.onAllNodes(hasText("登录") and hasClickAction() and isEnabled()).fetchSemanticsNodes().isNotEmpty()
        }
        ui.onNode(hasText("登录") and hasClickAction()).performClick()
        waitFor("扫码连接")
        ui.onNode(hasText("我的") and hasClickAction()).performClick()
        ui.onNodeWithText("关于 Hydros").performScrollTo().performClick()
        ui.onNodeWithText("当前版本").performClick()
        waitFor("当前已是最新版本")
    }
}
