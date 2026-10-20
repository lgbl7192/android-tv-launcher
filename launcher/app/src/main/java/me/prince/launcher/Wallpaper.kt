package me.prince.launcher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.util.Log
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.toSize
import androidx.tv.material3.LocalTextStyle
import androidx.tv.material3.Text
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.URLEncoder
import kotlin.math.roundToInt
import kotlin.random.Random

private const val TAG = "Wallpaper"
private const val API = "https://commons.wikimedia.org/w/api.php"
private const val MIN_WIDTH = 1920
// Landscape, but not panoramas
private val ASPECT_RATIOS = 1.3..2.0
// Commons scales thumbnails for at most 50 files per request
private const val BATCH_SIZE = 50
private const val MAX_BATCHES = 20
private const val LIST_REFRESH_MS = 7 * 24 * 3_600_000L
private const val RETRY_MS = 15_000L
private const val MAX_NAME = 50
private const val MAX_ARTIST = 40
internal const val CROSSFADE_MS = 1200
// The photo runs this far below the screen, so the parallax never shows its bottom edge
private val ParallaxRange = 60.dp
// The theme's scrim over the photo at full fade
private const val SCROLLED_SCRIM = 0.75f
// Featured, but not wallpapers: a nuclear test, a shell close-up and a manuscript diagram
private val EXCLUDED = setOf(
    "File:Operation Crossroads Baker Edit.jpg",
    "File:Ensis ensis (Linnaeus 1758).jpg",
    "File:Bartolomeu Velho 1568.jpg",
)

