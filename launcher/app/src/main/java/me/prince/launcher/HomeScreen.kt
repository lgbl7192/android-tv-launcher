package me.prince.launcher

import android.content.Intent
import android.text.format.DateFormat
import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
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
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.ScrollState
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextAlign
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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.launch
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.unit.IntSize
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
// Favourites dock along the bottom of the first screen; tiles are sized from its width so exactly DOCK_COLUMNS fit
private const val DOCK_COLUMNS = 6
private val DockMargin = 40.dp
// Also the gap between tiles: a smaller gap would put the next tile inside the end padding, peeking
private val DockPadding = 20.dp
private val DockVerticalPadding = 18.dp
private val DockShape = RoundedCornerShape(28.dp)
private val DockBottomMargin = 28.dp
// The All apps grid, five columns
internal val GridTileWidth = 150.dp
internal val GridTileHeight = 84.dp
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
private const val WEATHER_RETRY_MS = 15_000L
private const val SPORTS_LIVE_REFRESH_MS = 60_000L
private const val SPORTS_REFRESH_MS = 15 * 60_000L
private const val SPORTS_RETRY_MS = 15_000L
// The pinned bar fades in over this much scroll
private val BarFadeDistance = 40.dp
// The wallpaper dims and lifts over this much scroll
private val WallpaperFadeDistance = 300.dp
private val ScoreCardWidth = 212.dp
private val ScoreCardHeight = 84.dp
private val LiveRed = Color(0xFFFF453A)

