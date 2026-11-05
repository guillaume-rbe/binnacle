package app.binnacle;

/** When Settings and the setup wizard get out of the way of the cluster (audit D2; tests/run.sh). */
public class DriveWatchTest {
    static int fails;

    static void check(String name, boolean got, boolean want) {
        System.out.println((got == want ? "ok   " : "FAIL ") + name + (got == want ? "" : " (got " + got + ")"));
        if (got != want) fails++;
    }

    static CarState car(int gear, int speed, boolean cam) {
        CarState c = new CarState();
        c.gear = gear; c.speed = speed; c.frontCamRequest = cam;
        return c;
    }

    public static void main(String[] a) {
        DriveWatch w = new DriveWatch();
        w.seed(car(CarState.GEAR_P, 0, false));
        check("parked, nothing changes", w.startedDriving(car(CarState.GEAR_P, 0, false)), false);
        check("P -> D", w.startedDriving(car(CarState.GEAR_D, 0, false)), true);
        check("only once", w.startedDriving(car(CarState.GEAR_D, 0, false)), false);

        w = new DriveWatch(); w.seed(car(CarState.GEAR_P, 0, false));
        check("camera flag before the gear (code 16 first)", w.startedDriving(car(CarState.GEAR_P, 0, true)), true);

        w = new DriveWatch(); w.seed(car(CarState.GEAR_P, 0, false));
        check("feed reset: gear unknown", w.startedDriving(car(CarState.GEAR_UNKNOWN, -1, false)), false);
        check("P, blip, then D still leaves Park", w.startedDriving(car(CarState.GEAR_D, 0, false)), true);

        w = new DriveWatch(); w.seed(car(CarState.GEAR_D, 0, false));
        check("opened in D at a stop: stays", w.startedDriving(car(CarState.GEAR_D, 0, false)), false);
        check("then moves off", w.startedDriving(car(CarState.GEAR_D, 12, false)), true);

        w = new DriveWatch(); w.seed(car(CarState.GEAR_D, 50, true));
        check("opened while driving: stays", w.startedDriving(car(CarState.GEAR_D, 60, true)), false);
        check("D -> P is not driving", w.startedDriving(car(CarState.GEAR_P, 0, false)), false);

        w = new DriveWatch();
        check("first update only seeds", w.startedDriving(car(CarState.GEAR_D, 30, true)), false);

        check("parked: P", DriveWatch.parked(car(CarState.GEAR_P, 0, false)), true);
        check("parked: unknown and still", DriveWatch.parked(car(CarState.GEAR_UNKNOWN, -1, false)), true);
        check("parked: D", DriveWatch.parked(car(CarState.GEAR_D, 0, false)), false);
        check("parked: unknown but moving", DriveWatch.parked(car(CarState.GEAR_UNKNOWN, 20, false)), false);

        if (fails > 0) { System.out.println(fails + " failed"); System.exit(1); }
        System.out.println("all drive-watch checks passed");
    }
}
