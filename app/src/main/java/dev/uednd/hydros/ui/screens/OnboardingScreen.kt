package dev.uednd.hydros.ui.screens

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import dev.uednd.hydros.ui.theme.LocalFeedbackSettings
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import dev.uednd.hydros.R
import dev.uednd.hydros.ui.components.*

private const val STEP_COUNT = 4

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnboardingScreen(
    onComplete: (Boolean) -> Unit,
    onTerms: () -> Unit = {},
    onPrivacy: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var currentStep by rememberSaveable { mutableIntStateOf(0) }
    var agreedToLegal by rememberSaveable { mutableStateOf(false) }
    var analyticsEnabled by rememberSaveable { mutableStateOf(true) }

    val reduceMotion = LocalFeedbackSettings.current.reduceMotionEnabled
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val isPreview = LocalInspectionMode.current

    // Permissions state
    var cameraGranted by remember { mutableStateOf(isPreview || checkCameraPermission(context)) }
    var bluetoothGranted by remember { mutableStateOf(isPreview || checkBluetoothPermission(context)) }
    var notificationGranted by remember { mutableStateOf(isPreview || checkNotificationPermission(context)) }

    fun refreshPermissions() {
        if (!isPreview) {
            cameraGranted = checkCameraPermission(context)
            bluetoothGranted = checkBluetoothPermission(context)
            notificationGranted = checkNotificationPermission(context)
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                refreshPermissions()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        cameraGranted = it
        refreshPermissions()
    }

    val bluetoothLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        bluetoothGranted = checkBluetoothPermission(context)
        refreshPermissions()
    }

    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        notificationGranted = it
        refreshPermissions()
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Step indicator pills
                StepIndicator(STEP_COUNT, currentStep)

                // Action buttons
                ActionRow {
                    if (currentStep > 0) {
                        TextAction(
                            label = "上一步",
                            onClick = { if (currentStep > 0) currentStep-- },
                            modifier = Modifier.weight(1f)
                        )
                    }

                    when (currentStep) {
                        0 -> {
                            PrimaryAction(
                                label = "下一步",
                                onClick = { currentStep = 1 },
                                modifier = Modifier.weight(1f)
                            )
                        }
                        1 -> {
                            PrimaryAction(
                                label = "我已阅读并继续",
                                enabled = agreedToLegal,
                                onClick = {
                                    if (agreedToLegal) currentStep = 2
                                },
                                modifier = Modifier.weight(1f)
                            )
                        }
                        2 -> {
                            val allGranted = cameraGranted && bluetoothGranted && notificationGranted
                            PrimaryAction(
                                label = "下一步",
                                enabled = allGranted,
                                onClick = {
                                    if (allGranted) currentStep = 3
                                },
                                modifier = Modifier.weight(1f)
                            )
                        }
                        3 -> {
                            PrimaryAction(
                                label = "开始使用",
                                onClick = { onComplete(analyticsEnabled) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }
        }
    ) { innerPadding ->
        AnimatedContent(
            targetState = currentStep,
            transitionSpec = {
                if (reduceMotion) {
                    EnterTransition.None.togetherWith(ExitTransition.None)
                } else {
                    fadeIn(tween(250)).togetherWith(fadeOut(tween(250)))
                }
            },
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            label = "OnboardingStepTransition"
        ) { step ->
            when (step) {
                0 -> WelcomeStep()
                1 -> LegalStep(
                    agreed = agreedToLegal,
                    onToggleAgreed = { agreedToLegal = it },
                    analyticsEnabled = analyticsEnabled,
                    onAnalyticsEnabled = { analyticsEnabled = it },
                    onTerms = onTerms,
                    onPrivacy = onPrivacy
                )
                2 -> PermissionsStep(
                    cameraGranted = cameraGranted,
                    onRequestCamera = { cameraLauncher.launch(Manifest.permission.CAMERA) },
                    bluetoothGranted = bluetoothGranted,
                    onRequestBluetooth = {
                        bluetoothLauncher.launch(
                            arrayOf(
                                Manifest.permission.BLUETOOTH_SCAN,
                                Manifest.permission.BLUETOOTH_CONNECT
                            )
                        )
                    },
                    notificationGranted = notificationGranted,
                    onRequestNotification = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    }
                )
                3 -> GetStartedStep()
            }
        }
    }
}

@Composable
private fun WelcomeStep() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 20.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            AppBrandingHeader(
                title = "欢迎使用 Hydros",
                subtitle = "无广告 · 轻量化的趣智校园第三方客户端"
            )
        }
    }
}

