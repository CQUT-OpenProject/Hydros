package org.cqutosp.hydros.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import org.cqutosp.hydros.data.storage.FeedbackSettings
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

enum class ThemeMode(val label: String) { SYSTEM("跟随系统"), LIGHT("亮色主题"), DARK("暗色主题") }

// Font-size preview must not rescale slider layout; gesture controls read this density instead.
val LocalSystemDensity = staticCompositionLocalOf { Density(1f) }

private val Light = lightColorScheme(
    primary = Color(0xFF416189), onPrimary = Color(0xFFF8F8FF),
    primaryContainer = Color(0xFFA9C9F7), onPrimaryContainer = Color(0xFF204168),
    secondary = Color(0xFF546071), onSecondary = Color(0xFFF8F8FF),
    secondaryContainer = Color(0xFFD8E3F8), onSecondaryContainer = Color(0xFF475263),
    tertiary = Color(0xFF635983), tertiaryContainer = Color(0xFFDACDFF), onTertiaryContainer = Color(0xFF4C436C),
    background = Color(0xFFF2F2F2), onBackground = Color(0xFF2E333A),
    surface = Color.White, onSurface = Color(0xFF2E333A),
    surfaceVariant = Color(0xFFF1F2F4), onSurfaceVariant = Color(0xFF5B5F67),
    surfaceContainer = Color.White, surfaceContainerLow = Color(0xFFF1F2F4),
    surfaceContainerHigh = Color(0xFFEDEFF2), surfaceContainerHighest = Color(0xFFE0E2E6),
    outline = Color(0xFFCBD1D9), outlineVariant = Color(0xFFDDE1E6),
    error = Color(0xFFA83836), onError = Color(0xFFFFF7F6),
    errorContainer = Color(0xFFFA746F), onErrorContainer = Color(0xFF6E0A12),
)
private val Dark = darkColorScheme(
    primary = Color(0xFFB0C8EC), onPrimary = Color(0xFF2A415F),
    primaryContainer = Color(0xFF3D5472), onPrimaryContainer = Color(0xFFD4E4FF),
    secondary = Color(0xFFBCC7DB), onSecondary = Color(0xFF364151),
    secondaryContainer = Color(0xFF313C4C), onSecondaryContainer = Color(0xFFB4C0D4),
    tertiary = Color(0xFFE7DEFF), tertiaryContainer = Color(0xFFDACDFF), onTertiaryContainer = Color(0xFF4C436C),
    background = Color(0xFF171717), onBackground = Color(0xFFE2E5EF),
    surface = Color(0xFF282A31), onSurface = Color(0xFFE2E5EF),
    surfaceVariant = Color(0xFF17191D), onSurfaceVariant = Color(0xFFA7ABB4),
    surfaceContainer = Color(0xFF282A31), surfaceContainerLow = Color(0xFF17191D),
    surfaceContainerHigh = Color(0xFF34373F), surfaceContainerHighest = Color(0xFF3B3E47),
    outline = Color(0xFF4A5468), outlineVariant = Color(0xFF3F424A),
    error = Color(0xFFFA746F), onError = Color(0xFF490006),
    errorContainer = Color(0xFF871F21), onErrorContainer = Color(0xFFFF9993),
)
// System sans typography: zero tracking, shared size/line-height/weight roles.
private val HydrosTypography = Typography(
    displayLarge = TextStyle(fontFamily = FontFamily.SansSerif, letterSpacing = 0.sp, fontSize = 57.sp, lineHeight = 64.sp, fontWeight = FontWeight.Normal),
    displayMedium = TextStyle(fontFamily = FontFamily.SansSerif, letterSpacing = 0.sp, fontSize = 45.sp, lineHeight = 52.sp, fontWeight = FontWeight.Normal),
    displaySmall = TextStyle(fontFamily = FontFamily.SansSerif, letterSpacing = 0.sp, fontSize = 36.sp, lineHeight = 44.sp, fontWeight = FontWeight.Normal),
    headlineLarge = TextStyle(fontFamily = FontFamily.SansSerif, letterSpacing = 0.sp, fontSize = 32.sp, lineHeight = 40.sp, fontWeight = FontWeight.Normal),
    headlineMedium = TextStyle(fontFamily = FontFamily.SansSerif, letterSpacing = 0.sp, fontSize = 28.sp, lineHeight = 36.sp, fontWeight = FontWeight.Normal),
    headlineSmall = TextStyle(fontFamily = FontFamily.SansSerif, letterSpacing = 0.sp, fontSize = 24.sp, lineHeight = 32.sp, fontWeight = FontWeight.Normal),
    titleLarge = TextStyle(fontFamily = FontFamily.SansSerif, letterSpacing = 0.sp, fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Normal),
    titleMedium = TextStyle(fontFamily = FontFamily.SansSerif, letterSpacing = 0.sp, fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium),
    titleSmall = TextStyle(fontFamily = FontFamily.SansSerif, letterSpacing = 0.sp, fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontFamily = FontFamily.SansSerif, letterSpacing = 0.sp, fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium),
    bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif, letterSpacing = 0.sp, fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
    bodySmall = TextStyle(fontFamily = FontFamily.SansSerif, letterSpacing = 0.sp, fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
    labelLarge = TextStyle(fontFamily = FontFamily.SansSerif, letterSpacing = 0.sp, fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
    labelMedium = TextStyle(fontFamily = FontFamily.SansSerif, letterSpacing = 0.sp, fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold),
    labelSmall = TextStyle(fontFamily = FontFamily.SansSerif, letterSpacing = 0.sp, fontSize = 11.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold),
)

@Composable
fun HydrosTheme(mode: ThemeMode = ThemeMode.SYSTEM, feedback: FeedbackSettings = FeedbackSettings(), fontSizeScale: Float = 1f, content: @Composable () -> Unit) {
    val dark = when (mode) { ThemeMode.SYSTEM -> isSystemInDarkTheme(); ThemeMode.DARK -> true; ThemeMode.LIGHT -> false }
    val systemDensity = LocalDensity.current
    val density = Density(systemDensity.density, systemDensity.fontScale * fontSizeScale)
    MaterialTheme(
        colorScheme = if (dark) Dark else Light,
        typography = HydrosTypography,
        shapes = Shapes(small = RoundedCornerShape(12.dp), medium = RoundedCornerShape(20.dp), large = RoundedCornerShape(24.dp), extraLarge = RoundedCornerShape(28.dp)),
        content = { CompositionLocalProvider(LocalFeedbackSettings provides feedback,
            LocalSystemDensity provides systemDensity, LocalDensity provides density, content = content) },
    )
}
