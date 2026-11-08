package app.binnacle;

import android.content.SharedPreferences;
import android.util.Log;

/**
 * Keeps the unit's ADB off the Wi-Fi (owner, 8 Nov). Android 10's adbd listens on every network
 * once its port is open and can't be told to listen on loopback only, so each time the dash starts
 * (and when Settings → Unit → Network ADB changes, and at each screen-on) this drops port 5555 on
 * every interface but loopback, and lets only root, the shell user and Binnacle connect on loopback:
 * Binnacle's own LocalShell keeps working, a computer on the Wi-Fi or another app can't connect. Network ADB on
 * (off by default) removes the rule, for working on the unit from a computer. The rule goes at
 * every restart, so for the seconds between boot and the dash starting the port is reachable.
 * Two pinned LocalShell commands (root); nothing reaches the car.
 */
final class AdbFirewall {
    private AdbFirewall() {}

    static final String KEY = "network_adb";
    static final boolean DEFAULT = false;

    /**
     * Whether the last attempt to keep ADB off the Wi-Fi failed while ADB was reachable (for
     * example no root on other unit software): the Unit page warns, since ADB is then open to the
     * Wi-Fi. False when it worked, when Network ADB is on, or when ADB isn't on at all.
     */
    static volatile boolean failed;

    /** One at a time, in order: a quick On then Off must end with the rule in place (Codex). */
    private static final java.util.concurrent.ScheduledExecutorService ONE =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor();

    /**
     * Waits before each retry while the rule couldn't be put in place: at boot adbd may not answer
     * yet and netd holds the xtables lock (audit #5). About five minutes in all, then the next dash
     * start or screen-on tries again.
     */
    private static final long[] RETRY_MS = {5000, 10000, 20000, 40000, 80000, 160000};
    private static java.util.concurrent.ScheduledFuture<?> retry;   // guarded by AdbFirewall.class

    /**
     * Puts the rule in place, or takes it away if Network ADB is on. Off the main thread; the
     * Future is done once {@link #failed} holds this attempt's result (the Unit page waits on it).
     * A failed attempt is retried with backoff.
     */
    static java.util.concurrent.Future<?> apply(final SharedPreferences prefs) {
        synchronized (AdbFirewall.class) {
            if (retry != null) { retry.cancel(false); retry = null; }
        }
        return ONE.submit(new Runnable() {
            @Override public void run() { attempt(prefs, 0); }
        });
    }

    /** Applies the rule again each time the screen comes on (the car wakes), in case it was lost. */
    static void watchScreen(android.content.Context c, final SharedPreferences prefs) {
        c.registerReceiver(new android.content.BroadcastReceiver() {
            @Override public void onReceive(android.content.Context x, android.content.Intent i) { apply(prefs); }
        }, new android.content.IntentFilter(android.content.Intent.ACTION_SCREEN_ON));
    }

    private static void attempt(final SharedPreferences prefs, final int tries) {
        boolean network = prefs.getBoolean(KEY, DEFAULT);   // the latest switch position, read here
        boolean done = false;
        try {
            LocalShell.Command cmd = network ? LocalShell.Command.ADB_NETWORK : LocalShell.Command.ADB_LOCAL_ONLY;
            LocalShell.run(cmd);
            failed = false;
            done = true;
            Log.i("Binnacle", network ? "network ADB allowed" : "ADB reachable from the unit only");
        } catch (java.net.ConnectException e) {
            // no ADB (yet): nothing to block, and nothing can reach it either; at boot it may still be starting
            failed = false;
            Log.w("Binnacle", "ADB firewall not changed: " + e.getMessage());
        } catch (Exception e) {
            // ADB answered but the rule didn't go in: it is open to the Wi-Fi (unless the switch is on)
            failed = !network;
            Log.w("Binnacle", "ADB firewall not changed: " + e.getMessage());
        }
        if (done || tries >= RETRY_MS.length) return;
        synchronized (AdbFirewall.class) {
            retry = ONE.schedule(new Runnable() {
                @Override public void run() { attempt(prefs, tries + 1); }
            }, RETRY_MS[tries], java.util.concurrent.TimeUnit.MILLISECONDS);
        }
    }
}
