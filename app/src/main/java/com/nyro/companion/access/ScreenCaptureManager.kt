package com.nyro.companion.access

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import com.nyro.companion.overlay.NyroModeService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * MediaProjection fallback for screen capture, used when the accessibility screenshot API is
 * unavailable (service disabled). Consent is requested through the system dialog once per
 * NYRO Mode session. The virtual display is kept but detached from its surface between captures,
 * so no frames are produced unless NYRO is actually looking at the screen.
 */
object ScreenCaptureManager {
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var pendingConsent: CompletableDeferred<Pair<Int, Intent?>>? = null
    private val thread by lazy { HandlerThread("nyro-capture").apply { start() } }
    private val handler by lazy { Handler(thread.looper) }

    val isActive: Boolean get() = projection != null

    suspend fun capture(context: Context): Result<Bitmap> {
        if (NyroModeService.instance == null) return Result.failure(IllegalStateException("NYRO Mode must be on for screen capture."))
        if (projection == null) {
            val ok = requestConsent(context)
            if (!ok) return Result.failure(IllegalStateException("Screen capture permission was not granted."))
        }
        val vd = display ?: return Result.failure(IllegalStateException("Screen capture is not ready."))
        val r = reader ?: return Result.failure(IllegalStateException("Screen capture is not ready."))
        // Drain stale frames, attach the surface, take one fresh frame, detach.
        runCatching { r.acquireLatestImage()?.close() }
        val bmp = withTimeoutOrNull(2500) {
            suspendCancellableCoroutine<Bitmap?> { cont ->
                r.setOnImageAvailableListener({ ir ->
                    val img = runCatching { ir.acquireLatestImage() }.getOrNull() ?: return@setOnImageAvailableListener
                    try {
                        val plane = img.planes[0]
                        val pixelStride = plane.pixelStride
                        val rowPadding = plane.rowStride - pixelStride * img.width
                        val full = Bitmap.createBitmap(img.width + rowPadding / pixelStride, img.height, Bitmap.Config.ARGB_8888)
                        full.copyPixelsFromBuffer(plane.buffer)
                        val cropped = Bitmap.createBitmap(full, 0, 0, img.width, img.height)
                        if (cropped !== full) full.recycle()
                        ir.setOnImageAvailableListener(null, null)
                        if (cont.isActive) cont.resume(cropped)
                    } finally { img.close() }
                }, handler)
                vd.surface = r.surface
            }
        }
        vd.surface = null
        r.setOnImageAvailableListener(null, null)
        return bmp?.let { Result.success(it) } ?: Result.failure(IllegalStateException("No frame received from screen capture."))
    }

    private suspend fun requestConsent(context: Context): Boolean {
        val deferred = CompletableDeferred<Pair<Int, Intent?>>()
        pendingConsent = deferred
        context.startActivity(Intent(context, ProjectionPermissionActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val (code, data) = withTimeoutOrNull(60_000) { deferred.await() } ?: return false
        if (code != Activity.RESULT_OK || data == null) return false
        return start(context, code, data)
    }

    internal fun onConsentResult(code: Int, data: Intent?) {
        pendingConsent?.complete(code to data)
        pendingConsent = null
    }

    @SuppressLint("WrongConstant")
    private fun start(context: Context, code: Int, data: Intent): Boolean {
        // Android 14+: the foreground service must hold the mediaProjection type before getMediaProjection().
        NyroModeService.instance?.promoteForProjection() ?: return false
        val mpm = context.getSystemService(MediaProjectionManager::class.java)
        val p = runCatching { mpm.getMediaProjection(code, data) }.getOrNull() ?: return false
        p.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { release() }
        }, handler)
        val dm = context.resources.displayMetrics
        val ir = ImageReader.newInstance(dm.widthPixels, dm.heightPixels, PixelFormat.RGBA_8888, 2)
        val vd = runCatching {
            p.createVirtualDisplay("nyro-capture", dm.widthPixels, dm.heightPixels, dm.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, null, null, handler)
        }.getOrNull() ?: run { p.stop(); ir.close(); return false }
        projection = p; reader = ir; display = vd
        return true
    }

    fun release() {
        runCatching { display?.release() }
        runCatching { reader?.close() }
        runCatching { projection?.stop() }
        display = null; reader = null; projection = null
        NyroModeService.instance?.demoteFromProjection()
    }
}

/** Invisible activity that shows the system screen-capture consent dialog and returns. */
class ProjectionPermissionActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mpm = getSystemService(MediaProjectionManager::class.java)
        @Suppress("DEPRECATION")
        startActivityForResult(mpm.createScreenCaptureIntent(), 7)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        ScreenCaptureManager.onConsentResult(resultCode, data)
        finish()
    }

    override fun onDestroy() {
        if (isFinishing) ScreenCaptureManager.onConsentResult(RESULT_CANCELED, null)
        super.onDestroy()
    }
}
