package me.prince.launcher

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.util.Log
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.tv.material3.Text
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

private const val TAG = "Media"
private const val ART_PX = 128
private val CardShape = RoundedCornerShape(24.dp)
private val ArtShape = RoundedCornerShape(10.dp)
private val NowPlayingWidth = 460.dp

// Never does anything itself: being an enabled notification listener is what lets the launcher read other apps'
// media sessions. Enabled over adb with `cmd notification allow_listener`.
class MediaListener : NotificationListenerService()

class NowPlaying(
    val controller: MediaController,
    val title: String,
    val artist: String,
    val art: ImageBitmap?,
    val playing: Boolean,
    private val state: PlaybackState?,
    private val duration: Long,
) {
    // 0..1, or null when the app gives no duration
    fun progress(): Float? {
        val s = state ?: return null
        if (duration <= 0) return null
        val elapsed = if (playing) ((SystemClock.elapsedRealtime() - s.lastPositionUpdateTime) * s.playbackSpeed).toLong() else 0L
        return ((s.position + elapsed).toFloat() / duration).coerceIn(0f, 1f)
    }
}

// The session playing now, or the one we were showing until it's paused, while Home is started
@Composable
fun rememberNowPlaying(): State<NowPlaying?> {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    return produceState<NowPlaying?>(null) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            nowPlaying(context).collect { value = it }
        }
    }
}

private fun nowPlaying(context: Context): Flow<NowPlaying?> = callbackFlow {
    val watcher = SessionWatcher(context) { trySend(it) }
    watcher.start()
    awaitClose { watcher.stop() }
}

private class SessionWatcher(private val context: Context, private val onChange: (NowPlaying?) -> Unit) {
    private val manager = context.getSystemService(MediaSessionManager::class.java)
    private val component = ComponentName(context, MediaListener::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var controllers = emptyList<MediaController>()
    // Stays up while paused from the card, so its play button still works
    private var shown: String? = null
    private var artSource: Bitmap? = null
    private var coverTrack: String? = null
    private var art: ImageBitmap? = null
    private val icons = mutableMapOf<String, Bitmap?>()

    private val callback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) = publish()
        override fun onMetadataChanged(metadata: MediaMetadata?) = publish()
        override fun onSessionDestroyed() = publish()
    }
    private val sessionsChanged = MediaSessionManager.OnActiveSessionsChangedListener { watch(it.orEmpty()) }

    fun start() {
        try {
            manager.addOnActiveSessionsChangedListener(sessionsChanged, component, handler)
            watch(manager.getActiveSessions(component))
        } catch (e: SecurityException) {
            Log.i(TAG, "Notification listener not enabled; no now-playing card")
            onChange(null)
        }
    }

    fun stop() {
        manager.removeOnActiveSessionsChangedListener(sessionsChanged)
        controllers.forEach { it.unregisterCallback(callback) }
        controllers = emptyList()
    }

    private fun watch(active: List<MediaController>) {
        controllers.forEach { it.unregisterCallback(callback) }
        controllers = active
        active.forEach { it.registerCallback(callback, handler) }
        publish()
    }

    private fun publish() {
        val pick = controllers.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: controllers.firstOrNull { it.packageName == shown && it.playbackState?.state in KEEP_STATES }
        shown = pick?.packageName
        onChange(pick?.let(::describe))
    }

    private fun describe(controller: MediaController): NowPlaying {
        val metadata = controller.metadata
        val description = metadata?.description
        val state = controller.playbackState
        return NowPlaying(
            controller = controller,
            title = description?.title?.toString() ?: metadata?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty(),
            artist = description?.subtitle?.toString() ?: metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty(),
            art = artFor(controller, metadata),
            playing = state?.state == PlaybackState.STATE_PLAYING,
            state = state,
            duration = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L,
        )
    }

    // Scaled once per track: apps resend the cover as a new bitmap with every state change, and Spotify briefly
    // swaps it for its logo (DISPLAY_ICON only) after each button press
    private fun artFor(controller: MediaController, metadata: MediaMetadata?): ImageBitmap? {
        val track = metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI)?.takeIf { it.isNotEmpty() }
            ?: metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
        if (track != null && track == coverTrack) return art
        val cover = metadata?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_ART)
        coverTrack = if (cover != null) track else null
        val source = cover
            ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
            ?: icons.getOrPut(controller.packageName) {
                runCatching { context.packageManager.getApplicationIcon(controller.packageName).toBitmap(ART_PX, ART_PX) }.getOrNull()
            }
        if (source !== artSource) {
            artSource = source
            art = source?.let { Bitmap.createScaledBitmap(it, ART_PX, ART_PX * it.height / it.width.coerceAtLeast(1), true).asImageBitmap() }
        }
        return art
    }

    private companion object {
        val KEEP_STATES = setOf(PlaybackState.STATE_PAUSED, PlaybackState.STATE_BUFFERING, PlaybackState.STATE_CONNECTING)
    }
}

@Composable
fun NowPlayingCard(media: NowPlaying, modifier: Modifier = Modifier) {
    val colors = LocalColors.current
    val controls = media.controller.transportControls
    Row(
        modifier
            .width(NowPlayingWidth)
            .background(colors.smokeFill, CardShape)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(56.dp).clip(ArtShape).background(colors.smokeFill)) {
            media.art?.let { Image(it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(56.dp)) }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(media.title, color = colors.onSmoke, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (media.artist.isNotBlank()) {
                Text(media.artist, color = colors.onSmokeSecondary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(10.dp))
            Progress(media)
        }
        Spacer(Modifier.width(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Pill(R.drawable.ic_skip_previous, null, { controls.skipToPrevious() })
            Pill(if (media.playing) R.drawable.ic_pause else R.drawable.ic_play, null, { if (media.playing) controls.pause() else controls.play() })
            Pill(R.drawable.ic_skip_next, null, { controls.skipToNext() })
        }
    }
}

// Ticks on its own, so only this bar redraws each second
@Composable
private fun Progress(media: NowPlaying) {
    val colors = LocalColors.current
    val progress by produceState(media.progress(), media) {
        while (true) {
            value = media.progress()
            delay(1000)
        }
    }
    val fraction = progress ?: return
    Box(
        Modifier
            .fillMaxWidth()
            .height(3.dp)
            .drawBehind {
                val radius = CornerRadius(size.height / 2)
                drawRoundRect(colors.onSmoke.copy(alpha = 0.25f), cornerRadius = radius)
                drawRoundRect(colors.onSmoke, size = size.copy(width = size.width * fraction), cornerRadius = radius)
            }
    )
}
