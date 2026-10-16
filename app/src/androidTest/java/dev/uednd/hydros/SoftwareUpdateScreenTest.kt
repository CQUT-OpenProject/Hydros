package dev.uednd.hydros

import android.graphics.Bitmap
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import dev.uednd.hydros.data.update.*
import dev.uednd.hydros.ui.components.*
import dev.uednd.hydros.ui.screens.*
import dev.uednd.hydros.ui.state.*
import dev.uednd.hydros.ui.theme.HydrosTheme
import dev.uednd.hydros.ui.theme.ThemeMode
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.time.Instant

class SoftwareUpdateScreenTest {
    @get:Rule val ui = createComposeRule()
    private val release = AppRelease("0.2.0", "v0.2.0", "## 新增\n- 完整的软件更新体验\n- 支持应用内查看更新历史", publishedAt = "2026-10-15T00:00:00Z",
        assets = listOf(ReleaseAsset("Hydros-universal.apk", "https://github.com/UE-DND/Hydros/releases/download/v0.2.0/app.apk", 100, null)))

    private fun screenshot(name: String) {
        val dir = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "update-screenshots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { ui.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun newestAndFailureCardsProvideRecoveryAndMoreInformation() {
        var state by mutableStateOf(AppUpdateUiState(checked = true, lastChecked = Instant.parse("2026-10-15T01:02:03Z")))
        var checks = 0; var history = 0
        ui.setContent { HydrosTheme { HydrosSnackbarProvider { HydrosPage("软件更新", onBack = {}) {
            SoftwareUpdateScreen(state, onHistory = { history++ }) { checks++ }
        } } } }
        ui.onNodeWithText("当前已是最新版本").assertIsDisplayed()
        screenshot("latest-light")
        ui.onNodeWithText("重新检查").performClick(); assertEquals(1, checks)
        ui.onNodeWithText("更新历史").performScrollTo().performClick(); assertEquals(1, history)
        ui.runOnIdle { state = AppUpdateUiState(error = "检查失败，请检查网络后重试") }
        ui.onNodeWithText("重试").performScrollTo().performClick(); assertEquals(2, checks)
        screenshot("error-light")
    }

    @Test fun updateDetailsInstallProgressAndContinuationMatchActions() {
        var state by mutableStateOf(AppUpdateUiState(checked = true, hasUpdate = true, release = release))
        var installs = 0; var continues = 0; var cancels = 0
        var theme by mutableStateOf(ThemeMode.LIGHT)
        ui.setContent { HydrosTheme(theme) { HydrosSnackbarProvider { HydrosPage("软件更新", onBack = {}) {
            SoftwareUpdateScreen(state, onInstall = { installs++ }, onContinue = { continues++ }, onCancel = { cancels++ }) { }
        } } } }
        ui.onNodeWithText("2026年10月15日").assertExists()
        screenshot("available-light")
        ui.runOnIdle { theme = ThemeMode.DARK }
        screenshot("available-dark")
        ui.runOnIdle { theme = ThemeMode.LIGHT }
        ui.onNodeWithText("立即安装更新").performScrollTo().performClick(); assertEquals(1, installs)
        ui.runOnIdle { state = state.copy(install = InstallState(InstallPhase.DOWNLOADING, 42)) }
        ui.onNodeWithText("42%").performScrollTo().assertIsDisplayed()
        ui.onNodeWithText("继续安装").assertDoesNotExist()
        ui.onNodeWithText("取消更新").performScrollTo().performClick(); assertEquals(1, cancels)
        ui.runOnIdle { state = state.copy(install = InstallState(InstallPhase.AWAITING_PERMISSION)) }
        ui.onNodeWithText("继续安装").performScrollTo().performClick(); assertEquals(1, continues)
        screenshot("permission-light")
    }

    @Test fun historyShowsVersionAndDetailUsesMarkdown() {
        var selected by mutableStateOf<AppRelease?>(null)
        ui.setContent { HydrosTheme { HydrosSnackbarProvider { HydrosPage("更新历史", onBack = {}) {
            selected?.let { ReleaseDetailScreen(it) } ?: ReleaseHistoryScreen(ReleaseHistoryUiState(loaded = true, releases = listOf(release), hasMore = false), { selected = it }, {})
        } } } }
        ui.onNodeWithText("v0.2.0").performClick()
        ui.onNodeWithText("版本标签").assertIsDisplayed()
        ui.onNodeWithText("完整的软件更新体验", substring = true).assertExists()
    }
}
