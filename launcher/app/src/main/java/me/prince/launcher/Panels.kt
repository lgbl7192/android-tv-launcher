package me.prince.launcher

import android.text.format.DateFormat
import android.view.Window
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.tv.material3.Text
import java.time.format.DateTimeFormatter

enum class Panel { AllApps, Inputs, HomeScreen }

// Narrow enough for all five categories, ticked, on one line
private val SettingLabelWidth = 140.dp

// Full screen over a blurred capture of what was behind it, which also feeds the glass inside. Back dismisses.
@Composable
internal fun GlassDialog(
    backdrop: ImageBitmap?,
    onDismiss: () -> Unit,
    contentAlignment: Alignment = Alignment.TopStart,
    content: @Composable BoxScope.() -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = contentAlignment) {
            val bounds = with(LocalDensity.current) { Rect(0f, 0f, maxWidth.toPx(), maxHeight.toPx()) }
            if (backdrop != null) {
                Image(backdrop, contentDescription = null, contentScale = ContentScale.FillBounds, modifier = Modifier.fillMaxSize())
            }
            Box(Modifier.fillMaxSize().background(LocalColors.current.scrim.copy(alpha = 0.35f)))
            val source = remember(backdrop, bounds) {
                backdrop?.let { GlassSource { scope, origin, alpha, _ -> scope.drawGlassImage(it, bounds, origin, alpha) } }
            }
            CompositionLocalProvider(LocalGlassSource provides source) {
                content()
            }
        }
    }
}

// The long-press menu opens over this panel, so it gets the panel's own window to capture
@Composable
internal fun AppGridPanel(
    title: String,
    apps: List<TvApp>,
    tileWidth: Dp,
    tileHeight: Dp,
    shape: RoundedCornerShape,
    backdrop: ImageBitmap?,
    onLaunch: (TvApp) -> Unit,
    onMenu: (TvApp, Window) -> Unit,
    onDismiss: () -> Unit,
) {
    val firstTile = remember { FocusRequester() }
    LaunchedEffect(Unit) { firstTile.requestFocusWhenAttached() }
    GlassDialog(backdrop, onDismiss) {
        val window = (LocalView.current.parent as DialogWindowProvider).window
        Column(Modifier.fillMaxSize().padding(top = 48.dp)) {
            Text(
                title,
                color = LocalColors.current.textPrimary,
                fontSize = 34.sp,
                fontFamily = UiDisplay,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = EdgePadding),
            )
            LazyVerticalGrid(
                columns = GridCells.FixedSize(tileWidth),
                // Room for the focus scale and glow
                contentPadding = PaddingValues(horizontal = EdgePadding, vertical = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(24.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                itemsIndexed(apps, key = { _, app -> app.packageName }) { index, app ->
                    AppTile(
                        app = app,
                        onClick = { onLaunch(app) },
                        onLongClick = { onMenu(app, window) },
                        width = tileWidth,
                        height = tileHeight,
                        shape = shape,
                        modifier = if (index == 0) Modifier.focusRequester(firstTile) else Modifier,
                    )
                }
            }
        }
    }
}

@Composable
internal fun HomeScreenPanel(
    prefs: HomePrefs,
    daylight: Daylight,
    photo: Photo?,
    backdrop: ImageBitmap?,
    onChange: (HomePrefs) -> Unit,
    onNextPhoto: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalColors.current
    val selectedAppearance = remember { FocusRequester() }
    LaunchedEffect(Unit) { selectedAppearance.requestFocusWhenAttached() }
    val is24h = DateFormat.is24HourFormat(LocalContext.current)
    GlassDialog(backdrop, onDismiss) {
        Column(
            Modifier.fillMaxSize().padding(top = 48.dp).padding(horizontal = EdgePadding),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text(
                "Home screen",
                color = colors.textPrimary,
                fontSize = 34.sp,
                fontFamily = UiDisplay,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(bottom = 12.dp),
            )
            Setting("Appearance") {
                Appearance.entries.forEach { option ->
                    val selected = option == prefs.appearance
                    Choice(
                        option.label,
                        selected,
                        { onChange(prefs.copy(appearance = option)) },
                        if (selected) Modifier.focusRequester(selectedAppearance) else Modifier,
                    )
                }
                if (prefs.appearance == Appearance.Auto) {
                    Text(daylightLabel(daylight, is24h), color = colors.textSecondary, fontSize = 15.sp, modifier = Modifier.padding(start = 8.dp))
                }
            }
            Setting("Wallpaper") {
                WallpaperStyle.entries.forEach { style ->
                    Choice(style.label, style == prefs.wallpaper, { onChange(prefs.copy(wallpaper = style)) })
                }
            }
            if (prefs.wallpaper == WallpaperStyle.Photos) {
                Setting("Categories") {
                    WallpaperCategory.entries.forEach { category ->
                        Choice(category.label, category in prefs.categories, { onChange(prefs.toggle(category)) })
                    }
                }
                Setting("Change every") {
                    ChangeInterval.entries.forEach { interval ->
                        Choice(interval.label, interval == prefs.interval, { onChange(prefs.copy(interval = interval)) })
                    }
                }
                Setting("") {
                    Pill(null, "Next photo", onNextPhoto)
                    photo?.let { PhotoCredit(it, Modifier.padding(start = 8.dp)) }
                }
            }
        }
    }
}

@Composable
private fun Setting(label: String, content: @Composable RowScope.() -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            color = LocalColors.current.textSecondary,
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.width(SettingLabelWidth),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically, content = content)
    }
}

@Composable
private fun Choice(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Pill(R.drawable.ic_check.takeIf { selected }, label, onClick, modifier)
}

private fun daylightLabel(daylight: Daylight, is24h: Boolean): String = when (daylight) {
    is Daylight.Between -> {
        val format = DateTimeFormatter.ofPattern(if (is24h) "HH:mm" else "h:mm a")
        "Light ${daylight.sunrise.format(format).lowercase()} – ${daylight.sunset.format(format).lowercase()}"
    }
    Daylight.MidnightSun -> "Light all day"
    Daylight.PolarNight -> "Dark all day"
}
