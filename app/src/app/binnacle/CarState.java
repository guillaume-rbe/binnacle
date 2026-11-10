package app.binnacle;

import android.os.Bundle;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Locale;
import java.util.TreeMap;

/**
 * Latest decoded car values, filled from the stock CAN feed Bundles (see docs/FIELD_MAP.md).
 * Values arrive with mixed types (int, boolean, String like "18.0℃"), so every read is tolerant.
 * A field that has never arrived keeps its "unknown" default and the UI shows "--".
 */
public final class CarState {

    public static final int GEAR_UNKNOWN = -1, GEAR_P = 0, GEAR_R = 1, GEAR_N = 2, GEAR_D = 3;
    /** TPMS reads 64000 until the sensors wake after driving; anything this high (6 bar / 87 psi) is not a real tyre. */
    static final int TPMS_PLACEHOLDER_MIN = 60000;
    /** Parking-sensor value meaning "nothing detected". */
    static final int SENSOR_NONE = 255;

    // code 10: speed / energy
    public int speed;                     // km/h (or mph, see speedUnit)
    public String speedUnit;

    /** True when the car reports mph (same test the speed label uses). */
    public boolean speedInMph() {
        String u = speedUnit == null ? "" : speedUnit.toLowerCase();
        return u.contains("m") && !u.contains("k");
    }

    /** Speed in km/h whatever unit the car reports, for thresholds; -1 while unknown. */
    public float speedKmh() {
        return speed < 0 ? -1 : speedInMph() ? speed * 1.609344f : speed;
    }
    public int speedLimit;
    public boolean speedLimitOn;
    public int power;                     // kW magnitude (curentergyval, or the estimate)
    public boolean regen;                 // power is regen
    /** Decodes curentergyval and falls back to an estimate when the car's reading is unusable. */
    final PowerDecoder powerDecoder = new PowerDecoder();
    /** Demo feed: decode its values as they are, never through the power check. */
    boolean demoPower;

    /** True while power/regen are estimated from speed (the car's reading is unusable). */
    public boolean powerEstimated() { return powerDecoder.estimating(); }

    // code 11: battery / gear / odo / temp
    public int soc;                       // %
    public int gear;
    public long odometer;
    public String distUnit;
    public float outsideTemp;             // °C
    public int jhmail;                    // battery % code on some cars, range on others (BatteryDecoder)

    // code 9: lights / car info
    public boolean turnLeft, turnRight, lowBeam, highBeam, position, autopilot, charging;
    /** True once a lights message (live or restored) has said whether the low beams are on. */
    public boolean lowBeamKnown;
    /**
     * Code 18 curtLevel 0-100: the screen brightness the car sets for the unit (it follows the
     * Tesla's own display brightness: 44-100 by day, 15 at night on 29 Oct). -1 until known.
     */
    public int screenLevel = -1;
    /** Fog lights, auto high beam, Auto Hold and the seatbelt warning (stock icons: front_light, back_light, auto_light, hold, safe_line). */
    public boolean frontFog, rearFog, autoHighBeam, autoHold, seatbelt;
    public int driverWarning;
    /** Autopilot follow-distance setting (cardistance); the stock dash shows it as a coloured number. -1 = unknown. */
    public int followDistance;
    public int carColour;

    // code 12: TPMS (raw, see tyre())
    public final int[] tpmsRaw = new int[4];            // FL, FR, RL, RR
    public final boolean[] tpmsErr = new boolean[4];
    public String tpmsUnit;

    // code 15: doors
    public int doors;

    // code 16 / 2: cameras
    public boolean frontCamRequest, reverseCam;

    // codes 3 / 4: parking sensors FL, FML, FMR, FR / RL, RML, RMR, RR
    public final int[] front = new int[4];
    public final int[] rear = new int[4];

    /** Last raw Bundle per code, formatted only when the in-car raw overlay is shown. */
    public final TreeMap<Integer, Bundle> raw = new TreeMap<>();
    public long lastUpdateMs;

    public CarState() { reset(); }

    /** Back to "nothing received yet" (every value shows "--"), e.g. when switching demo/live feed. */
    public void reset() {
        speed = -1; speedUnit = "km/h"; speedLimit = 0; speedLimitOn = false; power = 0; regen = false;
        soc = -1; gear = GEAR_UNKNOWN; odometer = -1; distUnit = "km"; outsideTemp = Float.NaN; jhmail = -1;
        turnLeft = turnRight = lowBeam = highBeam = position = autopilot = charging = false;
        lowBeamKnown = false;
        screenLevel = -1;
        frontFog = rearFog = autoHighBeam = autoHold = seatbelt = false;
        driverWarning = 0; carColour = -1; followDistance = -1;
        Arrays.fill(tpmsRaw, -1); Arrays.fill(tpmsErr, false); tpmsUnit = "";
        doors = -1;
        frontCamRequest = reverseCam = false;
        Arrays.fill(front, SENSOR_NONE); Arrays.fill(rear, SENSOR_NONE);
        raw.clear();
        lastUpdateMs = 0;
    }

