package app.binnacle;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;

import java.util.Map;

/**
 * Centre window. Park / Neutral: side-on car. Drive / Reverse: rear view of the car on a road
 * whose lane dashes move with the real speed (perspective-correct, backwards in Reverse).
 * Parking-sensor bars appear when something is close. Also shows the feed state (demo / problem)
 * and, if enabled in settings, the latest raw CAN values for in-car checks.
 */
public class CentreView extends View {

    /** Lane dash geometry in metres: a 3 m dash every 12 m; the bottom of the screen is 4 m ahead. */
    private static final float DASH = 3f, PERIOD = 12f, NEAR = 4f, FAR = 120f;
    /**
     * Horizon height in the centre window. In-car feedback (28 Oct): the road looked as if seen
     * from lower down than the car render, so the horizon came down a little (0.34 -> 0.38) and the
     * lane spreads wider at the bottom (BOT_X), which tips the road plane up to meet the car's view.
     */
    private static final float HORIZON = 0.38f;

    private final DashApp app;
    private final CarState car;

    private final Paint screen = Style.fill(Style.SCREEN);
    private final Paint edge = Style.stroke(Style.LINE, 1.5f);
    private final Paint sky = new Paint();
    private final Paint road = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint attention = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint attentionSpill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private Shader attentionShader;
    private float attentionTop = Float.NaN;
    private long attentionUntil;
    private final Paint ground = new Paint(Paint.ANTI_ALIAS_FLAG), groundRim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final android.graphics.Matrix groundMatrix = new android.graphics.Matrix();
    private Shader groundShader;
    private float groundKey = Float.NaN;
    private final RectF attentionBox = new RectF(), attentionBar = new RectF();
    private final Path attentionClip = new Path();
    /**
     * The attention bar is drawn in this view, which sits above the in-dash camera (DashActivity),
     * so it still shows while the camera covers the centre window.
     */
    final View attentionOverlay;
    /** The strip follows the car's warning and fades out this fast once it drops (in-car: 3 s felt late). */
    private static final long ATTENTION_MIN_MS = 250;
    /** LEDs on each side of the centre, and how often a pulse runs out to the edges. */
    private static final int ATTENTION_LEDS = 24;
    private static final long ATTENTION_PERIOD_MS = 900;
    /** Road side feather: width at the bottom (px, ~15% of it at the horizon) and its number of steps. */
    private static final float ROAD_FEATHER = 90f;
    private static final int ROAD_FEATHER_STEPS = 12;
    private final Paint lane = Style.fill(0xffe8edf2);
    private final Paint glow = new Paint();
    private final Paint carStroke = Style.stroke(0xff9aa6b6, 3);
    private final Paint carFill = Style.fill(0xff1b2430);
    private final Paint bmp = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Paint badgeText = Style.label(Style.T_BADGE);
    private final Paint badgeBg = Style.fill(Style.BADGE_BG);
    private final Paint sensor = Style.fill(Style.GOOD);
    private final Paint sensorArc = new Paint(Paint.ANTI_ALIAS_FLAG), stopText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stopFill = Style.fill(0xffd8261c), stopRim = Style.stroke(0xffffffff, 4);
    private final Path stopPath = new Path();
    private final Paint odoText = Style.text(Style.BODY, 16, Style.MUTED, Paint.Align.RIGHT);
    private final Paint rawP = Style.text(Typeface.MONOSPACE, 13, 0xffc8d2de, Paint.Align.LEFT);
    private final Path path = new Path();
    private final RectF r = new RectF(), box = new RectF();

    private final RectF carRect = new RectF();
    // Autopilot look
    static final float AP_FADE_S = 0.4f;   // also the left panel's Autopilot cue
    static final int AP_LINE = 0xff7fd4ff;
    private final Paint apPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    /** Autopilot lane blue: deeper than the UI accent so it holds its own next to the rainbow. */
    static final int AP_BLUE = 0xff1f86ff;
    private static final float RAINBOW_FAR = 45f, RAINBOW_STEP = 0.75f, RAINBOW_MIN_CYCLE_PX = 16f;
    static final float RAINBOW_SAT = 0.75f;
    /** Soft lane edges: FEATHER nested passes, the innermost inset EDGE of the lane width each side. */
    private static final int FEATHER = 12;
    private static final float EDGE = 0.10f;
    private int[] rbColours;
    private float[] rbPos;
    private static final float[] TOP_X = {0.477f, 0.523f}, BOT_X = {0.255f, 0.745f};   // lane lines at horizon / bottom
    private final Paint distText = Style.text(Style.DISPLAY, Style.T_BADGE, Style.ACCENT, Paint.Align.LEFT);
    private final Paint distStroke = Style.stroke(Style.ACCENT, 3);
    private final float[] hsv = {0f, 0.75f, 1f};
    private float apBlend, apShaderTop = Float.NaN, apShaderH = Float.NaN;
    private long apLastMs;
    private LinearGradient apShader;
    private final CarLights lights = new CarLights();
    private final DoorScene doorScene = new DoorScene(new Runnable() {
        @Override public void run() { invalidate(); }
    });

    /** 0 = parked scene, 1 = driving scene; in between while handing over. */
    private float blend;
    private boolean blendStarted, wantDrive, gearDrive;
    private long lastBlendMs, gearSince, waitSince;
    private static final float BLEND_SECONDS = 0.7f;
    private static final long GEAR_SETTLE_MS = 400, TURN_TIMEOUT_MS = 1500;
    private final RectF parkRect = new RectF(), driveRect = new RectF();

    private float travel;          // metres of road travelled, drives the dash phase
    private long lastFrameMs;

    /** CarPlay covers the centre window (DashActivity): the scene isn't drawn or animated. */
    private boolean covered;

    void setCovered(boolean on) {
        if (on == covered) return;
        covered = on;
        invalidate();
    }

