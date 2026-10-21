package dev.uednd.hydros.ui

import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.activity.compose.BackHandler
import dev.uednd.hydros.ui.components.SnackbarEnd
import androidx.core.view.WindowCompat

import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.foundation.background
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import kotlinx.coroutines.launch
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.Color
import androidx.activity.compose.LocalActivity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModel
import androidx.compose.ui.platform.LocalContext
import dev.uednd.hydros.data.HydrosApplication
import dev.uednd.hydros.data.storage.PostSessionDestination
import android.os.Build
import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager
import androidx.navigation.compose.*
import dev.uednd.hydros.R
import dev.uednd.hydros.ui.components.HydrosPage
import dev.uednd.hydros.ui.components.HydrosSnackbarProvider
import dev.uednd.hydros.ui.components.HydrosSnackbarState
import dev.uednd.hydros.ui.components.Glyph
import dev.uednd.hydros.ui.components.HoldActionHost
import dev.uednd.hydros.ui.components.rememberHydrosHaptics
import dev.uednd.hydros.ui.legal.LegalDocuments
import com.mikepenz.markdown.model.rememberMarkdownState
import dev.uednd.hydros.ui.state.*
import dev.uednd.hydros.ui.screens.*
import dev.uednd.hydros.ui.theme.HydrosTheme

private data class AppDestination(val route: String, val label: String, val icon: Int, val iconOutline: Int)
private val destinations = listOf(
    AppDestination("home", "设备", R.drawable.ic_shower, R.drawable.ic_shower),
    AppDestination("wallet", "钱包", R.drawable.ic_wallet, R.drawable.ic_wallet_outline),
    AppDestination("mine", "我的", R.drawable.ic_person, R.drawable.ic_person_outline),
)

private fun isTabSwitch(from: String?, to: String?): Boolean =
    destinations.any { it.route == from } && destinations.any { it.route == to }

@Composable
private fun hydrosViewModel(): HydrosViewModel {
    val dependencies=(LocalContext.current.applicationContext as HydrosApplication).dependencies
    return viewModel(factory=remember(dependencies) { object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = HydrosViewModel(dependencies) as T
    } })
}

@Composable
private fun appInfoViewModel(): AppInfoViewModel {
    val context = LocalContext.current.applicationContext
    val app = context as HydrosApplication
    val source = app.appUpdates
    val installer = app.appInstaller
    return viewModel(factory = remember(source, installer) { object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = AppInfoViewModel(source, installer) as T
    } })
}

