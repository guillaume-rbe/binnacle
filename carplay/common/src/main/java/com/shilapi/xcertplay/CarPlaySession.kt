package com.shilapi.xcertplay

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import com.shilapi.xcertplay.airplay.AirPlayConfig
import com.shilapi.xcertplay.airplay.AirPlayKnobState
import com.shilapi.xcertplay.airplay.AirPlaySession
import com.shilapi.xcertplay.airplay.AirPlaySessionListener
import com.shilapi.xcertplay.airplay.CarPlayDisplayScale
import com.shilapi.xcertplay.airplay.CarPlayMediaEngine
import com.shilapi.xcertplay.location.AndroidCarPlayLocationProvider
import com.shilapi.xcertplay.media.AndroidMediaSink
import com.shilapi.xcertplay.media.CarPlayTouchMapper
import com.shilapi.xcertplay.media.MediaMetricsMonitor
import com.shilapi.xcertplay.network.CarPlayVpnService
import com.shilapi.xcertplay.orchestration.CarPlayController
import com.shilapi.xcertplay.orchestration.CarPlayStatus
import com.shilapi.xcertplay.orchestration.MfiTarget
import com.shilapi.xcertplay.transport.Iap2LocationProvider
import com.shilapi.xcertplay.transport.VehicleSpeedLocationProvider
import com.shilapi.xcertplay.vehicle.VehicleDataFeature
import com.shilapi.xcertplay.vehicle.VehicleLocationProvider
import com.shilapi.xcertplay.vehicle.source.VehicleDataSource
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * The CarPlay session for the whole process: the controller, the media sink, reconnects and the
 * handshake timeline. It lives as long as the process, not as long as a screen.
 *
 * Screens and embedding hosts are [Client]s. Each can hand over video surfaces and say how big
 * its view is. The client that most recently attached a main surface decides the display size
 * (with none attached, the one that most recently reported a size), and only its surface shows
 * the main stream; the others get it back when that client lets go.
 *
 * Main thread only, except where a method says otherwise.
 */
internal object CarPlaySession {
    const val SCREEN_TYPE_MAIN = 110
    const val SCREEN_TYPE_ALT = 111

    // media HID usages, in AirPlayHid's mediaDescriptor order
    private const val MEDIA_PLAY = 1
    private const val MEDIA_PAUSE = 2
    private const val MEDIA_PLAY_PAUSE = 3
    private const val MEDIA_NEXT = 4
    private const val MEDIA_PREVIOUS = 5
    private const val TAG = "xcertplay-usb"
    private const val IDLE_WORKER_KEEP_ALIVE_SECONDS = 30L
    private const val RECONNECT_DELAY_MILLIS = 2_000L
    private const val RECONNECT_MAX_DELAY_MILLIS = 30_000L
    private const val RECONNECT_MAX_SHIFT = 4
    /** After a surface attach: how long to wait for a picture before making the phone redraw. */
    private const val REDRAW_CHECK_MS = 2_000L
    /** How long the redraw stays on the other view area before going back. */
    private const val REDRAW_BOUNCE_MS = 400L
    /** Still no picture this long after the redraw: the phone has stopped drawing, so reconnect. */
    private const val REDRAW_GIVE_UP_MS = 8_000L
    private const val MAX_RECONNECT_ATTEMPTS = 3
    private const val IAP_TUNNEL_RECONNECT_DELAY_MILLIS = 15_000L
    private const val CONTROLLER_CLOSE_TIMEOUT_MILLIS = 4_000L

    data class DisplayLayout(val splitScreen: Boolean, val portrait: Boolean)

    /** Callbacks arrive on the main thread unless noted. */
    abstract class Client(val name: String) {
        /** What the connection is doing, in words. */
        open fun onConnectionStage(stage: String) = Unit
        /** A session-level line that also went to the log file. */
        open fun onLog(message: String) = Unit
        /** Every protocol log line, on the session's own threads. [generation] dates it. */
        open fun onDebugLog(generation: Int, message: String, atMillis: Long) = Unit
        open fun onStatus(status: CarPlayStatus) = Unit
        open fun onScreenStreamsChanged() = Unit
        /** Controller, timeline, retry state or view area changed; re-read what you show. */
        open fun onSessionChanged() = Unit
        /** The handshake timeline gained a failure. */
        open fun onTimelineChanged() = Unit
        /** CarPlay's car-maker tile. True when handled; otherwise the session goes Home. */
        open fun onHostUiRequested(): Boolean = false
        /** About to start a new controller. */
        open fun onBeforeStart() = Unit

        internal val surfaces = mutableMapOf<Int, Surface>()
        internal val surfaceStamps = mutableMapOf<Int, Long>()
        internal var display: DisplaySize? = null
        internal var layout = DisplayLayout(splitScreen = false, portrait = false)
        internal var displayStamp = 0L
        /** Sizes this client may switch between, declared to the phone as extra view areas. */
        internal var declaredSizes: List<DisplaySize> = emptyList()
    }

    val log = SessionLog()

    private lateinit var app: Context
    private val mainHandler = Handler(Looper.getMainLooper())
    private val clients = CopyOnWriteArrayList<Client>()
    private val holds = mutableSetOf<Any>()
    private var stamp = 0L

    // Teardown and AirPlay commands run off the main thread; their workers retire when idle.
    private val teardownExecutor: ExecutorService = idleRetiringSingleThreadExecutor()
    private val airPlayCommandExecutor: ExecutorService = idleRetiringSingleThreadExecutor()

    private var audioFocusSession: AndroidMediaSink.AudioFocusSession? = null