    public void apply(int code, Bundle b) {
        apply(code, b, true);
    }

    /** live = false: values restored from before a restart; they don't count as the feed being up. */
    public void apply(int code, Bundle b, boolean live) {
        if (b == null) return;
        try { b.setClassLoader(CarState.class.getClassLoader()); } catch (Throwable ignored) {}
        if (live) {
            raw.put(code, b);
            lastUpdateMs = android.os.SystemClock.elapsedRealtime();
        }
        switch (code) {
            case 9:
                turnLeft = on(b, "leftlight", turnLeft);
                turnRight = on(b, "rightlight", turnRight);
                lowBeam = on(b, "nearlight", lowBeam);
                lowBeamKnown |= b.containsKey("nearlight");
                highBeam = on(b, "farlight", highBeam);
                position = on(b, "postlight", position);
                autopilot = on(b, "autodrivermode", autopilot);
                charging = on(b, "chargelight", charging);
                frontFog = on(b, "frontaclight", frontFog);
                rearFog = on(b, "backaclight", rearFog);
                autoHighBeam = on(b, "autobiglight", autoHighBeam);
                autoHold = on(b, "autohandlight", autoHold);
                seatbelt = on(b, "safeline", seatbelt);
                driverWarning = num(b, "driverwarning", driverWarning);
                carColour = num(b, "carcolor", carColour);
                followDistance = num(b, "cardistance", followDistance);
                break;
            case 18:
                screenLevel = num(b, "curtLevel", screenLevel);
                break;
            case 10:
                speed = num(b, "carspeed", speed);
                speedUnit = str(b, "speedunit", speedUnit);
                speedLimit = num(b, "speedlimit", speedLimit);
                speedLimitOn = bool(b, "speedlimitsw", speedLimitOn);
                if (b.containsKey("curentergyval")) {
                    int v = num(b, "curentergyval", 0);
                    int kw = live && !demoPower
                            ? powerDecoder.read(android.os.SystemClock.elapsedRealtime(), speedKmh(), v)
                            : PowerDecoder.carKw(v);
                    regen = kw < 0;
                    power = Math.abs(kw);
                }
                break;
            case 11:
                soc = num(b, "batterylevel", soc);
                gear = num(b, "gear", gear);
                odometer = num(b, "totalmail", (int) odometer);
                distUnit = str(b, "distunit", distUnit);
                outsideTemp = flt(b, "enginetemp", outsideTemp);
                jhmail = num(b, "jhmail", jhmail);
                break;
            case 12:
                String[] k = {"lf", "rf", "lb", "rb"};
                for (int i = 0; i < 4; i++) {
                    tpmsRaw[i] = num(b, k[i] + "tpmsVal", tpmsRaw[i]);
                    tpmsErr[i] = bool(b, k[i] + "tpmserr", tpmsErr[i]);
                }
                tpmsUnit = str(b, "tpmsUnit", tpmsUnit);
                break;
            case 15:
                doors = num(b, "doorval", doors);
                break;
            case 16:
                frontCamRequest = bool(b, "frontcamstate", frontCamRequest);
                break;
            case 2:
                reverseCam = bool(b, "isRevStart", reverseCam);
                break;
            case 3:
                readSensors(b, new String[]{"fl", "fml", "fmr", "fr"}, front);
                break;
            case 4:
                readSensors(b, new String[]{"rl", "rml", "rmr", "rr"}, rear);
                break;
        }
    }

    private static void readSensors(Bundle b, String[] keys, int[] out) {
        for (int i = 0; i < keys.length; i++) out[i] = num(b, keys[i], out[i]);
    }

    // ---- derived values ----

    public boolean moving() { return gear == GEAR_D || gear == GEAR_R; }

    public char gearChar() {
        switch (gear) {
            case GEAR_P: return 'P';
            case GEAR_R: return 'R';
            case GEAR_N: return 'N';
            case GEAR_D: return 'D';
            default: return '-';
        }
    }

