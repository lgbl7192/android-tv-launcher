package me.prince.launcher

import android.view.Window
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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

enum class Panel { AllApps, Inputs }

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
            val source = remember(backdrop, bounds) { backdrop?.let { GlassSource(it, { bounds }) } }
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
