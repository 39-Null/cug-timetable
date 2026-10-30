package io.local.timetable.util;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

/** 极简 UI 工具（不依赖 AndroidX / Material）。 */
public final class Ui {

    private Ui() {
    }

    public static float dp(Context ctx, float v) {
        return v * ctx.getResources().getDisplayMetrics().density;
    }

    public static int dpInt(Context ctx, float v) {
        return Math.round(dp(ctx, v));
    }

    public static TextView title(Context ctx, String text) {
        TextView t = new TextView(ctx);
        t.setText(text);
        t.setTextSize(15);
        t.setTextColor(Theme.p(ctx).text);
        t.setPadding(0, dpInt(ctx, 8), 0, dpInt(ctx, 4));
        return t;
    }

    public static TextView sub(Context ctx, String text) {
        TextView t = new TextView(ctx);
        t.setText(text);
        t.setTextSize(12);
        t.setTextColor(Theme.p(ctx).sub);
        return t;
    }

    public static EditText input(Context ctx, String hint, String value) {
        EditText e = new EditText(ctx);
        e.setHint(hint);
        e.setText(value == null ? "" : value);
        e.setTextSize(14);
        e.setSingleLine(true);
        e.setInputType(InputType.TYPE_CLASS_TEXT);
        e.setTextColor(Theme.p(ctx).text);
        e.setHintTextColor(Theme.p(ctx).faint);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dpInt(ctx, 6);
        e.setLayoutParams(lp);
        return e;
    }

    public static View space(Context ctx, int dp) {
        View v = new View(ctx);
        v.setLayoutParams(new LinearLayout.LayoutParams(1, dpInt(ctx, dp)));
        return v;
    }

    public static GradientDrawable roundRect(int color, float radius, Context ctx) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(ctx, radius));
        return d;
    }

    public static GradientDrawable pill(int color, Context ctx) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(ctx, 20));
        return d;
    }

    public static GradientDrawable card(Context ctx) {
        return roundRect(Theme.p(ctx).card, 12, ctx);
    }

    public static LinearLayout column(Context ctx) {
        LinearLayout l = new LinearLayout(ctx);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    public static LinearLayout row(Context ctx) {
        LinearLayout l = new LinearLayout(ctx);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(android.view.Gravity.CENTER_VERTICAL);
        return l;
    }

    public static TextView button(Context ctx, String text) {
        TextView t = new TextView(ctx);
        t.setText(text);
        t.setTextSize(14);
        t.setTextColor(Color.WHITE);
        t.setGravity(android.view.Gravity.CENTER);
        t.setPadding(dpInt(ctx, 16), dpInt(ctx, 10), dpInt(ctx, 16), dpInt(ctx, 10));
        t.setBackground(roundRect(Theme.p(ctx).primary, 10, ctx));
        return t;
    }

    public static TextView ghostButton(Context ctx, String text) {
        TextView t = new TextView(ctx);
        t.setText(text);
        t.setTextSize(14);
        t.setTextColor(Theme.p(ctx).primary);
        t.setGravity(android.view.Gravity.CENTER);
        t.setPadding(dpInt(ctx, 16), dpInt(ctx, 10), dpInt(ctx, 16), dpInt(ctx, 10));
        t.setBackground(roundRect(Theme.p(ctx).primaryLight, 10, ctx));
        return t;
    }
}