    var settings: CarPlaySessionSettings? = null
        private set
    var controller: CarPlayController? = null
        private set
    private var sink: AndroidMediaSink? = null
    /** View areas declared by the current controller; null for a web display or no controller. */
    var viewAreas: CarPlayViewAreas? = null
        private set
    var handshakeTimeline: HandshakeTimeline? = null
        private set
    private var lastAttemptFailure: String? = null
    val activeScreenStreamTypes: Set<Int> get() = screenStreams
    private val screenStreams = mutableSetOf<Int>()
    private var activeAirPlaySession: AirPlaySession? = null
    /** Read from binder threads: whether a key would reach the phone right now. */
    @Volatile var sessionActive = false
        private set
    var vehicleProvider: VehicleDataSource? = null
        private set
    var generation = 0
        private set
    var connectionStage = "Preparing CarPlay"
        private set
    var reconnectExhausted = false
        private set
    var resetInProgress = false
        private set
    /** The settings screen is open: no session runs and none starts. */
    var suspended = false
        private set
    var shuttingDown = false
        private set
    var activeDisplaySize: DisplaySize? = null
        private set
    private var activeDisplayLayout: DisplayLayout? = null
    private var startAfterReset = false
    private var reconnectScheduled = false
    private var reconnectAttempts = 0
    private var mediaMetricsMonitor: MediaMetricsMonitor? = null
    private var nightMode = false
    /** The surface each stream type is currently drawing into. */
    private val shownSurfaces = mutableMapOf<Int, Surface>()

    val isInitialized: Boolean get() = ::app.isInitialized

    /** Safe to call often; the first call opens the session log. */
    fun init(context: Context) {
        if (::app.isInitialized) return
        app = context.applicationContext
        nightMode = isDarkMode(app.resources.configuration.uiMode)
        val baseDirectory = app.getExternalFilesDir(null) ?: app.filesDir
        val logFile = File(File(baseDirectory, "logs"), "xcertplay.log")
        log.start(
            logFile,
            "xcertplay log started " +
                "${SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())} " +
                "pid=${Process.myPid()} path=${logFile.absolutePath}",
            onError = { Log.e(TAG, it) },
        )
        log.videoFramesEnabled = AirPlayPersistence.loadVideoFrameLogsEnabled(app)
    }

    @Synchronized
    fun audioFocus(): AndroidMediaSink.AudioFocusSession =
        audioFocusSession ?: AndroidMediaSink.createAudioFocus(app).also { audioFocusSession = it }

    // ---------------------------------------------------------------------------------------
    // Clients, surfaces and display size
    // ---------------------------------------------------------------------------------------

    fun register(client: Client) {
        if (client !in clients) clients.add(client)
    }

    fun unregister(client: Client) {
        if (!clients.remove(client)) return
        val types = client.surfaces.keys.toList()
        types.forEach { type -> client.surfaces[type]?.let { detachSurface(client, type, it) } }
        client.display = null
        applyEffectiveDisplay()
    }

    /** Blocks starting a session while [token] is held, e.g. while a permission prompt is open. */
    fun hold(token: Any) {
        holds.add(token)
    }

    fun release(token: Any) {
        if (holds.remove(token)) maybeStart()
    }

    fun attachSurface(client: Client, type: Int, surface: Surface) {
        client.surfaces[type] = surface
        client.surfaceStamps[type] = ++stamp
        showBestSurface(type)
        if (type == SCREEN_TYPE_MAIN) {
            applyEffectiveDisplay()
            mainHandler.removeCallbacks(redrawIfBlank)
            mainHandler.postDelayed(redrawIfBlank, REDRAW_CHECK_MS)
        }
    }

    /**
     * A surface handed over again (the head unit's window came back) stays empty until the phone
     * sends a picture, and on a static CarPlay screen it never does: it ignores forceKeyFrame
     * (car, 6 Nov: black window after returning from Android Settings, 115 requests unanswered).
     * The phone redraws the whole screen when the view area changes, so if no picture came, the
     * view area goes to another declared one and straight back. If the picture still doesn't
     * come, the phone has stopped drawing for this session (car, 6 Nov: it kept the session and
     * its Wi-Fi link, call alerts still played, but sent no video for 15 minutes and showed no
     * CarPlay itself), so the session is rebuilt instead of leaving a black window.
     */
    private val redrawIfBlank = Runnable { redrawMainIfBlank() }
    private val reconnectIfBlank = Runnable { reconnectMainIfBlank() }

    private fun mainStillBlank(): Boolean =
        sink?.awaitingPicture(SCREEN_TYPE_MAIN) == true && shownSurfaces[SCREEN_TYPE_MAIN] != null &&
            controller?.isClosed() == false && !suspended && !resetInProgress && !shuttingDown

    private fun redrawMainIfBlank() {
        if (!mainStillBlank()) return
        val active = controller ?: return
        blankController = active
        mainHandler.removeCallbacks(reconnectIfBlank)
        mainHandler.postDelayed(reconnectIfBlank, REDRAW_GIVE_UP_MS)
        val areas = viewAreas ?: return
        if (areas.areas.size < 2) return
        val current = active.viewAreaIndex
        val other = if (current == 0) 1 else 0
        if (redrawBounce != null || !active.showViewArea(other)) return
        appendLog("No picture on the new surface: view area $current -> $other -> $current to redraw")
        redrawBounce = active to current
        mainHandler.postDelayed(endRedrawBounce, REDRAW_BOUNCE_MS)
    }

    /** The session the reconnect check belongs to: a session rebuilt meanwhile is left alone. */
    private var blankController: CarPlayController? = null

    private fun reconnectMainIfBlank() {
        val active = blankController
        blankController = null
        if (active == null || controller !== active || !mainStillBlank()) return
        restart("No picture ${(REDRAW_CHECK_MS + REDRAW_GIVE_UP_MS) / 1000} s after the window came back; reconnecting")
    }

    /** The controller and view area a redraw bounce goes back to, while one is under way. */
    private var redrawBounce: Pair<CarPlayController, Int>? = null
    private val endRedrawBounce = Runnable { finishRedrawBounce() }

    /**
     * Puts the view area back to where the bounce started. Runs first in [applyEffectiveDisplay],
     * so a display change during the bounce is worked out from the real area and wins.
     */
    private fun finishRedrawBounce() {
        mainHandler.removeCallbacks(endRedrawBounce)
        val (active, index) = redrawBounce ?: return
        redrawBounce = null
        if (controller === active && !active.isClosed()) active.showViewArea(index)
    }

