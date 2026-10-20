package me.prince.launcher

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.Window
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.foundation.border
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

// Android 11 has no real-time blur (RenderEffect is API 31), so glass samples a pre-blurred copy of
// whatever is drawn behind it: the wallpaper on the home screen, a screen capture behind the menu.
class GlassSource(val image: ImageBitmap, val bounds: Rect, val alpha: Float = 1f)

val LocalGlassSource = compositionLocalOf<GlassSource?> { null }

private const val BLUR_WIDTH = 160
private const val BLUR_RADIUS = 4
private const val BLUR_PASSES = 3
private const val STRIP_BLUR_RADIUS = 2

// Frosted surface: blurred backdrop lined up with what's behind it, a milky lift, a specular top
// highlight and a light rim that's brighter at the top-left, like light catching the edge.
fun Modifier.glass(shape: Shape, enabled: Boolean = true): Modifier = composed {
    val source = LocalGlassSource.current
    var origin by remember { mutableStateOf(Offset.Zero) }
    if (!enabled) return@composed this
    this
        .onGloballyPositioned { origin = it.positionInRoot() }
        .clip(shape)
        .drawBehind {
            if (source != null) {
                translate(-origin.x, -origin.y) {
                    drawImage(
                        source.image,
                        dstOffset = IntOffset(source.bounds.left.roundToInt(), source.bounds.top.roundToInt()),
                        dstSize = IntSize(source.bounds.width.roundToInt(), source.bounds.height.roundToInt()),
                        alpha = source.alpha,
                        filterQuality = FilterQuality.Low,
                    )
                }
            }
            drawRect(Color.White.copy(alpha = 0.09f))
            drawRect(Brush.verticalGradient(0f to Color.White.copy(alpha = 0.16f), 0.5f to Color.Transparent))
        }
        .border(
            1.dp,
            Brush.linearGradient(
                listOf(Color.White.copy(alpha = 0.5f), Color.White.copy(alpha = 0.08f), Color.White.copy(alpha = 0.22f)),
            ),
            shape,
        )
}

// Small blurred copy of the window, for the glass behind the long-press menu
fun captureBlurredWindow(window: Window, onReady: (ImageBitmap?) -> Unit) {
    val view = window.decorView
    val target = Bitmap.createBitmap(BLUR_WIDTH, BLUR_WIDTH * view.height / view.width, Bitmap.Config.ARGB_8888)
    PixelCopy.request(window, target, { result ->
        if (result != PixelCopy.SUCCESS) {
            onReady(null)
            return@request
        }
        val (w, h, px) = blurredPixels(target)
        onReady(Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888).asImageBitmap())
    }, Handler(Looper.getMainLooper()))
}

// Blurs an already-small capture (the strip under the pinned bar) in place, no rescale
fun blurredStrip(image: ImageBitmap): ImageBitmap {
    // Layer readbacks are HARDWARE bitmaps, which can't hand out pixels
    val bitmap = image.asAndroidBitmap().copy(Bitmap.Config.ARGB_8888, false)
    val w = bitmap.width
    val h = bitmap.height
    val px = IntArray(w * h)
    bitmap.getPixels(px, 0, w, 0, 0, w, h)
    repeat(BLUR_PASSES) {
        boxBlur(px, w, h, horizontal = true, radius = STRIP_BLUR_RADIUS)
        boxBlur(px, w, h, horizontal = false, radius = STRIP_BLUR_RADIUS)
    }
    return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888).asImageBitmap()
}

private data class Pixels(val width: Int, val height: Int, val argb: IntArray)

// Downscale then three box-blur passes (≈ gaussian). At 160px wide this takes a few ms.
private fun blurredPixels(source: Bitmap): Pixels {
    val w = BLUR_WIDTH
    val h = (source.height * w / source.width).coerceAtLeast(1)
    val small = if (source.width == w) source else Bitmap.createScaledBitmap(source, w, h, true)
    val px = IntArray(w * h)
    small.getPixels(px, 0, w, 0, 0, w, h)
    repeat(BLUR_PASSES) {
        boxBlur(px, w, h, horizontal = true)
        boxBlur(px, w, h, horizontal = false)
    }
    return Pixels(w, h, px)
}

private fun boxBlur(px: IntArray, w: Int, h: Int, horizontal: Boolean, radius: Int = BLUR_RADIUS) {
    val lines = if (horizontal) h else w
    val length = if (horizontal) w else h
    val n = 2 * radius + 1
    val line = IntArray(length)
    for (l in 0 until lines) {
        for (i in 0 until length) line[i] = px[if (horizontal) l * w + i else i * w + l]
        var a = 0
        var r = 0
        var g = 0
        var b = 0
        for (i in -radius..radius) {
            val p = line[i.coerceIn(0, length - 1)]
            a += p ushr 24; r += (p shr 16) and 0xFF; g += (p shr 8) and 0xFF; b += p and 0xFF
        }
        for (i in 0 until length) {
            px[if (horizontal) l * w + i else i * w + l] = ((a / n) shl 24) or ((r / n) shl 16) or ((g / n) shl 8) or (b / n)
            val add = line[(i + radius + 1).coerceAtMost(length - 1)]
            val sub = line[(i - radius).coerceAtLeast(0)]
            a += (add ushr 24) - (sub ushr 24)
            r += ((add shr 16) and 0xFF) - ((sub shr 16) and 0xFF)
            g += ((add shr 8) and 0xFF) - ((sub shr 8) and 0xFF)
            b += (add and 0xFF) - (sub and 0xFF)
        }
    }
}
