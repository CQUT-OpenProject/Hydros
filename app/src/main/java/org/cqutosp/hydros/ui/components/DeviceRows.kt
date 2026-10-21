package org.cqutosp.hydros.ui.components

import androidx.compose.runtime.Composable
import org.cqutosp.hydros.ui.state.*

@Composable
fun DeviceRow(device: DeviceUi, remarks: Map<String, String> = emptyMap(), onClick: () -> Unit) {
    MenuRow(
        icon = device.category.icon,
        title = device.displayName(remarks),
        tone = IconTone.PRIMARY,
        onClick = onClick
    )
}
