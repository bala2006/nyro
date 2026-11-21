package com.nyro.companion.tools

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.util.Base64
import com.nyro.companion.NyroApp
import com.nyro.companion.access.NyroAccessibilityService
import com.nyro.companion.access.ScreenCaptureManager
import com.nyro.companion.access.ScreenSnapshot
import com.nyro.companion.exec.ImageHash
import com.nyro.companion.exec.VisionCost
import com.nyro.companion.overlay.UiFeedback
import com.nyro.companion.perf.Metric
import com.nyro.companion.perf.Perf
import java.io.ByteArrayOutputStream

/** Where a picture sits on the screen, so image coordinates can be mapped back to taps. */
data class ShotGeometry(val left: Int, val top: Int, val scale: Float) {
    fun screenX(x: Int): Float = left + x / scale
    fun screenY(y: Int): Float = top + y / scale
}

/** One encoded picture ready to attach to a request. */
data class Shot(
    val base64: String,
    val mime: String,
    /** `low` or `high`, always explicit: luna reads `auto` as `original`. */
    val detail: String,
    val width: Int,
    val height: Int,
    /** The part of the screen this picture shows, in screen pixels. */
    val region: Rect,
    val level: VisionCost.Level,
    val bytes: Int,
    val tokens: Int,
    val hash: Long,
) {
    /** Identity of the frame: the same level, crop and size. The pixel hash is compared separately. */
    val frameKey: String
        get() = level.name + "|" + region.left + "," + region.top + "," + region.width() + "x" + region.height() +
            "|" + width + "x" + height

    val geometry: ShotGeometry get() = ShotGeometry(region.left, region.top, width.toFloat() / region.width())
}

/**
 * Which image format the deployment accepts. WebP keeps glyph edges crisp at the same dimensions and therefore
 * the same token cost; if a deployment rejects it, every later picture falls back to JPEG.
 */
object VisionFormat {
    @Volatile var webp: Boolean = true
    fun rememberRejection() { webp = false }
}

@Volatile private var lastCaptureError = "Screen capture unavailable."

/** Accessibility screenshot first (no extra consent, respects secure windows), MediaProjection otherwise. */
suspend fun captureScreen(): Bitmap? = Perf.time(Metric.SHOT_CAPTURE) {
    val svc = NyroAccessibilityService.current
    UiFeedback.hiddenForCapture {
        if (svc != null) {
            when (val r = svc.screenshot()) {
                is NyroAccessibilityService.Shot.Ok -> r.bitmap
                is NyroAccessibilityService.Shot.Failed -> { lastCaptureError = r.reason; null }
            }
        } else {
            ScreenCaptureManager.capture(NyroApp.graph.context).getOrElse {
                lastCaptureError = it.message ?: "Capture failed"
                null
            }
        }
    }
}

/**
 * Remembers the last picture that was sent. A picture is a duplicate when the same region was captured at the
 * same size and the pixels are within [ImageHash.SAME_THRESHOLD] bits, so a blinking cursor or a re-encode does
 * not cost a second upload.
 */
object ShotDedupe {
    @Volatile private var frame: String? = null
    @Volatile private var hash: Long? = null

    fun isDuplicate(shot: Shot): Boolean {
        val previous = hash
        if (frame == shot.frameKey && previous != null && ImageHash.distance(previous, shot.hash) <= ImageHash.SAME_THRESHOLD) {
            return true
        }
        frame = shot.frameKey
        hash = shot.hash
        return false
    }

    fun reset() { frame = null; hash = null }
}

/** The three outcomes of a capture, so callers can tell a failure from a picture they already have. */
sealed interface ShotOutcome {
    data class Got(val shot: Shot) : ShotOutcome
    data object Duplicate : ShotOutcome
    data class Failed(val message: String) : ShotOutcome
}

/**
 * Captures, crops, annotates and encodes one picture, and announces it. Shared by the take_screenshot tool and
 * the automatic fallback, so both follow the same rules and neither pays twice for the same picture.
 */
suspend fun captureShot(snap: ScreenSnapshot?, level: VisionCost.Level, target: Rect? = null): ShotOutcome {
    val raw = captureScreen() ?: return ShotOutcome.Failed(lastCaptureError)
    val shot = renderShot(raw, snap, level, target)
    if (ShotDedupe.isDuplicate(shot)) return ShotOutcome.Duplicate
    ScreenSession.shot = shot.geometry
    Perf.count(Metric.SCREENSHOTS_SENT, 1)
    Perf.count(Metric.IMAGE_BYTES, shot.bytes.toLong())
    Perf.count(Metric.IMAGE_TOKENS, shot.tokens.toLong())
    UiFeedback.notice("Screen shared with Azure")
    return ShotOutcome.Got(shot)
}

/**
 * One pass over the pixels: the wanted region is drawn straight into the final size, hashed, annotated and
 * encoded once. The old path copied the full-resolution frame three times (scale, annotate, thumbnail).
 */
