package app.binnacle;

/** Plain-Java checks for EfficiencyTracker (run by tests/run.sh). */
public class EfficiencyTrackerTest {
    private static int failures;

    private static void check(String what, boolean ok) {
        System.out.println((ok ? "ok   " : "FAIL ") + what);
        if (!ok) failures++;
    }

    public static void main(String[] args) {
        EfficiencyTracker t = new EfficiencyTracker();
        check("no data: NaN", Float.isNaN(t.tripWhPerKm()) && Float.isNaN(t.recentWhPerKm()));

        // 15 kW at 100 km/h for 6 min, sampled every 100 ms: 1.5 kWh over 10 km = 150 Wh/km
        long now = 0;
        for (int i = 0; i <= 3600; i++, now += 100) t.feed(now, 15f, 100f);
        check("trip 10 km (got " + t.tripKm() + ")", Math.abs(t.tripKm() - 10) < 0.01);
        check("trip 150 Wh/km (got " + t.tripWhPerKm() + ")", Math.abs(t.tripWhPerKm() - 150) < 1);
        check("recent 150 Wh/km (got " + t.recentWhPerKm() + ")", Math.abs(t.recentWhPerKm() - 150) < 1);

        // then 3 min of regen at -10 kW at 60 km/h: 0.5 kWh back over 3 km
        for (int i = 0; i < 1800; i++, now += 100) t.feed(now, -10f, 60f);
        check("regen 0.5 kWh (got " + t.tripRegenKwh() + ")", Math.abs(t.tripRegenKwh() - 0.5) < 0.01);
        check("regen share 1/3 (got " + t.regenShare() + ")", Math.abs(t.regenShare() - 0.333f) < 0.01);
        float trip = t.tripWhPerKm();
        check("trip net (1.5-0.5)/13 km = 77 Wh/km (got " + trip + ")", Math.abs(trip - 76.9f) < 1);
        // recent ~5 km: last 3 km regen (-0.5 kWh) + ~2 km cruise (+0.3 kWh) = about -40 Wh/km; whole
        // 10 s buckets (0.28 km at 100 km/h) are added until 5 km, so it overshoots by up to ~0.3 km
        float recent = t.recentWhPerKm();
        check("recent last ~5 km about -40 Wh/km (got " + recent + ")", recent < -25 && recent > -45);

        // a crawl pause (~6 s between readings) still counts: 6 s at 6 km/h is 10 m
        {
            EfficiencyTracker c = new EfficiencyTracker();
            c.feed(1_000, 2f, 6f);
            c.feed(7_000, 2f, 6f);
            check("6 s crawl gap counted (got " + c.tripKm() + " km)", Math.abs(c.tripKm() - 0.01) < 1e-6);
        }

        // a long gap (dash stopped) adds nothing
        double kmBefore = t.tripKm();
        t.feed(now + 600_000, 20f, 100f);
        check("gap skipped", Math.abs(t.tripKm() - kmBefore) < 1e-9);

        // graph is by time: now is the right-hand slot; the regen ended 10 min (60 slots) earlier
        float[] g = new float[EfficiencyTracker.BUCKETS];
        int n = t.graph(now + 600_000, g);
        check("graph spans the window (" + n + ")", n == EfficiencyTracker.BUCKETS);
        check("newest slot is the new reading", g[89] > 0);
        check("regen 60 slots back", g[29] < 0);
        check("the gap is empty", g[50] == 0 && g[88] == 0);

        // Codex: a slow last 5 km must not lose its distance to the 15-minute graph window
        EfficiencyTracker s = new EfficiencyTracker();
        long t2 = 0;
        for (int i = 0; i <= 3600; i++, t2 += 100) s.feed(t2, 15f, 100f);   // 10 km at 150 Wh/km
        for (int i = 0; i < 9000; i++, t2 += 100) s.feed(t2, 0.5f, 0f);     // 15 min stopped, 0.5 kW
        float slow = s.recentWhPerKm();
        // the 0.125 kWh used while stopped lands on the last segment: (0.75 + 0.125) kWh / ~5 km
        check("recent still ~175 Wh/km after 15 min stopped (got " + slow + ")", slow > 160 && slow < 190);

        // Codex: an hour-old bar must not show as recent
        EfficiencyTracker o = new EfficiencyTracker();
        o.feed(0, 50f, 50f);
        o.feed(100, 50f, 50f);
        o.feed(3_600_000, 10f, 50f);
        float[] g2 = new float[EfficiencyTracker.BUCKETS];
        o.graph(3_600_000, g2);
        boolean onlyNew = g2[89] == 10f;
        for (int k = 0; k < 89; k++) onlyNew &= g2[k] == 0;
        check("old bars expire after a long gap", onlyNew);

        // Codex: reset (feed switched) clears the drive
        o.reset();
        check("reset clears the drive", o.tripKm() == 0 && Float.isNaN(o.recentWhPerKm()) && o.graph(3_600_000, g2) == 0);

        if (failures > 0) {
            System.out.println(failures + " efficiency checks FAILED");
            System.exit(1);
        }
        System.out.println("all efficiency checks passed");
    }
}
