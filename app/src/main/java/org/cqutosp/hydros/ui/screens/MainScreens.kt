package org.cqutosp.hydros.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.cqutosp.hydros.R
import org.cqutosp.hydros.ui.components.*
import org.cqutosp.hydros.ui.state.*
import org.cqutosp.hydros.ui.theme.ThemeMode

@Composable
fun HomeScreen(state: HydrosUiState, onScan: () -> Unit, onDevice: (String) -> Unit,
    onSession: () -> Unit, onScanNearby: () -> Unit, onStopNearby: () -> Unit,
    onNearbyDevice: (String) -> Unit = onDevice) {
    val haptics = rememberHydrosHaptics()
    val connected = state.connectedDeviceIds.mapNotNull { id -> state.devices.find { it.id == id } }
    val favorites = connected.filter { it.id in state.favoriteDeviceIds }
    PageColumn {
        HeroCard {
            Glyph(R.drawable.ic_scan, modifier = Modifier.size(48.dp))
            Text("连接身边的设备", style = MaterialTheme.typography.headlineMedium)
            Text("扫描设备二维码，连接后即可开始使用。", style = MaterialTheme.typography.bodyMedium)
            Button(
                onClick = { haptics.light(); onScan() },
                modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp),
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.primary
                )
            ) {
                Text("扫码连接", style = MaterialTheme.typography.labelLarge)
            }
        }
        if (state.session != null) {
            Section(state.session.stage.label) {
                MenuRow(R.drawable.ic_clock, state.session.device.displayName(state.deviceRemarks), "${state.session.device.location}\n点击查看并处理当前设备", onSession)
            }
        }
        if (favorites.isEmpty()) {
            Section("收藏设备") {
                InlineState(
                    title = "把常用设备留在这里",
                    description = "连接成功后可收藏设备，下次点击即可再次连接。",
                    icon = R.drawable.ic_star
                )
            }
        } else Section("收藏设备 · ${favorites.size}") {
            favorites.forEach { device -> DeviceRow(device, state.deviceRemarks) { onDevice(device.id) } }
        }
        NearbyDevices(state.nearby, state.devices, onScanNearby, onStopNearby, onNearbyDevice, state.deviceRemarks)
    }
}

@Composable
fun WalletScreen(state: HydrosUiState, onMonth: (String) -> Unit = {}, onBillType: (BillType) -> Unit = {}, onRefresh: () -> Unit = {}, onBill: (String) -> Unit) {
    val haptics = rememberHydrosHaptics()
    val month = state.month
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            modifier = Modifier.widthIn(max = 520.dp).fillMaxWidth().fillMaxHeight().imePadding(),
            contentPadding = PaddingValues(16.dp)
        ) {
            item("wallet-balance", "balance") {
                HeroCard {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("钱包余额", style = MaterialTheme.typography.titleMedium)
                            when {
                                state.wallet.totalMilli != null -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("¥ ${money(state.wallet.totalCents)}", style = MaterialTheme.typography.headlineLarge)
                                    if (state.walletLoading) CircularProgressIndicator(Modifier.size(22.dp), color = LocalContentColor.current, strokeWidth = 2.dp)
                                }
                                state.walletLoading -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    CircularProgressIndicator(Modifier.size(22.dp), color = LocalContentColor.current, strokeWidth = 2.dp)
                                    Text("正在获取余额…", style = MaterialTheme.typography.headlineLarge)
                                }
                                else -> Text("余额暂无法获取", style = MaterialTheme.typography.headlineLarge)
                            }
                        }
                        TextButton(
                            onClick = { haptics.light(); onRefresh() },
                            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onPrimary)
                        ) { Text("刷新") }
                    }
                }
                state.walletError?.let { InlineState("余额刷新未完成", it, action = "重新刷新", onAction = onRefresh) }
                Spacer(Modifier.height(20.dp))
            }
            billHistoryList(
                month = month,
                billType = state.billType,
                bills = state.bills,
                loading = state.loadingBills,
                error = state.billsError != null,
                errorMessage = state.billsError,
                onMonth = onMonth,
                onBillType = { type ->
                    if (type != state.billType) haptics.medium()
                    onBillType(type)
                },
                onBill = onBill
            )
        }
    }
}

