package app.binnacle;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.SweepGradient;

/**
 * The speed dial (design 2a, 15 Nov): a split ring with the speed on the left and top (0 to 160
 * km/h, or 0 to 100 mph) and power / regen down the right, with the speed as big digits in the
 * middle. One dial is on screen at a time: the big one in the centre window in Drive and Reverse,
 * the compact one in the left column otherwise (CentreView, LeftPanelView).
 *
 * Angles are design angles: 0 = 12 o'clock, clockwise positive (Canvas angle = design - 90).
 * All sizes are design px. Nothing animates for show: the fills follow the car's speed and power.
 */
final class SpeedDial {

    /** Speed arc ends: 0 at -140, full scale at -10. Power: full at 10, zero at 96, full regen at 140. */
    private static final float SPEED_A0 = -140, SPEED_A1 = -10, POWER_FULL = 10, POWER_ZERO = 96, REGEN_FULL = 140;
    /** Power arc full scale (raw curentergyval units, as the old power bar). */
    private static final float POWER_MAX = 80f, REGEN_MAX = 40f;
    /** Light and dark track colour: the only new colours of the design. */
    private static int track() { return Style.light ? 0xffd9e0e8 : 0xff1a222d; }

    /** Big (centre) or compact (left column). */
    private final boolean big;
    private final float radius, stroke, digitSize, digitBase, unitSize, unitBase;

    private final Paint arc = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tick = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint label = Style.text(Style.BODY, 17, Style.MUTED, Paint.Align.CENTER);
    private final Paint digits = Style.text(Style.DISPLAY, 247, Style.FG, Paint.Align.CENTER);
    private final Paint unit = Style.label(Style.T_UNIT);
    private final Paint est = Style.text(Style.BODY, 16, Style.MUTED, Paint.Align.CENTER);
    private final RectF oval = new RectF();
    private final Matrix matrix = new Matrix();
    private SweepGradient powerShader, rainbowShader;
    private float shaderCx = Float.NaN, shaderCy = Float.NaN;
    private int shaderWarn;

    private SpeedDial(boolean big, float radius, float stroke, float digitSize, float digitBase,
                      float unitSize, float unitBase) {
        this.big = big;
        this.radius = radius;
        this.stroke = stroke;
        this.digitSize = digitSize;
        this.digitBase = digitBase;
        this.unitSize = unitSize;
        this.unitBase = unitBase;
        arc.setStyle(Paint.Style.STROKE);
        arc.setStrokeCap(Paint.Cap.BUTT);
        tick.setStyle(Paint.Style.STROKE);
        digits.setLetterSpacing(-0.02f);
        unit.setTextAlign(Paint.Align.CENTER);
        est.setLetterSpacing(0.16f);
    }

    /** The centre window's dial: R 300, stroke 16, ticks and labels, digits 247. */
    static SpeedDial big() { return new SpeedDial(true, 300, 16, 247, 22, Style.T_UNIT, 74); }

    /** The left column's dial: R 136, stroke 8.8, no ticks or labels, digits 150. */
    static SpeedDial compact() { return new SpeedDial(false, 136, 8.8f, 150, 57, 22, 98); }

    /** How far the dial reaches below its centre, digits and unit included (for layout). */
    float bottomReach() { return unitBase + 8; }