    /** Waits for the decoder to let go of [surface]; call before the surface is destroyed. */
    fun detachSurface(client: Client, type: Int, surface: Surface) {
        if (client.surfaces[type] !== surface) return
        client.surfaces.remove(type)
        client.surfaceStamps.remove(type)
        if (shownSurfaces[type] === surface) {
            shownSurfaces.remove(type)
            val outputs = CarPlayWebDisplayFactory.find(sink)
            if (outputs?.owns(type) == true) outputs.releasePreview(type, surface)
            else sink?.clearSurface(type, surface)
        }
        showBestSurface(type)
        if (type == SCREEN_TYPE_MAIN) applyEffectiveDisplay()
    }

    /** Re-sends the shown surfaces to web display previews, which follow the window. */
    fun refreshPreviews() {
        val outputs = CarPlayWebDisplayFactory.find(sink) ?: return
        shownSurfaces.forEach { (type, surface) -> if (outputs.owns(type)) outputs.preview(type, surface) }
    }

    private fun bestSurface(type: Int): Surface? =
        clients.filter { it.surfaces[type] != null }
            .maxByOrNull { it.surfaceStamps[type] ?: 0L }
            ?.surfaces?.get(type)

    private fun showBestSurface(type: Int) {
        val best = bestSurface(type) ?: return
        val current = shownSurfaces[type]
        if (current === best) return
        val outputs = CarPlayWebDisplayFactory.find(sink)
        if (outputs?.owns(type) == true) {
            current?.let { outputs.releasePreview(type, it) }
            outputs.preview(type, best)
        } else {
            sink?.setSurface(type, best)
        }
        if (sink != null) shownSurfaces[type] = best
    }

    private fun showAllSurfaces() {
        shownSurfaces.clear()
        listOf(SCREEN_TYPE_MAIN, SCREEN_TYPE_ALT).forEach(::showBestSurface)
    }

    /**
     * Sizes [client] may show CarPlay at. A running session whose view areas lack one of them
     * reconnects once, now rather than at the first switch to that size (mid-drive, perhaps), so
     * later switches between them need no reconnect. ICarPlayEmbed.declareSizes documents this.
     */
    fun declareSizes(client: Client, sizes: List<DisplaySize>) {
        if (client.declaredSizes == sizes) return
        client.declaredSizes = sizes
        val areas = viewAreas ?: return
        if (controller == null || sizes.all { areas.indexFor(it.width, it.height) != null }) return
        restart("New view areas declared: ${sizes.joinToString { "${it.width}x${it.height}" }}")
    }

    private fun declaredSizes(): List<DisplaySize> = clients.flatMap { it.declaredSizes }.distinct()

    fun setDisplay(client: Client, size: DisplaySize, layout: DisplayLayout) {
        client.display = size
        client.layout = layout
        client.displayStamp = ++stamp
        applyEffectiveDisplay()
    }

    /**
     * A client asking for a session before it has shown CarPlay anywhere (the dash's auto-connect
     * right after it starts): no display size is known yet, so the client's first declared size
     * (the dash's centre window) stands in until it hands over a surface. A known size is kept.
     */
    fun useDeclaredSizeIfNoDisplay(client: Client) {
        if (activeDisplaySize != null || client.display != null) return
        val size = client.declaredSizes.firstOrNull() ?: return
        appendLog("No display yet; using the declared ${size.width}x${size.height} until one is shown")
        setDisplay(client, size, DisplayLayout(splitScreen = false, portrait = size.height > size.width))
    }

    /** The client whose view the phone draws for. */
    private fun displayOwner(): Client? {
        val sized = clients.filter { it.display != null }
        return sized.filter { it.surfaces[SCREEN_TYPE_MAIN] != null }
            .maxByOrNull { it.surfaceStamps[SCREEN_TYPE_MAIN] ?: 0L }
            ?: sized.maxByOrNull { it.displayStamp }
    }

    fun currentLayout(): DisplayLayout =
        displayOwner()?.layout ?: activeDisplayLayout ?: DisplayLayout(splitScreen = false, portrait = false)

    private fun applyEffectiveDisplay() {
        finishRedrawBounce()
        if (shuttingDown) return
        val owner = displayOwner() ?: return
        val size = owner.display ?: return
        val layout = owner.layout
        val previous = activeDisplaySize
        val layoutChanged = activeDisplayLayout != null && layout != activeDisplayLayout
        activeDisplaySize = size
        activeDisplayLayout = layout
        recordDetectedMaximum(size)
        if (applyViewArea(size, layout)) return
        if (size == previous && !layoutChanged) {
            maybeStart()
            return
        }
        val webDisplay = settings?.mainWebDisplay ?: AirPlayPersistence.loadWebDisplay(app, SCREEN_TYPE_MAIN)
        if (previous == null) {
            appendLog("Display detected: ${size.width}x${size.height}")
            maybeStart()
        } else if (suspended || resetInProgress) {
            appendLog(
                "Display updated while handshake is reset: " +
                    "${previous.width}x${previous.height} -> ${size.width}x${size.height}",
            )
        } else if (controller == null) {
            maybeStart()
        } else if (webDisplay.enabled) {
            // The web display owns its negotiated size; only the preview follows the window.
            notifySessionChanged()
        } else if (!layoutChanged && canKeepDisplayWindow(size, layout)) {
            appendLog(
                "Display changed ${previous.width}x${previous.height} -> ${size.width}x${size.height} " +
                    "inside declared ${viewAreas?.activityWidthPixels}x${viewAreas?.activityHeightPixels}; " +
                    "keeping session",
            )
            notifySessionChanged()
        } else {
            restart(
                "Display changed ${previous.width}x${previous.height} -> ${size.width}x${size.height}",
            )
        }
    }