private data class MenuTarget(val app: TvApp, val canMove: Boolean)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    reloadKey: Int,
    homePresses: Int,
    resumes: Int,
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
    var panel by remember { mutableStateOf<Panel?>(null) }
    var movingId by remember { mutableStateOf<String?>(null) }
    var swallowKeyUp by remember { mutableStateOf<Key?>(null) }
    var refocus by remember { mutableIntStateOf(0) }
    val scroll = rememberScrollState()
    val favouriteRow = remember { LazyListState() }
    val firstTile = remember { FocusRequester() }
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
        if (!favouriteRow.isFullyVisible(to)) {
            // Focus stays on the moved tile but nothing scrolls it into view, so do it by hand
            val first = favouriteRow.firstVisibleItemIndex
            scope.launch { favouriteRow.animateScrollToItem(if (to <= first) to else first + 1) }
        }
        return true
    }

    LaunchedEffect(homePresses) {
        panel = null
        menu = null
    }
    LaunchedEffect(apps.isNotEmpty(), homePresses, refocus) {
        if (apps.isEmpty()) return@LaunchedEffect
        movingId = null
        scroll.animateScrollTo(0)
        favouriteRow.scrollToItem(0)
        // On cold start the tile may not be attached for a few frames; if focus is never set,
        // Android picks an arbitrary element (seen: the Settings pill)
        firstTile.requestFocusWhenAttached()
    }

    // Only the glow reads this, so moving focus recomposes just it
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

    var prefs by remember { mutableStateOf(loadHomePrefs(context)) }
    fun updatePrefs(changed: HomePrefs) {
        prefs = changed
        saveHomePrefs(context, changed)
    }
    val wallpaper = remember { WallpaperState() }
    val nextPhoto = remember { Channel<Unit>(Channel.CONFLATED) }
    var photo by remember { mutableStateOf<Photo?>(null) }
    LaunchedEffect(prefs) {
        if (prefs.wallpaper == WallpaperStyle.Plain) return@LaunchedEffect
        if (photo == null) photo = withContext(Dispatchers.IO) { savedWallpaper(context) }
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            var skip = false
            while (true) {
                val update = withContext(Dispatchers.IO) { updateWallpaper(context, prefs, skip) }
                update.photo?.let { photo = it }
                skip = withTimeoutOrNull(update.delayMs) { nextPhoto.receive() } != null
            }
        }
    }
    val photoSize = wallpaperSize()
    LaunchedEffect(photo, photoSize) {
        val shown = photo ?: return@LaunchedEffect
        withContext(Dispatchers.Default) { loadWallpaperImage(context, shown, photoSize) }?.let { wallpaper.show(it) }
    }
    val photoUp by remember { derivedStateOf { prefs.wallpaper == WallpaperStyle.Photos && wallpaper.shown != null } }

    val now = rememberNow()
    var location by remember { mutableStateOf(lastLocation(context)) }
    LaunchedEffect(resumes) { location = withContext(Dispatchers.IO) { lastLocation(context) } }
    val light by remember {
        derivedStateOf { isLight(prefs.appearance, location, now.value.atZone(ZoneId.systemDefault())) }
    }
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
    val verticalScrollSpec = remember(edgeMargin, barHeight) { minimalScroll(edgeMargin, topInset = barHeight.toFloat(), page = scroll) }
    val barFadeDistance = with(density) { BarFadeDistance.toPx() }
    val wallpaperFadeDistance = with(density) { WallpaperFadeDistance.toPx() }
    val wallpaperFade = { (scroll.value / wallpaperFadeDistance).coerceIn(0f, 1f) }
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
                    if (photoUp) Wallpaper(wallpaper, photoSize, wallpaperFade) else Ambient { shownApp()?.glow }
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
                                val onFocused = { app: TvApp -> focusedApp = app }
                                // The dock sits at the bottom of the first screen; the shelves below stay out of sight until focus moves down
                                Box(
                                    Modifier
                                        .fillMaxWidth()
                                        .height(LocalConfiguration.current.screenHeightDp.dp - with(density) { barHeight.toDp() }),
                                    contentAlignment = Alignment.BottomStart,
                                ) {
                                    Column(Modifier.padding(bottom = DockBottomMargin)) {
                                        NowPlayingSlot()
                                        MoveHint(moving = movingId != null)
                                        Dock(favourites, favouriteRow, firstTile, onLaunch, { menu = MenuTarget(it, canMove = true) }, onFocused)
                                    }
                                }
                                if (games.isNotEmpty()) {
                                    ShelfLabel("Sports")
                                    SportsShelf(games, onOpenGame)
                                }
                                if (recents.isNotEmpty()) {
                                    ShelfLabel("Recent")
                                    Shelf(recents, rememberLazyListState(), null, onLaunch, { menu = MenuTarget(it, canMove = false) }, onFocused)
                                }
                                Spacer(Modifier.height(40.dp))
                            }
                        }
                        TopBar(
                            alpha = { (scroll.value / barFadeDistance).coerceIn(0f, 1f) },
                            wallpaper = wallpaper.takeIf { photoUp },
                            photoSize = photoSize,
                            wallpaperFade = wallpaperFade,
                            shift = { shift },
                            onHeight = { barHeight = it },
                            below = page,
                        ) {
                            Header(
                                clock = { now.value },
                                onInputs = { panel = Panel.Inputs }.takeIf { inputs.isNotEmpty() },
                                onAllApps = { panel = Panel.AllApps },
                                allAppsFocus = firstTile.takeIf { favourites.isEmpty() },
                                onHomeScreen = { panel = Panel.HomeScreen },
                                onSettings = onSettings,
                                onPicture = onPicture,
                                onSound = onSound,
                            )
                        }
                    }
                }

                panel?.let { kind ->
                    val launchAndClose = { app: TvApp ->
                        onLaunch(app)
                        panel = null
                    }
                    when (kind) {
                        Panel.AllApps -> AppGridPanel(
                            "All apps", apps, GridTileWidth, GridTileHeight, FavouriteTileShape,
                            onLaunch = launchAndClose,
                            onMenu = { menu = MenuTarget(it, canMove = false) },
                            onDismiss = { panel = null },
                        )
                        Panel.Inputs -> AppGridPanel(
                            "Inputs", inputs, TileWidth, TileHeight, TileShape,
                            onLaunch = launchAndClose,
                            onMenu = {},
                            onDismiss = { panel = null },
                        )
                        Panel.HomeScreen -> HomeScreenPanel(
                            prefs = prefs,
                            daylight = daylight(location, now.value.toLocalDate(), ZoneId.systemDefault()),
                            onChange = ::updatePrefs,
                            onNextPhoto = { nextPhoto.trySend(Unit) },
                            onDismiss = { panel = null },
                        )
                    }
                }

                menu?.let { (app, canMove) ->
                    val isFavourite = app.packageName in favouriteIds
                    AppMenu(
                        app = app,
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
                        1f to color.copy(alpha = 0f),
                        center = Offset(size.width * 0.36f, size.height * 0.42f),
                        radius = size.width * 0.6f,
                    )
                )
                drawRect(Brush.verticalGradient(listOf(colors.scrim.copy(alpha = 0f), colors.scrim.copy(alpha = 0.55f))))
            }
    )
}

