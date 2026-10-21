package org.cqutosp.hydros.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import org.cqutosp.hydros.ui.components.PageColumn
import org.cqutosp.hydros.ui.components.RadioRow
import org.cqutosp.hydros.ui.components.Section
import org.cqutosp.hydros.ui.components.SubtleDivider
import org.cqutosp.hydros.ui.theme.ThemeMode

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

