package dev.uednd.hydros.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import dev.uednd.hydros.ui.components.*
import dev.uednd.hydros.ui.state.DeviceUi
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.uednd.hydros.data.repository.NearbyDiscoveryState

@Composable
fun NearbyDevices(
    state: NearbyDiscoveryState,
    devices: List<DeviceUi>,
    onScan: () -> Unit,
    onStop: () -> Unit,
    onSelect: (String) -> Unit,
    remarks: Map<String, String> = emptyMap(),
) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val stop by rememberUpdatedState(onStop)
    val wanted = listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    fun granted(permission: String) = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    var permissions by remember { mutableStateOf(wanted.associateWith(::granted)) }
    val snackbar = LocalHydrosSnackbar.current
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        permissions = wanted.associateWith(::granted)
        if (permissions.values.any { !it }) snackbar.show("请允许附近设备权限后重试", error = true)
    }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) permissions = wanted.associateWith(::granted)
            if (event == Lifecycle.Event.ON_STOP) stop()
        }
        owner.lifecycle.addObserver(observer)
        onDispose {
            owner.lifecycle.removeObserver(observer)
            stop()
        }
    }
    fun startScan() {
        permissions = wanted.associateWith(::granted)
        if (permissions.values.any { !it }) request.launch(wanted.toTypedArray()) else onScan()
    }

    Section("附近设备") {
        if (permissions.values.any { !it }) {
            SecondaryAction("允许蓝牙", onClick = { request.launch(wanted.toTypedArray()) }, modifier = Modifier.padding(16.dp))
        } else {
            val found = state.devices.map { it.id }.toSet()
            val nearby = devices.filter { it.id in found }
            nearby.forEach { DeviceRow(it, remarks) { onSelect(it.id) } }
            if (state.searching) {
                InlineState(
                    title = "正在查找附近设备…",
                    loading = true,
                    modifier = Modifier.padding(vertical = if (nearby.isEmpty()) 12.dp else 0.dp)
                )
            } else if (state.hasSearched && nearby.isEmpty() && state.error == null) {
                InlineState(
                    title = "未发现附近设备",
                    icon = null,
                    modifier = Modifier.padding(vertical = 4.dp)
                )
            }
            if (!state.searching) {
                TextAction(
                    label = if (state.hasSearched) "重新扫描" else "扫描附近设备",
                    onClick = { startScan() },
                    modifier = Modifier.align(Alignment.CenterHorizontally).padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
        }
    }
}

