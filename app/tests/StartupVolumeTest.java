package app.binnacle;

/** Which music volume changes StartupVolume puts back while the screen is on (tests/run.sh). */
public class StartupVolumeTest {
    static int fails;

    static void check(String name, boolean got, boolean want) {
        System.out.println((got == want ? "ok   " : "FAIL ") + name + (got == want ? "" : " (got " + got + ")"));
        if (got != want) fails++;
    }

    public static void main(String[] a) {
        check("android.car.server: full to 0", StartupVolume.zeroedInOneGo(40, 0), true);
        check("CAN app: 2 to 0", StartupVolume.zeroedInOneGo(2, 0), true);
        check("wheel key: last step 1 to 0", StartupVolume.zeroedInOneGo(1, 0), false);
        check("already 0", StartupVolume.zeroedInOneGo(0, 0), false);
        check("turned down, not to 0", StartupVolume.zeroedInOneGo(40, 20), false);
        check("turned up", StartupVolume.zeroedInOneGo(0, 40), false);
        check("extras missing", StartupVolume.zeroedInOneGo(-1, -1), false);
        if (fails > 0) { System.out.println(fails + " failed"); System.exit(1); }
    }
}
