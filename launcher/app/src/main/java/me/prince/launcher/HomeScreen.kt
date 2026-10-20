package me.prince.launcher

import androidx.compose.ui.graphics.ImageBitmap
import android.app.Activity
import android.view.Window
import android.content.Intent
import android.text.format.DateFormat
import androidx.annotation.DrawableRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.animation.core.tween
import androidx.tv.material3.Glow
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateIntOffsetAsState
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.IntOffset
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.collectLatest
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.LocalTextStyle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import androidx.tv.material3.darkColorScheme
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.onSizeChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal val TileWidth = 200.dp
internal val TileHeight = 112.dp
// Five across on a 960dp-wide screen, in two rows
internal val FavouriteTileWidth = 150.dp
internal val FavouriteTileHeight = 84.dp
private const val FAVOURITE_ROWS = 2
internal val TileShape = RoundedCornerShape(14.dp)
internal val FavouriteTileShape = RoundedCornerShape(12.dp)
// Left/right margin for text; rows use it as content padding so tiles can run off the screen edge
internal val EdgePadding = 56.dp

// OLED burn-in care: static UI drifts a few px, dims when idle and at night. Set to false on LCD TVs.
private const val OLED_CARE = true
private const val IDLE_DIM_MS = 60_000L
private const val IDLE_DIM = 0.65f
private const val NIGHT_DIM = 0.25f
private const val SHIFT_INTERVAL_MS = 120_000L
private val ShiftOrbit = listOf(0 to 0, 3 to 1, 4 to -2, 1 to -4, -3 to -3, -4 to 1, -1 to 4, 2 to 3)
private val MenuShape = RoundedCornerShape(28.dp)
private const val RECENT_COUNT = 8
private const val SHEEN_MS = 700
private const val WEATHER_REFRESH_MS = 30 * 60_000L
private const val SPORTS_LIVE_REFRESH_MS = 60_000L
private const val SPORTS_REFRESH_MS = 15 * 60_000L
private const val SPORTS_RETRY_MS = 15_000L
// The pinned bar fades in over this much scroll
private val BarFadeDistance = 40.dp
private val ScoreCardWidth = 240.dp
private val LiveRed = Color(0xFFFF453A)

