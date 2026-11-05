package app.binnacle;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Android's Wi-Fi auto-connect scanning, through fixed LocalShell commands (owner OK 4 Nov).
 *
 * Pause during CarPlay (Apps → Settings → General, on by default): while CarPlay connects,
 * runs or has its settings open, auto-connect scans are off, because each one takes the single
 * radio off CarPlay's Wi-Fi Direct channel for about a second and the music and calls stutter
 * (proved on drives, 3-4 Nov). When CarPlay ends they are on again. Car sleep stops both apps;
 * on wake the dash links to xcertplay again, which reports "idle", and scans go back on.
 *
 * The Wi-Fi icon turns scanning and Wi-Fi on (WIFI_REJOIN), so Android rejoins the saved home
 * network; Android 10 lets no app ask Wi-Fi to connect directly. Not while CarPlay runs with
 * the pause on: that would bring the stutter back.
 *
 * Android 10 only: the command calls a binder transaction by number, which other versions
 * number differently. Nothing here reaches the car or the MCU.
 */
final class WifiScans {
    static final String KEY = "pause_wifi_scans";
    /** On unless switched off (owner, 5 Nov: the stutter fix should just work). */
    static final boolean DEFAULT = true;
    private static final String TAG = "Binnacle";
    private static final ExecutorService SHELL = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    /** What the dash last set successfully: null = untouched since it started. Main thread only. */
    private static Boolean scansOn;
    /** What the dash wants now (a command for it may still be running). Main thread only. */
    private static Boolean wanted;

    private WifiScans() {}

    static boolean supported() {
        return Build.VERSION.SDK_INT == 29;
    }

    static boolean paused(SharedPreferences prefs) {
        return supported() && prefs.getBoolean(KEY, DEFAULT);
    }

    /** CarPlay is connecting, running or has its settings open (true), or not (false). */
    static void forCarPlay(SharedPreferences prefs, boolean carplayRunning) {
        if (!supported()) return;
        boolean pause = prefs.getBoolean(KEY, DEFAULT);
        if (!pause && scansOn == null && wanted == null) return;   // never touched: leave Android's own state
        final boolean wantOn = !(pause && carplayRunning);
        if (wanted != null && wanted == wantOn) return;             // already on its way (or done)
        wanted = wantOn;
        final LocalShell.Command cmd = wantOn ? LocalShell.Command.WIFI_SCANS_ON : LocalShell.Command.WIFI_SCANS_OFF;
        SHELL.execute(new Runnable() {
            @Override public void run() {
                boolean ok;
                try {
                    LocalShell.run(cmd);
                    ok = true;
                    Log.i(TAG, "Wi-Fi auto-connect scans " + (wantOn ? "on" : "off"));
                } catch (Exception e) {
                    ok = false;
                    Log.w(TAG, "Wi-Fi scans not changed: " + e.getMessage());
                }
                final boolean done = ok;
                MAIN.post(new Runnable() {
                    @Override public void run() {
                        if (done) scansOn = wantOn;
                        // failed (e.g. ADB not up yet right after boot): forget it, so the next call retries
                        else if (wanted != null && wanted == wantOn) wanted = scansOn;
                    }
                });
            }
        });
    }

    /** The Wi-Fi icon: scanning and Wi-Fi on, so the saved home network is joined again. */
    /** True when scans are paused while CarPlay runs (the switch, on by default) and can be. */
    static boolean pausedForCarPlay(SharedPreferences prefs) {
        return supported() && prefs.getBoolean(KEY, DEFAULT);
    }

    static void rejoin(Context context, SharedPreferences prefs, boolean carplayRunning) {
        final Context app = context.getApplicationContext();
        if (!supported()) {
            Toast.makeText(app, "Join Wi-Fi in Android's settings", Toast.LENGTH_SHORT).show();
            return;
        }
        if (carplayRunning && prefs.getBoolean(KEY, DEFAULT)) {
            Toast.makeText(app, "Wi-Fi scanning is paused while CarPlay runs", Toast.LENGTH_SHORT).show();
            return;
        }
        wanted = true;
        SHELL.execute(new Runnable() {
            @Override public void run() {
                String msg;
                boolean ok = false;
                try {
                    LocalShell.Command cmd = LocalShell.Command.WIFI_REJOIN;
                    LocalShell.run(cmd);
                    ok = true;
                    msg = "Looking for home Wi-Fi…";
                } catch (Exception e) {
                    msg = "Couldn't turn Wi-Fi scanning on: " + e.getMessage();
                }
                final String shown = msg;
                final boolean done = ok;
                MAIN.post(new Runnable() {
                    @Override public void run() {
                        if (done) scansOn = true; else wanted = scansOn;
                        Toast.makeText(app, shown, Toast.LENGTH_SHORT).show();
                    }
                });
            }
        });
    }
}
