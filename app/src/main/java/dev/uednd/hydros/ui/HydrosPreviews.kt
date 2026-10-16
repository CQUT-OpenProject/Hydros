package dev.uednd.hydros.ui

import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import dev.uednd.hydros.ui.state.*
import dev.uednd.hydros.ui.screens.*
import dev.uednd.hydros.ui.theme.*

@Preview(name="设备空记录", showBackground=true, widthDp=393, heightDp=850)
@Composable private fun EmptyHome() = HydrosTheme(ThemeMode.LIGHT) {
    Surface { HomeScreen(HydrosUiState(initializing=false, signedIn=true), {}, {}, {}, {}, {}) }
}
@Preview(name="钱包未加载", showBackground=true, widthDp=393, heightDp=850)
@Composable private fun EmptyWallet() = HydrosTheme(ThemeMode.DARK) {
    Surface { WalletScreen(HydrosUiState(initializing=false, signedIn=true), onBill={}) }
}
@Preview(name="登录", widthDp=320, heightDp=640, fontScale=1.5f)
@Composable private fun Login() = HydrosTheme(ThemeMode.LIGHT) { Surface { LoginScreen() } }

@Preview(name="设备备注·浅色", showBackground=true, widthDp=393, heightDp=850)
@Composable private fun DeviceDetailLight() = DeviceDetailPreview(ThemeMode.LIGHT)

@Preview(name="设备备注·深色小屏", showBackground=true, widthDp=320, heightDp=640, fontScale=1.3f)
@Composable private fun DeviceDetailDark() = DeviceDetailPreview(ThemeMode.DARK)

@Composable private fun DeviceDetailPreview(theme: ThemeMode) = HydrosTheme(theme) {
    val device = DeviceUi("AABBCCDDEEFF", "热水表-测试宿舍", "测试宿舍 · 1 楼", DeviceCategory.SHOWER,
        status=DeviceStatus.AVAILABLE, channel=ControlChannel.CLOUD, depositMilli=5_000)
    val state = HydrosUiState(initializing=false, signedIn=true, devices=listOf(device),
        connectedDeviceIds=listOf(device.id), favoriteDeviceIds=setOf(device.id), connectingId=device.id, connectionStage=ConnectionStage.READY,
        deviceRemarks=mapOf(device.id to "宿舍花洒"))
    dev.uednd.hydros.ui.components.HoldActionHost {
        Surface { DeviceDetailScreen(device, state, {}, {}, {}, {}, {}, { _, _ -> true }) }
    }
}