@Composable
private fun MoveHint(moving: Boolean) {
    if (!moving) return
    Text(
        "◀ ▶ to move, OK to finish",
        color = LocalColors.current.textSecondary,
        fontSize = 15.sp,
        modifier = Modifier.padding(start = DockMargin, bottom = 10.dp),
    )
}

// Pops up over the dock while a background app plays; its own composable so playback changes don't recompose the page
@Composable
private fun NowPlayingSlot() {
    val media by rememberNowPlaying()
    var last by remember { mutableStateOf<NowPlaying?>(null) }
    media?.let { last = it }
    AnimatedVisibility(
        visible = media != null,
        enter = fadeIn(tween(300)) + slideInVertically(tween(300)) { it / 3 },
        exit = fadeOut(tween(200)),
    ) {
        last?.let { NowPlayingCard(it, Modifier.padding(start = DockMargin, bottom = 12.dp)) }
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

@Composable
private fun AppMenu(
    app: TvApp,
    isFavourite: Boolean,
    canMove: Boolean,
    onDismiss: () -> Unit,
    onMove: () -> Unit,
    onToggleFavourite: () -> Unit,
    onAppInfo: () -> Unit,
) {
    val firstItem = remember { FocusRequester() }
    LaunchedEffect(Unit) { firstItem.requestFocusWhenAttached() }
    // The menu opens while OK is still held; its repeats and release would click the first item
    var okArmed by remember { mutableStateOf(false) }
    ScrimDialog(onDismiss, contentAlignment = Alignment.Center) {
        Column(
            Modifier
                .onPreviewKeyEvent {
                    if (it.key != Key.DirectionCenter && it.key != Key.Enter) return@onPreviewKeyEvent false
                    if (it.type == KeyEventType.KeyDown && it.nativeKeyEvent.repeatCount == 0) okArmed = true
                    !okArmed
                }
                .width(360.dp)
                .background(LocalColors.current.menuFill, MenuShape)
                .padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)) {
                Image(app.image, contentDescription = null, modifier = Modifier.size(56.dp, 32.dp).clip(RoundedCornerShape(6.dp)), contentScale = ContentScale.Crop)
                Spacer(Modifier.width(12.dp))
                Text(app.label, color = LocalColors.current.textPrimary, fontSize = 20.sp, fontFamily = UiDisplay, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            val itemModifier = Modifier.fillMaxWidth()
            // The same fill as the menu card they sit on
            if (canMove) Pill(null, "Move", onMove, itemModifier.focusRequester(firstItem), fillWidth = true, smoked = false)
            Pill(
                null,
                if (isFavourite) "Remove from favourites" else "Add to favourites",
                onToggleFavourite,
                if (canMove) itemModifier else itemModifier.focusRequester(firstItem),
                fillWidth = true,
                smoked = false,
            )
            Pill(null, "App info", onAppInfo, itemModifier, fillWidth = true, smoked = false)
        }
    }
}

// Compose's TV default pins the focused item 30% from the edge, which scrolls the header away.
// Scroll only when the item would leave the screen, keeping a margin so the focus scale isn't clipped.
@OptIn(ExperimentalFoundationApi::class)
private fun minimalScroll(margin: Float, topInset: Float = 0f, page: ScrollState? = null) = object : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        val start = offset - margin - topInset
        val end = offset + size + margin
        return when {
            // Anything on the first screen (the dock) takes the page back to the top, where the bar hides
            page != null && offset + page.value + size + margin <= containerSize -> -page.value.toFloat()
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
    onHomeScreen: () -> Unit,
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
            delay(if (value == null) WEATHER_RETRY_MS else WEATHER_REFRESH_MS)
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
                null,
                onAllApps,
                if (allAppsFocus != null) Modifier.focusRequester(allAppsFocus) else Modifier,
            )
            Pill(R.drawable.ic_wallpaper, null, onHomeScreen)
            onPicture?.let { Pill(R.drawable.ic_picture, null, it) }
            onSound?.let { Pill(R.drawable.ic_sound, null, it) }
            Pill(R.drawable.ic_settings, null, onSettings)
        }
    }
}

