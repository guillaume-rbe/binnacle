package app.binnacle;

/** Which CAN app builds the dash subscribes to (tests/run.sh). */
public class CanAppCheckTest {
    static int fails;

    static void check(String name, boolean got, boolean want) {
        System.out.println((got == want ? "ok   " : "FAIL ") + name + (got == want ? "" : " (got " + got + ")"));
        if (got != want) fails++;
    }

    public static void main(String[] a) {
        check("owner's build: known", CanAppCheck.known("202211121648v1.0"), true);
        check("owner's build: allowed", CanAppCheck.allowedBuild("202211121648v1.0", null), true);
        check("other build: not allowed", CanAppCheck.allowedBuild("202401061600v1.0", null), false);
        check("other build allowed by the user", CanAppCheck.allowedBuild("202401061600v1.0", "202401061600v1.0"), true);
        check("allowing one build doesn't allow the next", CanAppCheck.allowedBuild("202405010000v1.1", "202401061600v1.0"), false);
        check("no CAN app: not allowed", CanAppCheck.allowedBuild(null, null), false);
        check("no version name: not known", CanAppCheck.known(null), false);
        if (fails > 0) { System.out.println(fails + " failed"); System.exit(1); }
        System.out.println("all CAN app checks passed");
    }
}
