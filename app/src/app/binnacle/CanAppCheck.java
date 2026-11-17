package app.binnacle;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Which stock CAN app builds the dash subscribes to (17 Nov audit #8, owner's choice). CanFeed
 * sends binder transactions 1 and 2 by number, which are regPropIdList and unregPropIdList on the
 * CAN app builds below; a build whose IMcuService lists its methods in another order would give
 * those numbers to other methods. So on a build not listed here the dash asks first: Apps → Unit →
 * CAN app → Allow, remembered for that version only. Reads the CAN app's version name; the CAN app
 * reports versionCode 1 for every build, and its version name is the build's date.
 */
final class CanAppCheck {
    /** Owner's unit (Dash 1, System version 202302282110), from the 26 Oct backup's xbsCanApp.apk. */
    static final String[] KNOWN = {"202211121648v1.0"};
    /** The one CAN app version the user allowed (Settings), or absent. */
    static final String ALLOWED_KEY = "canapp_allowed";
    static final String PROBLEM = "CAN app not tested: allow it in Apps → Unit";
    /** The stock CAN app (CanFeed.SVC_PKG; repeated here so this class stands alone in the tests). */
    private static final String PKG = "com.xbs.canapp";

    private CanAppCheck() {}

    /** The installed CAN app's version name, or null if it isn't installed or has none. */
    static String version(Context c) {
        try {
            return c.getPackageManager().getPackageInfo(PKG, 0).versionName;
        } catch (Throwable t) {
            return null;
        }
    }

    static boolean known(String version) {
        if (version == null) return false;
        for (String k : KNOWN) if (k.equals(version)) return true;
        return false;
    }

    /** True if the dash may subscribe to this version: a known build, or the one the user allowed. */
    static boolean allowedBuild(String version, String allowedByUser) {
        return version != null && (known(version) || version.equals(allowedByUser));
    }

    static boolean allowed(Context c, SharedPreferences prefs) {
        return allowedBuild(version(c), prefs.getString(ALLOWED_KEY, null));
    }
}
