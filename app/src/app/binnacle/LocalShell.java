package app.binnacle;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * Runs one of a FIXED set of shell commands on the unit itself, through the unit's own ADB daemon
 * on 127.0.0.1:5555 (network ADB is enabled and unauthenticated on this unit: persist.adb.tcp.port
 * = 5555, no ro.adb.secure). This is how the dash does what a normal app may not, such as closing
 * CarPlay completely: the same as `adb shell ...` from the Mac, as the shell user (root only
 * through the pinned su 0 unit-setup commands).
 *
 * Only the commands in {@link Command} can run; callers can't pass their own text, and
 * tools/check_readonly.sh checks the command strings. None of them touches the car, the MCU or CAN.
 * Each dash-local change is owner-approved under CLAUDE.md's dash-local write rule; the unit-setup
 * ones (owner OK 8 Nov) are the only ones that use root (su 0). Blocking: call off the main thread.
 */
final class LocalShell {

    /** The only commands the dash may run. */
    enum Command {
        /**
         * End CarPlay and get home Wi-Fi back: cycle Wi-Fi, which drops CarPlay's access point
         * (and so the session) and rejoins the saved network. ZLink is NOT force-stopped: that
         * left the Goodocom Bluetooth module refusing the phone until a reboot (in-car, 29 Oct).
         */
        END_CARPLAY("svc wifi disable && sleep 2 && svc wifi enable && echo BINNACLE_OK"),
        /**
         * The unit's Bluetooth auto-connect (stock Car settings > Bluetooth): Settings.System
         * xbs_isautoconnect, exactly what the stock switch writes (BtSetupActivity). Read only by
         * the Bluetooth side; the CAN app never forwards it to the MCU (stock_settings_map.md).
         */
        BT_AUTOCONNECT_GET("settings get system xbs_isautoconnect && echo BINNACLE_OK"),
        BT_AUTOCONNECT_ON("settings put system xbs_isautoconnect true && echo BINNACLE_OK"),
        BT_AUTOCONNECT_OFF("settings put system xbs_isautoconnect false && echo BINNACLE_OK"),
        /**
         * Android's own Wi-Fi auto-connect scanning (IWifiManager.enableWifiConnectivityManager,
         * transaction 58 on Android 10; the shell user may call it). Off while CarPlay runs: each
         * scan takes the single radio off CarPlay's Wi-Fi Direct channel for about a second, the
         * road stutter (owner, 3-5 Nov). On again to rejoin the saved home network (Wi-Fi icon).
         * Resets to on at every reboot. Run only on Android 10 (other versions number it differently).
         */
        WIFI_SCANS_ON("service call wifi 58 i32 1 && echo BINNACLE_OK"),
        WIFI_SCANS_OFF("service call wifi 58 i32 0 && echo BINNACLE_OK"),
        /**
         * xcertplay's location app-op for its user id back to allow (owner OK 5 Nov). Wi-Fi Direct
         * needs location, and CarPlay runs from behind the dash, where "foreground" doesn't count;
         * the unit resets this uid mode on every reboot and every xcertplay update, and CarPlay then
         * fails with "createGroup failed: generic error". Shell user, the dash only.
         */
        /**
         * A screenshot of the whole screen, CarPlay and the camera included (owner, 5 Nov: to capture
         * the dash while driving): the shell's screencap, to the dash's own storage, named by the
         * time. Pull them with `adb pull /sdcard/Binnacle/screenshots`.
         */
        SCREENSHOT("mkdir -p /sdcard/Binnacle/screenshots && screencap -p /sdcard/Binnacle/screenshots/$(date +%Y%m%d-%H%M%S).png && echo BINNACLE_OK"),
        XCERTPLAY_LOCATION("appops set --uid com.shilapi.xcertplay COARSE_LOCATION allow && echo BINNACLE_OK"),
        /** The Wi-Fi icon only: scanning on and Wi-Fi itself on, so the saved home network is joined. */
        WIFI_REJOIN("service call wifi 58 i32 1 && svc wifi enable && echo BINNACLE_OK"),
        /**
         * Unit setup (owner OK 8 Nov: a new user should get the owner's setup without adb, root
         * allowed). The only root (su 0) commands the dash has; each is opt-in in setup or
         * Settings → Unit, with its undo beside it, and listed in docs/UNIT-CHANGES.md.
         * None touches the car, the MCU or CAN. Only UnitSetup and AdbFirewall run them.
         */
        /** What is in place now: one line of name=value pairs, then the stock dash's toast app-op (no error if it is missing). */
        UNIT_STATUS("echo adb=$(getprop persist.adb.tcp.port) dumps=$(getprop persist.vendor.sys.core.enabled) bt=$(getprop persist.car.use_system_bt) absvol=$(getprop persist.bluetooth.disableabsvol) && appops get com.xbs.launcher1 TOAST_WINDOW; echo BINNACLE_OK"),
        /** Network ADB on again after every restart (it is off by default on this firmware). */
        ADB_KEEP_ON("su 0 setprop persist.adb.tcp.port 5555 && echo BINNACLE_OK"),
        ADB_KEEP_OFF("su 0 setprop persist.adb.tcp.port '' && echo BINNACLE_OK"),
        /**
         * The unit's ADB reachable only from the unit itself (owner, 8 Nov: never from the Wi-Fi):
         * drop port 5555 on every interface but loopback, IPv4 and IPv6, once (-C first). And on
         * loopback (which also carries the unit's own Wi-Fi address) only root, the shell user and
         * Binnacle may connect (owner OK 8 Nov, audit #1): any other app could otherwise reach the
         * unauthenticated ADB and its root. Binnacle's uid is read from its own data folder inside
         * the command, never passed in. -w waits for the xtables lock netd holds at boot (audit #5).
         * The chain is built only when it is new (before the jump to it exists) and is never flushed
         * while in use; later runs only check it (Codex).
         * The rules go at a restart; AdbFirewall puts them back each time the dash starts.
         */
        ADB_LOCAL_ONLY("su 0 sh -c 'U=$(stat -c %u /data/data/com.xbs.launcher) && [ $U -ge 10000 ] && for T in iptables ip6tables; do $T -w -C INPUT -p tcp --dport 5555 ! -i lo -j DROP 2>/dev/null || $T -w -I INPUT -p tcp --dport 5555 ! -i lo -j DROP || exit 1; if $T -w -N binnacle_adb 2>/dev/null; then $T -w -A binnacle_adb -m owner --uid-owner 0 -j RETURN && $T -w -A binnacle_adb -m owner --uid-owner 2000 -j RETURN && $T -w -A binnacle_adb -m owner --uid-owner $U -j RETURN && $T -w -A binnacle_adb -p tcp -j REJECT --reject-with tcp-reset || { $T -w -F binnacle_adb; $T -w -X binnacle_adb; exit 1; }; fi; $T -w -C binnacle_adb -m owner --uid-owner $U -j RETURN && $T -w -C binnacle_adb -p tcp -j REJECT --reject-with tcp-reset || exit 1; $T -w -C OUTPUT -o lo -p tcp --dport 5555 -j binnacle_adb 2>/dev/null || $T -w -I OUTPUT -o lo -p tcp --dport 5555 -j binnacle_adb || exit 1; done' && echo BINNACLE_OK"),
        /** Settings → Unit → Network ADB on: both rules removed, so a computer on the Wi-Fi can connect. */
        ADB_NETWORK("su 0 sh -c 'for T in iptables ip6tables; do while $T -w -D INPUT -p tcp --dport 5555 ! -i lo -j DROP 2>/dev/null; do :; done; while $T -w -D OUTPUT -o lo -p tcp --dport 5555 -j binnacle_adb 2>/dev/null; do :; done; $T -w -F binnacle_adb 2>/dev/null; $T -w -X binnacle_adb 2>/dev/null; done; true' && echo BINNACLE_OK"),
        /** The GPS daemon's crash dumps (up to ~470 MB each, filling the storage) off, and the folder cleared. */
        GPS_DUMPS_OFF("su 0 setprop persist.vendor.sys.core.enabled 0 && su 0 sh -c 'rm -rf /data/corefile/*' && echo BINNACLE_OK"),
        GPS_DUMPS_ON("su 0 setprop persist.vendor.sys.core.enabled 1 && echo BINNACLE_OK"),
        /** The stock full-screen camera on Drive off (the dash shows the camera itself). */
        STOCK_CAMERA_OFF("su 0 pm disable com.xbs.frontcam/com.xbs.frontcam.CameraActivity && echo BINNACLE_OK"),
        STOCK_CAMERA_ON("su 0 pm enable com.xbs.frontcam/com.xbs.frontcam.CameraActivity && echo BINNACLE_OK"),
        /** The stock dash, still running behind, may not show toasts over everything. Shell user. */
        STOCK_TOASTS_OFF("appops set com.xbs.launcher1 TOAST_WINDOW ignore && echo BINNACLE_OK"),
        STOCK_TOASTS_ON("appops set com.xbs.launcher1 TOAST_WINDOW allow && echo BINNACLE_OK"),
        /** Android's own Bluetooth on beside the stock module, for wireless CarPlay. After a restart. */
        ANDROID_BT_ON("su 0 setprop persist.car.use_system_bt 2 && echo BINNACLE_OK"),
        ANDROID_BT_OFF("su 0 setprop persist.car.use_system_bt '' && echo BINNACLE_OK"),
        /** Bluetooth absolute volume off: the car's stuck at 0 and audio was silent. After a restart. */
        BT_VOLUME_FIX_ON("su 0 setprop persist.bluetooth.disableabsvol true && echo BINNACLE_OK"),
        BT_VOLUME_FIX_OFF("su 0 setprop persist.bluetooth.disableabsvol '' && echo BINNACLE_OK"),
        /**
         * CarPlay calls: xcertplay may place calls, its calling account on and the default, and
         * Android's own in-call screen off (it covered the dash). Undo: all four back.
         */
        CARPLAY_CALLS_ON("pm grant com.shilapi.xcertplay android.permission.CALL_PHONE && telecom set-phone-account-enabled com.shilapi.xcertplay/com.shilapi.xcertplay.telecom.CarPlayConnectionService carplay 0 && telecom set-user-selected-outgoing-phone-account com.shilapi.xcertplay/com.shilapi.xcertplay.telecom.CarPlayConnectionService carplay 0 && su 0 pm disable com.android.dialer/com.android.incallui.InCallServiceImpl && echo BINNACLE_OK"),
        CARPLAY_CALLS_OFF("su 0 pm enable com.android.dialer/com.android.incallui.InCallServiceImpl && telecom set-phone-account-disabled com.shilapi.xcertplay/com.shilapi.xcertplay.telecom.CarPlayConnectionService carplay 0 && pm revoke com.shilapi.xcertplay android.permission.CALL_PHONE && echo BINNACLE_OK");

