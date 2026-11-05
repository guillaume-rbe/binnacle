package com.shilapi.xcertplay

import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.RemoteException
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import com.shilapi.xcertplay.embed.ICarPlayEmbed
import com.shilapi.xcertplay.media.CarPlayNowPlaying
import com.shilapi.xcertplay.media.CarPlayNowPlayingFeed
import android.os.Message
import android.os.Messenger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Lets an app signed with the same key show CarPlay in its own view ([ICarPlayEmbed]). The
 * session itself is [CarPlaySession], shared with xcertplay's own screen: whichever of them
 * attached a surface last shows the main stream and sets the display size.
 *
 * Binding needs the signature permission com.shilapi.xcertplay.permission.EMBED_CARPLAY.
 */
class CarPlayEmbedService : Service() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val session = CarPlaySession
    private val client = EmbedClient()
    private var listener: Messenger? = null
    private var listenerDeath: IBinder.DeathRecipient? = null
    private var surface: Surface? = null
    /** Main thread: this client has a finger down on CarPlay (forwarded, not yet lifted). */
    private var fingerDown = false
    private var surfaceWidth = 0
    private var surfaceHeight = 0
    private var lastState = -1
    private var lastLayout: IntArray? = null
    private var lastStage = ""
    private var audioFocusRetained = false
    private var nowPlaying: CarPlayNowPlaying? = null
    private var nowPlayingAt = 0L                       // elapsedRealtime when nowPlaying was made
    private var nowPlayingArt: ByteArray? = null       // the cover, made small for the client
    private var nowPlayingArtId = -1                    // our own number per cover (iAP2 ids repeat)
    private var artSource: ByteArray? = null            // the iPhone's cover nowPlayingArt was made from
    private var artSeq = 0
    private var sentArtId = -1                          // the cover this listener already has
    private var nowPlayingSent = false

    override fun onCreate() {
        super.onCreate()
        session.init(this)
        session.register(client)
        CarPlayNowPlayingFeed.observe { next -> mainHandler.post { onNowPlaying(next) } }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onUnbind(intent: Intent?): Boolean {
        // The client went away without detaching: let go of its surface and listener.
        onMain { dropClient("embedding client unbound") }
        return false
    }

    override fun onDestroy() {
        CarPlayNowPlayingFeed.observe(null)
        dropClient("embed service destroyed")
        session.unregister(client)
        super.onDestroy()
    }

    private val binder = object : ICarPlayEmbed.Stub() {
        override fun getApiVersion(): Int = API_VERSION

        override fun setListener(next: Messenger?) {
            onMain {
                listenerDeath?.let { death -> runCatching { listener?.binder?.unlinkToDeath(death, 0) } }
                listener = next
                listenerDeath = null
                lastState = -1
                lastLayout = null
                sentArtId = -1
                nowPlayingSent = false
                if (next != null) {
                    val death = IBinder.DeathRecipient { onMain { dropClient("embedding client died") } }
                    try {
                        next.binder.linkToDeath(death, 0)
                        listenerDeath = death
                    } catch (_: RemoteException) {
                        listener = null
                        return@onMain
                    }
                    publishState()
                    publishNowPlaying()
                }
            }
        }

        override fun attachSurface(next: Surface?, width: Int, height: Int) {
            if (next == null || width <= 0 || height <= 0) return
            // Not waited for (audit I3): the client calls this from its main thread, and nothing it
            // does next depends on the attach having finished. Calls still run in order, so a
            // following detach (which is waited for) comes after it.
            onMain {
                surface?.takeIf { it !== next }?.let { session.detachSurface(client, CarPlaySession.SCREEN_TYPE_MAIN, it) }
                surface = next
                surfaceWidth = width
                surfaceHeight = height
                retainAudioFocus()
                session.appendLog("Embedded surface attached ${width}x$height")
                session.attachSurface(client, CarPlaySession.SCREEN_TYPE_MAIN, next)
                session.setDisplay(
                    client,
                    DisplaySize(width, height),
                    CarPlaySession.DisplayLayout(splitScreen = false, portrait = height > width),
                )
                publishState()
            }
        }

        override fun detachSurface() {
            onMainAndWait { releaseSurface("Embedded surface detached") }
        }

        override fun sendTouch(event: MotionEvent?) {
            if (event == null) return
            val copy = MotionEvent.obtain(event)
            onMain {
                try {
                    if (surface != null) {
                        val settings = session.settings ?: return@onMain
                        if (settings.mainWebDisplay.enabled && !settings.mainWebDisplay.preview) return@onMain
                        session.sendTouch(copy, surfaceWidth, surfaceHeight, settings.mainWebDisplay)
                        fingerDown = !copy.endsGesture()
                    } else if (copy.endsGesture()) {
                        // the end of a gesture that outlived the surface: lift the finger (audit I1)
                        liftFinger()
                    }
                } finally {
                    copy.recycle()
                }
            }
        }

        override fun sendKey(event: KeyEvent?): Boolean {
            if (event == null || !session.wouldHandleKey(event.keyCode)) return false
            val copy = KeyEvent(event)
            onMain { session.handleKey(copy) }
            return true
        }

        override fun start() {
            onMain {
                session.clearStop()
                // A dead controller is dropped (its teardown then starts afresh); a live one whose
                // retries ran out gets the retry button's reconnect.
                if (!session.dropClosedController()) {
                    if (session.reconnectExhausted) session.reconnectManually() else session.maybeStart()
                }
                publishState()
            }
        }

        override fun disconnect() {
            onMain {
                session.stop("Disconnected by the embedding app")
                publishState()
            }
        }

        override fun openSettings() {
            onMain {
                startActivity(
                    Intent(this@CarPlayEmbedService, CarPlayHostActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }

        override fun declareSizes(widthHeightPairs: IntArray?) {
            val pairs = widthHeightPairs ?: IntArray(0)
            val sizes = (0 until pairs.size / 2)
                .map { DisplaySize(pairs[2 * it], pairs[2 * it + 1]) }
                .filter { it.width in 1..MAX_SIZE && it.height in 1..MAX_SIZE }
                .take(MAX_DECLARED_SIZES)
            onMain {
                session.declareSizes(client, sizes)
                session.appendLog("Embedding app declared sizes ${sizes.joinToString { "${it.width}x${it.height}" }}")
            }
        }
    }

    /** The session's view of this service; callbacks arrive on the main thread. */
    private inner class EmbedClient : CarPlaySession.Client("embed service") {
        override fun onConnectionStage(stage: String) = publishState()
        override fun onScreenStreamsChanged() = publishState()
        override fun onSessionChanged() = publishState()

        override fun onHostUiRequested(): Boolean {
            // Only a client that is showing CarPlay decides what the car-maker tile does.
            val target = listener ?: return false
            if (!session.isShowing(this)) return false
            return try {
                target.send(Message.obtain(null, MSG_HOST_UI))
                true
            } catch (_: RemoteException) {
                false
            }
        }
    }

    private fun state(): Int = when {
        session.suspended -> STATE_PAUSED
        session.reconnectExhausted -> STATE_FAILED
        CarPlaySession.SCREEN_TYPE_MAIN in session.activeScreenStreamTypes -> STATE_STREAMING
        session.controller != null || session.resetInProgress -> STATE_CONNECTING
        !session.stopped && (session.isHeld || session.missingPrerequisites().isNotEmpty()) -> STATE_NEEDS_SETUP
        else -> STATE_IDLE
    }

    /**
     * Where the client's video view goes inside its view, as left, top, width, height ("video";
     * empty = fill the view). The decoder always renders the whole stream (the envelope of every
     * declared area) into the surface, so for a smaller area the view is made larger and clipped
     * by its parent, exactly as xcertplay's own screen does (mainAreaPlacement); touch already
     * maps to the visible area. Sent when it changes.
     */
    private fun publishLayout() {
        val target = listener ?: return
        val w = surfaceWidth
        val h = surfaceHeight
        val video = if (surface == null || w <= 0 || h <= 0) {
            IntArray(0)
        } else {
            val web = session.settings?.mainWebDisplay ?: CarPlayWebDisplayConfig()
            session.mainAreaPlacement(w, h, web)?.let { (rect, share) ->
                intArrayOf(
                    Math.round(rect[0]).toInt(), Math.round(rect[1]).toInt(),
                    Math.round(rect[2] / share[0]).toInt(), Math.round(rect[3] / share[1]).toInt(),
                )
            } ?: IntArray(0)
        }
        if (video.contentEquals(lastLayout)) return
        lastLayout = video
        try {
            target.send(Message.obtain(null, MSG_LAYOUT).apply { data = android.os.Bundle().apply { putIntArray("video", video) } })
        } catch (error: RemoteException) {
            Log.w(TAG, "embedding client unreachable", error)
        }
    }

    private fun publishState() {
        publishLayout()
        val target = listener ?: return
        val state = state()
        val stage = session.connectionStage
        if (state == lastState && stage == lastStage) return
        lastState = state
        lastStage = stage
        try {
            target.send(Message.obtain(null, MSG_STATE, state, 0).apply { data = android.os.Bundle().apply { putString("stage", stage) } })
        } catch (error: RemoteException) {
            Log.w(TAG, "embedding client unreachable", error)
        }
    }

    private fun onNowPlaying(next: CarPlayNowPlaying?) {
        nowPlaying = next
        nowPlayingAt = android.os.SystemClock.elapsedRealtime()
        val source = next?.artwork
        if (source !== artSource) {           // a new cover (or none): shrink it once
            artSource = source
            nowPlayingArt = source?.let { smallCover(it) }
            nowPlayingArtId = if (nowPlayingArt != null) ++artSeq else -1
        }
        publishNowPlaying()
    }

    /**
     * What the iPhone is playing, for the client's own card: what 4, arg1 1 = playing something
     * (data: "title", "artist", "album", "app", long "duration" and "position" in ms, "playing",
     * "controls", int "artId" and, when the cover is new to this client, byte[] "art", a JPEG of
     * at most 256 px), arg1 0 = nothing. Play/pause, next and previous go back as media keys
     * through sendKey, like the steering wheel's.
     */
    private fun publishNowPlaying() {
        val target = listener ?: return
        val now = nowPlaying
        if (now == null && !nowPlayingSent) return
        val data = android.os.Bundle()
        if (now != null) {
            data.putString("title", now.title)
            data.putString("artist", now.artist)
            data.putString("album", now.album)
            data.putString("app", now.appName)
            data.putLong("duration", now.durationMillis)
            // the snapshot can be older than this message (a new listener gets the cached one): move
            // a playing position on by the time since, so the client starts from now
            val since = if (now.playing) (android.os.SystemClock.elapsedRealtime() - nowPlayingAt).coerceAtLeast(0) else 0L
            val position = now.positionMillis + since
            data.putLong("position", if (now.durationMillis > 0) minOf(position, now.durationMillis) else position)
            data.putBoolean("playing", now.playing)
            data.putBoolean("controls", now.controls)
            data.putInt("artId", nowPlayingArtId)
            val art = nowPlayingArt
            if (art != null && nowPlayingArtId != sentArtId) data.putByteArray("art", art)
        }
        try {
            target.send(Message.obtain(null, MSG_NOW_PLAYING, if (now != null) 1 else 0, 0).apply { this.data = data })
            nowPlayingSent = now != null
            if (now != null && data.containsKey("art")) sentArtId = nowPlayingArtId
        } catch (error: RemoteException) {
            Log.w(TAG, "embedding client unreachable", error)
        }
    }

    /** The iPhone's cover scaled to fit 256 px and re-encoded, so it stays well inside a binder call. */
    private fun smallCover(data: ByteArray): ByteArray? = runCatching {
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= COVER_PX && bounds.outHeight / (sample * 2) >= COVER_PX) sample *= 2
        val decoded = android.graphics.BitmapFactory.decodeByteArray(
            data, 0, data.size, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample },
        ) ?: return@runCatching null
        val scale = minOf(1f, COVER_PX.toFloat() / maxOf(decoded.width, decoded.height))
        val bitmap = if (scale < 1f) {
            android.graphics.Bitmap.createScaledBitmap(
                decoded, Math.round(decoded.width * scale), Math.round(decoded.height * scale), true,
            ).also { if (it !== decoded) decoded.recycle() }
        } else decoded
        val out = java.io.ByteArrayOutputStream()
        bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, out)
        bitmap.recycle()
        out.toByteArray()
    }.getOrNull()

    /**
     * Lifts a finger this client pressed and never released. Only this client's: a browser on the
     * web display has its own fingers on the same controller, so nothing is sent otherwise.
     */
    private fun liftFinger() {
        if (!fingerDown) return
        fingerDown = false
        session.cancelTouch()
    }

    private fun MotionEvent.endsGesture(): Boolean =
        actionMasked == MotionEvent.ACTION_UP || actionMasked == MotionEvent.ACTION_CANCEL

    private fun releaseSurface(reason: String) {
        val current = surface ?: return
        surface = null
        // a finger still down on this client's CarPlay is lifted, or the phone keeps it pressed (I1)
        liftFinger()
        session.detachSurface(client, CarPlaySession.SCREEN_TYPE_MAIN, current)
        session.appendLog(reason)
        publishState()
    }

    private fun dropClient(reason: String) {
        releaseSurface(reason)
        listenerDeath?.let { death -> runCatching { listener?.binder?.unlinkToDeath(death, 0) } }
        listenerDeath = null
        listener = null
        if (audioFocusRetained) {
            session.audioFocus().release(this)
            audioFocusRetained = false
        }
    }

    private fun retainAudioFocus() {
        val focus = session.audioFocus()
        if (!audioFocusRetained) {
            focus.retain(this)
            audioFocusRetained = true
        }
        focus.setEnabled(AirPlayPersistence.loadAudioFocusEnabled(this))
        focus.requestFromHost()
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }

    /** For calls the client must not return from early, such as letting go of a surface. */
    private fun onMainAndWait(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
            return
        }
        val done = CountDownLatch(1)
        mainHandler.post {
            try {
                block()
            } finally {
                done.countDown()
            }
        }
        if (!done.await(MAIN_WAIT_SECONDS, TimeUnit.SECONDS)) Log.w(TAG, "embed call timed out on the main thread")
    }

    companion object {
        private const val TAG = "xcertplay-embed"
        const val API_VERSION = 2
        const val MSG_STATE = 1
        const val MSG_HOST_UI = 2
        const val MSG_LAYOUT = 3
        const val MSG_NOW_PLAYING = 4
        private const val COVER_PX = 256
        private const val MAX_SIZE = 8192
        private const val MAX_DECLARED_SIZES = 4
        const val STATE_IDLE = 0
        const val STATE_NEEDS_SETUP = 1
        const val STATE_CONNECTING = 2
        const val STATE_STREAMING = 3
        const val STATE_FAILED = 4
        const val STATE_PAUSED = 5
        // Longer than the decoder's own surface-detach wait, so a detach normally finishes first.
        private const val MAIN_WAIT_SECONDS = 5L
    }
}
