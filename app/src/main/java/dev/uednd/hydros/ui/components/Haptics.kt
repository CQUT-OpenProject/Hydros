package dev.uednd.hydros.ui.components

import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.VibratorManager
import android.os.Build
import android.media.AudioAttributes
import android.provider.Settings
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalInspectionMode
import dev.uednd.hydros.ui.theme.LocalFeedbackSettings

/** Semantic feedback matching Chronos's Android Capacitor haptics, with system touch settings. */
internal class HydrosHaptics(private val view: View, private val enabled: () -> Boolean) {
    private val vibrator by lazy { view.context.getSystemService(VibratorManager::class.java)?.defaultVibrator }

    fun light() = play(VibrationEffect.createOneShot(12, VibrationEffect.DEFAULT_AMPLITUDE))
    fun medium() = waveform(longArrayOf(0, 43), intArrayOf(0, 180))
    fun heavy() = waveform(longArrayOf(0, 60), intArrayOf(0, 255))
    fun success() = waveform(longArrayOf(0, 35, 65, 21), intArrayOf(0, 250, 0, 180))
    fun warning() = waveform(longArrayOf(0, 30, 40, 30, 50, 60), intArrayOf(255, 255, 255, 255, 255, 255))
    fun error() = waveform(longArrayOf(0, 27, 45, 50), intArrayOf(0, 120, 0, 250))

    private fun waveform(timings: LongArray, amplitudes: IntArray) {
        val levels = if (vibrator?.hasAmplitudeControl() == true) amplitudes else
            amplitudes.map { if (it == 0) 0 else VibrationEffect.DEFAULT_AMPLITUDE }.toIntArray()
        play(VibrationEffect.createWaveform(timings, levels, -1))
    }

    @Suppress("DEPRECATION") // Touch settings and AudioAttributes are needed on Android 12.
    private fun play(effect: VibrationEffect) {
        if (!enabled() || !view.isAttachedToWindow || !view.hasWindowFocus() || !view.isHapticFeedbackEnabled) return
        if (Build.VERSION.SDK_INT < 33 && Settings.System.getInt(view.context.contentResolver, Settings.System.HAPTIC_FEEDBACK_ENABLED, 1) == 0) return
        val motor = vibrator?.takeIf { it.hasVibrator() } ?: return
        // A feedback failure must never interrupt the action that requested it.
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                motor.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_TOUCH))
            } else {
                motor.vibrate(effect, AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            }
        } catch (_: SecurityException) { }
    }
}

@Composable
internal fun rememberHydrosHaptics(): HydrosHaptics {
    val view = LocalView.current
    val enabled by rememberUpdatedState(!LocalInspectionMode.current && LocalFeedbackSettings.current.hapticFeedbackEnabled)
    return remember(view) { HydrosHaptics(view) { enabled } }
}
