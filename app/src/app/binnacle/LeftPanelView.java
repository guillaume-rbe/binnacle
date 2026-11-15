package app.binnacle;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;
import android.view.View;

/**
 * Left panel: status lights, PRND, indicators, and either the compact speed dial with the
 * speed-limit sign (Park, or the camera or CarPlay over the centre) or a swipe card (Drive and
 * Reverse, while the big dial is in the centre). Exactly one speed on screen (design 2a, 15 Nov):
 * the centre reports how much of its dial shows and this column hands over to it.
 * Drawn in 340x720 design pixels (mockup's .side.l).
 */
public class LeftPanelView extends View {

    private final CarState car;
    private final Paint line = Style.fill(Style.LINE);
    private final Paint gearP = Style.text(Style.DISPLAY, Style.T_GEAR, Style.DIM, Paint.Align.CENTER);
    private final Paint limitNum = Style.text(Style.DISPLAY, Style.T_LIMIT, 0xff111111, Paint.Align.CENTER);
    private final Paint white = Style.fill(0xffffffff);
    private final Paint red = Style.stroke(Style.BAD, 8);
    private final Paint iconFill = Style.fill(Style.DIM);
    private final Paint iconStroke = Style.stroke(Style.DIM, 3);
    private final Path path = new Path();
    private final RectF r = new RectF();

    public LeftPanelView(Context c, DashApp app) {
        super(c);
        this.car = app.car;
        card = new SwipeCard(this, app, true, "left_card_pinned");
        card.setShown(false);
    }

    /** The left swipe card: up only while the big dial is in the centre. */
    final SwipeCard card;

    /** How much of the centre's big dial shows (CentreView.dialShown): 0 = this column's compact layout. */
    private float dialShown, dialTarget;
    private long dialStepMs;
    /** A snap (CarPlay or the camera covering the centre) fades over at least this long (in-car, 15 Nov). */
    private static final float DIAL_FADE_S = 0.25f;

    void onDialShown(float shown) {
        dialTarget = shown;
        card.setShown(shown > 0);   // a page clash is settled from the first faded-in frame
        invalidate();
    }

    /** Moves dialShown toward the centre's value, no faster than DIAL_FADE_S for the whole way. */
    private void stepDial() {
        long now = SystemClock.uptimeMillis();
        if (dialShown == dialTarget) { dialStepMs = 0; return; }
        float dt = dialStepMs == 0 ? 1f / 60 : Math.min(0.1f, (now - dialStepMs) / 1000f);
        dialStepMs = now;
        float step = dt / DIAL_FADE_S;
        dialShown = dialShown < dialTarget ? Math.min(dialTarget, dialShown + step) : Math.max(dialTarget, dialShown - step);
        if (dialShown != dialTarget) postInvalidateOnAnimation();
    }

    /**
     * Tapped in the top-left corner (the hidden screenshot button, when it is switched on). No
     * element of its own (the owner, 15 Nov): a good-sized area over the corner of the column.
     */
    private Runnable onShotTap;
    void setOnShotTap(Runnable r) { onShotTap = r; }

    private static final float SHOT_SIZE = 120;
    private boolean shotTouch;
    /** The left card sits CARD_DY lower than in the right column's frame it is drawn in. */
    private static final float CARD_DY = 90;

    @Override public boolean onTouchEvent(android.view.MotionEvent e) {
        float s = getHeight() / Style.DESIGN_H, x = e.getX() / s, y = e.getY() / s;
        int action = e.getActionMasked();
        if (!shotTouch && card.touch(action, x, y - CARD_DY)) return true;
        if (onShotTap == null) return false;
        boolean in = x <= SHOT_SIZE && y <= SHOT_SIZE;
        switch (action) {
            case android.view.MotionEvent.ACTION_DOWN:
                shotTouch = in;
                return shotTouch;
            case android.view.MotionEvent.ACTION_UP:
                if (shotTouch && x <= SHOT_SIZE + 20 && y <= SHOT_SIZE + 20) onShotTap.run();
                shotTouch = false;
                return true;
            case android.view.MotionEvent.ACTION_CANCEL:
                shotTouch = false;
                return true;
        }
        return shotTouch;
    }

    private final SpeedDial dial = SpeedDial.compact();

    @Override protected void onDraw(Canvas c) {
        float s = getHeight() / Style.DESIGN_H;
        c.save();
        c.scale(s, s);
        float w = getWidth() / s, cx = w / 2f;
        c.drawRect(w - 1, 0, w, Style.DESIGN_H, line);

        cue = approachCue(centreCovered && car.autopilot ? 1 : 0);
        if (cue > 0) drawAutopilotCue(c, cx, w);   // under the indicators and the sign

        drawStatusRow(c, cx, 84);

        // compact layout (dial, gears low, limit) hands over to the Drive layout (gears high, card)
        // as the centre's dial comes in: the gears and indicators slide up, the rest cross-fades
        stepDial();
        float d = dialShown, compact = 1f - d;
        card.track();   // a drive off ends a swipe on the card even while it is hidden
        DashApp app = DashApp.get(getContext());
        if (compact > 0) dial.draw(c, app, car, cx, DIAL_Y, compact, car.autopilot ? 1 : 0);
        float gears = lerp(GEARS_COMPACT, GEARS_DRIVE, d), turns = lerp(TURNS_COMPACT, TURNS_DRIVE, d);
        drawGears(c, cx, gears);
        drawTurn(c, cx - 52, turns, true, car.turnLeft);
        drawTurn(c, cx + 52, turns, false, car.turnRight);
        if (car.speedLimit > 0 && compact > 0) drawLimit(c, cx, LIMIT_Y, Units.speedLimit(app, car.speedLimit), compact);
        if (d > 0) {
            int layer = d < 1 ? c.saveLayerAlpha(0, 0, w, Style.DESIGN_H, Math.round(255 * d)) : -1;
            c.save();
            c.translate(0, CARD_DY);
            card.draw(c);
            c.restore();
            if (layer >= 0) c.restoreToCount(layer);
        }
        c.restore();
    }

