package app.binnacle;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Matrix;
import android.graphics.Outline;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Log;
import android.util.Range;
import android.util.Size;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewOutlineProvider;

import java.util.Collections;

/**
 * EXPERIMENTAL in-dash forward camera (Dash settings -> "Camera in the dash", off by default).
 * Opens the first Camera2 device (what the stock com.xbs.frontcam opens) into the centre window
 * with the auto-exposure frame rate LOCKED at 30 fps (the stock app leaves it at [5,30], so it can
 * drop in the dark). Frames go through FramePacer, which shows each one for exactly two refreshes
 * of the unit's 56 Hz panel; 30 fps drawn straight onto 56 Hz is what made the stock camera judder.
 * It does not select the video input: stock frontcam writes /sys/ziqi/camera/channel before
 * opening, which needs system rights, so this shows whatever input the driver last selected
 * (normally the forward camera, since frontcam set it). Local camera only; nothing reaches the car.
 *
 * Threading: all camera objects live on one long-lived "dash-camera" thread. The main thread only
 * posts start/stop with a generation number; any callback from an older generation closes what
 * it was handed and is otherwise ignored, so a fast toggle can never leak the camera or kill a
 * newer session.
 */
public class CameraPane extends TextureView implements TextureView.SurfaceTextureListener {

    interface Listener {
        void onCameraProblem(String problem);
        /** Open (or opening) / closed, for the dock's Camera button. */
        void onCameraState(boolean on);
    }

    private static final String TAG = "binnacle";
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private HandlerThread thread;
    private Handler bg;

    // main thread
    private boolean wanted;
    private int fps = 25;
    private int gen;                 // bumped on every start/stop; read on bg via the posted value

    // camera thread only
    private int liveGen = -1;
    private CameraDevice device;
    private CameraCaptureSession session;
    private Surface surface;
    private FramePacer pacer;
    private String cameraId;
    private CameraCharacteristics chars;
    private volatile Size previewSize;
    private volatile int sensorOrientation;
    private int opensInFlight;
    private HandlerThread quitWhenIdle;

