package org.cqutosp.hydros.ui.state

import org.cqutosp.hydros.data.feedback.*
import org.cqutosp.hydros.data.storage.FeedbackSettings
import org.cqutosp.hydros.data.storage.PostSessionDestination
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import org.cqutosp.hydros.data.HydrosDependencies
import org.cqutosp.hydros.data.network.*
import org.cqutosp.hydros.data.repository.ReauthenticationNotice
import org.cqutosp.hydros.data.repository.BillHistory
import org.cqutosp.hydros.data.repository.DevicePreparation
import org.cqutosp.hydros.data.repository.NearbyDiscovery
import org.cqutosp.hydros.data.repository.NearbyDiscoveryState
import org.cqutosp.hydros.ui.theme.ThemeMode
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.time.YearMonth

data class HydrosUiState(val initializing: Boolean = true, val signedIn: Boolean = false,
    val onboardingCompleted: Boolean = false, val reauthentication: ReauthenticationNotice? = null,
    val account: AccountUi = AccountUi(), val wallet: WalletUi = WalletUi(), val devices: List<DeviceUi> = emptyList(),
    val connectedDeviceIds: List<String> = emptyList(), val favoriteDeviceIds: Set<String> = emptySet(),
    val deviceRemarks: Map<String, String> = emptyMap(),
    val nearby: NearbyDiscoveryState = NearbyDiscoveryState(),
    val observation: SessionObservation = SessionObservation(),
    val bills: List<BillUi> = emptyList(), val session: SessionUi? = null, val connectingId: String? = null,
    val connectionStage: ConnectionStage = ConnectionStage.IDLE, val theme: ThemeMode = ThemeMode.SYSTEM,
    val feedback: FeedbackSettings = FeedbackSettings(),
    val fontSizeScale: Float = 1f,
    val month: String = YearMonth.now().toString(), val billType: BillType = BillType.CONSUMPTION,
    val loadingBills: Boolean = false,
    val messageSeverity: FeedbackSeverity = FeedbackSeverity.ERROR, val messageSource: String = "user",
    val messageAction: RecoveryAction? = null, val connectionAction: RecoveryAction? = null,
    val networkMessage: String? = null, val connectionMessage: String? = null,
    val walletLoading: Boolean = false, val walletLoaded: Boolean = false, val accountError: String? = null,
    val deviceActionError: String? = null,
    val billDetail: BillUi? = null, val billDetailSync: Map<String, BillDetailUi> = emptyMap(),
    val walletError: String? = null, val billsError: String? = null, val message: String? = null, val working: Boolean = false,
    val postSessionDestination: PostSessionDestination = PostSessionDestination.DEVICE,
    val resolvingPostSessionDestination: Boolean = false, val postSessionBillId: String? = null)
