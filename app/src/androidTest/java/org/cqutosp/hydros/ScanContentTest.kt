package org.cqutosp.hydros

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import org.cqutosp.hydros.ui.scanner.*
import org.cqutosp.hydros.ui.components.HydrosSnackbarProvider
import org.cqutosp.hydros.ui.screens.ScanContent
import org.cqutosp.hydros.ui.theme.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class ScanContentTest {
    @get:Rule val ui = createComposeRule()
    private fun show(
        session: ScanSession = ScanSession(), permission: Boolean = true,
        status: CameraStatus = CameraStatus(ready = true, hasFlash = true),
        mode: ThemeMode = ThemeMode.LIGHT, landscape: Boolean = false,
        photo: () -> Unit = {}, torch: () -> Unit = {},
        queryProgress: String? = null,
    ) {
        ui.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(
                if (landscape) DpSize(640.dp, 360.dp) else DpSize(360.dp, 720.dp))) {
                HydrosTheme(mode) { HydrosSnackbarProvider {
                    Surface {
                        val state by session.state.collectAsState()
                        ScanContent(state, permission, status, {}, onTorch = torch, onPhoto = photo,
                            onRetry = session::retryQuery, onChoose = session::choose,
                            onRescan = session::retry, queryProgress = queryProgress,
                            preview = { Box(it.background(Color(0xFF263238))) })
                    }
                } }
            }
        }
    }
    private fun snapshot(name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        File(context.cacheDir, "scan-$name.png").outputStream().use {
            ui.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
    @Test fun deniedCameraStillAllowsGallery() {
        var picks = 0
        show(permission = false, photo = { picks++ })
        ui.onNodeWithText("开启手电筒").performScrollTo().assertIsEnabled()
        ui.onNodeWithText("从相册选择").performScrollTo().performClick()
        assertEquals(1, picks)
        ui.onNodeWithText("允许相机").assertExists()
        snapshot("permission")
    }
    @Test fun torchReflectsHardwareAndActualState() {
        var toggles = 0
        show(status = CameraStatus(ready = true, hasFlash = true, torchOn = true), torch = { toggles++ })
        ui.onNodeWithText("关闭手电筒").performScrollTo().performClick()
        assertEquals(1, toggles)
        snapshot("light")
    }
    @Test fun noFlashIsExplained() {
        show(status = CameraStatus(ready = true, hasFlash = false))
        ui.onNodeWithText("当前相机不支持手电筒").assertDoesNotExist()
        ui.onNodeWithText("开启手电筒").performScrollTo().performClick()
        ui.onNodeWithText("当前相机不支持手电筒").assertIsDisplayed()
    }
    @Test fun photoProgressAndFailureRecoverToLiveScan() {
        val session = ScanSession().apply { pick(); picked(true) }
        show(session)
        ui.onNodeWithText("正在识别图片").performScrollTo().assertIsDisplayed()
        ui.onNodeWithText("从相册选择").assertIsNotEnabled()
        ui.runOnIdle { session.fail(session.state.value.generation, "图片无法读取") }
        ui.onNodeWithText("图片无法读取").assertIsDisplayed()
        ui.onAllNodesWithText("重新扫描").onFirst().performClick()
        ui.onNodeWithText("从相册选择").performScrollTo().assertIsEnabled()
        assertEquals(ScanPhase.SCANNING, session.state.value.phase)
    }
    @Test fun multipleDevicesSelectBeforeQueryAndDisableFurtherInput() {
        val session = ScanSession().apply { codes(0, listOf("${org.cqutosp.hydros.data.repository.DEVICE_BROADCAST_PREFIX},M,AABBCCDDEEFF", "${org.cqutosp.hydros.data.repository.DEVICE_BROADCAST_PREFIX},M,112233445566")) }
        show(session)
        ui.onNodeWithText("选择用水设备").assertIsDisplayed()
        ui.onNodeWithText("设备 112233445566").performClick()
        ui.onNodeWithText("正在查询设备").performScrollTo().assertIsDisplayed()
        ui.onNodeWithText("从相册选择").assertIsNotEnabled()
        assertEquals("112233445566", session.state.value.mac)
    }
    @Test fun failedLookupCanRetryWithoutCameraAndStillOffersRescan() {
        val session = ScanSession().apply {
            codes(0, listOf("${org.cqutosp.hydros.data.repository.DEVICE_BROADCAST_PREFIX},M,AABBCCDDEEFF"))
            resolved(0, null)
        }
        show(session)
        ui.onNodeWithText("重新扫描").performScrollTo().assertIsDisplayed()
        ui.onNodeWithText("重新查询").performScrollTo().performClick()
        assertEquals(ScanPhase.RESOLVING, session.state.value.phase)
        assertEquals("AABBCCDDEEFF", session.state.value.mac)
        ui.runOnIdle { session.resolved(session.state.value.generation, null) }
        ui.onNodeWithText("重新扫描").performScrollTo().performClick()
        assertEquals(ScanPhase.SCANNING, session.state.value.phase)
    }
    @Test fun cellularProgressAppearsDuringDeviceLookup() {
        val session = ScanSession().apply {
            codes(0, listOf("${org.cqutosp.hydros.data.repository.DEVICE_BROADCAST_PREFIX},M,AABBCCDDEEFF"))
        }
        show(session, queryProgress = "正在尝试移动网络…")
        ui.onNodeWithText("正在尝试移动网络…").performScrollTo().assertIsDisplayed()
        ui.onNodeWithText("从相册选择").assertIsNotEnabled()
    }
    @Test fun darkLandscapeControlsRemainReachable() {
        show(mode = ThemeMode.DARK, landscape = true)
        ui.onNodeWithText("从相册选择").performScrollTo().assertIsDisplayed().assertIsEnabled()
        snapshot("dark-landscape")
    }
}
