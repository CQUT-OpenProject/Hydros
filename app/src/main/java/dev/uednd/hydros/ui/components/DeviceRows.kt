package dev.uednd.hydros.ui.components

import androidx.compose.runtime.Composable
import dev.uednd.hydros.ui.state.*

@Composable
fun DeviceRow(device: DeviceUi, remarks: Map<String, String> = emptyMap(), onClick: () -> Unit) {
    MenuRow(
        icon = device.category.icon,
        title = device.displayName(remarks),
        tone = IconTone.PRIMARY,
        onClick = onClick
    )
}
