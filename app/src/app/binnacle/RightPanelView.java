package app.binnacle;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.MotionEvent;
import android.view.View;

import java.text.NumberFormat;
import java.util.Locale;

/**
 * Right panel: outside temp, battery % with estimated range, tyre pressures, odometer
 * and the app dock (CarPlay via xcertplay or ZLink, camera, apps). Drawn in 340x720 design pixels (mockup's .side.r).
 * The dock is navigable with the steering wheel via {@link #setFocusIndex}.
 */
public class RightPanelView extends View {

    interface DockListener {
        void onDock(int index);
        /** The small network icon was tapped. */
        void onNetIcon();
        /** A dock button held (~0.6 s): CarPlay's hides CarPlay and keeps it running. */
        void onDockHold(int index);
    }

    static final int DOCK_PHONE = 0, DOCK_CAMERA = 1, DOCK_APPS = 2, DOCK_COUNT = 3;
    private static final String[] DOCK_LABELS = {"CarPlay", "Camera", "Apps"};
    private static final int[] DOCK_COLOURS = {0xff2ad15a, Style.WARN, Style.ACCENT};
    private static final float DOCK_TOP = 606, DOCK_BOTTOM = 708, ODO_TOP = 510, ODO_BOTTOM = 594, X0 = 24, X1 = 316;

    private final DashApp app;
    private final CarState car;
    private DockListener dockListener;
    private int focus = -1, pressed = -1;

    private final Paint line = Style.fill(Style.LINE);
    private final Paint tile = Style.fill(Style.PANEL);
    private final Paint tileEdge = Style.stroke(Style.LINE, 1.5f);
    private final Paint noteFill = Style.fill(Style.PANEL);
    private final Paint lbl = Style.label(Style.T_LABEL);
    private final Paint big = Style.text(Style.DISPLAY, Style.T_BATTERY, Style.FG, Paint.Align.RIGHT);
    private final Paint mid = Style.text(Style.DISPLAY, Style.T_TEMP, Style.FG, Paint.Align.LEFT);
    private final Paint small = Style.text(Style.BODY, Style.T_SMALL, Style.MUTED, Paint.Align.LEFT);
    private final Paint pctP = Style.text(Style.DISPLAY, Style.T_PCT, Style.FG, Paint.Align.RIGHT);
    private final Paint cellsEdge = Style.stroke(Style.LINE, 2);
    private final Paint fill = Style.fill(Style.GOOD);
    private final Paint dockBg = Style.fill(Style.PANEL2);
    private final Paint focusEdge = Style.stroke(Style.ACCENT, 3), litEdge = Style.stroke(Style.WARN, 2.5f);
    private final Paint dockText = Style.text(Style.BODY_BOLD, Style.T_DOCK, Style.FG, Paint.Align.CENTER);
    private final Path path = new Path();
    private final RectF r = new RectF(), icon = new RectF();
    private final Paint ink = Style.fill(0xff04121c);
    /** The CarPlay icon: an open ring (round ends) and a play triangle with softened corners. */
    private final Paint inkRing = Style.stroke(0xff04121c, 3f), inkSoft = Style.stroke(0xff04121c, 1.6f);
    private final RectF ring = new RectF();
    { inkSoft.setStyle(Paint.Style.FILL_AND_STROKE); }      // the round join softens the corners
    private final NumberFormat grouping = NumberFormat.getIntegerInstance(Locale.UK);

    public RightPanelView(Context c, DashApp app) {
        super(c);
        this.app = app;
        this.car = app.car;
        card = new SwipeCard(this, app, false, PIN_KEY);
        if (!app.prefs().contains(PIN_KEY) && app.prefs().contains("card_pinned")) {
            // the old order was sensors, tyres, efficiency
            int old = app.prefs().getInt("card_pinned", -1);
            int pinned = old == 0 ? SwipeCard.PAGE_SENSORS : old == 1 ? SwipeCard.PAGE_TYRES
                    : old == 2 ? SwipeCard.PAGE_EFFICIENCY : old == 3 ? SwipeCard.PAGE_MEDIA : -1;
            app.prefs().edit().putInt(PIN_KEY, pinned).remove("card_pinned").apply();
            card.restorePin(pinned);
        }
    }

    /** The middle card (tyres, efficiency, sensors, Now playing), shared out with the left column's. */
    final SwipeCard card;
    /** Saved pin of the middle card (the left card has its own). */
    private static final String PIN_KEY = "card_pinned_v2";

