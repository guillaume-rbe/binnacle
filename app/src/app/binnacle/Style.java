package app.binnacle;

import android.graphics.Paint;
import android.graphics.Typeface;

/**
 * Colours and type from the agreed mockup (mockups/cluster_mockup.html). All panel views draw in
 * "design pixels" of the 1920x720 mockup and scale to the real view size, so the layout matches
 * the mockup whatever the screen density.
 */
final class Style {
    /**
     * Theme colours. Not final: applyTheme() swaps the whole set, and views pick the new colours up
     * when they are rebuilt (DashActivity recreates itself on a theme change).
     */
    static int BG, PANEL, PANEL2, SCREEN, LINE, FG, MUTED, DIM, ACCENT, GOOD, WARN, BAD;
    /** Centre window: glow behind the parked car, sky and road gradients, badge backing. */
    static int GLOW, BADGE_BG;
    static int[] SKY, ROAD;
    /** True while the light theme is applied. */
    static boolean light;

    static { applyTheme(false); }

    static void applyTheme(boolean useLight) {
        light = useLight;
        if (useLight) {   // the owner's pick from the 29 Oct mockup
            BG = 0xffe6ebf1; PANEL = 0xfff5f7fa; PANEL2 = 0xffffffff; SCREEN = 0xffeef2f6; LINE = 0xffc9d2dd;
            FG = 0xff0d1520; MUTED = 0xff5a6676; DIM = 0xffb3bcc7;
            ACCENT = 0xff0a8fd8; GOOD = 0xff12a46e; WARN = 0xffe08a00; BAD = 0xffe5392f;
            GLOW = 0xffffffff; BADGE_BG = 0xe6ffffff;
            SKY = new int[]{0xfff3f6f9, 0xffe9eef3, 0xffe1e7ee};
            ROAD = new int[]{0x00a9b3bf, 0xffa9b3bf, 0xff8b96a3};
        } else {
            BG = 0xff07090d; PANEL = 0xff0e131b; PANEL2 = 0xff141b25; SCREEN = 0xff05070b; LINE = 0xff222c39;
            FG = 0xffeef3f8; MUTED = 0xff8793a4; DIM = 0xff505b6a;
            ACCENT = 0xff36b9ff; GOOD = 0xff37e0a0; WARN = 0xffffb84d; BAD = 0xffff5a52;
            GLOW = 0xff0f1d2b; BADGE_BG = 0xcc0b0f16;
            SKY = new int[]{0xff0a1019, 0xff0d1622, 0xff0b111b};
            ROAD = new int[]{0x002c3644, 0xff2c3644, 0xff161d27};
        }
    }

    /** Roboto Condensed (system "sans-serif-condensed") for big numbers, Roboto for labels. */
    static final Typeface DISPLAY = Typeface.create("sans-serif-condensed", Typeface.BOLD);
    static final Typeface BODY = Typeface.create("sans-serif", Typeface.NORMAL);
    static final Typeface BODY_BOLD = Typeface.create("sans-serif-medium", Typeface.NORMAL);

    /** Panel design sizes (px of the 1920x720 mockup). */
    static final float DESIGN_H = 720f, SIDE_W = 340f;
    /**
     * Corner radius of the centre window when CarPlay or the camera fills it, in design px of the
     * 692 px high window: the iPhone's own rounded CarPlay card (a circle of 68 px covers its
     * continuous curve, car screenshots 6-10 Nov), so CarPlay and the camera share one corner.
     */
    static final float WINDOW_RADIUS = 68f, WINDOW_H = 692f;

    /**
     * Design text sizes (px of the 1920x720 layout), tuned after the first in-car test: small labels
     * clearly bigger than v0.1, big numbers only slightly bigger. v0.1 size in brackets.
     */
    static final float
            T_LABEL = 18,      // tile headers, OUTSIDE, POWER (12)
            T_UNIT = 22,       // KM / H (15)
            T_SMALL = 20,      // est. range, doors (13)
            T_TYRE_POS = 17,   // FL FR RL RR (11)
            T_SCALE = 16,      // Regen / 0 / Power (11)
            T_DOCK = 17,       // dock labels (12)
            T_BADGE = 17,      // centre badges (13)
            T_SPEED = 168,     // (160)
            T_GEAR = 34,       // PRND (30)
            T_LIMIT = 40,      // speed-limit sign (38)
            T_TYRE = 26,       // tyre values (22)
            T_BATTERY = 42,    // battery % number (38)
            T_PCT = 19,        // the % sign (17)
            T_ODO = 24,        // odometer (21)
            T_ODO_UNIT = 15,   // km after the odometer (11)
            T_TEMP = 27;       // outside temp (24)

    /** User text-size setting (1.0 / 1.15 / 1.3), applied on top of the design sizes. */
    static float textScale = 1f;

    private Style() {}

    /** Set a text paint to its design size times the user's text scale. */
    static void size(Paint p, float designSize) {
        p.setTextSize(designSize * textScale);
    }

    /** Shrink p (if needed) so text fits in maxW design px. Call after size(). */
    static void fit(Paint p, String text, float maxW) {
        if (maxW <= 0) return;   // not laid out yet: keep the design size rather than a zero/negative one
        float w = p.measureText(text);
        if (w > maxW && w > 0) p.setTextSize(p.getTextSize() * maxW / w);
    }

    static Paint fill(int colour) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(colour);
        return p;
    }

    static Paint stroke(int colour, float w) {
        Paint p = fill(colour);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(w);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
        return p;
    }

    static Paint text(Typeface tf, float size, int colour, Paint.Align align) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        p.setTypeface(tf);
        p.setTextSize(size);
        p.setColor(colour);
        p.setTextAlign(align);
        if (android.os.Build.VERSION.SDK_INT >= 21) p.setFontFeatureSettings("tnum");
        return p;
    }

    /** Small upper-case label with letter spacing, like the mockup's .lbl. */
    static Paint label(float size) {
        Paint p = text(BODY, size, MUTED, Paint.Align.LEFT);
        if (android.os.Build.VERSION.SDK_INT >= 21) p.setLetterSpacing(0.16f);
        return p;
    }
}
