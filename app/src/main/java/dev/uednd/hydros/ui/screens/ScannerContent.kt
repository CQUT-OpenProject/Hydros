package dev.uednd.hydros.ui.screens

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.uednd.hydros.ui.components.ChoiceDialog
import dev.uednd.hydros.ui.components.PageColumn
import dev.uednd.hydros.ui.components.Glyph
import dev.uednd.hydros.ui.components.StateCard
import dev.uednd.hydros.ui.components.LocalHydrosSnackbar
import dev.uednd.hydros.ui.components.SnackbarMessage
import dev.uednd.hydros.ui.components.SnackbarAction
import dev.uednd.hydros.ui.components.ActionRow
import dev.uednd.hydros.ui.components.PrimaryAction
import dev.uednd.hydros.ui.components.InlineState
import dev.uednd.hydros.ui.components.SecondaryAction
import dev.uednd.hydros.ui.components.TextAction
import dev.uednd.hydros.ui.components.rememberHydrosHaptics
import dev.uednd.hydros.data.feedback.*
import dev.uednd.hydros.R
import dev.uednd.hydros.ui.scanner.*
import kotlinx.coroutines.CancellationException

@Composable
fun ScanScreen(onResolve: suspend (String, Int?) -> String?, enabled: Boolean = true,
    queryProgress: String? = null, onSelect: (String) -> Unit) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val session = remember { ScanSession() }
    val snackbar = LocalHydrosSnackbar.current
    val state by session.state.collectAsState()
    val resolve by rememberUpdatedState(onResolve)
    val select by rememberUpdatedState(onSelect)
    fun cameraGranted() = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    var permission by remember { mutableStateOf(cameraGranted()) }
    var permissionDenied by remember { mutableStateOf(false) }
    var active by remember { mutableStateOf(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    var torch by remember { mutableStateOf(false) }
    var cameraStatus by remember { mutableStateOf(CameraStatus()) }
    var cameraRetryKey by remember { mutableIntStateOf(0) }
    var photo by remember { mutableStateOf<Pair<Long, Uri>?>(null) }
    val permissionRequest = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        permission = it
        permissionDenied = !it
        if (!it) snackbar.show("未获得相机权限，可从相册选择二维码", error = true)
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        val token = session.picked(uri != null)
        photo = if (token != null && uri != null) token to uri else null
    }
    LaunchedEffect(enabled) { if (enabled && !permission) permissionRequest.launch(Manifest.permission.CAMERA) }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) { active = true; permission = cameraGranted() }
            if (event == Lifecycle.Event.ON_PAUSE) { active = false; torch = false; session.pauseCamera() }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(enabled) {
        if (!enabled) { torch = false; photo = null; session.pauseCamera() }
    }
    LaunchedEffect(state.phase) { if (state.phase != ScanPhase.SCANNING) torch = false }
    LaunchedEffect(photo, enabled) {
        if (!enabled) return@LaunchedEffect
        val (token, uri) = photo ?: return@LaunchedEffect
        try { session.codes(token, decodeQrPhoto(context, uri), photo = true) }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { session.fail(token, "图片无法读取或识别，请选择其他图片") }
        finally { photo = null }
    }
    LaunchedEffect(state.phase, state.generation, enabled) {
        if (enabled && state.phase == ScanPhase.RESOLVING) {
            val token = state.generation
            try { session.resolved(token, resolve(state.mac!!, null)) }
            catch (e: CancellationException) { throw e }
            catch (error: Exception) { session.fail(token, failureFeedback(error, FailureOperation.SCAN, FailureStage.DEVICE_INFO).text) }
        }
    }
    var navigatedGeneration by remember { mutableStateOf<Long?>(null) }
    val haptics = rememberHydrosHaptics()
    LaunchedEffect(state.deviceId, state.generation, active, enabled) {
        if (enabled && active && state.phase == ScanPhase.DONE && navigatedGeneration != state.generation) {
            navigatedGeneration = state.generation
            haptics.success()
            select(state.deviceId!!)
        }
    }
    ScanContent(
        state = state,
        cameraGranted = permission,
        cameraStatus = cameraStatus,
        onPermission = { permissionRequest.launch(Manifest.permission.CAMERA) },
        onSettings = if (permissionDenied) ({
            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri()))
        }) else null,
        onTorch = { torch = !cameraStatus.torchOn },
        onPhoto = {
            torch = false
            session.pick()
            try { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
            catch (_: ActivityNotFoundException) { session.picked(false); snackbar.show("无法打开相册，请稍后重试", error = true) }
        },
        onRetry = {
            photo = null
            torch = false
            if (cameraStatus.error != null) {
                cameraStatus = CameraStatus()
                cameraRetryKey++
            }
            session.retryQuery()
        },
        onRescan = { session.retry() },
        queryProgress = queryProgress,
        onChoose = session::choose,
    ) { modifier ->
        key(cameraRetryKey) {
            val currentGeneration by rememberUpdatedState(state.generation)
            CameraPreview(
                enabled = enabled && permission,
                scanning = active && state.phase == ScanPhase.SCANNING,
                torchRequested = torch,
                modifier = modifier,
                onStatus = { cameraStatus = it; if (it.torchError != null) torch = it.torchOn },
                onCodes = { session.codes(currentGeneration, it) },
                onFailure = { session.fail(currentGeneration, "相机识别失败，请重新扫描") },
            )
        }
    }
}

