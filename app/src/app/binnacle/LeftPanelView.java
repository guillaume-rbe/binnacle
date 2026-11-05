package app.binnacle;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;

/**
 * Left panel: status lights, big speed, PRND, indicators, speed-limit sign, power/regen bar.
 * Drawn in 340x720 design pixels (mockup's .side.l).
 */
public class LeftPanelView extends View {

    /** Power bar full scale (raw curentergyval units; the capture saw ~0-60). */
    private static final float POWER_MAX = 80f, REGEN_MAX = 40f, ZERO_AT = 0.38f;

    private final CarState car;
    private final Paint line = Style.fill(Style.LINE);
    private final Paint speedP = Style.text(Style.DISPLAY, Style.T_SPEED, Style.FG, Paint.Align.CENTER);
    private final Paint unitP = Style.label(Style.T_UNIT);
    private final Paint gearP = Style.text(Style.DISPLAY, Style.T_GEAR, Style.DIM, Paint.Align.CENTER);
    private final Paint limitNum = Style.text(Style.DISPLAY, Style.T_LIMIT, 0xff111111, Paint.Align.CENTER);
    private final Paint white = Style.fill(0xffffffff);
    private final Paint red = Style.stroke(Style.BAD, 8);
    private final Paint iconFill = Style.fill(Style.DIM);
    private final Paint iconStroke = Style.stroke(Style.DIM, 3);
    private final Paint track = Style.fill(Style.PANEL2);
    private final Paint trackEdge = Style.stroke(Style.LINE, 1);
    private final Paint bar = Style.fill(Style.WARN);
    private final Paint scaleP = Style.text(Style.BODY, Style.T_SCALE, Style.DIM, Paint.Align.LEFT);
    private final Paint powerLbl = Style.label(Style.T_LABEL);
    private final Paint zeroP = Style.fill(Style.DIM);
    private Shader powerShader;
    private final Path path = new Path();
    private final RectF r = new RectF();

    public LeftPanelView(Context c, CarState car) {
        super(c);
        this.car = car;
        unitP.setTextAlign(Paint.Align.CENTER);
    }

    /** Tapped on the power bar (the hidden screenshot button, when it is switched on). */
    private Runnable onPowerTap;
    void setOnPowerTap(Runnable r) { onPowerTap = r; }

    /** The power bar with its label and scale (design px): an easy target while driving. */
    private static final float TAP_TOP = 612, TAP_BOTTOM = 712;
    private boolean powerTouch;

    @Override public boolean onTouchEvent(android.view.MotionEvent e) {
        if (onPowerTap == null) return false;
        float y = e.getY() / (getHeight() / Style.DESIGN_H);
        switch (e.getActionMasked()) {
            case android.view.MotionEvent.ACTION_DOWN:
                powerTouch = y >= TAP_TOP && y <= TAP_BOTTOM;
                return powerTouch;
            case android.view.MotionEvent.ACTION_UP:
                if (powerTouch && y >= TAP_TOP - 20 && y <= TAP_BOTTOM + 20) onPowerTap.run();
                powerTouch = false;
                return true;
            case android.view.MotionEvent.ACTION_CANCEL:
                powerTouch = false;
                return true;
        }
        return powerTouch;
    }

    @Override protected void onDraw(Canvas c) {
        float s = getHeight() / Style.DESIGN_H;
        c.save();
        c.scale(s, s);
        float w = getWidth() / s, cx = w / 2f;
        c.drawRect(w - 1, 0, w, Style.DESIGN_H, line);

        drawStatusRow(c, cx, 84);

        // speed group, vertically centred between the status row and the power bar
        DashApp app = DashApp.get(getContext());
        String speed = car.speed < 0 ? "--" : String.valueOf(Units.speed(app, car.speed));
        speedP.setColor(car.speed < 0 ? Style.DIM : Style.FG);
        Style.size(speedP, Style.T_SPEED);
        Style.fit(speedP, speed, w - 40);
        c.drawText(speed, cx, 296, speedP);
        Style.size(unitP, Style.T_UNIT);
        c.drawText(Units.speedLabel(app), cx, 330, unitP);
        drawGears(c, cx, 398);
        drawTurn(c, cx - 52, 438, true, car.turnLeft);
        drawTurn(c, cx + 52, 438, false, car.turnRight);
        if (car.speedLimit > 0) drawLimit(c, cx, 520, Units.speedLimit(app, car.speedLimit));

        drawPower(c, w);
        c.restore();
    }

    /** Telltales that only show while on, in priority order (warnings first). */
    private static final int T_SEATBELT = 0, T_HOLD = 1, T_FRONT_FOG = 2, T_REAR_FOG = 3;
    private final int[] extras = new int[4];

