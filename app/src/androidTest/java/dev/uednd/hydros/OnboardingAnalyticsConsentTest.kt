package dev.uednd.hydros

import androidx.compose.material3.Surface
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.uednd.hydros.ui.screens.OnboardingScreen
import dev.uednd.hydros.ui.theme.HydrosTheme
import org.junit.Rule
import org.junit.Test

class OnboardingAnalyticsConsentTest {
    @get:Rule val ui = createComposeRule()

    @Test fun analyticsConsentStartsCheckedAndCanBeDeclined() {
        ui.setContent {
            HydrosTheme {
                Surface { OnboardingScreen(onComplete = {}) }
            }
        }
        ui.onNodeWithText("下一步").performClick()
        ui.onNodeWithText("阅读相关条款").assertIsDisplayed()
        ui.onNodeWithText("匿名使用统计").assertIsDisplayed()
        ui.onNodeWithTag("analytics-consent").assertIsOn()
        ui.onNodeWithTag("analytics-consent").performClick()
        ui.onNodeWithTag("analytics-consent").assertIsOff()
    }
}
