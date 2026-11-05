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

    /** Extra on the dash's own intent: the car shifted into Drive, open the in-dash camera. */
    static final String EXTRA_DRIVE_CAMERA = "app.binnacle.extra.DRIVE_CAMERA";

    /**
     * Brings the dash (singleTask Home) to the front, closing our own screens above it, and asks it
     * to open the in-dash camera. From behind another app this needs the overlay app-op, as the
     * stock camera launch does (AndroidManifest.xml).
     */
    static void dashCamera(Context c) {
        start(c, new Intent(c, DashActivity.class).putExtra(EXTRA_DRIVE_CAMERA, true), false);
    }

    /** Same broadcast the stock launcher sends when frontcamstate drops. */
    static void closeCamera(Context c) {
        Intent i = new Intent("com.xbs.action.reqcam").setPackage("com.xbs.frontcam");
        i.putExtra("reqVal", false);
        try {
            c.sendBroadcast(i);
        } catch (RuntimeException ignored) {}
    }

    static void apps(Context c) {
        start(c, new Intent(c, HubActivity.class), true);
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