    /**
     * CarPlay (centre window) or the camera covers the centre screen, and with it the Autopilot lane
     * (DashActivity). Full-screen CarPlay hides this panel as well.
     */
    private boolean centreCovered;

    void setCentreCovered(boolean on) {
        if (on == centreCovered) return;
        centreCovered = on;
        invalidate();
    }

    /**
     * Autopilot cue while the centre is covered (in-car feedback, 6 Nov: on CarPlay or the camera
     * there was no sign of Autopilot but the small wheel icon): a soft glow in the shape of the
     * centre's Autopilot lane, from just below the gears down to the bottom of the screen, clear of
     * the speed (the owner, 6 Nov). Blue, or the rainbow with Rainbow road on. No
     * hard edges: it fades in from the top and out to nothing at the sides. It fades in and out like
     * the centre's and then holds still (no pulse or flow), so it never pulls the eye while driving.
     */
    private static final float CUE_TOP = 512, CUE_TOP_W = 70;
    /** Nested passes, each narrower toward the middle: they add up to a soft fade at the sides. */
    private static final int CUE_FEATHER = 20;
    private static final float CUE_EDGE = 0.48f;
    private final Paint cueFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private Shader cueBlue, cueRainbow;
    private float cue;
    private long cueLastMs;

    /** Fade the cue in and out over CentreView.AP_FADE_S, as the centre's Autopilot look does. */
    private float approachCue(float goal) {
        long now = SystemClock.uptimeMillis();
        float dt = cueLastMs == 0 ? 0 : Math.min(0.1f, (now - cueLastMs) / 1000f);
        cueLastMs = cue == goal ? 0 : now;
        float step = dt / CentreView.AP_FADE_S;
        float out = cue < goal ? Math.min(goal, cue + step) : Math.max(goal, cue - step);
        if (out != goal) postInvalidateOnAnimation();
        return out;
    }

    private void drawAutopilotCue(Canvas c, float cx, float w) {
        float bottom = Style.DESIGN_H;
        boolean rainbow = DashApp.get(getContext()).rainbowRoad();
        if (rainbow && cueRainbow == null) {
            int n = 11;
            int[] cols = new int[n];
            float[] pos = new float[n];
            float[] hsv = {0, CentreView.RAINBOW_SAT, 1f};
            for (int i = 0; i < n; i++) {
                float t = i / (n - 1f);
                hsv[0] = t * 330f;
                int a = Math.round(255 * (0.05f + 0.6f * t));
                cols[i] = (Color.HSVToColor(hsv) & 0x00ffffff) | (a << 24);
                pos[i] = t;
            }
            cueRainbow = new LinearGradient(0, CUE_TOP, 0, bottom, cols, pos, Shader.TileMode.CLAMP);
        }
        if (!rainbow && cueBlue == null) {
            int clear = CentreView.AP_BLUE & 0x00ffffff, a = Style.light ? 0x73 : 0x99;   // light needs less
            cueBlue = new LinearGradient(0, CUE_TOP, 0, bottom, clear, clear | (a << 24), Shader.TileMode.CLAMP);
        }
        cueFill.setShader(rainbow ? cueRainbow : cueBlue);
        cueFill.setAlpha(Math.round(255 * (1f - (float) Math.pow(1f - 0.9f * cue, 1.0 / CUE_FEATHER))));
        for (int i = 0; i < CUE_FEATHER; i++) {
            float k = 1f - 2f * CUE_EDGE * i / (CUE_FEATHER - 1);
            float tw = CUE_TOP_W * k / 2f, bw = w * k / 2f;
            path.reset();
            path.moveTo(cx - tw, CUE_TOP);
            path.lineTo(cx + tw, CUE_TOP);
            path.lineTo(cx + bw, bottom);
            path.lineTo(cx - bw, bottom);
            path.close();
            c.drawPath(path, cueFill);
        }
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

    private void drawLimit(Canvas c, float cx, float cy, int limit, float alpha) {
        int a = Math.round(255 * alpha);
        white.setAlpha(a);
        red.setColor(Style.BAD);
        red.setAlpha(a);
        limitNum.setAlpha(a);
        c.drawCircle(cx, cy, 48, white);
        c.drawCircle(cx, cy, 44, red);
        String t = String.valueOf(limit);
        Style.size(limitNum, Style.T_LIMIT);
        Style.fit(limitNum, t, 72);
        c.drawText(t, cx, cy + 14, limitNum);
    }

    /**
     * Design 2a (15 Nov), design px: compact dial centre, then the gears, indicators and limit
     * sign under it; in Drive the gears and indicators move up under the telltales.
     */
    private static final float DIAL_Y = 292, GEARS_COMPACT = 500, TURNS_COMPACT = 548, LIMIT_Y = 640,
            GEARS_DRIVE = 196, TURNS_DRIVE = 262;

    private static float lerp(float a, float b, float t) { return a + (b - a) * t; }
}
