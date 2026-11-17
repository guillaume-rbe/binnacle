package app.binnacle;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

/**
 * Start-up volume (Apps → Settings → General): when the dash starts, and when the car's Bluetooth
 * audio connects, Android's media volume is set to the chosen level (100% by default; "Leave as
 * is" turns it off). The car sets the loudness, so anything below full on the unit only makes
 * CarPlay, music and Bluetooth audio quieter than the car's own sources.
 *
 * This is Android's own music volume, the one the wheel's volume keys change (the CAN app sets it
 * on the unit too). It is not the car-setup speaker level. Like the CAN app's own volume slider,
 * each change that takes effect makes the dash's car service pass the level on to the MCU's main
 * volume (see CAR_SERVICE_SET); nothing goes onto the car's CAN bus. The volume keys still change it at any time; it is set again at the
 * next start, screen-on or Bluetooth audio connection while the screen is on. Whenever music is
 * muted or jumps to 0 in one go while the screen is on (android.car.server and the CAN app do that
 * on car wakes and mutes; on 15 Nov it was 0 again on getting in, after the one-minute watch had
 * ended), it goes back to the level it had before, not to the start-up level; at most 5 times in
 * 30 s, then not again until the next screen-on. A level turned down to 0 step by step with the
 * wheel keys is left alone.
 */
final class StartupVolume {
    static final String KEY = "startup_volume";
    static final String DEFAULT = "100";
    private static final String TAG = "Binnacle";
    /** The CAN app puts the volume back itself on wake; set ours after it. */
    private static final long SETTLE_MS = 3000;
    /**
     * After setting it, watch this long for something zeroing or muting music again: on 8 Nov it
     * was 0 on getting in, so something set it after the 3 s mark. Checked every WATCH_STEP_MS.
     */
    private static final long WATCH_MS = 60_000, WATCH_STEP_MS = 2000;

    /** Android's volume broadcasts (hidden constants in AudioManager, unchanged since Android 4). */
    private static final String VOLUME_CHANGED = "android.media.VOLUME_CHANGED_ACTION",
            MUTE_CHANGED = "android.media.STREAM_MUTE_CHANGED_ACTION",
            EXTRA_STREAM = "android.media.EXTRA_VOLUME_STREAM_TYPE",
            EXTRA_VALUE = "android.media.EXTRA_VOLUME_STREAM_VALUE",
            EXTRA_PREV = "android.media.EXTRA_PREV_VOLUME_STREAM_VALUE",
            EXTRA_MUTED = "android.media.EXTRA_STREAM_VOLUME_MUTED";
    /**
     * A reset seen while the screen is on is put back this long after it (a car going to sleep turns
     * the screen off around its mute, which is then left), at most RESTORE_MAX times in
     * RESTORE_WINDOW_MS; then not again until the next screen-on, so it never fights something that
     * keeps zeroing music (17 Nov audit #6b).
     */
    private static final long RESTORE_DELAY_MS = 1500, RESTORE_WINDOW_MS = 30_000;
    private static final int RESTORE_MAX = 5;

    private StartupVolume() {}

    /**
     * Music went to 0 in one go (android.car.server or the CAN app setting it), not by the last step
     * of the wheel's volume keys, which go down one step at a time: that 0 is left alone.
     */
    static boolean zeroedInOneGo(int prev, int now) {
        return now == 0 && prev > 1;
    }

    /**
     * How many restores may still go ahead: RESTORE_MAX in a window, and once a window is used up,
     * none until reset() (the next screen-on).
     */
    static final class RestoreBudget {
        private final int max;
        private final long windowMs;
        private boolean started, spent;
        private long windowStart;
        private int used;

        RestoreBudget(int max, long windowMs) { this.max = max; this.windowMs = windowMs; }

        /** True = this restore may go ahead. The first refusal ends restores until reset(). */
        boolean take(long now) {
            if (spent) return false;
            if (!started || now - windowStart > windowMs) { started = true; windowStart = now; used = 0; }
            if (++used > max) { spent = true; return false; }
            return true;
        }

        boolean spent() { return spent; }

        void reset() { started = false; spent = false; used = 0; }
    }

