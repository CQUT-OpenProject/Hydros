package dev.uednd.hydros.ui.state

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.uednd.hydros.BuildConfig
import dev.uednd.hydros.data.update.*
import dev.uednd.hydros.data.analytics.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.Instant


data class AppUpdateUiState(
    val checking: Boolean = false,
    val checked: Boolean = false,
    val release: AppRelease? = null,
    val error: String? = null,
    val hasUpdate: Boolean = false,
    val lastChecked: Instant? = null,
    val install: InstallState = InstallState(),
)

data class ReleaseHistoryUiState(
    val loading: Boolean = false, val loaded: Boolean = false,
    val releases: List<AppRelease> = emptyList(), val error: String? = null,
    val nextPage: Int = 1, val hasMore: Boolean = true,
)

class AppInfoViewModel(
    private val source: AppUpdateSource = GitHubAppUpdates(),
    private val installer: AndroidAppInstaller? = null,
    private val analytics: AnalyticsTracker = NoOpAnalyticsTracker,
) : ViewModel() {
    private val mutable = MutableStateFlow(AppUpdateUiState())
    val updates = mutable.asStateFlow()
    private val historyMutable = MutableStateFlow(ReleaseHistoryUiState())
    val history = historyMutable.asStateFlow()
    private var installBusy = false
    private val detailMutable = MutableStateFlow(ReleaseHistoryUiState())
    val detail = detailMutable.asStateFlow()
    private var detailJob: Job? = null
    fun loadRelease(tag: String) {
        detailJob?.cancel()
        detailMutable.value = ReleaseHistoryUiState(loading = true)
        detailJob = viewModelScope.launch {
            try {
                val release = source.release(tag)
                detailMutable.value = ReleaseHistoryUiState(loaded = true, releases = listOfNotNull(release),
                    error = if (release == null) "未获取到此版本的发布信息" else null, hasMore = false)
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { analytics.networkFailure(NetworkOperation.UPDATE, failureCategory(error), HttpClass.UNKNOWN, RecoveryResult.FAILED); detailMutable.value = ReleaseHistoryUiState(error = "无法加载版本信息，请检查网络后重试", hasMore = false) }
        }
    }
    init {
        checkForUpdates()
        if (installer != null) viewModelScope.launch {
            while (true) {
                if (!installBusy) try {
                    val state = installer.refresh()
                    mutable.value = mutable.value.copy(install = state.copy(error = state.error ?: mutable.value.install.error))
                } catch (_: CancellationException) { throw CancellationException() }
                catch (_: Exception) { mutable.value = mutable.value.copy(install = InstallState(error = "无法读取更新任务，请重试")) }
                delay(1000)
            }
        }
    }

    fun checkForUpdates() {
        if (mutable.value.checking || mutable.value.install.phase != null) return
        mutable.value = mutable.value.copy(checking = true, error = null)
        viewModelScope.launch {
            try {
                val release = source.latest()
                mutable.value = mutable.value.copy(checking = false, checked = true, release = release,
                    lastChecked = Instant.now(), error = null,
                    hasUpdate = release?.let { isNewerRelease(it.version, BuildConfig.VERSION_NAME) } == true)
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                analytics.networkFailure(NetworkOperation.UPDATE, failureCategory(error), HttpClass.UNKNOWN, RecoveryResult.FAILED)
                mutable.value = mutable.value.copy(checking = false, error = "检查失败，请检查网络后重试")
            }
        }
    }

    fun installUpdate() {
        val release = mutable.value.release?.takeIf { mutable.value.hasUpdate } ?: return
        val native = installer ?: return
        installTask { native.start(release); native.refresh() }
    }

    fun continueInstall(launch: (Intent) -> Unit) {
        val native = installer ?: return
        installTask {
            mutable.value = mutable.value.copy(install = InstallState(InstallPhase.VERIFYING))
            val intent = native.installIntent()
            launch(intent)
            native.refresh()
        }
    }

    fun cancelInstall() {
        val native = installer ?: return
        installTask { native.cancel(); InstallState() }
    }

    private fun installTask(block: suspend () -> InstallState) {
        if (installBusy) return
        installBusy = true
        viewModelScope.launch {
            try { mutable.value = mutable.value.copy(install = block()) }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                val previous = mutable.value.install
                // Leave a downloaded task cancellable and retryable after a validation/intent failure.
                val phase = if (previous.phase == InstallPhase.VERIFYING) InstallPhase.AWAITING_CONFIRMATION else previous.phase
                mutable.value = mutable.value.copy(install = previous.copy(phase = phase,
                    error = error.message ?: "安装更新失败，请重试"))
            } finally { installBusy = false }
        }
    }

    fun loadHistory(refresh: Boolean = false) {
        val state = historyMutable.value
        if (state.loading || (!refresh && !state.hasMore)) return
        val page = if (refresh) 1 else state.nextPage
        historyMutable.value = state.copy(loading = true, error = null)
        viewModelScope.launch {
            try {
                val releases = source.history(page)
                historyMutable.value = ReleaseHistoryUiState(loaded = true,
                    releases = ((if (refresh) emptyList() else state.releases) + releases.releases).distinctBy { it.tag },
                    nextPage = page + 1, hasMore = releases.hasMore)
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { analytics.networkFailure(NetworkOperation.UPDATE, failureCategory(error), HttpClass.UNKNOWN, RecoveryResult.FAILED); historyMutable.value = state.copy(error = "无法加载更新历史，请检查网络后重试") }
        }
    }
}