    void setDockListener(DockListener l) { dockListener = l; }

    void setFocusIndex(int i) {
        focus = i;
        invalidate();
    }

    @Override protected void onDraw(Canvas c) {
        float s = getHeight() / Style.DESIGN_H;
        c.save();
        c.scale(s, s);
        c.drawRect(0, 0, 1, Style.DESIGN_H, line);

        // outside temperature (left aligned: the top-right corner is behind the steering wheel)
        String t = Units.temperature(app, car.outsideTemp);
        Style.size(mid, Style.T_TEMP);
        c.drawText(t, X0, 96, mid);
        Style.size(lbl, Style.T_LABEL);
        lbl.setTextAlign(Paint.Align.LEFT);
        c.drawText("OUTSIDE", X0 + mid.measureText(t) + 12, 93, lbl);
        netIconX = X1 - 30; // right edge lines up with the battery % below (icon is ~15 px either side)
        drawNetIcon(c, netIconX, 86);
        drawLoggerEye(c, netIconX - 42, 86);
        drawNetNote(c);

        drawBattery(c);
        card.draw(c);   // in its own frame: the card was designed here
        // odometer moved to a line at the bottom of the centre window (the owner): the card gets its space
        drawDock(c);
        c.restore();
    }

    private void drawTile(Canvas c, float top, float bottom) {
        r.set(X0, top, X1, bottom);
        c.drawRoundRect(r, 14, 14, tile);
        c.drawRoundRect(r, 14, 14, tileEdge);
    }

    private void drawBattery(Canvas c) {
        // free column (design 2a, 15 Nov): no tile, only a hairline where its bottom edge was
        c.drawRect(X0, 248 - 0.75f, X1, 248 + 0.75f, line);
        // the car's % field (batterylevel) is stuck on some cars (77 % all day on the owner's); there
        // the % is decoded from the code 11 jhmail field (BatteryDecoder, table recorded at a
        // Supercharger). Once batterylevel has been seen to change it is used instead.
        boolean moves = levelMoves();
        int soc = BatteryDecoder.pick(BatteryDecoder.percent(car.jhmail), car.soc, moves);
        // no live % yet (just woke, or a value off the table): show the last one, marked estimated
        boolean estimated = soc < 0;
        if (estimated) soc = lastSoc();
        else if (!app.isDemo()) rememberSoc(soc);
        boolean charging = car.charging;
        Style.size(lbl, Style.T_LABEL);
        lbl.setColor(charging ? Style.ACCENT : Style.MUTED);
        c.drawText(charging ? "CHARGING" : "BATTERY", X0 + 15, 146, lbl);
        lbl.setColor(Style.MUTED);
        String pct = soc < 0 ? "--" : String.valueOf(soc);
        Style.size(pctP, Style.T_PCT);
        float pw = soc < 0 ? 0 : pctP.measureText("%");
        if (soc >= 0) c.drawText("%", X1 - 15, 160, pctP);
        Style.size(big, Style.T_BATTERY);
        Style.fit(big, pct, X1 - 15 - pw - (X0 + 15 + lbl.measureText("BATTERY") + 10));
        c.drawText(pct, X1 - 15 - pw, 160, big);

        r.set(X0 + 15, 174, X1 - 15, 202);
        cellsEdge.setColor(Style.light ? 0xffa9b3bf : Style.LINE);   // without the tile, light needs a firmer edge
        c.drawRoundRect(r, 6, 6, cellsEdge);
        if (soc > 0) {
            float inner = r.width() - 8;
            fill.setColor(charging ? Style.ACCENT : soc <= 10 ? Style.BAD : soc <= 20 ? Style.WARN : Style.GOOD);
            r.set(r.left + 4, r.top + 4, r.left + 4 + inner * Math.min(100, soc) / 100f, r.bottom - 4);
            c.drawRoundRect(r, 3, 3, fill);
            if (charging) drawChargeSweep(c, r);
        }
        String line = null;
        int carRange;
        if (estimated && soc >= 0) {
            line = "Estimated";                           // quiet (the owner): no colour, no time
        } else if (soc >= 0 && (carRange = carRange(moves)) >= 0) {
            // the car's own range where jhmail carries it (issue #91), in place of the estimate
            line = Units.carDistance(app, carRange) + " " + Units.distLabel(app);
        } else if (soc >= 0) {
            int full = fullRangeKm();
            if (full > 0) line = "≈ " + Math.round(Units.distance(app, full * soc / 100.0)) + " " + Units.distLabel(app) + " est.";
        }
        if (line != null) {
            Style.size(small, Style.T_SMALL);
            Style.fit(small, line, X1 - X0 - 30);
            small.setColor(charging ? Style.ACCENT : Style.MUTED);
            c.drawText(line, X0 + 15, 232, small);
            small.setColor(Style.MUTED);
        }
    }