    /** The start-up level for `percent` of `max` (at least the lowest step). */
    static int startupLevel(int max, int percent) {
        return Math.max(1, Math.round(max * percent / 100f));
    }

    /**
     * Which volume and mute broadcasts are a reset to put back. Android 10 also mutes music when it
     * is set to 0 and sends a mute broadcast after the volume one, so a 1 → 0 step stays silent
     * through both: the mute is ignored while music was last stepped down to 0 (the restore checks
     * again, in case the mute arrives first).
     */
    static final class Resets {
        private boolean steppedToZero;

        /** A VOLUME_CHANGED broadcast for music; true = put it back. */
        boolean onVolume(int prev, int now) {
            if (now != 0) { steppedToZero = false; return false; }
            if (zeroedInOneGo(prev, now)) { steppedToZero = false; return true; }
            if (prev == 1) steppedToZero = true;   // the wheel's last step (prev 0 = no change, keep)
            return false;
        }

        /** A STREAM_MUTE_CHANGED broadcast for music; true = put it back. */
        boolean onMute(boolean muted) {
            return muted && !steppedToZero;
        }

        /** Whether the music level was last stepped down to 0 by hand. */
        boolean steppedToZero() {
            return steppedToZero;
        }
    }

    /**
     * The car's Bluetooth audio (the dash's A2DP link to the car) is connected; read only. Also used
     * by CarPlayAuto, which waits for it after a start or wake (CarPlay's sound goes over it).
     */
    static boolean carBluetoothAudio(Context context) {
        AudioManager audio = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (audio == null) return true;
        for (AudioDeviceInfo d : audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
            if (d.getType() == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP) return true;
        }
        return false;
    }

