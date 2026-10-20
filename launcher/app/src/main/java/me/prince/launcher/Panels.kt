package me.prince.launcher

import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
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

// Full screen over the live screen behind it, dimmed in the theme's scrim colour. Back dismisses.
@Composable
internal fun ScrimDialog(
    onDismiss: () -> Unit,
    contentAlignment: Alignment = Alignment.TopStart,
    content: @Composable BoxScope.() -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        // The theme's scrim does the dimming, so it's light by day rather than the platform's grey
        val window = (LocalView.current.parent as DialogWindowProvider).window
        SideEffect { window.setDimAmount(0f) }
        Box(
            Modifier.fillMaxSize().background(LocalColors.current.scrim.copy(alpha = 0.88f)),
            contentAlignment = contentAlignment,
            content = content,
        )
    }
}

@Composable
internal fun AppGridPanel(
    title: String,
    apps: List<TvApp>,
    tileWidth: Dp,
    tileHeight: Dp,
    shape: RoundedCornerShape,
    onLaunch: (TvApp) -> Unit,
    onMenu: (TvApp) -> Unit,
    onDismiss: () -> Unit,
) {
    val firstTile = remember { FocusRequester() }
    LaunchedEffect(Unit) { firstTile.requestFocusWhenAttached() }
    ScrimDialog(onDismiss) {
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
                        onLongClick = { onMenu(app) },
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
    onChange: (HomePrefs) -> Unit,
    onNextPhoto: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalColors.current
    val selectedAppearance = remember { FocusRequester() }
    LaunchedEffect(Unit) { selectedAppearance.requestFocusWhenAttached() }
    val is24h = DateFormat.is24HourFormat(LocalContext.current)
    ScrimDialog(onDismiss) {
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
