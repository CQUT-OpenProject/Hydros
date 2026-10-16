package dev.uednd.hydros

import android.net.Uri
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.core.app.ActivityOptionsCompat
import androidx.test.platform.app.InstrumentationRegistry
import dev.uednd.hydros.ui.screens.ScanScreen
import dev.uednd.hydros.ui.components.HydrosSnackbarProvider
import dev.uednd.hydros.ui.theme.HydrosTheme
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Exercises picker -> real image decoding -> shared resolver -> navigation, without camera hardware. */
class ScanScreenTest {
    @get:Rule val ui = createComposeRule()
    private class Results : ActivityResultRegistry(), ActivityResultRegistryOwner {
        override val activityResultRegistry: ActivityResultRegistry get() = this
        var pendingPicker: Int? = null
        override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
            when (contract) {
                is ActivityResultContracts.RequestPermission -> dispatchResult(requestCode, false)
                is ActivityResultContracts.PickVisualMedia -> pendingPicker = requestCode
                else -> error("Unexpected contract: $contract")
            }
        }
        fun pick(uri: Uri?) { dispatchResult(pendingPicker!!, uri); pendingPicker = null }
    }
    @Test fun disablingDuringResolutionCancelsRequestAndPreventsNavigation() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val file = File(instrumentation.targetContext.cacheDir, "disabled-scan-qr.png")
        instrumentation.context.assets.open("scan/normal.png").use { input -> file.outputStream().use { input.copyTo(it) } }
        val results = Results()
        val enabled = mutableStateOf(true)
        val response = CompletableDeferred<String?>()
        var resolving = false
        var cancelled = false
        var navigated = false
        try {
            ui.setContent {
                CompositionLocalProvider(LocalActivityResultRegistryOwner provides results) {
                    HydrosTheme { HydrosSnackbarProvider { Surface {
                        ScanScreen(enabled = enabled.value, onResolve = { _, _ ->
                            resolving = true
                            try { response.await() } finally { cancelled = true }
                        }, onSelect = { navigated = true })
                    } } }
                }
            }
            ui.onNodeWithText("从相册选择").performScrollTo().performClick()
            ui.runOnIdle { results.pick(Uri.fromFile(file)) }
            ui.waitUntil(10_000) { resolving }
            ui.runOnIdle { enabled.value = false }
            ui.waitUntil(5_000) { cancelled }
            ui.runOnIdle { response.complete("device"); assertFalse(navigated) }
            ui.waitForIdle()
            assertFalse(navigated)
        } finally { file.delete() }
    }

    @Test fun cancelledPickerResumesAndSamePhotoCanRetryFailedQueryAndNavigateOnce() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val file = File(instrumentation.targetContext.cacheDir, "screen-test-qr.png")
        instrumentation.context.assets.open("scan/normal.png").use { input -> file.outputStream().use { input.copyTo(it) } }
        val results = Results()
        val response = CompletableDeferred<String?>()
        var calls = 0
        val navigated = mutableListOf<String>()
        try {
            ui.setContent {
                CompositionLocalProvider(LocalActivityResultRegistryOwner provides results) {
                    HydrosTheme { HydrosSnackbarProvider { Surface {
                        ScanScreen(onResolve = { mac, rssi ->
                            assertEquals("AABBCCDDEEFF", mac)
                            assertNull(rssi)
                            calls++
                            if (calls == 1) null else response.await()
                        }, onSelect = { navigated.add(it) })
                    } } }
                }
            }
            ui.onNodeWithText("从相册选择").performScrollTo().performClick()
            ui.runOnIdle { results.pick(null) }
            ui.onNodeWithText("从相册选择").assertIsEnabled().performClick()
            ui.runOnIdle { results.pick(Uri.fromFile(file)) }
            ui.waitUntil(10_000) { ui.onAllNodesWithText("重新扫描").fetchSemanticsNodes().isNotEmpty() }
            ui.runOnIdle { assertEquals(1, calls); assertTrue(navigated.isEmpty()) }
            ui.onNodeWithText("重新扫描").performScrollTo().performClick()
            ui.onNodeWithText("从相册选择").performScrollTo().performClick()
            ui.runOnIdle { results.pick(Uri.fromFile(file)) }
            ui.waitUntil(10_000) { calls == 2 }
            ui.onNodeWithText("从相册选择").assertIsNotEnabled()
            ui.runOnIdle { response.complete("device-id") }
            ui.waitUntil(5_000) { navigated.isNotEmpty() }
            ui.runOnIdle { assertEquals(listOf("device-id"), navigated); assertEquals(2, calls) }
        } finally { file.delete() }
    }
}
