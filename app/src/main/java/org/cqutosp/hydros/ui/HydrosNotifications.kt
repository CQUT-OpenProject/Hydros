package org.cqutosp.hydros.ui

import org.cqutosp.hydros.data.feedback.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import org.cqutosp.hydros.ui.components.LocalHydrosSnackbar
import org.cqutosp.hydros.ui.components.SnackbarAction
import org.cqutosp.hydros.ui.components.SnackbarMessage
import org.cqutosp.hydros.ui.state.AppUpdateUiState
import org.cqutosp.hydros.ui.state.HydrosUiState

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