@Composable
private fun LegalStep(
    agreed: Boolean,
    onToggleAgreed: (Boolean) -> Unit,
    analyticsEnabled: Boolean,
    onAnalyticsEnabled: (Boolean) -> Unit,
    onTerms: () -> Unit,
    onPrivacy: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 20.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "阅读相关条款",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center
                )
                Text(
                    text = "使用 Hydros 前，请了解我们的服务协议与隐私政策。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }

            // Legal document cards
            Section {
                MineRow(
                    title = "服务协议",
                    supporting = "了解服务范围、使用规范与免责声明",
                    icon = R.drawable.ic_gavel,
                    tone = IconTone.PRIMARY,
                    onClick = onTerms
                )
                SubtleDivider()
                MineRow(
                    title = "隐私政策",
                    supporting = "了解数据收集、存储与系统权限说明",
                    icon = R.drawable.ic_shield,
                    tone = IconTone.PRIMARY,
                    onClick = onPrivacy
                )
            }

            Section {
                SwitchRow(
                    title = "匿名使用统计",
                    supporting = "默认开启；用于 Firebase Analytics 与 Crashlytics 收集去标识化使用与崩溃信息，可随时关闭。",
                    checked = analyticsEnabled,
                    onCheckedChange = onAnalyticsEnabled,
                    switchTag = "analytics-consent"
                )
            }

            // Agreement Checkbox
            CheckboxRow(
                text = "我已仔细阅读并同意《Hydros 服务协议》与《Hydros 隐私政策》",
                checked = agreed,
                onCheckedChange = onToggleAgreed
            )
        }
    }
}

@Composable
private fun PermissionsStep(
    cameraGranted: Boolean,
    onRequestCamera: () -> Unit,
    bluetoothGranted: Boolean,
    onRequestBluetooth: () -> Unit,
    notificationGranted: Boolean,
    onRequestNotification: () -> Unit
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight()
                .verticalScroll(rememberScrollState())
                // Keep scroll padding so the last permission stays inside the viewport.
                .padding(horizontal = 24.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically)
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "应用权限清单",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center
                )
                Text(
                    text = "Hydros 需要以下权限来保障使用体验",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }

            Section {
                PermissionRow(
                    title = "相机",
                    supporting = "扫描水控机与吹风机设备机身上的二维码",
                    icon = R.drawable.ic_scan,
                    tone = IconTone.PRIMARY,
                    isGranted = cameraGranted,
                    onRequest = onRequestCamera
                )
                SubtleDivider()
                PermissionRow(
                    title = "附近设备（蓝牙）",
                    supporting = "搜索并直连周围的校园水控蓝牙设备",
                    icon = R.drawable.ic_bluetooth,
                    tone = IconTone.SECONDARY,
                    isGranted = bluetoothGranted,
                    onRequest = onRequestBluetooth
                )
                SubtleDivider()
                PermissionRow(
                    title = "通知",
                    supporting = "用水状态与账单通知",
                    icon = R.drawable.ic_notifications,
                    tone = IconTone.TERTIARY,
                    isGranted = notificationGranted,
                    onRequest = onRequestNotification
                )
            }
        }
    }
}



@Composable
private fun GetStartedStep() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 20.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f),
                contentColor = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(80.dp)
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Glyph(
                        res = R.drawable.ic_check,
                        modifier = Modifier.size(40.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = "开始使用 Hydros",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center
                )
                Text(
                    text = "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

private fun checkCameraPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

private fun checkBluetoothPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

private fun checkNotificationPermission(context: Context): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    } else {
        true
    }
