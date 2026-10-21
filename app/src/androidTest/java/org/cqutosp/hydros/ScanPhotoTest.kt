package org.cqutosp.hydros

import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import org.cqutosp.hydros.data.repository.qrMac
import org.cqutosp.hydros.ui.scanner.decodeQrPhoto
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Real bundled ML Kit, with fixtures passed through the production URI/EXIF/resize path. */
class ScanPhotoTest {
    private fun decode(name: String): List<String> = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val file = File(context.cacheDir, "scan-fixture-$name")
        try {
            instrumentation.context.assets.open("scan/$name").use { input -> file.outputStream().use { input.copyTo(it) } }
            decodeQrPhoto(context, Uri.fromFile(file))
        } finally { file.delete() }
    }
    @Test fun normalRotatedSmallLowContrastAndLargePhotosDecodeRealQr() {
        for (name in listOf("normal.png", "rotated.png", "small.png", "low-contrast.png", "large.png", "exif-rotated.jpg")) {
            assertEquals(name, listOf("AABBCCDDEEFF"), decode(name).mapNotNull(::qrMac))
        }
    }
    @Test fun mixedPhotoRetainsBothDevicesAndUnrelatedQr() {
        val raw = decode("mixed.png")
        assertTrue(raw.contains("https://example.com"))
        assertEquals(setOf("AABBCCDDEEFF", "112233445566"), raw.mapNotNull(::qrMac).toSet())
    }
    @Test fun noQrHasEmptyResult() { assertTrue(decode("empty.png").isEmpty()) }
    @Test fun unreadableUriFailsWithoutPretendingNoQr() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        try {
            runBlocking { decodeQrPhoto(context, Uri.fromFile(File(context.cacheDir, "missing-scan-photo"))) }
            fail("Missing image should fail")
        } catch (_: java.io.IOException) { }
    }
}