    public CameraPane(Context c, Listener l) {
        super(c);
        listener = l;
        setSurfaceTextureListener(this);
        setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), 16f * v.getHeight() / 692f);
            }
        });
        setClipToOutline(true);
    }

    /** Show and start the camera at a locked frame rate. */
    void start(int lockFps) {
        if (wanted && fps == lockFps) return;
        fps = lockFps;
        wanted = true;
        removeCallbacks(reveal);
        animate().cancel();
        setAlpha(HIDDEN);            // revealed once the decoder has settled
        setVisibility(VISIBLE);
        postDelayed(reveal, SETTLE_MS);
        listener.onCameraState(true);
        if (isAvailable()) post(getSurfaceTexture());
    }

    /** Stop the camera and hide. Safe to call repeatedly. */
    void stop() {
        if (!wanted && getVisibility() != VISIBLE) return;
        wanted = false;
        listener.onCameraState(false);
        removeCallbacks(reveal);
        animate().cancel();
        setAlpha(1f);
        setVisibility(GONE);
        final int g = ++gen;
        if (bg != null) bg.post(new Runnable() { @Override public void run() { closeCamera(g); } });
    }

    boolean isRunning() { return wanted; }

    private void post(final SurfaceTexture st) {
        if (st == null) return;
        if (Build.VERSION.SDK_INT >= 23 && getContext().checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            report(gen, "Camera permission not granted");
            return;
        }
        ensureThread();
        final int g = ++gen, f = fps;
        bg.post(new Runnable() { @Override public void run() { openCamera(g, f, st); } });
    }

    private void ensureThread() {
        if (thread != null) return;
        thread = new HandlerThread("dash-camera");
        thread.start();
        bg = new Handler(thread.getLooper());
    }

    @Override protected void onDetachedFromWindow() {
        stop();
        if (bg != null) {
            // keep the thread until any open still in flight has called back and been closed;
            // quitting earlier would drop that callback and leave the camera held
            final HandlerThread t = thread;
            bg.post(new Runnable() {
                @Override public void run() {
                    quitWhenIdle = t;
                    quitIfIdle();
                }
            });
            thread = null;
            bg = null;
        }
        super.onDetachedFromWindow();
    }

    // ---- camera thread ----

    /**
     * Video-input setup the stock frontcam writes every time it opens (com/xbs/frontcam/a.java):
     * input 2 and the camera's signal format, on the unit's camera decoder (TP9950, I2C driver
     * /sys/ziqi/camera, world-writable). Nothing to do with the car or the MCU. Without it, after a
     * reboot in which frontcam never ran, the HAL errors out ("Camera error 4", drive 29 Oct).
     * AHD_1080P_30 = frontcam's saved choice on this unit (camera.xml format 9), confirmed in the car.
     */
    private static final String DECODER_DIR = "/sys/ziqi/camera", INPUT = "2", SIGNAL = "AHD_1080P_30";

    private static void setUpDecoder() {
        writeDecoder("channel", INPUT);
        writeDecoder("N_P_pre_format", SIGNAL);
    }

    private static void writeDecoder(String node, String value) {
        java.io.File file = new java.io.File(DECODER_DIR, node);
        if (!file.canWrite()) return;
        try (java.io.FileWriter w = new java.io.FileWriter(file)) {
            w.write(value);
        } catch (java.io.IOException e) {
            android.util.Log.w("binnacle", "camera decoder " + node + ": " + e);
        }
    }

    private void openCamera(final int g, int f, SurfaceTexture st) {
        closeCamera(g);
        liveGen = g;
        setUpDecoder();
        try {
            CameraManager cm = (CameraManager) getContext().getSystemService(Context.CAMERA_SERVICE);
            if (cameraId == null) {
                String[] ids = cm.getCameraIdList();
                if (ids.length == 0) { report(g, "No camera found"); return; }
                CameraCharacteristics ch = cm.getCameraCharacteristics(ids[0]);
                chars = ch;              // publish the cache only once both are known, so a
                cameraId = ids[0];       // failed lookup is simply retried on the next start
            }
            previewSize = pickSize(chars);
            Integer so = chars.get(CameraCharacteristics.SENSOR_ORIENTATION);
            sensorOrientation = so == null ? 0 : so;
            final Range<Integer> range = pickRange(chars, f);
            st.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());
            pacer = FramePacer.create(st, previewSize);
            surface = pacer != null ? pacer.input() : new Surface(st);   // unpaced if GL setup fails
            main.post(new Runnable() { @Override public void run() { applyTransform(); } });
            final Handler cb = new Handler(Looper.myLooper());
            opensInFlight++;
            try {
            cm.openCamera(cameraId, new CameraDevice.StateCallback() {
                private boolean first = true;
                private void settled() { if (first) { first = false; opensInFlight--; quitIfIdle(); } }
                @Override public void onOpened(CameraDevice d) {
                    if (g != liveGen || quitWhenIdle != null) { d.close(); settled(); return; }   // stopped meanwhile
                    device = d;
                    settled();
                    startSession(g, range);
                }
                @Override public void onDisconnected(CameraDevice d) {
                    d.close();
                    settled();
                    if (g == liveGen) { device = null; report(g, "Camera taken by another app"); }
                }
                @Override public void onError(CameraDevice d, int error) {
                    d.close();
                    settled();
                    if (g == liveGen) { device = null; report(g, "Camera error " + error); }
                }
            }, cb);
            } catch (Throwable e) {
                opensInFlight--;              // no callback will come for a call that threw
                quitIfIdle();
                throw e;
            }
        } catch (Throwable e) {
            report(g, "Camera unavailable: " + e.getMessage());
        }
    }

    private void startSession(final int g, final Range<Integer> range) {
        try {
            final CameraDevice d = device;
            final Surface s = surface;
            if (d == null || s == null) return;
            d.createCaptureSession(Collections.singletonList(s), new CameraCaptureSession.StateCallback() {
                @Override public void onConfigured(CameraCaptureSession cs) {
                    if (g != liveGen || device != d) { cs.close(); return; }
                    session = cs;
                    try {
                        CaptureRequest.Builder b = d.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                        b.addTarget(s);
                        if (range != null) b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, range);
                        cs.setRepeatingRequest(b.build(), null, bg);
                        Log.i(TAG, "dash camera " + previewSize + " fps " + range + " sensor " + sensorOrientation);
                    } catch (Throwable e) {
                        report(g, "Camera preview failed: " + e.getMessage());
                    }
                }
                @Override public void onConfigureFailed(CameraCaptureSession cs) {
                    report(g, "Camera session failed");
                }
            }, bg);
        } catch (Throwable e) {
            report(g, "Camera session failed: " + e.getMessage());
        }
    }

    /** Quit the camera thread once detached and no open is still waiting for its callback. */
    private void quitIfIdle() {
        if (quitWhenIdle != null && opensInFlight == 0) {
            closeCamera(liveGen + 1);
            quitWhenIdle.quitSafely();
            quitWhenIdle = null;
        }
    }

    /** Close whatever is open. g only needs to be newer than the live generation. */
    private void closeCamera(int g) {
        if (g > liveGen) liveGen = g;
        try { if (session != null) session.close(); } catch (Throwable ignored) {}
        try { if (device != null) device.close(); } catch (Throwable ignored) {}
        session = null;
        device = null;
        if (pacer != null) { pacer.release(); pacer = null; }   // owns its input surface
        else if (surface != null) surface.release();
        surface = null;
    }

    /** Report a problem from the current generation only (stale callbacks are ignored). */
    private void report(final int g, final String p) {
        Log.w(TAG, "dash camera: " + p);
        main.post(new Runnable() {
            @Override public void run() {
                if (g != gen) return;
                stop();
                listener.onCameraProblem(p);
            }
        });
    }

    // ---- helpers ----

    /** Largest preview size up to 1920x1080 (as the stock app limits it). */
    private static Size pickSize(CameraCharacteristics ch) {
        StreamConfigurationMap map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        Size best = new Size(1280, 720);
        if (map == null) return best;
        long bestArea = 0;
        for (Size s : map.getOutputSizes(SurfaceTexture.class)) {
            long area = (long) s.getWidth() * s.getHeight();
            if (s.getWidth() <= 1920 && s.getHeight() <= 1080 && area > bestArea) { best = s; bestArea = area; }
        }
        return best;
    }

    /** A fixed [fps, fps] range if offered, else the fixed range closest to it, else none. */
    static Range<Integer> pickRange(CameraCharacteristics ch, int fps) {
        Range<Integer>[] ranges = ch.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
        if (ranges == null) return null;
        Range<Integer> best = null;
        for (Range<Integer> r : ranges) {
            if (!r.getLower().equals(r.getUpper())) continue;
            if (best == null || Math.abs(r.getLower() - fps) < Math.abs(best.getLower() - fps)) best = r;
        }
        return best;
    }

    /**
     * Upright, undistorted, centre-cropped preview. The unit's camera reports sensor orientation
     * 270 on a landscape panel, so the buffer arrives turned; this is the stock frontcam's own
     * transform (display rotation 0 minus sensor orientation, rect swap, then a uniform scale to
     * fill), which in the car showed the picture rotated 90 degrees and stretched without it.
     */
    private void applyTransform() {
        Size p = previewSize;
        if (p == null || getWidth() == 0) return;
        float vw = getWidth(), vh = getHeight(), cx = vw / 2, cy = vh / 2;
        int rot = ((360 - sensorOrientation) % 360 + 360) % 360;
        boolean swap = rot == 90 || rot == 270;
        RectF view = new RectF(0, 0, vw, vh);
        RectF buf = swap ? new RectF(0, 0, p.getHeight(), p.getWidth()) : new RectF(0, 0, p.getWidth(), p.getHeight());
        buf.offset(cx - buf.centerX(), cy - buf.centerY());
        Matrix m = new Matrix();
        m.setRectToRect(view, buf, Matrix.ScaleToFit.FILL);
        float scale = Math.max(vh / p.getHeight(), vw / p.getWidth());
        m.postScale(scale, scale, cx, cy);
        m.postRotate(rot, cx, cy);
        setTransform(m);
    }

    @Override public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int h) { if (wanted) post(st); }
    @Override public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int h) { applyTransform(); }

    /** Close on the camera thread first, then release the texture there (not under a live session). */
    @Override public boolean onSurfaceTextureDestroyed(final SurfaceTexture st) {
        final int g = ++gen;
        if (bg == null) return true;
        bg.post(new Runnable() {
            @Override public void run() {
                closeCamera(g);
                st.release();
            }
        });
        return false;
    }

    /**
     * The analogue decoder rolls the picture once while it locks on, just under a second after
     * the camera opens (seen in the car, 29 Oct). Keep the pane (almost) transparent for SETTLE_MS
     * after start(), then fade it in, so the roll is never seen. A plain timer: a hidden
     * TextureView stops reporting frames, so waiting for frames left it hidden for good.
     */
    private static final long SETTLE_MS = 1000, FADE_MS = 150;   // 1 s: the owner, in the car
    /** Not 0, so the view keeps being drawn while hidden. */
    private static final float HIDDEN = 0.01f;
    private final Runnable reveal = new Runnable() {
        @Override public void run() { if (wanted) animate().alpha(1f).setDuration(FADE_MS); }
    };

    /** Set once the dash's camera has shown a frame on this unit (UnitSetup recommends the stock camera off only then). */
    static final String SEEN_KEY = "camera_seen";
    private boolean seenSaved;

    @Override public void onSurfaceTextureUpdated(SurfaceTexture st) {
        if (seenSaved) return;
        seenSaved = true;
        android.content.SharedPreferences p = DashApp.get(getContext()).prefs();
        if (!p.getBoolean(SEEN_KEY, false)) p.edit().putBoolean(SEEN_KEY, true).apply();
    }
}
