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
    /** Shown until the picture draws: a spinner while connecting, a title and a hint line. */
    private final FrameLayout status;
    private final TextView statusTitle, statusDetail;
    private final CarPlayBadge statusIcon;
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
        cover = buildCover(c);
        tintCover();
        addView(cover, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        float px = c.getResources().getDisplayMetrics().heightPixels / Style.DESIGN_H;   // per design px
        status = new FrameLayout(c);
        android.widget.LinearLayout col = new android.widget.LinearLayout(c);
        col.setOrientation(android.widget.LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        statusIcon = new CarPlayBadge(c);
        int icon = Math.round(CarPlayBadge.SIZE * px);
        android.widget.LinearLayout.LayoutParams ip = new android.widget.LinearLayout.LayoutParams(icon, icon);
        ip.bottomMargin = Math.round(24f * px);
        col.addView(statusIcon, ip);
        statusTitle = new TextView(c);
        statusTitle.setTypeface(Style.BODY_BOLD);
        statusTitle.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 30f * px);
        statusTitle.setGravity(Gravity.CENTER);
        col.addView(statusTitle);
        statusDetail = new TextView(c);
        statusDetail.setTypeface(Style.BODY);
        statusDetail.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 21f * px);
        statusDetail.setGravity(Gravity.CENTER);
        android.widget.LinearLayout.LayoutParams dp = new android.widget.LinearLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        dp.topMargin = Math.round(10f * px);
        col.addView(statusDetail, dp);
        status.addView(col, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        addView(status, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        corners = new CornerMask(c);
        addView(corners, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        showState(CarPlayLink.STATE_CONNECTING, "");
    }

    private final CornerMask corners;

    /** Rounded like the centre window (on), or square edge to edge for full screen (off). */
    void setRounded(boolean rounded) {
        if (this.rounded == rounded) return;
        this.rounded = rounded;
        corners.setVisibility(rounded ? VISIBLE : GONE);
        requestLayout();                 // the video is stretched to the window only when rounded
    }

    private boolean rounded = true;

    /*
     * The iPhone (iOS 26) draws each CarPlay screen as a rounded card over its wallpaper, and in
     * the centre window that card stops short of the right and bottom edges (car screenshots,
     * 6 Nov, Now Playing, Maps, Waze: card 1203 x 684 in the 1210 x 690 window, flush at the left
     * and top). So the wallpaper showed as a strip on those two edges and at the corners, inside
     * our own smaller rounded corner. In the centre window the video is stretched by that much
     * (under 1 %) so the card fills it, and our corners take the card's radius (a circle of 68 px
     * at 690 high covers its continuous curve). Touch is scaled back the same way.
     */
    private static final float CARD_GAP_RIGHT = 7f, CARD_GAP_BOTTOM = 6f, CARD_RADIUS = 68f, CARD_REF_H = 690f;

    /** How much the video is stretched across and down; 1 when not rounded. */
    private float stretchX = 1f, stretchY = 1f;

    private void updateStretch(int w, int h) {
        if (!rounded || w <= 0 || h <= 0) {
            stretchX = stretchY = 1f;
            return;
        }
        float k = h / CARD_REF_H;
        stretchX = w / Math.max(1f, w - CARD_GAP_RIGHT * k);
        stretchY = h / Math.max(1f, h - CARD_GAP_BOTTOM * k);
    }

    /**
     * The centre window's rounded corners over the video. A SurfaceView ignores clipping to an
     * outline on Android 10, so the corners are painted over it in the dash's background, with
     * the window's edge line, at the radius of the iPhone's own card (see CARD_RADIUS).
     */
    private static final class CornerMask extends View {
        private final android.graphics.Paint fill = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Paint edge = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Path path = new android.graphics.Path();
        private final android.graphics.RectF box = new android.graphics.RectF();

        CornerMask(Context c) {
            super(c);
            edge.setStyle(android.graphics.Paint.Style.STROKE);
        }

        @Override protected void onDraw(android.graphics.Canvas canvas) {
            int w = getWidth(), h = getHeight();
            if (w <= 0 || h <= 0) return;
            fill.setColor(Style.BG);            // read each time: the theme can change
            edge.setColor(Style.LINE);
            float r = CARD_RADIUS * h / CARD_REF_H, stroke = Math.max(1f, 1.5f * h / 692f);
            box.set(0, 0, w, h);
            path.reset();
            path.addRoundRect(box, r, r, android.graphics.Path.Direction.CW);
            path.setFillType(android.graphics.Path.FillType.INVERSE_WINDING);
            canvas.drawPath(path, fill);            // only the four corners outside the rounded rect
            edge.setStrokeWidth(stroke);
            box.inset(stroke / 2, stroke / 2);
            canvas.drawRoundRect(box, r, r, edge);
        }
    }

    /** Hands the surface over once the layout has settled, e.g. when the pane is shown again. */
    void requestAttach() {
        removeCallbacks(attachNow);
        postDelayed(attachNow, SETTLE_MS);
    }

    /** Video view placement from xcertplay: left, top, width, height in this view; empty = fill. */
    private int[] videoLayout = new int[0];

    void setVideoLayout(int[] v) {
        if (!java.util.Arrays.equals(v, videoLayout)) {
            videoLayout = v;
            requestLayout();
        }
        // xcertplay has switched the view area: the iPhone now redraws at the new size
        if (cover.getVisibility() == VISIBLE) {
            removeCallbacks(uncover);
            postDelayed(uncover, REDRAW_MS);
        }
    }

    /**
     * Centre ↔ full screen: the view changes size at once, but the iPhone takes a moment to blur
     * and redraw at the new size, so the old picture would show stretched and then snap. A cover
     * in the dash's theme colour, with a spinner, hides that until the new size is drawing.
     */
    private static final long REDRAW_MS = 600, AFTER_ATTACH_MS = 900, COVER_MAX_MS = 1800, FADE_MS = 180;
    private View cover;
    private final Runnable uncover = new Runnable() {
        @Override public void run() {
            removeCallbacks(this);
            cover.animate().alpha(0f).setDuration(FADE_MS).withEndAction(new Runnable() {
                @Override public void run() { cover.setVisibility(GONE); }
            }).start();
        }
    };

    void coverForResize() {
        if (status.getVisibility() == VISIBLE) return;   // no picture yet: nothing to hide
        cover.animate().cancel();
        tintCover();                                     // the theme can change while CarPlay runs
        cover.setAlpha(1f);
        cover.setVisibility(VISIBLE);
        removeCallbacks(uncover);
        postDelayed(uncover, COVER_MAX_MS);              // in case no confirmation comes
    }

    private android.widget.ProgressBar spinner;

    /** The cover takes the dash's own screen colour and accent, so it isn't black on a light dash. */
    private void tintCover() {
        cover.setBackgroundColor(Style.SCREEN);
        spinner.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(Style.ACCENT));
    }

    private View buildCover(Context c) {
        FrameLayout f = new FrameLayout(c);
        spinner = new android.widget.ProgressBar(c);
        spinner.setIndeterminate(true);
        int size = Math.round(48f * c.getResources().getDisplayMetrics().heightPixels / Style.DESIGN_H);
        f.addView(spinner, new LayoutParams(size, size, Gravity.CENTER));
        f.setVisibility(GONE);
        return f;
    }

    /**
     * The decoder draws the whole stream, which spans every declared size; for a smaller one the
     * video view is made larger and this view clips it, so only the active area shows.
     */
    @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int w = r - l, h = b - t;
        status.layout(0, 0, w, h);
        cover.layout(0, 0, w, h);
        corners.layout(0, 0, w, h);
        updateStretch(w, h);
        int vl = 0, vt = 0, vw = w, vh = h;
        if (videoLayout.length == 4 && videoLayout[2] > 0 && videoLayout[3] > 0) {
            vl = videoLayout[0]; vt = videoLayout[1]; vw = videoLayout[2]; vh = videoLayout[3];
        }
        // stretched from the top left, where the iPhone's card sits flush
        vl = Math.round(vl * stretchX); vt = Math.round(vt * stretchY);
        vw = Math.round(vw * stretchX); vh = Math.round(vh * stretchY);
        video.measure(MeasureSpec.makeMeasureSpec(vw, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(vh, MeasureSpec.EXACTLY));
        video.layout(vl, vt, vl + vw, vt + vh);
    }

    /** Centre ↔ full screen: a new CarPlay size, even when the (enlarged) video view keeps its size. */
    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        requestAttach();
    }

    /** Touches go to CarPlay in this view's coordinates (what xcertplay maps), unless the status line is up. */
    @Override public boolean onInterceptTouchEvent(MotionEvent e) {
        return status.getVisibility() != VISIBLE;
    }

    /** A gesture whose start went to CarPlay: its end goes too, so no finger stays down on the phone. */
    private boolean gestureSent;

    @Override public boolean onTouchEvent(MotionEvent e) {
        int action = e.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) gestureSent = false;
        if (status.getVisibility() == VISIBLE && !gestureSent) return super.onTouchEvent(e);
        if (status.getVisibility() == VISIBLE) {
            // the status cover came up mid-gesture: end the gesture on the phone, send nothing more
            MotionEvent cancel = MotionEvent.obtain(e);
            cancel.setAction(MotionEvent.ACTION_CANCEL);
            sendTouch(cancel);
            cancel.recycle();
            gestureSent = false;
            return true;
        }
        sendTouch(e);
        gestureSent = action != MotionEvent.ACTION_UP && action != MotionEvent.ACTION_CANCEL;
        return true;
    }

    /** To CarPlay in this view's coordinates, undoing the video's stretch so a tap lands where it shows. */
    private void sendTouch(MotionEvent e) {
        if (stretchX == 1f && stretchY == 1f) {
            link.touch(e);
            return;
        }
        MotionEvent scaled = MotionEvent.obtain(e);
        android.graphics.Matrix m = new android.graphics.Matrix();
        m.setScale(1f / stretchX, 1f / stretchY);
        scaled.transform(m);
        link.touch(scaled);
        scaled.recycle();
    }

    /** What the status line says for an xcertplay state; hidden once the picture is drawing. */
    void showState(int state, String stage) {
        String title, detail;
        int icon;
        switch (state) {
            case CarPlayLink.STATE_STREAMING: status.setVisibility(GONE); return;
            case CarPlayLink.STATE_NEEDS_SETUP: title = "CarPlay needs setting up once"; detail = "Tap to open CarPlay settings"; icon = CarPlayBadge.SETTINGS; break;
            case CarPlayLink.STATE_FAILED: title = "CarPlay couldn't connect"; detail = "Tap to try again"; icon = CarPlayBadge.FAILED; break;
            case CarPlayLink.STATE_PAUSED: title = "CarPlay settings are open"; detail = ""; icon = CarPlayBadge.SETTINGS; break;
            case CarPlayLink.STATE_IDLE: title = "CarPlay is off"; detail = "Tap to connect"; icon = CarPlayBadge.OFF; break;
            default: title = "Connecting to CarPlay"; detail = stage == null ? "" : stage; icon = CarPlayBadge.CONNECTING; break;
        }
        // read each time: the theme can change while the dash runs
        status.setBackgroundColor(Style.SCREEN);
        statusTitle.setTextColor(Style.FG);
        statusDetail.setTextColor(Style.MUTED);
        statusIcon.setKind(icon);
        statusIcon.invalidate();
        statusTitle.setText(title);
        statusDetail.setText(detail);
        statusDetail.setVisibility(detail.isEmpty() ? GONE : VISIBLE);
        status.setVisibility(VISIBLE);
    }

    /** Taps on the status line (no picture yet): set up, retry or connect. */
    void setOnStatusTap(OnClickListener l) {
        status.setOnClickListener(l);
        // touch only: a clickable view is focusable by default, and the wheel's OK would click it
        status.setFocusable(false);
        status.setFocusableInTouchMode(false);
    }

    /** The surface goes to xcertplay once its size has settled: a size seen mid-layout would reconnect CarPlay. */
    private static final long SETTLE_MS = 250;
    private final Runnable attachNow = new Runnable() {
        @Override public void run() { reattach(); }
    };

    /** (Re)sends the surface and asks for CarPlay, e.g. after the link came back. */
    void reattach() {
        removeCallbacks(attachNow);
        // only while CarPlay is on screen: a re-send queued just before the pane closed (End CarPlay,
        // car sleep) attached again and started a new session 0.3 s after the old one was ended
        // (car, 5 Nov: CarPlay kept reconnecting and stopped home Wi-Fi joining)
        if (!isShown()) return;
        // the size is this view's (the area CarPlay lays out for), not the video view's, which can be larger
        if (holder == null || surfaceW <= 0 || surfaceH <= 0 || getWidth() <= 0 || getHeight() <= 0 || !link.connected()) return;
        link.attach(holder.getSurface(), getWidth(), getHeight());
        // the view area switches now; the iPhone takes about this long to blur and redraw
        if (cover.getVisibility() == VISIBLE) {
            removeCallbacks(uncover);
            postDelayed(uncover, AFTER_ATTACH_MS);
        }
        attached = true;
        // no-op when CarPlay is already running. Not after xcertplay gave up retrying: each new
        // state relays out the video view, and starting from here made that a reconnect loop
        // (car, 5 Nov: "Manual reconnect" every few seconds); the status tap retries instead.
        if (link.state() != CarPlayLink.STATE_FAILED) link.start();
    }

    /** Before the link goes (activity destroyed): xcertplay lets go of the surface now. */
    void release() {
        removeCallbacks(attachNow);
        if (attached) link.detach();
        attached = false;
    }

    @Override public void surfaceCreated(SurfaceHolder h) { holder = h; }

    @Override public void surfaceChanged(SurfaceHolder h, int format, int w, int height) {
        holder = h;
        surfaceW = w;
        surfaceH = height;
        requestAttach();
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
