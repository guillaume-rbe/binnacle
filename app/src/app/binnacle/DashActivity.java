package app.binnacle;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.Toast;

/**
 * The dash: left panel | centre window | right panel, in the mockup's 340 / 1240 / 340 proportions.
 * Steering wheel: scroll up/down moves the dock selection, right tilt (ENTER) opens it.
 */
public class DashActivity extends Activity implements DashApp.Listener, RightPanelView.DockListener {

    private static final int REQ_CAMERA = 2;
    private static final long FOCUS_TIMEOUT_MS = 8000;
    /** CarPlay drew in the dash and then stopped: how long it may take to come back before the dash shows again. */
    private static final long CARPLAY_LOST_MS = 30_000;

    private DashApp app;
    private LeftPanelView left;
    private CentreView centre;
    private CameraPane camera;
    /** CarPlay in the dash's own window (xcertplay's service), when xcertplay allows it. */
    private CarPlayLink carplayLink;
    private CarPlayPane carplay;
    /** CarPlay auto-connect at start and wake (off by default), behind the dash until it connects. */
    private CarPlayAuto carplayAuto;
    private static final int CARPLAY_OFF = 0, CARPLAY_CENTRE = 1, CARPLAY_FULL = 2;
    private int carplayMode = CARPLAY_OFF;
    private LinearLayout root;
    private FrameLayout centreFrame;
    /** The sizes last declared to xcertplay (centre w, h, full w, h). */
    private int[] declaredSizes;
    /** The centre window's inner size, from the last layout that wasn't full screen. */
    private int centreW, centreH;
    private boolean destroying;
    /** Theme the views were built in (Style.light at onCreate). */
    private boolean lightBuilt;
    private boolean lastFrontCam, askedCamera, permissionDialogOpen, resumed, manualCameraPending;
    private RightPanelView right;
    private int focus = -1;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable clearFocus = new Runnable() {
        @Override public void run() { setFocus(-1); }   // the dock times out: the wheel goes back to CarPlay
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        lightBuilt = Style.light;
        driveCamera = Launch.takeDriveCamera() && state == null;
        if (state != null && state.containsKey(KEY_THEME_CAMERA)) {
            themeCamera = state.getBoolean(KEY_THEME_CAMERA);
            themeCameraAuto = state.getBoolean(KEY_THEME_CAMERA_AUTO, true);
        }
        if (state != null) restoreCarplayMode = state.getInt(KEY_CARPLAY_MODE, CARPLAY_OFF);
        app = DashApp.get(this);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        left = new LeftPanelView(this, app.car);
        left.setOnPowerTap(new Runnable() {
            @Override public void run() { if (app.prefs().getBoolean(Screenshot.KEY, false)) Screenshot.take(DashActivity.this, left); }
        });
        centre = new CentreView(this, app);
        right = new RightPanelView(this, app);
        right.setDockListener(this);

        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setBackgroundColor(Style.BG);
        root.addView(left, new LinearLayout.LayoutParams(0, -1, Style.SIDE_W));
        camera = new CameraPane(this, new CameraPane.Listener() {
            @Override public void onCameraProblem(String problem) {
                Toast.makeText(DashActivity.this, problem, Toast.LENGTH_LONG).show();
            }
            @Override public void onCameraState(boolean on) {
                if (right != null) right.setCameraOn(on);
                if (left != null) left.setCentreCovered(on || carplayMode == CARPLAY_CENTRE);
                // the Camera button keeps its highlight while the camera is up (no timeout), so OK
                // closes it again; once it closes, the highlight times out as usual
                if (focus == RightPanelView.DOCK_CAMERA) setFocus(focus);
            }
        });
        camera.setVisibility(View.GONE);
        carplayLink = new CarPlayLink(this, carplayListener);
        carplay = new CarPlayPane(this, carplayLink);
        carplayAuto = new CarPlayAuto(this, app.prefs(), carplayLink, new CarPlayAuto.Host() {
            @Override public void showConnectedCarPlay() {
                // only over the dash itself: another app in front (Settings, the stock camera) stays
                if (resumed && carplayMode == CARPLAY_OFF) showCarPlay(CARPLAY_CENTRE);
            }
        });
        carplay.setVisibility(View.GONE);
        carplay.setOnStatusTap(new View.OnClickListener() {
            @Override public void onClick(View v) {
                int s = carplayLink.state();
                if (s == CarPlayLink.STATE_NEEDS_SETUP || s == CarPlayLink.STATE_PAUSED) carplayLink.openSettings();
                else carplayLink.start();
            }
        });
        // the camera and CarPlay sit exactly over the centre's rounded window (14 design px inset);
        // full-screen CarPlay fills it edge to edge (the side panels are hidden then)
        centreFrame = new FrameLayout(this) {
            @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
                int w = r - l, h = b - t, inset = Math.round(14f * h / Style.DESIGN_H);
                centre.layout(0, 0, w, h);
                int ci = carplayMode == CARPLAY_FULL ? 0 : inset;
                carplay.measure(View.MeasureSpec.makeMeasureSpec(w - 2 * ci, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(h - 2 * ci, View.MeasureSpec.EXACTLY));
                carplay.layout(ci, ci, w - ci, h - ci);
                camera.layout(inset, inset, w - inset, h - inset);
                centre.attentionOverlay.layout(0, 0, w, h);
                if (carplayMode != CARPLAY_FULL) { centreW = w - 2 * inset; centreH = h - 2 * inset; }
                noteCarPlaySizes();   // declared before CarPlay is first shown
            }
        };
        centreFrame.addView(centre);
        centreFrame.addView(carplay);      // below the camera: the camera covers CarPlay, its sound goes on
        centreFrame.addView(camera);
        centreFrame.addView(centre.attentionOverlay);   // above the camera
        root.addView(centreFrame, new LinearLayout.LayoutParams(0, -1, 1920f - 2 * Style.SIDE_W));
        root.addView(right, new LinearLayout.LayoutParams(0, -1, Style.SIDE_W));
        setContentView(root);
        // no storage permission: the car renders are bundled (or in this app's own files dir)
        carplayLink.bind();     // false when xcertplay is missing or signed differently: its own screen then
        IntentFilter screen = new IntentFilter(Intent.ACTION_SCREEN_OFF);
        screen.addAction(Intent.ACTION_SCREEN_ON);
        registerReceiver(screenOff, screen);
    }

