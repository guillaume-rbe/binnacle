package app.binnacle;

import android.util.Log;

/**
 * Puts xcertplay's location app-op back to allow whenever the dash links up with it (owner OK,
 * 5 Nov). Wireless CarPlay's Wi-Fi Direct hotspot needs location, and xcertplay runs it from
 * behind the dash, where the default "foreground" mode refuses it. The unit resets that mode on
 * every reboot and every xcertplay update, and CarPlay then fails to connect ("createGroup failed:
 * generic error") until it is set again. One pinned LocalShell command; the dash only.
 */
final class XcertplayLocation {
    private XcertplayLocation() {}

    static void restore() {
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    LocalShell.Command cmd = LocalShell.Command.XCERTPLAY_LOCATION;
                    LocalShell.run(cmd);
                    Log.i("Binnacle", "xcertplay location allowed (Wi-Fi Direct)");
                } catch (Exception e) {
                    Log.w("Binnacle", "couldn't allow xcertplay location: " + e.getMessage());
                }
            }
        }, "xcertplay-location").start();
    }
}
