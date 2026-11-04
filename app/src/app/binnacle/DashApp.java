package app.binnacle;

import android.app.Application;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.preference.PreferenceManager;

import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Owns the one CarState and the active feed for the whole process, so the forward-camera trigger
 * keeps working while CarPlay or the camera is in front of the dash.
 */
public class DashApp extends Application implements Feed.Sink, SharedPreferences.OnSharedPreferenceChangeListener {

    public interface Listener {
        void onCarChanged(int code);
    }

    /** Same debounce as the stock launcher: at most one camera open/close per second. */
    private static final long CAMERA_DEBOUNCE_MS = 1000;
    /**
     * Feed watchdog: while the car is moving the CAN app sends about 7 messages a second. This long
     * with nothing at all means the data stopped (canapp alive but silent: a stalled serial link, an
     * MCU reset), and the last speed and gear must not stay on screen as if live. Logger captures
     * 28-31 Oct (~45,600 speed messages in D/R while moving): gap between any two messages median
     * 0.14 s, p99.9 0.98 s, longest 2.6 s (speed alone pauses up to ~3 s cruising, ~6 s crawling,
     * so it re-arms on any code), so 8 s is ~3x the worst real gap. Not checked while stopped,
     * when the car may legitimately have nothing new to say.
     */
    static final long STALE_MS = 8000;
    static final String STALE_PROBLEM = "No car data, reconnecting";

    public final CarState car = new CarState();
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private final Handler main = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    private Feed feed;
    private boolean demo;
    private String problem;
    private boolean lastFrontCam;
    private long lastCameraActionMs;
    private CarArt art;
    private int artColour = Integer.MIN_VALUE;
    private boolean artLoading;
    private boolean rainbowRoad;

    /**
     * The car sends lights/info (code 9) only when something changes, not when we subscribe, so
     * after the dash restarts (an update, a crash) the light and warning icons stayed blank until
     * the next change. The last code 9 is kept here and restored if it is recent enough to still be
     * true. Momentary values (indicators, the attention pulse) are never restored.
     */
    private static final String LIGHTS = "last_code9";
    private static final long LIGHTS_MAX_AGE_MS = 30 * 60_000L;
    private static final java.util.Set<String> NOT_RESTORED = new java.util.HashSet<>(
            java.util.Arrays.asList("leftlight", "rightlight", "driverwarning"));

    private void saveLights(Bundle b) {
        SharedPreferences.Editor e = getSharedPreferences(LIGHTS, MODE_PRIVATE).edit().clear();
        for (String k : b.keySet()) {
            if (NOT_RESTORED.contains(k)) continue;
            Object v = b.get(k);
            if (v instanceof Boolean) e.putBoolean(k, (Boolean) v);
            else if (v instanceof Integer) e.putInt(k, (Integer) v);
        }
        e.putLong("_at", System.currentTimeMillis()).apply();
    }

    private void restoreLights() {
        SharedPreferences p = getSharedPreferences(LIGHTS, MODE_PRIVATE);
        long age = System.currentTimeMillis() - p.getLong("_at", 0);
        if (age < 0 || age > LIGHTS_MAX_AGE_MS) return;
        Bundle b = new Bundle();
        for (java.util.Map.Entry<String, ?> en : p.getAll().entrySet()) {
            if (en.getKey().startsWith("_")) continue;
            Object v = en.getValue();
            if (v instanceof Boolean) b.putBoolean(en.getKey(), (Boolean) v);
            else if (v instanceof Integer) b.putInt(en.getKey(), (Integer) v);
        }
        if (!b.isEmpty()) car.apply(9, b, false);
    }

