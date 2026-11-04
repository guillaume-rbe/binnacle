package app.binnacle;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Bundle;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.InputStream;
import java.util.ArrayList;

/**
 * Setup wizard: shown on first start, and again from Advanced → Run setup. One step per screen,
 * each with a clear choice or Not now, so nothing here is forced and every step can be changed
 * later in settings:
 * <ol>
 *   <li>welcome;</li>
 *   <li>your car: model (with a picture of each), driving side, colour;</li>
 *   <li>make Binnacle the Home app (Android's own Home picker: an app can't make itself the default);</li>
 *   <li>compass: location permission;</li>
 *   <li>camera in the dash: camera permission;</li>
 *   <li>done.</li>
 * </ol>
 * Only Binnacle's own settings and Android permission prompts: nothing is sent to the car.
 */
public final class SetupActivity extends Activity {
    static final String DONE_KEY = "setup_done";
    /** The step to come back to if the wizard was closed under us (Android's Home picker). */
    private static final String STEP_KEY = "setup_step";
    private static final int REQ_LOCATION = 11, REQ_CAMERA = 12;
    private static final int STEPS = 6;
    private static final int BUTTON_DP = 52;
    /** Paint swatches in carcolor order: white, red, blue, black, dark grey (silver). */
    private static final int[] PAINT = {0xfff2f2f0, 0xffb3202a, 0xff1f4f9f, 0xff121314, 0xff4b4f55};

    private SharedPreferences prefs;
    private FrameLayout page;
    private LinearLayout dots;
    private int step;

    /** Opens the wizard (from first start or Advanced). */
    static void start(Activity from) {
        from.startActivity(new Intent(from, SetupActivity.class));
    }

