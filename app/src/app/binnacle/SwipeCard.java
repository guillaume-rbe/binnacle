package app.binnacle;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;

import java.util.Locale;

/**
 * The swipe card: tyres, efficiency, parking sensors, and Now playing while CarPlay plays. The
 * right column always has one; the left column has a second one while the big dial is in the
 * centre (the owner, 15 Nov). A page never shows on both at once: while both cards are up, the
 * one chosen less recently (by a swipe or a pin) steps on to the next free page.
 *
 * Drawn in the right column's card frame (X0..X1 x CARD_TOP..CARD_BOTTOM, design px); the host
 * maps that frame onto its own rect (Canvas translate and scale) and hands touches over in the
 * same frame.
 *
 * Pages left to right: tyres, efficiency, parking sensors, Now playing (owner, 5 Nov: sensors next
 * to Now playing, so switching between the two doesn't slide past the others). Automatic choice on
 * the right: sensors whenever the car isn't driving (below 10 km/h, or speed not known yet: the
 * dash nearly always starts parked, and tyre pressures only arrive once driving), tyres while
 * driving; on the left: Now playing while CarPlay plays, efficiency otherwise. A swipe moves one
 * page and holds until the car next drives off. Holding a finger on the card pins the page for
 * good instead, across drives and restarts, until it is held again (the owner, 3 Nov: the
 * switching can be distracting; no button, only a small pin while pinned). A swipe while pinned
 * moves the pin to the new page.
 */
final class SwipeCard {

    static final float X0 = 24, X1 = 316, CARD_TOP = 262, CARD_BOTTOM = 594;
    static final int PAGE_TYRES = 0, PAGE_EFFICIENCY = 1, PAGE_SENSORS = 2, PAGE_MEDIA = 3, PAGES = 4;

    private final View host;
    private final DashApp app;
    private final CarState car;
    private final boolean left;
    private final String pinKey;
    /** The card in the other column (set on both by link()). */
    private SwipeCard other;
    /** Up on screen: the left card only while the big dial is in the centre. */
    private boolean shown = true;

    private final Paint tile = Style.fill(Style.PANEL);
    private final Paint line = Style.fill(Style.LINE);
    private final Paint psi = Style.text(Style.DISPLAY, Style.T_TYRE, Style.FG, Paint.Align.CENTER);
    private final Paint tyreLbl = Style.text(Style.BODY, Style.T_TYRE_POS, Style.MUTED, Paint.Align.CENTER);
    private final Paint carStroke = Style.stroke(Style.MUTED, 2.5f);
    private final Paint bmp = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF r = new RectF();

    SwipeCard(View host, DashApp app, boolean left, String pinKey) {
        this.host = host;
        this.app = app;
        this.car = app.car;
        this.left = left;
        this.pinKey = pinKey;
        pinned = app.prefs().getInt(pinKey, -1);
        if (pinned >= PAGES) pinned = -1;
        if (pinned >= 0) chosenAt = 1;   // a pin from before a restart still counts as a choice
    }

    /** Pinned page as migrated from an older setting (the right card only). */
    void restorePin(int page) {
        pinned = page >= 0 && page < PAGES ? page : -1;
        chosenAt = pinned >= 0 ? 1 : 0;
    }

    static void link(SwipeCard a, SwipeCard b) {
        a.other = b;
        b.other = a;
    }

    void setShown(boolean on) {
        if (on == shown) return;
        shown = on;
        host.invalidate();
        if (other != null) other.host.invalidate();
    }

    boolean shown() { return shown; }

    /** Pinned page, or -1: automatic (saved under pinKey). */
    private int pinned;
    /** Swiped page, or -1 for automatic. */
    private int manual = -1;
    /** When this card's page was last chosen by hand (uptime ms), 0 = never: decides a clash. */
    private long chosenAt;
    private boolean wasDriving;
    private int shownPage;

