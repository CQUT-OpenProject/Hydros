package dev.uednd.hydros

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import dev.uednd.hydros.ui.screens.DeveloperScreen
import dev.uednd.hydros.ui.theme.HydrosTheme
import dev.uednd.hydros.ui.theme.ThemeMode
import org.junit.Rule
import org.junit.Test

class DeveloperScreenTest {
    @get:Rule val ui = createComposeRule()

    @Test fun developerScreenRendersWithoutCrash() {
        ui.setContent {
            HydrosTheme(ThemeMode.LIGHT) {
                DeveloperScreen(
                    forceBluetoothConnection = false,
                    forceBluetoothEnabled = true,
                    onForceBluetoothConnection = {},
                )
            }
        }
        ui.onNodeWithText("强制蓝牙连接").assertExists()
    }
}
