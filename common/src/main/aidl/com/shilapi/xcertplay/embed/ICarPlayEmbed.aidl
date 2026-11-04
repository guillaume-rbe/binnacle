package com.shilapi.xcertplay.embed;

import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Surface;
import com.shilapi.xcertplay.embed.ICarPlayEmbedListener;

/**
 * Lets another app show CarPlay in a view of its own. Bound through CarPlayEmbedService, which
 * only apps signed with the same key may bind (signature permission).
 *
 * The session keeps running when the client unbinds; it ends only when xcertplay is closed or
 * the client calls disconnect(). Version: getApiVersion() returns 1.
 */
interface ICarPlayEmbed {
    int getApiVersion();

    /** One listener per client; null removes it. Called back at once with the current state. */
    void setListener(ICarPlayEmbedListener listener);

    /**
     * Shows the main CarPlay stream in [surface], which is [width] x [height] pixels. The size is
     * also the CarPlay display size: a size the phone already knows switches view area, another
     * one reconnects at that size. Replaces this client's previous surface.
     */
    void attachSurface(in Surface surface, int width, int height);

    /** Stops drawing into the surface; returns once the decoder has let go of it. */
    void detachSurface();

    /** A touch on the surface's view, in its own pixel coordinates. */
    oneway void sendTouch(in MotionEvent event);

    /** Wheel and media keys. True when CarPlay takes the key (a session is up and it maps). */
    boolean sendKey(in KeyEvent event);

    /** Starts a session if none runs and xcertplay has every permission it needs. */
    void start();

    /** Ends the running session, and keeps it ended until start() or xcertplay's own screen. */
    void disconnect();

    /** Opens xcertplay's own screen (settings, permission prompts). */
    void openSettings();
}
