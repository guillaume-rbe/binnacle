package app.binnacle;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Brings the dash back after Binnacle itself is updated (owner, 8 Nov: after the in-app update
 * Android left the recent-apps screen up and Home had to be pressed). Android sends
 * MY_PACKAGE_REPLACED only to the app that was replaced, to the new version. Starting a screen
 * from here relies on Binnacle being allowed to open over other apps (the camera's overlay
 * permission); without it Android ignores the start and Home still brings the dash back.
 * Opens only Binnacle's own Home screen; nothing reaches the car or the MCU.
 */
public final class Relaunch extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent intent) {
        if (!Intent.ACTION_MY_PACKAGE_REPLACED.equals(intent.getAction())) return;
        Launch.dash(c);
    }
}
