package app.binnacle;

import android.content.Context;
import android.graphics.Color;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

/**
 * CarPlay drawn inside the dash: xcertplay decodes into this view's surface (CarPlayLink), in the
 * centre window or across the whole screen. The dash activity stays in front, so the stock CAN
 * app keeps its door pop-up hidden. Touches go to CarPlay as they are; a line of text covers the
 * view until the picture arrives.
 */
final class CarPlayPane extends FrameLayout implements SurfaceHolder.Callback {
    private final CarPlayLink link;
    private final SurfaceView video;
    private final TextView status;
    private SurfaceHolder holder;
    private int surfaceW, surfaceH;
    private boolean attached;

    CarPlayPane(Context c, CarPlayLink link) {
        super(c);
        this.link = link;
        setBackgroundColor(Color.BLACK);
        video = new SurfaceView(c);
        video.getHolder().addCallback(this);
        addView(video, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        status = new TextView(c);
        status.setTextColor(0xffd0d4da);
        status.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 30f * c.getResources().getDisplayMetrics().heightPixels / Style.DESIGN_H);
        status.setGravity(Gravity.CENTER);
        status.setBackgroundColor(Color.BLACK);
        addView(status, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        video.setOnTouchListener(new OnTouchListener() {
            @Override public boolean onTouch(View v, MotionEvent e) {
                CarPlayPane.this.link.touch(e);
                return true;
            }
        });
        showState(CarPlayLink.STATE_CONNECTING, "");
    }

    /** What the status line says for an xcertplay state; hidden once the picture is drawing. */
    void showState(int state, String stage) {
        String text;
        switch (state) {
            case CarPlayLink.STATE_STREAMING: status.setVisibility(GONE); return;
            case CarPlayLink.STATE_NEEDS_SETUP: text = "CarPlay needs setting up once. Tap to open CarPlay settings."; break;
            case CarPlayLink.STATE_FAILED: text = "CarPlay couldn't connect. Tap to try again."; break;
            case CarPlayLink.STATE_PAUSED: text = "CarPlay settings are open"; break;
            case CarPlayLink.STATE_IDLE: text = "CarPlay is off. Tap to connect."; break;
            default: text = stage == null || stage.isEmpty() ? "Connecting to CarPlay…" : "Connecting to CarPlay…\n" + stage; break;
        }
        status.setText(text);
        status.setVisibility(VISIBLE);
    }

    /** Taps on the status line (no picture yet): set up, retry or connect. */
    void setOnStatusTap(OnClickListener l) { status.setOnClickListener(l); }

    /** The surface goes to xcertplay once its size has settled: a size seen mid-layout would reconnect CarPlay. */
    private static final long SETTLE_MS = 250;
    private final Runnable attachNow = new Runnable() {
        @Override public void run() { reattach(); }
    };

    /** (Re)sends the surface and asks for CarPlay, e.g. after the link came back. */
    void reattach() {
        removeCallbacks(attachNow);
        if (holder == null || surfaceW <= 0 || surfaceH <= 0 || !link.connected()) return;
        link.attach(holder.getSurface(), surfaceW, surfaceH);
        attached = true;
        link.start();       // no-op when CarPlay is already running
    }

    @Override public void surfaceCreated(SurfaceHolder h) { holder = h; }

    @Override public void surfaceChanged(SurfaceHolder h, int format, int w, int height) {
        holder = h;
        surfaceW = w;
        surfaceH = height;
        removeCallbacks(attachNow);
        postDelayed(attachNow, SETTLE_MS);
    }

    @Override public void surfaceDestroyed(SurfaceHolder h) {
        removeCallbacks(attachNow);
        // returns once xcertplay's decoder has let go, before the surface goes
        if (attached) link.detach();
        attached = false;
        holder = null;
        surfaceW = surfaceH = 0;
    }
}
