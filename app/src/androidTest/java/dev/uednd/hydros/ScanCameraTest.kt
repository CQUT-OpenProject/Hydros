package dev.uednd.hydros

import android.Manifest
import androidx.camera.core.CameraSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import dev.uednd.hydros.ui.scanner.CameraPreview
import dev.uednd.hydros.ui.scanner.CameraStatus
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.TimeUnit

/** Real CameraX/YUV/ML Kit path. This does not prove physical focus, flash or distance performance. */
class ScanCameraTest {
    @get:Rule val ui = createComposeRule()
    @Test fun liveFramesDecodeAndCameraCanPauseAndRebind() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.CAMERA)
        val provider = ProcessCameraProvider.getInstance(context).get(15, TimeUnit.SECONDS)
        assumeTrue(provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA))
        var enabled by mutableStateOf(true)
        var status by mutableStateOf(CameraStatus())
        var frames = 0
        var failures = 0
        ui.setContent {
            CameraPreview(enabled, false, Modifier.fillMaxSize(), onStatus = { status = it },
                onCodes = { frames++ }, onFailure = { failures++ })
        }
        ui.waitUntil(15_000) { status.ready && frames > 0 }
        ui.runOnIdle { enabled = false }
        ui.waitUntil(5_000) { !status.ready }
        val before = frames
        ui.runOnIdle { enabled = true }
        ui.waitUntil(15_000) { status.ready && frames > before }
        ui.runOnIdle { assertNull(status.error); assertEquals(0, failures); enabled = false }
        ui.waitUntil(5_000) { !status.ready }
    }
}
