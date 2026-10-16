package dev.uednd.hydros.data.analytics

import android.os.Bundle
import com.google.firebase.analytics.FirebaseAnalytics

enum class ResultCode { SUCCESS, PENDING, FAILURE }
enum class FailureCategory { NETWORK, REJECTED, PERMISSION, DEVICE, INVALID_INPUT, UNKNOWN }
enum class Channel { BLE, CLOUD }
enum class DeviceType { SHOWER, DRINK, WASH, OTHER }
enum class NetworkOperation { LOGIN, ACCOUNT, WALLET, BILL, DEVICE, SESSION, UPDATE }
enum class HttpClass { INFORMATIONAL, SUCCESS, REDIRECT, CLIENT_ERROR, SERVER_ERROR, UNKNOWN }
enum class RecoveryResult { NOT_NEEDED, RECOVERED, FAILED }

/** Events and parameters are intentionally closed over fixed enums and coarse buckets. */
interface AnalyticsTracker {
    fun setCollectionEnabled(enabled: Boolean)
    fun onboardingComplete()
    fun nearbyScanResult(result: ResultCode, discoveredCount: Int, failure: FailureCategory? = null)
    fun qrScanResult(result: ResultCode, failure: FailureCategory? = null)
    fun deviceConnectResult(result: ResultCode, channel: Channel, deviceType: DeviceType, failure: FailureCategory? = null)
    fun sessionStartResult(result: ResultCode, channel: Channel, deviceType: DeviceType, failure: FailureCategory? = null)
    fun sessionFinishResult(result: ResultCode, channel: Channel, deviceType: DeviceType, failure: FailureCategory? = null)
    fun networkFailure(operation: NetworkOperation, failure: FailureCategory, httpClass: HttpClass, recovery: RecoveryResult)
}

object NoOpAnalyticsTracker : AnalyticsTracker {
    override fun setCollectionEnabled(enabled: Boolean) = Unit
    override fun onboardingComplete() = Unit
    override fun nearbyScanResult(result: ResultCode, discoveredCount: Int, failure: FailureCategory?) = Unit
    override fun qrScanResult(result: ResultCode, failure: FailureCategory?) = Unit
    override fun deviceConnectResult(result: ResultCode, channel: Channel, deviceType: DeviceType, failure: FailureCategory?) = Unit
    override fun sessionStartResult(result: ResultCode, channel: Channel, deviceType: DeviceType, failure: FailureCategory?) = Unit
    override fun sessionFinishResult(result: ResultCode, channel: Channel, deviceType: DeviceType, failure: FailureCategory?) = Unit
    override fun networkFailure(operation: NetworkOperation, failure: FailureCategory, httpClass: HttpClass, recovery: RecoveryResult) = Unit
}

class FirebaseAnalyticsTracker(private val analytics: FirebaseAnalytics) : AnalyticsTracker {
    override fun setCollectionEnabled(enabled: Boolean) { analytics.setAnalyticsCollectionEnabled(enabled) }
    override fun onboardingComplete() = log("onboarding_complete")
    override fun nearbyScanResult(result: ResultCode, discoveredCount: Int, failure: FailureCategory?) =
        log("nearby_scan_result", "result" to result.name, "count_bucket" to countBucket(discoveredCount), "failure" to failure?.name)
    override fun qrScanResult(result: ResultCode, failure: FailureCategory?) =
        log("qr_scan_result", "result" to result.name, "failure" to failure?.name)
    override fun deviceConnectResult(result: ResultCode, channel: Channel, deviceType: DeviceType, failure: FailureCategory?) =
        log("device_connect_result", "result" to result.name, "channel" to channel.name, "device_type" to deviceType.name, "failure" to failure?.name)
    override fun sessionStartResult(result: ResultCode, channel: Channel, deviceType: DeviceType, failure: FailureCategory?) =
        log("session_start_result", "result" to result.name, "channel" to channel.name, "device_type" to deviceType.name, "failure" to failure?.name)
    override fun sessionFinishResult(result: ResultCode, channel: Channel, deviceType: DeviceType, failure: FailureCategory?) =
        log("session_finish_result", "result" to result.name, "channel" to channel.name, "device_type" to deviceType.name, "failure" to failure?.name)
    override fun networkFailure(operation: NetworkOperation, failure: FailureCategory, httpClass: HttpClass, recovery: RecoveryResult) =
        log("network_diagnostic", "operation" to operation.name, "failure" to failure.name, "http_class" to httpClass.name, "recovery" to recovery.name)

    private fun log(name: String, vararg values: Pair<String, String?>) {
        val bundle = Bundle()
        values.forEach { (key, value) -> value?.let { bundle.putString(key, it) } }
        analytics.logEvent(name, bundle)
    }
}

fun failureCategory(error: Throwable): FailureCategory = when (error) {
    is java.io.IOException -> FailureCategory.NETWORK
    is SecurityException -> FailureCategory.PERMISSION
    is dev.uednd.hydros.data.network.ApiFailure -> if (error.code == null) FailureCategory.UNKNOWN else FailureCategory.REJECTED
    else -> FailureCategory.UNKNOWN
}

fun countBucket(count: Int): String = when (count) { 0 -> "0"; in 1..2 -> "1_2"; in 3..5 -> "3_5"; in 6..10 -> "6_10"; else -> "11_plus" }
