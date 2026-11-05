package app.binnacle;

/**
 * Notices the car starting to drive, so screens that cover the cluster (Settings, the setup wizard)
 * get out of the way (code audit 5 Nov, D2). Only the change counts: a screen opened while already
 * driving stays until it is closed. Pure logic on CarState, checked by tests/DriveWatchTest.
 */
final class DriveWatch {
    private int lastGear = CarState.GEAR_UNKNOWN;
    private boolean lastMoving, lastFrontCam, seeded;

    /** Starts from the car as it is now: whatever is already true when the screen opens never counts. */
    void seed(CarState c) {
        if (c.gear != CarState.GEAR_UNKNOWN) lastGear = c.gear;
        lastMoving = c.speedKmh() > 0;
        lastFrontCam = c.frontCamRequest;
        seeded = true;
    }

    /**
     * True when, since the last call, the car left Park, started moving, or raised its forward-camera
     * flag (the shift into Drive). A feed reset (gear unknown) keeps the last known gear, so P, a blip,
     * then D still counts as leaving Park.
     */
    boolean startedDriving(CarState c) {
        if (!seeded) { seed(c); return false; }
        boolean leftPark = lastGear == CarState.GEAR_P && c.gear != CarState.GEAR_P && c.gear != CarState.GEAR_UNKNOWN;
        boolean moving = c.speedKmh() > 0;
        boolean startedMoving = moving && !lastMoving;
        boolean camera = c.frontCamRequest && !lastFrontCam;
        if (c.gear != CarState.GEAR_UNKNOWN) lastGear = c.gear;
        lastMoving = moving;
        lastFrontCam = c.frontCamRequest;
        return leftPark || startedMoving || camera;
    }

    /** Parked as far as we know: in P, or gear not known yet and not moving. */
    static boolean parked(CarState c) {
        return c.gear == CarState.GEAR_P || (c.gear == CarState.GEAR_UNKNOWN && c.speedKmh() <= 0);
    }
}