    /**
     * Called once from DashApp.onCreate. The car "sleeping" only turns the screen off (the app keeps
     * running), and the CAN app mutes music and sets it to 0 then; so the level is also set again
     * when the screen comes back on (drive 5 Nov: no sound until the volume was raised by hand).
     */
    static void start(final Context context, final SharedPreferences prefs) {
        final AudioManager audio = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (audio == null) return;
        final Handler main = new Handler(Looper.getMainLooper());
        final android.os.PowerManager power = (android.os.PowerManager) context.getSystemService(Context.POWER_SERVICE);
        final long[] watchUntil = {0};
        final RestoreBudget budget = new RestoreBudget(RESTORE_MAX, RESTORE_WINDOW_MS);
        // the last music level above 0 seen while awake: what a mute or reset is put back to (audit #6a)
        final int[] lastLevel = {0};
        final Resets resets = new Resets();
        final Runnable watch = new Runnable() {
            @Override public void run() {
                if (android.os.SystemClock.uptimeMillis() >= watchUntil[0]) return;
                // the car went to sleep: its mute then is deliberate (screen-off also stops this)
                if (power != null && !power.isInteractive()) return;
                // a 0 stepped down by hand stays (audit #6c)
                if (silent(audio) && !resets.steppedToZero()) apply(audio, main, startupLevel(audio, prefs));
                main.postDelayed(this, WATCH_STEP_MS);
            }
        };
        final Runnable apply = new Runnable() {
            @Override public void run() {
                // start or Bluetooth connect while the car sleeps: left for the next screen-on (audit #6d)
                if (power != null && !power.isInteractive()) return;
                apply(audio, main, startupLevel(audio, prefs));
                watchUntil[0] = android.os.SystemClock.uptimeMillis() + WATCH_MS;
                main.removeCallbacks(watch);
                main.postDelayed(watch, WATCH_STEP_MS);
            }
        };
        main.postDelayed(apply, SETTLE_MS);
        // Each output keeps its own level: the car's Bluetooth audio is set when it connects.
        audio.registerAudioDeviceCallback(new AudioDeviceCallback() {
            @Override public void onAudioDevicesAdded(AudioDeviceInfo[] added) {
                for (AudioDeviceInfo d : added) {
                    if (d.isSink() && d.getType() == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP) {
                        if (power != null && !power.isInteractive()) return;   // set at the next screen-on
                        main.removeCallbacks(apply);
                        main.postDelayed(apply, SETTLE_MS);
                        return;
                    }
                }
            }
        }, main);
        // React to music being zeroed or muted while the screen is on, whenever it happens (the minute's
        // watch above missed a later reset on 15 Nov). Android's own volume broadcasts; read only.
        final Runnable restore = new Runnable() {
            @Override public void run() {
                if (power != null && !power.isInteractive()) return;   // the car went to sleep meanwhile
                if (!silent(audio)) return;                             // put back already (the CAN app)
                if (resets.steppedToZero()) return;                     // turned down to 0 by hand
                if (percent(prefs.getString(KEY, DEFAULT)) < 0) return;  // "Leave as is"
                if (!budget.take(android.os.SystemClock.uptimeMillis())) {   // something keeps zeroing it: don't fight
                    Log.w(TAG, "start-up volume: music keeps going to 0, leaving it until the next wake");
                    return;
                }
                // back to the level it had, not the start-up level: a mute mid-drive never turns it up
                apply(audio, main, lastLevel[0] > 0 ? lastLevel[0] : startupLevel(audio, prefs));
            }
        };
        android.content.IntentFilter change = new android.content.IntentFilter(VOLUME_CHANGED);
        change.addAction(MUTE_CHANGED);
        context.getApplicationContext().registerReceiver(new android.content.BroadcastReceiver() {
            @Override public void onReceive(Context c, android.content.Intent i) {
                if (i.getIntExtra(EXTRA_STREAM, -1) != AudioManager.STREAM_MUSIC) return;
                if (percent(prefs.getString(KEY, DEFAULT)) < 0) return;   // "Leave as is"
                if (power != null && !power.isInteractive()) return;   // the car sleeping: its mute is deliberate
                boolean muted = MUTE_CHANGED.equals(i.getAction());
                // remember the level the driver (or a finished fade) left it at
                if (!muted && fading == null) {
                    int value = i.getIntExtra(EXTRA_VALUE, -1);
                    if (value > 0) lastLevel[0] = value;
                }
                if (budget.spent()) return;
                boolean reset = muted
                        ? resets.onMute(i.getBooleanExtra(EXTRA_MUTED, false))
                        : resets.onVolume(i.getIntExtra(EXTRA_PREV, -1), i.getIntExtra(EXTRA_VALUE, -1));
                if (!reset) return;
                Log.i(TAG, "start-up volume: music " + (muted ? "muted" : "set to 0") + " while awake");
                main.removeCallbacks(restore);
                main.postDelayed(restore, RESTORE_DELAY_MS);   // a screen-off that goes with it arrives first
            }
        }, change, null, main);
        android.content.IntentFilter screen = new android.content.IntentFilter(android.content.Intent.ACTION_SCREEN_ON);
        screen.addAction(android.content.Intent.ACTION_SCREEN_OFF);
        context.getApplicationContext().registerReceiver(new android.content.BroadcastReceiver() {
            @Override public void onReceive(Context c, android.content.Intent i) {
                main.removeCallbacks(apply);
                main.removeCallbacks(watch);
                main.removeCallbacks(restore);
                stopFade(main);
                watchUntil[0] = 0;
                budget.reset();
                // the car sleeping: the CAN app mutes music on purpose, so leave it until the next wake
                if (android.content.Intent.ACTION_SCREEN_OFF.equals(i.getAction())) return;
                main.postDelayed(apply, SETTLE_MS);       // after the CAN app's own wake-up changes
            }
        }, screen);
    }

    /** Percent of full for a setting value; -1 = leave as is. */
    static int percent(String value) {
        if ("off".equals(value)) return -1;
        try {
            int p = Integer.parseInt(value);
            return p >= 1 && p <= 100 ? p : 100;
        } catch (NumberFormatException e) {
            return 100;
        }
    }

    /**
     * Music at 0 or muted. Within the watch only this is put back: a level turned down by hand
     * stays, and so does a 0 set by hand after the minute is up.
     */
    private static boolean silent(AudioManager audio) {
        return audio.getStreamVolume(AudioManager.STREAM_MUSIC) == 0
                || (android.os.Build.VERSION.SDK_INT >= 23 && audio.isStreamMute(AudioManager.STREAM_MUSIC));
    }