private data class MenuTarget(val app: TvApp, val canMove: Boolean, val backdrop: ImageBitmap?)
private data class OpenPanel(val panel: Panel, val backdrop: ImageBitmap?)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    reloadKey: Int,
    homePresses: Int,
    resumes: Int,
    light: Boolean,
    onLaunch: (TvApp) -> Unit,
    onAppInfo: (TvApp) -> Unit,
    onSettings: () -> Unit,
    // Null hides the button (no working settings screen on this TV)
    onPicture: (() -> Unit)?,
    onSound: (() -> Unit)?,
    onOpenGame: (Intent) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var apps by remember { mutableStateOf(emptyList<TvApp>()) }
    var favouriteIds by remember { mutableStateOf(loadFavourites(context)) }
    var recentIds by remember { mutableStateOf(emptyList<String>()) }
    var inputs by remember { mutableStateOf(emptyList<TvApp>()) }
    var focusedApp by remember { mutableStateOf<TvApp?>(null) }
    var menu by remember { mutableStateOf<MenuTarget?>(null) }
    var panel by remember { mutableStateOf<OpenPanel?>(null) }
    val window = (context as Activity).window
    // Capture and blur the screen first so menus and panels open over frosted glass
    fun openMenu(app: TvApp, canMove: Boolean, from: Window = window) {
        captureBlurredWindow(from) { menu = MenuTarget(app, canMove, it) }
    }
    fun openPanel(kind: Panel) {
        captureBlurredWindow(window) { panel = OpenPanel(kind, it) }
    }
    var movingId by remember { mutableStateOf<String?>(null) }
    var swallowKeyUp by remember { mutableStateOf<Key?>(null) }
    var refocus by remember { mutableIntStateOf(0) }
    val scroll = rememberScrollState()
    val favouriteRows = remember { List(FAVOURITE_ROWS) { LazyListState() } }
    val firstTile = remember { FocusRequester() }
    val movingTile = remember { FocusRequester() }
    val page = remember { FocusRequester() }

    LaunchedEffect(reloadKey) {
        apps = withContext(Dispatchers.IO) { loadApps(context) }
    }
    LaunchedEffect(resumes) {
        recentIds = withContext(Dispatchers.IO) { recentPackages(context) }
        inputs = withContext(Dispatchers.IO) { loadInputs(context) }
    }

    val byPackage = remember(apps) { apps.associateBy { it.packageName } }
    val favourites = favouriteIds.mapNotNull { byPackage[it] }
    // Row-major: the first half on top, the rest below
    val favouritesPerRow = ((favourites.size + FAVOURITE_ROWS - 1) / FAVOURITE_ROWS).coerceAtLeast(1)
    // Favourites are already one row up
    val recents = recentIds.filter { it !in favouriteIds }.mapNotNull { byPackage[it] }.take(RECENT_COUNT)

    fun updateFavourites(ids: List<String>) {
        favouriteIds = ids
        saveFavourites(context, ids)
    }

    // Move mode: left/right shifts the tile, OK or Back finishes
    fun handleMoveKey(key: Key): Boolean {
        val id = movingId ?: return false
        val from = favourites.indexOfFirst { it.packageName == id }
        val to = when (key) {
            Key.DirectionLeft -> from - 1
            Key.DirectionRight -> from + 1
            Key.DirectionCenter, Key.Enter, Key.Back, Key.Escape -> {
                movingId = null
                // Otherwise the release reaches the tile and launches the app
                swallowKeyUp = key
                return true
            }
            else -> return true
        }
        if (from < 0 || to !in favourites.indices) return true
        updateFavourites(favourites.map { it.packageName }.toMutableList().apply { add(to, removeAt(from)) })
        val row = favouriteRows[to / favouritesPerRow]
        val index = to % favouritesPerRow
        if (from / favouritesPerRow != to / favouritesPerRow) {
            // Crossing into the other row recomposes the tile there, which drops focus
            scope.launch {
                row.scrollToItem(index)
                movingTile.requestFocusWhenAttached()
            }
        } else if (!row.isFullyVisible(index)) {
            // Focus stays on the moved tile but nothing scrolls it into view, so do it by hand
            val first = row.firstVisibleItemIndex
            scope.launch { row.animateScrollToItem(if (index <= first) index else first + 1) }
        }
        return true
    }

    LaunchedEffect(apps.isNotEmpty(), homePresses, refocus) {
        if (apps.isEmpty()) return@LaunchedEffect
        movingId = null
        scroll.animateScrollTo(0)
        favouriteRows.forEach { it.scrollToItem(0) }
        // On cold start the tile may not be attached for a few frames; if focus is never set,
        // Android picks an arbitrary element (seen: the Settings pill)
        firstTile.requestFocusWhenAttached()
    }

    // Only the title and the glow read this, so moving focus recomposes just them
    val shownApp = { focusedApp ?: favourites.firstOrNull() ?: apps.firstOrNull() }

    var games by remember { mutableStateOf(emptyList<Game>()) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(Unit) {
        // Only while Home is on screen: no polling while something else is playing
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            var loaded = false
            while (true) {
                val fresh = withContext(Dispatchers.IO) { fetchGames(context.packageManager) }
                if (fresh != null) {
                    games = fresh
                    loaded = true
                }
                delay(
                    when {
                        !loaded -> SPORTS_RETRY_MS
                        games.any { it.state == GameState.Live } -> SPORTS_LIVE_REFRESH_MS
                        else -> SPORTS_REFRESH_MS
                    }
                )
            }
        }
    }

    val now = rememberNow()
    var keyPresses by remember { mutableIntStateOf(0) }
    var idle by remember { mutableStateOf(false) }
    LaunchedEffect(resumes) {
        // Read here rather than as an effect key, so a key press doesn't recompose the whole screen
        snapshotFlow { keyPresses }.collectLatest {
            idle = false
            delay(IDLE_DIM_MS)
            idle = true
        }
    }
    val night by remember { derivedStateOf { now.value.hour >= 22 || now.value.hour < 6 } }
    val idleDim = if (OLED_CARE && idle) IDLE_DIM else 0f
    val nightDim = if (OLED_CARE && night) NIGHT_DIM else 0f
    val dim by animateFloatAsState(
        targetValue = 1 - (1 - idleDim) * (1 - nightDim),
        animationSpec = tween(if (idle) 3000 else 250),
        label = "dim",
    )

    val density = LocalDensity.current
    val shiftStep by produceState(resumes, resumes) {
        while (true) {
            delay(SHIFT_INTERVAL_MS)
            value++
        }
    }
    val (shiftX, shiftY) = if (OLED_CARE) ShiftOrbit[shiftStep % ShiftOrbit.size] else (0 to 0)
    val shift by animateIntOffsetAsState(
        with(density) { IntOffset(shiftX.dp.roundToPx(), shiftY.dp.roundToPx()) },
        tween(2000),
        label = "shift",
    )

    var barHeight by remember { mutableIntStateOf(0) }
    val edgeMargin = with(density) { 40.dp.toPx() }
    val rowScrollSpec = remember(edgeMargin) { minimalScroll(edgeMargin) }
    // Focused rows stop below the pinned bar instead of sliding under it
    val verticalScrollSpec = remember(edgeMargin, barHeight) { minimalScroll(edgeMargin, topInset = barHeight.toFloat()) }
    val barFadeDistance = with(density) { BarFadeDistance.toPx() }

    MaterialTheme(colorScheme = darkColorScheme()) {
    LauncherTheme(light) {
        CompositionLocalProvider(
            LocalBringIntoViewSpec provides rowScrollSpec,
            LocalTextStyle provides LocalTextStyle.current.copy(fontFamily = UiText),
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .onPreviewKeyEvent {
                        val down = it.type == KeyEventType.KeyDown
                        if (down) keyPresses++
                        when {
                            !down && it.key == swallowKeyUp -> {
                                swallowKeyUp = null
                                true
                            }
                            // Swallow key-ups too while moving, e.g. the OK release from the menu
                            movingId != null -> !down || handleMoveKey(it.key)
                            else -> false
                        }
                    }
                    .drawWithContent {
                        drawContent()
                        if (dim > 0f) drawRect(Color.Black.copy(alpha = dim))
                    }
            ) {
                Ambient { shownApp()?.glow }
                CompositionLocalProvider(LocalBringIntoViewSpec provides verticalScrollSpec) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .offset { shift }
                        .focusRequester(page)
                        .verticalScroll(scroll)
                ) {
                CompositionLocalProvider(LocalBringIntoViewSpec provides rowScrollSpec) {
                    Spacer(Modifier.height(with(density) { barHeight.toDp() }))
                    Spacer(Modifier.height(118.dp))
                    AppTitle({ shownApp()?.label.orEmpty() }, moving = movingId != null)
                    val onFocused = { app: TvApp -> focusedApp = app }
                    favourites.chunked(favouritesPerRow).forEachIndexed { row, rowApps ->
                        Shelf(
                            rowApps,
                            favouriteRows[row],
                            firstTile.takeIf { row == 0 },
                            onLaunch,
                            { openMenu(it, canMove = true) },
                            onFocused,
                            FavouriteTileWidth,
                            FavouriteTileHeight,
                            FavouriteTileShape,
                            // Closer together than separate shelves, still room for the focus scale and glow
                            verticalPadding = 12.dp,
                            movingId = movingId,
                            movingTile = movingTile,
                        )
                    }
                    if (games.isNotEmpty()) {
                        ShelfLabel("Sports")
                        SportsShelf(games, onOpenGame)
                    }
                    if (recents.isNotEmpty()) {
                        ShelfLabel("Recent")
                        Shelf(recents, rememberLazyListState(), null, onLaunch, { openMenu(it, canMove = false) }, onFocused)
                    }
                    Spacer(Modifier.height(40.dp))
                }
                }
                }
                TopBar(
                    alpha = { (scroll.value / barFadeDistance).coerceIn(0f, 1f) },
                    shift = { shift },
                    onHeight = { barHeight = it },
                    below = page,
                ) {
                    Header(
                        clock = { now.value },
                        onInputs = { openPanel(Panel.Inputs) }.takeIf { inputs.isNotEmpty() },
                        onAllApps = { openPanel(Panel.AllApps) },
                        allAppsFocus = firstTile.takeIf { favourites.isEmpty() },
                        onSettings = onSettings,
                        onPicture = onPicture,
                        onSound = onSound,
                    )
                }
            }
        }

        panel?.let { (kind, backdrop) ->
            val launchAndClose = { app: TvApp ->
                onLaunch(app)
                panel = null
            }
            when (kind) {
                Panel.AllApps -> AppGridPanel(
                    "All apps", apps, FavouriteTileWidth, FavouriteTileHeight, FavouriteTileShape, backdrop,
                    onLaunch = launchAndClose,
                    onMenu = { app, from -> openMenu(app, canMove = false, from) },
                    onDismiss = { panel = null },
                )
                Panel.Inputs -> AppGridPanel(
                    "Inputs", inputs, TileWidth, TileHeight, TileShape, backdrop,
                    onLaunch = launchAndClose,
                    onMenu = { _, _ -> },
                    onDismiss = { panel = null },
                )
            }
        }

        menu?.let { (app, canMove, backdrop) ->
            val isFavourite = app.packageName in favouriteIds
            AppMenu(
                app = app,
                backdrop = backdrop,
                isFavourite = isFavourite,
                canMove = canMove,
                onDismiss = { menu = null },
                onMove = {
                    menu = null
                    movingId = app.packageName
                },
                onToggleFavourite = {
                    menu = null
                    updateFavourites(if (isFavourite) favouriteIds - app.packageName else favouriteIds + app.packageName)
                    // The focused tile just jumped rows (or vanished); start again from the first tile
                    refocus++
                },
                onAppInfo = {
                    menu = null
                    onAppInfo(app)
                },
            )
        }
    }
    }
}

