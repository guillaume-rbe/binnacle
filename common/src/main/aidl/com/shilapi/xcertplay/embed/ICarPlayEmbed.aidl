package com.shilapi.xcertplay.embed;

import android.os.Messenger;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Surface;

/**
 * Lets another app show CarPlay in a view of its own. Bound through CarPlayEmbedService, which
 * only apps signed with the same key may bind (signature permission).
 *
 * The session keeps running when the client unbinds; it ends only when xcertplay is closed or
 * the client calls disconnect().
 *
 * API version 2. Clients may call it with plain Binder transactions, so the order below is fixed:
 * transaction codes are FIRST_CALL_TRANSACTION (1) + the method's position (getApiVersion = 1 ...
 * declareSizes = 10). New methods go at the end only.
 */
interface ICarPlayEmbed {
    /** 1 */
    int getApiVersion();

    /**
     * 2. Where state goes; null removes it. Messages: what 1 = state (arg1: 0 idle, 1 needs setup,
     * 2 connecting, 3 streaming, 4 stopped after failed retries, 5 paused while xcertplay's
     * settings are open; data string "stage": the state in words), sent at once and on every
     * change; what 2 = CarPlay's car-maker tile was pressed while this client's view was showing;
     * what 3 = where to put the video view inside the client's view (int array "video": left, top,
     * width, height; empty = fill it). The stream covers every declared area, so for a smaller
     * area the video view is larger than the client's view and must be clipped by it.
     */
    void setListener(in Messenger listener);

    /**
     * 3. Shows the main CarPlay stream in [surface], which is [width] x [height] pixels. The size is
     * also the CarPlay display size: a declared size switches view area, another one reconnects
     * at that size. Replaces this client's previous surface.
     */
    void attachSurface(in Surface surface, int width, int height);

    /** 4. Stops drawing into the surface; returns once the decoder has let go of it. */
    void detachSurface();

    /** 5. A touch on the surface's view, in its own pixel coordinates. */
    oneway void sendTouch(in MotionEvent event);

    /** 6. Wheel and media keys. True when CarPlay takes the key (a session is up and it maps). */
    boolean sendKey(in KeyEvent event);

    /** 7. Starts a session if none runs and xcertplay has every permission it needs. */
    void start();

    /** 8. Ends the running session, and keeps it ended until start() or xcertplay's own screen. */
    void disconnect();

    /** 9. Opens xcertplay's own screen (settings, permission prompts). */
    void openSettings();

    /**
     * 10. Every size this client may show CarPlay at, as width, height pairs (e.g. a centre window
     * and full screen). They are declared to the phone as view areas, so switching between them
     * needs no reconnect. Takes effect at the next connection.
     */
    void declareSizes(in int[] widthHeightPairs);
}
