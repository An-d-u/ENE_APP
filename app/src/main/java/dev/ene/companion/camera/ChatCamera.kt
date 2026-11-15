package dev.ene.companion.camera

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraState
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LiveData
import androidx.lifecycle.Observer
import dev.ene.companion.connection.ObservationIntervals
import java.util.concurrent.Executors

/** 한 번 허용한 렌즈만 연다. 종료 후 도착하는 프레임과 미리보기 콜백은 폐기한다. */
internal class ChatCamera(
    private val context: Context,
    private val owner: LifecycleOwner,
    val generation: Long,
    lens: String,
    private val rotation: Int,
    private val current: () -> Boolean,
    private val observationEpoch: () -> Long?,
    intervals: () -> ObservationIntervals,
    private val onObservation: (Long, ByteArray) -> Unit,
    private val onFailure: () -> Unit,
) {
    private val selector = when (lens) {
        "front" -> CameraSelector.DEFAULT_FRONT_CAMERA
        "back" -> CameraSelector.DEFAULT_BACK_CAMERA
        else -> error("지원하지 않는 렌즈")
    }
    private val sampler = CameraFrameSampler(intervals)
    private val worker = Executors.newSingleThreadExecutor()
    private val main = ContextCompat.getMainExecutor(context)
    private val analysis = ImageAnalysis.Builder().setTargetRotation(rotation)
        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
        .setResolutionSelector(ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
            .setResolutionStrategy(ResolutionStrategy(Size(1280, 720),
                ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)).build()).build()
    @Volatile private var closed = false
    private var provider: ProcessCameraProvider? = null
    private var preview: Preview? = null
    private var cameraState: LiveData<CameraState>? = null
    private val cameraObserver = Observer<CameraState> { if (it.error != null) fail() }

    fun start() {
        var nextPermissionCheck = 0L
        analysis.setAnalyzer(worker) { image ->
            try {
                if (closed || !current()) return@setAnalyzer
                val now = SystemClock.elapsedRealtime()
                if (now >= nextPermissionCheck) {
                    nextPermissionCheck = now + 1_000
                    if (!hasCameraPermission(context)) { main.execute(::fail); return@setAnalyzer }
                }
                sampler.frame(now, observationEpoch,
                    { cameraSignature(image) }, {
                        if (closed || !current() || !hasCameraPermission(context)) null
                        else {
                            val bitmap = image.toBitmap()
                            try { cameraJpeg(bitmap, image.cropRect, image.imageInfo.rotationDegrees) }
                            finally { bitmap.recycle() }
                        }
                    }) { epoch, jpeg -> main.execute {
                        if (!closed && current() && observationEpoch() == epoch) onObservation(epoch, jpeg)
                    } }
            } catch (_: RuntimeException) {
                main.execute { if (!closed) fail() }
            } finally { image.close() }
        }
        try {
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener({
                if (closed) return@addListener
                if (!current() || !owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) { fail(); return@addListener }
                try {
                    val cameras = future.get()
                    provider = cameras
                    check(cameras.hasCamera(selector))
                    val uses = listOfNotNull(analysis, preview).toTypedArray()
                    val camera = cameras.bindToLifecycle(owner, selector, *uses)
                    cameraState = camera.cameraInfo.cameraState
                    cameraState?.observe(owner, cameraObserver)
                } catch (_: Exception) { fail() }
            }, main)
        } catch (_: RuntimeException) { fail() }
    }

    suspend fun question(): ByteArray? {
        if (closed || !current() || !hasCameraPermission(context)) return null
        return sampler.nextJpeg()?.takeIf { !closed && current() && hasCameraPermission(context) }
    }

    fun previewOn(surface: Preview.SurfaceProvider?) {
        if (closed) return
        try {
            preview?.let { provider?.unbind(it) }
            preview = surface?.let { Preview.Builder().setTargetRotation(rotation).build().apply { surfaceProvider = it } }
            preview?.let { provider?.bindToLifecycle(owner, selector, it) }
        } catch (_: RuntimeException) { fail() }
    }

    private fun fail() {
        if (closed) return
        close()
        onFailure()
    }

    fun close() {
        if (closed) return
        closed = true
        sampler.close()
        cameraState?.removeObserver(cameraObserver)
        analysis.clearAnalyzer()
        provider?.unbind(*listOfNotNull(analysis, preview).toTypedArray())
        worker.shutdown()
    }
}

internal fun hasCameraPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