@Composable
fun MineScreen(
    state: HydrosUiState,
    onManageAccount: () -> Unit,
    onAppearance: () -> Unit,
    onAbout: () -> Unit,
    hasUpdate: Boolean = false,
    onFeedback: () -> Unit = {},
    onDisplay: () -> Unit = {},
    onRefresh: () -> Unit = {},
) {
    PageColumn {
        Section {
            Row(
                Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.Top
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        state.account.name,
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "${state.account.phone.take(3)} **** ${state.account.phone.takeLast(4)}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                TextAction("管理账户", trailingIcon = R.drawable.ic_chevron, onClick = onManageAccount)
            }
            SubtleDivider()
            InfoRow("学校", state.account.school)
            SubtleDivider()
            InfoRow("学号", state.account.studentId)
        }
        state.accountError?.let { StateCard("账户资料未更新", it, action = "刷新资料", onAction = onRefresh) }
        Section("个性化") {
            MineRow("反馈设置", icon = R.drawable.ic_vibration, tone = IconTone.PRIMARY, onClick = onFeedback)
            SubtleDivider()
            MineRow("显示设置", icon = R.drawable.ic_display_settings, tone = IconTone.PRIMARY, onClick = onDisplay)
            SubtleDivider()
            MineRow("应用外观", icon = R.drawable.ic_palette, tone = IconTone.SECONDARY, onClick = onAppearance)
        }
        Section("应用与支持") {
            MineRow("关于 Hydros", icon = R.drawable.ic_info, tone = IconTone.TERTIARY,
                trailing = if (hasUpdate) ({ StatusBadge("有新版本！", primary = true) }) else null,
                onClick = onAbout)
        }
    }
}

@Composable
fun BillDetailScreen(
    bill: BillUi?,
    onWallet: () -> Unit,
    durationPending: Boolean = false,
    error: String? = null,
    onRetry: () -> Unit = {},
    backLabel: String = "返回钱包",
) {
    SnackbarMessage(if (bill == null && error == null) "未找到账单，请返回${backLabel.removePrefix("返回")}查看其他记录" else null)
    PageColumn {
        error?.let { StateCard("账单详情未更新", it, action = "重新查询", onAction = onRetry) }
        if (bill == null) SecondaryAction(backLabel, onClick = onWallet)
        else {
            Column(Modifier.fillMaxWidth().padding(vertical = 20.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                IconTile(
                    if (bill.recharge) R.drawable.ic_check else R.drawable.ic_receipt,
                    tone = if (bill.recharge) IconTone.PRIMARY else IconTone.SECONDARY
                )
                Text(if (bill.recharge) "充值完成" else if (bill.amountMilli == null) "消费金额待查询" else "消费已结算", style = MaterialTheme.typography.titleLarge)
                Text("${if (bill.recharge) "+" else "−"} ¥${money(bill.amountCents)}", style = MaterialTheme.typography.headlineLarge)
            }
            Section("账单详情") {
                InfoRow("项目", bill.title)
                SubtleDivider()
                InfoRow(if (bill.recharge) "支付方式" else "位置", bill.location)
                SubtleDivider()
                InfoRow("时间", bill.time.toLocalDate().toString() + " " + bill.time.toLocalTime().withNano(0))
                if (!bill.recharge && (bill.durationText != null || durationPending)) {
                    SubtleDivider()
                    InfoRow("使用时长", bill.durationText ?: "查询中…")
                }
                SubtleDivider()
                InfoRow("订单编号", bill.id)
                SubtleDivider()
                InfoRow("状态", "已完成")
            }
            PrimaryAction(backLabel, onClick = onWallet)
        }
    }
}
