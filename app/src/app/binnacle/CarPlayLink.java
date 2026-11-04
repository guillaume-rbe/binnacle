package app.binnacle;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.Parcel;
import android.os.RemoteException;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Surface;

/**
 * The link to xcertplay's CarPlay service (CarPlayEmbedService, ICarPlayEmbed API 2), so CarPlay
 * can be drawn in the dash's own window. It never reaches the car: xcertplay is an app on the
 * unit, and this class only binds that one service (fixed package and class) and makes the fixed
 * calls below. State comes back on a Messenger this class only receives on, like the CAN feed.
 *
 * Plain Binder calls, no AIDL (the guards allow no binder stubs): each call writes the interface
 * token and its arguments in the order AIDL uses, and call() refuses any code but TX_VERSION ..
 * TX_SIZES. The service is protected by a signature permission, so this works only when xcertplay
 * is signed with the same key as the dash; otherwise binding fails and the dash opens xcertplay's
 * own screen as before.
 */
final class CarPlayLink implements ServiceConnection {
    interface Listener {
        /** Bound or lost; while unbound the dash uses xcertplay's own screen. */
        void onLinkChanged(boolean connected);
        void onCarPlayState(int state, String stage);
        /** CarPlay's car-maker tile, pressed while our view was showing. */
        void onHostUi();
        /** Where the video view goes inside ours: left, top, width, height; empty = fill. */
        void onVideoLayout(int[] video);
        /** What the iPhone is playing, or null for nothing (also when the link is lost). */
        void onNowPlaying(NowPlaying now);
    }

    static final int STATE_IDLE = 0, STATE_NEEDS_SETUP = 1, STATE_CONNECTING = 2, STATE_STREAMING = 3,
            STATE_FAILED = 4, STATE_PAUSED = 5;

    private static final String TAG = "binnacle";
    private static final String PKG = "com.shilapi.xcertplay";
    private static final String SERVICE = "com.shilapi.xcertplay.CarPlayEmbedService";
    private static final String DESCRIPTOR = "com.shilapi.xcertplay.embed.ICarPlayEmbed";
    private static final int API_VERSION = 2;
    /** ICarPlayEmbed transaction codes (FIRST_CALL_TRANSACTION + position), fixed by API 2. */
    private static final int TX_VERSION = 1, TX_LISTENER = 2, TX_ATTACH = 3, TX_DETACH = 4, TX_TOUCH = 5,
            TX_KEY = 6, TX_START = 7, TX_DISCONNECT = 8, TX_SETTINGS = 9, TX_SIZES = 10;
    private static final int MSG_STATE = 1, MSG_HOST_UI = 2, MSG_LAYOUT = 3, MSG_NOW_PLAYING = 4;

    private final Context context;
    private final Listener listener;
    private final Messenger receiver;
    private IBinder binder;
    private boolean bound;
    private int state = STATE_IDLE;

    CarPlayLink(Context c, Listener l) {
        context = c;
        listener = l;
        receiver = new Messenger(new Handler(Looper.getMainLooper()) {
            @Override public void handleMessage(Message m) {
                if (m.what == MSG_STATE) {
                    state = m.arg1;
                    String stage = m.peekData() != null ? m.peekData().getString("stage", "") : "";
                    listener.onCarPlayState(state, stage);
                } else if (m.what == MSG_HOST_UI) {
                    listener.onHostUi();
                } else if (m.what == MSG_LAYOUT) {
                    int[] video = m.peekData() != null ? m.peekData().getIntArray("video") : null;
                    listener.onVideoLayout(video != null && video.length == 4 ? video : new int[0]);
                } else if (m.what == MSG_NOW_PLAYING) {
                    listener.onNowPlaying(m.arg1 == 1 && m.peekData() != null ? nowPlaying(m.peekData()) : null);
                }
            }
        });
    }

    /** The cover xcertplay sent last; it sends each cover once, so it is kept here by its id. */
    private android.graphics.Bitmap art;
    private int artId = -1;

    private NowPlaying nowPlaying(android.os.Bundle d) {
        int id = d.getInt("artId", -1);
        byte[] bytes = d.getByteArray("art");
        if (id < 0) { art = null; artId = -1; }
        else if (bytes != null) {
            art = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
            artId = art != null ? id : -1;
        }
        return new NowPlaying(d.getString("title"), d.getString("artist"), d.getString("album"), d.getString("app"),
                d.getLong("duration"), d.getLong("position"), d.getBoolean("playing"), d.getBoolean("controls"),
                id, id >= 0 && id == artId ? art : null);
    }

    /** Binds xcertplay's service; false if it isn't there or we may not (other signing key). */
    boolean bind() {
        if (bound) return true;
        Intent i = new Intent().setComponent(new ComponentName(PKG, SERVICE));
        try {
            bound = context.bindService(i, this, Context.BIND_AUTO_CREATE);
        } catch (SecurityException e) {
            Log.i(TAG, "CarPlay link not allowed: " + e.getMessage());
            refused = true;             // another signing key: no point asking again
            bound = false;
        }
        if (!bound) {
            try { context.unbindService(this); } catch (RuntimeException ignored) { }
        }
        boundAt = android.os.SystemClock.elapsedRealtime();
        return bound;
    }

    /** Refused for good (other signing key, or an xcertplay without API 2). */
    private boolean refused;
    private long boundAt;
    /** A bind that hasn't connected after this long was dropped (the power manager at boot). */
    private static final long CONNECT_WAIT_MS = 5000;