    /** What CarPlay is playing; the Now playing page is there only while this isn't null. */
    private NowPlaying nowPlaying;

    void setNowPlaying(NowPlaying now) {
        nowPlaying = now;
        host.invalidate();
    }

    /** Pages there are now: Now playing only while CarPlay plays something. */
    private int pageCount() {
        return nowPlaying != null ? PAGES : PAGE_MEDIA;
    }

    /** The page this card would show on its own. */
    private int wanted() {
        int count = pageCount();   // a pin or swipe to Now playing waits while nothing plays
        if (pinned >= 0 && pinned < count) return pinned;
        if (manual >= 0 && manual < count) return manual;
        if (left) return count > PAGE_MEDIA ? PAGE_MEDIA : PAGE_EFFICIENCY;
        return car.speedKmh() >= 10 ? PAGE_TYRES : PAGE_SENSORS;
    }

    private boolean chosen() {
        int count = pageCount();
        return (pinned >= 0 && pinned < count) || (manual >= 0 && manual < count);
    }

    /** True if this card keeps the page when both want the same one. */
    private boolean wins() {
        boolean mine = chosen(), theirs = other.chosen();
        if (mine != theirs) return mine;
        if (chosenAt != other.chosenAt) return chosenAt > other.chosenAt;
        return left;   // two automatic choices can't clash today; the left card keeps it if they ever do
    }

    /** The page the other card shows, or -1 while it is hidden. */
    private int otherPage() {
        if (other == null || !other.shown || !shown) return -1;
        return other.wanted();
    }

    private int cardPage() {
        boolean driving = car.speedKmh() >= 10;
        if (driving && !wasDriving) manual = -1;       // driving off ends a manual choice; a swipe while driving holds
        wasDriving = driving;
        int page = wanted(), taken = otherPage();
        if (page == taken && !wins()) page = step(page, 1, taken);
        shownPage = page;
        return page;
    }

    /** The next page round the loop in direction dir, skipping the page the other card shows. */
    private int step(int from, int dir, int skip) {
        int n = pageCount(), p = from;
        for (int i = 0; i < n; i++) {
            p = ((p + dir) % n + n) % n;
            if (p != skip) return p;
        }
        return from;
    }

    private void setPinned(int page) {
        pinned = page;
        app.prefs().edit().putInt(pinKey, page).apply();
        changed();
    }

    private void changed() {
        chosenAt = SystemClock.uptimeMillis();
        host.invalidate();
        if (other != null) other.host.invalidate();
    }

    private final Paint pinFill = Style.fill(Style.MUTED), pinStroke = Style.stroke(Style.MUTED, 2f);
    private static final float PIN_X = X1 - 18, PIN_Y = CARD_TOP + 20, PIN_SCALE = 0.65f;

    /** Only an indicator: a small, quiet pin while the page is pinned (the owner, 3 Nov: subtler, smaller). */
    private void drawPin(Canvas c) {
        if (pinned < 0) return;
        c.save();
        c.scale(PIN_SCALE, PIN_SCALE, PIN_X, PIN_Y);
        Icons.pin(c, PIN_X, PIN_Y, Style.MUTED, path, pinFill, pinStroke);
        c.restore();
    }

    private static final long CARD_SLIDE_MS = 280;
    private float cardPos = Float.NaN;    // set on the first draw (negative is a valid looped position)
    private long cardLastMs;
    private boolean cardMoving;

