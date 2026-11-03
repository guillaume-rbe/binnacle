package app.binnacle;

/**
 * Turns code 10 curentergyval into a signed power reading (kW, regen negative), and falls back to
 * an estimate from speed when the car's reading can't be trusted.
 *
 * Normal format: 0x400 = regen, low 10 bits = magnitude (about kW). After a Tesla software update
 * in early Nov 2026 the dash's MCU started passing a value that no longer follows power at all
 * (bits 0x180 flicker, "regen" while accelerating, heavy regen while stopped). Old-format logs
 * (~140,000 readings, 28 Oct - 1 Nov) never show heavy power at a standstill or regen above 85;
 * the new value does both within seconds. So: a few impossible readings switch to the estimate; a
 * long run of plausible readings on the move switches back, so cars on the older Tesla software
 * keep the real reading. Replayed on those logs: the old ones never switch, the 4 Nov drive
 * switches 22 s in (parked) and stays.
 *
 * The estimate is road-load physics on the speed's slope over the last second, calibrated on the
 * old logs (correlation with the car's reading 0.80-0.84, typical error about 8 kW). Speed comes
 * in whole km/h, so at a steady speed the estimate can jump by ~15 kW when the speed ticks over.
 */
final class PowerDecoder {

    /**
     * Readings that can't happen in the normal format: power above REST_MAX while the speed reads 0
     * (old logs: never above 14 at <= 3 km/h; a hard launch shows speed within a sample or two,
     * and TRIP needs more than that), or regen above REGEN_MAX at any speed (old logs: max 85).
     */
    static final int REST_MAX = 60, REGEN_MAX = 120;
    /** Power above this is suspect: it doesn't trip the estimate (fast cars) but blocks going back. */
    static final int SUSPECT_POWER = 250;
    /** TRIP impossible readings within any RECENT readings switch to the estimate. */
    static final int TRIP = 4, RECENT = 50;
    /**
     * RECOVER plausible readings above RECOVER_KMH, with no impossible or suspect reading in
     * between (slower plausible readings neither count nor reset), switch back to the car's reading.
     */
    static final int RECOVER = 1000, RECOVER_KMH = 10;

    // road-load model (Model 3 with a driver)
    static final double MASS = 1800, CRR = 0.011, CDA = 0.58, AIR = 1.2, G = 9.81;
    static final double EFF_OUT = 0.9, EFF_REGEN = 0.8, AUX_KW = 0.5, REGEN_CAP_KW = 80, POWER_CAP_KW = 250;
    static final long WINDOW_MS = 1000;

    private boolean estimating;
    private long count;                                  // readings so far
    private final long[] lastImpossible = new long[TRIP]; // reading numbers of the last TRIP impossible ones
    private int impossibleSeen;
    private int plausibleRun;

    private final long[] t = new long[32];
    private final double[] v = new double[32];
    private int n, head;

    /** True while the bar shows the estimate instead of the car's reading. */
    boolean estimating() { return estimating; }

    /** Restore the last state (saved by the app) so a restart doesn't flash a bad reading. */
    void setEstimating(boolean on) {
        estimating = on;
        plausibleRun = 0;
        impossibleSeen = 0;
    }

    /** The car's reading in the normal format, kW, regen negative. */
    static int carKw(int raw) {
        int m = raw & 0x3ff;
        return (raw & 0x400) != 0 ? -m : m;
    }

    static boolean impossible(int raw, float kmh) {
        int m = raw & 0x3ff;
        if ((raw & 0x400) != 0) return m > REGEN_MAX;
        return kmh == 0 && m > REST_MAX;
    }

    static boolean suspect(int raw) {
        return (raw & 0x400) == 0 && (raw & 0x3ff) > SUSPECT_POWER;
    }

    /**
     * One code 10 reading. ms = elapsed-realtime clock, kmh = speed (-1 unknown), raw = curentergyval.
     * Returns the power to show, kW, regen negative.
     */
    int read(long ms, float kmh, int raw) {
        addSpeed(ms, kmh);
        count++;
        if (impossible(raw, kmh)) {
            lastImpossible[impossibleSeen % TRIP] = count;
            impossibleSeen++;
            plausibleRun = 0;
            // the oldest of the last TRIP impossible readings is within RECENT readings of this one
            if (impossibleSeen >= TRIP && count - lastImpossible[impossibleSeen % TRIP] < RECENT) estimating = true;
        } else if (suspect(raw)) {
            plausibleRun = 0;
        } else {
            if (kmh > RECOVER_KMH) plausibleRun++;
            if (estimating && plausibleRun >= RECOVER) estimating = false;
        }
        return estimating ? (int) Math.round(estimate(kmh)) : carKw(raw);
    }

    private void addSpeed(long ms, float kmh) {
        if (kmh < 0) { n = 0; return; }
        if (n > 0 && ms - t[(head + n - 1) % t.length] > 3000) n = 0;   // gap: start again
        if (n == t.length) { head = (head + 1) % t.length; n--; }
        int i = (head + n) % t.length;
        t[i] = ms;
        v[i] = kmh / 3.6;
        n++;
        while (n > 0 && t[head] < ms - WINDOW_MS) { head = (head + 1) % t.length; n--; }
    }

    /** Battery power from the speed history, kW. */
    double estimate(float kmh) {
        if (kmh <= 0) return 0;
        if (n < 3) return AUX_KW;
        double mt = 0, mv = 0;
        for (int k = 0; k < n; k++) { int i = (head + k) % t.length; mt += t[i]; mv += v[i]; }
        mt /= n; mv /= n;
        double sxx = 0, sxy = 0;
        for (int k = 0; k < n; k++) {
            int i = (head + k) % t.length;
            double dt = (t[i] - mt) / 1000.0;
            sxx += dt * dt; sxy += dt * (v[i] - mv);
        }
        if (sxx < 0.05) return AUX_KW;                  // samples too close together for a slope
        double a = sxy / sxx;
        double last = (t[(head + n - 1) % t.length] - mt) / 1000.0;
        double speed = Math.max(0, mv + a * last * 0.5);
        if (speed < 0.3) return AUX_KW;
        double force = MASS * a + CRR * MASS * G + 0.5 * AIR * CDA * speed * speed;
        double wheelKw = force * speed / 1000;
        double kw = (wheelKw > 0 ? wheelKw / EFF_OUT : wheelKw * EFF_REGEN) + AUX_KW;
        return Math.max(-REGEN_CAP_KW, Math.min(POWER_CAP_KW, kw));
    }
}