    /**
     * Binds again when there is no link yet. Right after boot the unit's power manager (Unisoc
     * BackgroundCleanHelper) refuses binds before it has loaded its allow list, and a refused
     * bind never connects, so the dash retries each time it comes to the front.
     */
    void retry() {
        if (binder != null || refused) return;
        if (bound) {
            // still connecting: leave it be; only a bind that never connected is replaced
            if (android.os.SystemClock.elapsedRealtime() - boundAt < CONNECT_WAIT_MS) return;
            bound = false;
            try { context.unbindService(this); } catch (RuntimeException ignored) { }
        }
        bind();
    }

    void unbind() {
        if (!bound) return;
        if (binder != null) {
            Parcel d = token();
            d.writeInt(0);                  // no listener: xcertplay lets go of our Messenger
            call(TX_LISTENER, d, false);
        }
        bound = false;
        binder = null;
        state = STATE_IDLE;
        try { context.unbindService(this); } catch (RuntimeException ignored) { }
        listener.onLinkChanged(false);
    }

    boolean connected() { return binder != null; }

    int state() { return state; }

    /** A CarPlay session is up: connecting, showing, or with xcertplay's settings open. */
    static boolean running(int state) {
        return state == STATE_CONNECTING || state == STATE_STREAMING || state == STATE_PAUSED;
    }

    @Override public void onServiceConnected(ComponentName name, IBinder service) {
        binder = service;
        int version = version();
        if (version != API_VERSION) {
            Log.i(TAG, "CarPlay link: xcertplay API " + version + ", need " + API_VERSION);
            refused = true;
            binder = null;
            listener.onLinkChanged(false);
            return;
        }
        Parcel d = token();
        d.writeInt(1);                      // non-null Messenger
        receiver.writeToParcel(d, 0);
        call(TX_LISTENER, d, false);
        listener.onLinkChanged(true);
    }

    @Override public void onServiceDisconnected(ComponentName name) {
        binder = null;
        state = STATE_IDLE;
        listener.onLinkChanged(false);
    }

    /** xcertplay was updated or reinstalled: this binding is dead for good, so bind afresh. */
    @Override public void onBindingDied(ComponentName name) {
        refused = false;            // the new xcertplay may well be the right one
        unbind();
        bind();
    }

    private int version() {
        Parcel r = callForReply(TX_VERSION, token());
        if (r == null) return -1;
        try { return r.readInt(); } finally { r.recycle(); }
    }

    /** The sizes the dash shows CarPlay at, as width, height pairs (centre window and full screen). */
    void declareSizes(int[] widthHeightPairs) {
        Parcel d = token();
        d.writeIntArray(widthHeightPairs);
        call(TX_SIZES, d, false);
    }

    void attach(Surface surface, int width, int height) {
        Parcel d = token();
        d.writeInt(1);
        surface.writeToParcel(d, 0);
        d.writeInt(width);
        d.writeInt(height);
        call(TX_ATTACH, d, false);
    }

    void detach() { call(TX_DETACH, token(), false); }

    void touch(MotionEvent e) {
        Parcel d = token();
        d.writeInt(1);
        e.writeToParcel(d, 0);
        call(TX_TOUCH, d, true);
    }

    /** True when CarPlay took the key. */
    boolean key(KeyEvent e) {
        // decided here, sent one-way: a busy xcertplay must never hold up the dash's main thread
        if (binder == null || state != STATE_STREAMING || !carPlayKey(e.getKeyCode())) return false;
        Parcel d = token();
        d.writeInt(1);
        e.writeToParcel(d, 0);
        call(TX_KEY, d, true);
        return true;
    }

    /** The keys xcertplay sends on to the phone (knob and media); volume stays with Android. */
    private static boolean carPlayKey(int code) {
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP: case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_LEFT: case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_ENTER: case KeyEvent.KEYCODE_DPAD_CENTER: case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_MEDIA_PLAY: case KeyEvent.KEYCODE_MEDIA_PAUSE: case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_HEADSETHOOK: case KeyEvent.KEYCODE_MEDIA_NEXT: case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
            case KeyEvent.KEYCODE_VOICE_ASSIST: case KeyEvent.KEYCODE_SEARCH:
                return true;
            default:
                return false;
        }
    }

    void start() { call(TX_START, token(), false); }

    void disconnect() { call(TX_DISCONNECT, token(), false); }

    void openSettings() { call(TX_SETTINGS, token(), false); }

    private static Parcel token() {
        Parcel d = Parcel.obtain();
        d.writeInterfaceToken(DESCRIPTOR);
        return d;
    }

    private void call(int txn, Parcel data, boolean oneway) {
        Parcel r = oneway ? null : Parcel.obtain();
        try {
            binderCall(txn, data, r, oneway);
        } finally {
            data.recycle();
            if (r != null) r.recycle();
        }
    }

    /** The reply, past AIDL's exception header; null when the call failed. Caller recycles it. */
    private Parcel callForReply(int txn, Parcel data) {
        Parcel r = Parcel.obtain();
        try {
            if (!binderCall(txn, data, r, false)) { r.recycle(); return null; }
            return r;
        } finally {
            data.recycle();
        }
    }

    /** The one Binder call: only ICarPlayEmbed codes, on xcertplay's service. */
    private boolean binderCall(int txn, Parcel data, Parcel reply, boolean oneway) {
        if (txn < TX_VERSION || txn > TX_SIZES) throw new IllegalArgumentException("not an ICarPlayEmbed call");
        IBinder b = binder;
        if (b == null) return false;
        try {
            if (!b.transact(txn, data, reply, oneway ? IBinder.FLAG_ONEWAY : 0)) return false;
            if (reply != null) reply.readException();
            return true;
        } catch (RemoteException | RuntimeException e) {
            Log.i(TAG, "CarPlay link call " + txn + " failed: " + e);
            return false;
        }
    }
}
