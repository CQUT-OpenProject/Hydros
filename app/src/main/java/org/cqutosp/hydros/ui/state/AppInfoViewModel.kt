package org.cqutosp.hydros.ui.state

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import org.cqutosp.hydros.BuildConfig
import org.cqutosp.hydros.data.feedback.FailureOperation
import org.cqutosp.hydros.data.feedback.failureFeedback
import org.cqutosp.hydros.data.update.AppInstaller
import org.cqutosp.hydros.data.update.AppRelease
import org.cqutosp.hydros.data.update.AppUpdateSource
import org.cqutosp.hydros.data.update.GitHubAppUpdates
import org.cqutosp.hydros.data.update.InstallState
import org.cqutosp.hydros.data.update.installationError
import org.cqutosp.hydros.data.update.isNewerRelease
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
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
    private val installer: AppInstaller? = null,
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
            catch (error: Exception) { detailMutable.value = ReleaseHistoryUiState(error = failureFeedback(error, FailureOperation.UPDATE).text, hasMore = false) }
        }
    }

    init {
        checkForUpdates()
        installer?.let { native ->
            // Clear the one-shot completion marker left by the previous process.
            native.consumeInstallCompleted()
            viewModelScope.launch {
                native.state.collect { install ->
                    mutable.value = mutable.value.copy(install = install)
                }
            }
            viewModelScope.launch { runCatching { native.refresh() } }
        }
    }

    fun checkForUpdates() {
        if (mutable.value.checking || mutable.value.install.phase != null) return
        mutable.value = mutable.value.copy(checking = true, error = null)
        viewModelScope.launch {
            try {
                val release = source.latest()
                val hasUpdate = release?.let { isNewerRelease(it.version, BuildConfig.VERSION_NAME) } == true
                mutable.value = mutable.value.copy(checking = false, checked = true, release = release,
                    lastChecked = Instant.now(), error = null, hasUpdate = hasUpdate)
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                mutable.value = mutable.value.copy(checking = false, error = failureFeedback(error, FailureOperation.UPDATE).text)
            }
        }
    }

    /** The Activity resumed: a permission change or a finished system install may have advanced the task. */
    fun onUpdateResumed() {
        val native = installer ?: return
        viewModelScope.launch { runCatching { native.refresh() } }
    }

    /** One-shot settings or system-confirmation intent, consumed by the Activity. */
    fun takeUpdateLaunchIntent(): Intent? = installer?.takeLaunchIntent()

    /** Puts a one-shot intent back when the Activity could not launch it. */
    fun restoreUpdateLaunchIntent(intent: Intent) = installer?.restoreLaunchIntent(intent)

    fun installUpdate() {
        val release = mutable.value.release?.takeIf { mutable.value.hasUpdate } ?: return
        val native = installer ?: return
        installTask { native.start(release) }
    }

    fun continueInstall(launch: (Intent) -> Unit) {
        val native = installer ?: return
        installTask {
            native.continueInstall()
            native.takeLaunchIntent()?.let(launch)
        }
    }

    fun cancelInstall() {
        val native = installer ?: return
        installTask { native.cancel() }
    }

    /**
     * The installer's StateFlow is the only writer of install state; these calls just trigger it.
     * Copying the returned snapshot here would race with newer emissions.
     */
    private fun installTask(block: suspend () -> Unit) {
        if (installBusy) return
        installBusy = true
        viewModelScope.launch {
            try { block() }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                mutable.value = mutable.value.copy(install = mutable.value.install.copy(error = installationError(error)))
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
            catch (error: Exception) { historyMutable.value = state.copy(error = failureFeedback(error, FailureOperation.UPDATE).text) }
        }
    }
}
