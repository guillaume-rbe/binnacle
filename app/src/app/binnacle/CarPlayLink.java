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
    private static final int MSG_STATE = 1, MSG_HOST_UI = 2;

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
                }
            }
        });
    }

    /** Binds xcertplay's service; false if it isn't there or we may not (other signing key). */
    boolean bind() {
        if (bound) return true;
        Intent i = new Intent().setComponent(new ComponentName(PKG, SERVICE));
        try {
            bound = context.bindService(i, this, Context.BIND_AUTO_CREATE);
        } catch (SecurityException e) {
            Log.i(TAG, "CarPlay link not allowed: " + e.getMessage());
            bound = false;
        }
        if (!bound) {
            try { context.unbindService(this); } catch (RuntimeException ignored) { }
        }
        return bound;
    }

    void unbind() {
        if (!bound) return;
        bound = false;
        binder = null;
        try { context.unbindService(this); } catch (RuntimeException ignored) { }
        listener.onLinkChanged(false);
    }

    boolean connected() { return binder != null; }

    int state() { return state; }

    @Override public void onServiceConnected(ComponentName name, IBinder service) {
        binder = service;
        int version = version();
        if (version != API_VERSION) {
            Log.i(TAG, "CarPlay link: xcertplay API " + version + ", need " + API_VERSION);
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
        listener.onLinkChanged(false);
    }

    @Override public void onBindingDied(ComponentName name) {
        unbind();
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
        Parcel d = token();
        d.writeInt(1);
        e.writeToParcel(d, 0);
        Parcel r = callForReply(TX_KEY, d);
        if (r == null) return false;
        try { return r.readInt() != 0; } finally { r.recycle(); }
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
