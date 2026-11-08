package app.binnacle;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * A pop-up in Binnacle's own look (panel colours, the compass icon, large rounded buttons that
 * the steering wheel can focus) instead of the stock Android alert. Light and dark follow Style.
 */
final class BinnacleDialog {
    interface Choice { void chosen(int index); }

    private BinnacleDialog() {}

    /**
     * Shows title + message with one button per label (left to right); choice gets the index.
     * Cancelable by BACK or tapping outside only if cancelable. Returns null (and shows nothing) once
     * the activity is finishing: a late reply (update check, unit undo) after DriveWatch closed the
     * screen would otherwise throw BadTokenException and take the dash's process with it (audit #6).
     */
    static Dialog show(Activity a, String title, String message, String[] labels, boolean cancelable, final Choice choice) {
        if (a.isFinishing() || (android.os.Build.VERSION.SDK_INT >= 17 && a.isDestroyed())) return null;
        final Dialog d = new Dialog(a);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        d.setCancelable(cancelable);

        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        int pad = dp(a, 28);
        box.setPadding(pad, dp(a, 24), pad, dp(a, 22));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Style.PANEL);
        bg.setStroke(dp(a, 1), Style.LINE);
        bg.setCornerRadius(dp(a, 22));
        box.setBackground(bg);

        ImageView icon = new ImageView(a);
        icon.setImageResource(R.drawable.ic_launcher);
        box.addView(icon, new LinearLayout.LayoutParams(dp(a, 56), dp(a, 56)));

        TextView t = text(a, title, 24, Style.FG);
        t.setTypeface(Style.DISPLAY);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, dp(a, 12), 0, dp(a, 4));
        box.addView(t);
        TextView m = text(a, message, 17, Style.MUTED);
        m.setGravity(Gravity.CENTER);
        m.setPadding(0, 0, 0, dp(a, 18));
        box.addView(m);

        final LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < labels.length; i++) {
            final int index = i;
            TextView b = text(a, labels[i], 18, Style.FG);
            b.setGravity(Gravity.CENTER);
            b.setFocusable(true);
            b.setClickable(true);
            b.setBackground(button(a));
            b.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    d.dismiss();
                    choice.chosen(index);
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(a, 56), 1f);
            lp.setMargins(dp(a, 6), 0, dp(a, 6), 0);
            row.addView(b, lp);
            if (i == 0) b.requestFocus();
        }
        box.addView(row, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        // the steering wheel scrolls as DPAD_UP/DOWN: walk the buttons in order (they sit side by side,
        // so Android's own focus search wouldn't move between them); OK presses the focused one
        d.setOnKeyListener(new android.content.DialogInterface.OnKeyListener() {
            @Override public boolean onKey(android.content.DialogInterface di, int code, android.view.KeyEvent e) {
                int step = code == android.view.KeyEvent.KEYCODE_DPAD_DOWN || code == android.view.KeyEvent.KEYCODE_DPAD_RIGHT ? 1
                        : code == android.view.KeyEvent.KEYCODE_DPAD_UP || code == android.view.KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 0;
                if (step == 0) return false;
                if (e.getAction() != android.view.KeyEvent.ACTION_DOWN) return true;
                int n = row.getChildCount(), at = 0;
                for (int k = 0; k < n; k++) if (row.getChildAt(k).isFocused()) at = k;
                row.getChildAt(Math.max(0, Math.min(n - 1, at + step))).requestFocus();
                return true;
            }
        });
        d.setContentView(box);
        Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setLayout(dp(a, 520), LinearLayout.LayoutParams.WRAP_CONTENT);
            w.setDimAmount(0.55f);
        }
        d.show();
        return d;
    }

    /**
     * Rounded button that reads as a button in both themes: a visible outline at rest, the accent
     * outline when focused by the wheel, a darker fill while pressed.
     */
    private static StateListDrawable button(Activity a) {
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_pressed}, shape(a, Style.LINE, Style.MUTED, 2));
        s.addState(new int[]{android.R.attr.state_focused}, shape(a, Style.PANEL2, Style.ACCENT, 3));
        s.addState(new int[]{}, shape(a, Style.PANEL2, Style.MUTED, 2));
        return s;
    }

    private static GradientDrawable shape(Activity a, int fill, int stroke, int strokeDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(a, 14));
        g.setStroke(dp(a, strokeDp), stroke);
        return g;
    }

    private static TextView text(Activity a, String s, float sp, int colour) {
        TextView t = new TextView(a);
        t.setText(s);
        t.setTextColor(colour);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp * Style.textScale);
        return t;
    }

    private static int dp(Activity a, int v) {
        return Math.round(v * a.getResources().getDisplayMetrics().density);
    }
}
