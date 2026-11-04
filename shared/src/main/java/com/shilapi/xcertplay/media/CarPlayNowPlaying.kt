package com.shilapi.xcertplay.media

import com.shilapi.xcertplay.iap2.message.Iap2NowPlayingState
import com.shilapi.xcertplay.iap2.message.Iap2PlaybackStatus

/**
 * What the iPhone is playing, for a host app's own now-playing card. [positionMillis] is the
 * position at the time this was made; while [playing] the host advances it itself.
 * [artwork] is the iPhone's cover image (usually JPEG), null until it has arrived.
 */
class CarPlayNowPlaying(
    val title: String,
    val artist: String,
    val album: String,
    val appName: String,
    val durationMillis: Long,
    val positionMillis: Long,
    val playing: Boolean,
    val controls: Boolean,
    val artworkId: Int,
    val artwork: ByteArray?,
) {
    internal companion object {
        fun from(snapshot: CarPlayMediaSnapshot): CarPlayNowPlaying? {
            val now = snapshot.nowPlaying ?: return null
            if (now.status == Iap2PlaybackStatus.STOPPED && now.title.isNullOrEmpty()) return null
            val art = snapshot.artwork?.takeIf { it.fileTransferId == now.artworkFileTransferId && it.data.isNotEmpty() }
            return CarPlayNowPlaying(
                title = now.title.orEmpty(),
                artist = now.artist.orEmpty(),
                album = now.album.orEmpty(),
                appName = now.appName.orEmpty(),
                durationMillis = now.durationMillis ?: 0L,
                positionMillis = now.positionNow(),
                playing = now.status == Iap2PlaybackStatus.PLAYING ||
                    now.status == Iap2PlaybackStatus.SEEKING_FORWARD ||
                    now.status == Iap2PlaybackStatus.SEEKING_BACKWARD,
                controls = snapshot.controlsAvailable,
                artworkId = art?.fileTransferId ?: -1,
                artwork = art?.data,
            )
        }

        private fun Iap2NowPlayingState.positionNow(): Long {
            val moved = if (status == Iap2PlaybackStatus.PLAYING && positionUpdateRealtimeMillis > 0) {
                (System.nanoTime() / 1_000_000L - positionUpdateRealtimeMillis).coerceAtLeast(0)
            } else 0L
            val at = elapsedMillis + moved
            return durationMillis?.let { at.coerceAtMost(it) } ?: at
        }
    }
}

/** Public entry to the now-playing feed for host apps (the bridge itself is module-internal). */
object CarPlayNowPlayingFeed {
    /** One observer at a time; null stops it. Called on the iAP2 thread, so post to your own. */
    fun observe(observer: ((CarPlayNowPlaying?) -> Unit)?) = CarPlayMediaSessionBridge.observeForHost(observer)
}
