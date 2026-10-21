package dev.uednd.hydros.ui.state

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.uednd.hydros.BuildConfig
import dev.uednd.hydros.data.analytics.AnalyticsTracker
import dev.uednd.hydros.data.analytics.HttpClass
import dev.uednd.hydros.data.analytics.NetworkOperation
import dev.uednd.hydros.data.analytics.NoOpAnalyticsTracker
import dev.uednd.hydros.data.analytics.RecoveryResult
import dev.uednd.hydros.data.analytics.ResultCode
import dev.uednd.hydros.data.analytics.UpdateCheckOutcome
import dev.uednd.hydros.data.analytics.FailureCategory
import dev.uednd.hydros.data.analytics.failureCategory
import dev.uednd.hydros.data.update.InstallPhase
import dev.uednd.hydros.data.feedback.FailureOperation
import dev.uednd.hydros.data.feedback.failureFeedback
import dev.uednd.hydros.data.update.AppInstaller
import dev.uednd.hydros.data.update.AppRelease
import dev.uednd.hydros.data.update.AppUpdateSource
import dev.uednd.hydros.data.update.GitHubAppUpdates
import dev.uednd.hydros.data.update.InstallState
import dev.uednd.hydros.data.update.installationError
import dev.uednd.hydros.data.update.isNewerRelease
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
            catch (error: Exception) { analytics.networkFailure(NetworkOperation.UPDATE, failureCategory(error), HttpClass.UNKNOWN, RecoveryResult.FAILED); detailMutable.value = ReleaseHistoryUiState(error = failureFeedback(error, FailureOperation.UPDATE).text, hasMore = false) }
        }
    }

    init {
        checkForUpdates()
        installer?.let { native ->
            // Installation kills the old process, so success is observed on the next launch.
            if (native.consumeInstallCompleted()) analytics.updateInstallResult(ResultCode.SUCCESS)
            viewModelScope.launch {
                var priorPhase: InstallPhase? = null
                native.state.collect { install ->
                    val phase = install.phase
                    // Cancelling resets the state to idle without an error, which is not a success.
                    if (priorPhase != null && phase == null && install.error != null) {
                        analytics.updateInstallResult(ResultCode.FAILURE, FailureCategory.UNKNOWN)
                    }
                    priorPhase = phase
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
                analytics.updateCheckResult(if (hasUpdate) UpdateCheckOutcome.UPDATE_AVAILABLE else UpdateCheckOutcome.UP_TO_DATE)
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                analytics.networkFailure(NetworkOperation.UPDATE, failureCategory(error), HttpClass.UNKNOWN, RecoveryResult.FAILED)
                analytics.updateCheckResult(UpdateCheckOutcome.FAILURE, failureCategory(error))
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
                analytics.updateInstallResult(ResultCode.FAILURE, failureCategory(error))
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
            catch (error: Exception) { analytics.networkFailure(NetworkOperation.UPDATE, failureCategory(error), HttpClass.UNKNOWN, RecoveryResult.FAILED); historyMutable.value = state.copy(error = failureFeedback(error, FailureOperation.UPDATE).text) }
        }
    }
}
