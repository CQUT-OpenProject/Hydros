package org.cqutosp.hydros.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Shared dialog chrome; callers own content, actions and operation state. */
@Composable
fun HydrosDialog(
    title: String,
    onDismiss: () -> Unit,
    confirmButton: @Composable () -> Unit,
    dismissEnabled: Boolean = true,
    dismissButton: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit
) {
    AlertDialog(
        modifier = Modifier.dismissSnackbarOnInteraction(),
        onDismissRequest = { if (dismissEnabled) onDismiss() },
        title = { Text(title, style = MaterialTheme.typography.titleLarge) },
        text = { ModalSnackbarHost(); content() },
        confirmButton = confirmButton,
        dismissButton = dismissButton,
        shape = MaterialTheme.shapes.extraLarge,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
    )
}

/** Scrollable choices shared by month selection and multiple QR results. */
@Composable
fun <T : Any> ChoiceDialog(
    title: String,
    options: List<T>,
    optionLabel: (T) -> String,
    onSelect: (T) -> Unit,
    onDismiss: () -> Unit,
    dismissLabel: String = "关闭"
) {
    val haptics = rememberHydrosHaptics()
    HydrosDialog(
        title = title,
        onDismiss = onDismiss,
        confirmButton = { TextButton(onDismiss) { Text(dismissLabel) } }
    ) {
        LazyColumn {
            items(options) { option ->
                TextButton(
                    onClick = { haptics.light(); onSelect(option) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                ) { Text(optionLabel(option)) }
            }
        }
    }
}