    /**
     * Draws the card in its frame. It slides between its pages over CARD_SLIDE_MS per page when it
     * switches, by itself or by a swipe (the owner, 29 Oct).
     */
    void draw(Canvas c) {
        int page = cardPage();
        int count = pageCount();
        long now = SystemClock.uptimeMillis();
        if (Float.isNaN(cardPos)) cardPos = page;    // no slide on the first frame
        // the pages loop (owner, 5 Nov): slide the short way round, from last to first included
        float goal = page;
        while (goal - cardPos > count / 2f) goal -= count;
        while (cardPos - goal > count / 2f) goal += count;
        // the clock starts at the switch itself: the panel may not have drawn for minutes, and the
        // time since that last draw used to finish the slide in one frame (in-car: animated ~20%)
        if (!cardMoving) { cardLastMs = now; cardMoving = cardPos != goal; }
        float step = (now - cardLastMs) / (float) CARD_SLIDE_MS;
        cardLastMs = now;
        cardPos = cardPos < goal ? Math.min(goal, cardPos + step) : Math.max(goal, cardPos - step);
        r.set(X0, CARD_TOP, X1, CARD_BOTTOM);
        if (!left) {   // free columns (design 2a, 15 Nov): a faint fill and no border; none on the left
            tile.setColor(Style.light ? 0x73ffffff : (Style.PANEL2 & 0x00ffffff) | 0x73000000);
            c.drawRoundRect(r, 14, 14, tile);
        }
        float base = (float) Math.floor(cardPos), f = cardPos - base;
        float pos = base + f * f * (3 - 2 * f), width = X1 - X0;
        c.save();
        c.clipRect(r);
        for (int p = 0; p < count; p++) {
            float d = p - pos;
            while (d >= count / 2f) d -= count;      // the page's nearest copy round the loop
            while (d < -count / 2f) d += count;
            float off = d * width;
            if (Math.abs(off) >= width) continue;
            c.save();
            c.translate(off, 0);
            if (p == PAGE_SENSORS) drawSensorCard(c);
            else if (p == PAGE_TYRES) drawTyres(c);
            else if (p == PAGE_EFFICIENCY) drawEfficiency(c);
            else drawNowPlaying(c);
            c.restore();
        }
        c.restore();
        pageDots(c, page);
        drawPin(c);
        cardMoving = cardPos != goal;
        if (cardMoving) host.postInvalidateOnAnimation();
        else cardPos = page;                         // settled: back inside 0..count-1
    }

    /** Page dots at the bottom of the card: which page is showing (the other card's page dimmed out). */
    private void pageDots(Canvas c, int page) {
        float cx = (X0 + X1) / 2f;
        int count = pageCount();
        for (int p = 0; p < count; p++) {
            dot.setColor(p == page ? Style.FG : Style.DIM);
            c.drawCircle(cx + (p - (count - 1) / 2f) * 14, CARD_BOTTOM - 12, 3.5f, dot);
        }
    }

    // ---- touch: swipe for the next page, hold to pin ----

    private static final long HOLD_MS = 600;
    private static final float SWIPE_MIN = 40;
    private float swipeX = Float.NaN, swipeY;
    /** The current touch started on the card. */
    private boolean cardTouch;

    /** Long press on the card: pin or unpin. Cancelled by a swipe or lifting early. */
    private final Runnable hold = new Runnable() {
        @Override public void run() {
            if (Float.isNaN(swipeX)) return;
            swipeX = Float.NaN;                           // this touch is used up: no swipe on release
            host.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
            host.playSoundEffect(android.view.SoundEffectConstants.CLICK);
            manual = -1;
            setPinned(pinned >= 0 ? -1 : shownPage);
        }
    };

