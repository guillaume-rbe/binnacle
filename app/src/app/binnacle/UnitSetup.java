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
 * and its undo), opt-in from setup's "Set up the unit" step or Settings → Unit, and listed
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

    /**
     * Unit software (System version, ro.build.version.incremental) these changes were tested on.
     * Other A007 units run newer software (2024-01, issue #91) with a newer Bluetooth module, so
     * the changes that alter how the unit behaves are not part of setup's one button there; they
     * stay available as switches on the Unit page.
     */
    private static final String[] TESTED_SOFTWARE = {"202302282110"};

    /** The unit's software version, as the stock CarSetUp screen shows it (System version). */
    static String software() {
        return android.os.Build.VERSION.INCREMENTAL;
    }

    static boolean testedSoftware() {
        for (String t : TESTED_SOFTWARE) if (t.equals(software())) return true;
        return false;
    }

    enum Change {
        ADB_KEEP("Keep the unit unlocked after a restart",
                LocalShell.Command.ADB_KEEP_ON, LocalShell.Command.ADB_KEEP_OFF, false, true),
        STOCK_TOASTS("Block the stock dash's pop-ups",
                LocalShell.Command.STOCK_TOASTS_OFF, LocalShell.Command.STOCK_TOASTS_ON, false, true),
        STOCK_CAMERA("Turn off the stock full-screen camera",
                LocalShell.Command.STOCK_CAMERA_OFF, LocalShell.Command.STOCK_CAMERA_ON, false, false),
        GPS_DUMPS("Stop the GPS crash dumps",
                LocalShell.Command.GPS_DUMPS_OFF, LocalShell.Command.GPS_DUMPS_ON, false, true),
        ANDROID_BT("Android Bluetooth for wireless CarPlay",
                LocalShell.Command.ANDROID_BT_ON, LocalShell.Command.ANDROID_BT_OFF, true, false),
        BT_VOLUME_FIX("Car Bluetooth volume fix",
                LocalShell.Command.BT_VOLUME_FIX_ON, LocalShell.Command.BT_VOLUME_FIX_OFF, true, true),
        CARPLAY_CALLS("CarPlay calls",
                LocalShell.Command.CARPLAY_CALLS_ON, LocalShell.Command.CARPLAY_CALLS_OFF, false, false);

        final String title;
        final LocalShell.Command on, off;
        /** Takes effect only after the unit restarts. */
        final boolean needsRestart;
        /**
         * A plain Android setting, safe on any A007 software. The others change how the unit
         * behaves (Bluetooth, the camera, calls) and are recommended only on tested software.
         */
        final boolean anySoftware;

        Change(String title, LocalShell.Command on, LocalShell.Command off, boolean needsRestart, boolean anySoftware) {
            this.title = title;
            this.on = on;
            this.off = off;
            this.needsRestart = needsRestart;
            this.anySoftware = anySoftware;
        }

        /** Not yet tried on this unit's software: offered as a switch, not in setup's one button. */
        boolean untested() {
            return !anySoftware && !testedSoftware();
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
            if (!applies(c) || untested()) return false;
            // only when Binnacle shows the camera instead: dash camera on, Binnacle is Home (Codex),
            // and the dash's camera has shown a picture on this unit at least once
            if (this == STOCK_CAMERA) return DashApp.get(c).prefs().getBoolean("dash_camera", false) && Launch.isDefaultHome(c)
                    && DashApp.get(c).prefs().getBoolean(CameraPane.SEEN_KEY, false);
            return true;
        }
    }

    /** What is in place now. */
    static final class Status {
        /** The unit's ADB answered on 127.0.0.1:5555 (false on a fresh unit). */
        boolean reachable;
        /** Why it couldn't be read, if reachable is false for another reason than a refused connection. */
        String problem;
        /** ADB answers but the rule keeping it off the Wi-Fi didn't go in (AdbFirewall.failed after this read's attempt). */
        boolean openToWifi;
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
            // the unlock may be new (setup's EngineerMode step after the dash started, when the start-up
            // firewall found nothing to block): put the rule in place now, not at the next start
            // and wait for it, so the status below shows whether it worked (AdbFirewall.failed) (Codex)
            try {
                AdbFirewall.apply(DashApp.get(c).prefs()).get(15, java.util.concurrent.TimeUnit.SECONDS);
            } catch (Exception ignored) {
                // timed out or interrupted: the warning shows on the next read
            }
            s.openToWifi = AdbFirewall.failed;
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

    /**
     * The stock camera screen is switched off (STOCK_CAMERA is on). Only reads the component's state.
     * Nothing else opens a camera on Drive while it is off, so it must come back whenever the dash
     * stops showing the camera itself (audit #2).
     */
    static boolean stockCameraOff(Context c) {
        try {
            int s = c.getPackageManager().getComponentEnabledSetting(STOCK_CAMERA);
            return s == PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                    || s == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER;
        } catch (RuntimeException e) {
            return false;                              // not on this unit
        }
    }

    /**
     * Switches the stock camera screen back on if it is off. Blocking: run it off the main thread.
     * True if it is on afterwards (or was never off), false if it stayed off.
     */
    static boolean restoreStockCamera(Context c) {
        if (!stockCameraOff(c)) return true;
        try {
            set(Change.STOCK_CAMERA, false);
            return true;
        } catch (Exception e) {
            return false;
        }
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
