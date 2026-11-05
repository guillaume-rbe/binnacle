package app.binnacle;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.view.SoundEffectConstants;
import android.view.View;
import android.widget.Toast;

/**
 * The hidden screenshot button (owner, 5 Nov: shots while driving): with Advanced → Screenshot on
 * power bar tap on, a tap on the power bar saves the whole screen, CarPlay and the camera
 * included, through one pinned LocalShell command. A click says it was heard; a short note says
 * when it is saved (after the capture, so it isn't in the picture).
 */
final class Screenshot {
    static final String KEY = "screenshot_tap";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static volatile boolean busy;

    private Screenshot() {}

    static void take(final Activity activity, View tapped) {
        if (busy) return;
        busy = true;
        tapped.playSoundEffect(SoundEffectConstants.CLICK);
        final android.content.Context app = activity.getApplicationContext();
        new Thread(new Runnable() {
            @Override public void run() {
                String msg;
                try {
                    LocalShell.Command cmd = LocalShell.Command.SCREENSHOT;
                    LocalShell.run(cmd);
                    msg = "Screenshot saved";
                } catch (Exception e) {
                    msg = "Screenshot failed: " + e.getMessage();
                }
                final String shown = msg;
                MAIN.post(new Runnable() {
                    @Override public void run() {
                        busy = false;
                        Toast.makeText(app, shown, Toast.LENGTH_SHORT).show();
                    }
                });
            }
        }, "screenshot").start();
    }
}
