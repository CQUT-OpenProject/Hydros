package dev.uednd.hydros.data.ble

import java.security.MessageDigest


object DeviceSigner {

    private const val OUTER_KEY = "&key=sign-kailu-855c74a88b8b494187c99b08b8c9a744"


    fun signParams(sessionKey: String, raw: String): String? {
        if (sessionKey.isEmpty()) {
            ProtocolLog.w("签名被拒：sessionKey 为空")
            return null
        }
        return try {
            val inner = md5Hex(raw + "&key=" + sessionKey)
            md5Hex(inner + OUTER_KEY)
        } catch (t: Throwable) {
            ProtocolLog.e("签名失败", t)
            null
        }
    }


    val isAvailable: Boolean get() = true


    val failureReason: String? get() = null

    fun md5Hex(s: String): String =
        MessageDigest.getInstance("MD5")
            .digest(s.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
