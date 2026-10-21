package dev.uednd.hydros.data.analytics

import android.os.Bundle
import com.google.firebase.analytics.FirebaseAnalytics
import dev.uednd.hydros.data.storage.FontSizeOption
import dev.uednd.hydros.data.storage.PostSessionDestination
import dev.uednd.hydros.ui.state.SessionRecoveryReason
import dev.uednd.hydros.ui.state.SessionStage

enum class ResultCode { SUCCESS, PENDING, FAILURE }
enum class FailureCategory { NETWORK, REJECTED, PERMISSION, UNKNOWN }
enum class Channel { BLE, CLOUD }
enum class DeviceType { SHOWER, DRINK, WASH }
enum class NetworkOperation { LOGIN, ACCOUNT, WALLET, BILL, DEVICE, SESSION, UPDATE }
enum class HttpClass { INFORMATIONAL, SUCCESS, REDIRECT, CLIENT_ERROR, SERVER_ERROR, UNKNOWN }
enum class RecoveryResult { NOT_NEEDED, RECOVERED, FAILED }
enum class LoginMethod { PASSWORD, SMS }
enum class AuthEndReason { LOGOUT, EXPIRED, ACCOUNT_CHANGED }
enum class ConnectEntry { LIST, NEARBY, QR }
enum class BillQueryScope { LIST, DETAIL }
enum class PostSessionNavResult { FOUND, NOT_FOUND, TIMEOUT, DISMISSED, SKIPPED }
enum class AppScreen { LOGIN, SCAN, DEVICE, SESSION, WALLET, BILL_DETAIL, ABOUT_UPDATE }
enum class SettingsKey { THEME, FONT_SCALE, REDUCE_MOTION, HAPTIC, POST_SESSION_DEST }
enum class UpdateCheckOutcome { UP_TO_DATE, UPDATE_AVAILABLE, FAILURE }
enum class DeviceListAction { FAVORITE, UNFAVORITE, REMARK }
enum class SessionRecoveryStage { OPEN_UNCONFIRMED, CLOSE_UNCONFIRMED, SETTLEMENT_PENDING, WRITEBACK_PENDING }

/** Events and parameters are intentionally closed over fixed enums and coarse buckets. */
interface AnalyticsTracker {
    fun setCollectionEnabled(enabled: Boolean)
    fun onboardingComplete()
    fun nearbyScanResult(result: ResultCode, discoveredCount: Int, failure: FailureCategory? = null)
    fun qrScanResult(result: ResultCode, failure: FailureCategory? = null)
    fun deviceConnectResult(
        result: ResultCode,
        channel: Channel,
        deviceType: DeviceType,
        entry: ConnectEntry = ConnectEntry.LIST,
        failure: FailureCategory? = null,
    )
    fun sessionStartResult(
        result: ResultCode,
        channel: Channel,
        deviceType: DeviceType,
        failure: FailureCategory? = null,
        recoveryReason: SessionRecoveryReason? = null,
    )
    fun sessionFinishResult(
        result: ResultCode,
        channel: Channel,
        deviceType: DeviceType,
        failure: FailureCategory? = null,
        recoveryReason: SessionRecoveryReason? = null,
    )
    fun networkFailure(operation: NetworkOperation, failure: FailureCategory, httpClass: HttpClass, recovery: RecoveryResult)
    fun loginResult(result: ResultCode, method: LoginMethod, failure: FailureCategory? = null)
    fun authSessionEnd(reason: AuthEndReason)
    fun analyticsConsentChange(enabled: Boolean)
    fun appScreen(screen: AppScreen)
    fun billQueryResult(scope: BillQueryScope, result: ResultCode, failure: FailureCategory? = null)
    fun postSessionNavResult(destination: PostSessionDestination, result: PostSessionNavResult)
    fun updateCheckResult(outcome: UpdateCheckOutcome, failure: FailureCategory? = null)
    fun updateInstallResult(result: ResultCode, failure: FailureCategory? = null)
    fun sessionRecoveryResult(
        stage: SessionRecoveryStage,
        result: ResultCode,
        recoveryReason: SessionRecoveryReason? = null,
        failure: FailureCategory? = null,
    )
    fun settingsChange(key: SettingsKey, valueBucket: String)
    fun dataClearResult(result: ResultCode)
    fun deviceListAction(action: DeviceListAction, result: ResultCode)
}