class HydrosViewModel(
    val graph: HydrosDependencies,
) : ViewModel() {
    private var postSessionNavigationDismissed = false
    private var lastActionError: Exception? = null
    private val mutable = MutableStateFlow(HydrosUiState())
    val state = mutable.asStateFlow()
    private val refreshJobs = mutableListOf<Job>()
    private val billHistory = BillHistory(graph.wallet, graph.auth.credentials, viewModelScope)
    private var walletGeneration = 0
    private val discovery = NearbyDiscovery(graph.nearbyScanner, graph.devices, graph.auth.credentials, viewModelScope)
    private val preparation = DevicePreparation(
        graph.auth, graph.devices, graph.sessions, graph.store, viewModelScope,
        ensureWatch = graph.ensureWatch,
    )
    init {
        viewModelScope.launch {
            graph.sessions.observation.collect { value -> mutable.update { it.copy(observation = value) } }
        }
        viewModelScope.launch {
            billHistory.state.collect { history ->
                mutable.update { it.copy(month = history.month, billType = history.type,
                    bills = history.bills, loadingBills = history.loading,
                    billsError = history.error?.let(::friendly),
                    billDetail = history.detailBill,
                    billDetailSync = history.details.mapValues { (_, detail) ->
                        BillDetailUi(detail.fetching, detail.error?.let { failureFeedback(it, FailureOperation.DETAIL).text })
                    }) }
            }
        }
        graph.networkRecovery?.let { recovery ->
            viewModelScope.launch {
                recovery.cellularRequests.collect { count ->
                    mutable.update { it.copy(networkMessage = if (count > 0) "正在尝试移动网络…" else null) }
                }
            }
        }
        viewModelScope.launch {
            var previous = emptyList<DeviceUi>()
            discovery.state.collect { nearby ->
                val resolved = nearby.devices.filter { it !in previous }
                previous = nearby.devices
                mutable.update { current -> current.copy(
                    nearby = nearby,
                    devices = current.devices.filterNot { old -> resolved.any { it.id == old.id } } + resolved,
                    message = current.message,
                ) }
            }
        }
        viewModelScope.launch {
            try {
                val theme = runCatching { ThemeMode.valueOf(graph.store.theme()) }.getOrDefault(ThemeMode.SYSTEM)
                val onboarding = graph.store.onboardingCompleted()
                val feedback = graph.store.feedback()
                val fontSizeScale = graph.store.fontSizeScale()
                val postSessionDestination = runCatching { graph.store.postSessionDestination() }.getOrDefault(PostSessionDestination.DEVICE)
                mutable.update { it.copy(theme=theme, onboardingCompleted=onboarding, feedback=feedback,
                    fontSizeScale=fontSizeScale, postSessionDestination=postSessionDestination) }
                graph.restore()
                graph.auth.authState.collectLatest { authentication ->
                    val auth = authentication.credentials
                    stopDiscovery()
                    refreshJobs.forEach { it.cancel() }; refreshJobs.clear()
                    preparation.cancel()
                    walletGeneration++
                    if (authentication.reauthentication != null) {
                        mutable.update { it.copy(signedIn = false, reauthentication = authentication.reauthentication,
                            initializing = false, loadingBills = false,
                            walletError = null, billsError = null, message = null) }
                        return@collectLatest
                    }
                    graph.sessions.restore(auth)
                    val saved = auth?.let { graph.store.account(it.key) }
                    mutable.update { current -> HydrosUiState(initializing=false, signedIn=auth != null,
                        onboardingCompleted=current.onboardingCompleted, reauthentication=authentication.reauthentication,
                        theme=current.theme, feedback=current.feedback, fontSizeScale=current.fontSizeScale,
                        postSessionDestination=current.postSessionDestination,
                        account=AccountUi(phone=auth?.phone ?: ""), devices=saved?.devices ?: emptyList(),
                        connectedDeviceIds=saved?.recent ?: emptyList(), favoriteDeviceIds=saved?.favorites ?: emptySet(),
                        deviceRemarks=saved?.deviceRemarks ?: emptyMap(), session=graph.sessions.session.value) }
                    if(auth != null) { refresh(); if(graph.sessions.session.value != null) graph.ensureWatch() }
                }
            } catch(e: CancellationException) { throw e } catch(e: Exception) { mutable.update { it.copy(initializing=false, message=failureFeedback(e, FailureOperation.RESTORE, FailureStage.STORAGE).text) } }
        }
        viewModelScope.launch {
            var prior: SessionUi? = null
            graph.sessions.session.collect { value ->
                val auth = graph.auth.credentials.value
                val finished = when {
                    value != null || auth == null -> null
                    else -> prior?.takeIf { it.ownerKey == auth.key }
                        ?: mutable.value.session?.takeIf { it.ownerKey == auth.key }
                }
                if(value == null || auth?.key == value.ownerKey || auth == null) mutable.update { current -> current.copy(
                    session=value,
                    resolvingPostSessionDestination = if (value != null) false else current.resolvingPostSessionDestination,
                    postSessionBillId = if (value == null) current.postSessionBillId else null) }
                // Refresh first: the bill lookup replaces the list selection it may still be loading.
                if(prior != null && value == null && auth != null) refresh()
                if (finished != null && graph.store.postSessionDestination() == PostSessionDestination.BILL) {
                    graph.scope.launch {
                        postSessionNavigationDismissed = false
                        mutable.update { it.copy(resolvingPostSessionDestination = true) }
                        resolveFinishedBill(finished)
                    }
                }
                prior = value
            }
        }
        viewModelScope.launch {
            preparation.state.collect { connection ->
                mutable.update { current ->
                    val ready = connection.device?.takeIf { connection.stage == ConnectionStage.READY }
                    val devices = if (ready == null) current.devices else {
                        current.devices.filterNot { it.id == ready.id } + ready
                    }
                    current.copy(
                        connectingId = connection.device?.id,
                        connectionStage = connection.stage,
                        connectionMessage = connection.error?.let { failureFeedback(it, FailureOperation.DEVICE).text } ?: connection.message,
                        connectionAction = connection.error?.let { failureFeedback(it, FailureOperation.DEVICE).action },
                        devices = devices,
                        connectedDeviceIds = if (ready == null) current.connectedDeviceIds else connection.recentDeviceIds,
                        message = current.message,
                    )
                }
            }
        }
    }
    private fun friendly(error: Exception) = failureFeedback(error, FailureOperation.BILLS).text
    private fun resolveFinishedBill(session: SessionUi) {
        viewModelScope.launch {
            // Wrap the result: withTimeoutOrNull returns null on timeout, while a completed lookup
            // legitimately returns null when no bill matches.
            val located = try {
                withTimeoutOrNull(8_000) { LocatedBill(billHistory.locateSessionBill(session)) }
            } catch (e: CancellationException) { throw e } catch (_: Exception) { LocatedBill(null) }
            val bill = located?.bill
            if (graph.auth.credentials.value?.key != session.ownerKey) return@launch
            if (postSessionNavigationDismissed) {
                mutable.update { it.copy(resolvingPostSessionDestination = false) }
                return@launch
            }
            // Publish the located month together with the bill id so the detail page can render it at once.
            val history = billHistory.state.value
            mutable.update { it.copy(month = history.month, billType = history.type,
                bills = history.bills, loadingBills = history.loading, billsError = history.error?.let(::friendly),
                resolvingPostSessionDestination = false, postSessionBillId = bill?.id) }
        }
    }
    suspend fun acknowledgeReauthentication(id: Long) {
        graph.auth.acknowledgeReauthentication(id)
        // Restoring the device can wait on an ongoing close operation. Consume the UI notice now.
        mutable.update { if (it.reauthentication?.id == id) it.copy(reauthentication = null) else it }
    }
    fun dismissMessage() { mutable.update { it.copy(message=null, messageSeverity=FeedbackSeverity.ERROR, messageSource="user", messageAction=null) } }
    fun clearAllData(clear: () -> Boolean): Boolean {
        if (state.value.session != null || graph.sessions.session.value != null) {
            mutable.update { it.copy(message = "请先结束当前设备会话，再清除所有数据。") }
            return false
        }
        if (state.value.working) {
            mutable.update { it.copy(message = "请等待当前操作完成后重试。") }
            return false
        }
        cancelConnection()
        stopDiscovery()
        val requested = runCatching(clear).getOrDefault(false)
        if (!requested) mutable.update { it.copy(message = "清除失败，请重试") }
        return requested
    }
    suspend fun action(allowRecovery: Boolean = false, operation: FailureOperation = FailureOperation.SETTINGS, block: suspend () -> Unit): Boolean {
        if(mutable.value.working || (!allowRecovery && state.value.reauthentication != null)) {
            // No request was attempted; callers must not report the previous action's failure.
            lastActionError = null
            return false
        }
        val owner = graph.auth.credentials.value
        lastActionError = null
        mutable.update { it.copy(working=true, message=null,
            deviceActionError=if (operation == FailureOperation.START) null else it.deviceActionError) }
        return try { block(); true } catch(e: CancellationException) { throw e }
        catch(e: Exception) {
            lastActionError = e
            if ((e !is ApiFailure || !e.expired) && state.value.reauthentication == null && graph.auth.credentials.value == owner) {
                val deviceAction = operation in listOf(FailureOperation.START, FailureOperation.FINISH)
                if (operation == FailureOperation.FINISH) graph.sessions.reportActionFailure(e)
                val feedback = if (deviceAction) graph.sessions.session.value?.let(::sessionFeedback) ?: failureFeedback(e, operation)
                    else failureFeedback(e, operation)
                mutable.update { it.copy(message=feedback.text, messageSeverity=feedback.severity,
                    messageSource=if (deviceAction) "device" else "user", messageAction=feedback.action,
                    deviceActionError=if (operation == FailureOperation.START && graph.sessions.session.value == null) feedback.text else it.deviceActionError) }
            }
            false
        }
        finally { mutable.update { it.copy(working=false) } }
    }
    suspend fun login(phone: String, secret: String, sms: Boolean): Boolean =
        action(operation = FailureOperation.LOGIN) { graph.auth.login(phone, secret, sms) }
    suspend fun sendCode(phone: String, type: Int) = action(operation = FailureOperation.CODE) { graph.auth.sendCode(phone, type) }
    suspend fun saveAccount(form: org.cqutosp.hydros.ui.screens.AccountForm, phone: String, old: String, password: String, code: String) = action(operation = FailureOperation.ACCOUNT) {
        if(state.value.session != null) throw ApiFailure(null, "请先结束当前设备使用，再修改账户")
        when(form) {
            org.cqutosp.hydros.ui.screens.AccountForm.PHONE -> graph.auth.savePhone(phone, code)
            org.cqutosp.hydros.ui.screens.AccountForm.PASSWORD -> {
                if (old.isNotEmpty()) graph.auth.savePassword(old, password, null)
                else graph.auth.savePassword(null, password, code)
            }
        }
    }
    fun theme(mode: ThemeMode) { graph.scope.launch {
        action { graph.store.theme(mode.name); mutable.update { it.copy(theme=mode) } }
    } }
    fun postSessionDestination(destination: PostSessionDestination) { graph.scope.launch {
        action {
            graph.store.postSessionDestination(destination)
            mutable.update { it.copy(postSessionDestination = destination) }
        }
    } }
    fun consumePostSessionBill() { mutable.update { it.copy(postSessionBillId = null) } }
    fun dismissPostSessionNavigation() {
        postSessionNavigationDismissed = true
        mutable.update { it.copy(resolvingPostSessionDestination = false, postSessionBillId = null) }
    }
    fun hapticFeedback(enabled: Boolean) {
        updateFeedback { it.copy(hapticFeedbackEnabled = enabled) }
    }
    suspend fun fontSizeScale(value: Float): Boolean = action {
        graph.store.fontSizeScale(value)
        mutable.update { it.copy(fontSizeScale = value) }
    }
    fun reduceMotion(enabled: Boolean) {
        updateFeedback { it.copy(reduceMotionEnabled = enabled) }
    }
    private fun updateFeedback(transform: (FeedbackSettings) -> FeedbackSettings) {
        graph.scope.launch { action {
            val feedback = graph.store.updateFeedback(transform)
            mutable.update { it.copy(feedback = feedback) }
        } }
    }
    fun completeOnboarding() { graph.scope.launch { action {
        graph.store.onboardingCompleted(true)
        mutable.update { it.copy(onboardingCompleted = true) }
    } } }
    fun logout() {
        graph.scope.launch {
            action {
                if(graph.sessions.session.value != null) throw ApiFailure(null, "请先结束或恢复当前设备会话")
                cancelConnection(); graph.auth.logout()
            }
        }
    }
    fun toggleFavorite(deviceId: String) {
        graph.scope.launch {
            action(operation = FailureOperation.FAVORITE) {
                val auth = graph.auth.requireCredentials()
                val saved = graph.store.updateAccount(auth.key) {
                    when {
                        deviceId in it.favorites -> it.copy(favorites = it.favorites - deviceId, deviceRemarks = it.deviceRemarks - deviceId)
                        deviceId in it.recent -> it.copy(favorites = it.favorites + deviceId)
                        else -> it
                    }
                }
                if(graph.auth.credentials.value == auth) mutable.update { it.copy(favoriteDeviceIds=saved.favorites, deviceRemarks=saved.deviceRemarks) }
            }
        }
    }
    suspend fun saveDeviceRemark(deviceId: String, value: String): Boolean {
        val ok = action(operation = FailureOperation.REMARK) {
            val auth = graph.auth.requireCredentials()
            if (state.value.devices.none { it.id == deviceId }) throw ApiFailure(null, "设备不存在，请重新扫描")
            if (value.length > DEVICE_REMARK_MAX_LENGTH) throw ApiFailure(null, "设备备注最多 $DEVICE_REMARK_MAX_LENGTH 字")
            val remark = value.trim()
            val saved = graph.store.updateAccount(auth.key) {
                if (deviceId !in it.favorites) throw ApiFailure(null, "请先收藏设备，再设置备注")
                it.copy(deviceRemarks = if (remark.isEmpty()) it.deviceRemarks - deviceId else it.deviceRemarks + (deviceId to remark))
            }
            if (graph.auth.credentials.value != auth) throw ApiFailure(null, "账号已切换，请重试")
            mutable.update { it.copy(deviceRemarks = saved.deviceRemarks) }
        }
        return ok
    }
    suspend fun scan(mac: String, rssi: Int? = null): String? {
        val auth = graph.auth.credentials.value ?: return null
        val device = graph.devices.find(auth, mac, if(rssi != null) mac else null, rssi)
        if(graph.auth.credentials.value != auth) return null
        mutable.update { it.copy(devices=it.devices.filterNot { d -> d.id == device.id } + device) }
        return device.id
    }
    fun discoverNearby() = discovery.start()
    fun stopDiscovery() = discovery.stop()
    fun connect(device: DeviceUi) {
        discovery.stop()
        if (state.value.deviceActionError != null) preparation.cancel()
        mutable.update { it.copy(deviceActionError=null) }
        preparation.prepare(device)
    }
    /** Resume the user's existing connection intent after returning from settings or a network interruption. */
    fun recoverConnection(deviceId: String) {
        if (!state.value.signedIn || state.value.reauthentication != null || state.value.session != null || state.value.working) return
        val pending = preparation.state.value
        val device = pending.device ?: return
        if (pending.stage != ConnectionStage.FAILED || device.id != deviceId) return
        val error = pending.error ?: return
        if (generateSequence<Throwable>(error) { it.cause }.any { it is javax.net.ssl.SSLException || it is java.net.ProtocolException }) return
        val failure = classifyFailure(error, FailureOperation.DEVICE, FailureStage.NETWORK)
        if (failure.reason !in setOf(FailureReason.NETWORK, FailureReason.TIMEOUT, FailureReason.DISCONNECTED,
                FailureReason.BLUETOOTH_OFF, FailureReason.PERMISSION)) return
        connect(device)
    }
    fun cancelConnection() {
        preparation.cancel()
    }
    fun start(device: DeviceUi) {
        if (state.value.working || state.value.session != null) return
        graph.scope.launch {
            action(operation = FailureOperation.START) {
                preparation.startSession(device.id)
            }
        }
    }
    fun finish() { if(!state.value.working) graph.scope.launch {
        if (graph.sessions.session.value == null) return@launch
        action(allowRecovery = true, operation = FailureOperation.FINISH) { graph.ensureWatch(); graph.sessions.finish(graph.auth.credentials.value) }
    } }
    /** A foreground return may complete an interrupted read, but never replays a user command. */
    fun recoverQueries(visibleBillId: String? = null) {
        val current = state.value
        if (!current.signedIn || current.reauthentication != null || current.working) return
        if ((current.accountError != null || current.walletError != null || current.billsError != null) &&
            !current.walletLoading && !current.loadingBills && refreshJobs.none { it.isActive }) refresh()
        val detail = visibleBillId?.let { current.billDetailSync[it] }
        if (visibleBillId != null && detail?.error != null && detail.fetching != true)
            loadDetail(visibleBillId, force = true)
    }
    fun refresh() {
        val auth = graph.auth.credentials.value ?: return
        refreshJobs.removeAll { it.isCompleted }
        val generation=++walletGeneration
        refreshJobs += viewModelScope.launch {
            try {
                val account=graph.account.load(auth)
                if(graph.auth.credentials.value==auth && generation==walletGeneration) mutable.update { it.copy(account=account, accountError=null) }
            } catch(e: CancellationException) { throw e } catch(e: Exception) {
                if(graph.auth.credentials.value==auth && generation==walletGeneration) mutable.update { it.copy(accountError=failureFeedback(e, FailureOperation.PROFILE).text) }
            }
        }
        refreshJobs += viewModelScope.launch {
            mutable.update { it.copy(walletError=null, walletLoading=true) }
            try {
                val wallet=graph.wallet.load(auth)
                if(graph.auth.credentials.value==auth && generation==walletGeneration) mutable.update { it.copy(wallet=wallet, walletLoaded=true, walletLoading=false, walletError=null) }
            } catch(e: CancellationException) { throw e } catch(e: Exception) {
                if(graph.auth.credentials.value==auth && generation==walletGeneration) mutable.update { it.copy(walletError=failureFeedback(e, FailureOperation.WALLET).text, walletLoading=false) }
            }
        }
        billHistory.refresh()
    }
    fun loadMonth(month: String) = billHistory.selectMonth(month)
    fun selectBillType(type: BillType) = billHistory.selectType(type)

    fun loadDetail(id: String, force: Boolean = false) {
        viewModelScope.launch { billHistory.loadDetail(id, force = force) }
    }
}
/** Distinguishes a timed-out lookup from a completed lookup that found no matching bill. */
private class LocatedBill(val bill: BillUi?)
fun phoneError(value: String): String? = if(Regex("1[3-9]\\d{9}").matches(value)) null else "请输入 11 位手机号"
fun passwordError(value: String): String? = if(value.length in 6..32 && value.any { !it.isWhitespace() }) null else "请输入 6–32 位密码"
fun codeError(value: String): String? = if(Regex("\\d{6}").matches(value)) null else "请输入 6 位验证码"
