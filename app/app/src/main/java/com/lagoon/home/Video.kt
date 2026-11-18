package com.lagoon.home

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import androidx.media3.exoplayer.video.VideoRendererEventListener

/**
 * The box's HiSilicon decoder throws on MediaCodec.setOutputSurface, which ExoPlayer uses when the video surface is
 * recreated (waking from the screensaver). Tell ExoPlayer to start a fresh decoder on the new surface instead.
 */
@OptIn(UnstableApi::class)
private class FreshCodecRenderers(ctx: Context) : DefaultRenderersFactory(ctx) {
    override fun buildVideoRenderers(
        context: Context, extensionRendererMode: Int, mediaCodecSelector: MediaCodecSelector, enableDecoderFallback: Boolean,
        eventHandler: Handler, eventListener: VideoRendererEventListener, allowedVideoJoiningTimeMs: Long, out: ArrayList<Renderer>,
    ) {
        out.add(object : MediaCodecVideoRenderer(
            context, codecAdapterFactory, mediaCodecSelector, allowedVideoJoiningTimeMs, enableDecoderFallback,
            eventHandler, eventListener, MAX_DROPPED_VIDEO_FRAME_COUNT_TO_NOTIFY,
        ) {
            override fun codecNeedsSetOutputSurfaceWorkaround(name: String) = true
        })
    }
}

/** Small buffers: two of these can be alive at once during a cross-fade, on a box with 2 GB of memory. */
@OptIn(UnstableApi::class)
fun carouselPlayer(ctx: Context): ExoPlayer = ExoPlayer.Builder(ctx, FreshCodecRenderers(ctx))
    .setLoadControl(androidx.media3.exoplayer.DefaultLoadControl.Builder().setBufferDurationsMs(2500, 6000, 400, 800).build())
    .build()

/**
 * Never leave the background frozen: after any playback error, prepare again at the shared timeline position,
 * backing off (1 s, 2 s, 4 s … 30 s) if it keeps failing, and resetting once a frame renders.
 */
fun ExoPlayer.selfHeal(resumeAt: () -> Long) {
    val h = Handler(Looper.getMainLooper())
    var delay = 1000L
    val p = this
    addListener(object : Player.Listener {
        override fun onPlayerError(error: PlaybackException) {
            Log.w("Carousel", "playback error, retrying in ${delay}ms", error)
            h.postDelayed({
                runCatching { p.prepare(); p.seekTo(resumeAt()); p.playWhenReady = true }
            }, delay)
            delay = (delay * 2).coerceAtMost(30_000L)
        }
        override fun onRenderedFirstFrame() { delay = 1000L }
    })
}
