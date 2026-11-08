package app.binnacle;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.Parcel;
import android.util.Log;

/**
 * Subscribes to the stock CAN service in com.xbs.canapp. READ-ONLY, by construction:
 *
 * - The only binder calls this class can make are IMcuService transaction 1 (regPropIdList) and
 *   transaction 2 (unregPropIdList). {@link #call} refuses (logs and drops) anything else.
 * - None of IMcuService's send/write methods are declared or referenced anywhere in the app.
 * - tools/check_readonly.sh fails the build if any car-write API appears in the source, or if
 *   transact() is used outside this class.
 *
 * Nothing is ever sent to the MCU or the vehicle bus.
 *
 * Binder calls run on a worker thread so a slow canapp can never freeze the dash; data arrives on
 * the main thread and is guarded so a bad Bundle can never crash it.
 */
final class CanFeed implements Feed {

    private static final String TAG = "binnacle";
    private static final String DESCRIPTOR = "com.mcu.iinterface.aidl.IMcuService";
    private static final String SVC_ACTION = "com.xbs.canapp.action.mcuservice";
    static final String SVC_PKG = "com.xbs.canapp";
    private static final int TX_REGISTER = 1, TX_UNREGISTER = 2;

    /** Codes we decode (see docs/FIELD_MAP.md). */
    private static final int[] CODES = {2, 3, 4, 6, 9, 10, 11, 12, 15, 16, 18};
    private static final long RETRY_MS = 5000;
    /**
     * A bind can be accepted but never connect (the Unisoc BackgroundCleanHelper refuses binds at
     * boot, as CarPlayLink found): after this long without a connection, bind again (audit #9).
     */
    private static final long CONNECT_WAIT_MS = 10000;

    private final Context ctx;
    private final Sink sink;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final HandlerThread workerThread = new HandlerThread("can-feed");
    private final Handler worker;
    private final Messenger messenger;

    // main thread
    private boolean bound;
    private volatile boolean running;
    // worker thread
    private volatile IBinder binder;   // set on the worker thread, read by connectCheck on main
    private boolean registered;

    CanFeed(Context ctx, Sink sink) {
        this.ctx = ctx.getApplicationContext();
        this.sink = sink;
        workerThread.start();
        worker = new Handler(workerThread.getLooper());
        messenger = new Messenger(new Handler(Looper.getMainLooper()) {
            @Override public void handleMessage(Message msg) {
                if (!running) return;
                try {
                    Bundle b = msg.getData();
                    b.size();   // force unparcel here, inside the guard
                    sink.onData(msg.what, b);
                } catch (Throwable t) {
                    Log.w(TAG, "dropped bad CAN message code " + msg.what, t);
                }
            }
        });
    }

    /** True if the stock CAN service exists on this device (false in the emulator). */
    /**
     * The stock CAN app is installed at all. Only its absence (an emulator, another device) means
     * demo data: a service that doesn't resolve once at boot is retried like any other bind failure,
     * never silently replaced by simulated speed and gear (code audit 5 Nov, D3).
     */
    static boolean installed(Context ctx) {
        try {
            ctx.getPackageManager().getPackageInfo(SVC_PKG, 0);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    static boolean available(Context ctx) {
        try {
            Intent i = new Intent(SVC_ACTION).setPackage(SVC_PKG);
            return ctx.getPackageManager().resolveService(i, 0) != null;
        } catch (Throwable t) {
            return false;
        }
    }

    private final ServiceConnection conn = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName n, final IBinder s) {
            worker.post(new Runnable() {
                @Override public void run() {
                    binder = s;
                    registered = false;
                    register();
                }
            });
        }
        @Override public void onServiceDisconnected(ComponentName n) {
            worker.post(new Runnable() {
                @Override public void run() {
                    binder = null;
                    registered = false;
                }
            });
            status("CAN service disconnected, waiting");
        }
        @Override public void onBindingDied(ComponentName n) {
            status("CAN service stopped, reconnecting");
            unbind();
            scheduleRetry();
        }
    };