    /**
     * Tyre pressure in bar, or NaN if not available. Fixed scale, same as the stock launcher's
     * TmpView: the raw value is in 1/10000 of the unit the feed reports ("bar" or "psi"), so
     * 29000 + "bar" = 2.9 bar (42.1 psi; the stock dash showed 42.0 psi in the car on 27 Oct 2026),
     * and a low tyre stays low (5000 = 0.5 bar). 64000 is the "sensors not awake" placeholder.
     */
    public double tyreBar(int i) {
        int r = tpmsRaw[i];
        if (r <= 0 || r >= TPMS_PLACEHOLDER_MIN || tpmsErr[i]) return Double.NaN;
        double v = r / 10000.0;
        return tpmsUnit.toLowerCase(Locale.US).contains("psi") ? v / PSI_PER_BAR : v;
    }

    static final double PSI_PER_BAR = 14.5038;

    /** Display text for a tyre in "psi" or "bar", or null if not available yet. */
    public String tyre(int i, String unit) {
        double bar = tyreBar(i);
        if (Double.isNaN(bar)) return null;
        return "bar".equals(unit) ? String.format(Locale.US, "%.1f", bar)
                : String.format(Locale.US, "%.1f", bar * PSI_PER_BAR);
    }

    /** Short open-lid text from the doorval bitfield. */
    public String doorsText() {
        if (doors < 0) return "Doors --";
        if (doors == 0) return "Doors closed";
        // Bits as the stock launcher reads them. 0x80 (driver, front right on this RHD car) and
        // 0x08 (boot) are verified in the car; the rest follow the stock code.
        ArrayList<String> open = new ArrayList<>();
        if ((doors & 0x80) != 0) open.add("Driver door");
        if ((doors & 0x40) != 0) open.add("Passenger door");
        if ((doors & 0x20) != 0) open.add("Rear right door");
        if ((doors & 0x10) != 0) open.add("Rear left door");
        if ((doors & 0x08) != 0) open.add("Boot");
        if ((doors & 0x04) != 0) open.add("Frunk");
        if ((doors & ~0xfc) != 0) open.add("Door");           // any bit not in the stock table
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < open.size(); i++) s.append(i == 0 ? "" : ", ").append(open.get(i));
        return s.append(" open").toString();
    }

    // ---- tolerant Bundle readers ----

    static int num(Bundle b, String k, int def) {
        if (!b.containsKey(k)) return def;
        Object o = b.get(k);
        if (o instanceof Number) return ((Number) o).intValue();
        if (o instanceof Boolean) return (Boolean) o ? 1 : 0;
        if (o instanceof String) {
            float f = parseLeadingFloat((String) o);
            return Float.isNaN(f) ? def : Math.round(f);
        }
        return def;
    }

    static float flt(Bundle b, String k, float def) {
        if (!b.containsKey(k)) return def;
        Object o = b.get(k);
        if (o instanceof Number) return ((Number) o).floatValue();
        if (o instanceof String) {
            float f = parseLeadingFloat((String) o);
            return Float.isNaN(f) ? def : f;
        }
        return def;
    }

    static boolean bool(Bundle b, String k, boolean def) {
        if (!b.containsKey(k)) return def;
        Object o = b.get(k);
        if (o instanceof Boolean) return (Boolean) o;
        if (o instanceof Number) return ((Number) o).intValue() != 0;
        if (o instanceof String) return "true".equalsIgnoreCase((String) o) || "1".equals(o);
        return def;
    }

    /** Light flags: booleans, or ints where 1 = lit and 2 = off (leftlight / rightlight). */
    static boolean on(Bundle b, String k, boolean def) {
        if (!b.containsKey(k)) return def;
        Object o = b.get(k);
        if (o instanceof Number) return ((Number) o).intValue() == 1;
        return bool(b, k, def);
    }

    static String str(Bundle b, String k, String def) {
        if (!b.containsKey(k)) return def;
        Object o = b.get(k);
        return o == null ? def : String.valueOf(o);
    }

    /** Parses "18.0℃", "-3.5", "50" etc. Returns NaN if there is no leading number. */
    static float parseLeadingFloat(String s) {
        int i = 0, n = s.length();
        while (i < n && Character.isWhitespace(s.charAt(i))) i++;
        int start = i;
        if (i < n && (s.charAt(i) == '-' || s.charAt(i) == '+')) i++;
        while (i < n && (Character.isDigit(s.charAt(i)) || s.charAt(i) == '.')) i++;
        try { return Float.parseFloat(s.substring(start, i)); } catch (NumberFormatException e) { return Float.NaN; }
    }

    static String describe(Bundle b) {
        try {
            ArrayList<String> keys = new ArrayList<>(b.keySet());
            Collections.sort(keys);
            StringBuilder sb = new StringBuilder();
            for (String k : keys) {
                if (sb.length() > 0) sb.append(", ");
                sb.append(k).append('=').append(b.get(k));
            }
            return sb.toString();
        } catch (Throwable t) {
            return "(unreadable: " + t.getClass().getSimpleName() + ")";
        }
    }
}
