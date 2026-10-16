package dev.uednd.hydros.data.ble

import android.annotation.SuppressLint
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec


object DeviceCrypto {

    private val AES_KEY_BYTES = byteArrayOf(
        50, 48, 50, 49, 48, 49, 49, 56, // 20210118
        107, 108, 99, 120,               // k l c x
        64, 48, 48, 50                   // @002
    )

    val aesKey: ByteArray get() = AES_KEY_BYTES.copyOf()

    fun decryptByAes(cipher: String): String? {
        if (cipher.isBlank()) return null
        return try {
            val decoded = base64Decode(cipher)

            val decrypted = if (decoded.size % 16 == 0 && decoded.isNotEmpty()) {
                aesEcbDecrypt(decoded) ?: return null
            } else return null
            stripPaddingToText(decrypted).takeIf { it.isNotEmpty() }
        } catch (t: Throwable) {
            ProtocolLog.e("AES 解密失败", t)
            null
        }
    }


    @SuppressLint("GetInstance") // Fixed device wire protocol; credentials use AES-GCM instead.
    private fun aesEcbDecrypt(data: ByteArray): ByteArray? = try {
        val cipher = Cipher.getInstance("AES/ECB/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(AES_KEY_BYTES, "AES"))
        cipher.doFinal(data)
    } catch (_: Exception) {
        null
    }


    private fun stripPaddingToText(decrypted: ByteArray): String {
        val n = decrypted.size
        if (n == 0) return ""
        val last = decrypted[n - 1].toInt() and 0xFF
        var effective = decrypted
        if (last in 1..n) {
            val start = n - last
            for (i in n - 1 downTo start) {
                if ((decrypted[i].toInt() and 0xFF) != last) return ""
            }
            effective = decrypted.copyOf(n - last)
        }
        val zeroIndex = effective.indexOfFirst { it.toInt() == 0 }
        val end = if (zeroIndex >= 0) zeroIndex else effective.size
        return String(effective, 0, end, Charsets.UTF_8)
    }


    private const val B64_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    private fun isBase64Char(c: Int): Boolean =
        (c in 'A'.code..'Z'.code) || (c in 'a'.code..'z'.code) || (c in '0'.code..'9'.code) ||
            c == '+'.code || c == '/'.code

    private fun base64Decode(input: String): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val quad = IntArray(4)
        var cnt = 0
        for (ch in input) {
            val c = ch.code
            if (c == '='.code || !isBase64Char(c)) break
            quad[cnt++] = B64_ALPHABET.indexOf(ch)
            if (cnt == 4) {
                out.write(((quad[0] shl 2) or ((quad[1] and 0x30) shr 4)) and 0xFF)
                out.write((((quad[1] and 0x0F) shl 4) or ((quad[2] and 0x3C) shr 2)) and 0xFF)
                out.write((((quad[2] and 0x03) shl 6) or quad[3]) and 0xFF)
                cnt = 0
            }
        }
        if (cnt == 2) {
            out.write(((quad[0] shl 2) or ((quad[1] and 0x30) shr 4)) and 0xFF)
        } else if (cnt == 3) {
            out.write(((quad[0] shl 2) or ((quad[1] and 0x30) shr 4)) and 0xFF)
            out.write((((quad[1] and 0x0F) shl 4) or ((quad[2] and 0x3C) shr 2)) and 0xFF)
        }
        return out.toByteArray()
    }
}