    public CentreView(Context c, DashApp app) {
        super(c);
        sensorArc.setStyle(Paint.Style.STROKE);
        sensorArc.setStrokeCap(Paint.Cap.ROUND);
        stopText.setColor(0xffffffff);
        stopText.setTextAlign(Paint.Align.CENTER);
        stopText.setTypeface(Typeface.DEFAULT_BOLD);
        groundRim.setStyle(Paint.Style.STROKE);
        groundRim.setStrokeWidth(1.5f);
        groundRim.setColor(0x2a7fd4ff);
        attentionOverlay = new View(c) {
            @Override protected void onDraw(Canvas canvas) {
                if (drawAttention(canvas, SystemClock.uptimeMillis())) postInvalidateOnAnimation();
            }
        };
        this.app = app;
        this.car = app.car;
    }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        glow.setShader(new RadialGradient(w / 2f, 0, Math.max(1, w * 0.52f), Style.GLOW, Style.BG, Shader.TileMode.CLAMP));
    }

    @Override protected void onDraw(Canvas c) {
        float s = getHeight() / Style.DESIGN_H;
        c.drawRect(0, 0, getWidth(), getHeight(), glow);
        c.save();
        c.scale(s, s);
        float w = getWidth() / s, pad = 14;
        box.set(pad, pad, w - pad, Style.DESIGN_H - pad);
        if (covered) {
            // under CarPlay: only the frame round it, no scene and no animation (audit D9); the
            // attention overlay sits above CarPlay and stays live
            c.drawRoundRect(box, 16, 16, edge);
            c.restore();
            lastFrameMs = 0;
            if (car.driverWarning != 0 || attentionUntil > SystemClock.uptimeMillis()) attentionOverlay.invalidate();
            return;
        }

        c.save();
        path.reset();
        path.addRoundRect(box, 16, 16, Path.Direction.CW);
        c.clipPath(path);
        c.drawRect(box, screen);
        long now = SystemClock.uptimeMillis();
        boolean transitioning = updateBlend(now);
        apBlend = approachBlend(apBlend, car.autopilot ? 1 : 0, now);   // every frame, whatever the scene
        if (blend > 0) { drawDrive(c, now, blend); doorScene.pause(); } else { drawPark(c); lastFrameMs = 0; }
        // the old pills when there's no car render, and until the hand-over to the driving view has
        // finished: the arcs only draw once the car has settled (mid-change they didn't line up with
        // the sliding car, in-car feedback), and a reading must never vanish
        if (app.art() == null || app.art().rear == null || blend < 1) drawSensors(c);
        drawBadges(c);
        drawCompass(c);
        if (car.driverWarning != 0 || attentionUntil > now) attentionOverlay.invalidate();
        if (car.autopilot && car.followDistance >= 0 && blend > 0) drawFollowDistance(c);
        drawOdometer(c);
        if (!car.raw.isEmpty() && app.rawOverlay()) drawRaw(c);
        c.restore();
        c.drawRoundRect(box, 16, 16, edge);
        c.restore();

        if (transitioning || (car.moving() && car.speed > 0)) postInvalidateOnAnimation();
    }

    /**
     * Follow-distance setting (cardistance, as the stock dash shows it) while on Autopilot: a small
     * car with two arcs ahead of it and the number, bottom right of the centre window (in-car
     * feedback: not in the AUTOPILOT chip).
     */
    private final java.text.NumberFormat odoFormat = java.text.NumberFormat.getIntegerInstance(java.util.Locale.UK);

    private String odoString, odoLabel;
    private long odoValue;

    /** Odometer as a quiet line, bottom right of the centre window (moved out of the right column). */
    private void drawOdometer(Canvas c) {
        if (car.odometer < 0) return;
        Style.size(odoText, Style.T_SMALL);              // a little bigger than the 16 px scale text (in-car feedback)
        odoText.setColor(Style.FG);
        // rebuilt only when the reading or its unit changes, not every frame (audit D9)
        String label = Units.distLabel(app);
        long value = Units.odometer(app, car.odometer);
        if (odoString == null || value != odoValue || !label.equals(odoLabel)) {
            odoValue = value;
            odoLabel = label;
            odoString = odoFormat.format(value) + " " + label;
        }
        String odo = odoString;
        float w = odoText.measureText(odo), x = box.right - 18, y = box.bottom - 12;
        r.set(x - w - 10, y + odoText.ascent() - 4, x + 10, y + odoText.descent() + 4);
        c.drawRoundRect(r, 10, 10, badgeBg);              // backing so it reads over the road, light theme too
        c.drawText(odo, x, y, odoText);
    }

    /**
     * GPS compass, top right of the centre window: the direction of travel as N, NE, E ... with a
     * small needle pointing north. GPS only gives a heading while moving, so parked it keeps the
     * last one, dimmed. Hidden until the first heading (no location permission: never shown).
     */
    private void drawCompass(Canvas c) {
        Compass cp = app.compass();
        float hd = cp.heading();
        if (Float.isNaN(hd)) return;
        int col = cp.fresh() ? Style.FG : Style.MUTED;
        String pt = Compass.point(hd);
        Style.size(compassText, Style.T_SMALL);
        compassText.setColor(col);
        float tw = compassText.measureText(pt), x1 = box.right - 18, y = box.top + 34;
        r.set(x1 - tw - 46, y + compassText.ascent() - 6, x1 + 10, y + compassText.descent() + 6);
        c.drawRoundRect(r, 12, 12, badgeBg);
        c.drawText(pt, x1, y, compassText);
        // needle: north relative to the direction of travel (straight up = heading north)
        float cx = x1 - tw - 22, cy = (r.top + r.bottom) / 2f;
        c.save();
        c.rotate(-hd, cx, cy);
        path.reset();
        path.moveTo(cx, cy - 11);
        path.lineTo(cx + 5, cy + 2);
        path.lineTo(cx - 5, cy + 2);
        path.close();
        needle.setColor(cp.fresh() ? Style.BAD : Style.MUTED);
        c.drawPath(path, needle);
        path.reset();
        path.moveTo(cx, cy + 11);
        path.lineTo(cx + 5, cy + 2);
        path.lineTo(cx - 5, cy + 2);
        path.close();
        needle.setColor(col);
        c.drawPath(path, needle);
        c.restore();
    }

    private final Paint compassText = Style.text(Style.BODY_BOLD, Style.T_SMALL, Style.FG, Paint.Align.RIGHT);
    private final Paint needle = Style.fill(Style.FG);

    private void drawFollowDistance(Canvas c) {
        // right-aligned with the odometer backing, with a clear gap above it (in-car, 2 Nov: it touched)
        float right = box.right - 8, bottom = box.bottom - 12;
        if (car.odometer >= 0) {
            Style.size(odoText, Style.T_SMALL);
            bottom = box.bottom - 12 + odoText.ascent() - 4 - 8;   // the odometer backing's top, less a gap
        }
        float cx = right - 52, cy = bottom - 26;
        r.set(cx - 26, cy - 42, right, bottom);
        c.drawRoundRect(r, 14, 14, badgeBg);                  // backing for contrast over the road (light theme)
        distStroke.setColor(Style.ACCENT);
        distText.setColor(Style.ACCENT);
        r.set(cx - 9, cy - 4, cx + 9, cy + 18);
        c.drawRoundRect(r, 6, 6, distStroke);                 // the car, seen from behind
        r.set(cx - 14, cy - 22, cx + 14, cy - 6);
        c.drawArc(r, 205, 130, false, distStroke);            // gap arcs ahead of it
        r.set(cx - 18, cy - 34, cx + 18, cy - 14);
        c.drawArc(r, 210, 120, false, distStroke);
        Style.size(distText, Style.T_BADGE * 1.6f);
        c.drawText(String.valueOf(car.followDistance), cx + 26, cy + 14, distText);
    }

    /**
     * Autopilot attention warning (driverwarning, a 2-3 s pulse every few minutes on Autosteer in
     * the 28 Oct drive log): a row of blue LEDs along the top of the centre window, with pulses
     * running out from the centre to both edges, mirrored (the owner's design, 29 Oct). Blue because
     * it is a reminder, not a danger. It shows while the car's warning is on and fades out over ATTENTION_MIN_MS once the
     * car clears it, like the stock dash and the Tesla screen. Returns true while it needs more frames.
     */
    private static final long GUTTER_PULSE_MS = 1200;
    private final Path gutterPath = new Path();
    private final Paint gutter = new Paint(Paint.ANTI_ALIAS_FLAG);

    private boolean drawAttention(Canvas c, long now) {
        if (car.driverWarning != 0) attentionUntil = now + ATTENTION_MIN_MS;
        float fade = Math.min(1f, (attentionUntil - now) / (float) ATTENTION_MIN_MS);
        if (fade <= 0) return false;
        // the same scaled, inset, rounded window as onDraw (this runs in attentionOverlay)
        float s = attentionOverlay.getHeight() / Style.DESIGN_H, pad = 14;
        attentionBox.set(pad, pad, attentionOverlay.getWidth() / s - pad, Style.DESIGN_H - pad);
        float top = attentionBox.top + 5, ledH = 7, cx = attentionBox.centerX();
        if (attentionShader == null || attentionTop != top) {   // light spilling down from the LEDs
            attentionShader = new LinearGradient(0, top, 0, top + 34, 0xff36b9ff, 0x0036b9ff, Shader.TileMode.CLAMP);
            attentionTop = top;
        }
        attentionSpill.setShader(attentionShader);
        c.save();
        c.scale(s, s);
        // a soft blue breathing in the gutter between the centre column's edges and the rounded
        // window, behind it all (the owner, 31 Oct: more noticeable, still subtle)
        float breath = 0.5f - 0.5f * (float) Math.cos(2 * Math.PI * (now % GUTTER_PULSE_MS) / GUTTER_PULSE_MS);
        gutterPath.reset();
        gutterPath.setFillType(Path.FillType.EVEN_ODD);
        gutterPath.addRect(0, 0, attentionOverlay.getWidth() / s, Style.DESIGN_H, Path.Direction.CW);
        gutterPath.addRoundRect(attentionBox, 16, 16, Path.Direction.CW);
        gutter.setColor(0xff36b9ff);
        gutter.setAlpha(Math.round(fade * (50 + 130 * breath)));
        c.drawPath(gutterPath, gutter);
        attentionClip.reset();
        attentionClip.addRoundRect(attentionBox, 16, 16, Path.Direction.CW);
        c.clipPath(attentionClip);
        float half = attentionBox.width() / 2 - 10, pitch = half / ATTENTION_LEDS, ledW = pitch * 0.62f;
        float phase = (now % ATTENTION_PERIOD_MS) / (float) ATTENTION_PERIOD_MS;
        for (int i = 0; i < ATTENTION_LEDS; i++) {
            float d = (i + 0.5f) / ATTENTION_LEDS;                   // 0 = centre, 1 = edge
            float behind = phase - d * 0.8f;                          // how far the pulse front is past this LED
            if (behind < 0) behind += 1;
            float lit = behind < 0.35f ? 1 - behind / 0.35f : 0;      // bright at the front, fading tail
            float level = fade * (0.18f + 0.82f * lit * lit);
            for (int side = -1; side <= 1; side += 2) {
                float x = cx + side * (i + 0.5f) * pitch;
                attentionBar.set(x - pitch / 2, top, x + pitch / 2, top + 34);
                attentionSpill.setAlpha(Math.round(150 * level * lit));
                if (lit > 0) c.drawRect(attentionBar, attentionSpill);
                attention.setColor(0xff36b9ff);                       // glow
                attention.setAlpha(Math.round(140 * level * (0.3f + 0.7f * lit)));
                attentionBar.set(x - ledW / 2 - 4, top - 4, x + ledW / 2 + 4, top + ledH + 4);
                c.drawRoundRect(attentionBar, 6, 6, attention);
                attention.setColor(lit > 0.6f ? 0xffd8f3ff : 0xff7fd4ff);   // the LED itself, near white when hot
                attention.setAlpha(Math.round(255 * level));
                attentionBar.set(x - ledW / 2, top, x + ledW / 2, top + ledH);
                c.drawRoundRect(attentionBar, 3, 3, attention);
            }
        }
        c.restore();
        return true;
    }

    /**
     * Park <-> drive hand-over, using the stock turn frames as the natural in-between steps:
     * to Drive, the parked car turns to face away (frame 12, same framing as the driving render),
     * then the road fades in as the car slides down to its driving spot; to Park, the reverse
     * (road fades out, car rises to the parked spot, then turns back to the side view).
     * A gear must hold for GEAR_SETTLE_MS before the scene follows it (D-N-D while rolling, or a
     * feed blip, doesn't start a hand-over), and an unknown gear keeps the current scene.
     * Once the fade has started it always runs to the end; the wait for the turn has a timeout.
     * Returns true while the fade itself is running (the turn redraws itself).
     */
    private boolean updateBlend(long now) {
        if (car.gear != CarState.GEAR_UNKNOWN) {
            boolean moving = car.moving();
            if (moving != gearDrive) { gearDrive = moving; gearSince = now; }
        } else {
            // feed lost / reset: cancel any pending change; the settle restarts when a known gear returns
            gearDrive = wantDrive;
            gearSince = now;
        }
        CarArt a = app.art();
        boolean turnFrames = a != null && a.src != null && a.side != null && a.rear != null;
        if (!blendStarted && car.gear != CarState.GEAR_UNKNOWN) {   // first real gear: no animation
            blendStarted = true;
            wantDrive = gearDrive;
            blend = wantDrive ? 1 : 0;
            doorScene.force(wantDrive ? DoorScene.VIEW_BEHIND : -1);
            if (wantDrive) doorScene.snapTo(DoorScene.VIEW_BEHIND);
        }
        if (gearDrive != wantDrive && now - gearSince >= GEAR_SETTLE_MS) {
            wantDrive = gearDrive;
            waitSince = now;
        } else if (gearDrive != wantDrive) {
            postInvalidateDelayed(GEAR_SETTLE_MS);           // re-check once the gear has settled
        }

        float target = wantDrive ? 1 : 0;
        if (blend == target) {
            lastBlendMs = 0;
            if (!wantDrive) doorScene.force(-1);
            return false;
        }
        if (wantDrive) {
            doorScene.force(DoorScene.VIEW_BEHIND);
            boolean ready = blend > 0 || !turnFrames || doorScene.reached(DoorScene.VIEW_BEHIND)
                    || now - waitSince > TURN_TIMEOUT_MS;
            if (!ready) {
                postInvalidateDelayed(TURN_TIMEOUT_MS);      // the turn redraws itself; this is the backstop
                return false;
            }
        }
        float dt = lastBlendMs == 0 ? 0 : Math.min(0.05f, (now - lastBlendMs) / 1000f);
        lastBlendMs = now;
        blend = wantDrive ? Math.min(1, blend + dt / BLEND_SECONDS) : Math.max(0, blend - dt / BLEND_SECONDS);
        return true;
    }

    @Override protected void onDetachedFromWindow() {
        doorScene.release();
        super.onDetachedFromWindow();
    }

    /** Memory pressure: drop the door/turn frames (they reload when needed). */
    void trimMemory() {
        doorScene.release();
    }

    private void drawPark(Canvas c) {
        CarArt a = app.art();
        Bitmap b = a == null ? null : a.side;
        float bw = box.width(), bh = box.height();
        if (a != null && a.src != null && a.side != null) {
            parkRect(carRect);
            drawGround(c, carRect, 1f);
            if (doorScene.draw(c, a, car.doors, carRect)) postInvalidateOnAnimation();
            lights.setModelY(app.modelY());
            lights.drawParked(c, doorScene.view(), carRect, car);
        } else if (b != null) {
            fitBottom(c, b, bw * 0.66f, bh * 0.44f, box.bottom - bh * 0.14f);
        } else {
            float cx = box.centerX(), base = box.bottom - bh * 0.16f, L = bw * 0.5f, H = bh * 0.2f;
            path.reset();
            path.moveTo(cx - L / 2, base - H * 0.2f);
            path.cubicTo(cx - L / 2, base - H * 0.6f, cx - L * 0.36f, base - H * 0.62f, cx - L * 0.22f, base - H * 0.66f);
            path.cubicTo(cx - L * 0.1f, base - H * 1.05f, cx + L * 0.12f, base - H * 1.05f, cx + L * 0.24f, base - H * 0.66f);
            path.cubicTo(cx + L * 0.4f, base - H * 0.6f, cx + L / 2, base - H * 0.5f, cx + L / 2, base - H * 0.2f);
            path.lineTo(cx - L / 2, base - H * 0.2f);
            c.drawPath(path, carFill);
            c.drawPath(path, carStroke);
            c.drawCircle(cx - L * 0.3f, base - H * 0.15f, H * 0.22f, carFill);
            c.drawCircle(cx - L * 0.3f, base - H * 0.15f, H * 0.22f, carStroke);
            c.drawCircle(cx + L * 0.32f, base - H * 0.15f, H * 0.22f, carFill);
            c.drawCircle(cx + L * 0.32f, base - H * 0.15f, H * 0.22f, carStroke);
        }
    }

    private void drawDrive(Canvas c, long now, float k) {
        parkRect(parkRect);
        doorScene.warm(app.art(), parkRect);   // cheap once warm; makes the first hand-back to Park seamless
        if (lastFrameMs != 0 && car.speed > 0) {
            float dt = Math.min(0.1f, (now - lastFrameMs) / 1000f);
            float mps = car.speedKmh() / 3.6f;   // m/s whatever unit the car reports
            travel += (car.gear == CarState.GEAR_R ? -mps : mps) * dt;
            travel %= PERIOD;
            if (travel < 0) travel += PERIOD;
        }
        lastFrameMs = now;

        float bw = box.width(), bh = box.height();
        float top = box.top + bh * HORIZON, bottom = box.bottom, rh = bottom - top;
        if (sky.getShader() == null) {
            sky.setShader(new LinearGradient(0, box.top, 0, box.bottom, Style.SKY, null, Shader.TileMode.CLAMP));
            // the far end fades into the sky instead of stopping at a hard horizon line
            road.setShader(new LinearGradient(0, top, 0, bottom, Style.ROAD,
                    new float[]{0f, 0.16f, 1f}, Shader.TileMode.CLAMP));
        }
        CarArt a = app.art();
        Bitmap b = a == null ? null : a.rear;
        if (b != null) {
            // the car slides between its parked and driving spots as the road fades
            fitRect(b, bw * 0.39f, bh * 0.46f, box.bottom - bh * 0.13f, driveRect);   // room below for the rear sensor arcs
            if (k >= 1) {
                carRect.set(driveRect);
            } else {
                parkRect(parkRect);
                float e = k * k * (3 - 2 * k);                // smoothstep
                carRect.set(lerp(parkRect.left, driveRect.left, e), lerp(parkRect.top, driveRect.top, e),
                    lerp(parkRect.right, driveRect.right, e), lerp(parkRect.bottom, driveRect.bottom, e));
            }
        }
        if (k < 1) {                                          // the park floor fades out as the road fades in
            parkRect(parkRect);
            drawGround(c, parkRect, 1f - k);
        }
        int layer = k < 1 ? c.saveLayerAlpha(box, Math.round(255 * k)) : -1;
        c.drawRect(box, sky);

        // road surface: trapezoid 104% wide, top 44-56%, with feathered sides: wider copies drawn
        // outermost first, each alpha chosen so the coverage ramps evenly up to the solid road
        float rx0 = box.centerX() - bw * 0.52f, rw = bw * 1.04f;
        for (int i = ROAD_FEATHER_STEPS; i >= 0; i--) {
            float grow = ROAD_FEATHER * i / ROAD_FEATHER_STEPS;
            float cov = 1f - (float) i / (ROAD_FEATHER_STEPS + 1), prev = 1f - (float) (i + 1) / (ROAD_FEATHER_STEPS + 1);
            road.setAlpha(Math.round(255 * (1f - (1f - cov) / (1f - prev))));
            path.reset();
            path.moveTo(rx0 + rw * 0.44f - grow * 0.15f, top);
            path.lineTo(rx0 + rw * 0.56f + grow * 0.15f, top);
            path.lineTo(rx0 + rw + grow, bottom);
            path.lineTo(rx0 - grow, bottom);
            path.close();
            c.drawPath(path, road);
        }
        road.setAlpha(255);

        // Autopilot: the lane under the car glows blue (or a moving rainbow road), lines turn blue
        float[] topX = TOP_X, botX = BOT_X;
        if (apBlend > 0) drawAutopilotLane(c, rx0, rw, top, rh, topX, botX);
        lane.setColor(lerpColor(0xffe8edf2, AP_LINE, apBlend));

        // lane lines either side of the car, perspective dashes
        for (int side = 0; side < 2; side++) {
            for (float d = NEAR - travel; d < FAR; d += PERIOD) {
                float d0 = Math.max(NEAR, d), d1 = d + DASH;
                if (d1 <= NEAR) continue;
                float t0 = NEAR / d0, t1 = NEAR / d1;          // 1 = bottom of screen, ->0 at horizon
                laneQuad(c, rx0, rw, top, rh, topX[side], botX[side], t1, t0);
            }
        }

        if (b != null) lights.drawBeam(c, carRect, car, top);    // under the car, fades with the road
        if (layer >= 0) c.restoreToCount(layer);
        if (b != null) {
            if (k >= 1) drawSensorArcs(c, driveRect);         // once the car has settled; under the car
            c.drawBitmap(b, null, carRect, bmp);
            lights.setModelY(app.modelY());
            lights.drawRearView(c, carRect, car);
        } else {
            float cx = box.centerX(), base = box.bottom - bh * 0.07f, W = bw * 0.26f, H = bh * 0.3f;
            r.set(cx - W / 2, base - H * 0.55f, cx + W / 2, base);
            c.drawRoundRect(r, 24, 24, carFill);
            c.drawRoundRect(r, 24, 24, carStroke);
            r.set(cx - W * 0.36f, base - H, cx + W * 0.36f, base - H * 0.5f);
            c.drawRoundRect(r, 30, 30, carFill);
            c.drawRoundRect(r, 30, 30, carStroke);
        }
    }

    /** Fade the Autopilot look in and out over AP_FADE_S rather than snapping. */
    private float approachBlend(float v, float goal, long now) {
        float dt = apLastMs == 0 ? 0 : Math.min(0.1f, (now - apLastMs) / 1000f);
        apLastMs = v == goal ? 0 : now;
        float step = dt / AP_FADE_S;
        float out = v < goal ? Math.min(goal, v + step) : Math.max(goal, v - step);
        if (out != goal) postInvalidateOnAnimation();
        return out;
    }

    /**
     * The lane between the two lane lines, horizon to bottom. Plain mode: a blue glow that fades
     * toward the horizon. Rainbow mode (Dash settings): a smooth colour gradient fixed to the road
     * (hues blend into each other and flow toward the car with the real speed). Both are drawn
     * feathered: FEATHER nested, slightly narrower copies, so the outer edges fade softly.
     */
    private void drawAutopilotLane(Canvas c, float rx0, float rw, float top, float rh, float[] topX, float[] botX) {
        float bottom = top + rh, tFar;
        if (!app.rainbowRoad()) {
            if (apShader == null || apShaderTop != top || apShaderH != rh) {
                // a deeper blue that still shows toward the horizon, as strong as the rainbow (in-car, 2 Nov: too pale)
                apShader = new LinearGradient(0, top, 0, bottom, AP_BLUE & 0x00ffffff | 0x59000000, AP_BLUE, Shader.TileMode.CLAMP);
                apShaderTop = top;
                apShaderH = rh;
            }
            apPaint.setShader(apShader);
            tFar = NEAR / FAR;
        } else {
            apPaint.setShader(rainbowShader(top, rh));
            tFar = NEAR / FAR;      // the shader clamps its farthest colour up to the horizon fade
        }
        // per-pass alpha so FEATHER overlapping passes add up to the target opacity in the middle
        float target = 0.75f * apBlend;
        int pass = Math.round(255 * (1f - (float) Math.pow(1f - target, 1.0 / FEATHER)));
        apPaint.setAlpha(pass);
        for (int i = 0; i < FEATHER; i++) {
            lanePath(rx0, rw, top, rh, topX, botX, tFar, 1f, EDGE * i / (FEATHER - 1));
            c.drawPath(path, apPaint);
        }
    }

    /**
     * Rainbow gradient down the lane: colour stops every RAINBOW_STEP metres of road, placed with
     * the same perspective as the lane dashes, hue tied to the road position (so it flows with the
     * real speed), fading out toward the horizon. travel wraps at PERIOD, one full hue cycle, so the
     * colours never jump. Stops end at RAINBOW_FAR, where they'd be under a pixel apart.
     */
    private Shader rainbowShader(float top, float rh) {
        // the same road position and size give the same stops: keep the shader (parked, or a
        // redraw without movement) instead of building a new one (audit D9)
        if (rbShader != null && rbTravel == travel && rbTop == top && rbHeight == rh) return rbShader;
        rbTravel = travel;
        rbTop = top;
        rbHeight = rh;
        int n = (int) ((RAINBOW_FAR - NEAR) / RAINBOW_STEP) + 1;
        if (rbColours == null || rbColours.length != n) { rbColours = new int[n]; rbPos = new float[n]; }
        float yFar = top + rh * NEAR / RAINBOW_FAR, yNear = top + rh;
        for (int i = 0; i < n; i++) {
            float d = RAINBOW_FAR - i * RAINBOW_STEP;           // far to near, so positions increase
            if (i == n - 1) d = NEAR;
            float y = top + rh * NEAR / d;
            hsv[0] = (((d + travel) / PERIOD) * 360f % 360f + 360f) % 360f;
            // far away a whole hue cycle squeezes into a few pixels and the stops alias into a
            // flickering solid colour (in-car feedback): fade the saturation out as the cycle
            // shrinks below RAINBOW_MIN_CYCLE_PX, so the far end becomes a calm pale glow
            float cyclePx = rh * NEAR * PERIOD / (d * d);
            hsv[1] = RAINBOW_SAT * Math.min(1f, Math.max(0f, (cyclePx - RAINBOW_MIN_CYCLE_PX * 0.5f) / RAINBOW_MIN_CYCLE_PX));
            float t = NEAR / d;                                  // 1 near the car, small far away
            int a = Math.round(255 * Math.min(1f, 0.15f + t * 1.1f));
            rbColours[i] = (Color.HSVToColor(hsv) & 0x00ffffff) | (a << 24);
            rbPos[i] = (y - yFar) / (yNear - yFar);
        }
        rbShader = new LinearGradient(0, yFar, 0, yNear, rbColours, rbPos, Shader.TileMode.CLAMP);
        return rbShader;
    }

    private Shader rbShader;
    private float rbTravel, rbTop, rbHeight;

    /** Quad of the lane interior between screen fractions tFar and tNear, inset by `inset` of its width each side. */
    private void lanePath(float rx0, float rw, float top, float rh, float[] topX, float[] botX, float tFar, float tNear, float inset) {
        path.reset();
        laneEdge(rx0, rw, top, rh, topX, botX, tFar, inset, true);
        laneEdge(rx0, rw, top, rh, topX, botX, tNear, inset, false);
        path.close();
    }

    /** Far edge left-to-right, then near edge right-to-left, so the quad winds once. */
    private void laneEdge(float rx0, float rw, float top, float rh, float[] topX, float[] botX, float t, float inset, boolean far) {
        float y = top + rh * t;
        float xl = rx0 + rw * (topX[0] + (botX[0] - topX[0]) * t);
        float xr = rx0 + rw * (topX[1] + (botX[1] - topX[1]) * t);
        float w = xr - xl;
        if (far) { path.moveTo(xl + w * inset, y); path.lineTo(xr - w * inset, y); }
        else { path.lineTo(xr - w * inset, y); path.lineTo(xl + w * inset, y); }
    }

    static int lerpColor(int a, int b, float t) {
        int r = Math.round(Color.red(a) + (Color.red(b) - Color.red(a)) * t);
        int g = Math.round(Color.green(a) + (Color.green(b) - Color.green(a)) * t);
        int bl = Math.round(Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t);
        return Color.rgb(r, g, bl);
    }

    /** One dash of a lane line between screen fractions t0 (far) and t1 (near). */
    private void laneQuad(Canvas c, float rx0, float rw, float top, float rh, float tx, float bx, float t0, float t1) {
        // no arrays: this runs for every dash on every frame (audit D9)
        float x0 = rx0 + rw * (tx + (bx - tx) * t0), half0 = rw * (0.003f + 0.0035f * t0), y0 = top + rh * t0;
        float x1 = rx0 + rw * (tx + (bx - tx) * t1), half1 = rw * (0.003f + 0.0035f * t1), y1 = top + rh * t1;
        path.reset();
        path.moveTo(x0 - half0, y0);
        path.lineTo(x0 + half0, y0);
        path.lineTo(x1 + half1, y1);
        path.lineTo(x1 - half1, y1);
        path.close();
        c.drawPath(path, lane);
    }

    private static float lerp(float a, float b, float t) { return a + (b - a) * t; }

    /** The parked car's footprint (800x434 render): fit 66% x 44% of the window, bottom 14% up. */
    /** Parked car frame: ~30% larger than at first (in-car feedback, 29 Oct), a little lower. */
    private void parkRect(RectF out) {
        float bw = box.width(), bh = box.height();
        float w = Math.min(bw * 0.8f, bh * 0.572f / DoorScene.aspect()), h = w * DoorScene.aspect();
        float bottom = box.bottom - bh * 0.06f;
        out.set(box.centerX() - w / 2, bottom - h, box.centerX() + w / 2, bottom);
    }

    /**
     * Floor under the parked car: a soft lit oval where the wheels stand (row y ~0.64 of the
     * 800x434 render) with a faint rim, so the car stands on something instead of floating.
     */
    private void drawGround(Canvas c, RectF car, float alpha) {
        float cx = car.centerX(), cy = car.top + car.height() * 0.57f;
        float rx = car.width() * 0.6f, ry = car.height() * 0.38f;   // seen from above like the car (in-car feedback: more tilt)
        if (groundShader == null || groundKey != rx + cy) {
            groundShader = new RadialGradient(0, 0, 1, new int[]{0x5a5f86ad, 0x26375a7a, 0x00000000},
                    new float[]{0f, 0.6f, 1f}, Shader.TileMode.CLAMP);
            groundMatrix.reset();
            groundMatrix.setScale(rx, ry);
            groundMatrix.postTranslate(cx, cy);
            groundShader.setLocalMatrix(groundMatrix);
            groundKey = rx + cy;
        }
        ground.setShader(groundShader);
        ground.setAlpha(Math.round(255 * alpha));
        groundRim.setAlpha(Math.round(0x2a * alpha));
        r.set(cx - rx, cy - ry, cx + rx, cy + ry);
        c.drawOval(r, ground);
        r.set(cx - rx * 0.78f, cy - ry * 0.78f, cx + rx * 0.78f, cy + ry * 0.78f);
        c.drawOval(r, groundRim);
    }

    /** Rect for b scaled to fit maxW x maxH, centred, its bottom edge at `bottom`. */
    private void fitRect(Bitmap b, float maxW, float maxH, float bottom, RectF out) {
        float k = Math.min(maxW / b.getWidth(), maxH / b.getHeight());
        float w = b.getWidth() * k, h = b.getHeight() * k, x = box.centerX() - w / 2;
        out.set(x, bottom - h, x + w, bottom);
    }

    /** Draw b scaled to fit maxW x maxH, centred, its bottom edge at `bottom`. */
    private void fitBottom(Canvas c, Bitmap b, float maxW, float maxH, float bottom) {
        fitRect(b, maxW, maxH, bottom, r);
        c.drawBitmap(b, null, r, bmp);
    }

    /**
     * Parking sensors: rear row along the bottom edge in Reverse, front row near the horizon when
     * creeping forward. Values: 255 = nothing; smaller = closer (scale not yet verified in the car).
     */
    private void drawSensors(Canvas c) {
        if (car.gear == CarState.GEAR_R) sensorRow(c, car.rear, box.bottom - 18);
        else if (car.gear == CarState.GEAR_D && car.speedKmh() < 10) sensorRow(c, car.front, box.top + box.height() * HORIZON - 30);
    }

    /** Arc sweeps (Canvas degrees, 0 = right, 90 = down) per sensor, in feed order left to right. */
    private static final float[] REAR_ARCS = {140, 95, 50, 5}, FRONT_ARCS = {185, 230, 275, 320};
    private static final float ARC_SWEEP = 35;
    private static final int ARC_BANDS = 4;
    private static final float ARC_FADE_S = 0.25f;
    private final int[] arcVals = new int[4];
    private boolean arcRear;
    private float arcFade;
    private long arcLastMs;

    /**
     * Parking sensors as arcs on the ground around the car (seen from behind in the drive view):
     * behind the bumper in R, ahead of the bonnet in D under 10 km/h. Each sensor has its own
     * segment in its real place, with ARC_BANDS rings: the lit ring moves in toward the car as the
     * level (1 = closest ... 10) drops, green -> yellow -> orange -> red, with the rings outside it
     * as a fainter trail. The car reports levels, not centimetres, so no distance is shown; level
     * 1-2 adds a red STOP sign above the fan. Sensors that see nothing (255, or 0) show only a faint track.
     */
    private void drawSensorArcs(Canvas c, RectF car) {
        boolean rearNow = this.car.gear == CarState.GEAR_R;
        boolean active = rearNow || (this.car.gear == CarState.GEAR_D && this.car.speedKmh() < 10);
        if (active) {
            int[] now = rearNow ? this.car.rear : this.car.front;
            active = false;
            for (int d : now) active |= d > 0 && d < CarState.SENSOR_NONE;
            if (active) { System.arraycopy(now, 0, arcVals, 0, 4); arcRear = rearNow; }
        }
        // fade in and out (in-car feedback: they snapped); fading out keeps the last readings
        long now = SystemClock.uptimeMillis();
        float dt = arcLastMs == 0 ? 0 : Math.min(0.1f, (now - arcLastMs) / 1000f);
        float goal = active ? 1 : 0;
        arcFade = arcFade < goal ? Math.min(goal, arcFade + dt / ARC_FADE_S) : Math.max(goal, arcFade - dt / ARC_FADE_S);
        arcLastMs = arcFade == goal ? 0 : now;
        if (arcFade != goal) postInvalidateOnAnimation();
        if (arcFade <= 0) return;
        boolean rear = arcRear;
        int[] v = arcVals;
        int closest = CarState.SENSOR_NONE;
        for (int d : v) if (d > 0 && d < closest) closest = d;
        float w = car.width(), h = car.height(), cx = car.centerX();
        float cy = rear ? car.bottom - h * 0.2f : car.top + h * 0.5f;
        float rx0 = w * (rear ? 0.6f : 0.5f), ry0 = h * (rear ? 0.2f : 0.56f);
        float sx = w * 0.075f, sy = h * (rear ? 0.05f : 0.07f);
        float[] starts = rear ? REAR_ARCS : FRONT_ARCS;
        sensorArc.setStrokeWidth(rear ? 9 : 7);
        for (int i = 0; i < 4; i++) {
            int d = v[i];
            boolean seen = d > 0 && d < CarState.SENSOR_NONE;
            int lit = !seen ? -1 : d <= 2 ? 0 : d <= 4 ? 1 : d <= 7 ? 2 : 3;   // ring 0 hugs the car
            int col = !seen ? 0xffffffff : d <= 2 ? Style.BAD : d <= 4 ? 0xffff8a3d : d <= 7 ? Style.WARN : Style.GOOD;
            for (int band = 0; band < ARC_BANDS; band++) {
                float rx = rx0 + sx * band, ry = ry0 + sy * band;
                r.set(cx - rx, cy - ry, cx + rx, cy + ry);
                sensorArc.setColor(col);
                sensorArc.setAlpha(Math.round(arcFade * (!seen || band < lit ? 28 : band == lit ? 255 : 255 * 2 / (band - lit + 3))));
                c.drawArc(r, starts[i], ARC_SWEEP, false, sensorArc);
            }
        }
        if (closest <= 2) {
            // a red octagon STOP sign, front and centre above the fan (in-car feedback): above the
            // outer front ring in D, above the roof in R (the rear fan is below the car)
            float rad = 44, sx2 = cx;
            float sy2 = rear ? car.top - rad - 14 : cy - ry0 - sy * ARC_BANDS - rad - 10;
            stopPath.reset();
            for (int k = 0; k < 8; k++) {
                double ang = Math.PI / 8 + k * Math.PI / 4;
                float px = sx2 + (float) Math.cos(ang) * rad, py = sy2 + (float) Math.sin(ang) * rad;
                if (k == 0) stopPath.moveTo(px, py); else stopPath.lineTo(px, py);
            }
            stopPath.close();
            int a = Math.round(255 * arcFade);
            stopFill.setAlpha(a);
            c.drawPath(stopPath, stopFill);
            stopRim.setAlpha(a);
            c.drawPath(stopPath, stopRim);
            Style.size(stopText, Style.T_BADGE * 1.45f);
            stopText.setAlpha(a);
            c.drawText("STOP", sx2, sy2 - (stopText.ascent() + stopText.descent()) / 2, stopText);
        }
    }

    private void sensorRow(Canvas c, int[] v, float y) {
        float segW = 70, gap = 10, x = box.centerX() - (segW * 4 + gap * 3) / 2;
        for (int i = 0; i < 4; i++) {
            int d = v[i];
            if (d > 0 && d < CarState.SENSOR_NONE) {
                sensor.setColor(d <= 2 ? Style.BAD : d <= 5 ? Style.WARN : Style.GOOD);
                r.set(x, y - 6, x + segW, y + 6);
                c.drawRoundRect(r, 6, 6, sensor);
            }
            x += segW + gap;
        }
    }

    private void drawBadges(Canvas c) {
        String mode;
        switch (car.gear) {
            case CarState.GEAR_D: mode = "DRIVE"; break;
            case CarState.GEAR_R: mode = "REVERSE"; break;
            case CarState.GEAR_N: mode = "NEUTRAL"; break;
            case CarState.GEAR_P: mode = "PARKED"; break;
            default: mode = "WAITING FOR CAR DATA";
        }
        // no gear or Autopilot chips any more (the left panel and the road show both; the owner, 30 Oct):
        // only "waiting for car data", problems, open doors and demo data
        float x = box.left + 14 - 8;
        if (car.gear == CarState.GEAR_UNKNOWN) x = badge(c, x + 8, mode, Style.MUTED);
        String p = app.feedProblem();
        if (p != null) x = badge(c, x + 8, p.toUpperCase(), Style.BAD);   // before anything optional, so it is never pushed off
        // a drive logger must never run unnoticed (the owner, 31 Oct): always shown while it records,
        // kept short; an idle logger needs no chip (fewer chips, the owner, 1 Nov)
        if (app.oldLoggerInstalled()) x = badge(c, x + 8, "OLD LOGGER", Style.BAD);
        if (app.loggerRecording()) x = badge(c, x + 8, "● REC", Style.BAD);
        // open doors, boot or frunk: the parked car already shows them; the chip is only for the
        // driving view, or when the car render can't show them (none, or frames missing) (safety first)
        CarArt art = app.art();
        boolean onCar = blend == 0 && art != null && art.src != null && art.side != null && doorScene.canShow(car.doors);
        if (car.doors > 0 && !onCar) x = badge(c, x + 8, car.doorsText().toUpperCase(), Style.WARN);
        if (app.isDemo()) badge(c, x + 8, "DEMO DATA", Style.WARN);
    }

    private float badge(Canvas c, float x, String text, int col) {
        badgeText.setColor(col);
        Style.size(badgeText, Style.T_BADGE);
        float tw = badgeText.measureText(text);
        r.set(x, box.top + 12, x + tw + 24, box.top + 44);
        c.drawRoundRect(r, 12, 12, badgeBg);
        edge.setColor(Style.LINE);
        c.drawRoundRect(r, 12, 12, edge);
        c.drawText(text, x + 12, box.top + 34, badgeText);
        return r.right;
    }

    private void drawRaw(Canvas c) {
        float maxW = box.width() - 40;
        rawLines.clear();
        for (Map.Entry<Integer, Bundle> e : car.raw.entrySet()) {
            String line = "code " + e.getKey() + ": " + CarState.describe(e.getValue());
            while (!line.isEmpty()) {           // wrap long Bundles so no field is hidden
                int n = Math.min(line.length(), Math.max(8, rawP.breakText(line, true, maxW, null)));
                rawLines.add(line.substring(0, n));
                line = line.length() > n ? "    " + line.substring(n) : "";
            }
        }
        r.set(box.left + 10, box.top + 44, box.right - 10, box.top + 52 + 18 * (rawLines.size() + 1));
        c.drawRoundRect(r, 8, 8, badgeBg);
        float y = box.top + 60;
        for (String line : rawLines) {
            c.drawText(line, box.left + 20, y, rawP);
            y += 18;
        }
    }

    private final java.util.ArrayList<String> rawLines = new java.util.ArrayList<>();
}
