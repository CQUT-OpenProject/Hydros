package dev.uednd.hydros

import dev.uednd.hydros.data.storage.FeedbackSettings
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.graphics.asAndroidBitmap
import dev.uednd.hydros.data.*
import dev.uednd.hydros.data.ble.*
import dev.uednd.hydros.data.network.*
import dev.uednd.hydros.data.repository.*
import dev.uednd.hydros.data.storage.*
import dev.uednd.hydros.ui.HydrosApp
import dev.uednd.hydros.ui.state.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** All remote data and hardware are injected here; production has no demo mode. */
class HydrosFlowTest {
    @get:Rule val ui=createComposeRule()
    private var scope: CoroutineScope?=null
    private lateinit var transport: UiTransport
    private lateinit var store: UiStore
    private lateinit var host: androidx.activity.ComponentActivity
    private lateinit var model: HydrosViewModel
    private lateinit var appInfo: AppInfoViewModel
    private val results = object : androidx.activity.result.ActivityResultRegistry(), androidx.activity.result.ActivityResultRegistryOwner {
        override val activityResultRegistry get() = this
        override fun <I, O> onLaunch(requestCode: Int, contract: androidx.activity.result.contract.ActivityResultContract<I, O>, input: I,
            options: androidx.core.app.ActivityOptionsCompat?) {
            when (contract) {
                is androidx.activity.result.contract.ActivityResultContracts.RequestPermission -> dispatchResult(requestCode, false)
                is androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions -> dispatchResult(requestCode, emptyMap<String, Boolean>())
                else -> error("Unexpected Activity result in offline flow: $contract")
            }
        }
    }
    private val credentials=Credentials("test-only","1","2","3","13800138000")
    private fun open(signedIn: Boolean=true, session: SessionUi?=null, onboarding: Boolean=true, withBills: Boolean=false, compact: Boolean=false,
        ble: BlePort = UiBle(),
        updateSource: dev.uednd.hydros.data.update.AppUpdateSource = dev.uednd.hydros.data.update.AppUpdateSource { null }) {
        transport=UiTransport().apply { this.withBills = withBills }; store=UiStore().apply { this.onboarding = onboarding; if (compact) mode = "DARK" }
        if(signedIn) store.auth=credentials
        val device=DeviceUi("AABBCCDDEEFF","测试用水表","测试宿舍",DeviceCategory.SHOWER,channel=ControlChannel.CLOUD,
            deviceId="7",ledgerMac="AABBCCDDEEFF",connectMac="AA:BB:CC:DD:EE:FF")
        store.record=SavedAccount(devices=listOf(device),recent=listOf(device.id),session=session)
        val appScope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate); scope=appScope
        val lock=Mutex()
        val graph=HydrosDependencies(RealAuthRepository(transport,store),RealAccountRepository(transport),RealWalletRepository(transport),
            RealDeviceRepository(transport,store,ble,lock),RealSessionRepository(transport,store,ble,lock,2,{}),store,appScope)
        ui.setContent {
            host = androidx.activity.compose.LocalActivity.current as androidx.activity.ComponentActivity
            val density = LocalDensity.current
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.activity.compose.LocalActivityResultRegistryOwner provides results,
                LocalDensity provides if (compact) Density(density.density, 1.6f) else density
            ) {
                Box(if (compact) Modifier.requiredSize(320.dp, 640.dp) else Modifier) {
                    HydrosApp(androidx.compose.runtime.remember { HydrosViewModel(graph).also { model=it } },
                        androidx.compose.runtime.remember { AppInfoViewModel(updateSource).also { appInfo = it } })
                }
            }
        }
        waitFor(session?.let { if (it.stage == SessionStage.USING) "正在${it.device.category.label}" else it.stage.label } ?: if(signedIn) "扫码连接" else if (onboarding) "欢迎回到 Hydros" else "欢迎使用 Hydros")
    }
    @After fun cleanup() { scope?.cancel() }
    @Test fun rejectedCloudOpeningKeepsPreparationAndRequiresSecondStartForBle() {
        var rates = 0
        val ble = object : BlePort {
            override var ready = false
            override suspend fun connect(mac: String): BleConnectResult { ready = true; return BleConnectResult.Ready }
            override suspend fun query() = DeviceQueryState("20", "ABCDEF01", 0, 1, 2)
            override suspend fun writeRate(bytes: ByteArray): Boolean { rates++; return true }
            override suspend fun closeValve() = false
            override suspend fun collect(): ByteArray? = null
            override suspend fun writeBack(bytes: ByteArray) = false
            override fun disconnect() { ready = false }
        }
        open(ble = ble)
        val rejection = CompletableDeferred<ApiResponse<JsonElement>>()
        transport.cloudOpenResult = rejection
        transport.deviceInfo = """{"snCode":"AABBCCDDEEFF","deviceName":"测试用水表","deviceId":"7","macAddress":"AABBCCDDEEFF","bigTypeId":4,"smallTypeId":1}"""
        ui.runOnIdle { model.connect(store.record.devices.single()) }
        ui.waitUntil(8_000) { model.state.value.connectionStage == ConnectionStage.READY }
        ui.runOnIdle { model.start(model.state.value.devices.single()) }
        ui.waitUntil(8_000) { transport.cloudOpenEntered }
        ui.onNodeWithText("扫码连接").assertExists()
        ui.onNodeWithText("设备使用").assertDoesNotExist()
        ui.runOnIdle { rejection.complete(ApiResponse(success = false, errorCode = 306)) }
        ui.waitUntil(8_000) {
            model.state.value.connectionStage == ConnectionStage.READY && model.state.value.devices.single().channel == ControlChannel.BLE
        }
        ui.runOnIdle {
            assertNull(model.state.value.session)
            assertEquals(0, rates)
            assertFalse(model.state.value.working)
            model.start(model.state.value.devices.single())
        }
        waitFor("正在洗浴")
        assertEquals(1, rates)
    }
    private fun waitFor(text: String) { ui.waitUntil(8_000) { ui.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() } }
    private fun click(text: String) {
        val node=ui.onAllNodes(hasText(text) and hasClickAction()).onLast()
        runCatching { node.performScrollTo() }; node.performClick(); ui.waitForIdle()
    }
    private fun selectMonth(month: java.time.YearMonth) {
        val current = java.time.YearMonth.parse(model.state.value.month)
        click("${current.year}年${current.monthValue}月")
        ui.onNodeWithContentDescription("年份").performScrollToNode(hasText("${month.year}年"))
        click("${month.year}年")
        ui.onNodeWithContentDescription("月份").performScrollToNode(hasText("${month.monthValue}月"))
        click("${month.monthValue}月")
        click("确定")
        ui.waitUntil(8_000) { model.state.value.month == month.toString() }
    }

    @Test fun tabsSwitchWithoutSlidingWhileSecondaryPagesStillSlide() {
        open()
        fun title(text: String) = ui.onNode(hasText(text) and !hasClickAction())
        val titleLeft = ui.onNodeWithTag("primary-switcher").fetchSemanticsNode().boundsInRoot.left
        ui.mainClock.autoAdvance = false
        try {
            // Check during the transition window, including a tab change that pops to home.
            listOf("钱包", "我的", "首页", "我的").forEach { tab ->
                ui.onNode(hasText(tab) and hasClickAction()).performClick()
                ui.mainClock.advanceTimeBy(64)
                val heading = if (tab == "我的") ui.onNodeWithTag("page-title") else ui.onNodeWithTag("primary-switcher")
                assertEquals(titleLeft, heading.fetchSemanticsNode().boundsInRoot.left, 1f)
                ui.onNode(hasText(tab) and hasClickAction()).assertIsSelected()
                ui.mainClock.advanceTimeBy(300)
            }
            ui.onNode(hasText("应用外观") and hasClickAction()).performClick()
            ui.mainClock.advanceTimeBy(64)
            val enteringLeft = title("应用外观").fetchSemanticsNode().boundsInRoot.left
            ui.mainClock.advanceTimeBy(300)
            assertTrue(enteringLeft > title("应用外观").fetchSemanticsNode().boundsInRoot.left + 1f)
            ui.onNodeWithText("主题外观").assertIsDisplayed()
        } finally {
            ui.mainClock.autoAdvance = true
        }
    }
    @Test fun topTabsFollowDragAndSettleWithPager() {
        open()
        fun indicator() = ui.onNodeWithTag("primary-tab-indicator").fetchSemanticsNode().boundsInRoot.center.x
        fun titleWidth(route: String) = ui.onNodeWithTag("primary-title-$route", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot.width
        val start = indicator()
        val homeWidth = titleWidth("home")
        val walletWidth = titleWidth("wallet")
        val pager = ui.onNodeWithTag("primary-pager")
        pager.performTouchInput {
            down(center)
            moveBy(androidx.compose.ui.geometry.Offset(-width * 0.3f, 0f), delayMillis = 300)
        }
        ui.waitForIdle()
        val duringDrag = indicator()
        assertTrue(duringDrag > start + 1f)
        assertTrue(titleWidth("home") < homeWidth)
        assertTrue(titleWidth("wallet") > walletWidth)
        // Reverse an unfinished drag and verify both the indicator and selection return.
        pager.performTouchInput { moveTo(center, delayMillis = 300); up() }
        ui.waitForIdle()
        assertEquals(start, indicator(), 1f)
        ui.onNode(hasText("设备") and hasClickAction()).assertIsSelected()
        pager.performTouchInput { swipeLeft() }
        ui.waitForIdle()
        assertTrue(indicator() > duringDrag + 1f)
        ui.onNode(hasText("钱包") and hasClickAction()).assertIsSelected()
        ui.onNode(hasText("首页") and hasClickAction()).assertIsSelected()
        assertTrue(titleWidth("wallet") > titleWidth("home"))
        screenshot("top-tabs-wallet")
        click("设备")
        assertEquals(start, indicator(), 1f)
        screenshot("top-tabs-devices")
    }

    @Test fun persistentScannerReturnsToEachPrimaryPageAndHasNoSelectedTab() {
        open()
        val root = ui.onRoot().fetchSemanticsNode().boundsInRoot
        val scanner = ui.onNodeWithTag("scan-action").fetchSemanticsNode().boundsInRoot
        assertEquals(root.center.x, scanner.center.x, 1f)
        for (page in listOf("设备", "钱包", "我的")) {
            click(page)
            if (page == "我的") {
                ui.onNode(hasText("首页") and hasClickAction()).assertIsNotSelected()
                ui.onNodeWithTag("primary-switcher").assertDoesNotExist()
            } else {
                ui.onNode(hasText("首页") and hasClickAction()).assertIsSelected()
                val tabs = ui.onNodeWithTag("primary-switcher").fetchSemanticsNode().boundsInRoot
                val bottom = ui.onNodeWithTag("main-bottom-bar").fetchSemanticsNode().boundsInRoot
                assertTrue(tabs.bottom < bottom.top)
            }
            screenshot("bottom-${if (page == "设备") "devices" else if (page == "钱包") "wallet" else "mine"}")
            ui.onNodeWithTag("scan-action").performClick()
            waitFor("从相册选择")
            ui.onNodeWithTag("main-bottom-bar").assertDoesNotExist()
            ui.onNodeWithContentDescription("返回").performClick()
            ui.onNode(hasText(page) and hasClickAction()).assertIsSelected()
        }
        click("首页")
        click("设备")
        val nearby = ui.onNodeWithText("附近设备").performScrollTo()
        val deviceTop = nearby.fetchSemanticsNode().boundsInRoot.top
        click("钱包")
        val previous = java.time.YearMonth.now().minusMonths(1)
        selectMonth(previous)
        click("设备")
        assertEquals(deviceTop, nearby.fetchSemanticsNode().boundsInRoot.top, 1f)
        click("钱包")
        ui.onNodeWithText("${previous.year}年${previous.monthValue}月").assertExists()
    }

    @Test fun bottomNavigationFitsSmallDarkLargeTextViewport() {
        open(compact = true)
        for (page in listOf("设备", "钱包", "我的")) {
            click(page)
            for (label in if (page == "我的") listOf("首页", "我的") else listOf("设备", "钱包", "首页", "我的")) {
                ui.onNode(hasText(label) and hasClickAction()).assertIsDisplayed()
            }
            ui.onNodeWithTag("scan-action").assertIsDisplayed()
            screenshot("bottom-compact-dark-${if (page == "设备") "devices" else if (page == "钱包") "wallet" else "mine"}")
        }
    }

    @Test fun billReturnsPopWithTheSameAnimationAndPreserveWalletState() {
        open(withBills = true)
        click("钱包")
        val previous = java.time.YearMonth.now().minusMonths(1)
        selectMonth(previous)
        ui.waitUntil(8_000) { !model.state.value.loadingBills && model.state.value.bills.size == 20 }
        ui.onNode(hasScrollToNodeAction() and
            SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange))
            .performScrollToNode(hasText("测试账单 12"))
        for (method in listOf("button", "toolbar", "system")) {
            val row = ui.onNodeWithText("测试账单 12").performScrollTo()
            val rowTop = row.fetchSemanticsNode().boundsInRoot.top
            if (method == "button") screenshot("wallet-bills-no-icons")
            row.performClick()
            ui.waitForIdle()
            ui.onNode(hasText("账单详情") and hasTestTag("page-title")).assertIsDisplayed()
            if (method == "button") ui.onNodeWithText("返回钱包").performScrollTo()
            val left = ui.onNode(hasText("账单详情") and hasTestTag("page-title")).fetchSemanticsNode().boundsInRoot.left
            ui.mainClock.autoAdvance = false
            try {
                when (method) {
                    "button" -> ui.onNodeWithText("返回钱包").performClick()
                    "toolbar" -> ui.onNodeWithContentDescription("返回").performClick()
                    else -> ui.runOnIdle { host.onBackPressedDispatcher.onBackPressed() }
                }
                ui.mainClock.advanceTimeBy(64)
                assertTrue("$method must slide the detail to the right",
                    ui.onNode(hasText("账单详情") and hasTestTag("page-title")).fetchSemanticsNode().boundsInRoot.left > left + 1f)
                ui.mainClock.advanceTimeBy(300)
            } finally { ui.mainClock.autoAdvance = true }
            ui.onNode(hasText("账单详情") and hasTestTag("page-title")).assertDoesNotExist()
            assertEquals(previous.toString(), model.state.value.month)
            assertEquals(rowTop, row.fetchSemanticsNode().boundsInRoot.top, 1f)
            ui.onNode(hasText("钱包") and hasClickAction()).assertIsSelected()
        }
    }

    @Test fun legalBodyMountsAfterEnterAndRemainsVisibleDuringPredictiveBack() {
        open(); click("我的"); click("关于 Hydros")
        for (document in listOf("服务协议", "隐私政策")) {
            ui.mainClock.autoAdvance = false
            try {
                ui.onNode(hasText(document) and hasClickAction()).performScrollTo().performClick()
                ui.mainClock.advanceTimeBy(64)
                ui.onNode(hasText(document) and hasTestTag("page-title")).assertExists()
                ui.onNodeWithText("Hydros $document").assertDoesNotExist()
                ui.mainClock.advanceTimeBy(350)
            } finally { ui.mainClock.autoAdvance = true }
            waitFor("Hydros $document")
            ui.onNodeWithText("Hydros $document").assertIsDisplayed()
            ui.mainClock.autoAdvance = false
            try {
                ui.runOnIdle {
                    host.onBackPressedDispatcher.dispatchOnBackStarted(androidx.activity.BackEventCompat(0f, 300f, 0f, androidx.activity.BackEventCompat.EDGE_LEFT))
                    host.onBackPressedDispatcher.dispatchOnBackProgressed(androidx.activity.BackEventCompat(150f, 300f, 0.5f, androidx.activity.BackEventCompat.EDGE_LEFT))
                }
                ui.mainClock.advanceTimeBy(64)
                ui.onNodeWithText("Hydros $document").assertExists()
                ui.runOnIdle { host.onBackPressedDispatcher.dispatchOnBackCancelled() }
                ui.mainClock.advanceTimeBy(350)
            } finally { ui.mainClock.autoAdvance = true }
            ui.onNodeWithText("Hydros $document").assertIsDisplayed()
            ui.onNodeWithContentDescription("返回").performClick()
            waitFor("构建时间")
        }
    }

    @Test fun walletLoadsRealFixtureAndHasNoRechargeEntry() {
        open(); click("钱包"); waitFor("¥ 12.34")
        ui.onNodeWithText("充值",substring=false).assertDoesNotExist()
        ui.onNodeWithText("¥ 37.60").assertDoesNotExist()
        val previous=java.time.YearMonth.now().minusMonths(1)
        selectMonth(previous)
        waitFor("所选月份暂无消费记录")
        assertTrue(transport.months.contains(previous.toString()))
    }
    // Pace the Compose clock in real time so navigation
    // actually presents frames rather than fast-forwarding through the animation.
    @Test fun profileLegalPagesFromAbout() = profileLegalPages(false)
    @Test fun profileLegalPagesFromOnboarding() = profileLegalPages(true)

    private fun profileLegalPages(fromOnboarding: Boolean) {
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        val label = androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("legalPerformance") ?: "verification"
        open(signedIn = !fromOnboarding, onboarding = !fromOnboarding)
        if (fromOnboarding) click("下一步") else { click("我的"); click("关于 Hydros") }
        val handlerThread = android.os.HandlerThread("legal-frame-metrics").apply { start() }
        val handler = android.os.Handler(handlerThread.looper)
        val frames = java.util.concurrent.CopyOnWriteArrayList<Pair<Long, Long>>()
        val captureStart = java.util.concurrent.atomic.AtomicLong(0)
        val listener = android.view.Window.OnFrameMetricsAvailableListener { _, metrics, _ ->
            frames += metrics.getMetric(android.view.FrameMetrics.INTENDED_VSYNC_TIMESTAMP) to
                metrics.getMetric(android.view.FrameMetrics.TOTAL_DURATION)
        }
        instrumentation.runOnMainSync { host.window.addOnFrameMetricsAvailableListener(listener, handler) }
        val results = buildJsonArray {
            try {
                repeat(2) { pass ->
                    listOf("服务协议", "隐私政策").forEach { document ->
                        frames.clear()
                        captureStart.set(System.nanoTime())
                        ui.mainClock.autoAdvance = false
                        try {
                            ui.onNode(hasText(document) and hasClickAction()).performScrollTo().performClick()
                            repeat(45) {
                                val start = android.os.SystemClock.uptimeMillis()
                                ui.mainClock.advanceTimeBy(16)
                                android.os.SystemClock.sleep((16 - (android.os.SystemClock.uptimeMillis() - start)).coerceAtLeast(0))
                            }
                        } finally { ui.mainClock.autoAdvance = true }
                        waitFor("Hydros $document")
                        val drained = java.util.concurrent.CountDownLatch(1)
                        handler.post { drained.countDown() }
                        check(drained.await(2, java.util.concurrent.TimeUnit.SECONDS))
                        val captured = frames.toList().filter { it.first >= captureStart.get() }
                        val sample = captured.map { it.second }.sorted()
                        check(sample.isNotEmpty()) { "No window frame metrics captured" }
                        add(buildJsonObject {
                            put("document", document)
                            put("pass", pass)
                            put("frames", sample.size)
                            put("p95Ms", sample[((sample.size - 1) * .95).toInt()] / 1_000_000.0)
                            put("maxMs", sample.last() / 1_000_000.0)
                            put("over50Ms", sample.count { it > 50_000_000 })
                            put("frozenFrames", sample.count { it > 700_000_000 })
                            put("durationNs", JsonArray(sample.map(::JsonPrimitive)))
                            put("timeline", buildJsonArray {
                                captured.forEach { (timestamp, duration) -> add(buildJsonObject {
                                    put("startMs", (timestamp - captureStart.get()) / 1_000_000.0)
                                    put("durationMs", duration / 1_000_000.0)
                                }) }
                            })
                        })
                        ui.onNodeWithContentDescription("返回").performClick()
                        ui.waitForIdle()
                    }
                }
            } finally {
                instrumentation.runOnMainSync { host.window.removeOnFrameMetricsAvailableListener(listener) }
                handlerThread.quitSafely()
            }
        }
        val source = if (fromOnboarding) "onboarding" else "about"
        java.io.File(instrumentation.targetContext.cacheDir, "legal-$label-$source.json").writeText(results.toString())
        println("LEGAL_FRAMES $label $source $results")
    }

    private fun screenshot(name: String) {
        if (androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("screenshots") != "true") return
        val context=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        java.io.File(context.cacheDir, "$name.png").outputStream().use {
            ui.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
    }
    @Test fun aboutShowsBuildMetadataLicensesAndLegalDocuments() {
        open(); click("我的")
        ui.onNodeWithText("v0.1.0").assertDoesNotExist()
        ui.onNodeWithText("有新版本！").assertDoesNotExist()
        click("关于 Hydros"); waitFor("构建时间")
        screenshot("about-light")
        ui.onNodeWithText(BuildConfig.VERSION_NAME).assertExists()
        ui.onNodeWithText("服务协议").assertExists()
        ui.onNodeWithText("隐私政策").assertExists()
        click("服务协议"); waitFor("Hydros 服务协议")
        ui.onNodeWithContentDescription("返回").performClick()
        click("隐私政策"); waitFor("Hydros 隐私政策")
        ui.onNodeWithContentDescription("返回").performClick()
        click("当前版本"); waitFor("当前已是最新版本")
        ui.onNodeWithContentDescription("返回").performClick()
        click("开源许可"); waitFor("本项目许可证")
        screenshot("licenses-index")
        click("本项目许可证")
        ui.waitUntil(5_000) { ui.onAllNodesWithText("Apache License", substring = true).fetchSemanticsNodes().isNotEmpty() }
        screenshot("project-license")
        ui.onNodeWithContentDescription("返回").performClick()
        click("第三方依赖许可证")
        ui.waitUntil(5_000) {
            ui.onAllNodesWithText("androidx.activity:activity", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        ui.onNodeWithText("感谢这些开源项目").assertDoesNotExist()
        screenshot("third-party-licenses")
        val library = ui.onAllNodes(hasClickAction()).filter(!hasContentDescription("返回")).onFirst()
        library.performClick()
        ui.onNodeWithText("查看许可原文").assertExists()
        click("关闭")
        ui.onNodeWithContentDescription("返回").performClick()
        ui.onNodeWithText("许可证").assertExists()
        ui.onNodeWithContentDescription("返回").performClick()
        ui.onNodeWithText("构建时间").assertExists()
    }
    private fun appData() =
        (androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as OfflineTestApplication).appData

    @Test fun clearAllDataDelegatesOnlyAfterConfirmation() {
        val before = appData().clearRequests
        open(); click("我的"); click("关于 Hydros")
        ui.onNodeWithText("存储占用情况").assertExists()
        ui.onNodeWithText("当前占用 2.5 MB").assertExists()
        click("清除所有数据"); waitFor("清除所有数据？")
        screenshot("clear-data-sheet")
        assertEquals(before, appData().clearRequests)
        click("取消")
        assertEquals(before, appData().clearRequests)
        click("清除所有数据"); click("清除")
        assertEquals(before + 1, appData().clearRequests)
    }

    @Test fun unfinishedSessionBlocksAllDataReset() {
        val before = appData().clearRequests
        val device = DeviceUi("AABBCCDDEEFF", "测试用水表", "测试宿舍", DeviceCategory.SHOWER, channel = ControlChannel.CLOUD)
        open(session = SessionUi(device, credentials.key, stage = SessionStage.OPEN_UNCONFIRMED))
        click("返回首页"); click("我的"); click("关于 Hydros")
        click("清除所有数据"); click("清除")
        waitFor("请先结束当前设备会话，再清除所有数据。")
        assertEquals(before, appData().clearRequests)
        assertNotNull(store.record.session)
        assertNotNull(store.auth)
    }
    @Test fun updateBadgeRequiresNewerReleaseAndSurvivesFailedRefresh() {
        var failRefresh = false
        open(updateSource = dev.uednd.hydros.data.update.AppUpdateSource {
            if (failRefresh) throw java.io.IOException("offline")
            dev.uednd.hydros.data.update.AppRelease("0.2.0", "v0.2.0", "测试更新说明")
        })
        click("我的"); ui.onNodeWithText("有新版本！").assertExists()
        click("关于 Hydros"); ui.onNodeWithText("有新版本！").assertExists()
        click("当前版本"); waitFor("前往更新")
        ui.onNodeWithText("测试更新说明").assertExists()
        failRefresh = true
        click("重新检查"); waitFor("检查失败，请检查网络后重试")
        ui.onNodeWithText("前往更新").assertExists()
    }
    @Test fun loginDoesNotAcceptLocallyValidButIncorrectPassword() {
        open(false)
        ui.onAllNodes(hasSetTextAction()).onFirst().performTextInput("13800138000")
        ui.onAllNodes(hasSetTextAction()).onLast().performTextInput("wrong123")
        click("登录"); waitFor("账号或密码错误")
        ui.onNodeWithText("欢迎回到 Hydros").assertExists()
        assertNull(store.auth)
        ui.onAllNodes(hasSetTextAction()).onLast().performTextReplacement("server123")
        ui.waitUntil(5_000) {
            ui.onAllNodes(hasText("登录") and hasClickAction() and isEnabled()).fetchSemanticsNodes().isNotEmpty()
        }
        click("登录"); waitFor("扫码连接")
        assertNotNull(store.auth)
    }
    @Test fun smsUsesServerResultAndOnlyThenStartsCooldown() {
        open(false); click("验证码登录")
        ui.onAllNodes(hasSetTextAction()).onFirst().performTextInput("13800138000")
        click("获取验证码")
        ui.waitUntil(5_000) { transport.smsTypes.contains("3") }
        ui.onAllNodes(hasSetTextAction()).onLast().performTextInput("654321")
        click("登录"); waitFor("扫码连接")
        assertEquals("654321",transport.lastSms)
    }
    @Test fun restorationShowsUnconfirmedSessionAndBlocksLogout() {
        val device=DeviceUi("AABBCCDDEEFF","测试用水表","测试宿舍",DeviceCategory.SHOWER,channel=ControlChannel.CLOUD)
        open(session=SessionUi(device,credentials.key,stage=SessionStage.OPEN_UNCONFIRMED,message="开启结果待确认"))
        waitFor("开启待确认")
        click("返回首页"); click("我的")
        click("管理账户"); click("退出账户")
        waitFor("请先结束当前会话，再退出账户。")
        ui.onNodeWithText("退出",substring=false).assertDoesNotExist()
        assertNotNull(store.auth)
    }
    @Test fun expirationPreservesDeviceSessionAndSameAccountLoginReturnsToIt() {
        val device = DeviceUi("AABBCCDDEEFF", "测试用水表", "测试宿舍", DeviceCategory.SHOWER, channel = ControlChannel.CLOUD)
        open(session = SessionUi(device, credentials.key, stage = SessionStage.USING, orderNo = "own"))
        ui.mainClock.autoAdvance = false
        ui.runOnIdle { runBlocking { (model.graph.auth as RealAuthRepository).expire(credentials) } }
        ui.mainClock.advanceTimeBy(500)
        ui.onNodeWithText("登录已失效，请重新登录").assertIsDisplayed()
        ui.onNodeWithText("正在洗浴").assertExists()
        assertNotNull(store.record.session)
        assertNull(store.auth)
        ui.mainClock.advanceTimeBy(4_500)
        ui.mainClock.autoAdvance = true
        waitFor("欢迎回到 Hydros")
        ui.onNodeWithText("返回当前设备，关闭或恢复使用").assertExists()
        ui.onNodeWithContentDescription("手机号").performTextInput(credentials.phone)
        ui.onNodeWithContentDescription("密码").performTextInput("server123")
        ui.onNode(hasText("登录") and hasClickAction()).performClick()
        waitFor("正在洗浴")
        assertEquals("own", store.record.session?.orderNo)
        ui.onNodeWithText("欢迎回到 Hydros").assertDoesNotExist()
    }

    @Test fun manageAccountOwnsActionsAndReturnsToMine() {
        open(); click("我的")
        listOf("更换手机号", "修改密码", "退出账户").forEach {
            ui.onNodeWithText(it).assertDoesNotExist()
        }
        ui.onNodeWithText("个性化").assertIsDisplayed()
        ui.onNodeWithText("应用与支持").assertIsDisplayed()
        ui.onNodeWithText("应用设置").assertDoesNotExist()
        screenshot("mine-manage-account")
        click("管理账户")
        waitFor("账户安全")
        ui.onNodeWithText("设备").assertDoesNotExist()
        screenshot("manage-account")
        click("更换手机号")
        ui.onNodeWithContentDescription("新手机号").assertIsDisplayed()
        ui.onNodeWithContentDescription("返回").performClick()
        waitFor("账户安全")
        click("修改密码")
        ui.onNodeWithContentDescription("当前密码").assertIsDisplayed()
        ui.onNodeWithContentDescription("返回").performClick()
        waitFor("账户安全")
        click("退出账户")
        ui.onNodeWithText("退出账户？").assertIsDisplayed()
        screenshot("logout-sheet")
        click("取消")
        assertNotNull(store.auth)
        ui.onNodeWithContentDescription("返回").performClick()
        waitFor("学校")
        ui.onNodeWithText("管理账户").assertIsDisplayed()
    }

    @Test fun accountFormCarriesOldPasswordAndDoesNotFakeSuccess() {
        open(); click("我的"); click("管理账户"); click("修改密码")
        val fields=ui.onAllNodes(hasSetTextAction())
        fields[0].performTextInput("server123")
        fields[1].performTextInput("newpass123")
        fields[2].performTextInput("newpass123")
        click("保存修改")
        ui.waitUntil(5_000) { transport.passwordFields!=null }
        assertEquals(passwordHash("server123"),transport.passwordFields?.get("oldPassword"))
        assertEquals(passwordHash("newpass123"),transport.passwordFields?.get("password"))
        waitFor("账户安全")
    }
    @Test fun appearanceUsesSecondaryPageAndPredictiveBackPreservesSelection() {
        open(); click("我的")
        screenshot("mine-single-line")
        listOf("验证码发送至新号码", "使用当前密码验证", "使用当前手机号验证码验证",
            "主题色彩与深浅模式", "0.1.0 · 校园生活客户端").forEach {
            ui.onNodeWithText(it).assertDoesNotExist()
        }
        click("应用外观")
        waitFor("主题外观")
        ui.onNodeWithText("钱包", substring=false).assertDoesNotExist()
        ui.onNodeWithText("跟随系统").assertIsSelected()
        listOf("暗色主题" to "DARK", "亮色主题" to "LIGHT", "跟随系统" to "SYSTEM", "暗色主题" to "DARK").forEach { (label, mode) ->
            click(label)
            ui.waitUntil(5_000) { store.mode == mode }
            ui.onNodeWithText(label).assertIsSelected()
            ui.onNodeWithText("主题外观").assertExists()
            screenshot("appearance-${mode.lowercase()}")
        }
        // Drive the same progress/cancel/commit callbacks used by a system back gesture.
        val instrumentation=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        fun startAndProgress() = instrumentation.runOnMainSync {
            host.onBackPressedDispatcher.dispatchOnBackStarted(androidx.activity.BackEventCompat(0f, 300f, 0f, androidx.activity.BackEventCompat.EDGE_LEFT))
            host.onBackPressedDispatcher.dispatchOnBackProgressed(androidx.activity.BackEventCompat(150f, 300f, 0.5f, androidx.activity.BackEventCompat.EDGE_LEFT))
        }
        startAndProgress()
        ui.waitForIdle()
        screenshot("appearance-predictive-back")
        instrumentation.runOnMainSync { host.onBackPressedDispatcher.dispatchOnBackCancelled() }
        ui.waitForIdle()
        ui.onNodeWithText("主题外观").assertExists()
        ui.onNodeWithText("暗色主题").assertIsSelected()
        startAndProgress()
        instrumentation.runOnMainSync { host.onBackPressedDispatcher.onBackPressed() }
        waitFor("学校")
        ui.onNodeWithText("主题外观").assertDoesNotExist()
        click("应用外观")
        ui.onNodeWithText("暗色主题").assertIsSelected()
        ui.onNodeWithContentDescription("返回").performClick()
        waitFor("学校")
        assertEquals("DARK",store.mode)
        click("关于 Hydros"); waitFor("构建时间")
        screenshot("about-dark")
    }
    private class UiStore : HydrosStore {
        var auth: Credentials?=null; var record=SavedAccount(); var mode="SYSTEM"; var onboarding=true
        override suspend fun credentials()=auth
        override suspend fun credentials(value: Credentials?) { auth=value }
        override suspend fun account(key: String)=record
        override suspend fun updateAccount(key: String, transform: (SavedAccount)->SavedAccount)=transform(record).also { record=it }
        override suspend fun theme()=mode
        override suspend fun theme(value: String) { mode=value }
        private var fontScale = 1f
        override suspend fun fontSizeScale() = fontScale
        override suspend fun fontSizeScale(value: Float) { fontScale = value }
        private var feedbackSettings = FeedbackSettings()
        override suspend fun feedback() = feedbackSettings
        override suspend fun updateFeedback(transform: (FeedbackSettings) -> FeedbackSettings): FeedbackSettings =
            transform(feedbackSettings).also { feedbackSettings = it }
        override suspend fun onboardingCompleted() = onboarding
        override suspend fun onboardingCompleted(value: Boolean) { onboarding = value }
    }
    private class UiBle : BlePort {
        override val ready=false
        override suspend fun connect(mac: String)=BleConnectResult.NoService
        override suspend fun query(): DeviceQueryState?=null
        override suspend fun writeRate(bytes: ByteArray)=false
        override suspend fun closeValve()=false
        override suspend fun collect(): ByteArray?=null
        override suspend fun writeBack(bytes: ByteArray)=false
        override fun disconnect()=Unit
    }
    private class UiTransport : CampusTransport {
        var deviceInfo: String? = null
        var cloudOpenResult: CompletableDeferred<ApiResponse<JsonElement>>? = null
        @Volatile var cloudOpenEntered = false
        val months=mutableListOf<String>(); val smsTypes=mutableListOf<String>()
        var withBills = false
        var lastSms: String?=null; var passwordFields: Map<String,String>?=null
        private fun data(text: String)=ApiResponse(success=true,errorCode=0,data=Json.parseToJsonElement(text))
        override suspend fun get(path: String, fields: Map<String,String>, auth: Credentials?): ApiResponse<JsonElement> {
            delay(20)
            return when(path) {
                "device/info/mac" -> data(deviceInfo ?: "null")
                "account/info" -> data("""{"name":"测试学生","idCardNumber":"1234","cardStatus":-1}""")
                "project/info/triple" -> data("""{"projectName":"测试学校"}""")
                "account/wallet" -> data("""{"money":"12.34","accountRealMoney":"10.00","accountGivenMoney":"2.34"}""")
                "order/query/account/bill/list" -> {
                    val month = fields.getValue("month")
                    months += month
                    data(if (!withBills) "[]" else (1..20).joinToString(prefix = "[", postfix = "]") { index ->
                        """{"consumeBillDTO":{"orderId":"$month-$index","consumeDate":"$month-15 12:30:00","description":"消费:测试账单 $index","consumeMoney":"0.50"}}"""
                    })
                }
                "order/query/account/bill/detail" -> data("""{"consumeMoney":"0.50","usingTimeDisplay":"3分20秒"}""")
                "user/verification/code/get" -> { smsTypes+=fields.getValue("typeId"); data("null") }
                else -> data("null")
            }
        }
        override suspend fun post(path: String, fields: Map<String,String>, auth: Credentials?): ApiResponse<JsonElement> {
            delay(20)
            if (path == "order/tcpDevice/downRate/rateOrder" && cloudOpenResult != null) {
                cloudOpenEntered = true
                return cloudOpenResult!!.await()
            }
            if (path == "order/downRate/bluetooth/rateOrder" && cloudOpenResult != null)
                return data("""{"downData":"0011","consumeDate":"20261016120000","orderNo":"ble-order"}""")
            if(path=="user/password/update") { passwordFields=fields; return data("null") }
            if(path=="user/login" && fields["password"]!=passwordHash("server123")) return ApiResponse(success=false,errorCode=12,errorMessage="账号或密码错误")
            if(path=="user/registerAndLogin") lastSms=fields["smsCode"]
            return if(path.startsWith("user/")) data("""{"loginCode":"test-only","userId":1,"userAccount":{"accountId":2,"projectId":3}}""") else data("null")
        }
    }
}