    /** True once the wizard has been finished or skipped (or settings from before it exist). */
    static boolean done(SharedPreferences p) {
        // a run under way (a step saved) wins, also a re-run from Advanced after an earlier finish: the
        // Home picker brings the dash forward over the wizard and it has to come back. Installs from
        // before the wizard already answered its questions.
        if (p.contains(STEP_KEY)) return false;
        return p.getBoolean(DONE_KEY, false) || (p.contains("car_model") && p.contains("drive_side"));
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = DashApp.get(this).prefs();
        step = state != null ? state.getInt("step", 0) : prefs.getInt(STEP_KEY, 0);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Style.BG);
        root.setPadding(dp(48), dp(10), dp(48), dp(12));
        page = new FrameLayout(this);
        root.addView(page, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        dots = new LinearLayout(this);
        dots.setGravity(Gravity.CENTER);
        root.addView(dots, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(16)));
        setContentView(root);
        show(step);
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putInt("step", step);
    }

    @Override protected void onResume() {
        super.onResume();
        if (step == 2) show(2);     // back from Android's Home picker: show whether it took
    }

    @Override public void onBackPressed() {
        if (step > 0) show(step - 1); else finishSetup();
    }

    private void next() {
        if (step + 1 >= STEPS) finishSetup(); else show(step + 1);
    }

    private void finishSetup() {
        prefs.edit().putBoolean(DONE_KEY, true).remove(STEP_KEY).apply();
        finish();
    }

    private void show(int i) {
        step = i;
        prefs.edit().putInt(STEP_KEY, i).apply();
        page.removeAllViews();
        View v;
        switch (i) {
            case 0: v = welcome(); break;
            case 1: v = car(); break;
            case 2: v = home(); break;
            case 3: v = compass(); break;
            case 4: v = camera(); break;
            default: v = finished(); break;
        }
        page.addView(v);
        dots.removeAllViews();
        for (int d = 0; d < STEPS; d++) {
            View dot = new View(this);
            GradientDrawable g = new GradientDrawable();
            g.setShape(GradientDrawable.OVAL);
            g.setColor(d == i ? Style.ACCENT : Style.LINE);
            dot.setBackground(g);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(8), dp(8));
            lp.setMargins(dp(5), 0, dp(5), 0);
            dots.addView(dot, lp);
        }
        // the main button (the usual answer, always last) takes the wheel's focus
        ArrayList<View> f = page.getFocusables(View.FOCUS_FORWARD);
        if (!f.isEmpty()) f.get(f.size() - 1).requestFocus();
    }

    // ---- steps ----

    private View welcome() {
        LinearLayout s = stepLayout("Welcome to Binnacle",
                "A read-only dash for your Tesla's instrument cluster. It only listens to the car and never sends "
                        + "anything to it, and the stock dash stays installed, so you can go back at any time.\n\n"
                        + "A few quick questions to set it up. You can skip any of them and change everything later in settings.");
        ImageView icon = new ImageView(this);
        icon.setImageResource(R.drawable.ic_launcher);
        s.addView(icon, 0, new LinearLayout.LayoutParams(dp(64), dp(64)));
        s.addView(buttons(new String[]{"Skip setup", "Start"}, new Runnable[]{
                new Runnable() { @Override public void run() { finishSetup(); } },
                new Runnable() { @Override public void run() { next(); } }}));
        return s;
    }

    private View car() {
        LinearLayout s = stepLayout("Your car", null);
        // model: a picture of each, the chosen one ringed
        LinearLayout models = new LinearLayout(this);
        final View[] cards = new View[2];
        String[] values = {"3", "y"}, names = {"Model 3", "Model Y"}, dirs = {"cars/rhd/silver", "cars/y-rhd/silver"};
        for (int m = 0; m < 2; m++) {
            final String value = values[m];
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setGravity(Gravity.CENTER);
            card.setFocusable(true);
            card.setClickable(true);
            card.setPadding(dp(8), dp(16), dp(8), dp(8));     // room above the car
            card.setBackground(pictureCard());
            ImageView pic = new ImageView(this);
            pic.setImageBitmap(asset(dirs[m] + "/turn/0.png"));
            pic.setScaleType(ImageView.ScaleType.FIT_CENTER);
            card.addView(pic, new LinearLayout.LayoutParams(dp(200), dp(74)));
            TextView name = text(names[m], 17, Style.FG);
            name.setGravity(Gravity.CENTER);
            card.addView(name, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            card.setSelected(value.equals(prefs.getString("car_model", "3")));
            card.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    prefs.edit().putString("car_model", value).apply();
                    for (View c : cards) c.setSelected(c == v);
                }
            });
            cards[m] = card;
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
            lp.setMargins(m == 0 ? 0 : dp(16), 0, 0, 0);
            models.addView(card, lp);
        }
        LinearLayout row = new LinearLayout(this);
        row.addView(models, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.2f));
        // side and colour, stacked beside the pictures
        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        right.setPadding(dp(28), 0, 0, 0);
        right.addView(text("Steering wheel", 15, Style.MUTED));
        right.addView(choice("drive_side", prefs.getString("drive_side", "rhd"),
                new String[]{"lhd", "rhd"}, new String[]{"Left", "Right"}));
        TextView colourLabel = text("Colour", 15, Style.MUTED);
        colourLabel.setPadding(0, dp(14), 0, 0);
        right.addView(colourLabel);
        right.addView(colours());
        row.addView(right, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        s.addView(row);
        // the note shares the row with the buttons: the screen is only ~360 dp tall
        LinearLayout foot = new LinearLayout(this);
        foot.setGravity(Gravity.CENTER_VERTICAL);
        TextView note = text("Any car images that aren't built in download over Wi-Fi when needed.", 13, Style.MUTED);
        note.setPadding(0, dp(14), dp(16), 0);
        foot.addView(note, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        s.addView(foot);
        foot.addView(buttons(new String[]{"Back", "Next"}, new Runnable[]{
                new Runnable() { @Override public void run() { show(step - 1); } },
                new Runnable() { @Override public void run() {
                    if (!prefs.contains("car_model")) prefs.edit().putString("car_model", "3").apply();
                    if (!prefs.contains("drive_side")) setDriveSide("rhd");
                    next();
                } }}));
        return s;
    }

    private View home() {
        final boolean isDefault = isDefaultHome();
        LinearLayout s = stepLayout("Open Binnacle when the unit starts",
                isDefault ? "The stock dash is still installed if you ever want it back "
                        + "(Display → Home app, or Advanced → Remove Binnacle)."
                        : "Android shows its Home app list: pick Binnacle. The stock dash stays installed, and you can "
                        + "switch back the same way.");
        if (isDefault) s.addView(tick("Binnacle is your Home app"));
        s.addView(buttons(isDefault ? new String[]{"Next"} : new String[]{"Not now", "Set as Default"},
                isDefault ? new Runnable[]{new Runnable() { @Override public void run() { next(); } }}
                        : new Runnable[]{
                        new Runnable() { @Override public void run() { next(); } },
                        new Runnable() { @Override public void run() { openHomePicker(); } }}));
        return s;
    }

    private View compass() {
        final boolean granted = granted(Manifest.permission.ACCESS_FINE_LOCATION);
        LinearLayout s = stepLayout("Compass",
                "Shows the direction you're driving (N, NE, E …) from the unit's GPS. Binnacle uses location only for "
                        + "this, keeps nothing and sends it nowhere.");
        if (granted) s.addView(tick("Already allowed"));
        s.addView(buttons(granted ? new String[]{"Next"} : new String[]{"Not now", "Allow"},
                granted ? new Runnable[]{new Runnable() { @Override public void run() { next(); } }}
                        : new Runnable[]{
                        new Runnable() { @Override public void run() { next(); } },
                        new Runnable() { @Override public void run() {
                            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, REQ_LOCATION);
                        } }}));
        return s;
    }

    private View camera() {
        final boolean on = prefs.getBoolean("dash_camera", false) && granted(Manifest.permission.CAMERA);
        LinearLayout s = stepLayout("Forward camera in the dash",
                "When the car asks for the forward camera (shifting into Drive), Binnacle can show it in the centre "
                        + "window, smoothed for this screen, instead of the stock full-screen camera.");
        if (on) s.addView(tick("Already on"));
        s.addView(buttons(on ? new String[]{"Next"} : new String[]{"Not now", "Use the dash camera"},
                on ? new Runnable[]{new Runnable() { @Override public void run() { next(); } }}
                        : new Runnable[]{
                        new Runnable() { @Override public void run() { next(); } },
                        new Runnable() { @Override public void run() {
                            if (granted(Manifest.permission.CAMERA)) {
                                prefs.edit().putBoolean("dash_camera", true).apply();
                                next();
                            } else {
                                requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
                            }
                        } }}));
        return s;
    }

    private View finished() {
        LinearLayout s = stepLayout("All set",
                "Everything here can be changed in Apps → Settings, and Advanced → Run setup shows these steps again.\n\n"
                        + "For a complete takeover, some steps need doing by hand. See UNIT-CHANGES.md in the repo "
                        + "(github.com/lotech/binnacle) for details.");
        s.addView(buttons(new String[]{"Done"}, new Runnable[]{
                new Runnable() { @Override public void run() { finishSetup(); } }}));
        return s;
    }

    @Override public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        boolean ok = results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED;
        if (code == REQ_LOCATION) {
            if (ok) DashApp.get(this).compass().start();
            next();
        } else if (code == REQ_CAMERA) {
            if (ok) prefs.edit().putBoolean("dash_camera", true).apply();
            next();
        }
    }

    // ---- helpers ----

    private boolean granted(String permission) {
        return checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
    }

    private void setDriveSide(String side) {
        SharedPreferences.Editor e = prefs.edit().putString("drive_side", side);
        if (!prefs.contains("menu_side")) e.putString("menu_side", "lhd".equals(side) ? "left" : "right");
        e.apply();
    }

    private boolean isDefaultHome() {
        Intent h = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        ResolveInfo ri = getPackageManager().resolveActivity(h, PackageManager.MATCH_DEFAULT_ONLY);
        return ri != null && ri.activityInfo != null && getPackageName().equals(ri.activityInfo.packageName);
    }

    private void openHomePicker() {
        for (String a : new String[]{Settings.ACTION_HOME_SETTINGS, Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS, Settings.ACTION_SETTINGS}) {
            try {
                startActivity(new Intent(a));
                return;
            } catch (RuntimeException ignored) {
                // not on this ROM: try the next
            }
        }
    }

    private Bitmap asset(String path) {
        try (InputStream in = getAssets().open(path)) {
            return BitmapFactory.decodeStream(in);
        } catch (Exception e) {
            return null;
        }
    }

    /** A green tick and a short line: this step is already done, just carry on. */
    private TextView tick(String what) {
        TextView t = text("✓  " + what, 19, Style.GOOD);
        t.setPadding(0, 0, 0, dp(4));
        return t;
    }

    /** Title, optional body, then whatever the step adds. */
    private LinearLayout stepLayout(String title, String body) {
        LinearLayout s = new LinearLayout(this);
        s.setOrientation(LinearLayout.VERTICAL);
        s.setGravity(Gravity.CENTER_VERTICAL);
        TextView t = text(title, 28, Style.FG);
        t.setTypeface(Style.DISPLAY);
        t.setPadding(0, dp(6), 0, dp(10));
        s.addView(t);
        if (body != null) {
            TextView b = text(body, 17, Style.MUTED);
            b.setPadding(0, 0, 0, dp(14));
            b.setMaxWidth(dp(860));
            s.addView(b, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        return s;
    }

    /** Buttons right-aligned, the last one (the usual answer) filled. */
    private View buttons(String[] labels, final Runnable[] actions) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.END);
        row.setPadding(0, dp(14), 0, 0);
        for (int i = 0; i < labels.length; i++) {
            final int index = i;
            TextView b = text(labels[i], 17, Style.FG);
            b.setGravity(Gravity.CENTER);
            b.setFocusable(true);
            b.setClickable(true);
            b.setPadding(dp(28), 0, dp(28), 0);
            boolean primary = i == labels.length - 1;
            b.setBackground(states(primary ? 0xff1f5b80 : Style.PANEL2, primary ? 0xff1f5b80 : Style.PANEL2));
            if (primary) b.setTextColor(0xffffffff);
            b.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { actions[index].run(); }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(BUTTON_DP));
            lp.setMargins(dp(12), 0, 0, 0);
            row.addView(b, lp);
        }
        return row;
    }

    /** A row of chips for a string setting. */
    private View choice(final String key, String current, final String[] values, String[] labels) {
        LinearLayout row = new LinearLayout(this);
        final View[] chips = new View[values.length];
        for (int i = 0; i < values.length; i++) {
            final String value = values[i];
            TextView c = text(labels[i], 17, Style.FG);
            c.setGravity(Gravity.CENTER);
            c.setFocusable(true);
            c.setClickable(true);
            c.setBackground(states(Style.PANEL2, 0xff1f5b80));
            c.setTextColor(new android.content.res.ColorStateList(
                    new int[][]{{android.R.attr.state_selected}, {}}, new int[]{0xffffffff, Style.FG}));
            c.setSelected(value.equals(current));
            c.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    if ("drive_side".equals(key)) setDriveSide(value); else prefs.edit().putString(key, value).apply();
                    for (View o : chips) o.setSelected(o == v);
                }
            });
            chips[i] = c;
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(48), 1);
            lp.setMargins(i == 0 ? 0 : dp(10), dp(6), 0, 0);
            row.addView(c, lp);
        }
        return row;
    }

    /** Auto plus a swatch per paint (Car → Car colour). */
    private View colours() {
        LinearLayout row = new LinearLayout(this);
        String current = prefs.getString("car_colour", "auto");
        final ArrayList<View> all = new ArrayList<>();
        String[] values = {"auto", "0", "1", "2", "3", "4"};
        for (int i = 0; i < values.length; i++) {
            final String value = values[i];
            View b;
            if (i == 0) {
                TextView t = text("Auto", 17, Style.FG);
                t.setGravity(Gravity.CENTER);
                t.setBackground(states(Style.PANEL2, 0xff1f5b80));
                t.setTextColor(new android.content.res.ColorStateList(
                        new int[][]{{android.R.attr.state_selected}, {}}, new int[]{0xffffffff, Style.FG}));
                b = t;
            } else {
                b = new View(this);
                b.setContentDescription(CarPacks.COLOURS[i - 1]);
                StateListDrawable sw = new StateListDrawable();
                sw.addState(new int[]{android.R.attr.state_selected}, shape(PAINT[i - 1], Style.ACCENT, 4));
                sw.addState(new int[]{android.R.attr.state_focused}, shape(PAINT[i - 1], Style.ACCENT, 3));
                sw.addState(new int[]{}, shape(PAINT[i - 1], Style.LINE, 1));
                b.setBackground(sw);
            }
            b.setFocusable(true);
            b.setClickable(true);
            b.setSelected(value.equals(current));
            b.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    prefs.edit().putString("car_colour", value).apply();
                    for (View o : all) o.setSelected(o == v);
                }
            });
            all.add(b);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(i == 0 ? 0 : dp(48), dp(48), i == 0 ? 1 : 0);
            lp.setMargins(i == 0 ? 0 : dp(8), dp(6), 0, 0);
            row.addView(b, lp);
        }
        return row;
    }

    /** Fill, the selected fill, and the accent outline when focused by the wheel. */
    private StateListDrawable states(int normal, int selected) {
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_focused, android.R.attr.state_selected}, shape(selected, Style.ACCENT, 3));
        s.addState(new int[]{android.R.attr.state_focused}, shape(normal, Style.ACCENT, 3));
        s.addState(new int[]{android.R.attr.state_selected}, shape(selected, 0, 0));
        s.addState(new int[]{}, shape(normal, Style.LINE, 1));
        return s;
    }

    /** Model picture card: an accent ring when chosen, a thinner one when focused. */
    private StateListDrawable pictureCard() {
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_selected}, shape(Style.PANEL2, Style.ACCENT, 4));
        s.addState(new int[]{android.R.attr.state_focused}, shape(Style.PANEL, Style.FG, 2));   // not the chosen colour
        s.addState(new int[]{}, shape(Style.PANEL, Style.LINE, 1));
        return s;
    }

    private GradientDrawable shape(int fill, int stroke, int strokeDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(14));
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

    /**
     * Steering wheel: up/down walks the buttons in reading order, wrapping round (the stock focus
     * search would jump by column); OK presses the focused one.
     */
    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        int code = e.getKeyCode();
        if ((code == KeyEvent.KEYCODE_DPAD_DOWN || code == KeyEvent.KEYCODE_DPAD_UP) && e.getAction() == KeyEvent.ACTION_DOWN) {
            ArrayList<View> order = page.getFocusables(View.FOCUS_FORWARD);
            if (order.isEmpty()) return true;
            View f = getCurrentFocus();
            int i = order.indexOf(f), n = order.size();
            int j = i < 0 ? n - 1 : (i + (code == KeyEvent.KEYCODE_DPAD_DOWN ? 1 : -1) + n) % n;
            order.get(j).requestFocus();
            return true;
        }
        return super.dispatchKeyEvent(e);
    }
}
