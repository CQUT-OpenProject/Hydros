package org.cqutosp.hydros.ui.components

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
import org.cqutosp.hydros.ui.theme.LocalFeedbackSettings

import android.view.HapticFeedbackConstants

/** Semantic feedback matching custom Android haptic patterns, with system touch settings. */
internal open class HydrosHaptics(private val view: View? = null, private val enabled: () -> Boolean = { true }) {
    private val vibrator by lazy { view?.context?.getSystemService(VibratorManager::class.java)?.defaultVibrator }

    @Suppress("DEPRECATION")
    open fun light() {
        val v = view
        if (v != null && v.isAttachedToWindow && enabled()) {
            val played = try {
                v.performHapticFeedback(
                    HapticFeedbackConstants.CLOCK_TICK,
                    HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING or HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING
                )
            } catch (_: Exception) { false }
            if (played) return
        }
        val effect = try {
            VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK)
        } catch (_: Exception) {
            waveformEffect(longArrayOf(0, 20), intArrayOf(0, 160))
        }
        play(effect)
    }

    open fun medium() = play(waveformEffect(longArrayOf(0, 43), intArrayOf(0, 180)))
    open fun heavy() = play(waveformEffect(longArrayOf(0, 60), intArrayOf(0, 255)))
    open fun success() = play(waveformEffect(longArrayOf(0, 35, 65, 21), intArrayOf(0, 250, 0, 180)))
    open fun warning() = play(waveformEffect(longArrayOf(0, 30, 40, 30, 50, 60), intArrayOf(255, 255, 255, 255, 255, 255)))
    open fun error() = play(waveformEffect(longArrayOf(0, 27, 45, 50), intArrayOf(0, 120, 0, 250)))

    private fun waveformEffect(timings: LongArray, amplitudes: IntArray): VibrationEffect {
        val levels = if (vibrator?.hasAmplitudeControl() == true) amplitudes else
            amplitudes.map { if (it == 0) 0 else VibrationEffect.DEFAULT_AMPLITUDE }.toIntArray()
        return VibrationEffect.createWaveform(timings, levels, -1)
    }

    @Suppress("DEPRECATION") // Touch settings and AudioAttributes are needed on Android 12.
    private fun play(effect: VibrationEffect) {
        val v = view ?: return
        if (!enabled() || !v.isAttachedToWindow) return
        if (Build.VERSION.SDK_INT < 33 && Settings.System.getInt(v.context.contentResolver, Settings.System.HAPTIC_FEEDBACK_ENABLED, 1) == 0) return
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
