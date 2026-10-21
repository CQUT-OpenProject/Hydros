package org.cqutosp.hydros.ui.screens

import androidx.compose.runtime.*
import org.cqutosp.hydros.R
import org.cqutosp.hydros.data.storage.FeedbackSettings
import org.cqutosp.hydros.ui.components.*

@Composable
fun FeedbackSettingsScreen(settings: FeedbackSettings, onHaptic: (Boolean) -> Unit, onReduceMotion: (Boolean) -> Unit) {
    val haptics = rememberHydrosHaptics()
    var wasEnabled by remember { mutableStateOf(settings.hapticFeedbackEnabled) }
    LaunchedEffect(settings.hapticFeedbackEnabled) {
        if (!wasEnabled && settings.hapticFeedbackEnabled) haptics.medium()
        wasEnabled = settings.hapticFeedbackEnabled
    }
    PageColumn {
        Section("触感反馈") {
            SwitchRow(
                title = "振动反馈",
                icon = R.drawable.ic_vibration,
                checked = settings.hapticFeedbackEnabled,
                onCheckedChange = onHaptic
            )
        }
        Section("动画") {
            SwitchRow(
                title = "减少动画效果",
                icon = R.drawable.ic_animation,
                checked = settings.reduceMotionEnabled,
                onCheckedChange = onReduceMotion
            )
        }
    }
}
