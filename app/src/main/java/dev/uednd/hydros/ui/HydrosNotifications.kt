package dev.uednd.hydros.ui

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
) {
    val snackbar = LocalHydrosSnackbar.current
    LaunchedEffect(state.message) {
        state.message?.let { onMessageConsumed(); snackbar.show(it, error = true) }
    }
    SnackbarMessage(state.session?.message, eventKey = state.session?.let { Triple(it.device.id, it.startedAt, it.message) })
    SnackbarMessage(state.walletError, action = SnackbarAction("重试", onRefresh))
    SnackbarMessage(state.billsError, eventKey = state.month to state.billsError, action = SnackbarAction("重试") { onMonth(state.month) })
    SnackbarMessage(updates.error.takeIf { showUpdateErrors }, action = SnackbarAction("重试", onCheckUpdates))
}