    /**
     * Draws the dial centred at (cx, cy). alpha fades the whole dial (the Park/Drive hand-over);
     * ap is the Autopilot blend (0..1): the speed fill turns Autopilot blue, or the rainbow.
     */
    void draw(Canvas c, DashApp app, CarState car, float cx, float cy, float alpha, float ap) {
        if (alpha <= 0) return;
        int a = Math.round(255 * Math.min(1f, alpha));
        boolean imperial = Units.imperial(app);
        float max = imperial ? 100 : 160, step = 10;
        int speed = car.speed < 0 ? -1 : Units.speed(app, car.speed);
        float t = speed < 0 ? 0 : Math.min(1f, speed / max);
        oval.set(cx - radius, cy - radius, cx + radius, cy + radius);
        arc.setStrokeWidth(stroke);

        // tracks
        arc.setShader(null);
        arc.setColor(track());
        arc.setAlpha(a);
        c.drawArc(oval, SPEED_A0 - 90, SPEED_A1 - SPEED_A0, false, arc);
        c.drawArc(oval, POWER_FULL - 90, REGEN_FULL - POWER_FULL, false, arc);

        // speed fill: accent, Autopilot blue on Autopilot, or the rainbow with Rainbow road on
        if (t > 0) {
            float sweep = (SPEED_A1 - SPEED_A0) * t;
            boolean rainbow = app.rainbowRoad();
            arc.setColor(CentreView.lerpColor(Style.ACCENT, CentreView.AP_BLUE, rainbow ? 0 : ap));
            arc.setAlpha(a);
            c.drawArc(oval, SPEED_A0 - 90, sweep, false, arc);
            if (rainbow && ap > 0) {
                ensureShaders(cx, cy);
                arc.setShader(rainbowShader);
                arc.setAlpha(Math.round(a * ap));
                c.drawArc(oval, SPEED_A0 - 90, sweep, false, arc);
                arc.setShader(null);
            }
        }

        // power (amber, up from zero toward the top) or regen (green, down toward the bottom)
        if (car.power > 0) {
            if (car.regen) {
                arc.setColor(Style.GOOD);
                arc.setAlpha(a);
                float sweep = (REGEN_FULL - POWER_ZERO) * Math.min(1f, car.power / REGEN_MAX);
                c.drawArc(oval, POWER_ZERO - 90, sweep, false, arc);
            } else {
                ensureShaders(cx, cy);
                arc.setShader(powerShader);
                arc.setAlpha(a);
                float sweep = (POWER_ZERO - POWER_FULL) * Math.min(1f, car.power / POWER_MAX);
                c.drawArc(oval, POWER_ZERO - 90 - sweep, sweep, false, arc);
                arc.setShader(null);
            }
        }

        // zero tick across the power arc
        tick.setColor(Style.FG);
        tick.setAlpha(a);
        tick.setStrokeWidth(2);
        radial(c, cx, cy, POWER_ZERO, radius - stroke * 0.75f, radius + stroke * 0.75f);

        if (big) {
            // ticks every 10 on the inner side, labels every 20
            float inner = radius - stroke / 2f - 4;
            Style.size(label, 17);
            for (int v = 0; v <= max; v += step) {
                float ang = SPEED_A0 + (SPEED_A1 - SPEED_A0) * v / max;
                boolean major = v % 20 == 0;
                tick.setColor(speed >= 0 && v <= speed ? Style.MUTED : Style.DIM);
                tick.setAlpha(a);
                tick.setStrokeWidth(major ? 2.5f : 1.5f);
                radial(c, cx, cy, ang, inner - (major ? 12 : 6), inner);
                if (major) {
                    double rad = Math.toRadians(ang);
                    float lr = radius - 42;
                    float lx = cx + (float) Math.sin(rad) * lr, ly = cy - (float) Math.cos(rad) * lr;
                    label.setColor(Style.MUTED);
                    label.setAlpha(a);
                    c.drawText(String.valueOf(v), lx, ly - (label.ascent() + label.descent()) / 2f, label);
                }
            }
        }

        // speed limit: a red mark across the arc at the limit
        if (car.speedLimit > 0) {
            int limit = Units.speedLimit(app, car.speedLimit);
            float ang = SPEED_A0 + (SPEED_A1 - SPEED_A0) * Math.min(1f, limit / max);
            tick.setColor(Style.BAD);
            tick.setAlpha(a);
            tick.setStrokeWidth(big ? 5 : 3.5f);
            float reach = big ? 13 : stroke;
            radial(c, cx, cy, ang, radius - reach, radius + reach);
        }

        // "EST." beside the power side while power is worked out from speed
        if (car.powerEstimated()) {
            float k = big ? 1f : 0.55f;
            Style.size(est, 16 * k);
            est.setColor(Style.MUTED);
            est.setAlpha(a);
            double rad = Math.toRadians((POWER_FULL + POWER_ZERO) / 2f);
            float er = radius + (big ? 46 : 22);
            float ex = cx + (float) Math.sin(rad) * er, ey = cy - (float) Math.cos(rad) * er;
            c.drawText("EST.", ex, ey - (est.ascent() + est.descent()) / 2f, est);
        }

        // digits and unit
        String text = speed < 0 ? "--" : String.valueOf(speed);
        Style.size(digits, digitSize);
        digits.setColor(speed < 0 ? Style.DIM : Style.FG);
        digits.setAlpha(a);
        Style.fit(digits, text, radius * 1.5f);
        c.drawText(text, cx, cy + digitBase, digits);
        Style.size(unit, unitSize);
        unit.setColor(Style.MUTED);
        unit.setAlpha(a);
        c.drawText(Units.speedLabel(app), cx, cy + unitBase, unit);
    }

    /** A radial line at design angle ang from radius r0 to r1. */
    private void radial(Canvas c, float cx, float cy, float ang, float r0, float r1) {
        double rad = Math.toRadians(ang);
        float s = (float) Math.sin(rad), k = (float) -Math.cos(rad);
        c.drawLine(cx + s * r0, cy + k * r0, cx + s * r1, cy + k * r1, tick);
    }

    /**
     * Power: amber at zero brightening to orange at full power (as the old power bar). Rainbow:
     * the Rainbow road's hues round the speed arc. Rebuilt only when the centre or theme changes
     * (a theme change rebuilds the whole activity, and with it this dial).
     */
    private void ensureShaders(float cx, float cy) {
        if (powerShader != null && cx == shaderCx && cy == shaderCy && shaderWarn == Style.WARN) return;
        shaderCx = cx;
        shaderCy = cy;
        shaderWarn = Style.WARN;
        // SweepGradient runs clockwise from 3 o'clock; rotate it so 0 sits at full power
        float full = POWER_FULL - 90, span = (POWER_ZERO - POWER_FULL) / 360f;
        powerShader = new SweepGradient(cx, cy, new int[]{0xffff8a3d, Style.WARN, Style.WARN},
                new float[]{0f, span, 1f});
        matrix.reset();
        matrix.setRotate(full, cx, cy);
        powerShader.setLocalMatrix(matrix);

        int n = 11;
        int[] cols = new int[n];
        float[] pos = new float[n];
        float[] hsv = {0, CentreView.RAINBOW_SAT, 1f};
        float arcSpan = (SPEED_A1 - SPEED_A0) / 360f;
        for (int i = 0; i < n; i++) {
            float u = i / (n - 1f);
            hsv[0] = (1f - u) * 330f;   // violet at 0 to red at full, as the road runs toward the car
            cols[i] = Color.HSVToColor(hsv);
            pos[i] = u * arcSpan;
        }
        rainbowShader = new SweepGradient(cx, cy, cols, pos);
        matrix.reset();
        matrix.setRotate(SPEED_A0 - 90, cx, cy);
        rainbowShader.setLocalMatrix(matrix);   // setLocalMatrix copies it
    }
}