private val USER_LINK = Regex("""<a\b[^>]*\btitle="User:[^"]*"[^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)
private val HTML_TAG = Regex("<[^>]*>")
private val ENTITY = Regex("&(#[0-9]+|#x[0-9a-fA-F]+|[a-z]+);")
private val NAMED_ENTITIES = mapOf("amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ")
private val SPACES = Regex("[\\s\\u00A0]+")

enum class WallpaperCategory(val label: String, val commonsCategories: List<String>) {
    Lakes("Lakes", listOf("Featured pictures of lakes")),
    Mountains("Mountains", listOf("Featured pictures of mountains")),
    Forests("Forests", listOf("Featured pictures of forests")),
    Coasts("Coasts", listOf("Featured pictures of coasts", "Featured pictures of beaches")),
    // Commons has no night-sky category
    NightSkies("Night skies", listOf("Featured pictures of astronomy", "Featured pictures of aurora")),
}

data class Photo(
    // "File:…", unique on Commons
    val title: String,
    // The MIN_WIDTH thumbnail
    val url: String,
    val name: String,
    val artist: String,
    val license: String,
) {
    // The attribution CC BY and CC BY-SA ask for, e.g. "Chensiyuan, CC BY-SA 4.0"
    val credit: String get() = listOf(artist, license).filter { it.isNotBlank() }.joinToString(", ")
}

// What to show now, and how long to wait before calling updateWallpaper again
data class WallpaperUpdate(val photo: Photo?, val delayMs: Long)

private data class State(val current: Photo?, val next: Photo?, val changedAt: Long, val shown: Set<String>)

private val lock = Any()

// The photo shown last, straight from disk (works offline); null on first run
fun savedWallpaper(context: Context): Photo? = readState(wallpaperDir(context)).current

// One step towards "a current photo on disk and the next one downloaded": switches photo when the interval is up
// (or on skip), otherwise fetches what's missing. Blocking and may use the network; calls are serialised.
fun updateWallpaper(context: Context, prefs: HomePrefs, skip: Boolean = false): WallpaperUpdate = synchronized(lock) {
    val dir = wallpaperDir(context)
    try {
        wallpaperStep(dir, prefs, skip, System.currentTimeMillis(), Random.Default)
    } catch (e: IOException) {
        Log.w(TAG, "Couldn't save wallpaper state", e)
        WallpaperUpdate(readState(dir).current, RETRY_MS)
    }
}

// Centre-cropped to the aspect ratio of width x height and no bigger (never upscaled), uploaded ahead of drawing
private fun decodeWallpaper(context: Context, photo: Photo, width: Int, height: Int): Bitmap? {
    val source = BitmapFactory.decodeFile(photoFile(wallpaperDir(context), photo).path) ?: return null
    val crop = centreCrop(source.width, source.height, width, height)
    val scale = minOf(1f, width.toFloat() / crop.width)
    val matrix = Matrix().apply { setScale(scale, scale) }
    val bitmap = Bitmap.createBitmap(source, crop.x, crop.y, crop.width, crop.height, matrix, true)
    if (bitmap !== source) source.recycle()
    bitmap.prepareToDraw()
    return bitmap
}

internal fun wallpaperStep(dir: File, prefs: HomePrefs, skip: Boolean, now: Long, random: Random): WallpaperUpdate {
    val state = readState(dir)
    val interval = prefs.interval.millis
    val wait = (state.changedAt + interval - now).coerceIn(0, interval)
    // A photo from a category that was just turned off goes straight away
    fun isDue(pool: List<Photo>) =
        skip || state.current == null || wait == 0L || (pool.isNotEmpty() && pool.none { it.title == state.current.title })

    val cached = readPool(dir, prefs.categories)
    val next = state.next?.takeIf { next -> cached.any { it.title == next.title } }
    if (next != null) {
        if (!isDue(cached)) return WallpaperUpdate(state.current, wait)
        writeState(dir, state.copy(current = next, next = null, changedAt = now))
        return WallpaperUpdate(next, 0)
    }

    refreshLists(dir, prefs.categories, now)
    val pool = readPool(dir, prefs.categories)
    val (photo, shown) = drawPhoto(pool, state.shown, setOfNotNull(state.current?.title), random)
        ?: return WallpaperUpdate(state.current, RETRY_MS)
    if (!download(dir, photo)) return WallpaperUpdate(state.current, RETRY_MS)
    if (isDue(pool)) {
        writeState(dir, State(current = photo, next = null, changedAt = now, shown = shown))
        return WallpaperUpdate(photo, 0)
    }
    writeState(dir, state.copy(next = photo, shown = shown))
    return WallpaperUpdate(state.current, wait)
}

// A shuffle bag: no repeats until the pool runs out, and never what's on screen
internal fun drawPhoto(pool: List<Photo>, shown: Set<String>, onScreen: Set<String>, random: Random): Pair<Photo, Set<String>>? {
    val fresh = pool.filter { it.title !in shown && it.title !in onScreen }
    if (fresh.isNotEmpty()) return fresh.random(random).let { it to shown + it.title }
    val photo = pool.filter { it.title !in onScreen }.ifEmpty { return null }.random(random)
    return photo to onScreen + photo.title
}

internal class Crop(val x: Int, val y: Int, val width: Int, val height: Int)

internal fun centreCrop(sourceWidth: Int, sourceHeight: Int, width: Int, height: Int): Crop =
    if (sourceWidth.toLong() * height > sourceHeight.toLong() * width) {
        val cropWidth = (sourceHeight.toLong() * width / height).toInt()
        Crop((sourceWidth - cropWidth) / 2, 0, cropWidth, sourceHeight)
    } else {
        val cropHeight = (sourceWidth.toLong() * height / width).toInt()
        Crop(0, (sourceHeight - cropHeight) / 2, sourceWidth, cropHeight)
    }

internal fun fetchCategory(category: String): List<Photo> {
    val params = mapOf(
        "action" to "query",
        "format" to "json",
        "formatversion" to "2",
        "generator" to "categorymembers",
        "gcmtitle" to "Category:$category",
        "gcmtype" to "file",
        "gcmlimit" to "$BATCH_SIZE",
        "prop" to "imageinfo",
        "iiprop" to "url|size|extmetadata",
        "iiurlwidth" to "$MIN_WIDTH",
        "iiextmetadatafilter" to "ObjectName|Artist|LicenseShortName",
    )
    val photos = mutableListOf<Photo>()
    var continuation = emptyMap<String, String>()
    repeat(MAX_BATCHES) {
        val query = (params + continuation).entries.joinToString("&") { (key, value) -> "$key=${URLEncoder.encode(value, "UTF-8")}" }
        val response = JSONObject(httpGet("$API?$query"))
        response.optJSONObject("error")?.let { throw IOException("Commons: ${it.optString("info")}") }
        photos += parsePhotos(response)
        val more = response.optJSONObject("continue") ?: return photos.distinctBy { it.title }
        continuation = more.keys().asSequence().associateWith { more.getString(it) }
    }
    return photos.distinctBy { it.title }
}

internal fun parsePhotos(response: JSONObject): List<Photo> =
    response.optJSONObject("query")?.optJSONArray("pages").objects().mapNotNull(::parsePhoto)

private fun parsePhoto(page: JSONObject): Photo? {
    val info = page.optJSONArray("imageinfo")?.optJSONObject(0) ?: return null
    val width = info.optInt("width")
    val height = info.optInt("height")
    if (width < MIN_WIDTH || height <= 0 || width.toDouble() / height !in ASPECT_RATIOS) return null
    val title = page.optString("title").ifBlank { return null }
    if (title in EXCLUDED) return null
    val url = info.optString("thumburl").ifBlank { return null }
    val metadata = info.optJSONObject("extmetadata")
    fun field(key: String) = metadata?.optJSONObject(key)?.optString("value").orEmpty()
    val name = plainText(field("ObjectName")).ifBlank { title.removePrefix("File:").substringBeforeLast('.') }
    return Photo(
        title = title,
        url = url,
        name = shorten(name, MAX_NAME),
        artist = shorten(artist(field("Artist")), MAX_ARTIST),
        license = plainText(field("LicenseShortName")),
    )
}

// Artist is HTML, sometimes a whole template with contact details; the uploader's user link holds the name
private fun artist(html: String): String {
    val linked = USER_LINK.find(html)?.let { plainText(it.groupValues[1]) }.orEmpty()
    return linked.ifBlank { plainText(html) }.trimEnd('.')
}

private fun plainText(html: String): String =
    ENTITY.replace(HTML_TAG.replace(html, "")) { match ->
        val entity = match.groupValues[1]
        val codePoint = when {
            entity.startsWith("#x") -> entity.drop(2).toIntOrNull(16)
            entity.startsWith("#") -> entity.drop(1).toIntOrNull()
            else -> null
        }
        codePoint?.takeIf(Character::isValidCodePoint)?.let { String(Character.toChars(it)) }
            ?: NAMED_ENTITIES[entity]
            ?: match.value
    }.replace(SPACES, " ").trim()

private fun shorten(text: String, max: Int): String {
    if (text.length <= max) return text
    val cut = text.take(max - 1)
    val words = cut.substringBeforeLast(' ').takeIf { it.length > max / 2 } ?: cut
    return words.trimEnd(',', ';', ':', ' ') + "…"
}

private fun wallpaperDir(context: Context) = File(context.filesDir, "wallpaper")
private fun stateFile(dir: File) = File(dir, "state.json")
private fun photosDir(dir: File) = File(dir, "photos")
private fun photoFile(dir: File, photo: Photo) = File(photosDir(dir), Integer.toHexString(photo.title.hashCode()))
private fun listFile(dir: File, category: String) = File(dir, "lists/${category.replace(' ', '_')}.json")

private fun refreshLists(dir: File, categories: Set<WallpaperCategory>, now: Long) {
    for (category in categories.flatMap { it.commonsCategories }) {
        val file = listFile(dir, category)
        val fetchedAt = readJson(file)?.optLong("fetchedAt") ?: 0L
        if (now - fetchedAt in 0..LIST_REFRESH_MS) continue
        try {
            val photos = JSONArray(fetchCategory(category).map { it.toJson() })
            writeAtomically(file, JSONObject().put("fetchedAt", now).put("photos", photos).toString().toByteArray())
        } catch (e: IOException) {
            Log.w(TAG, "Couldn't fetch $category", e)
        } catch (e: JSONException) {
            Log.w(TAG, "Unexpected Commons response for $category", e)
        }
    }
}

private fun readPool(dir: File, categories: Set<WallpaperCategory>): List<Photo> =
    categories.flatMap { it.commonsCategories }
        .flatMap { category -> readJson(listFile(dir, category))?.optJSONArray("photos").objects().mapNotNull(::photoFrom) }
        .distinctBy { it.title }

private fun download(dir: File, photo: Photo): Boolean = try {
    writeAtomically(photoFile(dir, photo), httpBytes(photo.url))
    true
} catch (e: IOException) {
    Log.w(TAG, "Couldn't download ${photo.title}", e)
    false
}

// Photos whose file is missing count as absent, so a crash at any point leaves a state the next step can repair
private fun readState(dir: File): State {
    val json = readJson(stateFile(dir))
    fun photo(key: String) = json?.optJSONObject(key)?.let(::photoFrom)?.takeIf { photoFile(dir, it).exists() }
    return State(
        current = photo("current"),
        next = photo("next"),
        changedAt = json?.optLong("changedAt") ?: 0L,
        shown = json?.optJSONArray("shown").strings().toSet(),
    )
}

// Also deletes every photo the state no longer mentions, and any half-finished download
private fun writeState(dir: File, state: State) {
    val json = JSONObject()
        .put("current", state.current?.toJson())
        .put("next", state.next?.toJson())
        .put("changedAt", state.changedAt)
        .put("shown", JSONArray(state.shown.toList()))
    writeAtomically(stateFile(dir), json.toString().toByteArray())
    val keep = listOfNotNull(state.current, state.next).map { photoFile(dir, it).name }
    photosDir(dir).listFiles()?.filter { it.name !in keep }?.forEach { it.delete() }
}

private fun readJson(file: File): JSONObject? = try {
    if (file.exists()) JSONObject(file.readText()) else null
} catch (e: IOException) {
    Log.w(TAG, "Couldn't read $file", e)
    null
} catch (e: JSONException) {
    Log.w(TAG, "Corrupt $file", e)
    null
}

private fun writeAtomically(file: File, bytes: ByteArray) {
    file.parentFile?.mkdirs()
    val temp = File(file.path + ".tmp")
    temp.writeBytes(bytes)
    if (!temp.renameTo(file)) throw IOException("Couldn't replace $file")
}

private fun Photo.toJson(): JSONObject =
    JSONObject().put("title", title).put("url", url).put("name", name).put("artist", artist).put("license", license)

private fun photoFrom(json: JSONObject): Photo? {
    val title = json.optString("title").ifBlank { return null }
    val url = json.optString("url").ifBlank { return null }
    return Photo(title, url, json.optString("name"), json.optString("artist"), json.optString("license"))
}

class WallpaperImage(val photo: Photo, val image: ImageBitmap, val frosted: ImageBitmap)

// Blocking; null if the photo has since been replaced on disk
fun loadWallpaperImage(context: Context, photo: Photo, size: IntSize): WallpaperImage? {
    val bitmap = decodeWallpaper(context, photo, size.width, size.height) ?: return null
    return WallpaperImage(photo, bitmap.asImageBitmap(), frostedWallpaper(bitmap))
}

@Stable
class WallpaperState {
    var shown by mutableStateOf<WallpaperImage?>(null)
        private set
    var outgoing by mutableStateOf<WallpaperImage?>(null)
        private set
    private val fadeIn = Animatable(1f)
    val shownAlpha: Float get() = if (outgoing != null) fadeIn.value else 1f

    // The first photo appears at once: after a cold start it's the one that was already up
    suspend fun show(image: WallpaperImage) {
        val previous = shown
        shown = image
        if (previous == null) return
        outgoing = previous
        try {
            fadeIn.snapTo(0f)
            fadeIn.animateTo(1f, tween(CROSSFADE_MS))
        } finally {
            outgoing = null
        }
    }
}

@Composable
fun wallpaperSize(): IntSize {
    val configuration = LocalConfiguration.current
    return with(LocalDensity.current) {
        IntSize(configuration.screenWidthDp.dp.roundToPx(), (configuration.screenHeightDp.dp + ParallaxRange).roundToPx())
    }
}

// Behind the header, the title and the shelves. In the photo's coordinates, so they parallax with it and
// frostedWallpaper() can bake them in.
fun wallpaperScrims(color: Color): List<Brush> {
    // Fading through transparent black would turn a white scrim grey
    fun scrim(alpha: Float) = color.copy(alpha = alpha)
    return listOf(
        Brush.verticalGradient(0f to scrim(0.65f), 0.3f to scrim(0f)),
        Brush.horizontalGradient(0f to scrim(0.6f), 0.6f to scrim(0f)),
        Brush.verticalGradient(0.45f to scrim(0f), 1f to scrim(0.7f)),
    )
}

// fade: 0 at the top of the page, 1 once scrolled far enough to fully dim and lift the photo. Read only in
// graphicsLayer, so scrolling never re-records the photo.
@Composable
fun Wallpaper(state: WallpaperState, photoSize: IntSize, fade: () -> Float) {
    val scrim = LocalColors.current.scrim
    val scrims = remember(scrim) { wallpaperScrims(scrim) }
    val height = with(LocalDensity.current) { photoSize.height.toDp() }
    Box(Modifier.fillMaxSize()) {
        Spacer(
            Modifier
                .fillMaxWidth()
                .wrapContentHeight(Alignment.Top, unbounded = true)
                .height(height)
                .graphicsLayer { translationY = parallax(fade()) }
                .drawBehind {
                    val dst = IntSize(size.width.roundToInt(), size.height.roundToInt())
                    state.outgoing?.let { drawImage(it.image, dstSize = dst) }
                    state.shown?.let { drawImage(it.image, dstSize = dst, alpha = state.shownAlpha) }
                    scrims.forEach { drawRect(it) }
                }
        )
        Spacer(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    alpha = scrollScrim(fade())
                    compositingStrategy = CompositingStrategy.ModulateAlpha
                }
                .background(scrim)
        )
    }
}

// The glass over Home: Wallpaper()'s layers again, from the frosted copies
fun wallpaperGlass(state: WallpaperState, photoSize: IntSize, fade: () -> Float) = GlassSource { scope, origin, alpha, scrim ->
    val f = fade()
    val bounds = Rect(Offset(0f, scope.parallax(f)), photoSize.toSize())
    scope.drawRect(scrim, alpha = alpha)
    state.outgoing?.let { scope.drawGlassImage(it.frosted, bounds, origin, alpha) }
    state.shown?.let { scope.drawGlassImage(it.frosted, bounds, origin, alpha * state.shownAlpha) }
    scope.drawRect(scrim, alpha = alpha * scrollScrim(f))
}

private fun Density.parallax(fade: Float) = -ParallaxRange.toPx() * fade

private fun scrollScrim(fade: Float) = SCROLLED_SCRIM * fade

// The name gives way when space runs short; the credit, which the licence asks for, never does
@Composable
fun PhotoCredit(photo: Photo, modifier: Modifier = Modifier) {
    val style = LocalTextStyle.current.copy(color = LocalColors.current.textSecondary, fontSize = 13.sp)
    val credit = when {
        photo.credit.isBlank() -> ""
        photo.name.isBlank() -> photo.credit
        else -> " · ${photo.credit}"
    }
    Layout(
        content = {
            Text(photo.name, style = style, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(credit, style = style, maxLines = 1, softWrap = false)
        },
        modifier = modifier,
    ) { (name, tail), constraints ->
        val loose = constraints.copy(minWidth = 0)
        val tailPlaced = tail.measure(loose)
        val namePlaced = name.measure(loose.copy(maxWidth = (loose.maxWidth - tailPlaced.width).coerceAtLeast(0)))
        layout(namePlaced.width + tailPlaced.width, maxOf(namePlaced.height, tailPlaced.height)) {
            namePlaced.place(0, 0)
            tailPlaced.place(namePlaced.width, 0)
        }
    }
}
