package dev.uednd.hydros.data.ble


object BleFrame {

    const val HEAD: Int = 0x60
    const val TAIL: Int = 0x16
    const val WIRE_START: Int = 0x23   // '#'
    const val WIRE_END: Int = 0x0A     // '\n'
    const val MAX_PAYLOAD_BYTES = 252
    private const val FLAG: Int = 0x80


    fun build(cmd: Int, data: ByteArray = ByteArray(0)): ByteArray {
        require(data.size <= MAX_PAYLOAD_BYTES) { "BLE payload exceeds frame length" }
        val len = data.size + 3
        val frame = ByteArray(data.size + 8)
        frame[0] = HEAD.toByte()
        frame[1] = 0
        frame[2] = len.toByte()
        frame[3] = FLAG.toByte()
        frame[4] = cmd.toByte()
        frame[5] = 0
        System.arraycopy(data, 0, frame, 6, data.size)

        var sum = 0
        for (b in data) sum += b.toInt() and 0xFF
        frame[6 + data.size] = ((sum + FLAG + cmd) % 256).toByte()
        frame[7 + data.size] = TAIL.toByte()
        return frame
    }


    fun toWire(frame: ByteArray): ByteArray {
        val hex = toHex(frame).toByteArray(Charsets.US_ASCII)
        val out = ByteArray(hex.size + 2)
        out[0] = WIRE_START.toByte()
        System.arraycopy(hex, 0, out, 1, hex.size)
        out[out.size - 1] = WIRE_END.toByte()
        return out
    }


    fun cmdToWire(cmd: Int, data: ByteArray = ByteArray(0)): ByteArray =
        toWire(build(cmd, data))


    fun parseWire(wire: ByteArray): ByteArray? {
        if (wire.isEmpty() || wire.last().toInt() and 0xFF != WIRE_END) return null
        val start = if (wire[0].toInt() and 0xFF == WIRE_START) 1 else 0
        val hexLen = wire.size - start - 1
        if (hexLen <= 0 || hexLen % 2 != 0) return null
        val frame = fromHex(String(wire, start, hexLen, Charsets.US_ASCII)) ?: return null
        return if (verify(frame)) frame else null
    }


    fun verify(frame: ByteArray): Boolean {
        if (frame.size < 8) return false
        if (frame[0].toInt() and 0xFF != HEAD) return false
        val len = frame[2].toInt() and 0xFF
        if (frame.size != len + 5) return false          // 总长 = (len-3)+8
        if (frame.last().toInt() and 0xFF != TAIL) return false
        val dataLen = len - 3
        var sum = 0
        for (i in 0 until dataLen) sum += frame[6 + i].toInt() and 0xFF
        val expected = (sum + (frame[3].toInt() and 0xFF) + (frame[4].toInt() and 0xFF)) % 256
        return (frame[frame.size - 2].toInt() and 0xFF) == expected
    }


    /** Extract from a frame already accepted by verify or parseWire, like cmdOf. */
    fun payloadOf(frame: ByteArray): ByteArray = frame.copyOfRange(6, frame.size - 2)


    fun cmdOf(frame: ByteArray): Int = frame[4].toInt() and 0xFF


    private const val HEX = "0123456789ABCDEF"


    fun toHex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            sb.append(HEX[v shr 4]).append(HEX[v and 0xF])
        }
        return sb.toString()
    }


    fun fromHex(hex: String): ByteArray? {
        if (hex.length % 2 != 0) return null
        val out = ByteArray(hex.length / 2)
        for (i in out.indices) {
            val hi = hexVal(hex[i * 2]) ?: return null
            val lo = hexVal(hex[i * 2 + 1]) ?: return null
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }

    private fun hexVal(c: Char): Int? = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> null
    }
}
