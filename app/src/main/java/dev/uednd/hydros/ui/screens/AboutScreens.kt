package dev.uednd.hydros.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import com.mikepenz.markdown.model.MarkdownState
import com.mikepenz.markdown.model.rememberMarkdownState
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.mikepenz.aboutlibraries.Libs
import com.mikepenz.aboutlibraries.entity.Library
import dev.uednd.hydros.data.storage.AppDataControl
import dev.uednd.hydros.BuildConfig
import dev.uednd.hydros.R
import dev.uednd.hydros.data.update.*
import dev.uednd.hydros.ui.components.*
import dev.uednd.hydros.ui.state.AppUpdateUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private const val DEVELOPER_PAGE_TAP_COUNT = 5

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(
    updates: AppUpdateUiState,
    onUpdates: () -> Unit,
    onLicenses: () -> Unit,
    onProjectLicense: () -> Unit,
    onTerms: () -> Unit,
    onPrivacy: () -> Unit,
    onDeveloper: () -> Unit,
    appData: AppDataControl,
    onClear: () -> Boolean,
    analyticsEnabled: Boolean,
    onAnalyticsEnabled: (Boolean) -> Unit
) {
    val snackbar = LocalHydrosSnackbar.current
    var developerPageTapCount by remember { mutableIntStateOf(0) }
    fun onBuildTimeClickForDeveloperPage() {
        developerPageTapCount += 1
        if (developerPageTapCount >= DEVELOPER_PAGE_TAP_COUNT) {
            developerPageTapCount = 0
            snackbar.notice.value?.let { snackbar.dismiss(it.id) }
            onDeveloper()
        } else if (developerPageTapCount >= (DEVELOPER_PAGE_TAP_COUNT + 1) / 2) {
            snackbar.show("再按 ${DEVELOPER_PAGE_TAP_COUNT - developerPageTapCount} 次进入开发者页面")
        }
    }
    var dataBytes by remember { mutableStateOf<Long?>(null) }
    var clearDialog by remember { mutableStateOf(false) }
    var clearing by remember { mutableStateOf(false) }
    LaunchedEffect(appData) { dataBytes = appData.sizeBytes() }
    val buildTime = remember {
        DateTimeFormatter.ofPattern("yyyy年MM月dd日 HH:mm", Locale.CHINA)
            .withZone(ZoneId.systemDefault()).format(Instant.parse(BuildConfig.BUILD_TIME))
    }
    PageColumn {
        AppBrandingHeader("Hydros", "使用 Kotlin 开发的趣智校园第三方客户端")
        Section("版本信息") {
            MineRow("当前版本", supporting = BuildConfig.VERSION_NAME, icon = R.drawable.ic_info,
                tone = IconTone.PRIMARY, onClick = onUpdates,
                trailing = if (updates.hasUpdate) ({ StatusBadge("有新版本！") }) else null)
            SubtleDivider()
            MineRow("构建时间", supporting = buildTime, icon = R.drawable.ic_clock, tone = IconTone.SECONDARY,
                onClick = { onBuildTimeClickForDeveloperPage() })
        }
        Section("存储占用情况") {
            MineRow("清除所有数据", supporting = dataBytes?.let { "当前占用 ${formatDataSize(it)}" }.orEmpty(),
                icon = R.drawable.ic_delete_sweep, onClick = { clearDialog = true },
                trailing = if (dataBytes == null) ({ CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) }) else null)
        }
        Section("软件信息") {
            SwitchRow("匿名使用统计", checked = analyticsEnabled, onCheckedChange = onAnalyticsEnabled)
            SubtleDivider()
            MineRow("服务协议", onClick = onTerms)
            SubtleDivider()
            MineRow("隐私政策", onClick = onPrivacy)
            SubtleDivider()
            MineRow("开源许可", onClick = onLicenses)
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalArrangement = Arrangement.Center) {
            Text("© ${Instant.parse(BuildConfig.BUILD_TIME).atZone(ZoneId.systemDefault()).year} UE-DND · ",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Apache-2.0", Modifier.clickable(onClick = onProjectLicense), textDecoration = TextDecoration.Underline,
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (clearDialog) ConfirmBottomSheet(
        title = "清除所有数据？",
        description = "将清除登录信息、设备记录和缓存，并恢复默认设置。应用会保留，此操作无法撤销。清除后应用将关闭，重新打开后需再次登录。",
        confirm = "清除",
        onDismiss = { clearDialog = false },
        enabled = !clearing,
        destructive = true
    ) {
        clearing = true
        onClear()
        clearDialog = false
        clearing = false
    }
}

private fun formatDataSize(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> String.format(Locale.CHINA, "%.1f GB", bytes / (1024.0 * 1024 * 1024))
    bytes >= 1024L * 1024 -> String.format(Locale.CHINA, "%.1f MB", bytes / (1024.0 * 1024))
    bytes >= 1024 -> String.format(Locale.CHINA, "%.1f KB", bytes / 1024.0)
    else -> "$bytes B"
}

@Composable
fun DeveloperScreen(
    forceBluetoothConnection: Boolean,
    forceBluetoothEnabled: Boolean,
    onForceBluetoothConnection: (Boolean) -> Unit,
) {
    PageColumn {
        Section("连接") {
            SwitchRow(
                title = "强制蓝牙连接",
                checked = forceBluetoothConnection,
                enabled = forceBluetoothEnabled,
                switchTag = "developer-force-bluetooth",
                onCheckedChange = onForceBluetoothConnection,
            )
        }
    }
}

@Composable
fun LicensesIndexScreen(onProject: () -> Unit, onThirdParty: () -> Unit) {
    PageColumn {
        Section("许可证") {
            MineRow("本项目许可证", onClick = onProject)
            SubtleDivider()
            MineRow("第三方依赖许可证", onClick = onThirdParty)
        }
    }
}

@Composable
fun ProjectLicenseScreen() {
    val context = LocalContext.current
    val license by produceState<String?>(null, context) {
        value = withContext(Dispatchers.IO) {
            context.assets.open("project_license.txt").bufferedReader().use { it.readText() }
        }
    }
    PageColumn {
        license?.let {
            SelectionContainer {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } ?: CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
    }
}

@Composable
fun OpenSourceLicensesScreen() {
    val context = LocalContext.current
    val libraries by produceState<List<Library>?>(null, context) {
        value = withContext(Dispatchers.IO) {
            val json = context.resources.openRawResource(R.raw.aboutlibraries).bufferedReader().use { it.readText() }
            Libs.Builder().withJson(json).build().libraries.sortedBy { it.uniqueId.lowercase() }
        }
    }
    var selected by remember { mutableStateOf<Library?>(null) }
    var showSymbols by remember { mutableStateOf(false) }
    val uri = LocalUriHandler.current
    if (libraries == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
    } else {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
            item("material-symbols") {
                Column(Modifier.fillMaxWidth().clickable { showSymbols = true }.padding(vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Google Material Symbols", style = MaterialTheme.typography.titleSmall)
                    Text("Apache-2.0", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
            }
            items(libraries!!, key = { it.uniqueId }) { library ->
                Column(Modifier.fillMaxWidth().clickable { selected = library }.padding(vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(library.uniqueId, style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface)
                    Text(library.licenses.joinToString(" · ") { it.spdxId ?: it.name }.ifBlank { "未提供许可信息" },
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (library != libraries!!.last()) HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
            }
        }
    }
    if (showSymbols) {
        val license by produceState<String?>(null, context) {
            value = withContext(Dispatchers.IO) {
                context.assets.open("third_party/material-symbols-LICENSE.txt").bufferedReader().use { it.readText() }
            }
        }
        HydrosDialog(title = "Google Material Symbols", onDismiss = { showSymbols = false },
            confirmButton = { TextAction("关闭", onClick = { showSymbols = false }) }) {
            SelectionContainer {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    val text = license
                    if (text == null) Box(Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    else Text(text, style = MaterialTheme.typography.bodySmall)
                    TextAction("查看项目", onClick = { uri.openUri("https://github.com/google/material-design-icons") })
                }
            }
        }
    }
    selected?.let { library ->
        HydrosDialog(onDismiss = { selected = null }, title = library.name,
            content = {
                SelectionContainer {
                    Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text(library.artifactId, style = MaterialTheme.typography.bodySmall)
                        if (library.licenses.isEmpty()) Text("此依赖未提供许可信息。")
                        library.licenses.forEach { license ->
                            Text(license.name, fontWeight = FontWeight.SemiBold)
                            Text(license.licenseContent ?: "此许可未提供正文。")
                            license.url?.takeIf { it.startsWith("https://") }?.let { url ->
                                TextAction("查看许可原文", onClick = { uri.openUri(url) })
                            }
                        }
                    }
                }
            }, confirmButton = { TextAction("关闭", onClick = { selected = null }) })
    }
}

@Composable
fun LegalDocumentScreen(content: String, markdownState: MarkdownState = rememberMarkdownState(content)) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val phase by lifecycle.currentStateAsState()
    val preview = LocalInspectionMode.current
    // Navigation only resumes an entry after its enter transition completes.
    // Latch this: pausing for back/predictive-back must keep the rendered page.
    var mounted by remember(content) { mutableStateOf(preview || phase.isAtLeast(Lifecycle.State.RESUMED)) }
    LaunchedEffect(content, phase) {
        if (phase.isAtLeast(Lifecycle.State.RESUMED)) mounted = true
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        if (mounted) {
            Markdown(content = content, markdownState = markdownState, lazy = true, contentPadding = PaddingValues(16.dp),
                modifier = Modifier.widthIn(max = 520.dp).fillMaxSize())
        } else {
            Column(Modifier.widthIn(max = 520.dp).fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                repeat(5) { index ->
                    Box(Modifier.fillMaxWidth(if (index == 4) 0.6f else 1f).height(12.dp)
                        .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f), RoundedCornerShape(6.dp)))
                }
            }
        }
    }
}
