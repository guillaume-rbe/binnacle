package app.binnacle;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.provider.Settings;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Apps and dash settings for the 1920x720 panel: section tabs down one side (the right by default,
 * for a right-hand-drive car; Display tab swaps it), big tiles on the other (an app grid, or setting cards with tap-sized segmented choices and switches) instead of
 * full-width Android list rows. Everything is focusable, so the steering wheel's scroll / OK can
 * reach it, and focus shows as an accent outline.
 *
 * Settings are this app's own SharedPreferences (same keys as before); nothing here is sent to
 * the car. The CarPlay layout is shown read-only (see StockScreens).
 */
public class HubActivity extends Activity {

    /** Tag on every settings card (scroller() scrolls a focused card fully into view). */
    private static final Object CARD_TAG = new Object();
    private static final String[] TABS = {"Apps", "Display", "Camera", "Car", "Advanced"};
    private static final int[] TAB_ICONS = {R.drawable.ic_tab_apps, R.drawable.ic_tab_display,
            R.drawable.ic_tab_camera, R.drawable.ic_tab_car, R.drawable.ic_tab_advanced};

    private SharedPreferences prefs;
    private FrameLayout content;
    private LinearLayout rail;
    private boolean menuRightBuilt;
    private final List<TextView> tabViews = new ArrayList<>();
    private int tab;
    private float textScaleBuilt;
    private boolean lightBuilt;
    private List<Entry> apps;                 // loaded once per screen (icons are slow on the unit)

    private static final class Entry {
        final String label;
        final Drawable icon;
        final Intent intent;
        final String pkg;
        Entry(String label, Drawable icon, Intent intent, String pkg) { this.label = label; this.icon = icon; this.intent = intent; this.pkg = pkg; }
    }

    /**
     * Pinned first, in this order, then a divider and every other app A-Z (the owner, 31 Oct):
     * Binnacle itself (back to the dash), Android settings, the drive logger, the stock dash.
     */
    private static final String[] PINNED = {"", "com.android.settings", "app.binnacle.logger", "com.lotech.tesladash", "com.xbs.launcher1"};
    private int pinnedCount;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = DashApp.get(this).prefs();
        textScaleBuilt = Style.textScale;
        lightBuilt = Style.light;

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setBackgroundColor(Style.BG);

