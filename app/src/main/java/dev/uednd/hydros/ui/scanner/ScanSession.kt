package dev.uednd.hydros.ui.scanner

import dev.uednd.hydros.data.repository.qrMac
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal enum class ScanPhase { SCANNING, PICKING, DECODING, CHOOSING, RESOLVING, ERROR, DONE }
internal data class ScanState(
    val phase: ScanPhase = ScanPhase.SCANNING,
    val generation: Long = 0,
    val candidates: List<String> = emptyList(),
    val mac: String? = null,
    val message: String? = null,
    val deviceId: String? = null,
)

/** Main-thread coordinator shared by live frames and picked images. Tokens reject stale results. */
internal class ScanSession {
    private val mutable = MutableStateFlow(ScanState())
    val state = mutable.asStateFlow()
    fun retry() { mutable.value = ScanState(generation = mutable.value.generation + 1) }
    fun retryQuery() {
        val current = mutable.value
        if (current.phase == ScanPhase.ERROR && current.mac != null)
            mutable.value = ScanState(ScanPhase.RESOLVING, generation = current.generation + 1, mac = current.mac)
        else retry()
    }
    fun pauseCamera() {
        if (mutable.value.phase == ScanPhase.SCANNING) retry()
    }
    fun pick() { mutable.value = ScanState(ScanPhase.PICKING, mutable.value.generation + 1) }
    fun picked(hasImage: Boolean): Long? {
        if (mutable.value.phase != ScanPhase.PICKING) return null
        if (!hasImage) { retry(); return null }
        mutable.value = mutable.value.copy(phase = ScanPhase.DECODING)
        return mutable.value.generation
    }
    fun codes(generation: Long, raw: List<String>, photo: Boolean = false) {
        val current = mutable.value
        if (generation != current.generation || current.phase != if (photo) ScanPhase.DECODING else ScanPhase.SCANNING) return
        val valid = raw.mapNotNull(::qrMac).distinct()
        mutable.value = when {
            valid.size == 1 -> current.copy(phase = ScanPhase.RESOLVING, mac = valid.single(), message = null)
            valid.size > 1 -> current.copy(phase = ScanPhase.CHOOSING, candidates = valid, message = null)
            photo -> current.copy(phase = ScanPhase.ERROR, message = if (raw.isEmpty()) "图片中未识别到二维码" else "不是支持的用水设备二维码")
            raw.isNotEmpty() -> current.copy(message = "不是支持的用水设备二维码，请对准设备二维码")
            else -> current
        }
    }
    fun choose(mac: String) {
        val current = mutable.value
        if (current.phase == ScanPhase.CHOOSING && mac in current.candidates)
            mutable.value = current.copy(phase = ScanPhase.RESOLVING, mac = mac)
    }
    fun fail(generation: Long, message: String) {
        val current = mutable.value
        if (generation == current.generation && current.phase in setOf(ScanPhase.SCANNING, ScanPhase.DECODING, ScanPhase.RESOLVING))
            mutable.value = current.copy(phase = ScanPhase.ERROR, message = message)
    }
    fun resolved(generation: Long, id: String?) {
        val current = mutable.value
        if (generation != current.generation || current.phase != ScanPhase.RESOLVING) return
        mutable.value = current.copy(phase = if (id == null) ScanPhase.ERROR else ScanPhase.DONE,
            deviceId = id, message = null) // The resolver owns the specific query error notification.
    }
}