private fun LazyListState.isFullyVisible(index: Int): Boolean {
    val info = layoutInfo
    val item = info.visibleItemsInfo.firstOrNull { it.index == index } ?: return false
    return item.offset >= 0 && item.offset + item.size <= info.viewportEndOffset - info.afterContentPadding
}

// Retries for a few frames: freshly composed tiles aren't attached immediately
internal suspend fun FocusRequester.requestFocusWhenAttached() {
    repeat(30) {
        withFrameNanos { }
        try {
            requestFocus()
            return
        } catch (e: IllegalStateException) {
            // not attached yet, try next frame
        }
    }
}

// Background, the focused app's colour glowing behind the shelves, and a scrim along the bottom
@Composable
private fun Ambient(glow: () -> Color?) {
    val colors = LocalColors.current
    val color by animateColorAsState(glow() ?: colors.background, tween(600), label = "glow")
    Box(
        Modifier
            .fillMaxSize()
            .drawBehind {
                drawRect(colors.background)
                drawRect(
                    Brush.radialGradient(
                        0f to color.copy(alpha = 0.9f * colors.glowStrength),
                        0.45f to color.copy(alpha = 0.3f * colors.glowStrength),
                        1f to Color.Transparent,
                        center = Offset(size.width * 0.36f, size.height * 0.42f),
                        radius = size.width * 0.6f,
                    )
                )
                drawRect(Brush.verticalGradient(listOf(Color.Transparent, colors.scrim.copy(alpha = 0.55f))))
            }
    )
}

