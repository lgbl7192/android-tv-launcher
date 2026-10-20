package me.prince.launcher

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.Window
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.GlobalPositionAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

// Android 11 has no real-time blur (RenderEffect is API 31), so glass samples a pre-blurred copy of
// whatever is drawn behind it: the wallpaper on the home screen, a screen capture behind the menu.
// Draws the part behind a surface whose top-left sits at origin (root coordinates). It runs while drawing,
// so the scroll and fades it reads redraw the glass without recomposing it.
fun interface GlassSource {
    fun draw(scope: DrawScope, origin: Offset, alpha: Float, scrim: Color)
}

val LocalGlassSource = compositionLocalOf<GlassSource?> { null }

private const val BLUR_WIDTH = 160
private const val BLUR_RADIUS = 4
private const val BLUR_PASSES = 3

// Frosted surface: blurred backdrop lined up with what's behind it, a milky lift, a specular top
// highlight and a light rim that's brighter at the top-left, like light catching the edge.
// Smoked darkens the backdrop instead of lifting it, in either theme; content on it uses onSmoke.
fun Modifier.glass(shape: Shape, enabled: Boolean = true, smoked: Boolean = false): Modifier =
    if (enabled) clip(shape).then(GlassElement(shape, smoked)) else this

private data class GlassElement(val shape: Shape, val smoked: Boolean) : ModifierNodeElement<GlassNode>() {
    override fun create() = GlassNode(shape, smoked)

    override fun update(node: GlassNode) {
        node.shape = shape
        node.smoked = smoked
    }
}

private class GlassNode(shape: Shape, smoked: Boolean) :
    Modifier.Node(), DrawModifierNode, GlobalPositionAwareModifierNode, CompositionLocalConsumerModifierNode {
    var shape = shape
        set(value) {
            field = value
            outline = null
            invalidateDraw()
        }
    var smoked = smoked
        set(value) {
            if (field == value) return
            field = value
            colors = null
            invalidateDraw()
        }
    private var origin = Offset.Zero
    private var outline: Outline? = null
    private var outlineSize = Size.Unspecified
    private var colors: LauncherColors? = null
    private lateinit var highlight: Brush
    private lateinit var rim: Brush

    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        val position = coordinates.positionInRoot()
        if (position == origin) return
        origin = position
        // Without a source nothing behind the glass shifts when it moves
        if (currentValueOf(LocalGlassSource) != null) invalidateDraw()
    }

    override fun ContentDrawScope.draw() {
        val theme = currentValueOf(LocalColors)
        if (theme !== colors) {
            colors = theme
            highlight = Brush.verticalGradient(0f to if (smoked) theme.smokeHighlight else theme.glassHighlight, 0.5f to Color.Transparent)
            rim = Brush.linearGradient(if (smoked) theme.smokeRim else theme.glassRim)
        }
        currentValueOf(LocalGlassSource)?.draw(this, origin, 1f, if (smoked) theme.smokeScrim else theme.scrim)
        drawRect(if (smoked) theme.smokeFill else theme.glassFill)
        drawRect(highlight)
        drawContent()
        // Inset by half the stroke so the clip doesn't cut the rim in half
        val stroke = 1.dp.toPx()
        val edge = outline.takeIf { outlineSize == size }
            ?: shape.createOutline(Size(size.width - stroke, size.height - stroke), layoutDirection, this).also {
                outline = it
                outlineSize = size
            }
        translate(stroke / 2, stroke / 2) { drawOutline(edge, rim, style = Stroke(stroke)) }
    }
}

// A blurred image stretched over bounds (root coordinates), seen from a surface whose top-left sits at origin,
// clipped to the surface
fun DrawScope.drawGlassImage(image: ImageBitmap, bounds: Rect, origin: Offset, alpha: Float) {
    clipRect {
        translate(-origin.x, -origin.y) {
            drawImage(
                image,
                dstOffset = IntOffset(bounds.left.roundToInt(), bounds.top.roundToInt()),
                dstSize = IntSize(bounds.width.roundToInt(), bounds.height.roundToInt()),
                alpha = alpha,
                filterQuality = FilterQuality.Low,
            )
        }
    }
}

// The wallpaper's glass copy, scrims and all. Like the photo, it goes over the theme's scrim colour, so a theme
// switch needs no new copy.
fun frostedWallpaper(photo: Bitmap): ImageBitmap {
    val (w, h, px) = blurredPixels(photo)
    return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888).asImageBitmap()
}

// Small blurred copy of the window, for the glass behind the long-press menu and the panels
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

private fun boxBlur(px: IntArray, w: Int, h: Int, horizontal: Boolean) {
    val lines = if (horizontal) h else w
    val length = if (horizontal) w else h
    val n = 2 * BLUR_RADIUS + 1
    val line = IntArray(length)
    for (l in 0 until lines) {
        for (i in 0 until length) line[i] = px[if (horizontal) l * w + i else i * w + l]
        var a = 0
        var r = 0
        var g = 0
        var b = 0
        for (i in -BLUR_RADIUS..BLUR_RADIUS) {
            val p = line[i.coerceIn(0, length - 1)]
            a += p ushr 24; r += (p shr 16) and 0xFF; g += (p shr 8) and 0xFF; b += p and 0xFF
        }
        for (i in 0 until length) {
            px[if (horizontal) l * w + i else i * w + l] = ((a / n) shl 24) or ((r / n) shl 16) or ((g / n) shl 8) or (b / n)
            val add = line[(i + BLUR_RADIUS + 1).coerceAtMost(length - 1)]
            val sub = line[(i - BLUR_RADIUS).coerceAtLeast(0)]
            a += (add ushr 24) - (sub ushr 24)
            r += ((add shr 16) and 0xFF) - ((sub shr 16) and 0xFF)
            g += ((add shr 8) and 0xFF) - ((sub shr 8) and 0xFF)
            b += (add and 0xFF) - (sub and 0xFF)
        }
    }
}
