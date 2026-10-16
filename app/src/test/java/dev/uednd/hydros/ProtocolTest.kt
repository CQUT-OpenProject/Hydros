package dev.uednd.hydros

import dev.uednd.hydros.data.ble.*
import dev.uednd.hydros.data.network.*
import dev.uednd.hydros.data.repository.*
import dev.uednd.hydros.ui.state.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import java.util.Base64

class ProtocolTest {
    @Test fun mqttIgnoresOtherOrdersRetainedAndMalformedMessages() {
        val payload="""{"orderNo":"own","state":0}""".toByteArray()
        assertTrue(matchesOrderPush("own",payload,false))
        assertFalse(matchesOrderPush("other",payload,false))
        assertFalse(matchesOrderPush("own",payload,true))
        assertFalse(matchesOrderPush("own","broken".toByteArray(),false))
    }
    @Test fun authFieldsIncludeBothPhonesAndProjectHeaders() {
        val auth=Credentials("token", "1", "2", "3", "13800138000")
        assertEquals(auth.fields()["telephone"], auth.fields()["telPhone"])
        assertEquals("6.5.28", auth.fields()["version"])
        assertEquals("3", auth.headers()["Config-Project"])
        assertTrue(auth.headers().getValue("Config-Keys").contains("module_list"))
    }
    @Test fun signingMatchesKnownPasswordAndSmsSamples() {
        assertEquals("4BC85B3E44", passwordHash("mypassword"))
        assertEquals("f000554d9cb90c44bd0ae2ded9a847aa", smsSecret("13800138000"))
        assertEquals(BleSignBuilder.signUpload("key", "13800138000", "aabb"), BleSignBuilder.signUpload("key", "13800138000", "AABB"))
        val expected=DeviceSigner.md5Hex(DeviceSigner.md5Hex("deviceId7randomNumberabcdef01telephone13800138000xfModel0&key=key") + "&key=sign-kailu-855c74a88b8b494187c99b08b8c9a744")
        assertEquals(expected, BleSignBuilder.signRateOrder("key", "13800138000", "7", "0", "ABCDEF01"))
    }
    @Test fun moneyHasNoFloatingPointOrUnknownZeroFallback() {
        assertEquals(40L, yuanMilli("0.04"))
        assertEquals(40000L, yuanMilli("40"))
        assertEquals(0L, yuanMilli("0"))
        assertEquals(0L, yuanMilli("0.00"))
        assertEquals(500L, yuanMilli("0.50"))
        assertEquals("0.04", money(displayCents(40)))
        assertEquals(1955L, yuanMilli("1.955"))
        assertEquals("1.96", money(displayCents(1955)))
        assertNull(yuanMilli(null)); assertNull(yuanMilli("NaN")); assertNull(yuanMilli("-1"))
        assertEquals("—", money(null))
    }
    @Test fun runningOrderRequiresExplicitOwnershipAndBusinessSuccess() {
        val obj=Json.parseToJsonElement("""{"orderNo":"abc"}""")
        assertNull((runningOrder(ApiResponse(success=true, data=obj)) as RunningOrder.Running).owner)
        assertSame(RunningOrder.Unknown, runningOrder(ApiResponse(success=true, errorCode=1)))
        assertSame(RunningOrder.Ended, runningOrder(ApiResponse(success=true)))
        assertTrue(runningOrder(ApiResponse(success=false, errorCode=307)) is RunningOrder.Running)
        assertFalse(isSessionExpiry("账号或密码错误")); assertTrue(isSessionExpiry("登录已过期，请重新登录"))
    }
    @Test fun qrAndMacCandidatesDoNotRewriteOriginalAddress() {
        val tag = DEVICE_BROADCAST_PREFIX
        assertEquals("AABBCCDDEEFF", qrMac("$tag,M,AA:BB:CC:DD:EE:FF"))
        assertEquals("AABBCCDDEEFF", qrMac("$tag, M, AA:BB:CC:DD:EE:FF"))
        assertEquals("AABBCCDDEEFF", qrMac("${tag.lowercase()},m,aabbccddeeff"))
        assertEquals("AABBCCDDEEFF", qrMac("$tag,AABBCCDDEEFF"))
        assertNull(qrMac("https://example.com/AABBCCDDEEFF"))
        assertNull(qrMac("$tag,M,not-a-mac"))
        assertEquals("C0158304D300", MacFormat.lookupVariants("C0158304D300").first())
        assertEquals("00158304d300", MacFormat.lookupVariants("C0158304D300")[1])
        assertEquals("C0158304D300", MacFormat.connectAddressFor("00158304D300"))
        assertEquals(DeviceCategory.SHOWER, category("热水变-宿舍-1001"))
        assertEquals(DeviceCategory.DRINK, category("直饮冷水-大厅"))
    }
    @Test fun wireFramesRejectBadCrcLengthAndIncompleteHex() {
        val wire=BleFrame.cmdToWire(0x23)
        assertEquals("#600003802300A316\n", String(wire))
        assertNotNull(BleFrame.parseWire(wire))
        val bad=wire.copyOf(); bad[13]='B'.code.toByte()
        assertNull(BleFrame.parseWire(bad))
        assertNull(BleFrame.fromHex("X0")); assertNull(BleFrame.fromHex("123"))
        assertNull(BleFrame.parseWire(wire.copyOf(wire.size-1)))
        val payload=byteArrayOf(0,1,127,-1)
        assertArrayEquals(payload, BleFrame.payloadOf(BleFrame.build(0x85,payload)))
    }
    @Test fun everyWireLengthIsValidatedBeforeExtractingItsPayload() {
        for (length in 0..255) {
            val frame = ByteArray(length + 5)
            frame[0] = BleFrame.HEAD.toByte()
            frame[2] = length.toByte()
            frame[3] = 0x80.toByte()
            frame[4] = 0x23
            frame[frame.lastIndex] = BleFrame.TAIL.toByte()
            if (frame.size >= 8) frame[frame.lastIndex - 1] = 0xA3.toByte()
            val parsed = BleFrame.parseWire(BleFrame.toWire(frame))
            if (length < 3) assertNull(parsed)
            else {
                assertNotNull(parsed)
                assertEquals(length - 3, BleFrame.payloadOf(requireNotNull(parsed)).size)
            }
        }
    }
    @Test fun aesWritebackDecryptsServerPacketAndRejectsInvalidData() {
        val cipher=Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(DeviceCrypto.aesKey, "AES"))
        val encoded=Base64.getEncoder().encodeToString(cipher.doFinal("prefix-AABBCC".toByteArray()))
        assertEquals("prefix-AABBCC", DeviceCrypto.decryptByAes(encoded))
        assertNull(DeviceCrypto.decryptByAes(""))
    }
}
