package dev.uednd.hydros.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.uednd.hydros.R
import dev.uednd.hydros.data.storage.FeedbackSettings
import dev.uednd.hydros.ui.components.*

@Composable
fun FeedbackSettingsScreen(settings: FeedbackSettings, onHaptic: (Boolean) -> Unit, onReduceMotion: (Boolean) -> Unit) {
    val haptics = rememberHydrosHaptics()
    var wasEnabled by remember { mutableStateOf(settings.hapticFeedbackEnabled) }
    LaunchedEffect(settings.hapticFeedbackEnabled) {
        if (!wasEnabled && settings.hapticFeedbackEnabled) haptics.light()
        wasEnabled = settings.hapticFeedbackEnabled
    }
    PageColumn {
        Section("触感反馈") {
            FeedbackSwitchRow("振动反馈", R.drawable.ic_vibration, settings.hapticFeedbackEnabled, onHaptic)
        }
        Section("动画") {
            FeedbackSwitchRow("减少动画效果", R.drawable.ic_animation, settings.reduceMotionEnabled) {
                haptics.light()
                onReduceMotion(it)
            }
        }
    }
}

@Composable
private fun FeedbackSwitchRow(title: String, icon: Int, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
            .heightIn(min = 60.dp).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        IconTile(icon, tone = IconTone.PRIMARY)
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
        Switch(checked = checked, onCheckedChange = null)
    }
}