    /**
     * A touch in card coordinates (x, y in the card's frame). True while the card owns the gesture.
     * A horizontal swipe moves one page (left = next), past the page the other card shows.
     */
    boolean touch(int action, float x, float y) {
        switch (action) {
            case MotionEvent.ACTION_DOWN:
                cardTouch = shown && !(y < CARD_TOP || y > CARD_BOTTOM || x < X0 || x > X1);
                if (!cardTouch) { swipeX = Float.NaN; return false; }
                swipeX = x; swipeY = y;
                host.postDelayed(hold, HOLD_MS);
                return true;
            case MotionEvent.ACTION_MOVE:
                if (!cardTouch) return false;
                if (!Float.isNaN(swipeX) && Math.hypot(x - swipeX, y - swipeY) > SWIPE_MIN / 2) host.removeCallbacks(hold);
                return true;                                  // a used-up hold keeps the gesture to the end
            case MotionEvent.ACTION_UP:
                host.removeCallbacks(hold);
                if (!cardTouch) return false;
                cardTouch = false;
                if (Float.isNaN(swipeX)) return true;
                if (Math.abs(x - swipeX) > SWIPE_MIN && Math.abs(x - swipeX) > Math.abs(y - swipeY)) {
                    int next = step(shownPage, x < swipeX ? 1 : -1, otherPage());   // the pages loop round
                    if (pinned >= 0) setPinned(next);               // the pin moves with the page
                    else { manual = next; changed(); }              // held until driving off
                }
                swipeX = Float.NaN;
                return true;
            case MotionEvent.ACTION_CANCEL:
                host.removeCallbacks(hold);
                swipeX = Float.NaN;
                boolean was = cardTouch;
                cardTouch = false;
                return was;
        }
        return false;
    }

    // ---- pages (moved from RightPanelView) ----

    /** Tyre display unit: psi by default (what the car and the stock dash show), or bar. */
    private String tyreUnit() {
        return app.tyreBar() ? "bar" : "psi";
    }

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
        // the unit only: each reading sits beside its own tyre (owner, 10 Nov: FL/FR/RL/RR were noise)
        c.drawText(tyreUnit(), x, y + 22, tyreLbl);
    }

    // Now playing page layout (design px): a big cover, then title and artist (owner, 5 Nov: no
    // progress bar or buttons, the cover gets the room)
    private static final float ART = 224, ART_TOP = CARD_TOP + 16, NP_TITLE_Y = CARD_TOP + 270,
            NP_ARTIST_Y = CARD_TOP + 296;
    private final Paint npTitle = Style.text(Style.BODY_BOLD, 21, Style.FG, Paint.Align.CENTER);
    private final Paint npArtist = Style.text(Style.BODY, 17, Style.MUTED, Paint.Align.CENTER);
    private final Paint npArtBg = Style.fill(Style.PANEL2), npNote = Style.stroke(Style.DIM, 5);
    private final Path npClip = new Path();

    /** Now playing (CarPlay): the cover, title and artist. */
    private void drawNowPlaying(Canvas c) {
        NowPlaying np = nowPlaying;
        if (np == null) return;
        float cx = (X0 + X1) / 2f;
        r.set(cx - ART / 2, ART_TOP, cx + ART / 2, ART_TOP + ART);
        if (np.art != null) {
            c.save();
            npClip.reset();
            npClip.addRoundRect(r, 12, 12, Path.Direction.CW);
            c.clipPath(npClip);
            c.drawBitmap(np.art, null, r, bmp);
            c.restore();
        } else {
            c.drawRoundRect(r, 12, 12, npArtBg);
            musicNote(c, cx, ART_TOP + ART / 2);
        }
        float textW = X1 - X0 - 28;
        Style.size(npTitle, 21);
        c.drawText(ellipsize(np.title.isEmpty() ? np.app : np.title, npTitle, textW), cx, NP_TITLE_Y, npTitle);
        Style.size(npArtist, 17);
        String by = np.artist.isEmpty() ? np.album : np.artist;
        if (!by.isEmpty()) c.drawText(ellipsize(by, npArtist, textW), cx, NP_ARTIST_Y, npArtist);
    }

    private void musicNote(Canvas c, float x, float y) {
        c.drawCircle(x - 12, y + 24, 12, npNote);
        c.drawLine(x, y + 24, x, y - 30, npNote);
        c.drawLine(x, y - 30, x + 20, y - 18, npNote);
    }

    private static String ellipsize(String text, Paint p, float maxW) {
        if (p.measureText(text) <= maxW) return text;
        int n = p.breakText(text, true, maxW - p.measureText("…"), null);
        return text.substring(0, Math.max(0, n)).trim() + "…";
    }
}