object NoOpAnalyticsTracker : AnalyticsTracker {
    override fun setCollectionEnabled(enabled: Boolean) = Unit
    override fun onboardingComplete() = Unit
    override fun nearbyScanResult(result: ResultCode, discoveredCount: Int, failure: FailureCategory?) = Unit
    override fun qrScanResult(result: ResultCode, failure: FailureCategory?) = Unit
    override fun deviceConnectResult(result: ResultCode, channel: Channel, deviceType: DeviceType, entry: ConnectEntry, failure: FailureCategory?) = Unit
    override fun sessionStartResult(result: ResultCode, channel: Channel, deviceType: DeviceType, failure: FailureCategory?, recoveryReason: SessionRecoveryReason?) = Unit
    override fun sessionFinishResult(result: ResultCode, channel: Channel, deviceType: DeviceType, failure: FailureCategory?, recoveryReason: SessionRecoveryReason?) = Unit
    override fun networkFailure(operation: NetworkOperation, failure: FailureCategory, httpClass: HttpClass, recovery: RecoveryResult) = Unit
    override fun loginResult(result: ResultCode, method: LoginMethod, failure: FailureCategory?) = Unit
    override fun authSessionEnd(reason: AuthEndReason) = Unit
    override fun analyticsConsentChange(enabled: Boolean) = Unit
    override fun appScreen(screen: AppScreen) = Unit
    override fun billQueryResult(scope: BillQueryScope, result: ResultCode, failure: FailureCategory?) = Unit
    override fun postSessionNavResult(destination: PostSessionDestination, result: PostSessionNavResult) = Unit
    override fun updateCheckResult(outcome: UpdateCheckOutcome, failure: FailureCategory?) = Unit
    override fun updateInstallResult(result: ResultCode, failure: FailureCategory?) = Unit
    override fun sessionRecoveryResult(stage: SessionRecoveryStage, result: ResultCode, recoveryReason: SessionRecoveryReason?, failure: FailureCategory?) = Unit
    override fun settingsChange(key: SettingsKey, valueBucket: String) = Unit
    override fun dataClearResult(result: ResultCode) = Unit
    override fun deviceListAction(action: DeviceListAction, result: ResultCode) = Unit
}

class FirebaseAnalyticsTracker(private val analytics: FirebaseAnalytics) : AnalyticsTracker {
    override fun setCollectionEnabled(enabled: Boolean) { analytics.setAnalyticsCollectionEnabled(enabled) }
    override fun onboardingComplete() = log("onboarding_complete")
    override fun nearbyScanResult(result: ResultCode, discoveredCount: Int, failure: FailureCategory?) =
        log("nearby_scan_result", "result" to result.name, "count_bucket" to countBucket(discoveredCount), "failure" to failure?.name)
    override fun qrScanResult(result: ResultCode, failure: FailureCategory?) =
        log("qr_scan_result", "result" to result.name, "failure" to failure?.name)
    override fun deviceConnectResult(result: ResultCode, channel: Channel, deviceType: DeviceType, entry: ConnectEntry, failure: FailureCategory?) =
        log("device_connect_result", "result" to result.name, "channel" to channel.name, "device_type" to deviceType.name,
            "entry" to entry.name, "failure" to failure?.name)
    override fun sessionStartResult(result: ResultCode, channel: Channel, deviceType: DeviceType, failure: FailureCategory?, recoveryReason: SessionRecoveryReason?) =
        log("session_start_result", "result" to result.name, "channel" to channel.name, "device_type" to deviceType.name,
            "failure" to failure?.name, "recovery_reason" to recoveryReason?.name)
    override fun sessionFinishResult(result: ResultCode, channel: Channel, deviceType: DeviceType, failure: FailureCategory?, recoveryReason: SessionRecoveryReason?) =
        log("session_finish_result", "result" to result.name, "channel" to channel.name, "device_type" to deviceType.name,
            "failure" to failure?.name, "recovery_reason" to recoveryReason?.name)
    override fun networkFailure(operation: NetworkOperation, failure: FailureCategory, httpClass: HttpClass, recovery: RecoveryResult) =
        log("network_diagnostic", "operation" to operation.name, "failure" to failure.name, "http_class" to httpClass.name, "recovery" to recovery.name)
    override fun loginResult(result: ResultCode, method: LoginMethod, failure: FailureCategory?) =
        log("login_result", "result" to result.name, "method" to method.name, "failure" to failure?.name)
    override fun authSessionEnd(reason: AuthEndReason) = log("auth_session_end", "reason" to reason.name)
    override fun analyticsConsentChange(enabled: Boolean) = log("analytics_consent_change", "enabled" to enabled.toString())
    override fun appScreen(screen: AppScreen) = log("app_screen", "screen" to screen.name.lowercase())
    override fun billQueryResult(scope: BillQueryScope, result: ResultCode, failure: FailureCategory?) =
        log("bill_query_result", "scope" to scope.name, "result" to result.name, "failure" to failure?.name)
    override fun postSessionNavResult(destination: PostSessionDestination, result: PostSessionNavResult) =
        log("post_session_nav_result", "destination" to destination.name, "result" to result.name)
    override fun updateCheckResult(outcome: UpdateCheckOutcome, failure: FailureCategory?) =
        log("update_check_result", "outcome" to outcome.name, "failure" to failure?.name)
    override fun updateInstallResult(result: ResultCode, failure: FailureCategory?) =
        log("update_install_result", "result" to result.name, "failure" to failure?.name)
    override fun sessionRecoveryResult(stage: SessionRecoveryStage, result: ResultCode, recoveryReason: SessionRecoveryReason?, failure: FailureCategory?) =
        log("session_recovery_result", "stage" to stage.name, "result" to result.name,
            "recovery_reason" to recoveryReason?.name, "failure" to failure?.name)
    override fun settingsChange(key: SettingsKey, valueBucket: String) =
        log("settings_change", "key" to key.name, "value_bucket" to valueBucket)
    override fun dataClearResult(result: ResultCode) = log("data_clear_result", "result" to result.name)
    override fun deviceListAction(action: DeviceListAction, result: ResultCode) =
        log("device_list_action", "action" to action.name, "result" to result.name)

