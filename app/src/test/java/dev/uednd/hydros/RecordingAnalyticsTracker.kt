package dev.uednd.hydros

import dev.uednd.hydros.data.analytics.*
import dev.uednd.hydros.data.storage.PostSessionDestination
import dev.uednd.hydros.ui.state.SessionRecoveryReason

/** Records the events a test cares about; every other event stays a no-op. */
class RecordingAnalyticsTracker : AnalyticsTracker by NoOpAnalyticsTracker {
    var enabledState = false
    val consents = mutableListOf<Boolean>()
    /** Collection state observed at the moment each consent event was emitted. */
    val consentStates = mutableListOf<Boolean>()
    val screens = mutableListOf<AppScreen>()
    val postSessionNav = mutableListOf<Pair<PostSessionDestination, PostSessionNavResult>>()
    val recoveryResults = mutableListOf<Triple<SessionRecoveryStage, ResultCode, SessionRecoveryReason?>>()
    val installResults = mutableListOf<ResultCode>()
    val loginResults = mutableListOf<ResultCode>()
    val settings = mutableListOf<Pair<SettingsKey, String>>()

    override fun setCollectionEnabled(enabled: Boolean) { enabledState = enabled }
    override fun analyticsConsentChange(enabled: Boolean) {
        consents += enabled
        consentStates += enabledState
    }
    override fun appScreen(screen: AppScreen) { screens += screen }
    override fun postSessionNavResult(destination: PostSessionDestination, result: PostSessionNavResult) {
        postSessionNav += destination to result
    }
    override fun sessionRecoveryResult(
        stage: SessionRecoveryStage,
        result: ResultCode,
        recoveryReason: SessionRecoveryReason?,
        failure: FailureCategory?,
    ) {
        recoveryResults += Triple(stage, result, recoveryReason)
    }
    override fun updateInstallResult(result: ResultCode, failure: FailureCategory?) { installResults += result }
    override fun loginResult(result: ResultCode, method: LoginMethod, failure: FailureCategory?) { loginResults += result }
    override fun settingsChange(key: SettingsKey, valueBucket: String) { settings += key to valueBucket }
}