        rail = new LinearLayout(this);
        rail.setOrientation(LinearLayout.VERTICAL);
        rail.setPadding(dp(14), dp(12), dp(12), dp(8));
        rail.setBackgroundColor(Style.PANEL);
        TextView back = tabButton("‹  Back to Dash");
        back.setTextColor(Style.MUTED);
        back.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });
        rail.addView(back, tabParams());
        for (int i = 0; i < TABS.length; i++) {
            final int index = i;
            TextView t = tabButton(TABS[i]);
            t.setCompoundDrawablesRelativeWithIntrinsicBounds(TAB_ICONS[i], 0, 0, 0);
            t.setCompoundDrawablePadding(dp(12));
            t.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { showTab(index); }
            });
            tabViews.add(t);
            rail.addView(t, tabParams());
        }
        ScrollView railScroll = new ScrollView(this);     // six 48 dp rows fit 360 dp; scrolls on a shorter screen
        railScroll.setBackgroundColor(Style.PANEL);
        railScroll.addView(rail);
        content = new FrameLayout(this);
        content.setPadding(dp(18), dp(18), dp(18), dp(12));
        // menu on the driver's side: right by default (right-hand drive), left as an option
        boolean menuRight = !"left".equals(prefs.getString("menu_side", "right"));
        menuRightBuilt = menuRight;
        LinearLayout.LayoutParams railLp = new LinearLayout.LayoutParams(dp(190), ViewGroup.LayoutParams.MATCH_PARENT);
        LinearLayout.LayoutParams contentLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1);
        if (menuRight) {
            root.addView(content, contentLp);
            root.addView(railScroll, railLp);
        } else {
            root.addView(railScroll, railLp);
            root.addView(content, contentLp);
        }
        setContentView(root);

        showTab(state != null ? state.getInt("tab", 0) : 0);
        tabViews.get(tab).requestFocus();
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putInt("tab", tab);
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION);
        }
    }

    /**
     * Steering wheel: scroll down/up walks focus through tabs and tiles in reading order (handled
     * here, before a ScrollView would turn it into a one-column jump), wrapping round at either end,
     * OK clicks. The first press
     * after touching lands on the current tab instead of being lost.
     */
    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        int code = event.getKeyCode();
        if ((code == KeyEvent.KEYCODE_DPAD_DOWN || code == KeyEvent.KEYCODE_DPAD_UP) && event.getAction() == KeyEvent.ACTION_DOWN) {
            View f = getCurrentFocus();
            if (f == null || !f.isFocused()) {
                tabViews.get(tab).requestFocus();
                return true;
            }
            // tabs first, then the content, whichever side the menu is on
            ArrayList<View> order = new ArrayList<>(rail.getFocusables(View.FOCUS_FORWARD));
            order.addAll(content.getFocusables(View.FOCUS_FORWARD));
            int i = order.indexOf(f);
            int n = order.size();
            if (n == 0) return true;
            int j = i < 0 ? 0 : i + (code == KeyEvent.KEYCODE_DPAD_DOWN ? 1 : -1);
            // wrap round: past the last button back to the top of the menu, and up from the top to the end
            if (j >= n) {
                j = 0;
                ScrollView page = pageScroll();
                if (page != null) page.smoothScrollTo(0, 0);
            } else if (j < 0) {
                j = n - 1;
            }
            order.get(j).requestFocus();
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    /** Auto can switch the theme while this screen is open: rebuild in the new palette. */
    private final DashApp.Listener themeListener = new DashApp.Listener() {
        @Override public void onCarChanged(int code) {
            // the car started to drive (left Park, moved, or raised the camera flag): back to the
            // cluster, and its camera (audit D2). Not with demo data, which drives on its own.
            DashApp app = DashApp.get(HubActivity.this);
            if (drive.startedDriving(app.car) && !app.isDemo()) { finish(); return; }
            if (code == DashApp.THEME_CHANGED && lightBuilt != Style.light) recreate();
            if (code == DashApp.PACKS_CHANGED && tab == CAR_TAB) showTab(CAR_TAB);   // whichever Car images card is showing now
            // the car reports its colour (code 9) after the card was built: rebuild it for that colour
            if (tab == CAR_TAB && packCardColour != DashApp.get(HubActivity.this).carColour()) showTab(CAR_TAB);
            // a door, boot or frunk opened: back to the dash. The stock CAN app only holds back its
            // full-screen door picture while the dash's own activity is in front, and the dash shows
            // the open door on its car instead.
            if (code == 15) {
                int doors = DashApp.get(HubActivity.this).car.doors;
                if (doorsSeen >= 0 && doors > 0 && (doors & ~doorsSeen) != 0) finish();
                doorsSeen = doors;
            }
        }
    };
    private final DriveWatch drive = new DriveWatch();
    /** Door bits when settings opened or last changed (-1: not known yet). */
    private int doorsSeen = -1;

    private final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());

    /** Car model or driving side changed: rebuild the Car tab so Car images shows (and fetches) that set. */
    private final SharedPreferences.OnSharedPreferenceChangeListener sideListener =
            new SharedPreferences.OnSharedPreferenceChangeListener() {
                @Override public void onSharedPreferenceChanged(SharedPreferences sp, String key) {
                    if (!"drive_side".equals(key) && !"car_model".equals(key) && !"car_colour".equals(key)) return;
                    // posted: listeners run in no set order, and the card reads DashApp's cached
                    // settings, which DashApp refreshes in its own listener for this same change
                    main.post(new Runnable() {
                        @Override public void run() { if (tab == CAR_TAB) showTab(CAR_TAB); }
                    });
                }
            };

    @Override protected void onStart() {
        super.onStart();
        DashApp.get(this).addListener(themeListener);
        doorsSeen = DashApp.get(this).car.doors;
        drive.seed(DashApp.get(this).car);
        prefs.registerOnSharedPreferenceChangeListener(sideListener);
    }

    @Override protected void onStop() {
        DashApp.get(this).removeListener(themeListener);
        prefs.unregisterOnSharedPreferenceChangeListener(sideListener);
        super.onStop();
    }

    @Override protected void onResume() {
        super.onResume();
        if (textScaleBuilt != Style.textScale) { recreate(); return; }   // text size changed: rebuild at the new size
        if (lightBuilt != Style.light) { recreate(); return; }           // theme changed meanwhile (Auto)
        if (tab == 1 || tab == CAR_TAB) showTab(tab);                      // CarPlay layout / Home app may have changed meanwhile
        else if (tab >= 4) showTab(tab);                                   // back from the uninstaller: logger state
    }

    /** The Car tab: car model, driving side, car images, tyres, range. */
    private static final int CAR_TAB = 3;

    private void showTab(int i) {
        // rebuilding the same tab (a download finished, the side changed): keep the scroll position
        ScrollView old = i == tab ? pageScroll() : null;
        final int keepScroll = old != null ? old.getScrollY() : 0;
        tab = i;
        for (int t = 0; t < tabViews.size(); t++) {
            TextView v = tabViews.get(t);
            v.setSelected(t == i);
            int colour = t == i ? 0xffffffff : Style.MUTED;     // white on the blue selected fill
            v.setTextColor(colour);
            if (android.os.Build.VERSION.SDK_INT >= 23)        // icon in the same colour as its label
                v.setCompoundDrawableTintList(android.content.res.ColorStateList.valueOf(colour));
        }
        content.removeAllViews();
        switch (i) {
            case 0: content.addView(appsGrid()); break;
            case 1: content.addView(cards(          // most used first; the rest below the fold
                    homeCard(),
                    choiceCard("Theme", null, "theme", "auto",
                            new String[]{"auto", "light", "dark"}, new String[]{"Auto", "Light", "Dark"}),
                    choiceCard("Text size", null, "text_size", "100",
                            new String[]{"100", "115", "130"}, new String[]{"100%", "115%", "130%"}),
                    choiceCard("Menu side", null, "menu_side", "right",
                            new String[]{"left", "right"}, new String[]{"Left", "Right"}),
                    choiceCard("Start-up volume", "Media volume set when the dash starts and when the car's Bluetooth audio "
                                    + "connects. The car sets the loudness, so full is best",
                            StartupVolume.KEY, StartupVolume.DEFAULT,
                            new String[]{"100", "75", "50", "off"}, new String[]{"100%", "75%", "50%", "Leave as is"}),
                    btAutoConnectCard(),
                    switchCard("Pause Wi-Fi scans during CarPlay", "Stops music and call stutter away from home. Scanning comes back on when CarPlay ends, and tapping the Wi-Fi icon rejoins home Wi-Fi", WifiScans.KEY, WifiScans.DEFAULT),
                    actionCard("End CarPlay", null,
                            "End CarPlay", LocalShell.Command.END_CARPLAY),
                    infoCard("CarPlay layout", carplayLayoutText()))); break;
            case 2: content.addView(cards(
                    switchCard("Camera in the dash", "Forward camera in the centre window", "dash_camera", false),
                    switchCard("Open stock camera on Drive", "Stock camera screen instead", "auto_camera", false))); break;
            case CAR_TAB: content.addView(cards(
                    choiceCard("Car model", null, "car_model", "3",
                            new String[]{"3", "y"}, new String[]{"Model 3", "Model Y"}),
                    choiceCard("Driving side", null, "drive_side", "rhd",
                            new String[]{"lhd", "rhd"}, new String[]{"Left", "Right"}),
                    carColourCard(),
                    carPicturesCard(),
                    choiceCard("Units", null, "units", "auto",
                            new String[]{"auto", "metric", "imperial"}, new String[]{"Auto", "Metric", "Imperial"}),
                    choiceCard("Tyre pressure", null, "tyre_unit", "psi",
                            new String[]{"psi", "bar"}, new String[]{"psi", "bar"}),
                    stepperCard("Range at 100%", "For the km estimate under the battery %", "full_range_km", 410, 150, 700, 5, "km"),
                    pairCard("Autopilot indication", "autopilot_rainbow", "Normal", "Rainbow road"))); break;
            default: content.addView(cards(
                    switchCard("Show raw CAN values", "Latest raw fields over the centre view, for in-car checks", "raw_overlay", false),
                    switchCard("Screenshot on power bar tap", "Tap the power bar to save a screenshot on the dash (Binnacle/screenshots), CarPlay and camera included", Screenshot.KEY, false),
                    switchCard("Demo data", "Simulated drive instead of the car feed", "demo", false),
                    loggerCards(),
                    stack(updateCard(), setupCard(), resetCard(), aboutCard()))); break;
        }
        final ScrollView sv = pageScroll();
        if (keepScroll > 0 && sv != null) {
            sv.post(new Runnable() { @Override public void run() { sv.scrollTo(0, keepScroll); } });
        }
    }

    // ---- apps ----

    private View appsGrid() {
        if (apps == null) apps = loadApps();
        GridLayout grid = new GridLayout(this);
        grid.setColumnCount(6);
        for (int i = 0; i < apps.size(); i++) {
            if (i == pinnedCount && pinnedCount > 0 && pinnedCount < apps.size()) grid.addView(divider(), dividerParams());
            Entry e = apps.get(i);
            final Intent intent = e.intent;
            LinearLayout tile = new LinearLayout(this);
            tile.setOrientation(LinearLayout.VERTICAL);
            tile.setGravity(Gravity.CENTER);
            tile.setPadding(dp(6), dp(12), dp(6), dp(10));
            tile.setBackground(bareBackground());   // just icon + name; a shape only when focused / pressed
            tile.setFocusable(true);
            tile.setClickable(true);
            ImageView icon = new ImageView(this);
            icon.setImageDrawable(e.icon);
            tile.addView(icon, new LinearLayout.LayoutParams(dp(54), dp(54)));
            TextView label = text(e.label, 15, Style.FG);
            label.setGravity(Gravity.CENTER);
            label.setSingleLine(true);
            label.setEllipsize(TextUtils.TruncateAt.END);
            label.setPadding(0, dp(8), 0, 0);
            tile.addView(label, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            tile.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    if (Launch.start(HubActivity.this, new Intent(intent), true)) finish();
                }
            });
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams(GridLayout.spec(GridLayout.UNDEFINED), GridLayout.spec(GridLayout.UNDEFINED, 1f));
            lp.width = 0;
            lp.height = dp(118);
            lp.setMargins(dp(4), dp(4), dp(4), dp(4));
            grid.addView(tile, lp);
        }
        return scroller(grid);
    }

    private List<Entry> loadApps() {
        PackageManager pm = getPackageManager();
        List<Entry> found = new ArrayList<>();
        boolean stockListed = false;
        boolean hideZlink = Launch.hasXcertplay(this);   // xcertplay replaces it (the owner, 1 Nov); ZLink stays installed
        for (ResolveInfo ri : pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)) {
            String pkg = ri.activityInfo.packageName;
            if (hideZlink && pkg.equals(Launch.ZLINK_PKG)) continue;
            boolean self = getPackageName().equals(pkg);
            String label = self ? "Binnacle" : pkg.startsWith("app.binnacle.logger") || pkg.equals("com.lotech.tesladash") ? "Logger"
                    : String.valueOf(ri.loadLabel(pm));
            if (pkg.equals(Launch.STOCK_DASH.getPackageName())) stockListed = true;
            found.add(new Entry(label, ri.loadIcon(pm),
                    new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                            .setComponent(new ComponentName(pkg, ri.activityInfo.name)), self ? "" : pkg));
        }
        if (!stockListed) {
            try {
                pm.getActivityInfo(Launch.STOCK_DASH, 0);
                found.add(new Entry("Stock dash", getDrawable(R.drawable.ic_stock_dash),
                        new Intent(Intent.ACTION_MAIN).setComponent(Launch.STOCK_DASH), Launch.STOCK_DASH.getPackageName()));
            } catch (PackageManager.NameNotFoundException ignored) {}
        } else {
            for (int i = 0; i < found.size(); i++) {      // our own name and icon for it (its own is a generic one)
                Entry e = found.get(i);
                if (e.pkg.equals(Launch.STOCK_DASH.getPackageName()))
                    found.set(i, new Entry("Stock dash", getDrawable(R.drawable.ic_stock_dash), e.intent, e.pkg));
            }
        }
        List<Entry> list = new ArrayList<>();
        for (String pin : PINNED) {
            for (int i = 0; i < found.size(); i++) {
                if (found.get(i).pkg.equals(pin)) { list.add(found.remove(i)); break; }
            }
        }
        pinnedCount = list.size();
        Collections.sort(found, new Comparator<Entry>() {
            @Override public int compare(Entry a, Entry b) { return a.label.compareToIgnoreCase(b.label); }
        });
        list.addAll(found);
        return list;
    }

    /** A thin line across the whole grid between the pinned apps and the rest. */
    private View divider() {
        View v = new View(this);
        v.setBackgroundColor(Style.LINE);
        return v;
    }

    private GridLayout.LayoutParams dividerParams() {
        GridLayout.LayoutParams lp = new GridLayout.LayoutParams(GridLayout.spec(GridLayout.UNDEFINED), GridLayout.spec(0, 6, 1f));
        lp.width = 0;
        lp.height = Math.max(1, dp(1));
        lp.setMargins(dp(12), dp(6), dp(12), dp(6));
        return lp;
    }

    /** Grey out a card whose setting has no effect right now (it stays readable). */
    private View enabledIf(boolean enabled, View card) {
        card.setAlpha(enabled ? 1f : 0.4f);
        setEnabledDeep(card, enabled);
        return card;
    }

    private static void setEnabledDeep(View v, boolean enabled) {
        v.setEnabled(enabled);
        v.setFocusable(enabled && v.isClickable());
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) setEnabledDeep(g.getChildAt(i), enabled);
        }
    }

    // ---- setting cards ----

    /** Two columns of cards, scrolling if they don't fit. */
    /** Button height in settings cards (was 52: about half again bigger than needed). */
    private static final int BUTTON_DP = 38;

    private View cards(View... cards) {
        GridLayout grid = new GridLayout(this);
        grid.setColumnCount(2);
        for (View c : cards) {
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams(GridLayout.spec(GridLayout.UNDEFINED), GridLayout.spec(GridLayout.UNDEFINED, 1f));
            lp.width = 0;
            lp.setMargins(dp(6), dp(6), dp(6), dp(6));
            grid.addView(c, lp);
        }
        if (cards.length % 2 == 1) {              // keep a lone last card half width
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams(GridLayout.spec(GridLayout.UNDEFINED), GridLayout.spec(GridLayout.UNDEFINED, 1f));
            lp.width = 0;
            lp.height = 1;
            grid.addView(new View(this), lp);
        }
        return scroller(grid);
    }

    /** The scrolling page on screen (inside its scroller() frame), or null. */
    private ScrollView pageScroll() {
        View v = content.getChildCount() > 0 ? content.getChildAt(0) : null;
        if (v instanceof FrameLayout && ((FrameLayout) v).getChildCount() > 0) v = ((FrameLayout) v).getChildAt(0);
        return v instanceof ScrollView ? (ScrollView) v : null;
    }

    /**
     * A settings page that shows when there's more below: a scrollbar that never fades, and a
     * "More below" pill at the bottom while the page can still scroll down.
     */
    private View scroller(View page) {
        final ScrollView sv = new ScrollView(this);
        sv.setVerticalScrollBarEnabled(true);
        sv.setScrollbarFadingEnabled(false);
        sv.setScrollBarSize(dp(6));
        // the bar sits in its own gutter at the right edge, clear of the right-hand cards
        sv.setScrollBarStyle(View.SCROLLBARS_OUTSIDE_OVERLAY);
        sv.setPadding(0, 0, dp(16), 0);
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            GradientDrawable thumb = new GradientDrawable();
            thumb.setColor(Style.MUTED);
            thumb.setCornerRadius(dp(3));
            sv.setVerticalScrollbarThumbDrawable(thumb);
        }
        sv.addView(page);
        FrameLayout frame = new FrameLayout(this);
        frame.addView(sv, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        final TextView more = text("More below  ▾", 15, Style.FG);
        more.setPadding(dp(18), dp(6), dp(18), dp(6));
        GradientDrawable pill = new GradientDrawable();
        pill.setColor(Style.PANEL2);
        pill.setStroke(dp(1), Style.LINE);
        pill.setCornerRadius(dp(16));
        more.setBackground(pill);
        frame.addView(more, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL));
        // only before the first scroll: once you've scrolled you know there's more
        final boolean[] scrolled = {false};
        final Runnable update = new Runnable() {
            @Override public void run() {
                if (sv.getScrollY() > dp(8)) scrolled[0] = true;
                // only when a real amount is hidden, not just the bottom edge of a card
                View page = sv.getChildCount() > 0 ? sv.getChildAt(0) : null;
                int hidden = page == null ? 0 : page.getBottom() - sv.getScrollY() - (sv.getHeight() - sv.getPaddingBottom());
                more.setVisibility(!scrolled[0] && hidden > dp(48) ? View.VISIBLE : View.GONE);
            }
        };
        if (android.os.Build.VERSION.SDK_INT >= 23) {
            sv.setOnScrollChangeListener(new View.OnScrollChangeListener() {
                @Override public void onScrollChange(View v, int x, int y, int ox, int oy) { update.run(); }
            });
        } else {
            sv.getViewTreeObserver().addOnScrollChangedListener(new android.view.ViewTreeObserver.OnScrollChangedListener() {
                @Override public void onScrollChanged() { update.run(); }
            });
        }
        // the wheel moves focus button by button, and ScrollView only scrolls far enough to show the
        // button; show the whole card instead, so its title (which says what the button is) is in view
        final android.view.ViewTreeObserver.OnGlobalFocusChangeListener showCard = new android.view.ViewTreeObserver.OnGlobalFocusChangeListener() {
            @Override public void onGlobalFocusChanged(View oldFocus, View newFocus) {
                // only focus inside this page (an old page's listener may still be registered)
                View card = null, v = newFocus;
                while (v != null && v != sv) {
                    if (v.getTag() == CARD_TAG) card = v;
                    v = v.getParent() instanceof View ? (View) v.getParent() : null;
                }
                if (v != sv || card == null || !sv.isAttachedToWindow()) return;
                final View c = card;
                sv.post(new Runnable() {
                    @Override public void run() {
                        android.graphics.Rect r = new android.graphics.Rect(0, 0, c.getWidth(), c.getHeight());
                        sv.offsetDescendantRectToMyCoords(c, r);
                        int top = r.top - dp(8), bottom = r.bottom + dp(8) - sv.getHeight();
                        if (top < sv.getScrollY()) sv.smoothScrollTo(0, Math.max(0, top));
                        else if (bottom > sv.getScrollY()) sv.smoothScrollTo(0, bottom);
                    }
                });
            }
        };
        sv.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View v) { v.getViewTreeObserver().addOnGlobalFocusChangeListener(showCard); }
            @Override public void onViewDetachedFromWindow(View v) { v.getViewTreeObserver().removeOnGlobalFocusChangeListener(showCard); }
        });
        sv.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override public void onLayoutChange(View v, int l, int t, int r, int b, int ol, int ot, int or, int ob) { sv.post(update); }
        });
        return frame;
    }

    private LinearLayout card(String title, String summary) {
        LinearLayout card = new LinearLayout(this);
        card.setTag(CARD_TAG);                             // scroller(): bring the whole card into view on focus
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(8), dp(14), dp(10));   // compact: most settings above the fold (the owner)
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Style.PANEL);
        bg.setCornerRadius(dp(14));
        bg.setStroke(dp(1), Style.LINE);
        card.setBackground(bg);
        TextView t = text(title, 16, Style.FG);
        if (summary == null) t.setPadding(0, 0, 0, dp(4));
        card.addView(t);
        if (summary != null) {
            TextView s = text(summary, 12, Style.MUTED);
            s.setPadding(0, 0, 0, dp(2));
            card.addView(s);
        }
        return card;
    }

    /** A row of segmented choices (each a big tap target). */
    private View choiceCard(String title, String summary, final String key, String def, final String[] values, String[] labels) {
        LinearLayout card = card(title, summary);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        final List<TextView> buttons = new ArrayList<>();
        String current = prefs.getString(key, def);
        for (int i = 0; i < values.length; i++) {
            final String value = values[i];
            TextView b = chip(labels[i]);
            b.setSelected(value.equals(current));
            b.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    prefs.edit().putString(key, value).apply();
                    for (TextView o : buttons) o.setSelected(o == v);
                    // text size: rebuild the whole hub (rail included) at the new size once DashApp has applied it
                    if ("text_size".equals(key)) v.post(new Runnable() {
                        @Override public void run() { if (textScaleBuilt != Style.textScale) recreate(); }
                    });
                    if ("theme".equals(key)) v.post(new Runnable() {     // DashApp applied it: redraw this screen too
                        @Override public void run() { if (lightBuilt != Style.light) recreate(); }
                    });
                    if ("menu_side".equals(key) && ("right".equals(value) != menuRightBuilt)) v.post(new Runnable() {
                        @Override public void run() { recreate(); }
                    });
                }
            });
            buttons.add(b);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(BUTTON_DP), 1);
            lp.setMargins(i == 0 ? 0 : dp(8), 0, 0, 0);
            row.addView(b, lp);
        }
        card.addView(row);
        return card;
    }

    /** On / off as one big chip. */
    static final String LOGGER_PKG = "app.binnacle.logger", OLD_LOGGER_PKG = "com.lotech.tesladash";

    /**
     * The optional drive logger, a separate developer app installed by hand (never by Binnacle):
     * whether it is installed, an opt-in "start with the dash" switch (off by default), and
     * Uninstall, which opens Android's own uninstall dialog and so wipes its logs. An old
     * location-recording logger, if present, is offered for removal.
     */
    private View loggerCards() {
        List<View> box = new ArrayList<>();
        if (installed(OLD_LOGGER_PKG)) {
            box.add(uninstallCard("Old drive logger found",
                    "An older logger that also recorded location. Uninstall it to delete its logs.", OLD_LOGGER_PKG));
        }
        if (installed(LOGGER_PKG)) {
            box.add(switchCard("Drive logger: start with the dash",
                    "Records car data, never location, to its own private storage; kept 7 days", "logger_autostart", false));
            box.add(uninstallCard("Remove drive logger", "Uninstalling also deletes all its logs", LOGGER_PKG));
        } else {
            box.add(infoCard("Drive logger (optional)", "A developer tool that records car data, never location. "
                    + "Not installed, and Binnacle never installs it. To add it: adb install BinnacleLogger.apk "
                    + "from github.com/lotech/binnacle/releases"));
        }
        return stack(box.toArray(new View[0]));
    }

    private boolean installed(String pkg) {
        try {
            getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (android.content.pm.PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private View uninstallCard(String title, String summary, final String pkg) {
        LinearLayout card = card(title, summary);
        TextView b = chip("Uninstall");
        b.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                try {
                    startActivity(new Intent(Intent.ACTION_DELETE, android.net.Uri.parse("package:" + pkg)));
                } catch (RuntimeException e) {
                    Toast.makeText(HubActivity.this, "Couldn't open the uninstaller", Toast.LENGTH_SHORT).show();
                }
            }
        });
        card.addView(b, new LinearLayout.LayoutParams(dp(150), dp(BUTTON_DP)));
        return card;
    }

    /**
     * Whether Binnacle is the Home app, and a Set as Default button that opens Android's own Home
     * app picker (an app can't make itself the default). The Display tab is rebuilt on return
     * (onResume), so the card shows the new state.
     */
    private View homeCard() {
        final boolean isDefault = isDefaultHome();
        LinearLayout card = card("Home app", isDefault ? null : "The stock dash stays installed");
        TextView b = chip(isDefault ? "Default" : "Set as Default");
        b.setSelected(isDefault);
        b.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { openHomePicker(); }
        });
        card.addView(b, new LinearLayout.LayoutParams(dp(200), dp(BUTTON_DP)));
        return card;
    }

    private boolean isDefaultHome() {
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        ResolveInfo ri = getPackageManager().resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY);
        return ri != null && ri.activityInfo != null && getPackageName().equals(ri.activityInfo.packageName);
    }

    /** Android's Home app setting, else the default-apps screen, else Settings itself. */
    private void openHomePicker() {
        String[] actions = {Settings.ACTION_HOME_SETTINGS, Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS, Settings.ACTION_SETTINGS};
        for (String a : actions) {
            try {
                startActivity(new Intent(a).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return;
            } catch (RuntimeException ignored) {
                // not on this ROM: try the next
            }
        }
        Toast.makeText(this, "Couldn't open the Home app setting", Toast.LENGTH_LONG).show();
    }

    /**
     * Reset settings: clears Binnacle's own settings (theme, car model, driving side, ...) and
     * restarts the dash, so the first-start questions come back. Downloaded car images stay.
     * Remove Binnacle: Android's uninstaller for this app; with Binnacle gone the unit falls
     * back to the stock dash, which was never removed. Both ask first.
     */
    /** The setup wizard again (first-start steps; none of them is forced). */
    private View setupCard() {
        LinearLayout card = card("Setup", null);
        TextView b = chip("Run setup");
        b.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { SetupActivity.start(HubActivity.this); }
        });
        card.addView(b, new LinearLayout.LayoutParams(dp(220), dp(BUTTON_DP)));
        return card;
    }

    private View resetCard() {
        LinearLayout card = card("Reset or remove", null);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        TextView reset = chip("Reset settings");
        reset.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                BinnacleDialog.show(HubActivity.this, "Reset settings?",
                        "Binnacle's settings go back to how they started and the dash restarts.\nDownloaded car images are kept.",
                        new String[]{"Cancel", "Reset"}, true, new BinnacleDialog.Choice() {
                            @Override public void chosen(int index) { if (index == 1) resetSettings(); }
                        });
            }
        });
        TextView remove = chip("Remove Binnacle");
        remove.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                BinnacleDialog.show(HubActivity.this, "Remove Binnacle?",
                        "The unit goes back to the stock dash, which is still installed.\nBinnacle's settings and downloaded car images are deleted with it."
                                + (stockCameraDisabled()
                                ? "\n\nThe stock camera screen is still switched off, so it won't open by itself in Drive. "
                                + "Turn it back on first (docs/UNIT-CHANGES.md: pm enable com.xbs.frontcam/com.xbs.frontcam.CameraActivity)."
                                : ""),
                        new String[]{"Cancel", "Remove"}, true, new BinnacleDialog.Choice() {
                            @Override public void chosen(int index) {
                                if (index != 1) return;
                                try {
                                    startActivity(new Intent(Intent.ACTION_DELETE, android.net.Uri.parse("package:" + getPackageName())));
                                } catch (RuntimeException e) {
                                    Toast.makeText(HubActivity.this, "Couldn't open the uninstaller", Toast.LENGTH_SHORT).show();
                                }
                            }
                        });
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(BUTTON_DP), 1);
        lp.setMargins(0, 0, dp(12), 0);
        row.addView(reset, lp);
        row.addView(remove, new LinearLayout.LayoutParams(0, dp(BUTTON_DP), 1));
        card.addView(row);
        return card;
    }

    /**
     * True if the stock camera screen was switched off on this unit (docs/UNIT-CHANGES.md): removing
     * Binnacle doesn't switch it back on. Only reads the component's state.
     */
    private boolean stockCameraDisabled() {
        try {
            int s = getPackageManager().getComponentEnabledSetting(
                    new ComponentName("com.xbs.frontcam", "com.xbs.frontcam.CameraActivity"));
            return s == PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                    || s == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER;
        } catch (RuntimeException e) {
            return false;                              // not on this unit
        }
    }

    /** Clears every setting, then restarts the app at the dash so nothing keeps the old values. */
    private void resetSettings() {
        prefs.edit().clear().commit();
        Intent dash = getPackageManager().getLaunchIntentForPackage(getPackageName());
        if (dash != null) startActivity(dash.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        System.exit(0);   // the activity start brings the process back with fresh settings
    }

    /** Two named choices for an on/off setting (off = first label), shown like the other choice cards. */
    private View pairCard(String title, final String key, String offLabel, String onLabel) {
        LinearLayout card = card(title, null);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        boolean on = prefs.getBoolean(key, false);
        final TextView off = chip(offLabel), yes = chip(onLabel);
        off.setSelected(!on);
        yes.setSelected(on);
        View.OnClickListener pick = new View.OnClickListener() {
            @Override public void onClick(View v) {
                prefs.edit().putBoolean(key, v == yes).apply();
                off.setSelected(v == off);
                yes.setSelected(v == yes);
            }
        };
        off.setOnClickListener(pick);
        yes.setOnClickListener(pick);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(BUTTON_DP), 1);
        lp.setMargins(0, 0, dp(12), 0);
        row.addView(off, lp);
        row.addView(yes, new LinearLayout.LayoutParams(0, dp(BUTTON_DP), 1));
        card.addView(row);
        return card;
    }

    private View switchCard(String title, String summary, final String key, boolean def) {
        LinearLayout card = card(title, summary);
        final TextView b = chip(prefs.getBoolean(key, def) ? "On" : "Off");
        b.setSelected(prefs.getBoolean(key, def));
        b.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                boolean now = !b.isSelected();
                prefs.edit().putBoolean(key, now).apply();
                b.setSelected(now);
                b.setText(now ? "On" : "Off");
            }
        });
        card.addView(b, new LinearLayout.LayoutParams(dp(150), dp(BUTTON_DP)));
        return card;
    }

    /** − value + for a number kept as a string preference (as the old EditText setting stored it). */
    private View stepperCard(String title, String summary, final String key, int def, final int min, final int max, final int step, final String unit) {
        LinearLayout card = card(title, summary);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int start;
        try {
            start = Integer.parseInt(prefs.getString(key, String.valueOf(def)).trim());
        } catch (RuntimeException e) {
            start = def;
        }
        final int[] value = {Math.max(min, Math.min(max, start))};
        if (value[0] != start) prefs.edit().putString(key, String.valueOf(value[0])).apply();   // what's shown is what's used
        final TextView shown = text(value[0] + " " + unit, 22, Style.FG);
        shown.setGravity(Gravity.CENTER);
        TextView minus = chip("−"), plus = chip("+");
        View.OnClickListener change = new View.OnClickListener() {
            @Override public void onClick(View v) {
                value[0] = Math.max(min, Math.min(max, value[0] + (v.getTag() == null ? -step : step)));
                prefs.edit().putString(key, String.valueOf(value[0])).apply();
                shown.setText(value[0] + " " + unit);
            }
        };
        plus.setTag("+");
        minus.setOnClickListener(change);
        plus.setOnClickListener(change);
        row.addView(minus, new LinearLayout.LayoutParams(dp(72), dp(BUTTON_DP)));
        row.addView(shown, new LinearLayout.LayoutParams(0, dp(BUTTON_DP), 1));
        row.addView(plus, new LinearLayout.LayoutParams(dp(72), dp(BUTTON_DP)));
        card.addView(row);
        return card;
    }

    /**
     * The unit's own Bluetooth auto-connect switch (same setting as the stock Car settings >
     * Bluetooth switch; local to the unit, never sent to the car). Read and written through
     * LocalShell, since a normal app may not write Settings.System.
     */
    /**
     * Car images for the car's colour (from the car) and the driving side: built in, downloaded,
     * or a Download button that fetches the pack from the project's GitHub release (CarPacks).
     */
    /** The colour as shown: the stock name "silver" is the dark grey paint. */
    private static String colourName(String colour) { return "silver".equals(colour) ? "dark grey" : colour; }

    /**
     * Car colour for the images: Auto uses what the car reports (shown in the summary), or pick one
     * by hand. A colour that isn't built in shows a Download button on the Car images card below.
     */
    private View carColourCard() {
        int reported = DashApp.get(this).car.carColour;
        String summary = reported >= 0 && reported < CarPacks.COLOURS.length
                ? "Auto: " + colourName(CarPacks.COLOURS[reported]) + ", from the car" : "Auto: from the car (not reported yet)";
        LinearLayout card = card("Car colour", summary);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        final String current = prefs.getString("car_colour", "auto");
        final List<View> buttons = new ArrayList<>();
        // Auto first and wider (what most people want), then a swatch per paint, in carcolor order
        String[] values = {"auto", "0", "1", "2", "3", "4"};
        for (int i = 0; i < values.length; i++) {
            final String value = values[i];
            final View b;
            if (i == 0) {
                b = chip("Auto");
            } else {
                b = new View(this);
                b.setFocusable(true);
                b.setClickable(true);
                b.setContentDescription(colourName(CarPacks.COLOURS[i - 1]));
                b.setBackground(swatch(PAINT[i - 1]));
            }
            b.setSelected(value.equals(current));
            b.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    prefs.edit().putString("car_colour", value).apply();
                    for (View o : buttons) o.setSelected(o == v);
                }
            });
            buttons.add(b);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(i == 0 ? 0 : dp(BUTTON_DP), dp(BUTTON_DP), i == 0 ? 1 : 0);
            lp.setMargins(i == 0 ? 0 : dp(10), 0, 0, 0);
            row.addView(b, lp);
        }
        card.addView(row);
        return card;
    }

    /** Paint swatches in carcolor order: white, red, blue, black, dark grey (silver). */
    private static final int[] PAINT = {0xfff2f2f0, 0xffb3202a, 0xff1f4f9f, 0xff121314, 0xff4b4f55};

    /** A round-cornered paint swatch: a thin outline at rest, a thick accent ring when chosen or focused. */
    private Drawable swatch(int paint) {
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_selected}, shape(paint, Style.ACCENT, 12, 4));
        s.addState(new int[]{android.R.attr.state_focused}, shape(paint, Style.ACCENT, 12, 3));
        s.addState(new int[]{}, shape(paint, Style.LINE, 12, 1));
        return s;
    }

    /** The car colour the Car images card was built for (-1: not reported yet). */
    private int packCardColour = Integer.MIN_VALUE;

    private View carPicturesCard() {
        final DashApp app = DashApp.get(this);
        int c = app.carColour();
        packCardColour = c;
        if (c < 0) {                                   // not reported yet: don't guess a colour to fetch
            LinearLayout card = card("Car images", "Waiting for the car to report its colour");
            TextView wait = chip("…");
            wait.setEnabled(false);
            card.addView(wait, new LinearLayout.LayoutParams(dp(150), dp(BUTTON_DP)));
            return card;
        }
        final String colour = c >= 0 && c < CarPacks.COLOURS.length ? CarPacks.COLOURS[c] : "silver";
        final String hand = app.artSet();   // the tab rebuilds when the model or side changes
        String what = (app.modelY() ? "Model Y, " : "Model 3, ") + colourName(colour)
                + (hand.endsWith("lhd") ? ", left-hand drive" : ", right-hand drive");
        final LinearLayout card = card("Car images", what);
        final TextView b = chip("…");
        final Runnable refresh = new Runnable() {
            @Override public void run() {
                boolean bundled = CarPacks.bundled(HubActivity.this, hand, colour);
                boolean got = CarPacks.downloaded(HubActivity.this, hand, colour);
                boolean busy = CarPacks.downloading(hand, colour);
                b.setEnabled(!busy && (!bundled || got));
                b.setSelected(bundled || got);
                b.setText(busy ? "Downloading…" : got ? "Remove" : bundled ? "Built in" : "Download");
            }
        };
        refresh.run();
        b.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (CarPacks.downloaded(HubActivity.this, hand, colour)) {
                    CarPacks.remove(HubActivity.this, hand, colour);
                    DashApp.get(HubActivity.this).reloadArt();
                    refresh.run();
                    return;
                }
                b.setEnabled(false);
                b.setText("Downloading…");
                new Thread(new Runnable() {
                    @Override public void run() {
                        String err = null;
                        try {
                            CarPacks.download(HubActivity.this, hand, colour, new CarPacks.Progress() {
                                @Override public void onProgress(final long bytes) {
                                    main.post(new Runnable() {
                                        @Override public void run() { b.setText(Math.max(1, bytes >> 20) + " MB…"); }
                                    });
                                }
                            });
                        } catch (Exception e) {
                            err = e.getMessage();
                        }
                        final String problem = err;
                        // through the main looper, not the button: the card may be gone by now
                        // (tab changed, settings closed), but the dash must still pick the pack up
                        main.post(new Runnable() {
                            @Override public void run() {
                                if (problem == null) app.reloadArt();
                                else Toast.makeText(app, "Couldn't download: " + problem, Toast.LENGTH_LONG).show();
                                app.notifyChanged(DashApp.PACKS_CHANGED);   // refreshes the card on screen, even a newer one
                            }
                        });
                    }
                }, "car-pack").start();
            }
        });
        card.addView(b, new LinearLayout.LayoutParams(dp(150), dp(BUTTON_DP)));
        return card;
    }

    private View btAutoConnectCard() {
        LinearLayout card = card("Bluetooth auto-connect", "Reconnect the phone when the car wakes");
        final TextView b = chip("…");
        b.setEnabled(false);
        runShell(LocalShell.Command.BT_AUTOCONNECT_GET, b, null);
        b.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                boolean now = !b.isSelected();
                runShell(now ? LocalShell.Command.BT_AUTOCONNECT_ON : LocalShell.Command.BT_AUTOCONNECT_OFF, b, now);
            }
        });
        card.addView(b, new LinearLayout.LayoutParams(dp(150), dp(BUTTON_DP)));
        return card;
    }

    /** Runs cmd off the main thread; shows On / Off on b from the reply (a GET) or from `set`. */
    /**
     * LocalShell reaches the unit's own ADB daemon, which is off on a fresh unit (it's turned on
     * once through EngineerMode, see docs/ADB-GUIDE.md): the connection is refused then.
     */
    private static final String NO_ADB = "no network adb";

    private void runShell(final LocalShell.Command cmd, final TextView b, final Boolean set) {
        b.setEnabled(false);
        new Thread(new Runnable() {
            @Override public void run() {
                Boolean on = set;
                String err = null;
                try {
                    String out = LocalShell.run(cmd);
                    if (on == null) on = out.startsWith("true");
                } catch (java.net.ConnectException e) {
                    err = NO_ADB;
                } catch (Exception e) {
                    err = e.getMessage();
                }
                final Boolean shown = on;
                final String problem = err;
                b.post(new Runnable() {
                    @Override public void run() {
                        b.setEnabled(true);
                        if (NO_ADB.equals(problem)) { b.setText("Needs network ADB"); return; }
                        if (problem != null) { b.setText(set == null ? "Couldn't read" : "Couldn't change"); return; }
                        b.setSelected(shown);
                        b.setText(shown ? "On" : "Off");
                    }
                });
            }
        }, "local-shell").start();
    }

    /** A card with one button that runs a fixed LocalShell command off the main thread. */
    private View actionCard(String title, String summary, String label, final LocalShell.Command cmd) {
        LinearLayout card = card(title, summary);
        final TextView b = chip(label);
        b.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                b.setEnabled(false);
                b.setText("Working…");
                new Thread(new Runnable() {
                    @Override public void run() {
                        String result;
                        try {
                            LocalShell.run(cmd);
                            result = "Done";
                        } catch (java.net.ConnectException e) {
                            // a fresh unit has no network ADB: for End CarPlay, Android's Wi-Fi screen does
                            // the same job by hand (Wi-Fi off and on)
                            result = cmd == LocalShell.Command.END_CARPLAY ? "Turn Wi-Fi off and on" : "Needs network ADB";
                            if (cmd == LocalShell.Command.END_CARPLAY) b.post(new Runnable() {
                                @Override public void run() {
                                    try {
                                        startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS));
                                    } catch (RuntimeException ignored) {
                                        // no Wi-Fi screen: the label says what to do
                                    }
                                }
                            });
                        } catch (Exception e) {
                            result = "Couldn't: " + e.getMessage();
                        }
                        final String shown = result;
                        b.post(new Runnable() {
                            @Override public void run() {
                                b.setText(shown);
                                b.setEnabled(true);
                            }
                        });
                    }
                }, "local-shell").start();
            }
        });
        card.addView(b, new LinearLayout.LayoutParams(-1, dp(BUTTON_DP)));
        return card;
    }

    /**
     * Text-only card. Focusable, with an accent outline when focused, so the steering wheel can
     * reach it (and the page scrolls to it) even though it has no buttons.
     */
    private View infoCard(String title, String body) {
        LinearLayout card = card(title, body);
        card.setFocusable(true);
        StateListDrawable bg = new StateListDrawable();
        bg.addState(new int[]{android.R.attr.state_focused}, shape(Style.PANEL, Style.ACCENT, 14, 3));
        bg.addState(new int[]{}, card.getBackground());
        card.setBackground(bg);
        return card;
    }

    /**
     * Updates: checks this project's latest GitHub release on a tap (never by itself) and, if it is
     * newer, downloads it into Android's installer, which asks before it installs (Updater). Only
     * in Park: installing restarts the dash.
     */
    private View updateCard() {
        LinearLayout card = card("Updates", "Binnacle " + versionName() + " · checks GitHub over Wi-Fi when you tap");
        final TextView b = chip(Updater.installing() ? "Downloading…" : "Check for updates");
        b.setEnabled(!Updater.installing());
        b.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                b.setEnabled(false);
                b.setText("Checking…");
                new Thread(new Runnable() {
                    @Override public void run() {
                        Updater.Release found = null;
                        String err = null;
                        try {
                            found = Updater.check();
                        } catch (Exception e) {
                            err = e.getMessage();
                        }
                        final Updater.Release r = found;
                        final String problem = err;
                        main.post(new Runnable() {
                            @Override public void run() {
                                b.setEnabled(true);
                                if (r == null) {
                                    b.setText("Check for updates");
                                    Toast.makeText(HubActivity.this, "Couldn't check for updates: " + problem
                                            + "\nIs the dash on Wi-Fi?", Toast.LENGTH_LONG).show();
                                } else if (r.versionCode <= Updater.installedCode(HubActivity.this)) {
                                    b.setText("Up to date");
                                } else {
                                    b.setText("Update to " + r.versionName);
                                    offerUpdate(r, b);
                                }
                            }
                        });
                    }
                }, "update-check").start();
            }
        });
        card.addView(b, new LinearLayout.LayoutParams(dp(260), dp(BUTTON_DP)));
        return card;
    }

    /** Asks, then downloads r into Android's installer (b shows progress). */
    private void offerUpdate(final Updater.Release r, final TextView b) {
        long mb = Math.max(1, (r.size + (1 << 19)) >> 20);
        BinnacleDialog.show(this, "Update to Binnacle " + r.versionName + "?",
                "Downloads " + mb + " MB from GitHub, then Android asks you to confirm the install.\n"
                        + "The dash closes and comes back while it installs. Settings and car images are kept.",
                new String[]{"Not now", "Update"}, true, new BinnacleDialog.Choice() {
                    @Override public void chosen(int index) {
                        if (index != 1) return;
                        b.setOnClickListener(new View.OnClickListener() {
                            @Override public void onClick(View v) { offerUpdate(r, b); }
                        });
                        startUpdate(r, b);
                    }
                });
    }

    private void startUpdate(final Updater.Release r, final TextView b) {
        final DashApp app = DashApp.get(this);
        if (app.car.gear != CarState.GEAR_P) {   // Park only, not just "not D or R": Neutral or no car data aren't safe
            Toast.makeText(this, "Update in Park: installing restarts the dash", Toast.LENGTH_LONG).show();
            return;
        }
        if (!Updater.allowedToInstall(this)) {
            BinnacleDialog.show(this, "Allow Binnacle to install updates",
                    "Android asks once. On the next screen, turn on \"Allow from this source\", come back, and tap Update again.",
                    new String[]{"Cancel", "Open setting"}, true, new BinnacleDialog.Choice() {
                        @Override public void chosen(int index) {
                            if (index != 1) return;
                            try {
                                startActivity(new Intent("android.settings.MANAGE_UNKNOWN_APP_SOURCES",
                                        android.net.Uri.parse("package:" + getPackageName())));
                            } catch (RuntimeException e) {
                                Toast.makeText(HubActivity.this, "Couldn't open the setting: Android settings → Apps → Binnacle → Install unknown apps",
                                        Toast.LENGTH_LONG).show();
                            }
                        }
                    });
            return;
        }
        b.setEnabled(false);
        b.setText("Downloading…");
        new Thread(new Runnable() {
            @Override public void run() {
                String err = null;
                try {
                    Updater.install(app, r, new Updater.Progress() {
                        long shownMb = -1;
                        @Override public void onProgress(final long bytes) {
                            final long mbNow = bytes >> 20;
                            if (mbNow == shownMb) return;
                            shownMb = mbNow;
                            main.post(new Runnable() {
                                @Override public void run() { b.setText(Math.max(1, mbNow) + " MB…"); }
                            });
                        }
                    });
                } catch (Exception e) {
                    err = e.getMessage();
                }
                final String problem = err;
                main.post(new Runnable() {
                    @Override public void run() {
                        b.setEnabled(true);
                        b.setText(problem == null ? "Installing…" : "Update to " + r.versionName);
                        if (problem != null) Toast.makeText(app, "Couldn't update: " + problem, Toast.LENGTH_LONG).show();
                    }
                });
            }
        }, "update").start();
    }

    /** About: who made it and where it lives; the button opens the full About pop-up. */
    private View aboutCard() {
        LinearLayout card = card("About", "Binnacle " + versionName() + " · Made by Lotech");
        TextView b = chip("About Binnacle");
        b.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showAbout(); }
        });
        card.addView(b, new LinearLayout.LayoutParams(dp(220), dp(BUTTON_DP)));
        return card;
    }

    private String versionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "";
        }
    }

    /**
     * About pop-up: the app, this unit (read from Android only: nothing is asked of the car or the
     * MCU, and no serial numbers are shown) and the 3D model credits.
     */
    private void showAbout() {
        DashApp app = DashApp.get(this);
        String installed = "";
        try {
            long t = getPackageManager().getPackageInfo(getPackageName(), 0).lastUpdateTime;
            installed = ", installed " + new java.text.SimpleDateFormat("d MMM yyyy", java.util.Locale.UK).format(new java.util.Date(t));
        } catch (PackageManager.NameNotFoundException ignored) {
            // no date
        }
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        String feed = app.feedProblem() == null ? "receiving" : app.feedProblem();
        String logger = !app.loggerInstalled() ? "not installed" : app.loggerRecording() ? "installed, recording" : "installed";
        String app1 = "Made by Lotech\n"
                + "github.com/lotech/binnacle\n"
                + "A read-only dash: it only listens to the car and never sends anything to it.\n\n"
                + "3D models (CC BY 4.0, modified)\n"
                + "Model 3: \"Tesla 2018 Model 3\" by Ameer Studio\n"
                + "Model Y: \"2021 Tesla Model Y\" by tonielpro520";
        String unit = androidVersion() + "\n"
                + "Firmware " + android.os.Build.DISPLAY.replace(" release-keys", "") + "\n"
                + "Board " + android.os.Build.BOARD + " · screen " + dm.widthPixels + " × " + dm.heightPixels + ", " + dm.densityDpi + " dpi\n"
                + "Memory " + memory() + "\n"
                + "Storage " + storage() + "\n"
                + "GPS " + app.compass().summary() + "\n"
                + "Car data: " + feed + "\n"
                + "Drive logger: " + logger;

        final android.app.Dialog d = new android.app.Dialog(this);
        d.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(26), dp(18), dp(18), dp(22));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Style.PANEL);
        bg.setStroke(dp(1), Style.LINE);
        bg.setCornerRadius(dp(22));
        box.setBackground(bg);

        // header: icon, name and version, and an X that starts focused so one wheel press closes it
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        android.widget.ImageView icon = new android.widget.ImageView(this);
        icon.setImageResource(R.drawable.ic_launcher);
        head.addView(icon, new LinearLayout.LayoutParams(dp(52), dp(52)));
        LinearLayout names = new LinearLayout(this);
        names.setOrientation(LinearLayout.VERTICAL);
        names.setPadding(dp(14), 0, 0, 0);
        TextView title = text("Binnacle", 22, Style.FG);
        title.setTypeface(Style.DISPLAY);
        names.addView(title);
        names.addView(text("Version " + versionName() + installed, 13, Style.MUTED));
        head.addView(names, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView close = chip("✕");
        close.setContentDescription("Close");
        close.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { d.dismiss(); }
        });
        head.addView(close, new LinearLayout.LayoutParams(dp(52), dp(52)));
        box.addView(head);

        // two columns: the app on the left, this unit on the right
        LinearLayout cols = new LinearLayout(this);
        cols.setOrientation(LinearLayout.HORIZONTAL);
        cols.setPadding(0, dp(14), 0, 0);
        cols.addView(aboutColumn("Binnacle", app1), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        View gap = new View(this);
        cols.addView(gap, new LinearLayout.LayoutParams(dp(24), 1));
        cols.addView(aboutColumn("This unit", unit), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        box.addView(cols);

        // the X is the only thing to press, so the wheel's OK closes straight away (no focus step first)
        d.setOnKeyListener(new android.content.DialogInterface.OnKeyListener() {
            @Override public boolean onKey(android.content.DialogInterface di, int code, KeyEvent e) {
                if (code != KeyEvent.KEYCODE_ENTER && code != KeyEvent.KEYCODE_DPAD_CENTER) return false;
                if (e.getAction() == KeyEvent.ACTION_UP) d.dismiss();
                return true;
            }
        });
        d.setContentView(box);
        android.view.Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0x00000000));
            w.setLayout(dp(860), ViewGroup.LayoutParams.WRAP_CONTENT);
            w.setDimAmount(0.55f);
        }
        d.show();
        close.requestFocus();
    }

    /** RAM in use of the total, from Android's memory info. */
    private String memory() {
        android.app.ActivityManager am = (android.app.ActivityManager) getSystemService(ACTIVITY_SERVICE);
        if (am == null) return "unknown";
        android.app.ActivityManager.MemoryInfo mi = new android.app.ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        return android.text.format.Formatter.formatShortFileSize(this, mi.totalMem - mi.availMem) + " used of "
                + android.text.format.Formatter.formatShortFileSize(this, mi.totalMem);
    }

    /** Internal storage (the data partition) in use of its size. */
    private String storage() {
        try {
            android.os.StatFs fs = new android.os.StatFs(android.os.Environment.getDataDirectory().getPath());
            long total = fs.getTotalBytes(), free = fs.getAvailableBytes();
            return android.text.format.Formatter.formatShortFileSize(this, total - free) + " used of "
                    + android.text.format.Formatter.formatShortFileSize(this, total);
        } catch (RuntimeException e) {
            return "unknown";
        }
    }

    private View aboutColumn(String heading, String body) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.addView(text(heading, 15, Style.FG));
        TextView t = text(body, 13, Style.MUTED);
        t.setPadding(0, dp(4), 0, 0);
        col.addView(t);
        return col;
    }

    /**
     * The real Android version from the API level: this unit's firmware calls itself 12 (RELEASE)
     * but is API 29, which is Android 10.
     */
    private static String androidVersion() {
        int api = android.os.Build.VERSION.SDK_INT;
        String[] names = {"8", "8.1", "9", "10", "11", "12", "12L", "13", "14", "15"};   // API 26..35
        String real = api >= 26 && api - 26 < names.length ? names[api - 26] : "API " + api;
        String label = android.os.Build.VERSION.RELEASE;
        return "Android " + real + " (API " + api + ")" + (label.equals(real) ? "" : ", labelled " + label);
    }

    /** Cards one under another in a single grid cell, with the same gap as between grid cells. */
    private View stack(View... cards) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        for (int i = 0; i < cards.length; i++) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(0, i == 0 ? 0 : dp(12), 0, 0);
            box.addView(cards[i], lp);
        }
        return box;
    }

    private String carplayLayoutText() {
        String now = StockScreens.carplayLayout(this);
        return (now == null ? "Current: unknown" : "Current: " + now)
                + ". Recommended split: CarPlay on the left, so the speed sits on the right, away from the car's own speedo"
                + " (or Full width). Change it in the stock dash: Settings, Screen display mode; the unit restarts to apply it.";
    }

    // ---- building blocks ----

    private TextView tabButton(String label) {
        TextView t = text(label, 20, Style.MUTED);
        t.setGravity(Gravity.CENTER_VERTICAL);
        t.setPadding(dp(16), 0, dp(12), 0);
        t.setFocusable(true);
        t.setClickable(true);
        t.setBackground(states(Style.PANEL, 0xff1f5b80, Style.LINE, 10));   // selected: the same blue as a selected button
        return t;
    }

    private LinearLayout.LayoutParams tabParams() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
        lp.setMargins(0, 0, 0, dp(4));
        return lp;
    }

    private TextView chip(String label) {
        TextView b = text(label, 15, Style.FG);
        b.setGravity(Gravity.CENTER);
        b.setFocusable(true);
        b.setClickable(true);
        b.setBackground(states(Style.PANEL2, 0xff1f5b80, Style.LINE, 12));
        // white on the blue selected fill in both themes (the light theme's dark text was unreadable on it)
        b.setTextColor(new android.content.res.ColorStateList(
                new int[][]{{android.R.attr.state_selected}, {}}, new int[]{0xffffffff, Style.FG}));
        return b;
    }

    /** No tile at rest; an accent outline when the wheel focuses it, a light fill while pressed. */
    private Drawable bareBackground() {
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_focused, android.R.attr.state_pressed}, shape(Style.PANEL2, Style.ACCENT, 14, 3));
        s.addState(new int[]{android.R.attr.state_focused}, shape(0x00000000, Style.ACCENT, 14, 3));
        s.addState(new int[]{android.R.attr.state_pressed}, shape(Style.PANEL2, 0, 14, 0));
        s.addState(new int[]{}, new android.graphics.drawable.ColorDrawable(0x00000000));
        return s;
    }

    /** Rounded background: normal / selected / pressed fills, and an accent outline when focused. */
    private Drawable states(int normal, int selected, int pressed, int radiusDp) {
        StateListDrawable s = new StateListDrawable();
        // focus is an outline only, so a focused chip never looks like the chosen one
        s.addState(new int[]{android.R.attr.state_focused, android.R.attr.state_selected}, shape(selected, Style.ACCENT, radiusDp, 3));
        s.addState(new int[]{android.R.attr.state_focused}, shape(normal, Style.ACCENT, radiusDp, 3));
        s.addState(new int[]{android.R.attr.state_pressed}, shape(pressed, 0, radiusDp, 0));
        s.addState(new int[]{android.R.attr.state_selected}, shape(selected, 0, radiusDp, 0));
        s.addState(new int[]{}, shape(normal, Style.LINE, radiusDp, 1));
        return s;
    }

    private GradientDrawable shape(int fill, int stroke, int radiusDp, int strokeDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(radiusDp));
        if (strokeDp > 0) g.setStroke(dp(strokeDp), stroke);
        return g;
    }

    private TextView text(String s, float sp, int colour) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(colour);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp * Style.textScale);
        return t;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
