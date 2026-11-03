package app.binnacle;

/** PowerDecoder: format check against real readings, and the speed-based estimate (tests/run.sh). */
public class PowerDecoderTest {
    static int fails;

    static void check(boolean ok, String what) {
        System.out.println((ok ? "ok   " : "FAIL ") + what);
        if (!ok) fails++;
    }

    public static void main(String[] a) {
        // normal format (car logs 28 Oct - 1 Nov): hard regen from 100 km/h, a launch, cruising
        PowerDecoder d = new PowerDecoder();
        long t = 0;
        int[][] old = {{103, 1109}, {101, 1108}, {96, 1108}, {86, 1105}, {3, 14}, {5, 45}, {40, 210}, {60, 12}, {0, 0}, {0, 1}};
        boolean tripped = false;
        for (int rep = 0; rep < 200; rep++)
            for (int[] r : old) { d.read(t += 140, r[0], r[1]); tripped |= d.estimating(); }
        check(!tripped, "normal-format readings never switch to the estimate");
        check(d.read(t += 140, 50, 1060) == -36, "normal format decodes regen 0x400|36 as -36 kW");
        check(d.read(t += 140, 50, 42) == 42, "normal format decodes 42 as 42 kW");

        // new format (4 Nov): at rest it flickers 0 / 1151 (0x47F); on the move 0x180 bits flicker
        d = new PowerDecoder();
        int[] rest = {0, 1151, 0, 0, 1151, 0, 1151, 0};
        for (int v : rest) d.read(t += 140, 0, v);
        check(!d.estimating(), "three impossible readings are not enough");
        d.read(t += 140, 0, 1151);
        check(d.estimating(), "four impossible readings at a standstill switch to the estimate");
        check(d.read(t += 140, 0, 1151) == 0, "estimate is 0 kW when stopped");

        // glitches spread out: no 50 readings hold four of them
        d = new PowerDecoder();
        for (int g = 0; g < 6; g++) {
            d.read(t += 140, 0, 1151);
            for (int i = 0; i < 20; i++) d.read(t += 140, 30, 20);
        }
        check(!d.estimating(), "glitches 21 readings apart don't switch to the estimate");

        // a hard launch on the old format: big power for a couple of samples before speed moves
        d = new PowerDecoder();
        int[][] launch = {{0, 0}, {0, 150}, {0, 180}, {2, 200}, {4, 210}, {7, 210}, {11, 200}};
        for (int rep = 0; rep < 20; rep++) {
            for (int[] r : launch) d.read(t += 140, r[0], r[1]);
            for (int i = 0; i < 60; i++) d.read(t += 140, 40, 10);       // a short drive, then stop again
        }
        check(!d.estimating(), "hard launches on the normal format don't switch to the estimate");

        // suspect power (above 250, no regen flag) blocks going back but doesn't trip
        d = new PowerDecoder();
        for (int i = 0; i < 100; i++) d.read(t += 140, 50, 383);
        check(!d.estimating(), "power over 250 alone doesn't switch to the estimate");
        d.setEstimating(true);
        for (int i = 0; i < 3000; i++) d.read(t += 140, 50, i % 10 == 0 ? 383 : 12);
        check(d.estimating(), "suspect readings keep resetting the way back");

        // back to the car's reading after a long plausible run on the move (older Tesla software)
        d = new PowerDecoder();
        d.setEstimating(true);
        for (int i = 0; i < PowerDecoder.RECOVER - 1; i++) d.read(t += 140, 50, 12);
        check(d.estimating(), "still estimating just before the recovery count");
        d.read(t += 140, 50, 12);
        check(!d.estimating(), "a long run of plausible readings returns to the car's reading");

        // estimate: accelerating 2 m/s^2 at ~40 km/h is clearly power; braking is regen; cruise is small
        d = new PowerDecoder();
        d.setEstimating(true);
        int kw = 0;
        for (int i = 0; i <= 10; i++) kw = d.read(t += 100, 36 + i * 0.72f, 0);
        check(kw > 30 && kw < 60, "accelerating 2 m/s^2 at ~40 km/h estimates 30-60 kW (got " + kw + ")");
        for (int i = 0; i <= 10; i++) kw = d.read(t += 100, 60 - i * 0.72f, 0);
        check(kw < -15, "braking 2 m/s^2 at ~58 km/h estimates regen (got " + kw + ")");
        for (int i = 0; i <= 10; i++) kw = d.read(t += 100, 50, 0);
        check(kw >= 1 && kw <= 8, "steady 50 km/h estimates a few kW (got " + kw + ")");
        kw = d.read(t += 5000, 50, 0);
        check(kw >= 0 && kw <= 1, "after a gap in the feed the estimate starts again (got " + kw + ")");

        if (fails > 0) { System.out.println(fails + " failed"); System.exit(1); }
        System.out.println("all power checks passed");
    }
}
