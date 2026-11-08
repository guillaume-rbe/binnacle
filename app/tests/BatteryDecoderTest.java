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
        BatteryDecoder.LevelWatch m = new BatteryDecoder.LevelWatch();
        m.see(62, 0); m.see(63, 1000); m.see(63, 5000);
        boolean early = m.moved;
        m.see(63, 11_000);
        if (early || !m.moved) { System.out.println("FAIL 62 -> 63 held 10 s should count as moving"); fails++; }
        else System.out.println("ok   62 -> 63 held 10 s is moving");
        if (fails > 0) { System.out.println(fails + " failed"); System.exit(1); }
        System.out.println("all battery checks passed");
    }
}
