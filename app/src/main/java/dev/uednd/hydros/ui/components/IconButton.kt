package dev.uednd.hydros.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

enum class IconBtnVariant {
    STANDARD,
    TONAL,
    DANGER
}

enum class IconBtnSize(val size: Dp, val iconSize: Dp) {
    MD(44.dp, 24.dp),
    SM(32.dp, 16.dp)
}

/**
 * Standard icon button:
 * 44dp (md) / 32dp (sm), circular shape, standard/tonal/danger semantic color variants.
 */
@Composable
fun HydrosIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    variant: IconBtnVariant = IconBtnVariant.STANDARD,
    size: IconBtnSize = IconBtnSize.MD,
    enabled: Boolean = true,
    description: String? = null,
    content: @Composable () -> Unit
) {
    val haptics = rememberHydrosHaptics()
    val colors = MaterialTheme.colorScheme
    val baseContentColor = when (variant) {
        IconBtnVariant.STANDARD -> colors.onSurface
        IconBtnVariant.TONAL -> colors.onSurfaceVariant
        IconBtnVariant.DANGER -> colors.error
    }
    val contentColor = if (enabled) baseContentColor else baseContentColor.copy(alpha = 0.4f)
    val rippleColor = when (variant) {
        IconBtnVariant.DANGER -> colors.error
        else -> colors.onSurface
    }

    Box(
        modifier = modifier
            .size(size.size)
            .clip(CircleShape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(color = rippleColor),
                enabled = enabled,
                role = Role.Button,
                onClick = { haptics.light(); onClick() }
            )
            .semantics(mergeDescendants = true) {
                role = Role.Button
                if (description != null) contentDescription = description
                if (!enabled) disabled()
            },
        contentAlignment = Alignment.Center
    ) {
        CompositionLocalProvider(LocalContentColor provides contentColor) {
            content()
        }
    }
}