    /** Runs on the main thread: rebind if needed, or re-register if bound but not registered. */
    private final Runnable retry = new Runnable() {
        @Override public void run() {
            if (!running) return;
            if (!bound) { bind(); return; }
            worker.post(new Runnable() {
                @Override public void run() { if (running && !registered && binder != null) register(); }
            });
        }
    };

    @Override public void start() {
        if (running) return;
        running = true;
        bind();
    }

    @Override public void stop() {
        running = false;
        main.removeCallbacks(retry);
        main.removeCallbacks(connectCheck);
        worker.post(new Runnable() {
            @Override public void run() {
                unregister();
                binder = null;
                workerThread.quitSafely();
            }
        });
        unbind();
    }

    private void bind() {
        try {
            Intent i = new Intent(SVC_ACTION).setPackage(SVC_PKG);
            bound = ctx.bindService(i, conn, Context.BIND_AUTO_CREATE);
        } catch (Throwable t) {
            Log.w(TAG, "bind failed", t);
            bound = false;
        }
        if (!bound) {
            status("CAN service not found");
            scheduleRetry();
        } else {
            main.removeCallbacks(connectCheck);
            main.postDelayed(connectCheck, CONNECT_WAIT_MS);
        }
    }

    /** Main thread: bound but still no connection after CONNECT_WAIT_MS, so unbind and bind again. */
    private final Runnable connectCheck = new Runnable() {
        @Override public void run() {
            if (!running || !bound || binder != null) return;
            status("CAN service not answering, reconnecting");
            unbind();
            bind();
        }
    };

    private void unbind() {
        if (!bound) return;
        try { ctx.unbindService(conn); } catch (Throwable ignored) {}
        bound = false;
    }

    private void scheduleRetry() {
        main.removeCallbacks(retry);
        if (running) main.postDelayed(retry, RETRY_MS);
    }

    private void status(final String problem) {
        main.post(new Runnable() {
            @Override public void run() { if (running) sink.onStatus(problem); }
        });
    }

    // ---- worker thread only ----

    private void register() {
        Parcel d = Parcel.obtain();
        try {
            d.writeInterfaceToken(DESCRIPTOR);
            d.writeInt(1);
            messenger.writeToParcel(d, 0);
            d.writeIntArray(CODES);
            Parcel r = call(TX_REGISTER, d);
            registered = r != null && r.readInt() != 0;
            if (r != null) r.recycle();
        } catch (Throwable t) {
            Log.w(TAG, "register failed", t);
            registered = false;
        } finally {
            d.recycle();
        }
        status(registered ? null : "CAN register refused, retrying");
        if (!registered) main.post(new Runnable() {
            @Override public void run() { scheduleRetry(); }
        });
    }

    private void unregister() {
        if (!registered) return;
        Parcel d = Parcel.obtain();
        try {
            d.writeInterfaceToken(DESCRIPTOR);
            d.writeInt(1);
            messenger.writeToParcel(d, 0);
            Parcel r = call(TX_UNREGISTER, d);
            if (r != null) r.recycle();
        } catch (Throwable t) {
            Log.w(TAG, "unregister failed", t);
        } finally {
            d.recycle();
            registered = false;
        }
    }

    /** The single binder entry point. Only register / unregister are allowed through; anything else is refused. */
    private Parcel call(int txn, Parcel data) {
        if (txn != TX_REGISTER && txn != TX_UNREGISTER) {
            Log.e(TAG, "read-only feed: refused IMcuService txn " + txn);
            return null;
        }
        IBinder b = binder;
        if (b == null) return null;
        Parcel r = Parcel.obtain();
        try {
            b.transact(txn, data, r, 0);
            r.readException();
            return r;
        } catch (Throwable t) {
            Log.w(TAG, "IMcuService txn " + txn + " failed", t);
            r.recycle();
            return null;
        }
    }
}
