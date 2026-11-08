package app.binnacle;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.widget.Toast;

/** Hands off to the stock apps. These are app intents on the head unit only; nothing reaches the car. */
final class Launch {
    static final String ZLINK_PKG = "com.zjinnova.zlink";
    static final String ZLINK_ACTION = "zjinnova.android.intent.action.ZLINK_MAIN";
    /** xcertplay: wireless CarPlay through the unit's own Apple chip and Android Bluetooth. */
    static final ComponentName XCERTPLAY = new ComponentName("com.shilapi.xcertplay", "com.shilapi.xcertplay.CarPlayHostActivity");
    static final ComponentName CAMERA = new ComponentName("com.xbs.frontcam", "com.xbs.frontcam.CameraActivity");
    static final ComponentName STOCK_DASH = new ComponentName("com.xbs.launcher1", "com.xbs.launcher1.MainActivity");

    private Launch() {}

    /**
     * CarPlay: xcertplay when it is installed (the owner's choice, 1 Nov: it matches ZLink and keeps
     * the dash on home Wi-Fi), otherwise the stock ZLink, which also does Android Auto.
     */
    static void phone(Context c) {
        if (hasXcertplay(c) && start(c, new Intent().setComponent(XCERTPLAY), false)) return;
        Intent i = new Intent(ZLINK_ACTION).setPackage(ZLINK_PKG);
        if (!start(c, i, false)) start(c, c.getPackageManager().getLaunchIntentForPackage(ZLINK_PKG), true);
    }

    static boolean hasXcertplay(Context c) {
        try {
            c.getPackageManager().getActivityInfo(XCERTPLAY, 0);
            return true;
        } catch (android.content.pm.PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    static void camera(Context c) {
        start(c, new Intent().setComponent(CAMERA), true);
    }

    /**
     * The Drive-camera request, kept in this process rather than as an intent extra: the dash is the
     * exported Home activity, so any app could otherwise send the extra and open the camera.
     * Taken once, and only within 10 s, so a launch that never arrived can't open it later.
     */
    private static volatile long driveCameraAt;

    static boolean takeDriveCamera() {
        long at = driveCameraAt;
        driveCameraAt = 0;
        return at != 0 && android.os.SystemClock.elapsedRealtime() - at < 10000;
    }

    /**
     * Brings the dash (singleTask Home) to the front, closing our own screens above it, and asks it
     * to open the in-dash camera. From behind another app this needs the overlay app-op, as the
     * stock camera launch does (AndroidManifest.xml).
     */
    static void dashCamera(Context c) {
        driveCameraAt = android.os.SystemClock.elapsedRealtime();
        // The registered activity is the subclass com.xbs.launcher.MainActivity, not DashActivity itself.
        start(c, new Intent(c, com.xbs.launcher.MainActivity.class), false);
    }

    /** Same broadcast the stock launcher sends when frontcamstate drops. */
    static void closeCamera(Context c) {
        Intent i = new Intent("com.xbs.action.reqcam").setPackage("com.xbs.frontcam");
        i.putExtra("reqVal", false);
        try {
            c.sendBroadcast(i);
        } catch (RuntimeException ignored) {}
    }

    /** Binnacle's own Home screen, from outside it (after an update: Relaunch). */
    static void dash(Context c) {
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                .setComponent(new ComponentName(c, "com.xbs.launcher.MainActivity"));
        start(c, home, false);
    }

    static void apps(Context c) {
        start(c, new Intent(c, HubActivity.class), true);
    }

    /**
     * Android's own "Display over other apps" switch for Binnacle (owner, 8 Nov: in place of an adb
     * command). Android 10 needs it before the camera may open from behind CarPlay, and before the
     * dash may reopen itself after an update (Relaunch). The user flips it; nothing is set here.
     */
    static void overAppsSetting(Context c) {
        Intent i = new Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                android.net.Uri.parse("package:" + c.getPackageName()));
        try {
            c.startActivity(i);
        } catch (RuntimeException e) {
            Toast.makeText(c, "Android settings → Apps → Binnacle → Display over other apps", Toast.LENGTH_LONG).show();
        }
    }

    /**
     * Android lets this app start screens from the background: without it, the camera on Drive is
     * silently blocked whenever another app is in front (audit D6). Read only.
     */
    static boolean canOpenOverApps(Context c) {
        return android.os.Build.VERSION.SDK_INT < 23 || android.provider.Settings.canDrawOverlays(c);
    }

    static boolean isDefaultHome(Context c) {
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        ResolveInfo ri = c.getPackageManager().resolveActivity(home, 0);
        return ri != null && ri.activityInfo != null && c.getPackageName().equals(ri.activityInfo.packageName);
    }

    static boolean start(Context c, Intent i, boolean complain) {
        if (i == null) {
            if (complain) Toast.makeText(c, "App not installed", Toast.LENGTH_SHORT).show();
            return false;
        }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            c.startActivity(i);
            return true;
        } catch (RuntimeException e) {
            if (complain) Toast.makeText(c, "Can't open: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            return false;
        }
    }
}
