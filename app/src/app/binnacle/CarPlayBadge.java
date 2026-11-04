package app.binnacle;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

/**
 * The icon on CarPlay's status screen: a round badge in the dash's panel colour with a phone in
 * it. Connecting adds Wi-Fi waves and an accent arc turning round the badge; off is the phone
 * alone; couldn't connect adds a warning dot; setup and settings show a gear. Drawn in design px
 * (the badge is 132 across) and scaled to the view, colours read from the theme on every draw.
 */
final class CarPlayBadge extends View {
    static final int CONNECTING = 0, OFF = 1, FAILED = 2, SETTINGS = 3;
    static final float SIZE = 132f;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF box = new RectF();
    private int kind = CONNECTING;
    private long start = System.currentTimeMillis();

    CarPlayBadge(Context c) {
        super(c);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeJoin(Paint.Join.ROUND);
    }

    void setKind(int k) {
        if (k == kind) return;
        kind = k;
        start = System.currentTimeMillis();
        invalidate();
    }

    @Override protected void onDraw(Canvas c) {
        float s = Math.min(getWidth(), getHeight()) / SIZE;
        c.save();
        c.scale(s, s);
        float cx = SIZE / 2f, cy = SIZE / 2f, r = SIZE / 2f - 6f;

        // badge
        fill.setColor(Style.PANEL2);
        c.drawCircle(cx, cy, r, fill);
        stroke.setColor(Style.LINE);
        stroke.setStrokeWidth(2f);
        c.drawCircle(cx, cy, r, stroke);

        // ring: a turning accent arc while connecting
        if (kind == CONNECTING) {
            float t = (System.currentTimeMillis() - start) % 1400L / 1400f;
            stroke.setColor(Style.ACCENT);
            stroke.setStrokeWidth(5f);
            box.set(cx - r, cy - r, cx + r, cy + r);
            c.drawArc(box, t * 360f - 90f, 80f, false, stroke);
        }

        int glyph = kind == OFF ? Style.MUTED : Style.FG;
        if (kind == SETTINGS) {
            gear(c, cx, cy, glyph);
        } else {
            float px = kind == CONNECTING ? cx - 9f : cx;      // room for the waves
            phone(c, px, cy, glyph);
            if (kind == CONNECTING) waves(c, px + 22f, cy - 20f, Style.ACCENT);
            if (kind == FAILED) {
                fill.setColor(Style.WARN);
                c.drawCircle(px + 19f, cy - 26f, 12f, fill);
                stroke.setColor(Style.PANEL2);
                stroke.setStrokeWidth(3.5f);
                c.drawLine(px + 19f, cy - 32f, px + 19f, cy - 25f, stroke);
                c.drawPoint(px + 19f, cy - 19.5f, stroke);
            }
        }
        c.restore();
        if (kind == CONNECTING && isShown()) postInvalidateOnAnimation();
    }

    /** Phone outline, 34 x 58, with a home bar. */
    private void phone(Canvas c, float cx, float cy, int col) {
        stroke.setColor(col);
        stroke.setStrokeWidth(4f);
        box.set(cx - 17f, cy - 29f, cx + 17f, cy + 29f);
        c.drawRoundRect(box, 7f, 7f, stroke);
        stroke.setStrokeWidth(3f);
        c.drawLine(cx - 6f, cy + 21f, cx + 6f, cy + 21f, stroke);
    }

    /** Two Wi-Fi arcs opening up and to the right of (x, y). */
    private void waves(Canvas c, float x, float y, int col) {
        stroke.setColor(col);
        stroke.setStrokeWidth(4f);
        for (int i = 1; i <= 2; i++) {
            float rr = 9f * i;
            box.set(x - rr, y - rr, x + rr, y + rr);
            c.drawArc(box, -80f, 70f, false, stroke);
        }
        fill.setColor(col);
        c.drawCircle(x + 1.5f, y - 1.5f, 3f, fill);
    }

    /** Gear: eight teeth round a ring. */
    private void gear(Canvas c, float cx, float cy, int col) {
        stroke.setColor(col);
        stroke.setStrokeWidth(4f);
        c.drawCircle(cx, cy, 20f, stroke);
        c.drawCircle(cx, cy, 8f, stroke);
        stroke.setStrokeWidth(8f);
        stroke.setStrokeCap(Paint.Cap.BUTT);
        for (int i = 0; i < 8; i++) {
            double a = Math.PI / 4 * i;
            float cos = (float) Math.cos(a), sin = (float) Math.sin(a);
            c.drawLine(cx + cos * 22f, cy + sin * 22f, cx + cos * 30f, cy + sin * 30f, stroke);
        }
        stroke.setStrokeCap(Paint.Cap.ROUND);
    }
}
