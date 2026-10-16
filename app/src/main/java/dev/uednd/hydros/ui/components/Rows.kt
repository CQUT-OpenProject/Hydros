package dev.uednd.hydros.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import dev.uednd.hydros.R

@Composable
fun StatusBadge(
    text: String,
    modifier: Modifier = Modifier,
    icon: Int? = null,
    primary: Boolean = true
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = modifier,
        shape = CircleShape,
        color = if (primary) colors.primaryContainer.copy(alpha = 0.45f) else colors.surfaceVariant,
        contentColor = if (primary) colors.primary else colors.onSurfaceVariant
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (icon != null) {
                Glyph(
                    icon,
                    modifier = Modifier.size(16.dp),
                    tint = if (primary) colors.primary else colors.onSurfaceVariant
                )
            }
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Normal)
            )
        }
    }
}

@Composable
fun InfoRow(
    title: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1.3f)
        )
    }
}

@Composable
fun MineRow(
    title: String,
    modifier: Modifier = Modifier,
    supporting: String = "",
    icon: Int? = null,
    tone: IconTone = IconTone.NEUTRAL,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null
) {
    val content: @Composable () -> Unit = {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 60.dp)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (icon != null) {
                IconTile(icon, tone = tone)
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (supporting.isNotBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = supporting,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (trailing != null) {
                trailing()
            } else if (onClick != null) {
                Glyph(
                    R.drawable.ic_chevron,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    if (onClick != null) {
        Surface(
            onClick = onClick,
            shape = MaterialTheme.shapes.small, // 12.dp
            color = MaterialTheme.colorScheme.surface,
            modifier = modifier.fillMaxWidth()
        ) {
            content()
        }
    } else {
        Surface(
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.surface,
            modifier = modifier.fillMaxWidth()
        ) {
            content()
        }
    }
}

@Composable
fun MenuRow(
    icon: Int,
    title: String,
    supporting: String = "",
    onClick: () -> Unit
) {
    MineRow(
        title = title,
        supporting = supporting,
        icon = icon,
        tone = IconTone.NEUTRAL,
        onClick = onClick
    )
}

@Composable
fun MenuRow(
    icon: Int,
    title: String,
    supporting: String = "",
    tone: IconTone,
    trailing: (@Composable () -> Unit)? = null,
    onClick: () -> Unit
) {
    MineRow(
        title = title,
        supporting = supporting,
        icon = icon,
        tone = tone,
        trailing = trailing,
        onClick = onClick
    )
}

/** Standard selectable radio option row within a Section. */
@Composable
fun RadioRow(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    supporting: String = "",
    enabled: Boolean = true
) {
    val haptics = rememberHydrosHaptics()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .selectable(
                selected = selected,
                enabled = enabled,
                role = Role.RadioButton,
                onClick = { if (!selected) { haptics.light(); onClick() } }
            )
            .heightIn(min = 60.dp)
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                title,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (supporting.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    supporting,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        RadioButton(
            selected = selected,
            onClick = null,
            enabled = enabled,
            colors = RadioButtonDefaults.colors(
                selectedColor = MaterialTheme.colorScheme.primary,
                unselectedColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            )
        )
    }
}

/** Checkbox card row, e.g. terms agreement. */
@Composable
fun CheckboxRow(
    text: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    checkboxTag: String? = null
) {
    val haptics = rememberHydrosHaptics()
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        modifier = modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { haptics.light(); onCheckedChange(!checked) }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Checkbox(
                modifier = checkboxTag?.let(Modifier::testTag) ?: Modifier,
                checked = checked,
                onCheckedChange = { haptics.light(); onCheckedChange(it) },
                enabled = enabled
            )
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

/** Row with an arbitrary action slot (e.g. badge, small button). */
@Composable
fun ActionRow(
    title: String,
    supporting: String = "",
    icon: Int? = null,
    tone: IconTone = IconTone.NEUTRAL,
    modifier: Modifier = Modifier,
    action: @Composable () -> Unit
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        if (icon != null) {
            IconTile(res = icon, tone = tone)
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (supporting.isNotBlank()) {
                Text(
                    text = supporting,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        action()
    }
}

/** Permission checklist item with inline status badge or grant button. */
@Composable
fun PermissionRow(
    title: String,
    supporting: String,
    icon: Int,
    tone: IconTone,
    isGranted: Boolean,
    onRequest: () -> Unit,
    modifier: Modifier = Modifier
) {
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val grantedLabel = textMeasurer.measure(
        text = "已允许",
        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Normal),
        maxLines = 1
    )
    val requestLabel = textMeasurer.measure(
        text = "授权",
        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
        maxLines = 1
    )
    // Reserve both states, including their icon/padding and the button touch target.
    // Keep a small layout-rounding margin so the action slot never pushes the copy
    // into a different line layout when the permission state changes.
    val actionWidth = with(density) {
        maxOf(ButtonDefaults.MinWidth, grantedLabel.size.width.toDp() + 44.dp, requestLabel.size.width.toDp() + 36.dp)
    }
    val actionHeight = with(density) {
        maxOf(48.dp, grantedLabel.size.height.toDp() + 8.dp, requestLabel.size.height.toDp() + 12.dp)
    }
    ActionRow(
        title = title,
        supporting = supporting,
        icon = icon,
        tone = tone,
        modifier = modifier
    ) {
        Box(Modifier.width(actionWidth).height(actionHeight), contentAlignment = Alignment.CenterEnd) {
            if (isGranted) {
                StatusBadge(
                    text = "已允许",
                    icon = R.drawable.ic_check,
                    primary = true
                )
            } else {
                Button(
                    onClick = onRequest,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 36.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    )
                ) {
                    Text(
                        text = "授权",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}


