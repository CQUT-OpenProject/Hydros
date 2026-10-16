package dev.uednd.hydros.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp

/** One lazy row of a continuous section, without a bitmap shadow as tall as the full list. */
@Composable
internal fun LazySectionItem(
    first: Boolean,
    last: Boolean,
    showBorder: Boolean = true,
    content: @Composable ColumnScope.() -> Unit
) {
    val corner = 20.dp
    val contentInset = 6.dp
    val shape = RoundedCornerShape(
        topStart = if (first) corner else 0.dp, topEnd = if (first) corner else 0.dp,
        bottomStart = if (last) corner else 0.dp, bottomEnd = if (last) corner else 0.dp
    )
    val colors = MaterialTheme.colorScheme
    val border = if (colors.surface.luminance() < 0.5f) Color(0xFF2E3038) else Color(0xFFD4E0EB)
    val modifier = if (showBorder) Modifier.fillMaxWidth().drawWithCache {
        val stroke = 1.dp.toPx()
        val radius = corner.toPx()
        val top = if (first) stroke / 2f else -radius
        val bottom = if (last) size.height - stroke / 2f else size.height + radius
        onDrawWithContent {
            drawContent()
            // Extend unfinished ends outside this row, so only the outer perimeter is visible.
            clipRect {
                drawRoundRect(border, Offset(stroke / 2f, top), Size(size.width - stroke, bottom - top),
                    CornerRadius(radius - stroke / 2f), style = Stroke(stroke))
            }
        }
    } else Modifier.fillMaxWidth()
    Surface(
        shape = shape, color = colors.surface,
        modifier = modifier
    ) {
        Column(Modifier.fillMaxWidth().padding(
            start = contentInset,
            end = contentInset,
            top = if (first) contentInset else 0.dp,
            bottom = if (last) contentInset else 0.dp
        ), content = content)
    }
}
