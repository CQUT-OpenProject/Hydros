package org.cqutosp.hydros.ui.screens

import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.cqutosp.hydros.BuildConfig
import org.cqutosp.hydros.R
import org.cqutosp.hydros.data.update.*
import org.cqutosp.hydros.ui.components.*
import org.cqutosp.hydros.ui.state.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal fun publishedDate(value: String?): String = value?.let {
    runCatching { DateTimeFormatter.ofPattern("yyyy年M月d日").withZone(ZoneId.systemDefault()).format(Instant.parse(it)) }.getOrNull()
} ?: "发布日期未知"

@Composable
fun SoftwareUpdateScreen(
    updates: AppUpdateUiState,
    onHistory: () -> Unit = {},
    onInstall: () -> Unit = {},
    onContinue: () -> Unit = {},
    onCancel: () -> Unit = {},
    onCheck: () -> Unit,
) {
    val uri = LocalUriHandler.current
    val release = updates.release
    val install = updates.install
    PageColumn {
        AppBrandingHeader("", if (updates.hasUpdate) "建议及时更新以获取最新特性与体验优化" else "")
        when {
            updates.checking && install.phase == null -> StatusCard {
                CircularProgressIndicator(Modifier.size(36.dp))
                Text("正在检查最新版本…", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            updates.hasUpdate || install.phase != null -> {
                Section {
                    release?.let {
                        MineRow("最新版本", supporting = it.name, icon = R.drawable.ic_update, tone = IconTone.PRIMARY)
                        SubtleDivider()
                        MineRow("发布日期", supporting = publishedDate(it.publishedAt), icon = R.drawable.ic_calendar, tone = IconTone.SECONDARY)
                        SubtleDivider()
                    }
                    MineRow("v${BuildConfig.VERSION_NAME}", icon = R.drawable.ic_info, tone = IconTone.PRIMARY)
                }
                release?.notes?.takeIf { it.isNotBlank() }?.let { notes ->
                    Section("更新内容") { Markdown(notes, Modifier.padding(16.dp)) }
                }
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    (install.error ?: updates.error)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    if (install.phase != null) {
                        Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainer) {
                            Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text(install.phase.label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                                    install.percent?.let { Text("${it.coerceIn(0, 100)}%", color = MaterialTheme.colorScheme.primary) }
                                }
                                install.statusMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                                if (install.percent != null) LinearProgressIndicator(progress = { install.percent.coerceIn(0, 100) / 100f }, modifier = Modifier.fillMaxWidth())
                                else LinearProgressIndicator(Modifier.fillMaxWidth())
                            }
                        }
                        when (install.phase) {
                            InstallPhase.AWAITING_PERMISSION -> PrimaryAction("去允许安装", onClick = onContinue)
                            InstallPhase.AWAITING_CONFIRMATION -> PrimaryAction("继续安装", onClick = onContinue)
                            else -> Unit
                        }
                        if (install.canCancel) SecondaryAction("取消更新", onClick = onCancel)
                    } else if (release?.installAsset(Build.SUPPORTED_ABIS.toList()) != null) {
                        PrimaryAction("立即安装更新", icon = R.drawable.ic_download, onClick = onInstall)
                    } else {
                        Text("此版本未配置适用于当前设备的 Android 更新包。", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        release?.let { SecondaryAction("前往更新", icon = R.drawable.ic_open_in_new, onClick = { uri.openUri(it.url) }) }
                    }
                    if (install.phase == null) TextAction("重新检查", onClick = onCheck, modifier = Modifier.align(Alignment.CenterHorizontally))
                }
            }
            updates.error != null -> StatusCard {
                Glyph(R.drawable.ic_info, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(updates.error, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
                PrimaryAction("重试", icon = R.drawable.ic_refresh, onClick = onCheck)
            }
            else -> StatusCard {
                Glyph(R.drawable.ic_check_circle, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary)
                Text(if (updates.checked) "当前已是最新版本" else "检查软件更新", style = MaterialTheme.typography.titleMedium)
                SecondaryAction("重新检查", icon = R.drawable.ic_refresh, onClick = onCheck)
            }
        }
        Section("更多信息") {
            MineRow("更新历史", icon = R.drawable.ic_history, tone = IconTone.PRIMARY, onClick = onHistory)
            SubtleDivider()
            MineRow("源代码仓库", supporting = HYDROS_GITHUB_REPO, icon = R.drawable.ic_code,
                onClick = { uri.openUri(HYDROS_REPOSITORY) }, trailing = { Glyph(R.drawable.ic_open_in_new, Modifier.size(18.dp)) })
        }
    }
}

@Composable
fun ReleaseHistoryScreen(state: ReleaseHistoryUiState, onRelease: (AppRelease) -> Unit, onLoad: () -> Unit) {
    PageColumn {
        if (state.releases.isNotEmpty()) Section("全部版本") {
            state.releases.forEachIndexed { index, release ->
                if (index > 0) SubtleDivider()
                MineRow(release.name, supporting = publishedDate(release.publishedAt), onClick = { onRelease(release) })
            }
        }
        if (state.loading) CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
        else if (state.error != null) StatusCard {
            Text(state.error, color = MaterialTheme.colorScheme.error)
            PrimaryAction("重试", onClick = onLoad)
        } else if (state.loaded && state.releases.isEmpty() && !state.hasMore) StatusCard { Text("暂无版本更新记录") }
        if (!state.loading && state.error == null && state.hasMore) SecondaryAction("加载更多", onClick = onLoad)
    }
}

@Composable
fun ReleaseDetailScreen(release: AppRelease) {
    val uri = LocalUriHandler.current
    PageColumn {
        Section {
            MineRow("版本标签", supporting = release.tag, icon = R.drawable.ic_update, tone = IconTone.PRIMARY)
            SubtleDivider()
            MineRow("发布日期", supporting = publishedDate(release.publishedAt), icon = R.drawable.ic_calendar, tone = IconTone.SECONDARY)
        }
        Section("更新内容") { Markdown(release.notes.ifBlank { "此 Release 没有正文内容。" }, Modifier.padding(16.dp)) }
        SecondaryAction("在 GitHub 查看", icon = R.drawable.ic_open_in_new, onClick = { uri.openUri(release.url) })
    }
}