// Pinned over the page. Invisible at the top; once scrolled, the wallpaper (if any) under a flat tint, hiding the
// page behind it. Lambdas so scrolling only redraws the bar instead of recomposing it.
@Composable
private fun TopBar(
    alpha: () -> Float,
    wallpaper: WallpaperState?,
    photoSize: IntSize,
    wallpaperFade: () -> Float,
    shift: () -> IntOffset,
    onHeight: (Int) -> Unit,
    below: FocusRequester,
    content: @Composable () -> Unit,
) {
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
                drawRect(colors.background, alpha = a)
                wallpaper?.let { drawWallpaper(it, photoSize, wallpaperFade(), colors.scrim, a) }
                drawRect(colors.scrim, alpha = 0.25f * a)
                drawRect(colors.surfaceFill, alpha = 0.66f * a)
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

// Translucent capsule; focused it turns solid white with dark content, like tvOS buttons
@Composable
internal fun Pill(
    @DrawableRes icon: Int?,
    label: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    fillWidth: Boolean = false,
    smoked: Boolean = true,
) {
    var focused by remember { mutableStateOf(false) }
    val colors = LocalColors.current
    Surface(
        onClick = onClick,
        modifier = modifier.onFocusChanged { focused = it.isFocused },
        shape = ClickableSurfaceDefaults.shape(CircleShape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            contentColor = if (smoked) colors.onSmoke else colors.textPrimary,
            focusedContainerColor = Color.White,
            focusedContentColor = Color.Black,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.08f),
        glow = ClickableSurfaceDefaults.glow(focusedGlow = Glow(colors.focusGlow, 12.dp)),
    ) {
        Row(
            Modifier
                .then(if (fillWidth) Modifier.fillMaxWidth() else Modifier)
                .background(if (focused) Color.Transparent else if (smoked) colors.smokeFill else colors.surfaceFill, CircleShape)
                .padding(horizontal = if (label == null) 11.dp else 20.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(18.dp))
            }
            if (icon != null && label != null) Spacer(Modifier.width(8.dp))
            if (label != null) Text(label, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
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
) {
    if (apps.isEmpty()) return
    LazyRow(
        state = state,
        // Vertical room for the focus scale and glow
        contentPadding = PaddingValues(horizontal = EdgePadding, vertical = 22.dp),
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
                    .onFocusChanged { if (it.isFocused) onFocused(app) },
            )
        }
    }
}

// Translucent dock holding the favourites; it clips, so tiles scrolling past slide under its edge
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Dock(
    apps: List<TvApp>,
    state: LazyListState,
    firstTile: FocusRequester,
    onLaunch: (TvApp) -> Unit,
    onMenu: (TvApp) -> Unit,
    onFocused: (TvApp) -> Unit,
) {
    if (apps.isEmpty()) return
    // A margin of exactly the padding makes each scroll one whole slot, so the tiles rest aligned with no partial tile
    val scrollSpec = with(LocalDensity.current) { remember(this) { minimalScroll(DockPadding.toPx()) } }
    BoxWithConstraints(
        Modifier
            .padding(horizontal = DockMargin)
            .fillMaxWidth()
            .clip(DockShape)
            .background(LocalColors.current.smokeFill)
    ) {
        val tileWidth = (maxWidth - DockPadding * (DOCK_COLUMNS + 1)) / DOCK_COLUMNS
        val tileHeight = tileWidth * 9 / 16
        CompositionLocalProvider(LocalBringIntoViewSpec provides scrollSpec) {
            LazyRow(
                state = state,
                contentPadding = PaddingValues(horizontal = DockPadding, vertical = DockVerticalPadding),
                horizontalArrangement = Arrangement.spacedBy(DockPadding),
                modifier = Modifier.fillMaxWidth(),
            ) {
                itemsIndexed(apps, key = { _, app -> app.packageName }) { index, app ->
                    AppTile(
                        app = app,
                        onClick = { onLaunch(app) },
                        onLongClick = { onMenu(app) },
                        width = tileWidth,
                        height = tileHeight,
                        shape = FavouriteTileShape,
                        modifier = Modifier
                            .then(if (index == 0) Modifier.focusRequester(firstTile) else Modifier)
                            .onFocusChanged { if (it.isFocused) onFocused(app) },
                    )
                }
            }
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
            IconTileContent(app)
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
        horizontalArrangement = Arrangement.spacedBy(20.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        items(games, key = { it.id }) { game ->
            FocusTile(game.glow, ScoreCardWidth, ScoreCardHeight, TileShape, { game.launch?.let(onOpen) }, null, Modifier) {
                ScoreCard(game)
            }
        }
    }
}

// Clock or start time and the network on top, then away vs home facing each other
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
    val final = game.state == GameState.Final
    Row(Modifier.fillMaxSize().background(colors.surfaceFill)) {
        Box(
            Modifier
                .padding(vertical = 14.dp)
                .width(3.dp)
                .fillMaxHeight()
                .background(if (game.isMine) colors.textPrimary.copy(alpha = 0.85f) else Color.Transparent, CircleShape)
        )
        Column(Modifier.fillMaxSize().padding(start = 7.dp, end = 10.dp, top = 10.dp, bottom = 8.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (live) {
                    Box(Modifier.size(6.dp).background(LiveRed, CircleShape))
                    Spacer(Modifier.width(5.dp))
                }
                Text(
                    status,
                    color = if (live) colors.textPrimary else colors.textSecondary,
                    fontSize = 12.sp,
                    fontWeight = if (live) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                game.network?.let {
                    Text(" · $it", color = colors.textSecondary, fontSize = 12.sp, maxLines = 1, softWrap = false)
                }
            }
            Row(Modifier.fillMaxWidth().weight(1f), verticalAlignment = Alignment.CenterVertically) {
                TeamSide(game.away, logoFirst = true, dim = final && !game.away.winner)
                val away = game.away.score
                val home = game.home.score
                if (game.state != GameState.Upcoming && away != null && home != null) {
                    FittedScore(
                        buildAnnotatedString {
                            withStyle(SpanStyle(color = if (final && !game.away.winner) colors.textSecondary else colors.textPrimary)) { append(away) }
                            append("\u2009–\u2009")
                            withStyle(SpanStyle(color = if (final && !game.home.winner) colors.textSecondary else colors.textPrimary)) { append(home) }
                        },
                        Modifier.weight(1f),
                    )
                } else {
                    Text("vs", color = colors.textSecondary, fontSize = 14.sp, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
                }
                TeamSide(game.home, logoFirst = false, dim = final && !game.home.winner)
            }
        }
    }
}

// Largest size from 15sp down that fits between the teams; late basketball scores and 4-letter college teams fill the card
@Composable
private fun FittedScore(score: AnnotatedString, modifier: Modifier) {
    val measurer = rememberTextMeasurer()
    val base = LocalTextStyle.current.merge(TextStyle(fontWeight = FontWeight.SemiBold))
    BoxWithConstraints(modifier.padding(horizontal = 4.dp), contentAlignment = Alignment.Center) {
        val maxPx = constraints.maxWidth
        val size = remember(score, maxPx) {
            listOf(15, 14, 13, 12, 11).firstOrNull { sp ->
                measurer.measure(score, base.copy(fontSize = sp.sp), maxLines = 1, softWrap = false).size.width <= maxPx
            } ?: 11
        }
        Text(score, color = LocalColors.current.textSecondary, style = base.copy(fontSize = size.sp), maxLines = 1, softWrap = false)
    }
}

@Composable
private fun TeamSide(side: Side, logoFirst: Boolean, dim: Boolean) {
    val colors = LocalColors.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        val logo = @Composable {
            if (side.logo != null) {
                Image(side.logo, contentDescription = null, modifier = Modifier.size(24.dp))
            } else {
                Spacer(Modifier.size(24.dp))
            }
        }
        val abbr = @Composable {
            Text(
                side.abbr,
                color = if (dim) colors.textSecondary else colors.textPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
        }
        if (logoFirst) {
            logo()
            abbr()
        } else {
            abbr()
            logo()
        }
    }
}

// Inputs and apps without a TV banner: icon top-left, name and detail bottom-left on a translucent fill
@Composable
private fun IconTileContent(app: TvApp) {
    val colors = LocalColors.current
    Column(
        Modifier
            .fillMaxSize()
            .background(colors.surfaceFill)
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
