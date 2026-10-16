package app.nekosakana.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

/** UI 样式工具：统一暗色主题的控件构造。 */
public final class Ui {
    public static final int BG = 0xFF111517;
    public static final int SURFACE = 0xFF1D2528;
    public static final int SURFACE2 = 0xFF293337;
    public static final int LINE = 0xFF354247;
    public static final int TEXT = 0xFFF4F3EE;
    public static final int MUTED = 0xFFAAB5B6;
    public static final int ACCENT = 0xFF78D7BD;
    public static final int INFO = 0xFF8CBCEB;
    public static final int WARN = 0xFFF0BE75;
    public static final int DANGER = 0xFFEF8985;

    private Ui() {
    }

    public static int dp(Context c, int value) {
        return (int) (value * c.getResources().getDisplayMetrics().density + 0.5f);
    }

    public static GradientDrawable round(int color, int radiusDp, Context c) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(c, radiusDp));
        return d;
    }

    public static TextView text(Context c, String value, int size, int color, boolean bold) {
        TextView v = new TextView(c);
        v.setText(value);
        v.setTextSize(size);
        v.setTextColor(color);
        v.setGravity(Gravity.CENTER_VERTICAL);
        if (bold) v.setTypeface(null, Typeface.BOLD);
        return v;
    }

    public static Drawable inputBackground(Context c) {
        GradientDrawable idle = rounded(SURFACE2, 10, c);
        idle.setStroke(dp(c, 1), LINE);
        GradientDrawable focused = rounded(0xFF0F1416, 10, c);
        focused.setStroke(dp(c, 1), ACCENT);
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_focused}, focused);
        states.addState(new int[]{}, idle);
        return states;
    }

    private static GradientDrawable rounded(int color, int radiusDp, Context c) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(c, radiusDp));
        return d;
    }

    public static EditText input(Context c, String hint) {
        EditText e = new EditText(c);
        e.setSingleLine(true);
        e.setMinHeight(dp(c, 50));
        e.setTextSize(15);
        e.setTextColor(TEXT);
        e.setHintTextColor(MUTED);
        e.setHint(hint);
        e.setPadding(dp(c, 15), 0, dp(c, 15), 0);
        e.setBackground(inputBackground(c));
        return e;
    }

    public static Button button(Context c, String label) {
        return button(c, label, ACCENT);
    }

    /** 就地更新按钮配色（用于切换选中态）。 */
    public static void colorButton(Context c, Button b, int color) {
        b.setTextColor(color == ACCENT || color == WARN || color == DANGER ? BG : TEXT);
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(0x2AFFFFFF), round(color, 8, c), null));
    }

    public static Button button(Context c, String label, int color) {
        Button b = new Button(c);
        b.setText(label);
        b.setTextColor(color == ACCENT || color == WARN || color == DANGER ? BG : TEXT);
        b.setTextSize(13);
        b.setAllCaps(false);
        b.setGravity(Gravity.CENTER);
        b.setMinHeight(dp(c, 44));
        b.setStateListAnimator(null);
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(0x2AFFFFFF), round(color, 8, c), null));
        b.setPadding(0, 0, 0, 0);
        return b;
    }

    public static LinearLayout column(Context c) {
        LinearLayout layout = new LinearLayout(c);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    public static LinearLayout row(Context c) {
        LinearLayout layout = new LinearLayout(c);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        layout.setGravity(Gravity.CENTER_VERTICAL);
        return layout;
    }

    public static LinearLayout.LayoutParams lp(Context c, int w, int h, int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                w < 0 ? w : dp(c, w), h < 0 ? h : dp(c, h));
        p.setMargins(dp(c, l), dp(c, t), dp(c, r), dp(c, b));
        return p;
    }

    public static void add(LinearLayout parent, View view, int top, int bottom) {
        parent.addView(view, lp(parent.getContext(), -1, -2, 0, top, 0, bottom));
    }

    /** 等高、等宽（weight=1）的布局参数，用于并排按钮。 */
    public static LinearLayout.LayoutParams grow(Context c, int heightDp, int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(c, heightDp), 1f);
        p.setMargins(dp(c, l), dp(c, t), dp(c, r), dp(c, b));
        return p;
    }

    /** 浮层小标签，用于画面上的状态提示。 */
    public static TextView chip(Context c, String value, int color) {
        TextView t = text(c, value, 11, color, true);
        t.setPadding(dp(c, 11), dp(c, 6), dp(c, 11), dp(c, 6));
        t.setBackground(round(0xD9161C1F, 20, c));
        return t;
    }

    /** 全大写、带字距的小标题。 */
    public static TextView overline(Context c, String value, int color) {
        TextView t = text(c, value, 11, color, true);
        t.setLetterSpacing(0.08f);
        return t;
    }

    public static LinearLayout card(Context c, String eyebrow, String title, String body, int tint) {
        LinearLayout box = column(c);
        box.setPadding(dp(c, 16), dp(c, 14), dp(c, 16), dp(c, 14));
        box.setBackground(new RippleDrawable(ColorStateList.valueOf(0x1FFFFFFF), round(SURFACE, 8, c), null));
        if (eyebrow != null && !eyebrow.isEmpty()) {
            TextView e = text(c, eyebrow.toUpperCase(), 10, tint, true);
            box.addView(e);
        }
        if (title != null && !title.isEmpty()) {
            android.widget.TextView t = text(c, title, 16, TEXT, true);
            t.setPadding(0, dp(c, 6), 0, 0);
            box.addView(t);
        }
        if (body != null && !body.isEmpty()) {
            TextView b = text(c, body, 12, MUTED, false);
            b.setPadding(0, dp(c, 6), 0, 0);
            box.addView(b);
        }
        return box;
    }

    public static TextView section(Context c, String title) {
        TextView t = text(c, title, 12, INFO, true);
        t.setPadding(0, dp(c, 10), 0, dp(c, 6));
        return t;
    }

    public static TextView value(Context c, String value, int color) {
        TextView t = text(c, value, 13, color, false);
        t.setLineSpacing(dp(c, 3), 1f);
        t.setTextIsSelectable(true);
        return t;
    }
}
