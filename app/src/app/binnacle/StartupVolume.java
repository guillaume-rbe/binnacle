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
 * on the unit too). It is not the car-setup speaker level, which goes to the MCU. Nothing here
 * reaches the car or the MCU. The volume keys still change it at any time; it is set again at the
 * next start, screen-on or Bluetooth audio connection, and whenever music is muted or jumps to 0
 * in one go while the screen is on (android.car.server and the CAN app do that on car wakes and
 * mutes; on 15 Nov it was 0 again on getting in, after the one-minute watch had ended). A level
 * turned down to 0 step by step with the wheel keys is left alone.
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
     * the screen off around its mute, which is then left), at most RESTORE_MAX times per
     * RESTORE_WINDOW_MS, so it never fights something that keeps zeroing music.
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
        final long[] restores = {0, 0};   // start of the current window, restores in it
        final Runnable watch = new Runnable() {
            @Override public void run() {
                if (android.os.SystemClock.uptimeMillis() >= watchUntil[0]) return;
                // the car went to sleep: its mute then is deliberate (screen-off also stops this)
                if (power != null && !power.isInteractive()) return;
                if (silent(audio)) apply(audio, prefs);
                main.postDelayed(this, WATCH_STEP_MS);
            }
        };
        final Runnable apply = new Runnable() {
            @Override public void run() {
                apply(audio, prefs);
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
                long now = android.os.SystemClock.uptimeMillis();
                if (now - restores[0] > RESTORE_WINDOW_MS) { restores[0] = now; restores[1] = 0; }
                if (++restores[1] > RESTORE_MAX) {                      // something keeps zeroing it: don't fight
                    if (restores[1] == RESTORE_MAX + 1) Log.w(TAG, "start-up volume: music keeps going to 0, leaving it");
                    return;
                }
                apply(audio, prefs);
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
                boolean reset = muted
                        ? i.getBooleanExtra(EXTRA_MUTED, false)
                        : zeroedInOneGo(i.getIntExtra(EXTRA_PREV, -1), i.getIntExtra(EXTRA_VALUE, -1));
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
                watchUntil[0] = 0;
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

    private static void apply(AudioManager audio, SharedPreferences prefs) {
        int p = percent(prefs.getString(KEY, DEFAULT));
        if (p < 0) return;
        int max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        int level = Math.max(1, Math.round(max * p / 100f));
        boolean muted = android.os.Build.VERSION.SDK_INT >= 23 && audio.isStreamMute(AudioManager.STREAM_MUSIC);
        if (!muted && audio.getStreamVolume(AudioManager.STREAM_MUSIC) == level) return;
        try {
            // the CAN app mutes music (ADJUST_MUTE) as well as setting it to 0 when the car sleeps;
            // a level set while muted stays silent
            if (muted) audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0);
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, level, 0);
            Log.i(TAG, "start-up volume " + p + "% (" + level + "/" + max + ")");
        } catch (SecurityException e) {
            Log.w(TAG, "start-up volume refused: " + e.getMessage());
        }
    }
}
