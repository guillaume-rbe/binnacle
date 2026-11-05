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
 * CarPlay completely: the same as `adb shell ...` from the Mac, as the shell user (not root).
 *
 * Only the commands in {@link Command} can run; callers can't pass their own text, and
 * tools/check_readonly.sh checks the command strings. None of them touches the car, the MCU, CAN or
 * root; the one setting they change (Bluetooth auto-connect, on the dash only) is owner-approved
 * under CLAUDE.md's dash-local write rule. Blocking: call off the main thread.
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
        WIFI_REJOIN("service call wifi 58 i32 1 && svc wifi enable && echo BINNACLE_OK");

        final String shell;
        Command(String shell) { this.shell = shell; }
    }

    private static final int A_CNXN = 0x4e584e43, A_OPEN = 0x4e45504f, A_OKAY = 0x59414b4f,
            A_CLSE = 0x45534c43, A_WRTE = 0x45545257, A_AUTH = 0x48545541;
    private static final int VERSION = 0x01000000, MAX_DATA = 4096, LOCAL_ID = 1;
    private static final int PORT = 5555, TIMEOUT_MS = 15000;

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
            while (true) {
                m = read(in);
                if (m.cmd == A_OKAY) { remote = m.arg0; continue; }
                if (m.cmd == A_WRTE) {
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
