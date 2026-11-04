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
 * Android's Wi-Fi auto-connect scanning, through two fixed LocalShell commands (owner OK 4 Nov).
 *
 * Pause during CarPlay (Apps → Settings → General, off by default): while CarPlay connects or
 * runs, auto-connect scans are off, because each one takes the single radio off CarPlay's
 * Wi-Fi Direct channel for about a second and the music and calls stutter (proved on drives,
 * 3-4 Nov). When CarPlay ends, or the dash starts (car sleep stops CarPlay without telling
 * anyone), they are on again.
 *
 * The Wi-Fi icon turns scanning on (and Wi-Fi itself), so Android rejoins the saved home
 * network; Android 10 lets no app ask Wi-Fi to connect directly.
 *
 * Android 10 only: the command calls a binder transaction by number, which other versions
 * number differently. Nothing here reaches the car or the MCU.
 */
final class WifiScans {
    static final String KEY = "pause_wifi_scans";
    private static final String TAG = "Binnacle";
    private static final ExecutorService SHELL = Executors.newSingleThreadExecutor();
    /** What the dash last set: null = untouched since it started. Main thread only. */
    private static Boolean scansOn;

    private WifiScans() {}

    static boolean supported() {
        return Build.VERSION.SDK_INT == 29;
    }

    /** CarPlay is connecting or running (true) or not (false). */
    static void forCarPlay(SharedPreferences prefs, boolean carplayRunning) {
        if (!supported()) return;
        boolean pause = prefs.getBoolean(KEY, false);
        if (!pause && scansOn == null) return;       // never touched: leave Android's own state
        boolean wantOn = !(pause && carplayRunning);
        if (scansOn != null && scansOn == wantOn) return;
        scansOn = wantOn;
        final LocalShell.Command cmd = wantOn ? LocalShell.Command.WIFI_SCANS_ON : LocalShell.Command.WIFI_SCANS_OFF;
        SHELL.execute(new Runnable() {
            @Override public void run() {
                try {
                    LocalShell.run(cmd);
                    Log.i(TAG, "Wi-Fi auto-connect scans " + (cmd == LocalShell.Command.WIFI_SCANS_ON ? "on" : "off"));
                } catch (Exception e) {
                    Log.w(TAG, "Wi-Fi scans not changed: " + e.getMessage());
                }
            }
        });
    }

    /** The Wi-Fi icon: scanning (and Wi-Fi) on, so the saved home network is joined again. */
    static void rejoin(final Context c) {
        if (!supported()) {
            Toast.makeText(c, "Join Wi-Fi in Android's settings", Toast.LENGTH_SHORT).show();
            return;
        }
        scansOn = true;
        final Handler main = new Handler(Looper.getMainLooper());
        SHELL.execute(new Runnable() {
            @Override public void run() {
                String msg;
                try {
                    LocalShell.Command cmd = LocalShell.Command.WIFI_SCANS_ON;
                    LocalShell.run(cmd);
                    msg = "Looking for home Wi-Fi…";
                } catch (Exception e) {
                    msg = "Couldn't turn Wi-Fi scanning on: " + e.getMessage();
                }
                final String shown = msg;
                main.post(new Runnable() {
                    @Override public void run() { Toast.makeText(c, shown, Toast.LENGTH_SHORT).show(); }
                });
            }
        });
    }
}
