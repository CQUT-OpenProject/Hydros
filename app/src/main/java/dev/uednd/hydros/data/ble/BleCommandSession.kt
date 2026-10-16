package dev.uednd.hydros.data.ble

import java.io.ByteArrayOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull

data class DeviceQueryState(
    val protocolType: String,
    val randomNumber: String,
    val state: Int,
    val devType: Int,
    val a1: Int,
)

/**
 * One established connection's command exchange. Callers serialize complete transactions.
 * The adapter submits bytes and forwards callbacks; this module owns framing, write acknowledgments,
 * reply matching and retirement. A retired exchange can never accept a late reply or replay a write.
 */
internal class BleCommandSession(
    private val writeChunk: (ByteArray) -> Boolean,
    private val disconnect: () -> Unit,
) {
    companion object {
        const val CMD_QUERY = 0x23
        const val CMD_RATE = 0x21
        const val CMD_CLOSE = 0x22
        const val CMD_COLLECT = 0x85
        const val CMD_WRITE_BACK = 0x86
        const val DEFAULT_TIMEOUT_MS = 8_000L
        private const val ACK_OK = 0x80
    }

    private val lock = Any()
    private val incoming = Channel<Pair<Int, ByteArray>>(32)
    private val buffer = ByteArrayOutputStream()
    private val lost = CompletableDeferred<Unit>()
    private var writeSignal: CompletableDeferred<Boolean>? = null
    @Volatile private var closed = false

    fun onDisconnected() {
        synchronized(lock) {
            closed = true
            buffer.reset()
            while (incoming.tryReceive().isSuccess) { }
            lost.complete(Unit)
            writeSignal?.complete(false)
        }
    }

    private fun retire() {
        val shouldDisconnect = synchronized(lock) {
            if (closed) false else { onDisconnected(); true }
        }
        if (shouldDisconnect) disconnect()
    }

    fun onWriteCompleted(success: Boolean) {
        synchronized(lock) { if (!closed) writeSignal?.complete(success) }
    }

    fun onNotification(bytes: ByteArray) {
        synchronized(lock) {
            if (closed) return
            if (buffer.size() + bytes.size > 4096) buffer.reset()
            buffer.write(bytes)
            val all = buffer.toByteArray()
            var start = 0
            for (i in all.indices) {
                if (all[i].toInt() and 0xFF != BleFrame.WIRE_END) continue
                val frame = BleFrame.parseWire(all.copyOfRange(start, i + 1))
                start = i + 1
                if (frame != null) incoming.trySend(BleFrame.cmdOf(frame) to (BleFrame.payloadOf(frame) ?: ByteArray(0)))
            }
            buffer.reset()
            if (start < all.size) buffer.write(all, start, all.size - start)
        }
    }

    private suspend fun write(wire: ByteArray): Boolean {
        var offset = 0
        while (offset < wire.size) {
            val chunk = wire.copyOfRange(offset, minOf(offset + 20, wire.size))
            val signal = CompletableDeferred<Boolean>()
            val accepted = synchronized(lock) {
                if (closed) return false
                writeSignal = signal
                try { writeChunk(chunk) } catch (error: CancellationException) { throw error }
                catch (_: Exception) { false }
            }
            try {
                if (!accepted || withTimeoutOrNull(3_000L) { signal.await() } != true) return false
            } finally {
                synchronized(lock) { if (writeSignal === signal) writeSignal = null }
            }
            offset += chunk.size
            if (offset < wire.size) delay(20)
        }
        return true
    }

    private suspend fun exchange(cmd: Int, data: ByteArray = ByteArray(0), timeoutMs: Long): ByteArray? {
        if (closed) return null
        while (incoming.tryReceive().isSuccess) { }
        return try {
            if (!write(BleFrame.cmdToWire(cmd, data))) { retire(); return null }
            val result = withTimeoutOrNull(timeoutMs) {
                while (true) {
                    val reply = select<Pair<Int, ByteArray>?> {
                        lost.onAwait { null }
                        incoming.onReceive { it }
                    } ?: return@withTimeoutOrNull null
                    if (reply.first == cmd) return@withTimeoutOrNull reply.second
                }
                @Suppress("UNREACHABLE_CODE") null
            }
            if (result == null) retire()
            result
        } catch (error: CancellationException) {
            retire()
            throw error
        }
    }

    suspend fun query(timeoutMs: Long = DEFAULT_TIMEOUT_MS): DeviceQueryState? {
        val payload = exchange(CMD_QUERY, timeoutMs = timeoutMs)?.takeIf { it.size >= 31 } ?: return null
        return DeviceQueryState(
            protocolType = "%02X".format(payload[19].toInt() and 0xFF),
            randomNumber = BleFrame.toHex(payload.copyOfRange(24, 28)),
            state = payload[28].toInt() and 0xFF,
            devType = payload[29].toInt() and 0xFF,
            a1 = payload[30].toInt() and 0xFF,
        )
    }

    private suspend fun acknowledged(cmd: Int, data: ByteArray = ByteArray(0), timeoutMs: Long): Boolean =
        exchange(cmd, data, timeoutMs)?.firstOrNull()?.let { it.toInt() and 0xFF == ACK_OK } == true

    suspend fun writeRate(data: ByteArray, timeoutMs: Long = DEFAULT_TIMEOUT_MS) = acknowledged(CMD_RATE, data, timeoutMs)
    suspend fun closeValve(timeoutMs: Long = DEFAULT_TIMEOUT_MS) = acknowledged(CMD_CLOSE, timeoutMs = timeoutMs)
    suspend fun collect(timeoutMs: Long = DEFAULT_TIMEOUT_MS) = exchange(CMD_COLLECT, timeoutMs = timeoutMs)
    suspend fun writeBack(data: ByteArray, timeoutMs: Long = DEFAULT_TIMEOUT_MS) = acknowledged(CMD_WRITE_BACK, data, timeoutMs)
}
