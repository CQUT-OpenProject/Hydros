package dev.uednd.hydros.ui.scanner

import android.util.Size
import android.view.MotionEvent
import androidx.camera.core.*
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Observer
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.ZoomSuggestionOptions
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

internal data class CameraStatus(
    val ready: Boolean = false,
    val hasFlash: Boolean = false,
    val torchOn: Boolean = false,
    val error: String? = null,
    val torchError: String? = null,
)

@androidx.annotation.OptIn(markerClass = [ExperimentalGetImage::class])
@Composable
internal fun CameraPreview(
    enabled: Boolean,
    torchRequested: Boolean,
    modifier: Modifier = Modifier,
    onStatus: (CameraStatus) -> Unit,
    onCodes: (List<String>) -> Unit,
    onFailure: () -> Unit,
) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val latestCodes by rememberUpdatedState(onCodes)
    val latestStatus by rememberUpdatedState(onStatus)
    val latestFailure by rememberUpdatedState(onFailure)
    val view = remember(context) { PreviewView(context).apply {
        implementationMode = PreviewView.ImplementationMode.PERFORMANCE
        scaleType = PreviewView.ScaleType.FILL_CENTER
        contentDescription = "相机取景，点击对焦"
    } }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var status by remember { mutableStateOf(CameraStatus()) }
    LaunchedEffect(torchRequested, camera, enabled) {
        val bound = camera
        if (bound != null && enabled && bound.cameraInfo.hasFlashUnit()) {
            val task = bound.cameraControl.enableTorch(torchRequested)
            task.addListener({
                if (camera === bound) {
                    val error = try { task.get(); null } catch (_: Exception) { "手电筒切换失败，请重试" }
                    status = status.copy(torchError = error)
                    latestStatus(status)
                }
            }, ContextCompat.getMainExecutor(context))
        }
    }
    DisposableEffect(owner, enabled, view) {
        if (!enabled) {
            status = CameraStatus()
            latestStatus(status)
            onDispose { }
        } else {
            val executor = Executors.newSingleThreadExecutor()
            val main = ContextCompat.getMainExecutor(context)
            val future = ProcessCameraProvider.getInstance(context)
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(view.surfaceProvider) }
            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(ResolutionSelector.Builder().setResolutionStrategy(
                    ResolutionStrategy(Size(1280, 720), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)).build())
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
            val lock = Any()
            var disposed = false
            var processing = false
            var provider: ProcessCameraProvider? = null
            var decoder: com.google.mlkit.vision.barcode.BarcodeScanner? = null
            var bound: Camera? = null
            var torchObserver: Observer<Int>? = null
            var cameraObserver: Observer<CameraState>? = null
            fun publish(value: CameraStatus) { status = value; latestStatus(value) }
            fun focus(x: Float, y: Float) {
                val target = bound ?: return
                val point = view.meteringPointFactory.createPoint(x, y)
                val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
                    .setAutoCancelDuration(3, TimeUnit.SECONDS).build()
                if (target.cameraInfo.isFocusMeteringSupported(action)) target.cameraControl.startFocusAndMetering(action)
            }
            view.setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_UP) { focus(event.x, event.y); view.performClick() }
                true
            }
            publish(CameraStatus())
            future.addListener({
                if (!disposed) try {
                    provider = future.get()
                    val target = provider!!.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                    bound = target
                    camera = target
                    val zoomMax = target.cameraInfo.zoomState.value?.maxZoomRatio ?: 1f
                    decoder = BarcodeScanning.getClient(qrOptions().setZoomSuggestionOptions(
                        ZoomSuggestionOptions.Builder { ratio ->
                            if (disposed || !status.ready) false else {
                                target.cameraControl.setZoomRatio(ratio.coerceIn(1f, zoomMax)); true
                            }
                        }.setMaxSupportedZoomRatio(zoomMax).build()).build())
                    torchObserver = Observer<Int> { value ->
                        if (!disposed) publish(status.copy(torchOn = value == TorchState.ON))
                    }.also { target.cameraInfo.torchState.observe(owner, it) }
                    cameraObserver = Observer<CameraState> { value ->
                        if (!disposed) publish(status.copy(ready = value.type == CameraState.Type.OPEN,
                            hasFlash = target.cameraInfo.hasFlashUnit(),
                            error = if (value.error != null) "相机无法启动，请检查权限或设备相机" else null))
                    }.also { target.cameraInfo.cameraState.observe(owner, it) }
                    view.post { if (!disposed) focus(view.width / 2f, view.height / 2f) }
                    analysis.setAnalyzer(executor) { image ->
                        synchronized(lock) {
                            val media = image.image
                            val scanner = decoder
                            if (disposed || processing || media == null || scanner == null) image.close()
                            else {
                                processing = true
                                try {
                                    scanner.process(InputImage.fromMediaImage(media, image.imageInfo.rotationDegrees))
                                        .addOnSuccessListener { codes ->
                                            if (!disposed) latestCodes(codes.mapNotNull { it.rawValue })
                                        }
                                        .addOnFailureListener { if (!disposed) latestFailure() }
                                        .addOnCompleteListener {
                                            synchronized(lock) {
                                                image.close()
                                                processing = false
                                                if (disposed) scanner.close()
                                            }
                                        }
                                } catch (_: Exception) {
                                    image.close(); processing = false
                                    main.execute { if (!disposed) latestFailure() }
                                }
                            }
                        }
                    }
                } catch (_: Exception) {
                    publish(CameraStatus(error = "相机无法启动，请检查权限或设备相机"))
                }
            }, main)
            onDispose {
                synchronized(lock) {
                    disposed = true
                    analysis.clearAnalyzer()
                    if (!processing) decoder?.close()
                }
                view.setOnTouchListener(null)
                torchObserver?.let { bound?.cameraInfo?.torchState?.removeObserver(it) }
                cameraObserver?.let { bound?.cameraInfo?.cameraState?.removeObserver(it) }
                bound?.cameraControl?.enableTorch(false)
                provider?.unbind(preview, analysis)
                camera = null
                executor.shutdown()
            }
        }
    }
    AndroidView(factory = { view }, modifier = modifier)
}
