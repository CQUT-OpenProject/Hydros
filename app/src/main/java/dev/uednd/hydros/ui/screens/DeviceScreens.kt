package dev.uednd.hydros.ui.screens

import dev.uednd.hydros.data.feedback.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.uednd.hydros.R
import dev.uednd.hydros.ui.components.*
import dev.uednd.hydros.ui.state.*
import kotlinx.coroutines.delay

@Composable
fun DeviceDetailScreen(device: DeviceUi?, state: HydrosUiState, onConnect: (DeviceUi) -> Unit, onStart: (DeviceUi) -> Unit, onSession: () -> Unit, onBack: () -> Unit, onFavorite: (String) -> Unit, onSaveRemark: suspend (String, String) -> Boolean) {
    var editingRemark by rememberSaveable(device?.id) { mutableStateOf(false) }
    var remark by rememberSaveable(device?.id) { mutableStateOf("") }
    var savingRemark by remember { mutableStateOf(false) }
    var remarkError by remember { mutableStateOf<String?>(null) }
    val favorite = device?.id in state.favoriteDeviceIds
    LaunchedEffect(device?.id, favorite) { if (!favorite) editingRemark = false }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val snackbar = LocalHydrosSnackbar.current
    val restriction = when {
        device == null -> "设备不存在，请重新扫描设备二维码"
        state.session != null -> {
            val otherDevice = state.session.device.id != device.id
            if (otherDevice) when (state.session.stage) {
                SessionStage.USING -> "已有设备正在使用，请先结束当前设备"
                SessionStage.OPENING -> "当前设备正在开启，请等待结果"
                SessionStage.STOPPING -> "原设备正在关闭，请等待结果"
                SessionStage.OPEN_UNCONFIRMED -> "原设备开启结果待确认，请先返回核实"
                SessionStage.CLOSE_UNCONFIRMED -> "原设备关闭结果待确认，请先返回核实"
                SessionStage.SETTLING, SessionStage.SETTLEMENT_PENDING -> "原设备结算尚未完成，请先返回处理"
                SessionStage.WRITEBACK_PENDING -> "原设备消费记录待清除，请先返回处理"
            } else null
        }
        state.connectingId == device.id && state.connectionStage != ConnectionStage.IDLE -> null
        device.status == DeviceStatus.BUSY -> "设备正在使用，请选择其他设备"
        device.status == DeviceStatus.OFFLINE -> "设备暂时离线，请稍后重试或选择其他设备"
        else -> null
    }
    SnackbarMessage(restriction, eventKey = device?.id to restriction)
    SnackbarMessage(
        state.connectionMessage?.takeIf { device != null && state.connectingId == device.id && state.connectionStage == ConnectionStage.READY },
        eventKey = device?.id to state.connectionMessage,
        error = false,
        severity = FeedbackSeverity.INFO,
    )
    PageColumn {
        if (device == null) SecondaryAction("返回", onClick = onBack)
        else {
            Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                IconTile(device.category.icon, tone = IconTone.PRIMARY)
                Column(Modifier.weight(1f)) { Text(device.displayName(state.deviceRemarks), style = MaterialTheme.typography.headlineSmall); Text(device.category.label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                if (favorite) TextAction(
                    label = if (device.id in state.deviceRemarks) "修改备注" else "设置备注",
                    onClick = {
                        remark = state.deviceRemarks[device.id].orEmpty()
                        remarkError = null
                        editingRemark = true
                    }
                )
            }
            if (device.id in state.connectedDeviceIds) {
                SecondaryAction(
                    label = if (favorite) "取消收藏" else "收藏设备",
                    icon = if (favorite) R.drawable.ic_star_filled else R.drawable.ic_star,
                    onClick = { onFavorite(device.id) }
                )
            } else Text("连接成功后可收藏此设备", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Section("设备信息") {
                InfoRow("原始设备名", device.name)
                SubtleDivider()
                InfoRow("位置", device.location)
                SubtleDivider()
                InfoRow("设备编号", device.id)
                SubtleDivider()
                InfoRow("当前状态", device.status.label)
                SubtleDivider()
                InfoRow("信号强度", device.signal)
                SubtleDivider()
                InfoRow("连接方式", device.connection)
                SubtleDivider()
                InfoRow("连接状态", if (state.connectingId != device.id) "未连接" else when (state.connectionStage) {
                    ConnectionStage.IDLE -> "未连接"
                    ConnectionStage.CONNECTING -> "连接中"
                    ConnectionStage.READY -> "已连接"
                    ConnectionStage.FAILED -> "连接失败"
                }, Modifier.semantics(mergeDescendants = true) {})
                SubtleDivider()
                InfoRow("预扣金额", "¥ ${money(device.depositCents)}")
            }
            state.deviceActionError?.takeIf { state.connectingId == device.id && state.session == null }?.let {
                StateCard("开启未完成", it, action = "重新连接", onAction = { onConnect(device) })
            }
            when {
                state.session != null -> SecondaryAction("进入使用页面", onClick = onSession)
                state.connectingId == device.id && state.connectionStage == ConnectionStage.CONNECTING -> StateCard("正在连接",
                    state.networkMessage ?: state.connectionMessage ?: "正在核实云端或蓝牙连接…", loading = true)
                state.connectingId == device.id && state.connectionStage == ConnectionStage.FAILED -> {
                    StateCard("连接未完成", state.connectionMessage ?: "暂时无法连接设备，请重新连接", action = "重新连接", onAction = { onConnect(device) })
                    if (state.connectionAction in listOf(RecoveryAction.SETTINGS, RecoveryAction.ENABLE_BLUETOOTH)) {
                        TextAction(if (state.connectionAction == RecoveryAction.ENABLE_BLUETOOTH) "开启蓝牙" else "前往设置", onClick = {
                            val intent = if (state.connectionAction == RecoveryAction.ENABLE_BLUETOOTH) android.content.Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS)
                                else android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:${context.packageName}"))
                            context.startActivity(intent)
                        })
                    }
                }
                state.connectingId == device.id && state.connectionStage == ConnectionStage.READY -> {
                    if (device.status == DeviceStatus.BUSY) PrimaryAction("核实并恢复本人订单", enabled = !state.working, loading = state.working) { onStart(device) }
                    else HoldActionButton(device.id, enabled = !state.working, working = state.working) { onStart(device) }
                }
                device.status == DeviceStatus.OFFLINE && device.channel == ControlChannel.CLOUD && device.isBle != true -> SecondaryAction("返回", onClick = onBack)
                else -> PrimaryAction("连接设备") { onConnect(device) }
            }
        }
    }
    if (editingRemark && device != null && favorite) HydrosDialog(
        onDismiss = { editingRemark = false },
        dismissEnabled = !savingRemark,
        title = "设备备注",
        content = {
            Column {
                FormField(remark, { remark = it; remarkError = null }, "备注", maxLength = DEVICE_REMARK_MAX_LENGTH, error = remarkError)
                Text("留空保存可恢复原始设备名", style = MaterialTheme.typography.bodySmall)
            }
        },
        dismissButton = { TextAction("取消", enabled = !savingRemark, onClick = { editingRemark = false }) },
        confirmButton = {
            TextAction(
                label = if (savingRemark) "保存中" else "保存",
                enabled = !savingRemark && !state.working,
                loading = savingRemark,
                onClick = {
                    scope.launch {
                        savingRemark = true
                        try {
                            val before = snackbar.notice.value?.id
                            if (onSaveRemark(device.id, remark)) {
                                editingRemark = false
                                snackbar.show("备注已保存", severity = FeedbackSeverity.SUCCESS)
                            } else {
                                remarkError = "保存失败，请重试"
                                if (snackbar.notice.value?.id == before) snackbar.show(remarkError!!, error = true)
                            }
                        } finally { savingRemark = false }
                    }
                }
            )
        }
    )
}

@Composable
fun SessionScreen(session: SessionUi?, onFinish: () -> Unit, onHome: () -> Unit, remarks: Map<String, String> = emptyMap(), working: Boolean = false, observation: SessionObservation = SessionObservation(), resolvingDestination: Boolean = false) {
    var seconds by remember { mutableLongStateOf(0) }
    LaunchedEffect(session?.startedAt, session?.endedAt) {
        while (session != null) {
            seconds = (((session.endedAt ?: System.currentTimeMillis()) - session.startedAt) / 1000).coerceAtLeast(0)
            if (session.endedAt != null) break
            delay(1000)
        }
    }
    val spinning = session != null && (working || session.stage in listOf(SessionStage.OPENING, SessionStage.STOPPING, SessionStage.SETTLING))
    val observed = session != null && observation.deviceId == session.device.id && observation.startedAt == session.startedAt
    val confirmedAt = observation.lastConfirmedAt?.let {
        "上次确认：" + java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.CHINA).format(java.util.Date(it))
    }
    val observationNotice = if (session != null && !spinning && observed) {
        observation.actionError?.takeIf { it != session.message } ?: if (observation.unavailable) {
            if (observation.lastConfirmedAt == null) "暂时无法确认设备状态，请检查水流并恢复连接。本次启动后尚未成功核实设备状态"
            else "暂时无法确认设备状态，请检查水流并恢复连接"
        } else null
    } else null
    SnackbarMessage(observationNotice, eventKey = Triple(session?.device?.id, session?.startedAt, observationNotice), error = false, source = "device", severity = FeedbackSeverity.WARNING)
    when {
        session == null && resolvingDestination -> SpinningTransition(title = "正在打开账单", description = "正在获取本次使用对应的账单…")
        session == null -> PageColumn {
            StateCard("当前没有使用中的设备", "从设备页扫码连接，开始使用。", action = "返回首页", onAction = onHome)
        }
        spinning -> SpinningTransition(
            title = sessionBusyTitle(session),
            description = session.message,
            elapsed = elapsed(seconds),
            deviceName = session.device.displayName(remarks),
            onHome = onHome,
        )
        else -> PageColumn {
            HeroCard(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer
            ) {
                Column(
                    Modifier.fillMaxWidth().padding(vertical = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Glyph(session.device.category.icon, modifier = Modifier.size(64.dp))
                    Text(if (session.stage == SessionStage.USING) "正在${session.device.category.label}" else session.stage.label, style = MaterialTheme.typography.titleLarge)
                    Text(elapsed(seconds), style = MaterialTheme.typography.headlineLarge)
                    Text("使用时长", style = MaterialTheme.typography.bodySmall)
                    if (observed && confirmedAt != null) Text(confirmedAt, style = MaterialTheme.typography.bodySmall)
                }
            }
            Section("当前设备") {
                InfoRow("设备", session.device.displayName(remarks))
                SubtleDivider()
                InfoRow("位置", session.device.location)
                SubtleDivider()
                InfoRow("连接", session.device.connection)
                SubtleDivider()
                InfoRow("预扣", "¥ ${money(session.device.depositCents)}")
            }
            DangerAction(label = sessionActionLabel(session), onClick = onFinish)
            SecondaryAction("返回首页", onClick = onHome)
        }
    }
}

private fun sessionBusyTitle(session: SessionUi): String = when (session.stage) {
    SessionStage.OPENING -> "正在开启"
    SessionStage.SETTLING, SessionStage.SETTLEMENT_PENDING -> if (session.endedAt != null) "设备已关闭，正在结算" else "正在核实结算"
    SessionStage.WRITEBACK_PENDING -> "正在清除设备记录"
    SessionStage.OPEN_UNCONFIRMED -> "正在核实"
    else -> "正在关闭"
}

@Composable
private fun SpinningTransition(
    title: String,
    description: String? = null,
    elapsed: String? = null,
    deviceName: String? = null,
    onHome: (() -> Unit)? = null,
) {
    Box(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).testTag("session-transition"),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.widthIn(max = 520.dp).fillMaxWidth().padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            CircularProgressIndicator(Modifier.size(48.dp))
            Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
            if (elapsed != null) {
                Text(elapsed, style = MaterialTheme.typography.headlineLarge)
                Text("使用时长", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (!deviceName.isNullOrBlank()) {
                Text(deviceName, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
            }
            if (!description.isNullOrBlank()) {
                Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            }
            if (onHome != null) SecondaryAction("返回首页", onClick = onHome)
        }
    }
}
