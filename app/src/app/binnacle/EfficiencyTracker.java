package app.binnacle;

/**
 * Energy use from the car's power reading (code 10 curentergyval: magnitude plus a regen flag;
 * driving peaks around 210, taken as kW, still to confirm against the car's own trip screen) and
 * speed. Power and speed are held between samples and integrated over time; a gap over MAX_GAP_MS
 * (dash stopped, feed paused) is skipped rather than guessed. Fed from DashApp on every code 10, so
 * it keeps counting while CarPlay or the camera is in front; reset when the feed changes (demo/live).
 *
 * Keeps a trip since the dash started (the car's sleep stops the dash, so this is roughly one
 * drive), distance segments of SEGMENT_KM for the recent Wh/km (by distance, however long it took),
 * and BUCKET_MS time buckets for the power graph (by time, empty where there was no data).
 */
final class EfficiencyTracker {
    static final long BUCKET_MS = 10_000;
    static final int BUCKETS = 90;                    // 15 minutes of graph
    static final float RECENT_KM = 5f;
    static final float SEGMENT_KM = 0.1f;
    private static final int SEGMENTS = (int) (RECENT_KM / SEGMENT_KM) + 1;
    /**
     * Longer than this between two readings counts as no data (the dash was stopped). Code 10
     * pauses up to ~6 s while crawling (DashApp.STALE_MS notes), so this matches the 8 s feed
     * watchdog instead of dropping those stretches (audit D10).
     */
    private static final long MAX_GAP_MS = 8_000;

    private long lastMs;
    private float lastKw, lastKmh;

    // trip totals
    private double usedKwh, regenKwh, km;

    // distance segments, newest (still filling) at (segHead + segCount - 1)
    private final double[] segKwh = new double[SEGMENTS], segKm = new double[SEGMENTS];
    private int segHead, segCount;

    // time buckets; bucket i covers [bucketStart[i], +BUCKET_MS); newest at (head + count - 1)
    private final float[] bucketPowerSum = new float[BUCKETS];
    private final int[] bucketSamples = new int[BUCKETS];
    private final long[] bucketStart = new long[BUCKETS];
    private int head, count;

    EfficiencyTracker() { reset(); }

    /** Forget everything (feed switched between demo and live). */
    void reset() {
        lastMs = -1;
        lastKw = lastKmh = 0;
        usedKwh = regenKwh = km = 0;
        segHead = segCount = 0;
        head = count = 0;
    }

    /** One reading: kw is signed (regen negative), kmh -1 when unknown. */
    void feed(long now, float kw, float kmh) {
        if (lastMs >= 0) {
            long dt = now - lastMs;
            if (dt > 0 && dt <= MAX_GAP_MS) {
                double h = dt / 3_600_000.0;
                double e = lastKw * h;
                double d = Math.max(0, lastKmh) * h;
                if (e >= 0) usedKwh += e; else regenKwh -= e;
                km += d;
                addToSegment(e, d);
            }
        }
        int b = bucketFor(now);
        bucketPowerSum[b] += kw;
        bucketSamples[b]++;
        lastMs = now;
        lastKw = kw;
        lastKmh = kmh;
    }

    private void addToSegment(double e, double d) {
        if (segCount == 0 || segKm[(segHead + segCount - 1) % SEGMENTS] >= SEGMENT_KM) {
            int i;
            if (segCount < SEGMENTS) {
                i = (segHead + segCount) % SEGMENTS;
                segCount++;
            } else {
                i = segHead;
                segHead = (segHead + 1) % SEGMENTS;
            }
            segKwh[i] = 0;
            segKm[i] = 0;
        }
        int i = (segHead + segCount - 1) % SEGMENTS;
        segKwh[i] += e;
        segKm[i] += d;
    }

    /** The bucket for now, appending empty buckets for any whole intervals with no data. */
    private int bucketFor(long now) {
        long start = now - (now % BUCKET_MS);
        if (count > 0) {
            int newest = (head + count - 1) % BUCKETS;
            long last = bucketStart[newest];
            if (start == last) return newest;
            if (start < last) return newest;          // clock went back: keep adding to the newest
            long next = last + BUCKET_MS;
            // skip far gaps outright: everything older would fall off the graph anyway
            if ((start - next) / BUCKET_MS >= BUCKETS) count = 0;
            else for (long t = next; t < start; t += BUCKET_MS) append(t);
        }
        return append(start);
    }

    private int append(long start) {
        int i;
        if (count < BUCKETS) {
            i = (head + count) % BUCKETS;
            count++;
        } else {
            i = head;
            head = (head + 1) % BUCKETS;
        }
        bucketPowerSum[i] = 0;
        bucketSamples[i] = 0;
        bucketStart[i] = start;
        return i;
    }

    double tripKm() { return km; }
    double tripUsedKwh() { return usedKwh; }
    double tripRegenKwh() { return regenKwh; }

    /** Net Wh/km since the dash started, NaN under 0.5 km. */
    float tripWhPerKm() {
        return km < 0.5 ? Float.NaN : (float) ((usedKwh - regenKwh) * 1000 / km);
    }

    /** Net Wh/km over the most recent RECENT_KM driven (or what there is, from 0.5 km), NaN before. */
    float recentWhPerKm() {
        double e = 0, d = 0;
        for (int k = segCount - 1; k >= 0 && d < RECENT_KM; k--) {
            int i = (segHead + k) % SEGMENTS;
            e += segKwh[i];
            d += segKm[i];
        }
        return d < 0.5 ? Float.NaN : (float) (e * 1000 / d);
    }

    /** Share of the energy used that regen won back this trip, 0..1, NaN before any use. */
    float regenShare() {
        return usedKwh <= 0 ? Float.NaN : (float) Math.min(1, regenKwh / usedKwh);
    }

    /**
     * Average kW of each BUCKET_MS of the last BUCKETS intervals up to now, oldest first; slot
     * BUCKETS - 1 is the current interval. Intervals with no data are 0. Returns how many of the
     * newest slots fall within the data held (0 when there is none yet).
     */
    int graph(long now, float[] out) {
        java.util.Arrays.fill(out, 0, BUCKETS, 0f);
        long current = now - (now % BUCKET_MS);
        int span = 0;
        for (int k = 0; k < count; k++) {
            int i = (head + k) % BUCKETS;
            long age = (current - bucketStart[i]) / BUCKET_MS;
            if (age < 0 || age >= BUCKETS) continue;
            int slot = BUCKETS - 1 - (int) age;
            out[slot] = bucketSamples[i] == 0 ? 0 : bucketPowerSum[i] / bucketSamples[i];
            span = Math.max(span, (int) age + 1);
        }
        return span;
    }
}