/** UI entry point independent of camera hardware and the system image picker. */
@Composable
internal fun ScanContent(
    state: ScanState,
    cameraGranted: Boolean,
    cameraStatus: CameraStatus,
    onPermission: () -> Unit,
    onSettings: (() -> Unit)? = null,
    onTorch: () -> Unit,
    onPhoto: () -> Unit,
    onRetry: () -> Unit,
    onChoose: (String) -> Unit,
    onRescan: (() -> Unit)? = null,
    queryProgress: String? = null,
    preview: @Composable (Modifier) -> Unit,
) {
    val scanning = state.phase == ScanPhase.SCANNING
    val busy = state.phase in setOf(ScanPhase.PICKING, ScanPhase.DECODING, ScanPhase.RESOLVING, ScanPhase.DONE)
    val snackbar = LocalHydrosSnackbar.current
    SnackbarMessage(state.message.takeIf { state.phase != ScanPhase.ERROR }, eventKey = state.generation to state.message,
        action = if (state.phase == ScanPhase.ERROR) SnackbarAction("重新扫描", onRetry) else null)
    SnackbarMessage(cameraStatus.error, eventKey = state.generation to cameraStatus.error, action = SnackbarAction("重试", onRetry))
    SnackbarMessage(cameraStatus.torchError)
    val torchReason = when {
        !cameraGranted -> "允许相机权限后可开启手电筒"
        cameraStatus.error != null -> "相机不可用，仍可从相册选择"
        !scanning -> "请先返回扫码状态，再开启手电筒"
        !cameraStatus.ready -> "相机正在启动，请稍候"
        !cameraStatus.hasFlash -> "当前相机不支持手电筒"
        else -> null
    }
    PageColumn {
        Card(shape = RoundedCornerShape(24.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(24.dp)).background(Color.Black), contentAlignment = Alignment.Center) {
                if (cameraGranted) preview(Modifier.fillMaxSize())
                if (cameraGranted && cameraStatus.error == null) {
                    ScanGuide(Modifier.fillMaxSize())
                    if (scanning && !cameraStatus.ready) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            CircularProgressIndicator(color = Color.White)
                            Text("正在启动相机", color = Color.White)
                        }
                    }
                }
                if (!cameraGranted || cameraStatus.error != null) {
                    Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        PrimaryAction(label = if (!cameraGranted) "允许相机" else "重试相机", onClick = if (!cameraGranted) onPermission else onRetry)
                        if (!cameraGranted && onSettings != null) TextAction(label = "前往设置", onClick = onSettings)
                    }
                }
            }
            Box(Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) {
                Text("将设备二维码放入扫描区域", style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
            }
        }
        ActionRow {
            SecondaryAction(
                label = if (cameraStatus.torchOn) "关闭手电筒" else "开启手电筒",
                icon = R.drawable.ic_flashlight,
                disabledReason = torchReason,
                modifier = Modifier
                    .weight(1f)
                    .semantics {
                        if (torchReason == null) stateDescription = if (cameraStatus.torchOn) "已开启" else "已关闭"
                    },
                onClick = onTorch
            )
            SecondaryAction(
                label = "从相册选择",
                icon = R.drawable.ic_photo,
                enabled = !busy,
                modifier = Modifier.weight(1f),
                onClick = onPhoto
            )
        }
        when (state.phase) {
            ScanPhase.PICKING -> StateCard("正在选择图片", "返回后继续扫码")
            ScanPhase.DECODING -> StateCard("正在识别图片", "正在查找用水设备二维码", loading = true)
            ScanPhase.RESOLVING, ScanPhase.DONE -> StateCard("正在查询设备", queryProgress ?: "识别成功，正在获取设备信息", loading = true)
            ScanPhase.ERROR -> {
                InlineState(if (state.mac != null) "设备信息查询未完成" else "识别未完成", state.message ?: "请重试")
                SecondaryAction(if (state.mac != null) "重新查询" else "重新扫描", onClick = onRetry)
                if (state.mac != null && onRescan != null) TextAction(label = "重新扫描", onClick = onRescan)
            }
            else -> Unit
        }
    }
    if (state.phase == ScanPhase.CHOOSING) ChoiceDialog(
        title = "选择用水设备",
        options = state.candidates,
        optionLabel = { "设备 $it" },
        onSelect = onChoose,
        onDismiss = onRetry,
        dismissLabel = "重新扫描"
    )
}

@Composable
private fun ScanGuide(modifier: Modifier) {
    val accent = MaterialTheme.colorScheme.primary
    Canvas(modifier) {
        val side = size.minDimension * .72f
        val left = (size.width - side) / 2
        val top = (size.height - side) / 2
        val shade = Color.Black.copy(alpha = .38f)
        drawRect(shade, size = Size(size.width, top))
        drawRect(shade, Offset(0f, top + side), Size(size.width, size.height - top - side))
        drawRect(shade, Offset(0f, top), Size(left, side))
        drawRect(shade, Offset(left + side, top), Size(size.width - left - side, side))
        val length = 24.dp.toPx()
        val stroke = 3.dp.toPx()
        for (x in listOf(left, left + side)) for (y in listOf(top, top + side)) {
            drawLine(accent, Offset(x, y), Offset(x + if (x == left) length else -length, y), stroke)
            drawLine(accent, Offset(x, y), Offset(x, y + if (y == top) length else -length), stroke)
        }
    }
}