    private fun canKeepDisplayWindow(size: DisplaySize, layout: DisplayLayout): Boolean {
        val areas = viewAreas ?: return false
        val active = controller ?: return false
        if (layout.splitScreen) return false
        val window = areas.activitySize(active.viewAreaIndex)
        // Temporary overlays may shrink the current layout, but cannot expand it using another orientation.
        return size.width <= window.first && size.height <= window.second &&
            (size.width > size.height) == (window.first > window.second) &&
            (areas.indexFor(size.width, size.height) == null || active.viewAreaIndex == areas.indexFor(size.width, size.height))
    }

    /** Switches to a declared area; failure falls through to the renegotiation decision. */
    private fun applyViewArea(size: DisplaySize, layout: DisplayLayout): Boolean {
        val areas = viewAreas ?: return false
        val active = controller ?: return false
        if (active.isClosed() || suspended || resetInProgress || shuttingDown) return false
        val current = active.viewAreaIndex
        val (currentWidth, currentHeight) = areas.activitySize(current)
        val turned = (size.width > size.height) != (currentWidth > currentHeight)
        if (turned && !areas.turnsWithScreen) return false
        val target = areas.indexFor(size.width, size.height, layout.splitScreen, layout.portrait) ?: return false
        if (!turned && target == current && !layout.splitScreen) return false
        if (!active.showViewArea(target)) return false
        appendLog(
            "View area $current -> $target for ${size.width}x${size.height} " +
                "(${areas.areas[target].widthPixels}x${areas.areas[target].heightPixels})",
        )
        notifySessionChanged()
        return true
    }

    private fun recordDetectedMaximum(size: DisplaySize) {
        val (savedWidth, savedHeight) = AirPlayPersistence.loadMaximumDetectedDisplay(app)
        val width = maxOf(savedWidth, size.width)
        val height = maxOf(savedHeight, size.height)
        if (width == savedWidth && height == savedHeight) return
        AirPlayPersistence.saveMaximumDetectedDisplay(app, width, height)
    }

    /**
     * Where the main stream's active area is drawn in a view of this size, as
     * [left, top, width, height], plus the area's [width, height] share of the stream. Null when
     * the stream simply fills the view.
     */
    fun mainAreaPlacement(
        viewWidth: Int,
        viewHeight: Int,
        mainWebDisplay: CarPlayWebDisplayConfig,
    ): Pair<DoubleArray, DoubleArray>? {
        if (viewWidth <= 0 || viewHeight <= 0) return null
        if (mainWebDisplay.enabled) {
            return CarPlayTouchMapper.contentRect(viewWidth, viewHeight, mainWebDisplay.width, mainWebDisplay.height) to
                doubleArrayOf(1.0, 1.0)
        }
        val areas = viewAreas ?: return null
        val index = controller?.viewAreaIndex?.takeIf { it in 0 until areas.size } ?: return null
        val area = areas.areas[index]
        val share = doubleArrayOf(
            area.widthPixels.toDouble() / areas.widthPixels,
            area.heightPixels.toDouble() / areas.heightPixels,
        )
        // The area's own activity size fills the view; any other size (inside the envelope) is
        // letterboxed so the phone's layout keeps its aspect ratio.
        val rect = if (areas.activitySize(index) == (viewWidth to viewHeight)) {
            doubleArrayOf(0.0, 0.0, viewWidth.toDouble(), viewHeight.toDouble())
        } else {
            CarPlayTouchMapper.contentRect(viewWidth, viewHeight, area.widthPixels, area.heightPixels)
        }
        return rect to share
    }

    // ---------------------------------------------------------------------------------------
    // Input
    // ---------------------------------------------------------------------------------------

    /** Touch on a view showing the main stream. Returns whether it was queued for the phone. */
    fun sendTouch(event: MotionEvent, viewWidth: Int, viewHeight: Int, mainWebDisplay: CarPlayWebDisplayConfig): Boolean {
        val placement = mainAreaPlacement(viewWidth, viewHeight, mainWebDisplay)
        val contacts = if (placement == null) {
            CarPlayTouchMapper.contacts(event, viewWidth, viewHeight)
        } else {
            // Touch is normalized to the active area, then to the whole declared display.
            val (rect, share) = placement
            CarPlayTouchMapper.contacts(event, rect)
                .map { it.copy(x = it.x * share[0], y = it.y * share[1]) }
        }
        return controller?.sendTouch(contacts) ?: false
    }

    /** Lifts every finger, e.g. before a gesture the host keeps for itself. */
    fun cancelTouch() {
        controller?.sendTouch(emptyList())
    }

    /** Any thread: whether [handleKey] would take this key now. */
    fun wouldHandleKey(keyCode: Int): Boolean = sessionActive && isCarPlayKey(keyCode)