        final String shell;
        Command(String shell) { this.shell = shell; }
    }

    private static final int A_CNXN = 0x4e584e43, A_OPEN = 0x4e45504f, A_OKAY = 0x59414b4f,
            A_CLSE = 0x45534c43, A_WRTE = 0x45545257, A_AUTH = 0x48545541;
    private static final int VERSION = 0x01000000, MAX_DATA = 4096, LOCAL_ID = 1;
    private static final int PORT = 5555, TIMEOUT_MS = 15000;
    // A whole command gets at most a minute and 64 KB of output: something else listening on
    // 127.0.0.1:5555 while adbd is down can't stall or flood the dash.
    private static final long DEADLINE_MS = 60000;
    private static final int MAX_OUTPUT = 64 * 1024;

    private LocalShell() {}

    /** Marker every command echoes as its last step, only if all the steps before it succeeded. */
    static final String OK = "BINNACLE_OK";

    /**
     * Runs cmd and returns its output; throws if ADB isn't reachable or asks for a key, or if the
     * command didn't reach its success marker (a failed step stops the && chain).
     */
    static String run(Command cmd) throws IOException {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", PORT), 3000);
            s.setSoTimeout(TIMEOUT_MS);
            OutputStream out = s.getOutputStream();
            DataInputStream in = new DataInputStream(s.getInputStream());

            send(out, A_CNXN, VERSION, MAX_DATA, "host::\0".getBytes(StandardCharsets.US_ASCII));
            Msg m = read(in);
            if (m.cmd == A_AUTH) throw new IOException("ADB on the unit asks for a key");
            if (m.cmd != A_CNXN) throw new IOException("unexpected ADB reply");

            send(out, A_OPEN, LOCAL_ID, 0, ("shell:" + cmd.shell + "\0").getBytes(StandardCharsets.UTF_8));
            ByteArrayOutputStream result = new ByteArrayOutputStream();
            int remote = 0;
            long deadline = android.os.SystemClock.elapsedRealtime() + DEADLINE_MS;
            while (true) {
                long left = deadline - android.os.SystemClock.elapsedRealtime();
                if (left <= 0) throw new IOException("the shell took too long");
                s.setSoTimeout((int) Math.min(TIMEOUT_MS, left));
                m = read(in);
                if (m.cmd == A_OKAY) { remote = m.arg0; continue; }
                if (m.cmd == A_WRTE) {
                    if (result.size() + m.data.length > MAX_OUTPUT) throw new IOException("too much output from the shell");
                    result.write(m.data);
                    send(out, A_OKAY, LOCAL_ID, m.arg0, new byte[0]);
                    continue;
                }
                if (m.cmd == A_CLSE) {
                    send(out, A_CLSE, LOCAL_ID, remote != 0 ? remote : m.arg0, new byte[0]);
                    break;
                }
            }
            String text = result.toString("UTF-8").trim();
            if (!text.endsWith(OK)) throw new IOException(text.isEmpty() ? "no reply from the shell" : text);
            return text;
        }
    }

    private static final class Msg { int cmd, arg0, arg1; byte[] data; }

    private static void send(OutputStream out, int cmd, int arg0, int arg1, byte[] data) throws IOException {
        int sum = 0;
        for (byte b : data) sum += b & 0xff;
        ByteBuffer h = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);
        h.putInt(cmd).putInt(arg0).putInt(arg1).putInt(data.length).putInt(sum).putInt(~cmd);
        out.write(h.array());
        out.write(data);
        out.flush();
    }

    private static Msg read(DataInputStream in) throws IOException {
        byte[] head = new byte[24];
        in.readFully(head);
        ByteBuffer h = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN);
        Msg m = new Msg();
        m.cmd = h.getInt();
        m.arg0 = h.getInt();
        m.arg1 = h.getInt();
        int len = h.getInt();
        if (len < 0 || len > 1024 * 1024) throw new IOException("bad ADB message");
        m.data = new byte[len];
        in.readFully(m.data);
        return m;
    }
}
