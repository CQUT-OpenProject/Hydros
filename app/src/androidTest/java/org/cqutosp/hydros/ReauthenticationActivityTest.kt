package org.cqutosp.hydros

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.platform.app.InstrumentationRegistry
import org.cqutosp.hydros.data.HydrosApplication
import org.cqutosp.hydros.data.network.Credentials
import org.cqutosp.hydros.data.repository.RealAuthRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain

class ReauthenticationActivityTest {
    private val ui = createAndroidComposeRule<MainActivity>()
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as HydrosApplication
    private val auth get() = app.dependencies.auth as RealAuthRepository
    private val credentials = Credentials("test-only", "1", "2", "3", "13800138000")
    private val setup = object : ExternalResource() {
        override fun before() = runBlocking {
            auth.logout()
            app.dependencies.store.credentials(credentials)
            app.dependencies.store.updateAccount(credentials.key) { it.copy(session = null) }
            auth.restore()
        }
        override fun after() { runBlocking { auth.logout() } }
    }
    @get:Rule val rules: RuleChain = RuleChain.outerRule(setup).around(ui)
    private fun waitFor(text: String) = ui.waitUntil(8_000) {
        ui.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    }
    private fun expire() = ui.runOnIdle { runBlocking { auth.expire(credentials) } }

    @Test fun timeoutKeepsCurrentPageUntilNoticeFinishesAndCannotBeOverwritten() {
        waitFor("扫码连接")
        ui.mainClock.autoAdvance = false
        expire()
        ui.mainClock.advanceTimeBy(500)
        ui.onNodeWithText("登录已失效，请重新登录").assertIsDisplayed()
        ui.onNodeWithText("扫码连接").assertExists()
        ui.onNodeWithText("欢迎回到 Hydros").assertDoesNotExist()
        assertNull(auth.credentials.value)
        ui.onNode(hasText("我的") and hasClickAction()).performClick()
        ui.mainClock.advanceTimeBy(500)
        ui.onNodeWithText("扫码连接").assertExists()
        ui.mainClock.advanceTimeBy(3_600)
        ui.mainClock.autoAdvance = true
        waitFor("欢迎回到 Hydros")
        assertNull(auth.authState.value.reauthentication)
        ui.onNodeWithText("扫码连接").assertDoesNotExist()
    }

    @Test fun dismissalFromModalNavigatesAndDoesNotRepeatAfterRecreation() {
        waitFor("扫码连接")
        ui.onNode(hasText("我的") and hasClickAction()).performClick()
        ui.onNodeWithText("管理账户").performClick()
        ui.onNodeWithText("退出账户").performScrollTo().performClick()
        ui.mainClock.autoAdvance = false
        expire()
        ui.mainClock.advanceTimeBy(1_300)
        ui.onAllNodesWithText("登录已失效，请重新登录").assertCountEquals(1)
        // Tapping the dialog after the lock period dismisses the notice; logout itself is blocked.
        ui.onNodeWithText("取消").performClick()
        ui.mainClock.autoAdvance = true
        waitFor("欢迎回到 Hydros")
        ui.activityRule.scenario.recreate()
        waitFor("欢迎回到 Hydros")
        ui.onNodeWithText("登录已失效，请重新登录").assertDoesNotExist()
    }

    @Test fun backgroundExpirationWaitsForForegroundAndPendingNoticeSurvivesRecreation() {
        waitFor("扫码连接")
        ui.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        runBlocking { auth.expire(credentials) }
        val notice = auth.authState.value.reauthentication!!
        assertNull(auth.credentials.value)
        ui.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        ui.mainClock.autoAdvance = false
        ui.mainClock.advanceTimeBy(500)
        ui.onNodeWithText("登录已失效，请重新登录").assertIsDisplayed()
        ui.activityRule.scenario.recreate()
        ui.mainClock.advanceTimeBy(500)
        ui.onNodeWithText("登录已失效，请重新登录").assertIsDisplayed()
        assertEquals(notice, auth.authState.value.reauthentication)
        ui.mainClock.advanceTimeBy(4_500)
        ui.mainClock.autoAdvance = true
        waitFor("欢迎回到 Hydros")
        assertNull(auth.authState.value.reauthentication)
    }
}
