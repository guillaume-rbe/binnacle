package app.binnacle;

import android.content.Context;
import android.graphics.Bitmap;
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
    }

    static final int DOCK_PHONE = 0, DOCK_CAMERA = 1, DOCK_APPS = 2, DOCK_COUNT = 3;
    private static final String[] DOCK_LABELS = {"CarPlay", "Camera", "Apps"};
    private static final String[] TYRE_NAMES = {"FL", "FR", "RL", "RR"};
    private static final int[] DOCK_COLOURS = {0xff2ad15a, Style.WARN, Style.ACCENT};
    private static final float DOCK_TOP = 606, DOCK_BOTTOM = 708, ODO_TOP = 510, ODO_BOTTOM = 594, X0 = 24, X1 = 316;

    private final DashApp app;
    private final CarState car;
    private DockListener dockListener;
    private int focus = -1, pressed = -1;

    private final Paint line = Style.fill(Style.LINE);
    private final Paint tile = Style.fill(Style.PANEL);
    private final Paint tileEdge = Style.stroke(Style.LINE, 1.5f);
    private final Paint lbl = Style.label(Style.T_LABEL);
    private final Paint big = Style.text(Style.DISPLAY, Style.T_BATTERY, Style.FG, Paint.Align.RIGHT);
    private final Paint mid = Style.text(Style.DISPLAY, Style.T_TEMP, Style.FG, Paint.Align.LEFT);
    private final Paint psi = Style.text(Style.DISPLAY, Style.T_TYRE, Style.FG, Paint.Align.CENTER);
    private final Paint small = Style.text(Style.BODY, Style.T_SMALL, Style.MUTED, Paint.Align.LEFT);
    private final Paint pctP = Style.text(Style.DISPLAY, Style.T_PCT, Style.FG, Paint.Align.RIGHT);
    private final Paint tyreLbl = Style.text(Style.BODY, Style.T_TYRE_POS, Style.MUTED, Paint.Align.CENTER);
    private final Paint cellsEdge = Style.stroke(Style.LINE, 2);
    private final Paint fill = Style.fill(Style.GOOD);
    private final Paint dockBg = Style.fill(Style.PANEL2);
    private final Paint focusEdge = Style.stroke(Style.ACCENT, 3), litEdge = Style.stroke(Style.WARN, 2.5f);
    private final Paint iconText = Style.text(Style.DISPLAY, 15, 0xff04121c, Paint.Align.CENTER);
    private final Paint dockText = Style.text(Style.BODY_BOLD, Style.T_DOCK, Style.FG, Paint.Align.CENTER);
    private final Paint carStroke = Style.stroke(Style.MUTED, 2.5f);
    private final Paint bmp = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF r = new RectF(), icon = new RectF();
    private final Paint ink = Style.fill(0xff04121c);
    private final NumberFormat grouping = NumberFormat.getIntegerInstance(Locale.UK);

    public RightPanelView(Context c, DashApp app) {
        super(c);
        this.app = app;
        this.car = app.car;
        pinned = app.prefs().getInt("card_pinned", -1);
        if (pinned >= PAGES) pinned = -1;
    }

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

        drawBattery(c);
        drawMiddleCard(c);
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
        drawTile(c, 112, 248);
        // the car's % field (batterylevel) is stuck on this car (77 % all day); the % is decoded from
        // the code 11 jhmail field instead (BatteryDecoder, table recorded at a Supercharger)
        int soc = BatteryDecoder.percent(car.jhmail);
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
        c.drawRoundRect(r, 6, 6, cellsEdge);
        if (soc > 0) {
            float inner = r.width() - 8;
            fill.setColor(charging ? Style.ACCENT : soc <= 10 ? Style.BAD : soc <= 20 ? Style.WARN : Style.GOOD);
            r.set(r.left + 4, r.top + 4, r.left + 4 + inner * Math.min(100, soc) / 100f, r.bottom - 4);
            c.drawRoundRect(r, 3, 3, fill);
            if (charging) drawChargeSweep(c, r);
        }
        String line = null;
        if (estimated && soc >= 0) {
            line = "Estimated";                           // quiet (the owner): no colour, no time
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

    /** Tyre display unit: psi by default (what the car and the stock dash show), or bar. */
    private String tyreUnit() {
        return app.tyreBar() ? "bar" : "psi";
    }

    private int fullRangeKm() { return app.fullRangeKm(); }

    private void drawTyres(Canvas c) {
        float cx = (X0 + X1) / 2f, cy = (CARD_TOP + CARD_BOTTOM) / 2f - 6;   // no title (the owner): the unit goes under each value
        CarArt a = app.art();
        Bitmap b = a == null ? null : a.top;
        if (b != null) {
            float k = Math.min(140f / b.getWidth(), 250f / b.getHeight());
            float w = b.getWidth() * k, h = b.getHeight() * k;
            r.set(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2);
            c.drawBitmap(b, null, r, bmp);
        } else {
            Icons.carTop(c, cx, cy, 62, 140, carStroke);
        }
        tyre(c, 0, X0 + 46, 352);
        tyre(c, 1, X1 - 46, 352);
        tyre(c, 2, X0 + 46, 500);
        tyre(c, 3, X1 - 46, 500);
    }

    /**
     * Middle card, three pages left to right: parking sensors, tyres, efficiency. Automatic choice:
     * sensors whenever the car isn't driving (below 10 km/h, or speed not known yet: the dash nearly
     * always starts parked, and tyre pressures only arrive once driving), tyres while driving. A
     * swipe moves one page and holds until the car next drives off. Holding a finger on the card pins
     * the page for good instead, across drives and restarts, until it is held again (the owner, 3 Nov:
     * the switching can be distracting; no button, only a small pin while pinned). A swipe while
     * pinned moves the pin to the new page.
     */
    private int cardPage() {
        boolean driving = car.speedKmh() >= 10;
        if (driving && !wasDriving) manual = -1;       // driving OFF ends a manual choice; a swipe while driving holds
        wasDriving = driving;
        shownPage = pinned >= 0 ? pinned : manual >= 0 ? manual : driving ? PAGE_TYRES : PAGE_SENSORS;
        return shownPage;
    }

    /** Pinned page, or -1: automatic (saved in "card_pinned"). */
    private int pinned;
    private final Paint pinFill = Style.fill(Style.MUTED), pinStroke = Style.stroke(Style.MUTED, 2f);
    private static final long HOLD_MS = 600;
    /** Long press on the card: pin or unpin. Cancelled by a swipe or lifting early. */
    private final Runnable hold = new Runnable() {
        @Override public void run() {
            if (Float.isNaN(swipeX)) return;
            swipeX = Float.NaN;                           // this touch is used up: no swipe on release
            performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
            playSoundEffect(android.view.SoundEffectConstants.CLICK);
            manual = -1;
            setPinned(pinned >= 0 ? -1 : shownPage);
        }
    };

    private void setPinned(int page) {
        pinned = page;
        app.prefs().edit().putInt("card_pinned", page).apply();
        invalidate();
    }

    /** Only an indicator: a small, quiet pin while the page is pinned (the owner, 3 Nov: subtler, smaller). */
    private void drawPin(Canvas c) {
        if (pinned < 0) return;
        c.save();
        c.scale(PIN_SCALE, PIN_SCALE, PIN_X, PIN_Y);
        Icons.pin(c, PIN_X, PIN_Y, Style.MUTED, path, pinFill, pinStroke);
        c.restore();
    }

    static final int PAGE_SENSORS = 0, PAGE_TYRES = 1, PAGE_EFFICIENCY = 2, PAGES = 3;
    /** Swiped page, or -1 for automatic. */
    private int manual = -1;
    private boolean wasDriving;
    private int shownPage;
    private float swipeX = Float.NaN, swipeY;
    /** The current touch started on the middle card (swipe / hold), not the dock or icons. */
    private boolean cardTouch;
    private static final float CARD_TOP = 262, CARD_BOTTOM = 594, SWIPE_MIN = 40;
    private static final float PIN_X = X1 - 18, PIN_Y = CARD_TOP + 20, PIN_SCALE = 0.65f;

    /**
     * The middle card slides between its pages over CARD_SLIDE_MS per page when it switches, by
     * itself or by a swipe (the owner, 29 Oct).
     */
    private void drawMiddleCard(Canvas c) {
        int page = cardPage();
        long now = android.os.SystemClock.uptimeMillis();
        float goal = page;
        if (cardPos < 0) cardPos = goal;    // no slide on the first frame
        // the clock starts at the switch itself: the panel may not have drawn for minutes, and the
        // time since that last draw used to finish the slide in one frame (in-car: animated ~20%)
        if (!cardMoving) { cardLastMs = now; cardMoving = cardPos != goal; }
        float step = (now - cardLastMs) / (float) CARD_SLIDE_MS;
        cardLastMs = now;
        cardPos = cardPos < goal ? Math.min(goal, cardPos + step) : Math.max(goal, cardPos - step);
        drawTile(c, CARD_TOP, CARD_BOTTOM);
        float base = (float) Math.floor(cardPos), f = cardPos - base;
        float pos = base + f * f * (3 - 2 * f), width = X1 - X0;
        c.save();
        r.set(X0, CARD_TOP, X1, CARD_BOTTOM);
        c.clipRect(r);
        for (int p = 0; p < PAGES; p++) {
            float off = (p - pos) * width;
            if (Math.abs(off) >= width) continue;
            c.save();
            c.translate(off, 0);
            if (p == PAGE_SENSORS) drawSensorCard(c);
            else if (p == PAGE_TYRES) drawTyres(c);
            else drawEfficiency(c);
            c.restore();
        }
        c.restore();
        pageDots(c, page);
        drawPin(c);
        cardMoving = cardPos != goal;
        if (cardMoving) postInvalidateOnAnimation();
    }

    private static final long CARD_SLIDE_MS = 280;
    private float cardPos = -1;     // set on the first draw
    private long cardLastMs;
    private boolean cardMoving;

    /** Page dots at the bottom of the middle card: which page is showing. */
    private void pageDots(Canvas c, int page) {
        float cx = (X0 + X1) / 2f;
        for (int p = 0; p < PAGES; p++) {
            dot.setColor(p == page ? Style.FG : Style.DIM);
            c.drawCircle(cx + (p - (PAGES - 1) / 2f) * 14, CARD_BOTTOM - 12, 3.5f, dot);
        }
    }

    private final float[] graph = new float[EfficiencyTracker.BUCKETS];
    private final Paint bar = Style.fill(Style.WARN);
    private final Paint effBig = Style.text(Style.DISPLAY, Style.T_BATTERY, Style.FG, Paint.Align.CENTER);
    private final Paint centred = Style.text(Style.BODY, Style.T_SMALL, Style.MUTED, Paint.Align.CENTER);

    /**
     * Efficiency, kept to a glance (in-car, 1 Nov: too many words): the net Wh/km over the last
     * 5 km, a 15-minute strip of power (amber, up) and regen (green, down), and one line for this
     * drive's distance and average (EfficiencyTracker; power taken as kW).
     */
    private void drawEfficiency(Canvas c) {
        EfficiencyTracker eff = app.efficiency();
        float cx = (X0 + X1) / 2f;
        String per = "Wh/" + Units.distLabel(app);
        float recent = eff.recentWhPerKm();
        Style.size(effBig, Style.T_BATTERY);
        c.drawText(Float.isNaN(recent) ? "--" : String.valueOf(Math.round(Units.perDistance(app, recent))), cx, CARD_TOP + 58, effBig);
        Style.size(centred, Style.T_SMALL);
        centred.setColor(Style.MUTED);
        // "est." while the car's power reading is unusable and the numbers come from speed alone
        c.drawText(app.car.powerEstimated() ? per + " · est." : per, cx, CARD_TOP + 84, centred);

        // graph: zero line low in the middle, power up in amber, regen down in green, 15 min wide
        float gTop = CARD_TOP + 104, gBottom = CARD_TOP + 250, zero = gTop + (gBottom - gTop) * 0.62f;
        float gx0 = X0 + 16, gx1 = X1 - 16;
        c.drawRect(gx0, zero - 0.75f, gx1, zero + 0.75f, line);
        // a slot per 10 s of the last 15 min up to now (same clock as the feed), newest on the right
        eff.graph(android.os.SystemClock.elapsedRealtime(), graph);
        float bw = (gx1 - gx0) / EfficiencyTracker.BUCKETS;
        for (int k = 0; k < EfficiencyTracker.BUCKETS; k++) {
            float kw = graph[k], x = gx0 + k * bw;
            if (kw == 0) continue;
            if (kw > 0) {
                float h = Math.min(1f, kw / GRAPH_MAX_KW) * (zero - gTop);
                bar.setColor(Style.WARN);
                c.drawRect(x, zero - h, x + bw - 1, zero, bar);
            } else {
                float h = Math.min(1f, -kw / GRAPH_MAX_REGEN_KW) * (gBottom - zero);
                bar.setColor(Style.GOOD);
                c.drawRect(x, zero, x + bw - 1, zero + h, bar);
            }
        }

        double km = eff.tripKm();
        float trip = eff.tripWhPerKm();
        String drive = km < 0.05 ? "--"
                : String.format(Locale.UK, "%.1f %s · %s avg", Units.distance(app, km), Units.distLabel(app),
                        Float.isNaN(trip) ? "--" : Math.round(Units.perDistance(app, trip)) + " " + per);
        centred.setColor(Style.FG);
        Style.fit(centred, drive, X1 - X0 - 24);
        c.drawText(drive, cx, gBottom + 36, centred);
        centred.setColor(Style.MUTED);
    }

    /** Graph full scale: power and regen of this size fill their half (taller bars are capped). */
    private static final float GRAPH_MAX_KW = 80, GRAPH_MAX_REGEN_KW = 50;

    private final Paint arc = new Paint(Paint.ANTI_ALIAS_FLAG), dot = new Paint(Paint.ANTI_ALIAS_FLAG);
    { arc.setStyle(Paint.Style.STROKE); arc.setStrokeCap(Paint.Cap.ROUND); arc.setStrokeWidth(6); }
    private static final float[] FRONT = {200, 237, 273, 310}, REAR = {125, 88, 52, 15};

    /** Top-down car with every sensor's arcs around it: front above, rear below. */
    private void drawSensorCard(Canvas c) {
        float cx = (X0 + X1) / 2f, cy = (CARD_TOP + CARD_BOTTOM) / 2f - 6;   // no title: the graphic gets the card
        CarArt a = app.art();
        Bitmap b = a == null ? null : a.top;
        float w = 80, h = 168;
        if (b != null) {
            float k = Math.min(90f / b.getWidth(), 168f / b.getHeight());
            w = b.getWidth() * k; h = b.getHeight() * k;
            r.set(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2);
            c.drawBitmap(b, null, r, bmp);
        } else {
            Icons.carTop(c, cx, cy, w, h, carStroke);
        }
        float rx0 = w * 0.95f, ry0 = h * 0.6f;
        int closest = CarState.SENSOR_NONE;
        for (int side = 0; side < 2; side++) {
            int[] v = side == 0 ? car.front : car.rear;
            float[] starts = side == 0 ? FRONT : REAR;
            for (int i = 0; i < 4; i++) {
                int d = v[i];
                boolean seen = d > 0 && d < CarState.SENSOR_NONE;
                if (seen) closest = Math.min(closest, d);
                int lit = !seen ? -1 : d <= 2 ? 0 : d <= 4 ? 1 : d <= 7 ? 2 : 3;
                int col = !seen ? Style.MUTED : d <= 2 ? Style.BAD : d <= 4 ? 0xffff8a3d : d <= 7 ? Style.WARN : Style.GOOD;
                int idx = i;   // feed order is left to right; seen from above (nose up) left is left at both ends
                for (int band = 0; band < 4; band++) {
                    float rx = rx0 + 12 * band, ry = ry0 + 12 * band;
                    r.set(cx - rx, cy - ry, cx + rx, cy + ry);
                    arc.setColor(col);
                    arc.setAlpha(!seen || band < lit ? 40 : band == lit ? 255 : 255 * 2 / (band - lit + 3));
                    c.drawArc(r, starts[idx], 30, false, arc);
                }
            }
        }
    }

    private void tyre(Canvas c, int i, float x, float y) {
        String v = car.tyre(i, tyreUnit());
        String text = v == null ? "--" : v;
        psi.setColor(v == null ? Style.DIM : Style.FG);
        Style.size(psi, Style.T_TYRE);
        Style.fit(psi, text, 86);
        c.drawText(text, x, y, psi);
        Style.size(tyreLbl, Style.T_TYRE_POS);
        c.drawText(TYRE_NAMES[i] + " · " + tyreUnit(), x, y + 22, tyreLbl);
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
        invalidate();
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
        int col = net == NET_NONE ? Style.DIM : Style.MUTED;
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
        return netIconX > 0 && Math.abs(x / s - netIconX) < 26 && Math.abs(y / s - 86) < 26;
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
            } else {
                c.drawRoundRect(r, 12, 12, i == focus ? focusEdge : tileEdge);
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
            c.drawText("CP", cx, cy + 5.5f, iconText);
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

    /** Horizontal swipe on the middle card moves one page (left = next). True while it owns the gesture. */
    private boolean swipe(MotionEvent e) {
        float s = getHeight() / Style.DESIGN_H, x = e.getX() / s, y = e.getY() / s;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                cardTouch = !(y < CARD_TOP || y > CARD_BOTTOM || x < X0 || x > X1);
                if (!cardTouch) { swipeX = Float.NaN; return false; }
                swipeX = x; swipeY = y;
                postDelayed(hold, HOLD_MS);
                return true;
            case MotionEvent.ACTION_MOVE:
                if (!cardTouch) return false;
                if (!Float.isNaN(swipeX) && Math.hypot(x - swipeX, y - swipeY) > SWIPE_MIN / 2) removeCallbacks(hold);
                return true;                                  // a used-up hold keeps the gesture to the end
            case MotionEvent.ACTION_UP:
                removeCallbacks(hold);
                if (!cardTouch) return false;
                cardTouch = false;
                if (Float.isNaN(swipeX)) return true;
                if (Math.abs(x - swipeX) > SWIPE_MIN && Math.abs(x - swipeX) > Math.abs(y - swipeY)) {
                    int next = Math.max(0, Math.min(PAGES - 1, shownPage + (x < swipeX ? 1 : -1)));
                    if (pinned >= 0) setPinned(next);               // the pin moves with the page
                    else manual = next;                             // held until driving off
                    invalidate();
                }
                swipeX = Float.NaN;
                return true;
            case MotionEvent.ACTION_CANCEL:
                removeCallbacks(hold);
                swipeX = Float.NaN;
                boolean was = cardTouch;
                cardTouch = false;
                return was;
        }
        return false;
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
        if (swipe(e)) return true;
        if (e.getActionMasked() == MotionEvent.ACTION_UP && hitNetIcon(e.getX(), e.getY()) && dockListener != null) {
            playSoundEffect(android.view.SoundEffectConstants.CLICK);
            dockListener.onNetIcon();
            return true;
        }
        if (e.getActionMasked() == MotionEvent.ACTION_DOWN && hitNetIcon(e.getX(), e.getY())) return true;
        int hit = hitDock(e.getX(), e.getY());
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                pressed = hit;
                invalidate();
                return hit >= 0;
            case MotionEvent.ACTION_MOVE:
                if (hit != pressed) { pressed = -1; invalidate(); }
                return true;
            case MotionEvent.ACTION_UP:
                if (hit >= 0 && hit == pressed && dockListener != null) {
                    playSoundEffect(android.view.SoundEffectConstants.CLICK);
                    dockListener.onDock(hit);
                }
                pressed = -1;
                invalidate();
                return true;
            case MotionEvent.ACTION_CANCEL:
                pressed = -1;
                invalidate();
                return true;
        }
        return super.onTouchEvent(e);
    }
}
