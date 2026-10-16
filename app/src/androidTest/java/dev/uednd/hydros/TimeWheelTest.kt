package dev.uednd.hydros

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import dev.uednd.hydros.ui.components.BillMonthPickerSheet
import dev.uednd.hydros.ui.components.HydrosHaptics
import dev.uednd.hydros.ui.components.MonthWheelColumn
import dev.uednd.hydros.ui.theme.HydrosTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.YearMonth

class TimeWheelTest {
    @get:Rule val ui = createComposeRule()

    @Test fun wheelDoesNotVibrateOnInitialComposition() {
        var lightCount = 0
        val testHaptics = object : HydrosHaptics() {
            override fun light() { lightCount++ }
        }
        var selected = 2

        ui.setContent {
            HydrosTheme {
                Surface {
                    Box(Modifier.fillMaxWidth().height(184.dp)) {
                        MonthWheelColumn(
                            values = (2017..2026).toList(),
                            selectedIndex = selected,
                            label = "年份",
                            haptics = testHaptics,
                            onSelected = { selected = it }
                        ) { "${it}年" }
                    }
                }
            }
        }

        ui.runOnIdle {
            assertEquals(0, lightCount)
            assertEquals(2, selected)
        }
    }

    @Test fun wheelTriggersLightHapticWhenItemClicked() {
        var lightCount = 0
        val testHaptics = object : HydrosHaptics() {
            override fun light() { lightCount++ }
        }
        var selected by mutableStateOf(2)

        ui.setContent {
            HydrosTheme {
                Surface {
                    Box(Modifier.fillMaxWidth().height(184.dp)) {
                        MonthWheelColumn(
                            values = (2017..2026).toList(),
                            selectedIndex = selected,
                            label = "年份",
                            haptics = testHaptics,
                            onSelected = { selected = it }
                        ) { "${it}年" }
                    }
                }
            }
        }

        ui.runOnIdle { assertEquals(0, lightCount) }
        ui.onNodeWithText("2020年").performClick()
        ui.runOnIdle {
            assertTrue("Clicking an unselected item should trigger haptics", lightCount > 0)
            assertEquals(3, selected)
        }
    }

    @Test fun wheelTriggersLightHapticWhenScrolled() {
        var lightCount = 0
        val testHaptics = object : HydrosHaptics() {
            override fun light() { lightCount++ }
        }
        var selected = 0

        ui.setContent {
            HydrosTheme {
                Surface {
                    Box(Modifier.fillMaxWidth().height(184.dp)) {
                        MonthWheelColumn(
                            values = (2017..2026).toList(),
                            selectedIndex = selected,
                            label = "年份",
                            haptics = testHaptics,
                            onSelected = { selected = it }
                        ) { "${it}年" }
                    }
                }
            }
        }

        ui.runOnIdle { assertEquals(0, lightCount) }
        ui.onNodeWithContentDescription("年份").performTouchInput { swipeUp() }
        ui.runOnIdle {
            assertTrue("Scrolling the wheel should trigger haptic feedback", lightCount > 0)
        }
    }

    @Test fun billMonthPickerSheetRendersAndSelectsMonth() {
        var confirmedMonth: YearMonth? = null
        var dismissed = false

        ui.setContent {
            HydrosTheme {
                Surface {
                    BillMonthPickerSheet(
                        month = YearMonth.of(2024, 5),
                        onDismiss = { dismissed = true },
                        onConfirm = { confirmedMonth = it }
                    )
                }
            }
        }

        ui.onNodeWithText("选择月份").assertExists()
        ui.onNodeWithText("年份").assertExists()
        ui.onNodeWithText("月份").assertExists()
        ui.onNodeWithText("2024年").assertExists()
        ui.onNodeWithText("5月").assertExists()

        ui.onNodeWithText("确定").performClick()
        ui.runOnIdle {
            assertEquals(YearMonth.of(2024, 5), confirmedMonth)
        }
    }
}
