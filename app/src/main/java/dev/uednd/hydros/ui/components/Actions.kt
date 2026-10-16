package dev.uednd.hydros.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun PrimaryAction(
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    disabledReason: String? = null,
    icon: Int? = null,
    onClick: () -> Unit
) {
    val snackbar = LocalHydrosSnackbar.current
    val haptics = rememberHydrosHaptics()
    val effectiveEnabled = enabled && disabledReason == null
    Button(
        onClick = {
            if (disabledReason != null) {
                snackbar.show(disabledReason)
            } else {
                haptics.light()
                onClick()
            }
        },
        enabled = enabled,
        modifier = modifier.fillMaxWidth().heightIn(min = 44.dp).semantics {
            if (disabledReason != null) stateDescription = disabledReason
        },
        shape = CircleShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (effectiveEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primary.copy(alpha = 0.4f),
            contentColor = if (effectiveEnabled) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.4f),
            disabledContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f),
            disabledContentColor = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.4f)
        )
    ) {
        if (icon != null) {
            Glyph(icon, modifier = Modifier.size(20.dp), tint = if (effectiveEnabled) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.4f))
            Spacer(Modifier.width(8.dp))
        }
        Text(label, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun SecondaryAction(
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    disabledReason: String? = null,
    icon: Int? = null,
    onClick: () -> Unit
) {
    val snackbar = LocalHydrosSnackbar.current
    val haptics = rememberHydrosHaptics()
    val effectiveEnabled = enabled && disabledReason == null
    OutlinedButton(
        onClick = {
            if (disabledReason != null) {
                snackbar.show(disabledReason)
            } else {
                haptics.light()
                onClick()
            }
        },
        enabled = enabled,
        modifier = modifier.fillMaxWidth().heightIn(min = 44.dp).semantics {
            if (disabledReason != null) stateDescription = disabledReason
        },
        shape = CircleShape,
        border = BorderStroke(
            1.dp,
            if (effectiveEnabled) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
        ),
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = if (effectiveEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primary.copy(alpha = 0.4f),
            disabledContentColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
        ),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)
    ) {
        if (icon != null) {
            Glyph(icon, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(label, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun TextAction(
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    danger: Boolean = false,
    disabledReason: String? = null,
    trailingIcon: Int? = null,
    onClick: () -> Unit
) {
    val snackbar = LocalHydrosSnackbar.current
    val haptics = rememberHydrosHaptics()
    val effectiveEnabled = enabled && disabledReason == null
    val contentColor = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    TextButton(
        onClick = {
            if (disabledReason != null) {
                snackbar.show(disabledReason)
            } else {
                haptics.light()
                onClick()
            }
        },
        enabled = enabled,
        modifier = modifier.heightIn(min = 44.dp).semantics {
            if (disabledReason != null) stateDescription = disabledReason
        },
        shape = CircleShape,
        colors = ButtonDefaults.textButtonColors(
            contentColor = if (effectiveEnabled) contentColor else contentColor.copy(alpha = 0.4f),
            disabledContentColor = contentColor.copy(alpha = 0.4f)
        )
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        if (trailingIcon != null) {
            Spacer(Modifier.width(4.dp))
            Glyph(trailingIcon, modifier = Modifier.size(16.dp), tint = if (effectiveEnabled) contentColor else contentColor.copy(alpha = 0.4f))
        }
    }
}

@Composable
fun DangerAction(
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    disabledReason: String? = null,
    icon: Int? = null,
    onClick: () -> Unit
) {
    val snackbar = LocalHydrosSnackbar.current
    val haptics = rememberHydrosHaptics()
    val effectiveEnabled = enabled && disabledReason == null
    Button(
        onClick = {
            if (disabledReason != null) {
                snackbar.show(disabledReason)
            } else {
                haptics.warning()
                onClick()
            }
        },
        enabled = enabled,
        shape = CircleShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (effectiveEnabled) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f),
            contentColor = if (effectiveEnabled) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.4f),
            disabledContainerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f),
            disabledContentColor = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.4f)
        ),
        modifier = modifier.fillMaxWidth().heightIn(min = 44.dp).semantics {
            if (disabledReason != null) stateDescription = disabledReason
        }
    ) {
        if (icon != null) {
            Glyph(icon, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onErrorContainer)
            Spacer(Modifier.width(8.dp))
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onErrorContainer,
            fontWeight = FontWeight.SemiBold
        )
    }
}

/** Horizontal row of equal-priority actions, spaced by 12dp. */
@Composable
fun ActionRow(
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content
    )
}


