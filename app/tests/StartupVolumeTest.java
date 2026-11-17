package app.binnacle;

/** Which music volume changes StartupVolume puts back while the screen is on (tests/run.sh). */
public class StartupVolumeTest {
    static int fails;

    static void check(String name, boolean got, boolean want) {
        System.out.println((got == want ? "ok   " : "FAIL ") + name + (got == want ? "" : " (got " + got + ")"));
        if (got != want) fails++;
    }

    public static void main(String[] a) {
        check("android.car.server: full to 0", StartupVolume.zeroedInOneGo(40, 0), true);
        check("CAN app: 2 to 0", StartupVolume.zeroedInOneGo(2, 0), true);
        check("wheel key: last step 1 to 0", StartupVolume.zeroedInOneGo(1, 0), false);
        check("already 0", StartupVolume.zeroedInOneGo(0, 0), false);
        check("turned down, not to 0", StartupVolume.zeroedInOneGo(40, 20), false);
        check("turned up", StartupVolume.zeroedInOneGo(0, 40), false);
        check("extras missing", StartupVolume.zeroedInOneGo(-1, -1), false);

        // both broadcasts together, in the order Android 10 sends them (volume, then mute)
        StartupVolume.Resets r = new StartupVolume.Resets();
        check("wheel 1 -> 0: volume kept", r.onVolume(1, 0), false);
        check("wheel 1 -> 0: its mute kept", r.onMute(true), false);
        check("wheel 1 -> 0: restore skips", r.steppedToZero(), true);
        check("then a CAN app mute at 0 kept", r.onMute(true), false);
        check("turned up again", r.onVolume(0, 3), false);
        check("unmute ignored", r.onMute(false), false);
        r = new StartupVolume.Resets();
        check("android.car.server 40 -> 0: put back", r.onVolume(40, 0), true);
        check("its mute: put back", r.onMute(true), true);
        check("not stepped", r.steppedToZero(), false);
        r = new StartupVolume.Resets();
        check("stepped to 0 earlier", r.onVolume(1, 0), false);
        check("raised by hand", r.onVolume(0, 20), false);
        check("CAN app mutes first", r.onMute(true), true);
        check("then sets 0 from 20", r.onVolume(20, 0), true);
        r = new StartupVolume.Resets();
        check("mute while at a level", r.onMute(true), true);

        // fades take at most 8 steps (each change makes the car service send the MCU its main volume)
        check("1 -> 40: stride 5", StartupVolume.fadeStride(1, 40) == 5, true);
        check("1 -> 8: one level a step", StartupVolume.fadeStride(1, 8) == 1, true);
        check("20 -> 40: stride 3", StartupVolume.fadeStride(20, 40) == 3, true);
        check("1 -> 40 in <= 8 steps", (39 + StartupVolume.fadeStride(1, 40) - 1) / StartupVolume.fadeStride(1, 40) <= 8, true);
        if (fails > 0) { System.out.println(fails + " failed"); System.exit(1); }
    }
}