@Composable
private fun AppTitle(label: () -> String, moving: Boolean) {
    val colors = LocalColors.current
    Row(Modifier.padding(horizontal = EdgePadding), verticalAlignment = Alignment.Bottom) {
        Text(
            text = label(),
            color = colors.textPrimary,
            fontSize = 44.sp,
            fontFamily = UiDisplay,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (moving) {
            Spacer(Modifier.width(20.dp))
            Text(
                "◀ ▶ to move, OK to finish",
                color = colors.textSecondary,
                fontSize = 15.sp,
                modifier = Modifier.padding(bottom = 10.dp),
            )
        }
    }
}

@Composable
private fun ShelfLabel(text: String) {
    Text(
        text,
        color = LocalColors.current.textPrimary.copy(alpha = 0.92f),
        fontSize = 19.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = EdgePadding, top = 14.dp),
    )
}

// Opens over a blurred capture of the screen, like tvOS context menus
@Composable
private fun AppMenu(
    app: TvApp,
    backdrop: ImageBitmap?,
    isFavourite: Boolean,
    canMove: Boolean,
    onDismiss: () -> Unit,
    onMove: () -> Unit,
    onToggleFavourite: () -> Unit,
    onAppInfo: () -> Unit,
) {
    val firstItem = remember { FocusRequester() }
    LaunchedEffect(Unit) { firstItem.requestFocusWhenAttached() }
    GlassDialog(backdrop, onDismiss, contentAlignment = Alignment.Center) {
        Column(
            Modifier
                .width(360.dp)
                .glass(MenuShape)
                .padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)) {
                Image(app.image, contentDescription = null, modifier = Modifier.size(56.dp, 32.dp).clip(RoundedCornerShape(6.dp)), contentScale = ContentScale.Crop)
                Spacer(Modifier.width(12.dp))
                Text(app.label, color = LocalColors.current.textPrimary, fontSize = 20.sp, fontFamily = UiDisplay, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            val itemModifier = Modifier.fillMaxWidth()
            if (canMove) Pill(null, "Move", onMove, itemModifier.focusRequester(firstItem), fillWidth = true)
            Pill(
                null,
                if (isFavourite) "Remove from favourites" else "Add to favourites",
                onToggleFavourite,
                if (canMove) itemModifier else itemModifier.focusRequester(firstItem),
                fillWidth = true,
            )
            Pill(null, "App info", onAppInfo, itemModifier, fillWidth = true)
        }
    }
}

