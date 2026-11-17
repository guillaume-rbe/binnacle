package app.binnacle;

/**
 * Whether the GPS says the car is driving: above MIN_MS for at least HOLD_MS, with fixes no more
 * than MAX_GAP_MS apart. Used only to tell a car feed that went silent while the car was stopped
 * from a car that is still stopped (17 Nov audit #7). Pure logic: GPS fixes go in, nothing out.
 */
final class GpsMotion {
    /** About 15 km/h: well above GPS speed noise when parked. */
    static final float MIN_MS = 4.2f;
    static final long HOLD_MS = 5_000, MAX_GAP_MS = 2_500;

    private long movingSince = -1, lastFix = -1;

    /** A GPS fix at `now` (elapsed ms); speed in m/s, or NaN when the fix has none. */
    void fix(long now, float speedMs) {
        boolean fast = !Float.isNaN(speedMs) && speedMs >= MIN_MS;
        boolean gap = lastFix >= 0 && now - lastFix > MAX_GAP_MS;
        lastFix = now;
        if (!fast) { movingSince = -1; return; }
        if (movingSince < 0 || gap) movingSince = now;
    }

    /** Driving at `now`: fast fixes for HOLD_MS, the latest no older than MAX_GAP_MS. */
    boolean moving(long now) {
        return movingSince >= 0 && now - lastFix <= MAX_GAP_MS && now - movingSince >= HOLD_MS;
    }
}