    private fun log(name: String, vararg values: Pair<String, String?>) {
        val bundle = Bundle()
        values.forEach { (key, value) -> value?.let { bundle.putString(key, it) } }
        analytics.logEvent(name, bundle)
    }
}

fun failureCategory(error: Throwable): FailureCategory = when (error) {
    is dev.uednd.hydros.data.feedback.AppFailure -> when (error.reason) {
        dev.uednd.hydros.data.feedback.FailureReason.NETWORK, dev.uednd.hydros.data.feedback.FailureReason.TIMEOUT -> FailureCategory.NETWORK
        dev.uednd.hydros.data.feedback.FailureReason.PERMISSION -> FailureCategory.PERMISSION
        dev.uednd.hydros.data.feedback.FailureReason.REJECTED -> FailureCategory.REJECTED
        else -> FailureCategory.UNKNOWN
    }
    is java.io.IOException -> FailureCategory.NETWORK
    is SecurityException -> FailureCategory.PERMISSION
    is dev.uednd.hydros.data.network.ApiFailure -> if (error.code == null) FailureCategory.UNKNOWN else FailureCategory.REJECTED
    else -> FailureCategory.UNKNOWN
}

fun countBucket(count: Int): String = when (count) { 0 -> "0"; in 1..2 -> "1_2"; in 3..5 -> "3_5"; in 6..10 -> "6_10"; else -> "11_plus" }

fun fontScaleBucket(scale: Float): String {
    // The settings UI snaps to an option today, but comparing floats for equality would silently
    // fall back to OTHER if a future slider interpolates between them.
    val option = FontSizeOption.entries.minByOrNull { kotlin.math.abs(it.scale - scale) } ?: return "OTHER"
    return if (kotlin.math.abs(option.scale - scale) <= 0.01f) option.name else "OTHER"
}

fun SessionStage.toRecoveryStage(): SessionRecoveryStage? = when (this) {
    SessionStage.OPEN_UNCONFIRMED -> SessionRecoveryStage.OPEN_UNCONFIRMED
    SessionStage.CLOSE_UNCONFIRMED -> SessionRecoveryStage.CLOSE_UNCONFIRMED
    SessionStage.SETTLEMENT_PENDING -> SessionRecoveryStage.SETTLEMENT_PENDING
    SessionStage.WRITEBACK_PENDING -> SessionRecoveryStage.WRITEBACK_PENDING
    else -> null
}
