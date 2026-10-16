package dev.uednd.hydros.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import dev.uednd.hydros.R

@Composable
fun SubtleDivider(modifier: Modifier = Modifier, horizontalPadding: Dp = 16.dp) {
    val colors = MaterialTheme.colorScheme
    val dark = colors.surface.luminance() < 0.5f
    HorizontalDivider(
        modifier = modifier.padding(horizontal = horizontalPadding),
        thickness = 1.dp,
        color = if (dark) colors.onSurfaceVariant.copy(alpha = 0.18f)
            else Color(0xFFD4E0EB).copy(alpha = 0.8f)
    )
}

@Composable
fun PageColumn(content: @Composable ColumnScope.() -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier
                .widthIn(max = 520.dp)
                .fillMaxWidth()
                .fillMaxHeight()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
            content = content
        )
    }
}

@Composable
fun Section(
    title: String? = null,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    // Follow the selected app palette, including an explicit light/dark override.
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val shape = MaterialTheme.shapes.medium
    // Raised elevation shadows, in logical Android units.
    val shadow = if (dark) {
        Modifier.dropShadow(shape, Shadow(3.dp, Color.Black.copy(alpha = 0.22f), offset = DpOffset(0.dp, 1.dp)))
    } else {
        Modifier
            .dropShadow(shape, Shadow(3.dp, Color(0xFF0F172A).copy(alpha = 0.06f), offset = DpOffset(0.dp, 1.dp)))
            .dropShadow(shape, Shadow(2.dp, Color(0xFF0F172A).copy(alpha = 0.04f), spread = (-1).dp, offset = DpOffset(0.dp, 1.dp)))
    }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (title != null) {
            Text(
                text = title,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Surface(
            modifier = shadow,
            shape = shape,
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, if (dark) Color(0xFF2E3038) else Color(0xFFD4E0EB))
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(6.dp),
                content = content
            )
        }
    }
}

/** Large prominent card with primary/container background, icon and headline. */
@Composable
fun HeroCard(
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.primary,
    contentColor: Color = MaterialTheme.colorScheme.onPrimary,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = containerColor,
        contentColor = contentColor,
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            content = content
        )
    }
}

/** Standard brand header with icon card, app title and description. */
@Composable
fun AppBrandingHeader(
    title: String,
    subtitle: String = "",
    modifier: Modifier = Modifier,
    iconRes: Int = R.drawable.ic_launcher_foreground,
    iconSize: Dp = 80.dp
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 1.dp,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        ) {
            Image(
                painter = painterResource(iconRes),
                contentDescription = null,
                modifier = Modifier.size(iconSize)
            )
        }
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (title.isNotBlank()) Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center
            )
            if (subtitle.isNotBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

/** Standard scaffold page shell with top bar, optional back navigation, and window insets handling. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HydrosPage(
    title: String,
    onBack: (() -> Unit)? = null,
    containerColor: Color = MaterialTheme.colorScheme.background,
    bottomBar: @Composable () -> Unit = {},
    titleContent: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit
) {
    Scaffold(
        containerColor = containerColor,
        topBar = {
            TopAppBar(
                title = titleContent ?: { Text(title, modifier = Modifier.testTag("page-title"), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Medium) },
                navigationIcon = {
                    if (onBack != null) {
                        HydrosIconButton(onClick = onBack, description = "返回") { Glyph(R.drawable.ic_back) }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = if (onBack != null) MaterialTheme.colorScheme.surface else containerColor
                )
            )
        },
        bottomBar = bottomBar
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) { content() }
    }
}


