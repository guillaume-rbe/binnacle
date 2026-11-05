package app.binnacle;

import java.io.IOException;

/** Plain-Java checks for the update info file Updater reads (run by tests/run.sh). */
public class UpdaterTest {
    private static int failures;

    private static void check(String what, boolean ok) {
        System.out.println((ok ? "ok   " : "FAIL ") + what);
        if (!ok) failures++;
    }

    private static boolean refused(String text) {
        try {
            Updater.parse(text);
            return false;
        } catch (IOException e) {
            return true;
        }
    }

    public static void main(String[] args) throws IOException {
        String sha = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
        String good = "# Binnacle update info\nversionCode=8\nversionName=0.8\nsize=12345678\nsha256=" + sha + "\n";
        Updater.Release r = Updater.parse(good);
        check("reads all four fields", r.versionCode == 8 && "0.8".equals(r.versionName) && r.size == 12345678 && sha.equals(r.sha256));
        check("CRLF and upper-case hash", Updater.parse(good.replace("\n", "\r\n").replace(sha, sha.toUpperCase())).sha256.equals(sha));
        check("missing hash refused", refused("versionCode=8\nversionName=0.8\nsize=1\n"));
        check("short hash refused", refused(good.replace(sha, "abc")));
        check("bad version code refused", refused(good.replace("versionCode=8", "versionCode=eight")));
        check("zero size refused", refused(good.replace("size=12345678", "size=0")));
        check("huge size refused", refused(good.replace("size=12345678", "size=99999999999")));
        check("odd version name refused", refused(good.replace("versionName=0.8", "versionName=0.8 <b>")));
        check("empty file refused", refused(""));
        check("no CarPlay app in an older info file", r.carplay == null);
        String sha2 = "fedcba9876543210fedcba9876543210fedcba9876543210fedcba9876543210";
        String both = good + "carplayVersionCode=1403\ncarplayVersionName=1.4.3\ncarplaySize=40000000\ncarplaySha256=" + sha2 + "\n";
        Updater.Release b = Updater.parse(both);
        check("reads the CarPlay app's fields", b.carplay != null && b.carplay.versionCode == 1403 && "1.4.3".equals(b.carplay.versionName)
                && b.carplay.size == 40000000 && sha2.equals(b.carplay.sha256) && b.carplay.carplay == null && b.versionCode == 8);
        check("CarPlay fields all or none", refused(good + "carplayVersionCode=1403\n"));
        check("bad CarPlay hash refused", refused(both.replace(sha2, "abc")));
        check("bad CarPlay size refused", refused(both.replace("carplaySize=40000000", "carplaySize=-1")));
        check("CarPlay fields don't stand in for the dash's", refused(both.replace("versionCode=8\n", "")));
        if (failures > 0) { System.out.println(failures + " failed"); System.exit(1); }
        System.out.println("all update info checks passed");
    }
}
