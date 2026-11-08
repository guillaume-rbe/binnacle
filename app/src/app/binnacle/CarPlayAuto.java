package app.binnacle;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

/**
 * CarPlay auto-connect (Apps → Settings → General, off by default; issue #90): when the dash starts
 * and when the screen comes back on after the car slept, CarPlay is started the same way the dock's
 * CarPlay button starts it, but behind the dash. Nothing shows while it tries: someone else in the
 * car (no paired iPhone in range) sees no "Connecting" window and no "couldn't connect". If the
 * iPhone connects, CarPlay either stays in the background (music, calls, Now playing) or comes up in
 * the centre window, as chosen. If it hasn't connected within WINDOW_MS, or xcertplay gives up
 * first, the attempt is ended (as End CarPlay's disconnect does), so it never keeps retrying and
 * home Wi-Fi scanning comes back on. A tap on CarPlay meanwhile takes over and is left alone.
 *
 * Only ICarPlayEmbed calls the dash already makes (start, disconnect) through CarPlayLink; nothing
 * here reaches the car, the MCU or a setting.
 */
final class CarPlayAuto {
    static final String KEY = "carplay_auto";
    static final String OFF = "off", BACKGROUND = "background", SHOW = "show";
    static final String DEFAULT = OFF;

    /** Give up this long after asking for CarPlay: xcertplay's own three retries fit well inside it. */
    static final long WINDOW_MS = 90_000;
    /** After start or wake, wait for the car's Bluetooth audio (CarPlay sound goes over it) this long at most. */
    static final long MAX_WAIT_MS = 20_000, WAIT_STEP_MS = 2_000;
    /** Settle time before the first look: Bluetooth is still coming up just after boot or wake. */
    static final long FIRST_LOOK_MS = 4_000;

    interface Host {
        /** The iPhone connected on an attempt set to show CarPlay: bring it up in the centre window. */
        void showConnectedCarPlay();
    }

    private static final String TAG = "binnacle";
    /** Once per dash process: a theme recreation reconnects the link but is not a new start. */
    private static boolean triedSinceStart;

    private final Context context;
    private final SharedPreferences prefs;
    private final CarPlayLink link;
    private final Host host;
    private final Handler handler = new Handler(Looper.getMainLooper());
    // Static (per process), so a theme recreation of the dash keeps an attempt in flight: the new
    // screen's CarPlayAuto picks up its wait or its deadline instead of losing them.
    /** A start or wake is waiting for the link or the car's Bluetooth. */
    private static boolean pending;
    private static long waitingSince;
    /** start() was sent by us and the outcome isn't known yet; given up at attemptUntil (uptime). */
    private static boolean attempt;
    private static long attemptUntil;

    CarPlayAuto(Context c, SharedPreferences prefs, CarPlayLink link, Host host) {
        this.context = c;
        this.prefs = prefs;
        this.link = link;
        this.host = host;
        // carried over from the screen this one replaces (theme change): keep waiting / keep the deadline
        if (attempt) handler.postDelayed(giveUp, Math.max(0, attemptUntil - SystemClock.uptimeMillis()));
        if (pending) arm(FIRST_LOOK_MS);
    }

    static String mode(SharedPreferences prefs) {
        String m = prefs.getString(KEY, DEFAULT);
        return SHOW.equals(m) || BACKGROUND.equals(m) ? m : OFF;
    }

    /** The link to xcertplay is up: the first time since the dash process started counts as a start. */
    void onLinkUp() {
        if (triedSinceStart) {
            if (pending) arm(FIRST_LOOK_MS);   // a wake that came before the link
            return;
        }
        triedSinceStart = true;
        request("dash start");
    }

    /** The screen came back on (the car woke). */
    void onScreenOn() {
        request("wake");
    }

    /** The screen went off (the car sleeps): nothing more to try until it wakes. */
    void onScreenOff() {
        pending = false;
        handler.removeCallbacks(look);
        endAttempt(false);
    }

    /** The user opened CarPlay (or it was shown): whatever happens now is theirs, not ours to end. */
    void userTookOver() {
        pending = false;
        handler.removeCallbacks(look);
        attempt = false;
        handler.removeCallbacks(giveUp);
    }

    /** This screen is going (closed or recreated): its timers stop; an attempt's state stays for the next one. */
    void release() {
        handler.removeCallbacksAndMessages(null);
    }

    private void request(String why) {
        if (OFF.equals(mode(prefs))) return;
        Log.i(TAG, "CarPlay auto-connect: " + why);
        pending = true;
        waitingSince = SystemClock.uptimeMillis();
        arm(FIRST_LOOK_MS);
    }

    private void arm(long delay) {
        handler.removeCallbacks(look);
        handler.postDelayed(look, delay);
    }

    private final Runnable look = new Runnable() {
        @Override public void run() {
            if (!pending) return;
            if (OFF.equals(mode(prefs))) { pending = false; return; }
            if (!link.connected()) return;                // onLinkUp looks again
            int s = link.state();
            if (CarPlayLink.running(s)) { pending = false; return; }   // already on (or its settings are open)
            if (s == CarPlayLink.STATE_NEEDS_SETUP) {      // permissions missing: that needs the user, quietly skip
                pending = false;
                Log.i(TAG, "CarPlay auto-connect: CarPlay needs setting up; not trying");
                return;
            }
            boolean waited = SystemClock.uptimeMillis() - waitingSince >= MAX_WAIT_MS;
            if (!waited && !StartupVolume.carBluetoothAudio(context)) { arm(WAIT_STEP_MS); return; }
            pending = false;
            attempt = true;
            handler.removeCallbacks(giveUp);
            attemptUntil = SystemClock.uptimeMillis() + WINDOW_MS;
            handler.postDelayed(giveUp, WINDOW_MS);
            Log.i(TAG, "CarPlay auto-connect: asking for CarPlay in the background");
            link.start();
        }
    };

    private final Runnable giveUp = new Runnable() {
        @Override public void run() {
            if (!attempt) return;
            Log.i(TAG, "CarPlay auto-connect: no iPhone within " + WINDOW_MS / 1000 + " s; stopping quietly");
            endAttempt(true);
        }
    };

    /** xcertplay's state, as the dash hears it. */
    void onCarPlayState(int state) {
        if (!attempt) return;
        if (state == CarPlayLink.STATE_STREAMING) {
            attempt = false;
            handler.removeCallbacks(giveUp);
            Log.i(TAG, "CarPlay auto-connect: connected");
            if (SHOW.equals(mode(prefs))) host.showConnectedCarPlay();
        } else if (state == CarPlayLink.STATE_FAILED || state == CarPlayLink.STATE_NEEDS_SETUP) {
            Log.i(TAG, "CarPlay auto-connect: xcertplay gave up; stopping quietly");
            endAttempt(true);
        } else if (state == CarPlayLink.STATE_PAUSED) {
            userTookOver();                                // xcertplay's own settings opened: the user is there
        }
    }

    private void endAttempt(boolean disconnect) {
        if (!attempt) return;
        attempt = false;
        handler.removeCallbacks(giveUp);
        // ends the retries and xcertplay's hotspot; a tap on CarPlay starts afresh
        if (disconnect && link.connected() && link.state() != CarPlayLink.STATE_STREAMING) link.disconnect();
    }
}