@Composable
fun HydrosApp(model: HydrosViewModel = hydrosViewModel(), appInfo: AppInfoViewModel = appInfoViewModel()) {
    val state by model.state.collectAsStateWithLifecycle()
    val updates by appInfo.updates.collectAsStateWithLifecycle()
    val history by appInfo.history.collectAsStateWithLifecycle()
    val detail by appInfo.detail.collectAsStateWithLifecycle()
    val nav = rememberNavController()
    val pageState = rememberPagerState(pageCount = { 2 })
    val navigationScope = rememberCoroutineScope()
    var fontSizePreview by remember { mutableStateOf<Float?>(null) }
    // Parse static legal documents off the UI thread before either entry is opened.
    // These states outlive their destinations and are reused on subsequent visits.
    val terms = rememberMarkdownState(LegalDocuments.TERMS_OF_SERVICE)
    val privacy = rememberMarkdownState(LegalDocuments.PRIVACY_POLICY)
    val context=LocalContext.current
    val appData = (context.applicationContext as HydrosApplication).appData
    // The installer exposes at most one pending settings/confirmation intent; launch it as the
    // system would after a silent commit so the user never has to find the right button.
    LaunchedEffect(updates.install.phase, updates.install.error) {
        appInfo.takeUpdateLaunchIntent()?.let { intent ->
            if (runCatching { context.startActivity(intent) }.isFailure) appInfo.restoreUpdateLaunchIntent(intent)
        }
    }
    val snackbar = remember { HydrosSnackbarState() }
    var permissionTarget by remember { mutableStateOf<DeviceUi?>(null) }
    fun bluetoothGranted() = listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT).all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }
    val bluetoothPermission=rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { _ ->
        permissionTarget?.let { target ->
            if (bluetoothGranted()) {
                permissionTarget = null
                model.connect(target)
            } else snackbar.show("请允许附近设备权限，授权后将继续连接", action = dev.uednd.hydros.ui.components.SnackbarAction("前往设置") {
                context.startActivity(android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:${context.packageName}")))
            }, error = true)
        }
    }
    fun requestConnect(device: DeviceUi) {
        if (!state.signedIn || state.reauthentication != null) return
        val permissions=arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        if(permissions.any { ContextCompat.checkSelfPermission(context, it)!=PackageManager.PERMISSION_GRANTED }) {
            permissionTarget=device; bluetoothPermission.launch(permissions)
        } else model.connect(device)
    }
    fun openPage(destination: String) {
        if (state.reauthentication != null) return
        if (!state.signedIn && destination != "session" && !destination.startsWith("legal/")) return
        nav.navigate(destination)
    }
    var handledSession by rememberSaveable { mutableStateOf<String?>(null) }
    var billFromSession by rememberSaveable { mutableStateOf(false) }
    fun dismissSessionScreen() {
        if (state.session == null) {
            handledSession = null
            model.dismissPostSessionNavigation()
        }
    }
    fun back() {
        if (state.reauthentication == null) {
            if (nav.currentDestination?.route == "session") dismissSessionScreen()
            nav.popBackStack()
        }
    }
    val permission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (!it) snackbar.show("未开启通知权限，后台设备通知可能无法显示")
    }
    LaunchedEffect(state.initializing, state.signedIn, state.onboardingCompleted, state.reauthentication) {
        if(!state.initializing && state.reauthentication == null) {
            val currentRoute=nav.currentDestination?.route
            val target=when {
                !state.signedIn && !state.onboardingCompleted && currentRoute != "onboarding" -> "onboarding"
                !state.signedIn && state.onboardingCompleted && currentRoute != "login" -> "login"
                state.signedIn && (currentRoute == "login" || currentRoute == "onboarding") -> if (state.session != null) "session" else "home"
                else -> null
            }
            if(target != null) {
                if (target == "home") pageState.requestScrollToPage(0)
                nav.navigate(target) { popUpTo(nav.graph.id) { inclusive=true }; launchSingleTop=true }
            }
        }
    }
    AutoUpdateChecks(appInfo)
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(state.reauthentication?.id, lifecycleOwner) {
        val notice = state.reauthentication ?: return@LaunchedEffect
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            val ticket = snackbar.show(notice.message, error = true, protected = true) ?: return@repeatOnLifecycle
            try {
                if (snackbar.awaitDismissal(ticket) != SnackbarEnd.REPLACED) {
                    nav.navigate("login") { popUpTo(nav.graph.id) { inclusive = true }; launchSingleTop = true }
                    model.acknowledgeReauthentication(notice.id)
                }
            } finally {
                snackbar.dismiss(ticket.id, SnackbarEnd.REPLACED)
            }
        }
    }
    BackHandler(state.reauthentication != null) { }
    LaunchedEffect(state.initializing, state.session?.device?.id, state.session?.startedAt, state.session?.stage,
        state.working, state.reauthentication, state.signedIn,
        state.resolvingPostSessionDestination, state.postSessionBillId) {
        if(!state.initializing && state.signedIn && state.reauthentication == null) {
            // The durable opening intent may still receive an explicit rejection (and prepare BLE).
            // Keep its preparation owner alive until an outcome or recovery state is available.
            if (state.working && state.session?.stage == SessionStage.OPENING) return@LaunchedEffect
            val sessionId=state.session?.let { "${it.device.id}:${it.startedAt}" }
            if(sessionId != null && sessionId != handledSession) {
                handledSession=sessionId; nav.navigate("session") { launchSingleTop=true }
            } else if(sessionId == null && handledSession != null && state.signedIn) {
                // The end-use destination may still be resolving a matching bill.
                if (state.resolvingPostSessionDestination) return@LaunchedEffect
                handledSession=null
                val billId=state.postSessionBillId
                pageState.requestScrollToPage(0)
                if(billId != null) {
                    model.consumePostSessionBill(); billFromSession=true
                    nav.navigate("bill/$billId") { popUpTo("home"); launchSingleTop=true }
                } else {
                    if (state.postSessionDestination == PostSessionDestination.BILL)
                        snackbar.show("未找到本次账单，已返回设备页")
                    nav.navigate("home") { popUpTo("home"); launchSingleTop=true }
                }
            }
        }
    }
    val primaryStates = rememberSaveableStateHolder()
    val entry by nav.currentBackStackEntryAsState()
    val navigationRoute = entry?.destination?.route ?: "home"
    val route = if (navigationRoute == "home") destinations[pageState.currentPage].route else navigationRoute
    LaunchedEffect(entry?.id, state.signedIn) {
        if (!state.signedIn || entry?.destination?.route != "device/{id}" ||
            permissionTarget?.id != entry?.arguments?.getString("id")) permissionTarget = null
    }
    DisposableEffect(lifecycleOwner, nav, model, appInfo) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val current = nav.currentBackStackEntry
                val deviceId = current?.arguments?.getString("id")
                model.recoverQueries(deviceId.takeIf { current?.destination?.route == "bill/{id}" })
                appInfo.onUpdateResumed()
                if (bluetoothGranted() && current?.destination?.route == "device/{id}" && deviceId != null) {
                    val pending = permissionTarget?.takeIf { it.id == deviceId }
                    if (pending != null) { permissionTarget = null; model.connect(pending) }
                    else model.recoverConnection(deviceId)
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    BackHandler(navigationRoute == "home" && pageState.currentPage == 1 && state.reauthentication == null) {
        navigationScope.launch {
            if (state.feedback.reduceMotionEnabled) pageState.scrollToPage(0) else pageState.animateScrollToPage(0)
        }
    }
    val targetDeviceId = if (route == "device/{id}") entry?.arguments?.getString("id") else null
    val targetDevice = targetDeviceId?.let { id -> state.devices.find { it.id == id } }
    LaunchedEffect(entry?.id, targetDevice != null) {
        if (targetDevice != null) requestConnect(targetDevice)
        else if (route != "device/{id}") model.cancelConnection()
    }
    fun main(destination: String) {
        if (route == destination || !state.signedIn || state.reauthentication != null) return
        val page = destinations.take(2).indexOfFirst { it.route == destination }
        if (page >= 0) {
            if (navigationRoute != "home") {
                // A restored secondary route may not have laid out the pager yet.
                pageState.requestScrollToPage(page)
                nav.navigate("home") { popUpTo("home"); launchSingleTop = true }
            } else navigationScope.launch {
                if (state.feedback.reduceMotionEnabled) pageState.scrollToPage(page)
                else pageState.animateScrollToPage(page)
            }
            return
        }
        // Only primary pages belong to a tab. Preserve their UI state explicitly;
        // never restore a secondary route as the destination of a main tab.
        nav.navigate(destination) {
            popUpTo("home")
            launchSingleTop = true
        }
    }
    val density = LocalDensity.current
    var mainBarHeight by remember { mutableStateOf(80.dp) }
    val updateBarHeight: (Int) -> Unit = { mainBarHeight = with(density) { it.toDp() } }
    HydrosTheme(state.theme, feedback = state.feedback, fontSizeScale = fontSizePreview ?: state.fontSizeScale) {
        val pageEasing = remember { CubicBezierEasing(0.2f, 0f, 0f, 1f) }
        val haptics = rememberHydrosHaptics()
        var previousConnection by remember { mutableStateOf(state.connectionStage) }
        LaunchedEffect(state.connectionStage) {
            if (previousConnection == ConnectionStage.CONNECTING && state.connectionStage == ConnectionStage.READY) haptics.success()
            previousConnection = state.connectionStage
        }
        LaunchedEffect(pageState.settledPage) { if (pageState.settledPage != 0) model.stopDiscovery() }
        val window = LocalActivity.current?.window
        val lightSystemBars = MaterialTheme.colorScheme.background.luminance() > 0.5f
        SideEffect {
            window?.let {
                WindowCompat.getInsetsController(it, it.decorView).apply {
                    isAppearanceLightStatusBars = lightSystemBars
                    isAppearanceLightNavigationBars = lightSystemBars
                }
            }
        }
        // Each destination owns its chrome so both pages retain their own bars during a back gesture.
        HydrosSnackbarProvider(snackbar, bottomPadding = if (destinations.any { it.route == route }) mainBarHeight else 0.dp) {
            HydrosNotificationEffects(state, updates, model::dismissMessage, model::refresh, model::loadMonth, appInfo::checkForUpdates, showUpdateErrors = route != "about/update",
                announceSession = route != "session" || state.session?.let { state.working || it.stage in listOf(SessionStage.OPENING, SessionStage.STOPPING, SessionStage.SETTLING) } != true,
                onSession = { openPage("session") })
            HoldActionHost {
                Box(Modifier.fillMaxSize()) {
                    NavHost(
                        // Fading only the parent over this backplate gives a subtle 10% scrim.
                        navController = nav, startDestination = "home", modifier = Modifier.fillMaxSize().background(Color.Black).pointerInput(state.reauthentication, route) {
                            if (state.reauthentication != null && route != "session") awaitPointerEventScope {
                                while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                            }
                        },
                        // Tab changes and reduced-motion navigation have no page translation.
                        enterTransition = {
                            if (state.feedback.reduceMotionEnabled || isTabSwitch(initialState.destination.route, targetState.destination.route)) EnterTransition.None
                            else slideInHorizontally(tween(300, easing = pageEasing)) { it }
                        },
                        exitTransition = {
                            if (state.feedback.reduceMotionEnabled || isTabSwitch(initialState.destination.route, targetState.destination.route)) ExitTransition.None
                            else slideOutHorizontally(tween(300, easing = pageEasing)) { -it / 5 } +
                                fadeOut(tween(300, easing = pageEasing), targetAlpha = 0.9f)
                        },
                        popEnterTransition = {
                            if (state.feedback.reduceMotionEnabled || isTabSwitch(initialState.destination.route, targetState.destination.route)) EnterTransition.None
                            else slideInHorizontally(tween(300, easing = pageEasing)) { -it / 5 } +
                                fadeIn(tween(300, easing = pageEasing), initialAlpha = 0.9f)
                        },
                        popExitTransition = {
                            if (state.feedback.reduceMotionEnabled || isTabSwitch(initialState.destination.route, targetState.destination.route)) ExitTransition.None
                            else slideOutHorizontally(tween(300, easing = pageEasing)) { it }
                        },
                    ) {
                        composable("home") {
                            val pageRoute = destinations[pageState.currentPage].route
                            HydrosPage("首页",
                                titleContent = { HydrosPrimaryTabs(pageState, ::main) },
                                containerColor = MaterialTheme.colorScheme.surface,
                                bottomBar = { HydrosBottomBar(pageRoute, ::main, onScan = { openPage("scan") }, onHeight = updateBarHeight) }) {
                                HorizontalPager(
                                    state = pageState, modifier = Modifier.fillMaxSize().testTag("primary-pager"),
                                    userScrollEnabled = state.signedIn && state.reauthentication == null,
                                    flingBehavior = rememberPrimaryPagerFlingBehavior(pageState, state.feedback.reduceMotionEnabled),
                                    key = { destinations[it].route }
                                ) { page ->
                                    primaryStates.SaveableStateProvider(destinations[page].route) {
                                        if (page == 0) HomeScreen(state, onScan = { openPage("scan") },
                                            onDevice = { openPage("device/$it") },
                                            onNearbyDevice = { openPage("device/$it") },
                                            onSession = { openPage("session") },
                                            onScanNearby = model::discoverNearby, onStopNearby = model::stopDiscovery)
                                        else WalletScreen(state, onMonth = model::loadMonth, onBillType = model::selectBillType, onRefresh = model::refresh, onBill = { billFromSession = false; openPage("bill/$it") })
                                    }
                                }
                            }
                        }
                        composable("mine") {
                            HydrosPage("我的", bottomBar = { HydrosBottomBar("mine", ::main, onScan = { openPage("scan") }, onHeight = updateBarHeight) }) {
                                primaryStates.SaveableStateProvider("mine") {
                                    MineScreen(state, onRefresh = model::refresh, onManageAccount = { openPage("account") },
                                        onAppearance = { openPage("appearance") },
                                        onFeedback = { openPage("feedback") },
                                        onDisplay = { openPage("display") },
                                        onAbout = { openPage("about") }, hasUpdate = updates.hasUpdate)
                                }
                            }
                        }
                        composable("account") {
                            HydrosPage("管理账户", onBack = { back() }) {
                                ManageAccountScreen(state,
                                    onPhone = { openPage("account/PHONE") }, onPassword = { openPage("account/PASSWORD") },
                                    onLogout = model::logout, onSession = { openPage("session") })
                            }
                        }
                        composable("appearance") {
                            HydrosPage("应用外观", onBack = { back() }) {
                                AppearanceScreen(state.theme, onTheme = model::theme)
                            }
                        }
                        composable("feedback") {
                            HydrosPage("反馈设置", onBack = { back() }) {
                                FeedbackSettingsScreen(state.feedback, onHaptic = model::hapticFeedback, onReduceMotion = model::reduceMotion)
                            }
                        }
                        composable("display") {
                            HydrosPage("显示设置", onBack = { back() }) {
                                DisplaySettingsScreen(state.fontSizeScale, onPreview = { fontSizePreview = it }, onSave = model::fontSizeScale,
                                    postSessionDestination = state.postSessionDestination, onPostSessionDestination = model::postSessionDestination)
                            }
                        }
                        composable("about") {
                            HydrosPage("关于 Hydros", onBack = { back() }) {
                                AboutScreen(updates, onUpdates = { openPage("about/update") },
                                    onLicenses = { openPage("about/licenses") },
                                    onProjectLicense = { openPage("about/licenses/project") },
                                    onTerms = { openPage("legal/terms") },
                                    onPrivacy = { openPage("legal/privacy") },
                                    onDeveloper = { openPage("about/developer") },
                                    appData = appData,
                                    onClear = { model.clearAllData(appData::clearAllData) })
                            }
                        }
                        composable("about/update") {
                            HydrosPage("软件更新", onBack = { back() }) {
                                LaunchedEffect(Unit) { appInfo.checkForUpdates() }
                                SoftwareUpdateScreen(updates, onHistory = { openPage("about/releases") },
                                    onInstall = appInfo::installUpdate,
                                    onContinue = { appInfo.continueInstall { intent ->
                                        if (runCatching { context.startActivity(intent) }.isFailure) appInfo.restoreUpdateLaunchIntent(intent)
                                    } },
                                    onCancel = appInfo::cancelInstall, onCheck = appInfo::checkForUpdates)
                            }
                        }
                        composable("about/releases") {
                            LaunchedEffect(Unit) { if (!history.loaded) appInfo.loadHistory() }
                            HydrosPage("更新历史", onBack = { back() }) {
                                ReleaseHistoryScreen(history, onRelease = { openPage("about/releases/${android.net.Uri.encode(it.tag)}") },
                                    onLoad = { appInfo.loadHistory() })
                            }
                        }
                        composable("about/releases/{tag}") { entry ->
                            val tag = entry.arguments?.getString("tag").orEmpty()
                            LaunchedEffect(tag) { appInfo.loadRelease(tag) }
                            HydrosPage(detail.releases.firstOrNull()?.name ?: tag, onBack = { back() }) {
                                detail.releases.firstOrNull()?.let { ReleaseDetailScreen(it) }
                                    ?: ReleaseHistoryScreen(detail, onRelease = {}, onLoad = { appInfo.loadRelease(tag) })
                            }
                        }
                        composable("about/developer") {
                            HydrosPage("开发者页面", onBack = { back() }) {
                                DeveloperScreen()
                            }
                        }
                        composable("about/licenses") {
                            HydrosPage("开源许可", onBack = { back() }) {
                                LicensesIndexScreen(onProject = { openPage("about/licenses/project") },
                                    onThirdParty = { openPage("about/licenses/third-party") })
                            }
                        }
                        composable("about/licenses/project") {
                            HydrosPage("本项目许可证", onBack = { back() }) { ProjectLicenseScreen() }
                        }
                        composable("about/licenses/third-party") {
                            HydrosPage("第三方依赖许可证", onBack = { back() }) { OpenSourceLicensesScreen() }
                        }
                        composable("legal/terms") {
                            HydrosPage("服务协议", onBack = { back() }) {
                                LegalDocumentScreen(LegalDocuments.TERMS_OF_SERVICE, terms)
                            }
                        }
                        composable("legal/privacy") {
                            HydrosPage("隐私政策", onBack = { back() }) {
                                LegalDocumentScreen(LegalDocuments.PRIVACY_POLICY, privacy)
                            }
                        }
                        composable("onboarding") {
                            OnboardingScreen(
                                onComplete = model::completeOnboarding,
                                onTerms = { openPage("legal/terms") },
                                onPrivacy = { openPage("legal/privacy") }
                            )
                        }
                        composable("login") {
                            HydrosPage("登录") {
                                Column {
                                    state.session?.let { TextButton(onClick = { openPage("session") }) { Text("返回当前设备，关闭或恢复使用") } }
                                    LoginScreen(onSend = { model.sendCode(it, 3) }, onLogin = model::login,
                                        loginRetryAfterMillis = { model.graph.auth.loginRetryAfterMillis })
                                }
                            }
                        }
                        composable("device/{id}") { backStack ->
                            val device = state.devices.find { it.id == backStack.arguments?.getString("id") }
                            HydrosPage("设备详情", onBack = { back() }) {
                                DeviceDetailScreen(device, state, ::requestConnect, onStart = {
                                    if (Build.VERSION.SDK_INT >= 33) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                                    model.start(it)
                                }, onSession = { openPage("session") }, onBack = { back() }, onFavorite = model::toggleFavorite, onSaveRemark = model::saveDeviceRemark)
                            }
                        }
                        composable("scan") {
                            HydrosPage("扫码连接", onBack = { back() }) {
                                ScanScreen(onResolve = model::scan, enabled = state.signedIn && state.reauthentication == null,
                                    queryProgress = state.networkMessage) { openPage("device/$it") }
                            }
                        }
                        composable("session") {
                            HydrosPage("设备使用", onBack = { back() }) {
                                SessionScreen(state.session, onFinish = model::finish, onHome = {
                                    dismissSessionScreen()
                                    main("home")
                                }, remarks = state.deviceRemarks, working = state.working, observation = state.observation,
                                    resolvingDestination = state.resolvingPostSessionDestination)
                            }
                        }
                        composable("bill/{id}") { backStack ->
                            val billId = backStack.arguments?.getString("id")
                            LaunchedEffect(backStack.id) { billId?.let(model::loadDetail) }
                            HydrosPage("账单详情", onBack = { back() }) {
                                val currentBill = billId?.let { id ->
                                    state.billDetail?.takeIf { it.id == id } ?: state.bills.find { it.id == id }
                                }
                                val detailSync = billId?.let { state.billDetailSync[it] }
                                BillDetailScreen(
                                    bill = currentBill,
                                    onWallet = { if (state.reauthentication == null) nav.popBackStack("home", inclusive = false) },
                                    backLabel = if (billFromSession) "返回首页" else "返回钱包",
                                    durationPending = currentBill != null && !currentBill.recharge &&
                                        currentBill.durationText == null && detailSync?.fetching == true,
                                    error = detailSync?.error,
                                    onRetry = { billId?.let { model.loadDetail(it, force = true) } },
                                )
                            }
                        }
                        composable("account/{form}") { backStack ->
                            val form = AccountForm.valueOf(backStack.arguments!!.getString("form")!!)
                            HydrosPage(form.title, onBack = { back() }) {
                                AccountFormScreen(form, state.account, onSend = model::sendCode) { phone, old, password, code ->
                                    val saved = model.saveAccount(form, phone, old, password, code)
                                    if (saved && form != AccountForm.PHONE) back()
                                    saved
                                }
                            }
                        }
                    }

                }
            }
        }
    }
}




