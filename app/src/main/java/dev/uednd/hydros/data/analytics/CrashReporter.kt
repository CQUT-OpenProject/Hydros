package dev.uednd.hydros.data.analytics

import com.google.firebase.crashlytics.FirebaseCrashlytics
import dev.uednd.hydros.BuildConfig

object CrashReporter {
    fun setCollectionEnabled(enabled: Boolean) {
        if (BuildConfig.DEBUG) return
        FirebaseCrashlytics.getInstance().isCrashlyticsCollectionEnabled = enabled
    }
}
