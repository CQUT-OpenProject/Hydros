package dev.uednd.hydros.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

enum class IconTone {
    PRIMARY,
    SECONDARY,
    TERTIARY,
    NEUTRAL
}

@Composable
fun Glyph(
    res: Int,
    modifier: Modifier = Modifier,
    description: String? = null,
    tint: Color = LocalContentColor.current
) {
    Icon(
        painter = painterResource(res),
        contentDescription = description,
        modifier = modifier.size(24.dp),
        tint = tint
    )
}

@Composable
fun IconTile(
    res: Int,
    modifier: Modifier = Modifier,
    tone: IconTone = IconTone.NEUTRAL,
    tertiary: Boolean = false,
    size: Dp = 36.dp,
    iconSize: Dp = 18.dp
) {
    val effectiveTone = if (tertiary) IconTone.TERTIARY else tone
    val colors = MaterialTheme.colorScheme
    val (bgColor, fgColor) = when (effectiveTone) {
        IconTone.PRIMARY -> colors.primaryContainer.copy(alpha = 0.75f) to colors.primary
        IconTone.SECONDARY -> colors.secondaryContainer.copy(alpha = 0.75f) to colors.onSecondaryContainer
        IconTone.TERTIARY -> colors.tertiaryContainer.copy(alpha = 0.75f) to colors.onTertiaryContainer
        IconTone.NEUTRAL -> colors.surfaceVariant.copy(alpha = 0.75f) to colors.onSurfaceVariant
    }
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        color = bgColor,
        contentColor = fgColor
    ) {
        Box(Modifier.size(size), contentAlignment = Alignment.Center) {
            Glyph(res, modifier = Modifier.size(iconSize), tint = fgColor)
        }
    }
}

