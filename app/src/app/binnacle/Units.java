package app.binnacle;

import java.util.Locale;

/**
 * Display units (Car → Units): Auto follows the units the car reports (speedunit / distunit),
 * Metric or Imperial override it. Values arrive from the car in the car's own units (speed and the
 * speed-limit sign in speedunit, odometer in distunit); the outside temperature arrives in °C, and
 * Binnacle's own figures (range estimate, efficiency, drive distance) are worked out in km.
 */
final class Units {
    static final double KM_PER_MILE = 1.609344;

    private Units() {}

    /** True when the dash shows mph, miles and °F. */
    static boolean imperial(DashApp app) {
        String u = app.units();
        if ("metric".equals(u)) return false;
        if ("imperial".equals(u)) return true;
        return app.car.speedInMph();
    }

    static boolean carMiles(CarState car) {
        String u = car.distUnit == null ? "" : car.distUnit.toLowerCase(Locale.ROOT);
        return u.contains("m") && !u.contains("k");
    }

    /** A speed in the car's speed unit, in the display unit (the same number when they match). */
    static int speed(DashApp app, int carValue) {
        if (carValue < 0) return carValue;
        boolean carMph = app.car.speedInMph(), mph = imperial(app);
        if (carMph == mph) return carValue;
        return (int) Math.round(mph ? carValue / KM_PER_MILE : carValue * KM_PER_MILE);
    }

    /** The speed-limit sign in the display unit; a converted limit is rounded to the nearest 5. */
    static int speedLimit(DashApp app, int carValue) {
        if (carValue <= 0 || app.car.speedInMph() == imperial(app)) return carValue;
        return Math.max(5, Math.round(speed(app, carValue) / 5f) * 5);
    }

    static String speedLabel(DashApp app) { return imperial(app) ? "MPH" : "KM / H"; }

    /** The odometer (in the car's distance unit) in the display unit. */
    static long odometer(DashApp app, long carValue) {
        if (carValue < 0) return carValue;
        boolean miles = imperial(app), carMi = carMiles(app.car);
        if (miles == carMi) return carValue;
        return Math.round(miles ? carValue / KM_PER_MILE : carValue * KM_PER_MILE);
    }

    /** A distance worked out in km, in the display unit. */
    static double distance(DashApp app, double km) { return imperial(app) ? km / KM_PER_MILE : km; }

    static String distLabel(DashApp app) { return imperial(app) ? "mi" : "km"; }

    /** Wh per km in the display unit (Wh/mi for imperial). */
    static float perDistance(DashApp app, float whPerKm) {
        return imperial(app) ? (float) (whPerKm * KM_PER_MILE) : whPerKm;
    }

    /** Outside temperature (°C from the car), rounded, with its unit; "--" while unknown. */
    static String temperature(DashApp app, float celsius) {
        if (Float.isNaN(celsius)) return "--";
        return imperial(app) ? Math.round(celsius * 9 / 5f + 32) + "°F" : Math.round(celsius) + "°C";
    }
}
