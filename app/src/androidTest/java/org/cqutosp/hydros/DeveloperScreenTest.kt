package org.cqutosp.hydros

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.cqutosp.hydros.ui.screens.DeveloperScreen
import org.cqutosp.hydros.ui.theme.HydrosTheme
import org.cqutosp.hydros.ui.theme.ThemeMode
import org.junit.Rule
import org.junit.Test

class DeveloperScreenTest {
    @get:Rule val ui = createComposeRule()

    @Test fun developerScreenRendersWithoutCrash() {
        ui.setContent {
            HydrosTheme(ThemeMode.LIGHT) {
                DeveloperScreen()
            }
        }
        ui.onNodeWithText("强制蓝牙连接").assertDoesNotExist()
    }
}
