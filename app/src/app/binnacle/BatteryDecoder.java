package app.binnacle;

/**
 * Battery % from the car's code 11 "jhmail" field. On this car the real % field (batterylevel)
 * is stuck at 77, but jhmail moves exactly one step per 1 % and its exact value identifies the %:
 * the table below was recorded through a 13 % -> 100 % Supercharge on 30 Oct 2026 (0-12 % follow
 * the same 3321-per-% line) and decodes every reading checked against the car's screen
 * (21, 25, 29, 40, 41, 42, 58, 95, 100 %). Each level also shows up 25 or 26 higher (a jitter
 * bit), so a value matches a % if it is within TOLERANCE of the base, base+25/26 or base-25/26.
 * Base values of different percents differ by 59 or more (59 % and 71 %), but with the jitter two
 * percents can come as close as 7 (59 % - 26 = 24932, 71 % + 26 = 24925). A reading within
 * TOLERANCE of both is ambiguous and returns -1 (shown as unknown), never a wrong %.
 */
final class BatteryDecoder {

    /** jhmail base value for 0..100 %. */
    private static final int[] BASE = {
            4, 3325, 6646, 9967, 13288, 16609, 19930, 23251, 26572, 29893, 33214, 36535, 39856, 43177,
            46498, 49819, 53140, 56461, 59782, 63103, 888, 4209, 7530, 10851, 14172, 17493, 20814,
            24135, 27456, 30777, 34098, 37419, 828, 4149, 7470, 10791, 14112, 17433, 20754, 24075,
            27396, 30717, 34038, 37359, 40680, 44001, 47322, 50643, 53964, 57285, 60606, 63927, 1712,
            5033, 8354, 11675, 14996, 18317, 21638, 24958, 28279, 31600, 34921, 38242, 1652, 4973,
            8294, 11615, 14936, 18257, 21578, 24899, 28220, 31541, 34862, 38183, 41504, 44825, 48146,
            51467, 54788, 58109, 61430, 64750, 2535, 5856, 9177, 12498, 15819, 19140, 22461, 25782,
            29103, 32424, 35745, 39066, 2476, 5797, 9118, 12439, 15760
    };
    private static final int[] JITTER = {0, 25, 26, -25, -26};
    private static final int TOLERANCE = 4;

    private BatteryDecoder() {}

    /**
     * The % to show. The car's own batterylevel field works on most cars (it moved 62 -> 81 on a
     * 2020 Model 3 on newer firmware, issue #91) but is stuck at 77 on the owner's car, so it is
     * used only once it has been seen to change ({@link LevelWatch}); until then the jhmail table.
     * -1 = unknown (the caller shows the last saved %).
     */
    static int pick(int fromJhmail, int batterylevel, boolean levelMoves) {
        if (levelMoves && batterylevel >= 0 && batterylevel <= 100) return batterylevel;
        return fromJhmail;
    }

    /**
     * The car's own range, from jhmail, in the car's distance unit; -1 if jhmail isn't a range on
     * this car. On the owner's car jhmail is the 16-bit value decoded by {@link #percent}, but on a
     * 2020 Model 3 with a 2024-01 ROM and MCU0 mdl3-Ac1.07 it is the range the car shows (342 at
     * 79 %, 377 km at 86 %, issue #91). It is taken as a range only where batterylevel works
     * (levelMoves) and the two agree: under 1000 and between 2 and 8 km per % (any Model 3 or Y).
     * That also rules out the % codes: the few under 1000 (0, 20 and 32 %) are over 25 per %.
     * Taken to be in the car's distance unit, like the odometer (only seen in km so far).
     */
    static int rangeFromCar(int jhmail, int batterylevel, boolean levelMoves, boolean carMiles) {
        if (!levelMoves || batterylevel < 1 || batterylevel > 100) return -1;
        if (jhmail <= 0 || jhmail >= 1000) return -1;
        double kmPerPercent = (carMiles ? jhmail * 1.609344 : jhmail) / batterylevel;
        if (kmPerPercent < 2 || kmPerPercent > 8) return -1;
        return jhmail;
    }

    /**
     * Once jhmail has been a range ({@link #rangeFromCar}), it stays one: shown as is while it is a
     * plausible number (0 near empty, or a cold battery under 2 km per %), so the line doesn't
     * flip to the estimate and back. -1 if it isn't.
     */
    static int rangeKept(int jhmail, boolean levelMoves) {
        return levelMoves && jhmail >= 0 && jhmail < 1000 ? jhmail : -1;
    }

    /**
     * Notices batterylevel changing. A value counts only once it has held for HOLD_MS, so an odd
     * reading at wake-up (before or after the real one) doesn't count: the first value that holds
     * is the baseline, and a second, different value that holds means the field moves. The caller
     * saves {@link #moved} so it is kept for good.
     */
    static final class LevelWatch {
        static final long HOLD_MS = 10_000;
        private int baseline = -1, current = -1;
        private long currentSince;
        boolean moved;

        /** Feed every reading (with the time); returns true once the field has been seen to move. */
        boolean see(int level, long nowMs) {
            if (moved) return true;
            // no valid reading (feed lost, car.reset()): the next value has to hold afresh
            if (level < 0 || level > 100) { current = -1; return false; }
            if (level != current) { current = level; currentSince = nowMs; return false; }
            if (nowMs - currentSince < HOLD_MS) return false;
            if (baseline < 0) baseline = level;
            else if (level != baseline) moved = true;
            return moved;
        }
    }

    /** The battery % for a jhmail value, or -1 if it matches none (or, defensively, more than one). */
    static int percent(int jhmail) {
        if (jhmail < 0) return -1;
        int found = -1;
        for (int p = 0; p < BASE.length; p++) {
            for (int j : JITTER) {
                if (Math.abs(jhmail - (BASE[p] + j)) <= TOLERANCE) {
                    if (found >= 0 && found != p) return -1;
                    found = p;
                }
            }
        }
        return found;
    }
}