    /**
     * Charging: a soft band of light sweeps along the filled part of the bar, start to tip, every
     * CHARGE_SWEEP_MS. Only that it is charging and the % are shown (the owner): the car sends no charge
     * power or time to full, and estimates from the % alone weren't good enough to show.
     */
    private void drawChargeSweep(Canvas c, RectF bar) {
        long now = android.os.SystemClock.uptimeMillis();
        float t = (now % CHARGE_SWEEP_MS) / (float) CHARGE_SWEEP_MS;
        float band = Math.max(24, bar.width() * 0.35f);
        float x = bar.left - band + t * (bar.width() + band);
        if (sweepShader == null || sweepWidth != band) {
            sweepWidth = band;
            sweepShader = new LinearGradient(0, 0, band, 0, new int[]{0x00ffffff, 0x99ffffff, 0x00ffffff},
                    null, Shader.TileMode.CLAMP);
            sweep.setShader(sweepShader);
        }
        c.save();
        c.clipRect(bar);
        c.translate(x, 0);
        c.drawRect(0, bar.top, band, bar.bottom, sweep);
        c.restore();
        postInvalidateDelayed(33);                     // ~30 fps, only while charging
    }

    private static final long CHARGE_SWEEP_MS = 1600;
    private final Paint sweep = new Paint(Paint.ANTI_ALIAS_FLAG);
    private LinearGradient sweepShader;
    private float sweepWidth;

    private static final String LAST_SOC = "last_soc", LAST_SOC_TIME = "last_soc_time";
    private static final String SOC_FIELD_MOVES = "soc_field_moves";

    /** Watches the car's own % field; once it has changed, it is trusted from then on (saved). */
    private final BatteryDecoder.LevelWatch levelWatch = new BatteryDecoder.LevelWatch();
    private boolean levelChecked;

    private boolean levelMoves() {
        if (!levelChecked) {
            levelWatch.moved = app.prefs().getBoolean(SOC_FIELD_MOVES, false);
            levelChecked = true;
        }
        if (levelWatch.moved || app.isDemo()) return levelWatch.moved;
        // jhmail is a range that fits batterylevel (2-8 km per %): the % field is live on this car,
        // no need to wait to see it change, which can take a whole drive (issue #91, 17 Nov). Never
        // true on the owner's car: its stuck 77 % would need a jhmail of 154-616, and no % code is
        // in that range.
        boolean rangeFits = BatteryDecoder.rangeFromCar(car.jhmail, car.soc, true, Units.carMiles(car)) > 0;
        if (rangeFits) levelWatch.moved = true;
        if (rangeFits || levelWatch.see(car.soc, android.os.SystemClock.elapsedRealtime())) {
            app.prefs().edit().putBoolean(SOC_FIELD_MOVES, true).apply();
        }
        return levelWatch.moved;
    }

    /** Keeps the last live % (and when) for after a sleep; saved when it changes, else once a minute. */
    private void rememberSoc(int soc) {
        long now = System.currentTimeMillis();
        if (savedSoc == soc && now - savedSocAt < 60_000) return;   // checked in memory: this runs every frame
        savedSoc = soc;
        savedSocAt = now;
        app.prefs().edit().putInt(LAST_SOC, soc).putLong(LAST_SOC_TIME, now).apply();
    }

    /** The last % saved, read from settings once and then kept in memory. */
    private int savedSoc = Integer.MIN_VALUE;
    private long savedSocAt;

    private int lastSoc() {
        if (savedSoc == Integer.MIN_VALUE) {
            savedSoc = app.prefs().getInt(LAST_SOC, -1);
            savedSocAt = app.prefs().getLong(LAST_SOC_TIME, 0);
        }
        return savedSoc;
    }

    private int fullRangeKm() { return app.fullRangeKm(); }

    /** jhmail is the car's range on this car (seen once since the dash started): kept from then on. */
    private boolean jhmailIsRange;