// Compose's TV default pins the focused item 30% from the edge, which scrolls the header away.
// Scroll only when the item would leave the screen, keeping a margin so the focus scale isn't clipped.
@OptIn(ExperimentalFoundationApi::class)
private fun minimalScroll(margin: Float, topInset: Float = 0f) = object : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        val start = offset - margin - topInset
        val end = offset + size + margin
        return when {
            start < 0 -> start
            end > containerSize -> end - containerSize
            else -> 0f
        }
    }
}

// Ticks on the minute boundary
@Composable
private fun rememberNow(): State<LocalDateTime> = produceState(LocalDateTime.now()) {
    while (true) {
        value = LocalDateTime.now()
        delay(60_000 - System.currentTimeMillis() % 60_000)
    }
}

// Null callbacks hide their pill
@Composable
private fun Header(
    clock: () -> LocalDateTime,
    onInputs: (() -> Unit)?,
    onAllApps: () -> Unit,
    allAppsFocus: FocusRequester?,
    onSettings: () -> Unit,
    onPicture: (() -> Unit)?,
    onSound: (() -> Unit)?,
) {
    val now = clock()
    val colors = LocalColors.current
    val context = LocalContext.current
    val is24h = DateFormat.is24HourFormat(context)
    val weather by produceState<Weather?>(null) {
        while (true) {
            value = withContext(Dispatchers.IO) { fetchWeather(context) } ?: value
            delay(WEATHER_REFRESH_MS)
        }
    }

    Row(Modifier.fillMaxWidth().padding(horizontal = EdgePadding), verticalAlignment = Alignment.CenterVertically) {
        Row {
            Text(
                now.format(DateTimeFormatter.ofPattern(if (is24h) "HH:mm" else "h:mm")),
                color = colors.textPrimary,
                fontSize = 54.sp,
                fontFamily = UiDisplay,
                fontWeight = FontWeight.Light,
                modifier = Modifier.alignByBaseline(),
            )
            if (!is24h) {
                Text(
                    " " + now.format(DateTimeFormatter.ofPattern("a")).uppercase(),
                    color = colors.textSecondary,
                    fontSize = 18.sp,
                    modifier = Modifier.alignByBaseline(),
                )
            }
        }
        Spacer(Modifier.width(20.dp))
        // Gives way to the pills on TVs that show all six
        Column(Modifier.weight(1f)) {
            Text(greeting(now.hour), color = colors.textPrimary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            val weatherText = weather?.let { "  ·  ${it.temperature}° ${it.summary}" }.orEmpty()
            Text(
                now.format(DateTimeFormatter.ofPattern("EEEE d MMMM")) + weatherText,
                color = colors.textSecondary,
                fontSize = 15.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            onInputs?.let { Pill(R.drawable.ic_input, "Inputs", it) }
            Pill(
                R.drawable.ic_apps,
                "All apps",
                onAllApps,
                if (allAppsFocus != null) Modifier.focusRequester(allAppsFocus) else Modifier,
            )
            onPicture?.let { Pill(R.drawable.ic_picture, null, it) }
            onSound?.let { Pill(R.drawable.ic_sound, null, it) }
            Pill(R.drawable.ic_settings, "Settings", onSettings)
        }
    }
}

// Pinned over the page. Invisible at the top; once scrolled, frosted glass over whatever the glass source is.
// Lambdas so scrolling only redraws the bar instead of recomposing it.
@Composable
private fun TopBar(
    alpha: () -> Float,
    shift: () -> IntOffset,
    onHeight: (Int) -> Unit,
    below: FocusRequester,
    content: @Composable () -> Unit,
) {
    val source = LocalGlassSource.current
    val colors = LocalColors.current
    Column(
        Modifier
            .fillMaxWidth()
            // The page scrolls behind the bar, so its focus group starts above the pills and Down search never picks it
            .onKeyEvent {
                if (it.type != KeyEventType.KeyDown || it.key != Key.DirectionDown) return@onKeyEvent false
                below.requestFocus()
                true
            }
            .onSizeChanged { onHeight(it.height) }
            .drawBehind {
                val a = alpha()
                if (a == 0f) return@drawBehind
                // Hide the sharp page underneath first
                drawRect(colors.background, alpha = a)
                source?.let { drawGlassSource(it, Offset.Zero, a) }
                drawRect(colors.scrim, alpha = 0.25f * a)
                drawRect(colors.glassFill, alpha = 0.66f * a)
                val edge = 1.dp.toPx()
                drawRect(colors.divider, topLeft = Offset(0f, size.height - edge), size = Size(size.width, edge), alpha = a)
            }
            .offset { shift() }
    ) {
        Spacer(Modifier.height(36.dp))
        content()
        Spacer(Modifier.height(14.dp))
    }
}

private fun greeting(hour: Int): String = when (hour) {
    in 5..11 -> "Good morning"
    in 12..16 -> "Good afternoon"
    else -> "Good evening"
}

// Glass capsule; focused it turns solid white with dark content, like tvOS buttons
@Composable
internal fun Pill(
    @DrawableRes icon: Int?,
    label: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    fillWidth: Boolean = false,
) {
    var focused by remember { mutableStateOf(false) }
    val colors = LocalColors.current
    Surface(
        onClick = onClick,
        modifier = modifier.onFocusChanged { focused = it.isFocused },
        shape = ClickableSurfaceDefaults.shape(CircleShape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            contentColor = colors.textPrimary,
            focusedContainerColor = Color.White,
            focusedContentColor = Color.Black,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.08f),
        glow = ClickableSurfaceDefaults.glow(focusedGlow = Glow(colors.focusGlow, 12.dp)),
    ) {
        Row(
            Modifier
                .then(if (fillWidth) Modifier.fillMaxWidth() else Modifier)
                .glass(CircleShape, enabled = !focused)
                .padding(horizontal = if (label == null) 11.dp else 20.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(18.dp))
            }
            if (icon != null && label != null) Spacer(Modifier.width(8.dp))
            if (label != null) Text(label, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

// No container: tiles float and run off the screen edge, like tvOS rows
@Composable
private fun Shelf(
    apps: List<TvApp>,
    state: LazyListState,
    firstTile: FocusRequester?,
    onLaunch: (TvApp) -> Unit,
    onMenu: (TvApp) -> Unit,
    onFocused: (TvApp) -> Unit,
    tileWidth: Dp = TileWidth,
    tileHeight: Dp = TileHeight,
    shape: RoundedCornerShape = TileShape,
    verticalPadding: Dp = 22.dp,
    movingId: String? = null,
    movingTile: FocusRequester? = null,
) {
    if (apps.isEmpty()) return
    LazyRow(
        state = state,
        // Vertical room for the focus scale and glow
        contentPadding = PaddingValues(horizontal = EdgePadding, vertical = verticalPadding),
        horizontalArrangement = Arrangement.spacedBy(24.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        itemsIndexed(apps, key = { _, app -> app.packageName }) { index, app ->
            val requester = if (index == 0) firstTile else null
            AppTile(
                app = app,
                onClick = { onLaunch(app) },
                onLongClick = { onMenu(app) },
                width = tileWidth,
                height = tileHeight,
                shape = shape,
                modifier = Modifier
                    .then(if (requester != null) Modifier.focusRequester(requester) else Modifier)
                    .then(if (movingTile != null && app.packageName == movingId) Modifier.focusRequester(movingTile) else Modifier)
                    .onFocusChanged { if (it.isFocused) onFocused(app) },
            )
        }
    }
}

@Composable
internal fun AppTile(
    app: TvApp,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    width: Dp,
    height: Dp,
    shape: RoundedCornerShape,
    modifier: Modifier,
) {
    FocusTile(app.glow, width, height, shape, onClick, onLongClick, modifier) {
        if (app.hasBanner) {
            Image(app.image, contentDescription = app.label, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            GlassTileContent(app, shape)
        }
    }
}

// Focus lifts the tile (scale, glow in the given colour, light rim) instead of drawing a thick border
@Composable
private fun FocusTile(
    glow: Color,
    width: Dp,
    height: Dp,
    shape: RoundedCornerShape,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
    modifier: Modifier,
    content: @Composable () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val colors = LocalColors.current
    val sheen = remember { Animatable(1f) }
    LaunchedEffect(focused) {
        if (focused) {
            sheen.snapTo(0f)
            sheen.animateTo(1f, tween(SHEEN_MS, easing = FastOutSlowInEasing))
        }
    }
    Surface(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier
            .onFocusChanged { focused = it.isFocused }
            .size(width, height),
        shape = ClickableSurfaceDefaults.shape(shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            focusedContainerColor = Color.Transparent,
        ),
        border = ClickableSurfaceDefaults.border(
            border = Border(BorderStroke(1.dp, colors.tileBorder), shape = shape),
            focusedBorder = Border(BorderStroke(1.5.dp, colors.tileBorderFocused), shape = shape),
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.1f),
        glow = ClickableSurfaceDefaults.glow(focusedGlow = Glow(glow.copy(alpha = 0.55f), 20.dp)),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .drawWithContent {
                    drawContent()
                    // Soft glare from the top-left while focused, plus a band that sweeps across once
                    if (focused) {
                        drawRect(
                            Brush.linearGradient(
                                0f to Color.White.copy(alpha = 0.16f),
                                0.45f to Color.Transparent,
                                end = Offset(size.width * 0.7f, size.height),
                            )
                        )
                    }
                    if (sheen.value < 1f) {
                        val band = size.width * 0.6f
                        val x = -band + (size.width + 2 * band) * sheen.value
                        drawRect(
                            Brush.linearGradient(
                                listOf(Color.Transparent, Color.White.copy(alpha = 0.28f), Color.Transparent),
                                start = Offset(x - band / 2, 0f),
                                end = Offset(x + band / 2, size.height),
                            )
                        )
                    }
                }
        ) {
            content()
        }
    }
}

@Composable
private fun SportsShelf(games: List<Game>, onOpen: (Intent) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = EdgePadding, vertical = 22.dp),
        horizontalArrangement = Arrangement.spacedBy(24.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        items(games, key = { it.id }) { game ->
            FocusTile(game.glow, ScoreCardWidth, TileHeight, TileShape, { game.launch?.let(onOpen) }, null, Modifier) {
                ScoreCard(game)
            }
        }
    }
}

// Away team over home team, then the clock or start time and the network
@Composable
private fun ScoreCard(game: Game) {
    val colors = LocalColors.current
    val context = LocalContext.current
    val live = game.state == GameState.Live
    val status = when (game.state) {
        GameState.Upcoming -> {
            val start = game.start.atZone(ZoneId.systemDefault())
            val time = if (DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm a"
            val pattern = if (start.toLocalDate() == LocalDate.now()) time else "EEE $time"
            start.format(DateTimeFormatter.ofPattern(pattern))
        }
        else -> game.detail
    }
    Row(Modifier.fillMaxSize().glass(TileShape)) {
        Box(
            Modifier
                .padding(vertical = 18.dp)
                .width(3.dp)
                .fillMaxHeight()
                .background(if (game.isMine) colors.textPrimary.copy(alpha = 0.85f) else Color.Transparent, CircleShape)
        )
        Column(Modifier.fillMaxSize().padding(start = 11.dp, end = 14.dp, top = 12.dp, bottom = 10.dp)) {
            val showScore = game.state != GameState.Upcoming
            TeamLine(game.away, showScore, dim = game.state == GameState.Final && !game.away.winner)
            Spacer(Modifier.height(4.dp))
            TeamLine(game.home, showScore, dim = game.state == GameState.Final && !game.home.winner)
            Spacer(Modifier.weight(1f))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (live) {
                    Box(Modifier.size(7.dp).background(LiveRed, CircleShape))
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    status,
                    color = if (live) colors.textPrimary else colors.textSecondary,
                    fontSize = 13.sp,
                    fontWeight = if (live) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                game.network?.let {
                    Text(it, color = colors.textSecondary, fontSize = 12.sp, maxLines = 1, modifier = Modifier.padding(start = 8.dp))
                }
            }
        }
    }
}

@Composable
private fun TeamLine(side: Side, showScore: Boolean, dim: Boolean) {
    val colors = LocalColors.current
    val color = if (dim) colors.textSecondary else colors.textPrimary
    Row(Modifier.height(26.dp), verticalAlignment = Alignment.CenterVertically) {
        if (side.logo != null) {
            Image(side.logo, contentDescription = null, modifier = Modifier.size(24.dp))
        } else {
            Spacer(Modifier.size(24.dp))
        }
        Spacer(Modifier.width(10.dp))
        Text(side.abbr, color = color, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        Spacer(Modifier.weight(1f))
        if (showScore && side.score != null) {
            Text(side.score, color = color, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

// Inputs and apps without a TV banner: icon top-left, name and detail bottom-left on glass
@Composable
private fun GlassTileContent(app: TvApp, shape: RoundedCornerShape) {
    val colors = LocalColors.current
    Column(
        Modifier
            .fillMaxSize()
            .glass(shape)
            .padding(16.dp)
    ) {
        Image(app.image, contentDescription = null, modifier = Modifier.size(30.dp))
        Spacer(Modifier.weight(1f))
        Text(app.label, color = colors.textPrimary, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (app.detail != null) {
            Text(app.detail, color = colors.textSecondary, fontSize = 13.sp, maxLines = 1)
        }
    }
}