package app.binnacle;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;

/** Small own-drawn telltale icons, ~30 design px, centred on (cx, cy). */
final class Icons {
    private static final RectF R = new RectF();

    private Icons() {}

    /** Headlight: a "D" lamp with beams; high beam = horizontal beams, low beam = dipped. */
    static void headlight(Canvas c, float cx, float cy, int col, boolean high, Path p, Paint fill, Paint stroke) {
        stroke.setColor(col);
        stroke.setStrokeWidth(3);
        p.reset();
        p.moveTo(cx + 2, cy - 10);
        p.cubicTo(cx + 16, cy - 10, cx + 16, cy + 10, cx + 2, cy + 10);
        p.close();
        c.drawPath(p, stroke);
        for (int i = -1; i <= 1; i++) {
            float y = cy + i * 7;
            c.drawLine(cx - 14, high ? y : y + 4, cx - 4, y, stroke);
        }
    }

    /**
     * Fog light: a lamp with beams crossed by a wavy line. Front fog faces left (beams on the
     * left, like the headlight icons); rear fog faces right.
     */
    static void fog(Canvas c, float cx, float cy, int col, boolean front, Path p, Paint stroke) {
        stroke.setColor(col);
        stroke.setStrokeWidth(3);
        float d = front ? 1 : -1;   // +1: lamp on the right, beams to the left
        p.reset();
        p.moveTo(cx + d * 2, cy - 10);
        p.cubicTo(cx + d * 16, cy - 10, cx + d * 16, cy + 10, cx + d * 2, cy + 10);
        p.close();
        c.drawPath(p, stroke);
        for (int i = -1; i <= 1; i++) {
            float y = cy + i * 7;
            c.drawLine(cx - d * 14, y, cx - d * 4, y, stroke);
        }
        stroke.setStrokeWidth(2);
        p.reset();
        float wx = cx - d * 9;
        p.moveTo(wx, cy - 13);
        p.quadTo(wx - 3, cy - 6, wx, cy);
        p.quadTo(wx + 3, cy + 6, wx, cy + 13);
        c.drawPath(p, stroke);
        stroke.setStrokeWidth(3);
    }

    /** Auto Hold: "H" in a circle. */
    static void hold(Canvas c, float cx, float cy, int col, Paint fill, Paint stroke) {
        stroke.setColor(col);
        stroke.setStrokeWidth(3);
        c.drawCircle(cx, cy, 13, stroke);
        c.drawLine(cx - 5, cy - 7, cx - 5, cy + 7, stroke);
        c.drawLine(cx + 5, cy - 7, cx + 5, cy + 7, stroke);
        c.drawLine(cx - 5, cy, cx + 5, cy, stroke);
    }

    /** Seatbelt warning: seated figure with a diagonal belt. */
    static void seatbelt(Canvas c, float cx, float cy, int col, Path p, Paint fill, Paint stroke) {
        fill.setColor(col);
        stroke.setColor(col);
        stroke.setStrokeWidth(3);
        c.drawCircle(cx, cy - 9, 4, fill);
        p.reset();
        p.moveTo(cx - 8, cy + 13);
        p.lineTo(cx - 7, cy - 1);
        p.quadTo(cx, cy - 5, cx + 7, cy - 1);
        p.lineTo(cx + 8, cy + 13);
        c.drawPath(p, stroke);
        c.drawLine(cx - 6, cy - 2, cx + 6, cy + 10, stroke);
    }

    /** Autopilot / driver-assist: steering wheel. */
    static void autopilot(Canvas c, float cx, float cy, int col, Paint stroke) {
        stroke.setColor(col);
        stroke.setStrokeWidth(3);
        c.drawCircle(cx, cy, 12, stroke);
        c.drawLine(cx - 12, cy, cx + 12, cy, stroke);
        c.drawLine(cx, cy, cx, cy + 12, stroke);
    }

    /** Warning triangle with "!". */
    static void warning(Canvas c, float cx, float cy, int col, Path p, Paint fill, Paint stroke) {
        stroke.setColor(col);
        stroke.setStrokeWidth(3);
        p.reset();
        p.moveTo(cx, cy - 12);
        p.lineTo(cx + 13, cy + 11);
        p.lineTo(cx - 13, cy + 11);
        p.close();
        c.drawPath(p, stroke);
        c.drawLine(cx, cy - 4, cx, cy + 3, stroke);
        fill.setColor(col);
        c.drawCircle(cx, cy + 7.5f, 1.8f, fill);
    }

    /** Push pin, head up and needle down. */
    static void pin(Canvas c, float cx, float cy, int col, Path p, Paint fill, Paint stroke) {
        stroke.setColor(col);
        p.reset();
        p.moveTo(cx - 5, cy - 10);
        p.lineTo(cx + 5, cy - 10);
        p.lineTo(cx + 4, cy - 2);
        p.lineTo(cx + 8, cy + 2);
        p.lineTo(cx - 8, cy + 2);
        p.lineTo(cx - 4, cy - 2);
        p.close();
        fill.setColor(col);
        c.drawPath(p, fill);
        c.drawPath(p, stroke);
        c.drawLine(cx, cy + 2, cx, cy + 11, stroke);
    }

    /** Lightning bolt (charging). */
    static void bolt(Canvas c, float cx, float cy, int col, Path p, Paint fill) {
        fill.setColor(col);
        p.reset();
        p.moveTo(cx + 3, cy - 13);
        p.lineTo(cx - 8, cy + 2);
        p.lineTo(cx - 1, cy + 2);
        p.lineTo(cx - 3, cy + 13);
        p.lineTo(cx + 8, cy - 2);
        p.lineTo(cx + 1, cy - 2);
        p.close();
        c.drawPath(p, fill);
    }

    /** Simple top-down car outline for the TPMS tile when no render is available. */
    static void carTop(Canvas c, float cx, float cy, float w, float h, Paint stroke) {
        R.set(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2);
        c.drawRoundRect(R, w * 0.4f, w * 0.35f, stroke);
        R.set(cx - w * 0.36f, cy - h * 0.2f, cx + w * 0.36f, cy + h * 0.22f);
        c.drawRoundRect(R, w * 0.2f, w * 0.2f, stroke);
    }
}
