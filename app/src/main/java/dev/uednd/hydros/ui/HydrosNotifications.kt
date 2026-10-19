package dev.uednd.hydros.ui

import dev.uednd.hydros.data.feedback.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import dev.uednd.hydros.ui.components.LocalHydrosSnackbar
import dev.uednd.hydros.ui.components.SnackbarAction
import dev.uednd.hydros.ui.components.SnackbarMessage
import dev.uednd.hydros.ui.state.AppUpdateUiState
import dev.uednd.hydros.ui.state.HydrosUiState

/** Collect application errors once, including background failures while a different page is open. */
@Composable
internal fun HydrosNotificationEffects(
    state: HydrosUiState,
    updates: AppUpdateUiState,
    onMessageConsumed: () -> Unit,
    onRefresh: () -> Unit,
    onMonth: (String) -> Unit,
    onCheckUpdates: () -> Unit,
    showUpdateErrors: Boolean = true,
    announceSession: Boolean = true,
    onSession: () -> Unit = {},
) {
    val snackbar = LocalHydrosSnackbar.current
    LaunchedEffect(state.message) {
        state.message?.let { onMessageConsumed(); snackbar.show(it, error = state.messageSeverity == FeedbackSeverity.ERROR, source = state.messageSource, severity = state.messageSeverity,
            action = if (state.messageAction == RecoveryAction.SESSION) SnackbarAction("进入使用页面", onSession) else null) }
    }
    SnackbarMessage(state.session?.message.takeIf { announceSession }, eventKey = state.session?.let { Triple(it.device.id, it.startedAt, it.message) },
        source = "device", severity = FeedbackSeverity.WARNING, action = SnackbarAction("进入使用页面", onSession))
    SnackbarMessage(updates.error.takeIf { showUpdateErrors }, action = SnackbarAction("重试", onCheckUpdates), source = "background")
}
