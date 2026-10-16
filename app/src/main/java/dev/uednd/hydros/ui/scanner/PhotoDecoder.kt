package dev.uednd.hydros.ui.scanner

import android.content.Context
import android.graphics.ImageDecoder
import android.net.Uri
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

internal fun qrOptions() = BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE)

/** ImageDecoder applies EXIF orientation. Decode and allocation never run on the UI thread. */
internal suspend fun decodeQrPhoto(context: Context, uri: Uri): List<String> = withContext(Dispatchers.IO) {
    val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        val longest = maxOf(info.size.width, info.size.height)
        if (longest > 4096) decoder.setTargetSize(
            (info.size.width.toLong() * 4096 / longest).toInt().coerceAtLeast(1),
            (info.size.height.toLong() * 4096 / longest).toInt().coerceAtLeast(1))
    }
    val scanner = BarcodeScanning.getClient(qrOptions().build())
    // Task completion owns resources even if the calling coroutine is cancelled.
    suspendCancellableCoroutine { continuation ->
        try {
            scanner.process(InputImage.fromBitmap(bitmap, 0))
                .addOnCompleteListener { task ->
                    try {
                        if (continuation.isActive) {
                            if (task.isSuccessful) continuation.resume(task.result.mapNotNull { it.rawValue })
                            else continuation.resumeWithException(task.exception ?: IllegalStateException("图片识别失败"))
                        }
                    } finally { scanner.close(); bitmap.recycle() }
                }
        } catch (e: Exception) {
            scanner.close(); bitmap.recycle()
            if (continuation.isActive) continuation.resumeWithException(e)
        }
    }
}