    /** The car's own range (car's unit), or -1 where jhmail isn't one (BatteryDecoder.rangeFromCar). */
    private int carRange(boolean moves) {
        if (!jhmailIsRange) jhmailIsRange = BatteryDecoder.rangeFromCar(car.jhmail, car.soc, moves, Units.carMiles(car)) > 0;
        return jhmailIsRange ? BatteryDecoder.rangeKept(car.jhmail, moves) : -1;
    }

    private void drawOdometer(Canvas c) {
        drawTile(c, ODO_TOP, ODO_BOTTOM);
        Style.size(lbl, Style.T_LABEL);
        float labelY = ODO_TOP + 30;
        c.drawText("ODOMETER", X0 + 15, labelY, lbl);
        String odo = car.odometer < 0 ? "--" : grouping.format(car.odometer);
        Style.size(mid, Style.T_ODO);
        float valueY = Math.min(ODO_BOTTOM - 12, labelY + mid.getTextSize() * 0.92f + 8);   // follows the text size
        c.drawText(odo, X0 + 15, valueY, mid);
        Style.size(small, Style.T_ODO_UNIT);
        c.drawText(" " + car.distUnit, X0 + 15 + mid.measureText(odo), valueY, small);
    }

    /** Network: 0 none, 1 home Wi-Fi, 2 hotspot (CarPlay's access point). */
    static final int NET_NONE = 0, NET_WIFI = 1, NET_HOTSPOT = 2;
    private int net = NET_NONE;
    private float netIconX = -1;
    private final Paint netPaint = Style.stroke(Style.MUTED, 2.5f), netDot = Style.fill(Style.MUTED);

    void setNet(int mode) {
        if (mode == net) return;
        net = mode;
        if (mode == NET_WIFI && netBusy) netNote("On home Wi-Fi", false);
        invalidate();
    }

    /**
     * What a tap on the Wi-Fi icon is doing, shown in a pill beside it (owner, 8 Nov: with only a
     * toast there was no sign the tap had done anything). A busy note (joining) lights the icon and
     * stays until home Wi-Fi joins or NOTE_BUSY_MS passes; any other note shows for NOTE_MS. A join
     * that times out says so (owner, 10 Nov: a tap away from home showed "Joining home Wi-Fi…" on
     * the motorway, with nothing after it to say there was no home Wi-Fi to join).
     */
    private static final long NOTE_MS = 4000, NOTE_BUSY_MS = 20_000;
    private String netNote;
    private boolean netBusy, netPressed, netAsking;
    private final Runnable clearNetNote = new Runnable() {
        @Override public void run() {
            if (netBusy) { netNote("Home Wi-Fi not in range", false); return; }
            netNote = null;
            netAsking = false;
            invalidate();
        }
    };

    /**
     * A question for a second tap, e.g. "Join home Wi-Fi?" (owner, 10 Nov: one stray tap started a
     * join with no way back). It goes after NOTE_MS, and the tap is forgotten with it.
     */
    void netAsk(String text) {
        netNote(text, false);
        netAsking = true;
    }

    /** The question is showing: this tap answers it. */
    boolean netAsking() { return netAsking && netNote != null; }

    /** Takes the note away now. */
    void netClear() {
        removeCallbacks(clearNetNote);
        netNote = null;
        netBusy = netAsking = false;
        invalidate();
    }

    /** A join is under way: this tap cancels it. */
    boolean netBusy() { return netBusy && netNote != null; }

    void netNote(String text, boolean busy) {
        // the join can finish before the shell command reports back: already on Wi-Fi, so say so
        // (a tap while on Wi-Fi never starts a join, so a busy note here is always a late one)
        if (busy && net == NET_WIFI) { text = "On home Wi-Fi"; busy = false; }
        netNote = text;
        netBusy = busy;
        netAsking = false;
        removeCallbacks(clearNetNote);
        postDelayed(clearNetNote, busy ? NOTE_BUSY_MS : NOTE_MS);
        invalidate();
    }

    private void drawNetNote(Canvas c) {
        if (netNote == null) return;
        // over the outside temperature for a few seconds: the row has no other free space
        r.set(X0, 64, netIconX - 24, 108);
        // no outline (owner, 10 Nov: the blue border was too much): the tile, a touch of the
        // accent while it asks or looks
        noteFill.setColor(netBusy || netAsking ? CentreView.lerpColor(Style.PANEL, Style.ACCENT, 0.14f) : Style.PANEL);
        c.drawRoundRect(r, 12, 12, noteFill);
        Style.size(lbl, Style.T_LABEL);
        lbl.setTextAlign(Paint.Align.LEFT);
        lbl.setColor(netBusy ? Style.ACCENT : Style.FG);
        Style.fit(lbl, netNote, r.width() - 28);
        c.drawText(netNote, X0 + 14, 92, lbl);
        lbl.setColor(Style.MUTED);
    }

