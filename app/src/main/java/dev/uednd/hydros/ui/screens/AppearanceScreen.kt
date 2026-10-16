package dev.uednd.hydros.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.uednd.hydros.ui.components.PageColumn
import dev.uednd.hydros.ui.components.RadioRow
import dev.uednd.hydros.ui.components.Section
import dev.uednd.hydros.ui.components.SubtleDivider
import dev.uednd.hydros.ui.theme.ThemeMode

@Composable
fun AppearanceScreen(theme: ThemeMode, onTheme: (ThemeMode) -> Unit) {
    PageColumn {
        Section("主题外观") {
            Column(Modifier.selectableGroup()) {
                ThemeMode.entries.forEachIndexed { index, mode ->
                    RadioRow(
                        title = mode.label,
                        selected = mode == theme,
                        onClick = { onTheme(mode) }
                    )
                    if (index < ThemeMode.entries.lastIndex) SubtleDivider()
                }
            }
        }
    }
}