    /** 7:00 to 18:59 local time: a guess for Auto before the car has said anything. */
    private static boolean daytime() {
        int h = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY);
        return h >= 7 && h < 19;
    }

    public static DashApp get(android.content.Context c) { return (DashApp) c.getApplicationContext(); }

    @Override public void onCreate() {
        super.onCreate();
        prefs = PreferenceManager.getDefaultSharedPreferences(this);
        prefs.registerOnSharedPreferenceChangeListener(this);
        applyTextSize();
        // until the car's screen level is known, Auto starts in the theme it last used; a fresh install
        // has none, and the CAN app only sends the level when it changes, so it goes by the clock
        String mode = prefs.getString("theme", "auto");
        Style.applyTheme("light".equals(mode) || ("auto".equals(mode) && prefs.getBoolean("theme_last_light", daytime())));
        rainbowRoad = prefs.getBoolean("autopilot_rainbow", false);
        powerEstimatedSaved = prefs.getBoolean(POWER_ESTIMATED, false);   // applied in startFeed
        readDrawPrefs();
        startFeed();
        startCompass();
        StartupVolume.start(this, prefs);
        watchLogger();
        new Thread(new Runnable() {
            @Override public void run() { CarPacks.removeLegacy(DashApp.this); }
        }, "car-pack-cleanup").start();
    }

    // ---- the optional drive logger must never run unnoticed: the dash always shows a badge ----

    /** onCarChanged code: a logger was installed/removed or started/stopped recording. */
    public static final int LOGGER_CHANGED = -5;
    static final String LOGGER_PKG = "app.binnacle.logger", OLD_LOGGER_PKG = "com.lotech.tesladash";
    private static final String LOGGER_PERMISSION = "app.binnacle.permission.CONTROL_LOGGER";
    private boolean loggerInstalled, oldLoggerInstalled, loggerRecording;

    /** A logger app is installed (this one or the old one that also recorded location). */
    public boolean loggerInstalled() { return loggerInstalled; }
    public boolean oldLoggerInstalled() { return oldLoggerInstalled; }
    /** The logger reported that it is recording. */
    public boolean loggerRecording() { return loggerRecording; }

    private void watchLogger() {
        android.content.IntentFilter pkgs = new android.content.IntentFilter();
        pkgs.addAction(android.content.Intent.ACTION_PACKAGE_ADDED);
        pkgs.addAction(android.content.Intent.ACTION_PACKAGE_REMOVED);
        pkgs.addAction(android.content.Intent.ACTION_PACKAGE_REPLACED);
        pkgs.addDataScheme("package");
        registerReceiver(new android.content.BroadcastReceiver() {
            @Override public void onReceive(android.content.Context c, android.content.Intent i) { checkLogger(); }
        }, pkgs);
        // only the logger (same signing key) can send its state
        registerReceiver(new android.content.BroadcastReceiver() {
            @Override public void onReceive(android.content.Context c, android.content.Intent i) {
                loggerRecording = i.getBooleanExtra("running", false);
                for (Listener l : listeners) l.onCarChanged(LOGGER_CHANGED);
            }
        }, new android.content.IntentFilter("app.binnacle.logger.STATE"), LOGGER_PERMISSION, null);
        checkLogger();
    }

    /** Which loggers are installed; asks the current one whether it is recording. */
    void checkLogger() {
        loggerInstalled = installed(LOGGER_PKG);
        oldLoggerInstalled = installed(OLD_LOGGER_PKG);
        if (!loggerInstalled) loggerRecording = false;
        else sendBroadcast(new android.content.Intent("app.binnacle.logger.QUERY").setPackage(LOGGER_PKG), LOGGER_PERMISSION);
        for (Listener l : listeners) l.onCarChanged(LOGGER_CHANGED);
    }

    private boolean installed(String pkg) {
        try {
            getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (android.content.pm.PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    /** onCarChanged code: a car-picture pack finished downloading (or failed); screens showing it refresh. */
    public static final int PACKS_CHANGED = -4;

    /** Tell every screen something changed outside the car feed (e.g. PACKS_CHANGED). */
    public void notifyChanged(int code) {
        for (Listener l : listeners) l.onCarChanged(code);
    }

    /** onCarChanged code for a new GPS heading. */
    public static final int HEADING_CHANGED = -3;
    private final Compass compass = new Compass(this, new Compass.Sink() {
        @Override public void onHeading() {
            for (Listener l : listeners) l.onCarChanged(HEADING_CHANGED);
        }
    });

    /** GPS heading for the compass; starts once location is granted (also retried from the dash). */
    public Compass compass() { return compass; }
    public void startCompass() { compass.start(); }

    private void startFeed() {
        if (feed != null) feed.stop();
        // No stock CAN service (emulator / other device) -> demo, so the UI is never just blank.
        demo = prefs.getBoolean("demo", false) || !CanFeed.available(this);
        feed = demo ? new DemoFeed(this) : new CanFeed(this, this);
        // Never let values from the previous feed (e.g. simulated ones) pass as live readings.
        car.reset();
        efficiency.reset();                    // demo kilometres never count towards the real drive
        // demo values never train the power check; live starts from what the last live drive found
        car.demoPower = demo;
        car.powerDecoder.setEstimating(!demo && powerEstimatedSaved);
        if (!demo) restoreLights();
        lastFrontCam = false;
        main.removeCallbacks(cameraAction);
        cameraPending = false;
        main.removeCallbacks(stalled);
        problem = null;
        for (Listener l : listeners) l.onCarChanged(-1);
        feed.start();
    }

    /**
     * Fires STALE_MS after the last live message while the car was moving (re-armed by every
     * message in onData). Clears the screen like a lost feed, then subscribes afresh: the old
     * registration is dropped (unregister) and a new one made (register), the same two calls as
     * always. Re-arms only once data with the car moving comes back, so it never loops.
     */
    private final Runnable stalled = new Runnable() {
        @Override public void run() {
            if (demo || !(feed instanceof CanFeed) || !car.moving() || car.speed <= 0) return;
            android.util.Log.w("binnacle", "no CAN data for " + STALE_MS + " ms while moving: subscribing again");
            onStatus(STALE_PROBLEM);
            feed.stop();
            feed = new CanFeed(DashApp.this, DashApp.this);
            feed.start();
        }
    };

    private void applyTextSize() {
        int pct;
        try {
            pct = Integer.parseInt(prefs.getString("text_size", "100"));
        } catch (RuntimeException e) {
            pct = 100;
        }
        Style.textScale = Math.max(100, Math.min(130, pct)) / 100f;
    }

    /** onCarChanged code telling the screens to rebuild in the new theme. */
    public static final int THEME_CHANGED = -2;
    /** Auto theme: how long the screen level must stay past a threshold before the theme follows. */
    private static final long THEME_SETTLE_MS = 30_000;
    /** Screen-brightness levels (0-100) for Auto: dark at or below, light at or above. */
    private static final int AUTO_DARK = 30, AUTO_LIGHT = 45;
    private final Runnable themeSwitch = new Runnable() { @Override public void run() { updateTheme(true); } };
    private boolean themeWantLight, themePending;

    /**
     * Theme setting (Display): light, dark, or auto = follow the car's screen brightness (code 18,
     * dark at or below AUTO_DARK, light at or above AUTO_LIGHT), after it holds for THEME_SETTLE_MS.
     * Call with now = true to apply at once (setting changed, timer fired).
     */
    void updateTheme(boolean now) {
        String mode = prefs.getString("theme", "auto");
        // Auto follows the brightness the car sets for the screen (code 18), which tracks the Tesla's
        // own display: headlights flipped at dusk and go off when parked at night (in-car, 29 Oct).
        // Hysteresis: dark at or below AUTO_DARK, light at or above AUTO_LIGHT, keep it in between.
        // Until the level is known, keep the current theme.
        boolean want;
        if ("auto".equals(mode)) {
            int lvl = car.screenLevel;
            if (lvl < 0 || (lvl > AUTO_DARK && lvl < AUTO_LIGHT)) { themePending = false; main.removeCallbacks(themeSwitch); return; }
            want = lvl >= AUTO_LIGHT;
        } else {
            want = "light".equals(mode);
        }
        if ("auto".equals(mode) && !now && want != Style.light) {
            if (want != themeWantLight || !themePending) {
                themeWantLight = want;
                themePending = true;
                main.removeCallbacks(themeSwitch);
                main.postDelayed(themeSwitch, THEME_SETTLE_MS);
            }
            return;
        }
        themePending = false;
        main.removeCallbacks(themeSwitch);
        if (want == Style.light) return;
        if (!now) return;
        Style.applyTheme(want);
        prefs.edit().putBoolean("theme_last_light", want).apply();
        for (Listener l : listeners) l.onCarChanged(THEME_CHANGED);
    }

    /** Rainbow road on Autopilot (Dash settings), cached so the draw loop never reads preferences. */
    public boolean rainbowRoad() { return rainbowRoad; }

    public boolean isDemo() { return demo; }

    /** Pref: the car's power reading was last found unusable, so the bar shows an estimate. */
    static final String POWER_ESTIMATED = "power_estimated";
    private boolean powerEstimatedSaved;

    private final EfficiencyTracker efficiency = new EfficiencyTracker();
    /** Energy use since the dash started (fed on every code 10, whichever app is in front). */
    public EfficiencyTracker efficiency() { return efficiency; }
    public String feedProblem() { return problem; }
    public SharedPreferences prefs() { return prefs; }

    public void addListener(Listener l) { listeners.addIfAbsent(l); }
    public void removeListener(Listener l) { listeners.remove(l); }

    @Override public void onData(int code, Bundle data) {
        try {
            car.apply(code, data);
            if (code == 10) {
                efficiency.feed(android.os.SystemClock.elapsedRealtime(),
                        car.regen ? -car.power : car.power, car.speedKmh());
                if (car.powerEstimated() != powerEstimatedSaved && !demo) {
                    // remembered so a restart doesn't flash the bad reading before it is spotted again
                    powerEstimatedSaved = car.powerEstimated();
                    prefs.edit().putBoolean(POWER_ESTIMATED, powerEstimatedSaved).apply();
                }
            }
            if (code == 16) onFrontCam(car.frontCamRequest);
            if (code == 9 && !demo) saveLights(data);
            if (STALE_PROBLEM.equals(problem)) problem = null;   // live data again after a stall
        } catch (Throwable t) {
            android.util.Log.w("binnacle", "bad data for code " + code, t);
            return;
        } finally {
            // feed watchdog: any message, even one that fails to decode, shows the feed is alive.
            // Armed only while the car is moving in D/R (the timer checks again when it fires).
            if (!demo) {
                main.removeCallbacks(stalled);
                if (car.moving() && car.speed > 0) main.postDelayed(stalled, STALE_MS);
            }
        }
        if (code == 18) updateTheme(false);        // screen brightness: Auto theme follows the car's display
        for (Listener l : listeners) l.onCarChanged(code);
    }

    /**
     * The car renders for the current colour, or null while they load. Decoded once, off the UI
     * thread, and shared by all views; listeners get onCarChanged(-1) when they are ready.
     */
    public CarArt art() {
        final int colour = carColour();
        final String hand = artSet();
        final int key = colour * 8 + java.util.Arrays.asList(CarPacks.HANDS).indexOf(hand);   // a colour, model or side change reloads
        fetchModelYPack(colour, hand);
        if (art != null && artColour == key) return art;
        if (!artLoading) {
            artLoading = true;
            new Thread(new Runnable() {
                @Override public void run() {
                    CarArt a;
                    try {
                        a = CarArt.load(DashApp.this, colour, hand);
                    } catch (Throwable t) {
                        a = CarArt.NONE;
                    }
                    final CarArt loaded = a;
                    main.post(new Runnable() {
                        @Override public void run() {
                            art = loaded;
                            artColour = key;
                            artLoading = false;
                            for (Listener l : listeners) l.onCarChanged(-1);
                        }
                    });
                }
            }, "car-art").start();
        }
        return art;
    }

    /** Driving side (Car → Driving side): left-hand drive uses the lhd car renders. */
    public boolean leftHandDrive() { return lhd; }

    /**
     * Settings the views use on every frame, cached here (read in onCreate, refreshed on change) so
     * the draw loop never goes to SharedPreferences.
     */
    private volatile boolean lhd, modelY, rawOverlay;
    private volatile int colourPref = -1;              // -1 = Auto (what the car reports)
    private volatile String units = "auto";
    private volatile boolean tyreBar;
    private volatile int fullRangeKm = 410;

    private void readDrawPrefs() {
        lhd = "lhd".equals(prefs.getString("drive_side", "rhd"));
        modelY = "y".equals(prefs.getString("car_model", "3"));
        rawOverlay = prefs.getBoolean("raw_overlay", false);
        units = prefs.getString("units", "auto");
        tyreBar = "bar".equals(prefs.getString("tyre_unit", "psi"));
        int r;
        try {
            r = Integer.parseInt(prefs.getString("full_range_km", "410").trim());
        } catch (RuntimeException e) {
            r = 330;                                   // unreadable: the old fallback
        }
        fullRangeKm = r;
        String c = prefs.getString("car_colour", "auto");
        int v = -1;
        try {
            if (!"auto".equals(c)) v = Integer.parseInt(c);
        } catch (NumberFormatException ignored) {
            // unreadable: treat as Auto
        }
        colourPref = v;
    }

    /** Units setting (Car → Units): "auto", "metric" or "imperial". */
    public String units() { return units; }

    /** Tyre pressure in bar (Car → Tyre pressure), else psi. */
    public boolean tyreBar() { return tyreBar; }

    /** Car → Range at 100%, in km (as stored). */
    public int fullRangeKm() { return fullRangeKm; }

    /** Show raw CAN values (Advanced). */
    public boolean rawOverlay() { return rawOverlay; }

    /**
     * The car's colour for the images (CarPacks.COLOURS index): Car → Car colour if picked by hand,
     * else what the car reports (carcolor, as the stock dash's vehicle colour setting gives it).
     */
    public int carColour() { return colourPref >= 0 ? colourPref : car.carColour; }

    /** Car model (first start, or Car → Car model): "3" (default) or "y". */
    public boolean modelY() { return modelY; }

    /** The render set for the model and driving side (CarPacks.HANDS): rhd, lhd, y-rhd or y-lhd. */
    public String artSet() { return (modelY() ? "y-" : "") + (leftHandDrive() ? "lhd" : "rhd"); }

    /** Model Y packs already tried this run ("hand/colour"), so a failed download isn't retried in a loop. */
    private final java.util.Set<String> packTried = new java.util.HashSet<>();

    /**
     * Only the dark grey right-hand drive Model Y is bundled, so choosing Model Y (the choice says
     * other colours download) fetches the pack for the car's colour and side from the project's
     * release once the car has reported its colour; until then, or if it fails, CarArt shows the
     * nearest it has. Tried once per run;
     * Apps & settings → Car → Car images retries by hand.
     */
    private void fetchModelYPack(int colour, final String hand) {
        if (!hand.startsWith("y-") || colour < 0 || colour >= CarPacks.COLOURS.length) return;
        final String c = CarPacks.COLOURS[colour];
        // called from art(), so on every frame: decide once per pack, then it's a set lookup (no
        // asset-folder scan). Recorded even when bundled or already downloaded, so Remove in Car
        // images doesn't trigger a fresh download.
        if (!packTried.add(hand + "/" + c)) return;
        if (CarPacks.bundled(this, hand, c) || CarPacks.downloaded(this, hand, c) || CarPacks.downloading(hand, c)) return;
        new Thread(new Runnable() {
            @Override public void run() {
                boolean ok;
                try {
                    CarPacks.download(DashApp.this, hand, c, null);
                    ok = true;
                } catch (Exception e) {
                    ok = false;
                }
                final boolean got = ok;
                main.post(new Runnable() {
                    @Override public void run() {
                        if (got) reloadArt();
                        notifyChanged(PACKS_CHANGED);
                    }
                });
            }
        }, "car-pack").start();
    }

    /** Forget the renders and load them again (e.g. the driving side changed). */
    public void reloadArt() {
        artColour = Integer.MIN_VALUE;
        art();
    }

    @Override public void onStatus(String p) {
        // A silent feed re-registers fine without sending anything, so "no car data" stays up until
        // live data actually arrives (onData), not just until the new registration succeeds.
        if (p == null && STALE_PROBLEM.equals(problem)) return;
        problem = p;
        // Feed lost (service died, not found, refused, or silent): nothing on screen is current any
        // more, so clear it rather than keep showing the last speed / gear / indicators as live.
        if (p != null) {
            car.reset();
            main.removeCallbacks(stalled);
            // A camera open still waiting out the debounce is dropped: the flag behind it is no longer
            // known to be true. Otherwise lastFrontCam is kept, so when the feed returns with
            // frontcamstate false a camera that is already up still gets its close. Never close it
            // here: with the feed gone we can't know the car isn't in Reverse.
            if (cameraPending && cameraWanted) {
                main.removeCallbacks(cameraAction);
                cameraPending = false;
                lastFrontCam = false;          // it never opened, so a flag still up on return opens it then
            }
        }
        for (Listener l : listeners) l.onCarChanged(-1);
    }

    @Override public void onSharedPreferenceChanged(SharedPreferences sp, String key) {
        readDrawPrefs();
        if ("demo".equals(key)) startFeed();
        if ("text_size".equals(key)) applyTextSize();
        if ("autopilot_rainbow".equals(key)) rainbowRoad = prefs.getBoolean(key, false);
        if ("theme".equals(key)) updateTheme(true);
        if ("drive_side".equals(key) || "car_model".equals(key) || "car_colour".equals(key)) reloadArt();
        for (Listener l : listeners) l.onCarChanged(-1);
    }

    /**
     * Re-does the stock launcher's forward-camera behaviour: open the camera when the car raises
     * frontcamstate (shift to Drive), ask the camera to close when it drops. Only when we are the
     * Home app, so it never doubles up with the stock launcher while we run as a normal app.
     */
    private void onFrontCam(boolean want) {
        if (want == lastFrontCam) return;
        lastFrontCam = want;
        // the experimental in-dash camera (DashActivity) owns the flag when it is on
        if (demo || !prefs.getBoolean("auto_camera", false) || prefs.getBoolean("dash_camera", false)
                || !Launch.isDefaultHome(this)) return;
        long since = SystemClock.elapsedRealtime() - lastCameraActionMs;
        long delay = since >= CAMERA_DEBOUNCE_MS ? 0 : CAMERA_DEBOUNCE_MS - since;
        cameraWanted = want;
        main.removeCallbacks(cameraAction);     // only the pending camera action, nothing else on main
        main.postDelayed(cameraAction, delay);
        cameraPending = true;
    }

    private boolean cameraWanted, cameraPending;
    private final Runnable cameraAction = new Runnable() {
        @Override public void run() {
            cameraPending = false;
            lastCameraActionMs = SystemClock.elapsedRealtime();
            if (cameraWanted) Launch.camera(DashApp.this); else Launch.closeCamera(DashApp.this);
        }
    };
}