@Composable
private fun HydrosPrimaryTabs(pager: PagerState, onNavigate: (String) -> Unit) {
    val haptics = rememberHydrosHaptics()
    val colors = MaterialTheme.colorScheme
    val typography = MaterialTheme.typography
    val density = LocalDensity.current
    // Fixed slots keep the title centers steady while their type sizes change.
    val tabWidth = with(density) { typography.headlineSmall.fontSize.toDp() * 2 + 16.dp }
    val titleHeight = with(density) { typography.headlineSmall.lineHeight.toDp() }
    val position = (pager.currentPage + pager.currentPageOffsetFraction).coerceIn(0f, 1f)
    Box(Modifier.testTag("primary-switcher")) {
        Row {
            destinations.take(2).forEachIndexed { index, page ->
                val emphasis = (1f - kotlin.math.abs(position - index)).coerceIn(0f, 1f)
                val selected = pager.currentPage == index
                Box(
                    Modifier.width(tabWidth).heightIn(min = 48.dp).height(titleHeight + 16.dp)
                        .selectable(selected = selected, role = Role.Tab, onClick = {
                            if (!selected) { haptics.medium(); onNavigate(page.route) }
                        }).padding(bottom = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(page.label, modifier = Modifier.testTag("primary-title-${page.route}"),
                        style = typography.headlineSmall,
                        fontSize = androidx.compose.ui.unit.lerp(typography.titleMedium.fontSize, typography.headlineSmall.fontSize, emphasis),
                        fontWeight = FontWeight.Bold,
                        color = androidx.compose.ui.graphics.lerp(colors.onSurfaceVariant, colors.primary, emphasis),
                        maxLines = 1, softWrap = false)
                }
            }
        }
        // Read the pager's actual drag/settle progress; no second animation can lag behind the finger.
        Box(Modifier.align(Alignment.BottomStart)
            .offset(x = tabWidth * (position + 0.5f) - 14.dp, y = (-4).dp)
            .size(width = 28.dp, height = 3.dp).testTag("primary-tab-indicator")
            .clip(CircleShape).background(colors.primary))
    }
}

@Composable
private fun HydrosBottomBar(route: String, onNavigate: (String) -> Unit, onScan: () -> Unit, onHeight: (Int) -> Unit) {
    val haptics = rememberHydrosHaptics()
    val windowHeight = LocalConfiguration.current.screenHeightDp
    val density = LocalDensity.current
    // Base tab bar spacing; navigationBarsPadding adds the separate system safe area.
    val baseHeight = when { windowHeight <= 560 -> 50.dp; windowHeight <= 720 -> 58.dp; else -> 68.dp }
    val barHeight = with(density) {
        maxOf(baseHeight, MaterialTheme.typography.labelLarge.lineHeight.toDp() + 29.dp)
    }
    Surface(color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.testTag("main-bottom-bar").navigationBarsPadding()) {
            HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            Row(
                Modifier.fillMaxWidth().height(barHeight - 1.dp)
                    .onSizeChanged { onHeight(it.height + with(density) { 1.dp.roundToPx() }) }
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.weight(2f), contentAlignment = Alignment.Center) {
                    val selected = route == "home" || route == "wallet"
                    val colors = MaterialTheme.colorScheme
                    val contentColor = if (selected) colors.onSecondaryContainer else colors.onSurfaceVariant
                    val interaction = remember { MutableInteractionSource() }
                    val pressed by interaction.collectIsPressedAsState()
                    val iconBackground = if (selected) colors.secondaryContainer else Color.Transparent
                    Column(
                        Modifier.clip(CircleShape)
                            .selectable(selected = selected, role = Role.Tab,
                                interactionSource = interaction, indication = null, onClick = {
                                if (!selected) { haptics.medium(); onNavigate("home") }
                            })
                            .heightIn(min = 48.dp).padding(horizontal = 16.dp, vertical = 2.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Box(Modifier.size(width = 48.dp, height = 28.dp).clip(CircleShape)
                            .background(if (pressed) colors.onSurface.copy(alpha = 0.1f).compositeOver(iconBackground) else iconBackground),
                            contentAlignment = Alignment.Center) {
                            Glyph(if (selected) R.drawable.ic_home else R.drawable.ic_home_outline,
                                modifier = Modifier.size(22.dp), tint = contentColor)
                        }
                        Text("首页", style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant)
                    }
                }
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    FilledIconButton(onClick = { haptics.light(); onScan() }, shape = CircleShape, modifier = Modifier.size(48.dp).testTag("scan-action")) {
                        Glyph(R.drawable.ic_scan, modifier = Modifier.size(22.dp), description = "扫码")
                    }
                }
                Box(Modifier.weight(2f), contentAlignment = Alignment.Center) {
                    val mine = destinations.last()
                    val selected = route == mine.route
                    val colors = MaterialTheme.colorScheme
                    val contentColor = if (selected) colors.onSecondaryContainer else colors.onSurfaceVariant
                    val interaction = remember { MutableInteractionSource() }
                    val pressed by interaction.collectIsPressedAsState()
                    val iconBackground = if (selected) colors.secondaryContainer else Color.Transparent
                    Column(
                        Modifier.clip(CircleShape)
                            .selectable(selected = selected, role = Role.Tab,
                                interactionSource = interaction, indication = null, onClick = {
                                if (!selected) { haptics.medium(); onNavigate(mine.route) }
                            })
                            .heightIn(min = 48.dp).padding(horizontal = 16.dp, vertical = 2.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Box(Modifier.size(width = 48.dp, height = 28.dp).clip(CircleShape)
                            .background(if (pressed) colors.onSurface.copy(alpha = 0.1f).compositeOver(iconBackground) else iconBackground),
                            contentAlignment = Alignment.Center) {
                            Glyph(if (selected) mine.icon else mine.iconOutline,
                                modifier = Modifier.size(22.dp), tint = contentColor)
                        }
                        Text(mine.label, style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant)
                    }
                }
            }
        }
    }
}