    /**
     * Music fades up to the level over about this long (owner, 15 Nov: quick, but not a sudden jump
     * into a car whose own volume expects full from the dash).
     */
    private static final long FADE_MS = 1000;
    /**
     * At most this many steps per fade: every music level change that takes effect makes the dash's
     * car service send the MCU its main-volume command, as the CAN app's slider does for each step.
     */
    private static final int FADE_STEPS = 8;
    /**
     * The ROM's "set by the car service" volume flag (AudioManager has no name for it). The ZIQI ROM's
     * CarAudioPatch.checkCarOpAllowed silently drops every music level or mute change from an ordinary
     * app unless the flags carry it (17 Nov: without it none of this ever changed the volume; the CAN
     * app, a system app, was what put it back). It does nothing else: the car service tests only 0x4000
     * (the MCU's own report), so a change made with it is passed on to the MCU's main volume exactly
     * like the CAN app's slider and car-on restore (owner OK 17 Nov, CLAUDE.md "MCU writes").
     */
    private static final int CAR_SERVICE_SET = 0x2000;
    /** The fade in progress, or null. Main thread only. */
    private static Runnable fading;

    /** The chosen start-up level on this unit; -1 = "Leave as is". */
    private static int startupLevel(AudioManager audio, SharedPreferences prefs) {
        int p = percent(prefs.getString(KEY, DEFAULT));
        return p < 0 ? -1 : startupLevel(audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC), p);
    }

    /** Sets music to `level` (-1: nothing), fading up from where it is. */
    private static void apply(AudioManager audio, Handler main, int level) {
        if (level < 0) return;
        int max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        level = Math.min(level, max);
        boolean muted = android.os.Build.VERSION.SDK_INT >= 23 && audio.isStreamMute(AudioManager.STREAM_MUSIC);
        int now = muted ? 0 : audio.getStreamVolume(AudioManager.STREAM_MUSIC);
        if (!muted && now == level) return;
        stopFade(main);
        try {
            if (now >= level) {                  // only ever lower: no fade needed
                setLevel(audio, level);
            } else {
                // start from the quietest step: the CAN app mutes music (ADJUST_MUTE) as well as setting
                // it to 0 when the car sleeps, a level set while muted stays silent, and an unmute alone
                // would bring back the level it kept at once. That step is one of the FADE_STEPS.
                int steps = FADE_STEPS;
                if (now == 0) { setLevel(audio, 1); steps--; }
                if (muted) audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, CAR_SERVICE_SET);
                fade(audio, main, Math.max(now, 1), level, steps);
            }
            Log.i(TAG, "start-up volume: " + now + " -> " + level + "/" + max);
        } catch (SecurityException e) {
            Log.w(TAG, "start-up volume refused: " + e.getMessage());
        }
    }

    /** Raises music from `from` to `to` in at most `steps` steps; stops if anything else changes it. */
    private static void fade(final AudioManager audio, final Handler main, final int from, final int to, int steps) {
        if (to <= from) return;
        final int stride = fadeStride(from, to, steps);
        final long step = FADE_MS / ((to - from + stride - 1) / stride);
        fading = new Runnable() {
            private int at = from;
            @Override public void run() {
                if (fading != this) return;
                // turned by hand, zeroed or muted again: stop (a reset is picked up and faded again)
                if (audio.getStreamVolume(AudioManager.STREAM_MUSIC) != at) { fading = null; return; }
                try {
                    at = Math.min(to, at + stride);
                    setLevel(audio, at);
                } catch (SecurityException e) {
                    fading = null;
                    return;
                }
                if (at < to) main.postDelayed(this, step); else fading = null;
            }
        };
        main.postDelayed(fading, step);
    }

    /** Levels per fade step, so a fade from `from` to `to` takes at most FADE_STEPS steps. */
    static int fadeStride(int from, int to) {
        return fadeStride(from, to, FADE_STEPS);
    }

    static int fadeStride(int from, int to, int steps) {
        steps = Math.max(1, steps);
        return Math.max(1, (to - from + steps - 1) / steps);
    }

    static void stopFade(Handler main) {
        if (fading != null) main.removeCallbacks(fading);
        fading = null;
    }

    private static void setLevel(AudioManager audio, int level) {
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, level, CAR_SERVICE_SET);
    }
}
