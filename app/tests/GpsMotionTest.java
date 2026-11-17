package app.binnacle;

/** When the GPS says the car is driving (tests/run.sh). */
public class GpsMotionTest {
    static int fails;

    static void check(String name, boolean got, boolean want) {
        System.out.println((got == want ? "ok   " : "FAIL ") + name + (got == want ? "" : " (got " + got + ")"));
        if (got != want) fails++;
    }

    public static void main(String[] a) {
        GpsMotion g = new GpsMotion();
        check("no fixes: not moving", g.moving(0), false);
        for (int t = 0; t <= 4000; t += 1000) g.fix(t, 10f);
        check("fast for 4 s: not yet", g.moving(4000), false);
        g.fix(5000, 10f);
        check("fast for 5 s: driving", g.moving(5000), true);
        check("no fix for 3 s: no longer", g.moving(8000), false);

        g = new GpsMotion();
        for (int t = 0; t <= 10_000; t += 1000) g.fix(t, 1.0f);
        check("parked drift (3.6 km/h): not moving", g.moving(10_000), false);

        g = new GpsMotion();
        for (int t = 0; t <= 3000; t += 1000) g.fix(t, 10f);
        g.fix(4000, 2f);
        for (int t = 5000; t <= 9000; t += 1000) g.fix(t, 10f);
        check("a slow fix restarts the count", g.moving(9000), false);
        g.fix(10_000, 10f);
        check("then 5 s fast again", g.moving(10_000), true);

        g = new GpsMotion();
        for (int t = 0; t <= 2000; t += 1000) g.fix(t, 10f);
        for (int t = 6000; t <= 9000; t += 1000) g.fix(t, 10f);
        check("a gap in fixes restarts the count", g.moving(9000), false);

        g = new GpsMotion();
        for (int t = 0; t <= 6000; t += 1000) g.fix(t, Float.NaN);
        check("fixes without speed: not moving", g.moving(6000), false);
        if (fails > 0) { System.out.println(fails + " failed"); System.exit(1); }
        System.out.println("all GPS motion checks passed");
    }
}
