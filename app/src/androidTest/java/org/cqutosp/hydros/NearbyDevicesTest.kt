package org.cqutosp.hydros

import android.Manifest
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.platform.app.InstrumentationRegistry
import org.cqutosp.hydros.data.repository.NearbyDiscoveryState
import org.cqutosp.hydros.ui.components.HydrosSnackbarProvider
import org.cqutosp.hydros.ui.screens.NearbyDevices
import org.cqutosp.hydros.ui.state.*
import org.cqutosp.hydros.ui.theme.HydrosTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class NearbyDevicesTest {
    @get:Rule val ui = createComposeRule()

    @Test fun selectionRescanBackgroundAndDisposalUseTheSameWorkflowCallbacks() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT).forEach {
            instrumentation.uiAutomation.grantRuntimePermission(instrumentation.targetContext.packageName, it)
        }
        val owner = object : LifecycleOwner {
            val registry = LifecycleRegistry(this)
            override val lifecycle get() = registry
        }
        val device = DeviceUi("registered", "测试水表", "宿舍", DeviceCategory.SHOWER)
        var state by mutableStateOf(NearbyDiscoveryState())
        var shown by mutableStateOf(true)
        var scans = 0
        var stops = 0
        var selected: String? = null
        ui.runOnUiThread { owner.registry.currentState = Lifecycle.State.RESUMED }
        ui.setContent {
            HydrosTheme { HydrosSnackbarProvider { Surface {
                CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                    if (shown) NearbyDevices(state, listOf(device), { scans++ }, { stops++ }, { selected = it }, mapOf(device.id to "宿舍花洒"))
                }
            } } }
        }
        ui.onNodeWithText("扫描附近设备").performClick()
        assertEquals(1, scans)
        ui.runOnIdle { state = NearbyDiscoveryState(searching = true, hasSearched = true) }
        ui.onNodeWithText("正在查找附近设备…").assertIsDisplayed()
        ui.onNodeWithText("重新扫描").assertDoesNotExist()
        ui.runOnIdle { state = state.copy(searching = false, devices = listOf(device)) }
        ui.onNodeWithText("宿舍花洒").assertIsDisplayed()
        ui.onNodeWithText(device.location).assertDoesNotExist()
        ui.onNodeWithText("信号", substring = true).assertDoesNotExist()
        ui.onNodeWithText(device.status.label, substring = true).assertDoesNotExist()
        ui.onNodeWithText("宿舍花洒").performClick()
        assertEquals(device.id, selected)
        ui.onNodeWithText("重新扫描").performClick()
        assertEquals(2, scans)
        ui.runOnIdle { owner.registry.currentState = Lifecycle.State.CREATED }
        assertEquals(1, stops)
        ui.runOnIdle { shown = false }
        ui.waitForIdle()
        assertEquals(2, stops)
    }
    @Test fun partialLookupFailureKeepsSelectableDevicesAndPersistentSummary() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT).forEach {
            instrumentation.uiAutomation.grantRuntimePermission(instrumentation.targetContext.packageName, it)
        }
        val device = DeviceUi("registered", "测试水表", "宿舍", DeviceCategory.SHOWER)
        var selected: String? = null
        ui.setContent { HydrosTheme { HydrosSnackbarProvider { Surface {
            NearbyDevices(NearbyDiscoveryState(hasSearched = true, completed = true, devices = listOf(device),
                error = java.io.IOException("raw diagnostic"), failedLookups = 1), listOf(device), {}, {}, { selected = it })
        } } } }
        ui.onNodeWithText("部分设备信息暂未加载，已找到的设备仍可选择").assertExists()
        ui.onNodeWithText("测试水表").performClick()
        assertEquals(device.id, selected)
        ui.mainClock.advanceTimeBy(5_000)
        ui.onNodeWithText("部分设备信息暂未加载，已找到的设备仍可选择").assertExists()
        ui.onNodeWithText("raw diagnostic").assertDoesNotExist()
    }

}
