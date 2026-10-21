package org.cqutosp.hydros.data.update

import kotlinx.serialization.Serializable

internal enum class UpdatePhase {
    IDLE, AWAITING_PERMISSION, DOWNLOADING, WAITING_NETWORK, VERIFYING, INSTALLING, AWAITING_CONFIRMATION, FAILED
}

/**
 * The single authorized update, persisted so a receiver or a restarted process can resume it.
 * [versionCode] stays at 0 until the downloaded archive has been parsed and verified.
 */
@Serializable
internal data class UpdateTask(
    val phase: UpdatePhase = UpdatePhase.IDLE,
    val taskId: String = "",
    val version: String = "",
    val url: String = "",
    val size: Long = 0,
    val sha256: String? = null,
    val downloadId: Long = -1,
    val sessionId: Int = -1,
    val versionCode: Long = 0,
    val committed: Boolean = false,
    val committedAt: Long = 0,
    val percent: Int? = null,
    val status: String? = null,
    val error: String? = null,
) {
    val active: Boolean get() = phase != UpdatePhase.IDLE && phase != UpdatePhase.FAILED
    val cancellable: Boolean get() = active && !committed
}
