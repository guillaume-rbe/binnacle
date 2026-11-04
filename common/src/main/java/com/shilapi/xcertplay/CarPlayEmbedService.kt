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
    private var surfaceWidth = 0
    private var surfaceHeight = 0
    private var lastState = -1
    private var lastLayout: IntArray? = null
    private var lastStage = ""
    private var audioFocusRetained = false

    override fun onCreate() {
        super.onCreate()
        session.init(this)
        session.register(client)
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onUnbind(intent: Intent?): Boolean {
        // The client went away without detaching: let go of its surface and listener.
        onMain { dropClient("embedding client unbound") }
        return false
    }

    override fun onDestroy() {
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
                }
            }
        }

        override fun attachSurface(next: Surface?, width: Int, height: Int) {
            if (next == null || width <= 0 || height <= 0) return
            onMainAndWait {
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

    private fun releaseSurface(reason: String) {
        val current = surface ?: return
        surface = null
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