    /**
     * The car "sleeping" only turns the dash's screen off; apps keep running. When it woke, CarPlay
     * was still in the centre and started at once, before the car's Bluetooth was back (owner,
     * 5 Nov). So when the screen goes off while parked, CarPlay ends and the dash goes back to its own view; on
     * wake CarPlay waits for a tap on CarPlay (the owner's choice), unless CarPlay auto-connect is
     * on (CarPlayAuto: it waits for the car's Bluetooth audio first, then tries behind the dash).
     */
    private final BroadcastReceiver screenOff = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            if (Intent.ACTION_SCREEN_ON.equals(i.getAction())) { carplayAuto.onScreenOn(); return; }
            // the car sleeps only when parked: a screen time-out behind another app while driving
            // (Android's 10 min, the dash keeps its own screen on) must not end CarPlay, nor an
            // auto-connect attempt (it keeps its 90 s limit and its show-on-connect)
            if (app.car.moving() || app.car.speedKmh() >= 1) return;
            carplayAuto.onScreenOff();   // CarPlay is ended just below
            if (carplayMode != CARPLAY_OFF) showCarPlay(CARPLAY_OFF);
            if (carplayLink.connected() && CarPlayLink.running(carplayLink.state())) carplayLink.disconnect();
        }
    };

    @Override protected void onDestroy() {
        destroying = true;      // the unbind below is not xcertplay going away
        handler.removeCallbacks(carplayLost);
        carplayAuto.release();
        try { unregisterReceiver(screenOff); } catch (RuntimeException ignored) { }
        // closed for good (not recreated): nothing will hear CarPlay end, so scanning goes back on now
        if (!isChangingConfigurations()) WifiScans.forCarPlay(app.prefs(), false);
        carplay.release();      // xcertplay lets go of the surface while we are still bound
        carplayLink.unbind();   // the CarPlay session itself keeps running in xcertplay
        super.onDestroy();
    }

    // ---- CarPlay in the dash ----

    private final CarPlayLink.Listener carplayListener = new CarPlayLink.Listener() {
        @Override public void onLinkChanged(boolean connected) {
            if (!connected) right.setNowPlaying(null);
            else XcertplayLocation.restore();     // after a reboot or an xcertplay update, before CarPlay starts
            if (connected) {
                declaredSizes = null;
                noteCarPlaySizes();     // before the surface goes over, so the session starts knowing both
                if (carplayMode == CARPLAY_OFF && restoreCarplayMode != CARPLAY_OFF) showCarPlay(restoreCarplayMode);
                restoreCarplayMode = CARPLAY_OFF;
                // a pane already on screen tried to hand over its surface before the link was up
                // (after a restart its surface already exists, so nothing else would resend it)
                if (carplayMode != CARPLAY_OFF) carplay.requestAttach();   // also asks for CarPlay
                carplayAuto.onLinkUp();         // the first link since the dash started counts as a start
            } else if (carplayMode != CARPLAY_OFF) {
                showCarPlay(CARPLAY_OFF);       // xcertplay went away; the dock opens its own screen now
            }
            // xcertplay went away (not just this screen closing, whose unbind also lands here):
            // no CarPlay without it, so scanning back on
            if (!connected && !destroying) WifiScans.forCarPlay(app.prefs(), false);
        }
        @Override public void onVideoLayout(int[] video) {
            carplay.setVideoLayout(video);
        }
        @Override public void onNowPlaying(NowPlaying now) {
            right.setNowPlaying(now);
        }
        @Override public void onCarPlayState(int state, String stage) {
            carplay.showState(state, stage);
            watchCarPlayLost(state);
            carplayAuto.onCarPlayState(state);
            WifiScans.forCarPlay(app.prefs(), CarPlayLink.running(state));
        }
        @Override public void onHostUi() {
            // CarPlay's Binnacle tile: from full screen back to the dash with CarPlay in the centre;
            // from the centre, back to the dash's own centre view (CarPlay keeps running)
            showCarPlay(carplayMode == CARPLAY_FULL ? CARPLAY_CENTRE : CARPLAY_OFF);
        }
    };

    /**
     * The centre window's size (measured) and the whole screen: both are declared to xcertplay, so
     * switching between them needs no reconnect. Sent again whenever either changes.
     */
    private void noteCarPlaySizes() {
        int fullW = root.getWidth(), fullH = root.getHeight();
        if (centreW <= 0 || centreH <= 0 || fullW <= 0 || fullH <= 0 || !carplayLink.connected()) return;
        int[] sizes = {centreW, centreH, fullW, fullH};
        if (java.util.Arrays.equals(sizes, declaredSizes)) return;
        declaredSizes = sizes;
        carplayLink.declareSizes(sizes);
    }

    /**
     * A CarPlay window that was drawing and then lost its session (the phone dropped it, or
     * xcertplay rebuilt a session that had stopped sending a picture) goes back to the dash if
     * CarPlay isn't drawing again within CARPLAY_LOST_MS, so a half-dropped session never leaves a
     * "Connecting" or black window up (car, 6 Nov: 15 minutes of black window, Now playing still
     * up). The dock's CarPlay button opens it again; xcertplay keeps reconnecting meanwhile.
     */
    private boolean carplayDrew;
    private final Runnable carplayLost = new Runnable() {
        @Override public void run() {
            if (carplayMode == CARPLAY_OFF) return;
            android.util.Log.i("binnacle", "CarPlay hasn't drawn for " + CARPLAY_LOST_MS / 1000 + " s; back to the dash");
            showCarPlay(CARPLAY_OFF);
            right.setNowPlaying(null);
        }
    };

    private void watchCarPlayLost(int state) {
        if (state == CarPlayLink.STATE_STREAMING) {
            carplayDrew = true;
            handler.removeCallbacks(carplayLost);
        } else if (carplayDrew && carplayMode != CARPLAY_OFF && state != CarPlayLink.STATE_PAUSED) {
            // once per loss: later states (connecting, failed) don't restart the wait
            carplayDrew = false;
            handler.postDelayed(carplayLost, CARPLAY_LOST_MS);
        } else if (state == CarPlayLink.STATE_PAUSED) {
            handler.removeCallbacks(carplayLost);   // xcertplay's settings are open: the user is there
        }
    }

    private void showCarPlay(int mode) {
        if (mode == CARPLAY_FULL && camera.isRunning()) mode = CARPLAY_CENTRE;   // no full screen under the camera
        if (mode == carplayMode) return;
        // centre <-> full: hide the iPhone's blur and snap while it redraws at the new size
        if (mode != CARPLAY_OFF && carplayMode != CARPLAY_OFF) carplay.coverForResize();
        carplayMode = mode;
        wheelOnDock = false;                      // a newly shown CarPlay gets the wheel
        boolean full = mode == CARPLAY_FULL;
        left.setVisibility(full ? View.GONE : View.VISIBLE);
        right.setVisibility(full ? View.GONE : View.VISIBLE);
        carplay.setVisibility(mode == CARPLAY_OFF ? View.GONE : View.VISIBLE);
        centre.setCovered(mode != CARPLAY_OFF);   // the scene under CarPlay stops drawing (audit D9)
        left.setCentreCovered(mode == CARPLAY_CENTRE || camera.isRunning());   // its Autopilot cue
        carplay.setRounded(!full);
        centreFrame.requestLayout();
        if (mode != CARPLAY_OFF) {
            carplayAuto.userTookOver();   // CarPlay is on screen now: a background attempt is not ours to end
            carplay.requestAttach();   // the new size, or a surface that already existed
            // reopened on a session that is already drawing: xcertplay sends no new state for it
            if (carplayLink.state() == CarPlayLink.STATE_STREAMING) carplayDrew = true;
        } else {
            setFocus(-1);
            handler.removeCallbacks(carplayLost);
            carplayDrew = false;
        }
    }

    /**
     * With CarPlay in the centre, the wheel is either CarPlay's (scroll, OK and media keys go to
     * the phone) or the dock's (scroll and OK pick a dash button). Left tilt (BACK) moves it
     * between them; full screen has no dock, so there BACK drops back to the centre window. OK on
     * the dock's CarPlay button (as a tap) makes the centre window full screen.
     */
    private boolean wheelOnDock;

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        // BACK is decided on its press: a press that closed the camera must not also step CarPlay
        // back on its release (the camera is gone by then)
        if (event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0)
                backForCarPlay = carplayMode != CARPLAY_OFF && !camera.isRunning();
            if (backForCarPlay) {
                if (event.getAction() == KeyEvent.ACTION_UP) {
                    backForCarPlay = false;
                    if (event.isCanceled()) return true;
                    if (carplayMode == CARPLAY_FULL) showCarPlay(CARPLAY_CENTRE);
                    else if (wheelOnDock || focus >= 0) setFocus(-1);                   // wheel back to CarPlay
                    else { setFocus(RightPanelView.DOCK_PHONE); wheelOnDock = true; }   // wheel to the dock
                }
                return true;
            }
            return super.dispatchKeyEvent(event);
        }
        // Who gets a key is decided on its press and kept for its release, so a dock OK that hands
        // the wheel back can't send CarPlay a release without a press. While the dock has the wheel,
        // scroll and OK stay with the dash; media keys still go to CarPlay.
        int code = event.getKeyCode();
        if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
            boolean toCarPlay = carplayMode != CARPLAY_OFF && !camera.isRunning() && !(wheelOnDock && isNavKey(code));
            keysToCarPlay.put(code, toCarPlay);
        }
        boolean toCarPlay = keysToCarPlay.get(code, false);
        if (event.getAction() == KeyEvent.ACTION_UP) keysToCarPlay.put(code, false);   // this press is over
        if (toCarPlay && carplayLink.key(event)) return true;
        return super.dispatchKeyEvent(event);
    }

    /** Per key code: whether its current press went to CarPlay. */
    private final android.util.SparseBooleanArray keysToCarPlay = new android.util.SparseBooleanArray();

    private static boolean isNavKey(int code) {
        return code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN
                || code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT
                || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_DPAD_CENTER
                || code == KeyEvent.KEYCODE_NUMPAD_ENTER;
    }

    /** The current BACK press belongs to CarPlay (decided on its key-down). */
    private boolean backForCarPlay;

    /**
     * First start: the setup wizard (SetupActivity), until it has been finished or skipped once.
     * Checked on every resume: picking Binnacle in Android's Home list brings the dash (a singleTask
     * Home activity) to the front over the wizard, which then carries on from where it was.
     */
    private void firstStartQuestions() {
        // not while driving: the wizard closes itself when the car drives off and comes back in Park (audit D2)
        if (!SetupActivity.done(app.prefs()) && DriveWatch.parked(app.car)) SetupActivity.start(this);
    }

    @Override public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        permissionDialogOpen = false;
        if (code == REQ_CAMERA) {
            boolean granted = results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED;
            boolean manual = manualCameraPending;
            manualCameraPending = false;
            if (granted && (manual || app.car.frontCamRequest)) { startDashCamera(); cameraAuto = !manual; }
            if (!granted && dashCamera()) {
                // no camera in the dash: Drive opens the stock camera screen instead (audit #2b)
                Toast.makeText(this, "Camera not allowed: the stock camera opens on Drive", Toast.LENGTH_LONG).show();
                app.stockCameraFallback();
            }
            return;
        }
    }

    /**
     * The drive logger (separate app app.binnacle.logger, capture/) is force-stopped with every
     * car sleep; the dash comes back as Home, so it restarts the logger's service each time it
     * starts. Does nothing if the logger isn't installed. Receive-only like the dash.
     */
    private void startLogger() {
        if (!app.prefs().getBoolean("logger_autostart", false)) return;   // opt-in only (Advanced -> Drive logger)
        try {
            android.content.Intent i = new android.content.Intent().setComponent(
                    new android.content.ComponentName("app.binnacle.logger", "app.binnacle.logger.CaptureService"));
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
        } catch (RuntimeException e) {
            android.util.Log.i("binnacle", "drive logger not started: " + e.getMessage());
        }
    }

    @Override protected void onStart() {
        super.onStart();
        startLogger();
        app.checkLogger();                  // the logger badge is always current when the dash shows
        app.startCompass();                 // no-op once running; picks up a location grant made since
        // the theme changed while we were stopped (e.g. set in Apps & settings): rebuild in it
        if (lightBuilt != Style.light) { recreate(); return; }
        android.content.IntentFilter f = new android.content.IntentFilter("android.net.wifi.WIFI_AP_STATE_CHANGED");
        f.addAction(android.net.ConnectivityManager.CONNECTIVITY_ACTION);
        f.addAction(android.net.wifi.WifiManager.NETWORK_STATE_CHANGED_ACTION);
        registerReceiver(netReceiver, f);              // sticky AP state arrives at once
        updateNet();
        app.addListener(this);
        onCarChanged(-1);
    }

    /** Brought forward by DashApp because the car shifted into Drive behind another screen (audit D1). */
    private boolean driveCamera;

    @Override protected void onNewIntent(android.content.Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (Launch.takeDriveCamera()) driveCamera = true;   // never read from the intent's extras
    }

    @Override protected void onResume() {
        super.onResume();
        firstStartQuestions();
        resumed = true;
        app.setDashResumed(true);
        boolean drive = driveCamera && !app.isDemo();
        driveCamera = false;
        carplayLink.retry();    // a bind refused at boot (power manager not ready yet) connects now
        // a pause setting changed in Settings applies on return (once xcertplay has said where it is)
        if (carplayLink.connected()) WifiScans.forCarPlay(app.prefs(), CarPlayLink.running(carplayLink.state()));
        lastFrontCam = app.car.frontCamRequest;     // seed the edge detector with the current state
        if (!dashCamera()) return;
        // demo data never opens the real camera (audit D3)
        boolean flag = (lastFrontCam || drive) && !app.isDemo();
        if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            // ask once per screen session; a denial must not re-prompt on every resume
            if (!askedCamera && !permissionDialogOpen) {
                askedCamera = true;
                permissionDialogOpen = true;
                requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
            }
        } else if (themeCamera != null ? themeCamera : flag) {
            startDashCamera();
            cameraAuto = themeCamera == null || themeCameraAuto;   // a restored dock-opened camera stays manual
        }
        themeCamera = null;
    }

    /** Camera open/closed as it was before a theme recreation, so the palette change keeps the user's view. */
    private Boolean themeCamera;
    private boolean cameraWasRunning, themeCameraAuto;
    private static final String KEY_THEME_CAMERA_AUTO = "theme_camera_auto";
    private static final String KEY_THEME_CAMERA = "theme_camera";
    private static final String KEY_CARPLAY_MODE = "carplay_mode";
    /** CarPlay shown before a theme recreation; shown again once the link is back. */
    private int restoreCarplayMode = CARPLAY_OFF;

    @Override protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putBoolean(KEY_THEME_CAMERA, cameraWasRunning);
        out.putBoolean(KEY_THEME_CAMERA_AUTO, cameraAuto);
        out.putInt(KEY_CARPLAY_MODE, carplayMode);
    }

    /** The in-dash camera closes itself above this speed (the owner, 29 Oct), or with the Camera button. */
    private static final int CAMERA_OFF_KMH = 15;
    /** Opened by the car's flag (closes itself); false when opened from the dock (stays until closed). */
    private boolean cameraAuto;
    private int lastGear = CarState.GEAR_UNKNOWN;

    /**
     * Open the in-dash camera when the car raises its forward-camera flag (shift to Drive). The
     * car drops the flag again after a few seconds; the camera stays up until the car passes
     * CAMERA_OFF_KMH, goes back to Park, or the Camera button closes it (in-car feedback: the
     * stock timeout was too short).
     */
    private void followFrontCam() {
        boolean req = app.car.frontCamRequest;
        if (req != lastFrontCam) {
            lastFrontCam = req;
            // only the flag's own opening is automatic: a camera already opened from the dock stays manual
            if (req && dashCamera() && resumed && !app.isDemo() && !camera.isRunning()) { startDashCamera(); cameraAuto = true; }
        }
        // Park closes it on the change into Park, not on the gear value: code 16 can arrive before the
        // code 11 that leaves Park, and a stale P must not close the camera the flag just opened
        int gear = app.car.gear;
        boolean intoPark = gear == CarState.GEAR_P && lastGear != CarState.GEAR_P && lastGear != CarState.GEAR_UNKNOWN;
        // a feed blip (gear unknown) keeps the last known gear, so D, blip, P still closes the camera
        if (gear != CarState.GEAR_UNKNOWN) lastGear = gear;
        if (cameraAuto && camera.isRunning() && (app.car.speedKmh() > CAMERA_OFF_KMH || intoPark)) camera.stop();
    }

    @Override protected void onPause() {
        resumed = false;
        app.setDashResumed(false);             // paused: feed updates must not reopen the camera; onResume reconciles
        cameraWasRunning = camera.isRunning();   // onSaveInstanceState runs after this (API 28+)
        camera.stop();               // never hold the camera while another app (or stock frontcam) is in front
        super.onPause();
    }

    /** Experimental in-dash camera (Dash settings), off by default. */
    private boolean dashCamera() {
        return app.prefs().getBoolean("dash_camera", false);
    }

    private void startDashCamera() {
        // the camera goes in the centre window: over full-screen CarPlay it was stretched to the
        // screen's width with CarPlay showing below it (car, 5 Nov), so CarPlay steps back first
        if (carplayMode == CARPLAY_FULL) showCarPlay(CARPLAY_CENTRE);
        camera.start(30);   // the camera's own rate (TVI 1080p30); FramePacer evens it out on the 56 Hz panel
    }

    /**
     * Home Wi-Fi or CarPlay's hotspot, for the right panel's network icon. The single Wi-Fi radio is
     * either joined to a network (station) or serving CarPlay's access point (WIFI_AP_STATE 13).
     */
    private final android.content.BroadcastReceiver netReceiver = new android.content.BroadcastReceiver() {
        @Override public void onReceive(android.content.Context c, android.content.Intent i) {
            if ("android.net.wifi.WIFI_AP_STATE_CHANGED".equals(i.getAction())) apOn = i.getIntExtra("wifi_state", 0) == 13;
            updateNet();
        }
    };
    private boolean apOn;

    /** The network icon's last state (RightPanelView.NET_*). */
    private int netMode = RightPanelView.NET_NONE;

    private void updateNet() {
        int mode = RightPanelView.NET_NONE;
        try {
            android.net.ConnectivityManager cm = (android.net.ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            android.net.NetworkInfo ni = cm == null ? null : cm.getActiveNetworkInfo();
            if (apOn) mode = RightPanelView.NET_HOTSPOT;
            else if (ni != null && ni.isConnected() && ni.getType() == android.net.ConnectivityManager.TYPE_WIFI) mode = RightPanelView.NET_WIFI;
        } catch (RuntimeException ignored) {
        }
        netMode = mode;
        right.setNet(mode);
    }

    /**
     * Network icon tapped: offer the other mode (start CarPlay, or end it and rejoin home Wi-Fi).
     * With xcertplay the dash stays on home Wi-Fi, so there is nothing to offer unless ZLink's
     * hotspot is somehow up (the owner, 1 Nov: no ZLink steps from this icon).
     */
    /** Holding the CarPlay button hides CarPlay; it keeps running (music, calls) behind the dash. */
    @Override public void onDockHold(int index) {
        if (index != RightPanelView.DOCK_PHONE) { onDock(index); return; }   // others: as a tap
        if (carplayMode != CARPLAY_OFF) {
            showCarPlay(CARPLAY_OFF);
            Toast.makeText(this, "CarPlay hidden, still running", Toast.LENGTH_SHORT).show();
        } else onDock(index);
    }

    /** The Wi-Fi icon's messages show beside it on the dash (owner, 8 Nov: a tap gave no sign it did anything). */
    private final WifiScans.Note netNote = new WifiScans.Note() {
        @Override public void show(String text, boolean busy) { right.netNote(text, busy); }
    };

    @Override public void onNetIcon() {
        final boolean hotspot = apOn;
        if (!hotspot && Launch.hasXcertplay(this)) {
            // xcertplay keeps the dash on home Wi-Fi; off it (scanning paused, out of range), the
            // icon rejoins the saved network (WifiScans)
            if (netMode == RightPanelView.NET_WIFI) { right.netNote("On home Wi-Fi", false); return; }
            boolean running = carplayLink.connected() && CarPlayLink.running(carplayLink.state());
            if (running && WifiScans.pausedForCarPlay(app.prefs())) {
                // scanning is paused for CarPlay: offer to end it, then rejoin (owner, 5 Nov: there
                // was no way to end CarPlay from the dash)
                BinnacleDialog.show(this, "CarPlay is on", "End CarPlay and rejoin home Wi-Fi?",
                        new String[]{"Cancel", "End CarPlay"}, true, new BinnacleDialog.Choice() {
                            @Override public void chosen(int index) {
                                if (index != 1) return;
                                carplayLink.disconnect();                  // xcertplay stops and stays stopped
                                showCarPlay(CARPLAY_OFF);
                                WifiScans.rejoin(DashActivity.this, app.prefs(), false, netNote);
                            }
                        });
                return;
            }
            WifiScans.rejoin(this, app.prefs(), running, netNote);
            return;
        }
        BinnacleDialog.show(this, hotspot ? "On CarPlay's hotspot" : "On home Wi-Fi",
                hotspot ? "End CarPlay and switch back to home Wi-Fi?" : "Start CarPlay? The dash leaves home Wi-Fi while CarPlay is on.",
                new String[]{"Cancel", hotspot ? "Switch to Wi-Fi" : "Start CarPlay"}, true, new BinnacleDialog.Choice() {
                    @Override public void chosen(int index) {
                        if (index != 1) return;
                        if (!hotspot) { Launch.phone(DashActivity.this); return; }
                        new Thread(new Runnable() {
                            @Override public void run() {
                                String msg;
                                boolean ok = false;
                                try { LocalShell.Command cmd = LocalShell.Command.END_CARPLAY; LocalShell.run(cmd); msg = "Looking for home Wi-Fi…"; ok = true; }
                                catch (Exception e) { msg = "Couldn't switch: " + e.getMessage(); }
                                final String shown = msg;
                                final boolean busy = ok;
                                runOnUiThread(new Runnable() {
                                    @Override public void run() { right.netNote(shown, busy); }
                                });
                            }
                        }, "local-shell").start();
                    }
                });
    }

    @Override protected void onStop() {
        try { unregisterReceiver(netReceiver); } catch (RuntimeException ignored) { }
        app.removeListener(this);
        handler.removeCallbacks(clearFocus);
        super.onStop();
    }

    @Override public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level >= TRIM_MEMORY_RUNNING_LOW) centre.trimMemory();
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemBars();
    }

    private void hideSystemBars() {
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
    }

    @Override public void onCarChanged(int code) {
        if (code == DashApp.THEME_CHANGED) { recreate(); return; }   // every view rebuilds its paints in the new colours
        switch (code) {
            case 10:
                followFrontCam();                  // speed: close the camera once past CAMERA_OFF_KMH
                left.invalidate();
                centre.invalidate();
                right.invalidate();                // speed decides the sensors / tyres card
                break;
            case 11:
                followFrontCam();                  // gear: close the camera in Park
                left.invalidate();
                centre.invalidate();
                right.invalidate();
                break;
            case 12:
                right.invalidate();
                break;
            case 15:                            // doors: the centre car animates them and shows the badge
            case 16:
                followFrontCam();
                centre.invalidate();
                break;
            case 3: case 4:                    // parking sensors: centre arcs and the right-hand sensor card
                centre.invalidate();
                right.invalidate();
                break;
            case 2:
            case DashApp.HEADING_CHANGED:      // GPS compass, top right of the centre window
                centre.invalidate();
                break;
            case DashApp.LOGGER_CHANGED:       // logger badge (centre) and eye (beside the Wi-Fi icon)
                centre.invalidate();
                right.invalidate();
                break;
            default:
                followFrontCam();                  // e.g. a feed reset clears the flag
                left.invalidate();
                centre.invalidate();
                right.invalidate();
        }
    }

    @Override public void onDock(int index) {
        wheelOnDock = false;
        setFocus(-1);
        switch (index) {
            case RightPanelView.DOCK_PHONE:
                // CarPlay in the centre window, then full screen; xcertplay's own screen (or ZLink)
                // when the dash can't draw it
                if (carplayLink.connected()) showCarPlay(carplayMode == CARPLAY_CENTRE ? CARPLAY_FULL : CARPLAY_CENTRE);
                else Launch.phone(this);
                break;
            case RightPanelView.DOCK_CAMERA:
                if (dashCamera()) {
                    if (camera.isRunning()) camera.stop();
                    else if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                        permissionDialogOpen = true;   // an explicit tap may ask again
                        manualCameraPending = true;    // and opens the camera once granted
                        requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
                    } else { startDashCamera(); cameraAuto = false; }
                }
                else Launch.camera(this);
                break;
            case RightPanelView.DOCK_APPS: Launch.apps(this); break;
        }
    }

    private void setFocus(int i) {
        focus = i;
        if (i < 0) wheelOnDock = false;      // no highlight: the wheel is CarPlay's again (when it is shown)
        right.setFocusIndex(i);
        handler.removeCallbacks(clearFocus);
        if (i >= 0 && !(i == RightPanelView.DOCK_CAMERA && camera.isRunning()))
            handler.postDelayed(clearFocus, FOCUS_TIMEOUT_MS);
    }

    @Override public boolean onKeyDown(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                setFocus(focus < 0 ? 0 : (focus + 1) % RightPanelView.DOCK_COUNT);
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_LEFT:
                setFocus(focus <= 0 ? RightPanelView.DOCK_COUNT - 1 : focus - 1);
                return true;
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (focus == RightPanelView.DOCK_CAMERA) {
                    // the camera opens or closes and the highlight stays, so OK again undoes it
                    boolean dock = wheelOnDock;
                    onDock(focus);
                    setFocus(RightPanelView.DOCK_CAMERA);
                    wheelOnDock = dock;
                } else if (focus >= 0) onDock(focus); else setFocus(0);   // OK on CarPlay in the centre: full screen
                return true;
            case KeyEvent.KEYCODE_BACK:
                if (focus >= 0) { setFocus(-1); return true; }
                if (camera.isRunning()) { camera.stop(); return true; }
                // As the Home app there is nothing behind us; swallow BACK instead of finishing.
                if (Launch.isDefaultHome(this)) return true;
                break;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override public void onBackPressed() {
        if (Launch.isDefaultHome(this)) return;
        super.onBackPressed();
    }
}