    /** Wi-Fi fan, or a hotspot (dot with rings), or a dim fan when neither; tap for the switch prompt. */
    private final Paint eyeLine = Style.stroke(Style.WARN, 2.5f), eyeFill = Style.fill(Style.WARN);
    private final Path eyePath = new Path();

    /**
     * An eye beside the Wi-Fi icon whenever a drive logger is installed: amber while installed,
     * red while it records or if the old location-recording logger is present (the owner, 31 Oct:
     * it must never run unnoticed). Nothing is drawn when no logger is installed.
     */
    private void drawLoggerEye(Canvas c, float cx, float cy) {
        if (!app.loggerInstalled() && !app.oldLoggerInstalled()) return;
        int col = app.loggerRecording() || app.oldLoggerInstalled() ? Style.BAD : Style.WARN;
        eyeLine.setColor(col);
        eyeFill.setColor(col);
        eyePath.reset();
        eyePath.moveTo(cx - 15, cy);
        eyePath.quadTo(cx, cy - 15, cx + 15, cy);
        eyePath.quadTo(cx, cy + 15, cx - 15, cy);
        eyePath.close();
        c.drawPath(eyePath, eyeLine);
        c.drawCircle(cx, cy, 4.5f, eyeFill);
    }

    private void drawNetIcon(Canvas c, float cx, float cy) {
        if (netPressed) {   // the finger is on it
            netDot.setColor(Style.LINE);
            c.drawCircle(cx, cy, 24, netDot);
        }
        int col = netBusy ? Style.ACCENT : net == NET_NONE ? Style.DIM : Style.MUTED;
        netPaint.setColor(col);
        netDot.setColor(col);
        if (net == NET_HOTSPOT) {
            c.drawCircle(cx, cy, 3, netDot);
            r.set(cx - 8, cy - 8, cx + 8, cy + 8);
            c.drawArc(r, 200, 140, false, netPaint);
            c.drawArc(r, 20, 140, false, netPaint);
            r.set(cx - 14, cy - 14, cx + 14, cy + 14);
            c.drawArc(r, 205, 130, false, netPaint);
            c.drawArc(r, 25, 130, false, netPaint);
        } else {
            c.drawCircle(cx, cy + 6, 3, netDot);
            for (int i = 1; i <= 3; i++) {
                float rr = 6 + i * 5;
                r.set(cx - rr, cy + 6 - rr, cx + rr, cy + 6 + rr);
                c.drawArc(r, 225, 90, false, netPaint);
            }
        }
    }

    private boolean hitNetIcon(float x, float y) {
        float s = getHeight() / Style.DESIGN_H;
        if (netIconX <= 0) return false;
        float dx = x / s, dy = y / s;
        if (Math.abs(dx - netIconX) < 26 && Math.abs(dy - 86) < 26) return true;
        // the note beside it answers too, while it asks or looks: that's where the eye is
        return (netAsking() || netBusy()) && dx >= X0 && dx <= netIconX - 24 && dy >= 64 && dy <= 108;
    }

    private void dockRect(int i, RectF out) {
        float gap = 8, w = (X1 - X0 - gap * (DOCK_COUNT - 1)) / DOCK_COUNT;
        float x = X0 + i * (w + gap);
        out.set(x, DOCK_TOP, x + w, DOCK_BOTTOM);
    }

    /** Camera open (or opening): its dock tile shows a lit "on" state, and tapping it closes the camera. */
    private boolean cameraOn;

    void setCameraOn(boolean on) {
        if (on == cameraOn) return;
        cameraOn = on;
        invalidate();
    }

