package app.binnacle;

/** Every jhmail reading checked against the car's own screen, 28-30 Oct 2026 (tests/run.sh). */
public class BatteryDecoderTest {
    public static void main(String[] a) {
        int[][] seen = {
                {21663, 58}, {34038, 42}, {30743, 41}, {27422, 40}, {30777, 29}, {17493, 25},
                {4209, 21}, {15760, 100}, {39066, 95}, {39092, 95}, {2476, 96}, {43177, 13}};
        int fails = 0;
        for (int[] s : seen) {
            int got = BatteryDecoder.percent(s[0]);
            boolean ok = got == s[1];
            System.out.println((ok ? "ok   " : "FAIL ") + s[0] + " -> " + got + "%" + (ok ? "" : " (car showed " + s[1] + "%)"));
            if (!ok) fails++;
        }
        if (BatteryDecoder.percent(-1) != -1) { System.out.println("FAIL -1 should be unknown"); fails++; }
        // the closest two percents get with the jitter (audit 5 Nov): 59 % - 26 = 24932, 71 % + 26 = 24925
        int[][] close = {{24932, 59}, {24925, 71}, {24928, -1}, {24929, -1}};
        for (int[] c : close) {
            int got = BatteryDecoder.percent(c[0]);
            boolean ok = got == c[1];
            System.out.println((ok ? "ok   " : "FAIL ") + c[0] + " -> " + got + (ok ? "" : " (expected " + c[1] + ")"));
            if (!ok) fails++;
        }
        // pick(): batterylevel only once it has moved, else the jhmail table (issue #91)
        int[][] picks = {{58, 77, 0, 58}, {-1, 77, 0, -1}, {58, 81, 1, 81}, {-1, 81, 1, 81}, {58, -1, 1, 58}, {58, 200, 1, 58}};
        for (int[] p : picks) {
            int got = BatteryDecoder.pick(p[0], p[1], p[2] == 1);
            boolean ok = got == p[3];
            System.out.println((ok ? "ok   " : "FAIL ") + "pick(" + p[0] + ", " + p[1] + ", " + (p[2] == 1) + ") -> " + got);
            if (!ok) fails++;
        }
        // LevelWatch: stuck at 77 never moves; a one-off odd reading doesn't count; a held change does
        BatteryDecoder.LevelWatch w = new BatteryDecoder.LevelWatch();
        for (long t = 0; t < 600_000; t += 1000) w.see(77, t);
        w.see(0, 600_000); w.see(0, 605_000); w.see(77, 606_000); w.see(-1, 620_000); w.see(77, 630_000);
        if (w.moved) { System.out.println("FAIL stuck/odd readings counted as moving"); fails++; }
        else System.out.println("ok   stuck at 77 with a blip is not moving");
        // a blip as the very first reading must not become the baseline (Codex review on #110)
        BatteryDecoder.LevelWatch b = new BatteryDecoder.LevelWatch();
        b.see(0, 0);
        for (long t = 1000; t <= 60_000; t += 1000) b.see(77, t);
        if (b.moved) { System.out.println("FAIL first-reading blip then stuck at 77 counted as moving"); fails++; }
        else System.out.println("ok   first-reading blip then stuck at 77 is not moving");
        // time with no valid reading doesn't count towards the hold (Codex review on #110)
        BatteryDecoder.LevelWatch g = new BatteryDecoder.LevelWatch();
        g.see(77, 0); g.see(77, 10_000); g.see(0, 11_000); g.see(-1, 12_000); g.see(0, 22_000);
        if (g.moved) { System.out.println("FAIL a gap in the feed counted towards the hold"); fails++; }
        else System.out.println("ok   a gap in the feed restarts the hold");
        BatteryDecoder.LevelWatch m = new BatteryDecoder.LevelWatch();
        for (long t = 0; t <= 12_000; t += 1000) m.see(62, t);
        m.see(63, 13_000); m.see(63, 18_000);
        boolean early = m.moved;
        m.see(63, 23_000);
        if (early || !m.moved) { System.out.println("FAIL 62 held, then 63 held 10 s should count as moving"); fails++; }
        else System.out.println("ok   62 held, then 63 held 10 s is moving");
        // rangeFromCar(): jhmail is the car's range on some cars (issue #91), never on the owner's
        // {jhmail, batterylevel, levelMoves, carMiles, expected}
        int[][] ranges = {
                {342, 79, 1, 0, 342}, {377, 86, 1, 0, 377}, {347, 81, 1, 0, 347},  // #91, km
                {342, 79, 0, 0, -1},                                         // batterylevel not seen to move
                {21663, 58, 1, 0, -1}, {15760, 100, 1, 0, -1},               // the owner's % codes
                {215, 79, 1, 1, 215},                                         // a car set to miles
                {500, 79, 1, 1, -1},                                          // 10 km per % in miles: not a range
                {100, 79, 1, 0, -1}, {900, 79, 1, 0, -1},                     // not 2-8 km per %
                {0, 50, 1, 0, -1}, {342, 0, 1, 0, -1}, {342, -1, 1, 0, -1}, {-1, 79, 1, 0, -1}};
        for (int[] r : ranges) {
            int got = BatteryDecoder.rangeFromCar(r[0], r[1], r[2] == 1, r[3] == 1);
            boolean ok = got == r[4];
            System.out.println((ok ? "ok   " : "FAIL ") + "rangeFromCar(" + r[0] + ", " + r[1] + ", " + (r[2] == 1) + ", " + (r[3] == 1) + ") -> " + got);
            if (!ok) fails++;
        }
        // a table value under 1000 where batterylevel agrees with the table is not a range
        for (int v = 1; v < 1000; v++) {
            int p = BatteryDecoder.percent(v);
            if (p > 0 && BatteryDecoder.rangeFromCar(v, p, true, false) != -1) {
                System.out.println("FAIL % code " + v + " (" + p + " %) taken as a range"); fails++;
            }
        }
        if (fails > 0) { System.out.println(fails + " failed"); System.exit(1); }
        System.out.println("all battery checks passed");
    }
}
