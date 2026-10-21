package org.cqutosp.hydros.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Base bottom sheet container managing insets, rounded chrome, and dismissing. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HydrosBottomSheetContainer(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    dismissEnabled: Boolean = true,
    sheetGesturesEnabled: Boolean = dismissEnabled,
    content: @Composable ColumnScope.() -> Unit
) {
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { dismissEnabled || it != SheetValue.Hidden }
    )
    BoxWithConstraints {
        val maxSheetHeight = maxHeight * 0.85f
        ModalBottomSheet(
            modifier = modifier.dismissSnackbarOnInteraction(),
            onDismissRequest = { if (dismissEnabled) onDismiss() },
            sheetState = sheetState,
            sheetMaxWidth = 640.dp,
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.onSurface,
            scrimColor = Color.Black.copy(alpha = 0.5f),
            dragHandle = null,
            sheetGesturesEnabled = sheetGesturesEnabled
        ) {
            ModalSnackbarHost()
            Column(Modifier.fillMaxWidth().heightIn(max = maxSheetHeight), content = content)
        }
    }
}

/** Handle-free confirmation bottom sheet layout, with native inset handling. */
@Composable
fun HydrosBottomSheet(
    title: String,
    description: String,
    onDismiss: () -> Unit,
    dismissEnabled: Boolean = true,
    actions: @Composable RowScope.() -> Unit
) {
    HydrosBottomSheetContainer(
        onDismiss = onDismiss,
        dismissEnabled = dismissEnabled
    ) {
        Text(
            title,
            modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 8.dp)
                .semantics { heading() },
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center
        )
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())
            .padding(start = 24.dp, end = 24.dp, bottom = 20.dp)) {
            Text(
                description,
                modifier = Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.bodyMedium,
                lineHeight = 22.75.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
        Row(
            Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            content = actions
        )
    }
}

/** Equal-width confirmation actions matching the handle-free sheet layout. */
@Composable
fun ConfirmBottomSheet(
    title: String,
    description: String,
    confirm: String,
    onDismiss: () -> Unit,
    enabled: Boolean = true,
    destructive: Boolean = false,
    onConfirm: () -> Unit
) {
    val haptics = rememberHydrosHaptics()
    HydrosBottomSheet(title, description, onDismiss, dismissEnabled = enabled) {
        TextButton(
            onClick = onDismiss,
            enabled = enabled,
            modifier = Modifier.weight(1f).heightIn(min = 44.dp)
        ) { Text("取消") }
        Button(
            onClick = { if (destructive) haptics.warning() else haptics.light(); onConfirm() },
            enabled = enabled,
            modifier = Modifier.weight(1f).heightIn(min = 44.dp),
            colors = if (destructive) ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer
            ) else ButtonDefaults.buttonColors()
        ) { Text(confirm) }
    }
}