    private void drawDock(Canvas c) {
        for (int i = 0; i < DOCK_COUNT; i++) {
            dockRect(i, r);
            boolean lit = i == DOCK_CAMERA && cameraOn;
            dockBg.setColor(i == pressed ? Style.LINE : lit ? (DOCK_COLOURS[i] & 0x00ffffff) | 0x40000000 : Style.PANEL2);
            c.drawRoundRect(r, 12, 12, dockBg);
            if (lit && i != focus) {
                litEdge.setColor(DOCK_COLOURS[i]);
                c.drawRoundRect(r, 12, 12, litEdge);
            } else if (i == focus) {
                c.drawRoundRect(r, 12, 12, focusEdge);       // no border otherwise (design 2a, 15 Nov)
            }
            float cx = r.centerX(), iy = r.top + 36, bottom = r.bottom, w = r.width();
            icon.set(cx - 21, iy - 21, cx + 21, iy + 21);
            dockBg.setColor(DOCK_COLOURS[i]);
            c.drawRoundRect(icon, 11, 11, dockBg);
            drawDockIcon(c, i, cx, iy);
            Style.size(dockText, Style.T_DOCK);
            String label = lit ? "Close" : DOCK_LABELS[i];   // says what a tap does now
            Style.fit(dockText, label, w - 10);
            c.drawText(label, cx, bottom - 14, dockText);
        }
    }

    private void drawDockIcon(Canvas c, int i, float cx, float cy) {
        if (i == DOCK_PHONE) {
            // a ring open on the right (from 25 to 335 degrees) round a play triangle (Simon, 15 Nov)
            ring.set(cx - 12, cy - 12, cx + 12, cy + 12);
            c.drawArc(ring, 25, 310, false, inkRing);
            path.reset();
            path.moveTo(cx - 3.8f, cy - 6.6f);
            path.lineTo(cx + 8f, cy);
            path.lineTo(cx - 3.8f, cy + 6.6f);
            path.close();
            c.drawPath(path, inkSoft);
        } else if (i == DOCK_CAMERA) {
            r.set(cx - 12, cy - 8, cx + 5, cy + 8);
            c.drawRoundRect(r, 3, 3, ink);
            path.reset();
            path.moveTo(cx + 6, cy);
            path.lineTo(cx + 13, cy - 6);
            path.lineTo(cx + 13, cy + 6);
            path.close();
            c.drawPath(path, ink);
        } else {
            for (int a = -1; a <= 1; a++) for (int b = -1; b <= 1; b++) c.drawCircle(cx + a * 8, cy + b * 8, 2.6f, ink);
        }
    }

    private int hitDock(float x, float y) {
        float s = getHeight() / Style.DESIGN_H;
        for (int i = 0; i < DOCK_COUNT; i++) {
            dockRect(i, r);
            if (r.contains(x / s, y / s)) return i;
        }
        return -1;
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        float s = getHeight() / Style.DESIGN_H;
        if (card.touch(e.getActionMasked(), e.getX() / s, e.getY() / s)) return true;
        if (netPressed) {
            boolean on = hitNetIcon(e.getX(), e.getY());
            int a = e.getActionMasked();
            if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL || !on) {
                netPressed = false;
                invalidate();
                if (a == MotionEvent.ACTION_UP && on && dockListener != null) {
                    playSoundEffect(android.view.SoundEffectConstants.CLICK);
                    performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY);
                    dockListener.onNetIcon();
                }
            }
            return true;
        }
        if (e.getActionMasked() == MotionEvent.ACTION_DOWN && hitNetIcon(e.getX(), e.getY())) {
            netPressed = true;
            invalidate();
            return true;
        }
        int hit = hitDock(e.getX(), e.getY());
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                pressed = hit;
                held = false;
                removeCallbacks(dockHold);
                if (hit >= 0) postDelayed(dockHold, HOLD_MS);
                invalidate();
                return hit >= 0;
            case MotionEvent.ACTION_MOVE:
                if (hit != pressed) { pressed = -1; removeCallbacks(dockHold); invalidate(); }
                return true;
            case MotionEvent.ACTION_UP:
                removeCallbacks(dockHold);
                if (held) { held = false; pressed = -1; invalidate(); return true; }   // the hold already acted
                if (hit >= 0 && hit == pressed && dockListener != null) {
                    playSoundEffect(android.view.SoundEffectConstants.CLICK);
                    dockListener.onDock(hit);
                }
                pressed = -1;
                invalidate();
                return true;
            case MotionEvent.ACTION_CANCEL:
                removeCallbacks(dockHold);
                held = false;
                pressed = -1;
                invalidate();
                return true;
        }
        return super.onTouchEvent(e);
    }

    private static final long HOLD_MS = 600;

    /** A dock button held: acts once while still held (owner, 5 Nov: hold CarPlay to hide it). */
    private boolean held;
    private final Runnable dockHold = new Runnable() {
        @Override public void run() {
            if (pressed < 0 || dockListener == null) return;
            held = true;
            performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
            playSoundEffect(android.view.SoundEffectConstants.CLICK);
            dockListener.onDockHold(pressed);
        }
    };
}
