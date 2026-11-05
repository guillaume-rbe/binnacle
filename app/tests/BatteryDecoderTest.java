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
        if (fails > 0) { System.out.println(fails + " failed"); System.exit(1); }
        System.out.println("all battery checks passed");
    }
}