    private fun isCarPlayKey(keyCode: Int): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT,
        KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_CENTER,
        KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_MEDIA_PLAY,
        KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK,
        KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_VOICE_ASSIST,
        KeyEvent.KEYCODE_SEARCH -> true
        else -> false
    }

    /**
     * Head-unit buttons into CarPlay while a session is up: the steering wheel arrives as normal
     * key events (scroll = DPAD up/down, tilt right = ENTER, tilt left = BACK, plus media keys),
     * sent on as the knob and media HID devices that /info already declares.
     */
    fun handleKey(event: KeyEvent): Boolean {
        val ctl = controller
        if (suspended || ctl == null || activeAirPlaySession == null) return false
        val down = event.action == KeyEvent.ACTION_DOWN
        val first = down && event.repeatCount == 0
        val handled = when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> { if (down) ctl.sendKnob(AirPlayKnobState(wheel = -1)); true }
            KeyEvent.KEYCODE_DPAD_DOWN -> { if (down) ctl.sendKnob(AirPlayKnobState(wheel = 1)); true }
            KeyEvent.KEYCODE_DPAD_LEFT -> { if (down) ctl.sendKnob(AirPlayKnobState(x = -1)); true }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { if (down) ctl.sendKnob(AirPlayKnobState(x = 1)); true }
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                if (first || event.action == KeyEvent.ACTION_UP) ctl.sendKnobSelect(down); true
            }
            KeyEvent.KEYCODE_BACK -> { if (first) ctl.sendKnob(AirPlayKnobState(back = true)); true }
            KeyEvent.KEYCODE_MEDIA_PLAY -> { if (first) ctl.sendMedia(MEDIA_PLAY); true }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> { if (first) ctl.sendMedia(MEDIA_PAUSE); true }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK -> {
                if (first) ctl.sendMedia(MEDIA_PLAY_PAUSE); true
            }
            KeyEvent.KEYCODE_MEDIA_NEXT -> { if (first) ctl.sendMedia(MEDIA_NEXT); true }
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> { if (first) ctl.sendMedia(MEDIA_PREVIOUS); true }
            KeyEvent.KEYCODE_VOICE_ASSIST, KeyEvent.KEYCODE_SEARCH -> { if (first) ctl.invokeSiri(); true }
            else -> false
        }
        if (handled && first) appendFileLog("key ${KeyEvent.keyCodeToString(event.keyCode)} -> CarPlay")
        return handled
    }

    fun setNightMode(night: Boolean) {
        if (night == nightMode) return
        nightMode = night
        syncNightMode()
    }

    private fun syncNightMode() {
        val session = activeAirPlaySession ?: return
        val night = nightMode
        airPlayCommandExecutor.execute {
            try {
                val sent = session.setNightMode(night)
                Log.i(TAG, "AirPlay dark mode=${if (night) "dark" else "light"} eventChannelReady=$sent")
            } catch (error: Throwable) {
                Log.w(TAG, "Could not send AirPlay dark mode update", error)
            }
        }
    }

    fun setMediaMetricsMonitor(monitor: MediaMetricsMonitor?) {
        mediaMetricsMonitor = monitor
        sink?.setMediaMetricsMonitor(monitor)
    }

    // ---------------------------------------------------------------------------------------
    // Lifecycle
    // ---------------------------------------------------------------------------------------

    /** What still stops a session from starting without a screen to ask for it; empty = none. */
    fun missingPrerequisites(): List<String> {
        val missing = mutableListOf<String>()
        val wireless = AirPlayPersistence.loadWirelessEnabled(app)
        if (wireless) {
            if (!requiredWirelessPermissions().all(::granted)) missing += "wireless permissions"
        } else if (CarPlayVpnService.prepare(app) != null) {
            missing += "VPN consent"
        }
        if (AirPlayPersistence.loadLocationReportingEnabled(app) && !granted(Manifest.permission.ACCESS_FINE_LOCATION)) {
            missing += "location permission"
        }
        return missing
    }

    fun requiredWirelessPermissions(): List<String> = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> listOf(
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.NEARBY_WIFI_DEVICES,
        )
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> listOf(
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_FINE_LOCATION,
        )
        else -> listOf(
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_FINE_LOCATION,
        )
    }

    private fun granted(permission: String): Boolean =
        app.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    /** Something (a screen's permission prompt, a disconnect) is holding the session back. */
    val isHeld: Boolean get() = holds.isNotEmpty() || stopped

    /** Set by [stop]: no session starts until [clearStop]. */
    var stopped = false
        private set

    /** Ends the running session and keeps it ended until [clearStop], e.g. a host's disconnect. */
    fun stop(reason: String) {
        if (shuttingDown) return
        stopped = true
        reconnectScheduled = false
        reconnectAttempts = 0
        reconnectExhausted = false
        ++generation
        if (controller == null && !resetInProgress) {
            notifySessionChanged()
            return
        }
        appendLog(reason)
        controller?.sendTouch(emptyList())
        screenStreams.clear()
        setConnectionStage(reason)
        notifyScreenStreams()
        tearDown {}
    }

    /**
     * A controller that closed itself (retries ran out, or a status nobody acted on) blocks
     * every start. Drops it so the next [maybeStart] begins afresh with a new retry budget; the
     * old per-screen hand-off did the same when a screen opened. True when one was dropped.
     */
    fun dropClosedController(): Boolean {
        val current = controller ?: return false
        if (!current.isClosed() || resetInProgress || shuttingDown) return false
        appendLog("Dropping a closed CarPlay controller")
        reconnectScheduled = false
        reconnectAttempts = 0
        reconnectExhausted = false
        ++generation
        screenStreams.clear()
        notifyScreenStreams()
        tearDown {}
        return true
    }

    /** True when [client]'s surface is the one showing the main stream. */
    fun isShowing(client: Client): Boolean =
        client.surfaces[SCREEN_TYPE_MAIN] != null && displayOwner() === client

    /** Lets sessions start again after [stop]; the caller then asks for one with [maybeStart]. */
    fun clearStop() {
        if (!stopped) return
        stopped = false
        notifySessionChanged()
    }

    fun maybeStart() {
        if (shuttingDown || suspended || resetInProgress || controller != null) return
        if (holds.isNotEmpty() || stopped) return
        val size = activeDisplaySize ?: return
        if (missingPrerequisites().isNotEmpty()) return
        start(size)
    }

    private fun start(size: DisplaySize) {
        clients.forEach { it.onBeforeStart() }
        val settings = CarPlaySessionSettings.load(app)
        this.settings = settings
        val controllerGeneration = generation
        log.videoFramesEnabled = AirPlayPersistence.loadVideoFrameLogsEnabled(app)
        audioFocus().setEnabled(settings.audioFocusEnabled)
        handshakeTimeline = HandshakeTimeline(settings.wirelessEnabled, System.currentTimeMillis(), lastAttemptFailure)
        lastAttemptFailure = null
        notifySessionChanged()
        val microphone = granted(Manifest.permission.RECORD_AUDIO)
        val config = settings.runtimeConfig()
        val areas = settings.viewAreas(app, size, declaredSizes())
        val airPlayConfig = settings.airPlayConfig(app, size, areas, microphone)
        val vehicle = settings.vehicleDataSource(app).also { it.start() }
        vehicleProvider = vehicle
        val positionProvider: Iap2LocationProvider? =
            if (config.locationReportingEnabled) {
                VehicleLocationProvider(
                    AndroidCarPlayLocationProvider(
                        app, precise = settings.vehicleDataConfig.isEnabled(VehicleDataFeature.PRECISE_POSITIONING),
                    ), settings.vehicleDataConfig,
                    android.os.SystemClock::elapsedRealtime,
                )
            } else {
                null
            }
        val locationProvider = if (settings.vehicleDataConfig.isEnabled(VehicleDataFeature.WHEEL_SPEED)) {
            VehicleSpeedLocationProvider(positionProvider, vehicle)
        } else {
            positionProvider
        }
        appendLog(
            "Starting CarPlay controller at ${size.width}x${size.height} -> " +
                "${airPlayConfig.main.widthPixels}x${airPlayConfig.main.heightPixels} " +
                "(${CarPlayDisplayScale.label(settings.displayScaleTenths)}) " +
                "viewAreas=${areas ?: "single"} " +
                "physical=${airPlayConfig.main.widthPhysicalMm}x" +
                "${airPlayConfig.main.heightPhysicalMm}mm " +
                "video=${if (airPlayConfig.hevc) "HEVC" else "H.264"} " +
                "decoder=${if (airPlayConfig.hevc && settings.hevcSoftwareDecoderEnabled) "software" else "hardware"} " +
                "microphone=${airPlayConfig.microphone} " +
                "location=${if (config.locationReportingEnabled) "enabled" else "disabled"} " +
                "mfi=${mfiTargetLabel(config.mfiTarget)}",
        )
        Log.i(
            TAG,
            "starting controller display=${size.width}x${size.height} " +
                "negotiated=${airPlayConfig.main.widthPixels}x${airPlayConfig.main.heightPixels} " +
                "scale=${CarPlayDisplayScale.label(settings.displayScaleTenths)} " +
                "hevc=${airPlayConfig.hevc} " +
                "softwareHevc=${airPlayConfig.hevc && settings.hevcSoftwareDecoderEnabled} " +
                "microphone=${airPlayConfig.microphone} " +
                "location=${config.locationReportingEnabled} " +
                "mfi=${config.mfiTarget}",
        )
        val renderer = try {
            createMediaSink(settings, airPlayConfig, controllerGeneration)
        } catch (error: Exception) {
            // Nothing owns these providers yet; release the car service binding and worker.
            vehicle.close()
            vehicleProvider = null
            appendLog("Could not create web displays: ${error.message}")
            failHandshake("Could not start web displays", error.message ?: "Check resolution and port 8080")
            setConnectionStage("Could not start web displays; check resolution and port 8080")
            return
        }
        sink = renderer
        showAllSurfaces()
        val media = CarPlayMediaEngine(
            sink = renderer,
            microphoneEnabled = microphone,
            audioCaptureDirectory = settings.audioCaptureDirectory(app),
            videoPacketLog = log::appendVideoPacket,
            statsLog = { log.appendTimestamped(it, System.currentTimeMillis()) },
        )
        val pairings = AirPlayPersistence.loadPairings(app) { id, key ->
            AirPlayPersistence.savePairing(app, id, key)
        }
        val next = CarPlayController(
            context = app,
            config = config,
            airPlayConfig = airPlayConfig,
            identity = AirPlayPersistence.loadIdentity(app),
            pairings = pairings,
            listener = createSessionListener(controllerGeneration),
            media = media,
            reportStatus = createStatusReporter(controllerGeneration),
            loadPairRecord = { AirPlayPersistence.loadLockdownRecord(app) },
            savePairRecord = { record -> AirPlayPersistence.saveLockdownRecord(app, record) },
            clearPairRecord = { AirPlayPersistence.clearLockdownRecord(app) },
            locationProvider = locationProvider,
            vehicleStatusProvider = vehicle,
        )
        controller = next
        viewAreas = areas
        notifySessionChanged()
        next.start()
    }

    private fun createMediaSink(
        settings: CarPlaySessionSettings,
        airPlayConfig: AirPlayConfig,
        controllerGeneration: Int,
    ): AndroidMediaSink {
        val outputs = CarPlayWebDisplayFactory.create(
            context = app,
            configs = mapOf(SCREEN_TYPE_MAIN to settings.mainWebDisplay, SCREEN_TYPE_ALT to settings.clusterWebDisplay),
            sendTouch = { controller?.sendTouch(it) ?: false },
        )
        return try {
            AndroidMediaSink(
                context = app,
                videoWidth = airPlayConfig.main.widthPixels,
                videoHeight = airPlayConfig.main.heightPixels,
                videoSizes = buildMap {
                    put(SCREEN_TYPE_MAIN, airPlayConfig.main.widthPixels to airPlayConfig.main.heightPixels)
                    airPlayConfig.cluster?.let { put(SCREEN_TYPE_ALT, it.widthPixels to it.heightPixels) }
                },
                preferSoftwareHevcDecoder = settings.hevcSoftwareDecoderEnabled,
                advancedAudioChannelMapping = settings.advancedAudioChannelMapping,
                mainMediaAudioBufferDurationMs = settings.mainMediaAudioBufferDurationMs,
                microphoneGainPercent = settings.microphoneGainPercent,
                mediaMetricsMonitor = mediaMetricsMonitor,
                onClosed = { outputs?.close() },
                mediaTap = outputs,
                audioFocus = audioFocus(),
                callMicCaptureDirectory = settings.audioCaptureDirectory(app),
                debugLog = { appendFileLog(it) },
                onScreenStreamActiveChanged = { type, active ->
                    onScreenStreamStateChanged(controllerGeneration, type, active)
                },
            ).also { outputs?.bind(it) }
        } catch (error: Exception) { outputs?.close(); throw error }
    }

    private fun createSessionListener(controllerGeneration: Int): AirPlaySessionListener =
        object : AirPlaySessionListener {
            override fun onSessionActive(session: AirPlaySession) {
                mainHandler.post {
                    if (controllerGeneration != generation) return@post
                    activeAirPlaySession = session
                    sessionActive = true
                    reconnectAttempts = 0
                    reconnectExhausted = false
                    syncNightMode()
                    notifySessionChanged()
                    if (suspended) return@post
                    appendLog("AirPlay session active")
                }
            }

            override fun onSessionEnded(session: AirPlaySession) {
                mainHandler.post {
                    if (activeAirPlaySession === session) {
                        activeAirPlaySession = null
                        sessionActive = false
                    }
                    if (suspended || controllerGeneration != generation) return@post
                    screenStreams.clear()
                    failHandshake("CarPlay session ended", "iPhone closed the AirPlay session")
                    setConnectionStage("CarPlay session ended; reconnecting")
                    appendLog("AirPlay session ended; reconnecting from scratch")
                    reconnectAfterLoss("AirPlay session ended")
                    notifyScreenStreams()
                }
            }

            override fun onTransportError(message: String) {
                mainHandler.post {
                    if (suspended || controllerGeneration != generation) return@post
                    screenStreams.clear()
                    failHandshake("Transport error", message)
                    setConnectionStage("Transport error; reconnecting")
                    appendLog("CarPlay transport error: $message; reconnecting from scratch")
                    reconnectAfterLoss("CarPlay transport error: $message")
                    notifyScreenStreams()
                }
            }

            override fun onHostUiRequested(session: AirPlaySession) {
                mainHandler.post {
                    if (clients.reversed().any { it.onHostUiRequested() }) return@post
                    // CarPlay's car-maker tile: back to the head unit's own Home (the dash). The
                    // session keeps running in the background.
                    try {
                        app.startActivity(
                            Intent(Intent.ACTION_MAIN)
                                .addCategory(Intent.CATEGORY_HOME)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    } catch (_: ActivityNotFoundException) {
                        appendLog("No Home app to return to")
                    }
                }
            }

            override fun onDebugLog(message: String) {
                // TRACE lines hold decrypted control traffic: only with Debug logs on, since the
                // session log is in shared storage (users attach it to issues)
                if (message.startsWith("TRACE ") && !AirPlayPersistence.loadDebugLogsEnabled(app)) return
                val now = System.currentTimeMillis()
                log.appendTimestamped(message, now)
                clients.forEach { it.onDebugLog(controllerGeneration, message, now) }
            }
        }

    private fun createStatusReporter(controllerGeneration: Int): (CarPlayStatus) -> Unit = { status ->
        mainHandler.post {
            if (suspended || controllerGeneration != generation) return@post
            clients.forEach { it.onStatus(status) }
            val description = describe(status)
            setConnectionStage(description)
            if (status is CarPlayStatus.Failed) {
                failHandshake("CarPlay failed", description)
                reconnectAfterLoss(description)
            }
        }
    }

    private fun onScreenStreamStateChanged(controllerGeneration: Int, type: Int, active: Boolean) {
        mainHandler.post {
            if (shuttingDown || controllerGeneration != generation) return@post
            if (active) screenStreams.add(type) else screenStreams.remove(type)
            notifyScreenStreams()
        }
    }

    private fun reconnectAfterLoss(reason: String) {
        if (shuttingDown || suspended || resetInProgress) return
        if (reconnectScheduled || reconnectExhausted) return
        if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
            reconnectExhausted = true
            setConnectionStage("$reason; stopped after $MAX_RECONNECT_ATTEMPTS attempts")
            appendLog("$reason; stopped retrying after $MAX_RECONNECT_ATTEMPTS attempts, waiting for manual reconnect")
            notifySessionChanged()
            return
        }
        reconnectScheduled = true
        val scheduledGeneration = generation
        val delayMillis = if (reason.contains("AirPlay iAP tunnel", ignoreCase = true)) {
            IAP_TUNNEL_RECONNECT_DELAY_MILLIS
        } else {
            minOf(
                RECONNECT_DELAY_MILLIS shl reconnectAttempts.coerceAtMost(RECONNECT_MAX_SHIFT),
                RECONNECT_MAX_DELAY_MILLIS,
            )
        }
        reconnectAttempts++
        appendLog("$reason; retrying in ${delayMillis}ms")
        mainHandler.postDelayed(
            {
                if (scheduledGeneration != generation) return@postDelayed
                reconnectScheduled = false
                if (shuttingDown || suspended || resetInProgress) return@postDelayed
                restart("Reconnecting after $reason")
            },
            delayMillis,
        )
    }

    /** The retry button: reconnects now, and that try opens a fresh retry budget. */
    fun reconnectManually() {
        if (!reconnectExhausted || shuttingDown || suspended || resetInProgress) return
        reconnectExhausted = false
        reconnectAttempts = 1
        restart("Manual reconnect")
    }

    /** Full-stack rebuild at the current display size. */
    fun restart(reason: String) {
        if (shuttingDown || suspended || resetInProgress) return
        val size = activeDisplaySize ?: return
        appendLog(reason)
        screenStreams.clear()
        setConnectionStage(reason)
        Log.i(TAG, "$reason; rebuilding stack at ${size.width}x${size.height}")
        val restartGeneration = ++generation
        reconnectScheduled = false
        tearDown { if (!shuttingDown && restartGeneration == generation) maybeStart() }
    }

    /** Ends the running session and blocks new ones until [resume], e.g. while settings are open. */
    fun suspend() {
        if (suspended || shuttingDown) return
        suspended = true
        startAfterReset = false
        reconnectScheduled = false
        reconnectAttempts = 0
        reconnectExhausted = false
        val suspendGeneration = ++generation
        controller?.sendTouch(emptyList())
        screenStreams.clear()
        setConnectionStage("Reconnecting after settings")
        tearDown {
            if (shuttingDown || suspendGeneration != generation) return@tearDown
            if (!suspended && startAfterReset) {
                startAfterReset = false
                maybeStart()
            }
        }
    }

    fun resume() {
        if (!suspended) return
        suspended = false
        notifySessionChanged()
        if (resetInProgress) startAfterReset = true else maybeStart()
    }

    /** Closes the controller and sink off the main thread, then runs [after] on the main thread. */
    private fun tearDown(after: () -> Unit) {
        val oldController = controller
        val oldSink = sink
        val tearDownGeneration = generation
        controller = null
        viewAreas = null
        sink = null
        shownSurfaces.clear()
        activeAirPlaySession = null
        sessionActive = false
        resetInProgress = true
        notifySessionChanged()
        teardownExecutor.execute {
            try {
                oldController?.close()
                oldController?.awaitClosed(CONTROLLER_CLOSE_TIMEOUT_MILLIS)
            } finally {
                try {
                    oldSink?.close()
                } finally {
                    mainHandler.post {
                        if (tearDownGeneration == generation) {
                            resetInProgress = false
                            notifySessionChanged()
                        }
                        after()
                        // A start asked for while this ran (e.g. start() right after stop())
                        // was turned away; maybeStart checks again whether one may run now.
                        maybeStart()
                    }
                }
            }
        }
    }

    /** Stops CarPlay for good; with [terminateProcess] the process ends once it is closed. */
    fun shutdown(terminateProcess: Boolean, reason: String) {
        if (shuttingDown) return
        shuttingDown = true
        generation += 1
        val oldController = controller
        val oldSink = sink
        controller = null
        viewAreas = null
        sink = null
        sessionActive = false
        Log.i(TAG, "shutdown reason=$reason terminateProcess=$terminateProcess")
        teardownExecutor.execute {
            oldController?.close()
            val clean = oldController?.awaitClosed(CONTROLLER_CLOSE_TIMEOUT_MILLIS) ?: true
            oldSink?.close()
            airPlayCommandExecutor.shutdown()
            if (terminateProcess) {
                app.stopService(Intent(app, CarPlayVpnService::class.java))
            }
            log.append("CarPlay stopped; clean=$clean")
            log.close()
            Log.i(TAG, "shutdown complete clean=$clean")
            teardownExecutor.shutdown()
            if (terminateProcess) Process.killProcess(Process.myPid())
        }
    }

    // ---------------------------------------------------------------------------------------
    // Status and logs
    // ---------------------------------------------------------------------------------------

    fun appendLog(message: String) {
        appendFileLog(message)
        clients.forEach { it.onLog(message) }
    }

    private fun appendFileLog(message: String) = log.appendTimestamped(message, System.currentTimeMillis())

    fun setConnectionStage(message: String) {
        connectionStage = message
        clients.forEach { it.onConnectionStage(message) }
    }

    private fun failHandshake(title: String, detail: String) {
        val timeline = handshakeTimeline ?: return
        timeline.fail(title, detail, System.currentTimeMillis())
        lastAttemptFailure = timeline.failure?.let { "${it.title}: ${it.detail}" }
        clients.forEach { it.onTimelineChanged() }
    }

    private fun notifySessionChanged() = clients.forEach { it.onSessionChanged() }

    private fun notifyScreenStreams() = clients.forEach { it.onScreenStreamsChanged() }

    fun mfiTargetLabel(target: MfiTarget): String = when (target) {
        MfiTarget.USB_CH341 -> "USB/CH341"
        MfiTarget.I2C -> "I2C"
        MfiTarget.REMOTE -> "Remote"
        MfiTarget.LOCAL_FILES -> "Local files"
    }

    private fun describe(status: CarPlayStatus): String = when (status) {
        CarPlayStatus.DiscoveringMfi -> "Preparing MFi authentication"
        CarPlayStatus.WaitingForMfi -> "Waiting for MFi coprocessor"
        CarPlayStatus.RequestingMfiPermission -> "Requesting MFi USB permission"
        CarPlayStatus.MfiReady -> "MFi authentication ready"
        CarPlayStatus.StartingHotspot -> "Starting wireless hotspot"
        is CarPlayStatus.HotspotReady ->
            "Hotspot ready: ${status.backend}, ${status.ssid}, ${status.band}, " +
                "channel ${if (status.channel == 0) "auto" else status.channel}"
        CarPlayStatus.WaitingForPairedIphone -> "Waiting for paired iPhone"
        CarPlayStatus.ConnectingBluetooth -> "Connecting Bluetooth"
        CarPlayStatus.RunningWireless -> "Wireless CarPlay control running"
        CarPlayStatus.WirelessActive -> "Wireless CarPlay active"
        CarPlayStatus.DiscoveringIphone -> "Discovering iPhone"
        CarPlayStatus.WaitingForIphone -> "Waiting for iPhone over USB"
        CarPlayStatus.RequestingIphonePermission -> "Requesting iPhone USB permission"
        CarPlayStatus.WaitingForReenumeration -> "Waiting for iPhone re-enumeration"
        CarPlayStatus.SelectingConfiguration -> "Selecting CarPlay configuration"
        CarPlayStatus.OpeningDataPaths -> "Opening USB data paths"
        CarPlayStatus.Pairing -> "Pairing with iPhone"
        CarPlayStatus.ConnectingControl -> "Connecting iAP2 control"
        CarPlayStatus.AttachingNetwork ->
            if (settings?.wirelessEnabled == true) "Starting AirPlay service" else "Attaching NCM/AirPlay network"
        CarPlayStatus.RunningControl -> "CarPlay control running"
        CarPlayStatus.ControlEnded -> "CarPlay control window ended"
        is CarPlayStatus.Failed -> "Failed: ${status.message}"
    }

    private fun idleRetiringSingleThreadExecutor(): ExecutorService =
        ThreadPoolExecutor(
            1, 1, IDLE_WORKER_KEEP_ALIVE_SECONDS, TimeUnit.SECONDS,
            LinkedBlockingQueue(),
        ).apply { allowCoreThreadTimeOut(true) }
}
