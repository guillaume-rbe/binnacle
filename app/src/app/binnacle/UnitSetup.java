package app.binnacle;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;

import java.net.ConnectException;
import java.util.EnumMap;

/**
 * Unit setup (owner, 8 Nov): the changes the owner made by hand over adb, so that a new user who
 * only installs Binnacle gets the same unit. Each one is a pair of pinned LocalShell commands (on
 * and its undo), opt-in from setup's "Set up the unit" step or Advanced → Unit setup, and listed
 * with its undo in docs/UNIT-CHANGES.md. Nothing here touches the car, the MCU or CAN.
 *
 * LocalShell needs the unit's ADB on 127.0.0.1:5555, which a fresh unit only has after the one
 * EngineerMode step (docs/ADB-GUIDE.md); {@link Status#reachable} says whether it answers.
 * Blocking calls: run them off the main thread.
 */
final class UnitSetup {
    private UnitSetup() {}

    static final String XCERTPLAY = "com.shilapi.xcertplay";
    private static final ComponentName STOCK_CAMERA = Launch.CAMERA;
    private static final ComponentName DIALER_INCALL =
            new ComponentName("com.android.dialer", "com.android.incallui.InCallServiceImpl");
    /** EngineerMode's main screen, the only launchable part of it (its Adb shell is behind it). */
    static final ComponentName ENGINEER_MODE =
            new ComponentName("com.sprd.engineermode", "com.sprd.engineermode.EngineerModeActivity");

    enum Change {
        ADB_KEEP("Keep the unit unlocked after a restart",
                "The one-time unlock (the unit's ADB, which Binnacle's unit features use) is lost at every restart "
                        + "without this. Reachable only from the unit itself unless Network ADB is on", LocalShell.Command.ADB_KEEP_ON, LocalShell.Command.ADB_KEEP_OFF, false),
        STOCK_TOASTS("Block the stock dash's pop-ups",
                "The stock dash keeps running behind Binnacle and shows toasts over everything (a white pill on Autopilot)",
                LocalShell.Command.STOCK_TOASTS_OFF, LocalShell.Command.STOCK_TOASTS_ON, false),
        STOCK_CAMERA("Turn off the stock full-screen camera",
                "It opens on Drive over everything; Binnacle shows the camera in the dash instead. "
                        + "Turn it back on if you use the stock camera", LocalShell.Command.STOCK_CAMERA_OFF, LocalShell.Command.STOCK_CAMERA_ON, false),
        GPS_DUMPS("Stop the GPS crash dumps",
                "The GPS service crashes often and saved up to 470 MB each time, filling the storage. "
                        + "Turns them off and deletes the saved ones; GPS keeps working",
                LocalShell.Command.GPS_DUMPS_OFF, LocalShell.Command.GPS_DUMPS_ON, false),
        ANDROID_BT("Android Bluetooth for wireless CarPlay",
                "Turns on the unit's own Bluetooth beside the stock module, which wireless CarPlay needs. Restart the unit after",
                LocalShell.Command.ANDROID_BT_ON, LocalShell.Command.ANDROID_BT_OFF, true),
        BT_VOLUME_FIX("Car Bluetooth volume fix",
                "Some cars set Bluetooth audio volume to zero (silent CarPlay audio). Lets the car's own volume rule. "
                        + "Restart the unit after", LocalShell.Command.BT_VOLUME_FIX_ON, LocalShell.Command.BT_VOLUME_FIX_OFF, true),
        CARPLAY_CALLS("CarPlay calls",
                "Calls go through the iPhone over CarPlay, and Android's own call screen stays off the dash",
                LocalShell.Command.CARPLAY_CALLS_ON, LocalShell.Command.CARPLAY_CALLS_OFF, false);

        final String title, summary;
        final LocalShell.Command on, off;
        /** Takes effect only after the unit restarts. */
        final boolean needsRestart;

        Change(String title, String summary, LocalShell.Command on, LocalShell.Command off, boolean needsRestart) {
            this.title = title;
            this.summary = summary;
            this.on = on;
            this.off = off;
            this.needsRestart = needsRestart;
        }

        /** Only offered where it makes sense: the CarPlay ones with Binnacle's CarPlay installed. */
        boolean applies(Context c) {
            switch (this) {
                case ANDROID_BT: case BT_VOLUME_FIX: case CARPLAY_CALLS: return installed(c, XCERTPLAY);
                case STOCK_CAMERA: return installed(c, Launch.CAMERA.getPackageName());
                case STOCK_TOASTS: return installed(c, Launch.STOCK_DASH.getPackageName());
                default: return true;
            }
        }

        /** Part of setup's one button: the camera one only when the dash shows the camera itself. */
        boolean recommended(Context c) {
            if (!applies(c)) return false;
            if (this == STOCK_CAMERA) return DashApp.get(c).prefs().getBoolean("dash_camera", false);
            return true;
        }
    }

    /** What is in place now. */
    static final class Status {
        /** The unit's ADB answered on 127.0.0.1:5555 (false on a fresh unit). */
        boolean reachable;
        /** Why it couldn't be read, if reachable is false for another reason than a refused connection. */
        String problem;
        final EnumMap<Change, Boolean> on = new EnumMap<>(Change.class);

        boolean isOn(Change ch) { return Boolean.TRUE.equals(on.get(ch)); }
    }

    /** Reads every change's state: one shell command, plus Android's own package manager. */
    static Status read(Context c) {
        Status s = new Status();
        PackageManager pm = c.getPackageManager();
        s.on.put(Change.STOCK_CAMERA, disabled(pm, STOCK_CAMERA));
        s.on.put(Change.CARPLAY_CALLS, installed(c, XCERTPLAY)
                && pm.checkPermission("android.permission.CALL_PHONE", XCERTPLAY) == PackageManager.PERMISSION_GRANTED
                && disabled(pm, DIALER_INCALL));
        try {
            LocalShell.Command cmd = LocalShell.Command.UNIT_STATUS;
            String out = LocalShell.run(cmd);
            s.reachable = true;
            s.on.put(Change.ADB_KEEP, out.contains("adb=5555"));
            s.on.put(Change.GPS_DUMPS, out.contains("dumps=0"));
            s.on.put(Change.ANDROID_BT, out.contains("bt=2"));
            s.on.put(Change.BT_VOLUME_FIX, out.contains("absvol=true"));
            s.on.put(Change.STOCK_TOASTS, out.contains("TOAST_WINDOW: ignore"));
        } catch (ConnectException e) {
            s.reachable = false;
        } catch (Exception e) {
            s.reachable = false;
            s.problem = e.getMessage();
        }
        return s;
    }

    /** Turns one change on or off (its undo). Throws with the shell's reply if it didn't work. */
    static void set(Change ch, boolean wantOn) throws Exception {
        LocalShell.Command cmd = wantOn ? ch.on : ch.off;
        LocalShell.run(cmd);
    }

    /** EngineerMode is on this unit (the one way to switch the unit's ADB on the first time). */
    static boolean hasEngineerMode(Context c) {
        Intent i = new Intent().setComponent(ENGINEER_MODE);
        ResolveInfo ri = c.getPackageManager().resolveActivity(i, 0);
        return ri != null;
    }

    private static boolean disabled(PackageManager pm, ComponentName cn) {
        try {
            return pm.getComponentEnabledSetting(cn) == PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
        } catch (RuntimeException e) {
            return false;
        }
    }

    static boolean installed(Context c, String pkg) {
        try {
            c.getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }
}