    /**
     * Status row: the five fixed icons (low beam, high beam, Autopilot, warning, charging), then any
     * telltale that is on (seatbelt, Auto Hold, fog) in line with them, centred.
     * Always one row (in-car feedback: a lone wrapped icon looked odd): past 6 icons the spacing
     * tightens and, if still too wide for the panel, the whole row scales down a little (only with
     * fog lights on as well, which this car can't do). No auto high beam icon: in the car its field
     * never changed, even when the car's own toggle did (the owner, 28 Oct).
     */
    private void drawStatusRow(Canvas c, float cx, float cy) {
        int n = 0;
        if (car.seatbelt) extras[n++] = T_SEATBELT;
        if (car.autoHold) extras[n++] = T_HOLD;
        if (car.frontFog) extras[n++] = T_FRONT_FOG;
        if (car.rearFog) extras[n++] = T_REAR_FOG;
        int count = 5 + n;
        float step = count > 5 ? 42 : 46, span = step * (count - 1), maxSpan = 280;
        // centre what is drawn, not the icon centres: the first icon (low beam) reaches further
        // left than the last one reaches right (in-car feedback: the row sat a little left)
        float right = n > 0 ? reachRight(extras[n - 1]) : 9.5f;   // bolt
        float k = span > maxSpan ? maxSpan / span : 1f, x = cx - span / 2f + (LOW_BEAM_REACH_LEFT - right) / 2f;
        c.save();
        c.scale(k, k, cx, cy);
        Icons.headlight(c, x, cy, car.lowBeam ? Style.GOOD : Style.DIM, false, path, iconFill, iconStroke);
        Icons.headlight(c, x + step, cy, car.highBeam ? Style.ACCENT : Style.DIM, true, path, iconFill, iconStroke);
        Icons.autopilot(c, x + step * 2, cy, car.autopilot ? Style.ACCENT : Style.DIM, iconStroke);
        Icons.warning(c, x + step * 3, cy, car.driverWarning != 0 ? Style.BAD : Style.DIM, path, iconFill, iconStroke);
        Icons.bolt(c, x + step * 4, cy, car.charging ? Style.GOOD : Style.DIM, path, iconFill);
        for (int i = 0; i < n; i++) drawExtra(c, extras[i], x + step * (5 + i), cy);
        c.restore();
    }

    /** How far the low-beam icon's lines reach left of its centre (incl. half the stroke). */
    private static final float LOW_BEAM_REACH_LEFT = 15.5f;

    /** How far each telltale reaches right of its centre (incl. half the stroke), see Icons. */
    private static float reachRight(int which) {
        switch (which) {
            case T_SEATBELT: return 9.5f;
            case T_HOLD: return 14.5f;
            case T_FRONT_FOG: return 14f;
            default: return 15.5f;   // rear fog: its lines point right
        }
    }

    private void drawExtra(Canvas c, int which, float x, float y) {
        switch (which) {
            case T_SEATBELT: Icons.seatbelt(c, x, y, Style.BAD, path, iconFill, iconStroke); break;
            case T_HOLD: Icons.hold(c, x, y, Style.GOOD, iconFill, iconStroke); break;
            case T_FRONT_FOG: Icons.fog(c, x, y, Style.GOOD, true, path, iconStroke); break;
            default: Icons.fog(c, x, y, Style.WARN, false, path, iconStroke); break;
        }
    }

    private void drawGears(Canvas c, float cx, float baseline) {
        char[] g = {'R', 'P', 'N', 'D'};   // R on the left (the owner, 30 Oct)
        float step = 44, x = cx - step * 1.5f;
        Style.size(gearP, Style.T_GEAR);
        for (int i = 0; i < g.length; i++) {
            gearP.setColor(g[i] == car.gearChar() ? Style.ACCENT : Style.DIM);
            c.drawText(String.valueOf(g[i]), x + step * i, baseline, gearP);
        }
    }

    private void drawTurn(Canvas c, float cx, float cy, boolean left, boolean lit) {
        iconFill.setColor(lit ? Style.WARN : Style.DIM);
        iconStroke.setColor(lit ? Style.WARN : Style.DIM);
        iconStroke.setStrokeWidth(6);
        float d = left ? -1 : 1;
        path.reset();
        path.moveTo(cx + d * 11, cy);
        path.lineTo(cx - d * 8, cy - 13);
        path.lineTo(cx - d * 8, cy + 13);
        path.close();
        c.drawPath(path, iconFill);
        c.drawPath(path, iconStroke);
        iconStroke.setStrokeWidth(3);
    }

    private void drawLimit(Canvas c, float cx, float cy, int limit) {
        c.drawCircle(cx, cy, 48, white);
        c.drawCircle(cx, cy, 44, red);
        String t = String.valueOf(limit);
        Style.size(limitNum, Style.T_LIMIT);
        Style.fit(limitNum, t, 72);
        c.drawText(t, cx, cy + 14, limitNum);
    }

    private void drawPower(Canvas c, float w) {
        float x0 = 24, x1 = w - 24, top = 648, bottom = 676;   // 28 tall, same as the battery bar (the owner)
        Style.size(powerLbl, Style.T_LABEL);
        // "EST." while the car's power reading is unusable and the bar is worked out from speed
        c.drawText(car.powerEstimated() ? "POWER · EST." : "POWER", x0, 638, powerLbl);
        Style.size(scaleP, Style.T_SCALE);
        r.set(x0, top, x1, bottom);
        c.drawRoundRect(r, 8, 8, track);
        c.drawRoundRect(r, 8, 8, trackEdge);
        float zero = x0 + (x1 - x0) * ZERO_AT;
        if (car.power > 0) {
            if (car.regen) {
                float len = (zero - x0) * Math.min(1f, car.power / REGEN_MAX);
                r.set(zero - len, top, zero, bottom);
                bar.setShader(null);
                bar.setColor(Style.GOOD);
            } else {
                float len = (x1 - zero) * Math.min(1f, car.power / POWER_MAX);
                r.set(zero, top, zero + len, bottom);
                if (powerShader == null) powerShader = new LinearGradient(zero, 0, x1, 0, Style.WARN, 0xffff8a3d, Shader.TileMode.CLAMP);
                bar.setShader(powerShader);
            }
            c.save();
            c.clipRect(x0, top, x1, bottom);
            c.drawRect(r, bar);
            c.restore();
        }
        c.drawRect(zero - 1, top, zero + 1, bottom, zeroP);
        scaleP.setTextAlign(Paint.Align.LEFT);
        c.drawText("Regen", x0, 702, scaleP);
        scaleP.setTextAlign(Paint.Align.CENTER);
        c.drawText("0", zero, 702, scaleP);
        scaleP.setTextAlign(Paint.Align.RIGHT);
        c.drawText("Power", x1, 702, scaleP);
    }
}