private fun renderShot(
    src: Bitmap,
    snap: ScreenSnapshot?,
    level: VisionCost.Level,
    target: Rect? = null,
): Shot = Perf.time(Metric.SHOT_ENCODE) {
    val screenW = snap?.screenW?.takeIf { it > 0 } ?: src.width
    val screenH = snap?.screenH?.takeIf { it > 0 } ?: src.height
    val region = clampRegion(target ?: contentRegion(level, snap) ?: Rect(0, 0, screenW, screenH), screenW, screenH)
    val fit = VisionCost.plan(region.width(), region.height(), level)

    val out = Bitmap.createBitmap(fit.width, fit.height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(out)
    val kx = src.width.toFloat() / screenW
    val ky = src.height.toFloat() / screenH
    val source = Rect(
        (region.left * kx).toInt().coerceIn(0, src.width - 1),
        (region.top * ky).toInt().coerceIn(0, src.height - 1),
        (region.right * kx).toInt().coerceIn(1, src.width),
        (region.bottom * ky).toInt().coerceIn(1, src.height),
    )
    canvas.drawBitmap(
        src,
        source,
        RectF(0f, 0f, fit.width.toFloat(), fit.height.toFloat()),
        Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG),
    )

    // Hash before the marks are drawn: a new number badge must not look like a new screen.
    val hash = averageHash(out)
    if (snap != null && level != VisionCost.Level.THUMB) {
        drawMarks(canvas, snap, region, fit.width.toFloat() / region.width(), fit)
    }

    val (bytes, mime) = encode(out, level)
    out.recycle()
    src.recycle()
    Shot(
        base64 = Base64.encodeToString(bytes, Base64.NO_WRAP),
        mime = mime,
        detail = level.detail.wire,
        width = fit.width,
        height = fit.height,
        region = region,
        level = level,
        bytes = bytes.size,
        tokens = VisionCost.tokens(fit.width, fit.height),
        hash = hash,
    )
}

/**
 * The app content for a crop: the union of visible element bounds, so the status bar, navigation bar and empty
 * margins are not paid for. Falls back to the whole screen when that union is nearly the whole screen anyway.
 */
fun contentRegion(level: VisionCost.Level, snap: ScreenSnapshot?): Rect? {
    if (level != VisionCost.Level.CROP || snap == null) return null
    val interesting = snap.elements.filter { it.clickable || it.editable || it.scrollable }
        .ifEmpty { snap.elements }
    if (interesting.isEmpty()) return null
    val r = Rect(interesting.first().bounds)
    interesting.forEach { r.union(it.bounds) }
    if (r.width() <= 1 || r.height() <= 1) return null
    r.inset(-8, -8)
    val screen = snap.screenW.toLong() * snap.screenH
    if (screen > 0 && r.width().toLong() * r.height() * 100 >= screen * 92) {
        return Rect(0, 0, snap.screenW, snap.screenH)
    }
    return r
}

private fun clampRegion(r: Rect, screenW: Int, screenH: Int): Rect {
    val out = Rect(r)
    out.left = out.left.coerceIn(0, screenW - 1)
    out.top = out.top.coerceIn(0, screenH - 1)
    out.right = out.right.coerceIn(out.left + 1, screenW)
    out.bottom = out.bottom.coerceIn(out.top + 1, screenH)
    return out
}

private fun averageHash(bmp: Bitmap): Long {
    val t = Bitmap.createScaledBitmap(bmp, 8, 8, true)
    val px = IntArray(64)
    t.getPixels(px, 0, 8, 0, 0, 8, 8)
    if (t !== bmp) t.recycle()
    return ImageHash.average(IntArray(64) { ImageHash.gray(px[it]) })
}

/**
 * Numbers the tappable elements that fall inside the crop, using the same ids as read_screen. The badge moves
 * above a short element so it never covers the label the model is being asked to read.
 */
private fun drawMarks(canvas: Canvas, snap: ScreenSnapshot, region: Rect, scale: Float, fit: VisionCost.Fit) {
    val accent = Color.rgb(74, 143, 224)
    val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = maxOf(1.5f, fit.width / 320f)
        color = accent
    }
    val fill = Paint().apply { color = accent }
    val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = maxOf(9f, fit.width / 32f)
        typeface = Typeface.DEFAULT_BOLD
    }
    var drawn = 0
    for (e in snap.elements) {
        if (drawn >= 60) break
        if (!(e.clickable || e.editable || e.scrollable)) continue
        if (!Rect.intersects(e.bounds, region)) continue
        val r = RectF(
            (e.bounds.left - region.left) * scale,
            (e.bounds.top - region.top) * scale,
            (e.bounds.right - region.left) * scale,
            (e.bounds.bottom - region.top) * scale,
        )
        canvas.drawRect(r, stroke)
        val label = e.id.toString()
        val w = text.measureText(label) + 5f
        val h = text.textSize + 3f
        val top = if (r.height() < h * 1.8f) (r.top - h).coerceAtLeast(0f) else r.top
        canvas.drawRect(r.left, top, r.left + w, top + h, fill)
        canvas.drawText(label, r.left + 2.5f, top + h - 3.5f, text)
        drawn++
    }
}

private fun encode(bmp: Bitmap, level: VisionCost.Level): Pair<ByteArray, String> {
    val quality = if (level == VisionCost.Level.THUMB) 70 else 82
    if (VisionFormat.webp) {
        val webp = ByteArrayOutputStream()
        if (bmp.compress(Bitmap.CompressFormat.WEBP_LOSSY, quality, webp) && webp.size() > 0) {
            return webp.toByteArray() to "image/webp"
        }
    }
    val jpeg = ByteArrayOutputStream()
    bmp.compress(Bitmap.CompressFormat.JPEG, quality + 3, jpeg)
    return jpeg.toByteArray() to "image/jpeg"
}
