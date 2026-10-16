package dev.uednd.hydros.data.ble

/** Protocol payloads and session material must never reach platform logs. */
internal object ProtocolLog {
    fun i(@Suppress("UNUSED_PARAMETER") message: String) = Unit
    fun w(@Suppress("UNUSED_PARAMETER") message: String) = Unit
    fun e(@Suppress("UNUSED_PARAMETER") message: String, @Suppress("UNUSED_PARAMETER") cause: Throwable?) = Unit
}
