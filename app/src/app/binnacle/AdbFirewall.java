package app.binnacle;

import android.content.SharedPreferences;
import android.util.Log;

/**
 * Keeps the unit's ADB off the Wi-Fi (owner, 8 Nov). Android 10's adbd listens on every network
 * once its port is open and can't be told to listen on loopback only, so each time the dash starts
 * (and when Advanced → Network ADB changes) this drops port 5555 on every interface but loopback:
 * Binnacle's own LocalShell keeps working, a computer on the Wi-Fi can't connect. Network ADB on
 * (off by default) removes the rule, for working on the unit from a computer. The rule goes at
 * every restart, so for the seconds between boot and the dash starting the port is reachable.
 * Two pinned LocalShell commands (root); nothing reaches the car.
 */
final class AdbFirewall {
    private AdbFirewall() {}

    static final String KEY = "network_adb";
    static final boolean DEFAULT = false;

    /** Puts the rule in place, or takes it away if Network ADB is on. Off the main thread. */
    static void apply(SharedPreferences prefs) {
        final boolean network = prefs.getBoolean(KEY, DEFAULT);
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    LocalShell.Command cmd = network ? LocalShell.Command.ADB_NETWORK : LocalShell.Command.ADB_LOCAL_ONLY;
                    LocalShell.run(cmd);
                    Log.i("Binnacle", network ? "network ADB allowed" : "ADB reachable from the unit only");
                } catch (Exception e) {
                    // no ADB yet (a fresh unit): nothing to block, and nothing can reach it either
                    Log.w("Binnacle", "ADB firewall not changed: " + e.getMessage());
                }
            }
        }, "adb-firewall").start();
    }
}
