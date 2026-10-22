package io.github.cleantv;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Outline;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Launcher settings, after CleanFrame: a grouped overview, then one page per group with the
 * choices on the left and a live preview of the real home screen on the right. Focus alone never
 * changes anything; OK applies, a tick marks what's applied, Back goes up one level.
 */
final class SettingsPanel extends FrameLayout {

    interface Host {
        SharedPreferences prefs();
        void applyAppearance();
        Bitmap snapshot(int w, int h);
        String wallpaperName();
        int homeAppCount();
        void openWallpaperPicker(); // the panel comes back to Background when the picker closes
        void openAllApps();
        void openStockHome();
        void openTvSettings();
    }

    /** One row of mutually exclusive choices. */
    private interface Choice {
        int current();
        void apply(int index);
    }

    private static final int PREVIEW_W = 480, PREVIEW_H = 270, PREVIEW_MARGIN = 48;
    private static final int PREVIEW_LEFT = 960 - PREVIEW_MARGIN - PREVIEW_W; // dp; TVs are 960dp wide

    static final String[] LAYOUTS = {"list", "dock"};
    static final String[] DISPLAYS = {"text", "icons", "both"};
    static final String[] FOCUS = {"glass", "type"};
    private static final String[] ROTATIONS = {"Fixed", "Each visit", "15 min", "Hourly"};

    private final Host host;
    private final float dp;
    private final LinearLayout overview;
    private final FrameLayout detail;
    private final LinearLayout detailCol;
    private final ImageView preview;
    private final View previewFrame;
    private String page; // null: overview
    private int overviewFocus;

    SettingsPanel(Context c, Host host) {
        super(c);
        this.host = host;
        dp = c.getResources().getDisplayMetrics().density;
        setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[] {0xE6080809, 0xB3080809, 0x59080809}));

        overview = new LinearLayout(c);
        overview.setOrientation(LinearLayout.VERTICAL);
        overview.setPadding(px(64), px(36), 0, px(24));
        addView(overview, new LayoutParams(px(660), -1));

        detail = new FrameLayout(c);
        detailCol = new LinearLayout(c);
        detailCol.setOrientation(LinearLayout.VERTICAL);
        detailCol.setPadding(px(64), px(28), 0, px(32));
        // Left column ends before the preview starts (960 - 48 margin - 480 preview = 432dp), so nothing hides behind it.
        detail.addView(detailCol, new LayoutParams(px(PREVIEW_LEFT - 20), -1));

        FrameLayout frame = new FrameLayout(c);
        GradientDrawable border = new GradientDrawable();
        border.setCornerRadius(px(18));
        border.setStroke(px(2), 0x66FFFFFF);
        frame.setForeground(border);
        round(frame, px(18));
        preview = new ImageView(c);
        preview.setScaleType(ImageView.ScaleType.CENTER_CROP);
        frame.addView(preview, new LayoutParams(-1, -1));
        LayoutParams flp = new LayoutParams(px(PREVIEW_W), px(PREVIEW_H), Gravity.END | Gravity.CENTER_VERTICAL);
        flp.rightMargin = px(PREVIEW_MARGIN);
        detail.addView(frame, flp);
        previewFrame = frame;
        addView(detail, new LayoutParams(-1, -1));
    }

    // ---- navigation ----

    void open() {
        page = null;
        showOverview();
    }

    void openPage(String id) {
        showDetail(id);
    }

    /** @return false when the panel should close */
    boolean back() {
        if (page == null) return false;
        page = null;
        showOverview();
        return true;
    }

    private void showOverview() {
        final int f = overviewFocus; // before rebuilding: removing the old rows moves focus to the first one
        detail.setVisibility(GONE);
        overview.setVisibility(VISIBLE);
        overview.removeAllViews();
        overview.addView(text("Settings", 34, 1f), wrap(0, 0, 0, px(14)));

        SharedPreferences p = host.prefs();
        int i = 0;
        overview.addView(row(i++, "Background", host.wallpaperName(), () -> showDetail("background")));
        overview.addView(row(i++, "Layout", title(p.getString("layout", "dock")), () -> showDetail("layout")));
        overview.addView(row(i++, "Font", Fonts.name(getContext()), () -> showDetail("font")));
        overview.addView(row(i++, "Focus", title(p.getString("focus", "glass")), () -> showDetail("focus")));
        overview.addView(row(i++, "Apps on Home", host.homeAppCount() + " apps", host::openAllApps));
        overview.addView(row(i, "Google TV", "Stock home and TV settings", () -> showDetail("google")));
        overview.addView(hint("Open selected group"), wrap(0, px(12), 0, 0));
        overview.getChildAt(1 + f).requestFocus();
        overview.post(() -> overview.getChildAt(1 + f).requestFocus());
    }

    private void showDetail(String id) {
        page = id;
        overview.setVisibility(GONE);
        detail.setVisibility(VISIBLE);
        detailCol.removeAllViews();
        previewFrame.setVisibility("google".equals(id) ? GONE : VISIBLE);

        TextView back = text("‹  Settings", 15, 0.7f);
        detailCol.addView(back);
        final SharedPreferences p = host.prefs();
        View first = null;
        switch (id) {
            case "background":
                detailCol.addView(text("Background", 34, 1f), wrap(0, px(4), 0, px(18)));
                first = row(-1, "Wallpaper", host.wallpaperName(), host::openWallpaperPicker);
                detailCol.addView(first);
                detailCol.addView(group("Rotation", ROTATIONS, new Choice() {
                    @Override public int current() { return p.getInt("rotate", 0); }
                    @Override public void apply(int i) { p.edit().putInt("rotate", i).apply(); }
                }, false));
                break;
            case "layout":
                detailCol.addView(text("Layout", 34, 1f), wrap(0, px(4), 0, px(10)));
                first = group("Layout", new String[] {"List", "Dock"}, pref(p, "layout", LAYOUTS, "dock"), false);
                detailCol.addView(first);
                detailCol.addView(group("App display", new String[] {"Text", "Icons", "Both"},
                        pref(p, "apps", DISPLAYS, "both"), false));
                detailCol.addView(group("Home controls", new String[] {"Text", "Icons", "Both"},
                        pref(p, "controls", DISPLAYS, "icons"), false));
                break;
            case "font":
                detailCol.addView(text("Font", 34, 1f), wrap(0, px(4), 0, px(10)));
                first = group("Home screen font", Fonts.names(getContext()), new Choice() {
                    @Override public int current() { return indexOf(Fonts.ids(getContext()), Fonts.family(getContext()), 0); }
                    @Override public void apply(int i) { Fonts.setFamily(getContext(), Fonts.ids(getContext())[i]); }
                }, true);
                detailCol.addView(first);
                break;
            case "focus":
                detailCol.addView(text("Focus", 34, 1f), wrap(0, px(4), 0, px(10)));
                first = group("Focus", new String[] {"Glass", "Type"}, pref(p, "focus", FOCUS, "glass"), false);
                detailCol.addView(first);
                TextView about = text("Glass slides a soft plate between apps. Type keeps it to the text: "
                        + "the selected app stays bright and the rest dim.", 14, 0.6f);
                detailCol.addView(about, wrap(0, px(14), px(40), 0));
                break;
            default: // google
                detailCol.addView(text("Google TV", 34, 1f), wrap(0, px(4), 0, px(18)));
                first = row(-1, "Google TV home", "The stock home screen, kept as a backup", host::openStockHome);
                detailCol.addView(first);
                detailCol.addView(row(-1, "TV settings", "Android TV settings", host::openTvSettings));
        }
        detailCol.addView(hint("google".equals(id) || "background".equals(id) ? "Open selected item"
                : "Apply selected setting"), wrap(0, px(24), 0, 0));
        final View target = first instanceof LinearLayout && ((LinearLayout) first).getChildCount() > 1
                && ((LinearLayout) first).getChildAt(1) instanceof LinearLayout
                ? applied((LinearLayout) ((LinearLayout) first).getChildAt(1)) : first;
        detailCol.post(() -> { if (target != null) target.requestFocus(); });
        refreshPreview();
    }

    // ---- building blocks ----

    /** A white plate when focused, like the TV's own menus. */
    private View row(final int overviewIndex, String title, String sub, final Runnable open) {
        final LinearLayout r = new LinearLayout(getContext());
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setFocusable(true);
        r.setPadding(px(22), px(8), px(22), px(8));
        LinearLayout col = new LinearLayout(getContext());
        col.setOrientation(LinearLayout.VERTICAL);
        final TextView t = text(title, 22, 1f);
        final TextView s = text(sub, 13, 0.75f);
        col.addView(t);
        col.addView(s);
        r.addView(col, new LinearLayout.LayoutParams(0, -2, 1f));
        final TextView chev = text("›", 26, 0.8f);
        r.addView(chev);
        final GradientDrawable plate = plate();
        r.setBackground(plate);
        r.setOnFocusChangeListener((v, has) -> {
            plate.setAlpha(has ? 255 : 0);
            int ink = has ? 0xFF111111 : 0xFFFFFFFF;
            t.setTextColor(ink);
            s.setTextColor(ink);
            chev.setTextColor(ink);
            for (TextView x : new TextView[] {t, s, chev}) x.setShadowLayer(has ? 0 : px(6), 0, px(1), 0x59000000);
            if (has && overviewIndex >= 0) overviewFocus = overviewIndex;
        });
        r.setOnClickListener(v -> open.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = px(4);
        r.setLayoutParams(lp);
        return r;
    }

    private View group(String title, final String[] names, final Choice choice, final boolean fontSamples) {
        LinearLayout g = new LinearLayout(getContext());
        g.setOrientation(LinearLayout.VERTICAL);
        g.setPadding(0, px(14), 0, 0);
        g.addView(text(title, 15, 0.7f), wrap(px(4), 0, 0, px(6)));
        final LinearLayout opts = new LinearLayout(getContext());
        opts.setTag("options");
        final boolean stacked = fontSamples || names.length > 3; // too wide for one row beside the preview
        if (stacked) opts.setOrientation(LinearLayout.VERTICAL);
        for (int i = 0; i < names.length; i++) {
            final int index = i;
            final TextView o = text(names[i], 22, 1f);
            if (fontSamples) { // each font shows itself, whatever the current one is
                Fonts.untrack(o);
                o.setTypeface(sampleFace(i));
            }
            o.setFocusable(true);
            o.setPadding(px(16), px(9), px(16), px(9));
            final GradientDrawable plate = plate();
            o.setBackground(plate);
            o.setOnFocusChangeListener((v, has) -> paint(opts, choice));
            o.setOnClickListener(v -> {
                if (choice.current() == index) return; // applying the current value writes nothing
                choice.apply(index);
                paint(opts, choice);
                host.applyAppearance();
                refreshPreview();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            if (stacked) lp.bottomMargin = px(4);
            else lp.rightMargin = px(10);
            opts.addView(o, lp);
        }
        paint(opts, choice);
        g.addView(opts);
        return g;
    }

    private void paint(LinearLayout opts, Choice choice) {
        int applied = choice.current();
        for (int i = 0; i < opts.getChildCount(); i++) {
            TextView o = (TextView) opts.getChildAt(i);
            String name = o.getText().toString().replace("  ✓", "");
            o.setText(i == applied ? name + "  ✓" : name);
            boolean f = o.isFocused();
            o.getBackground().setAlpha(f ? 255 : 0);
            o.setTextColor(f ? 0xFF111111 : 0xFFFFFFFF);
            o.setShadowLayer(f ? 0 : px(6), 0, px(1), 0x59000000);
        }
    }

    private View applied(LinearLayout opts) {
        for (int i = 0; i < opts.getChildCount(); i++) {
            if (((TextView) opts.getChildAt(i)).getText().toString().endsWith("✓")) return opts.getChildAt(i);
        }
        return opts.getChildAt(0);
    }

    private Choice pref(final SharedPreferences p, final String key, final String[] values, final String def) {
        return new Choice() {
            @Override public int current() { return indexOf(values, p.getString(key, def), 0); }
            @Override public void apply(int i) { p.edit().putString(key, values[i]).apply(); }
        };
    }

    private android.graphics.Typeface sampleFace(int i) {
        return Fonts.book(getContext(), Fonts.ids(getContext())[i]);
    }

    private void refreshPreview() {
        if (previewFrame.getVisibility() != VISIBLE) return;
        // after the home screen has laid itself out with the new choice
        postDelayed(() -> {
            Bitmap b = host.snapshot(px(PREVIEW_W), px(PREVIEW_H));
            if (b != null) preview.setImageBitmap(b);
        }, 140);
    }

    private TextView hint(String what) {
        LinearLayout r = new LinearLayout(getContext());
        TextView ok = text("OK", 12, 1f);
        GradientDrawable pill = new GradientDrawable();
        pill.setCornerRadius(px(12));
        pill.setStroke(px(2), 0xFFFFFFFF);
        ok.setBackground(pill);
        ok.setPadding(px(8), px(2), px(8), px(2));
        TextView t = text("  " + what, 16, 0.85f);
        t.setCompoundDrawablePadding(px(8));
        // a TextView keeps the footer one view: draw the pill as its left drawable
        ok.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        ok.layout(0, 0, ok.getMeasuredWidth(), ok.getMeasuredHeight());
        Bitmap b = Bitmap.createBitmap(ok.getMeasuredWidth(), ok.getMeasuredHeight(), Bitmap.Config.ARGB_8888);
        ok.draw(new android.graphics.Canvas(b));
        t.setCompoundDrawablesWithIntrinsicBounds(new android.graphics.drawable.BitmapDrawable(getResources(), b),
                null, null, null);
        return t;
    }

    private GradientDrawable plate() {
        GradientDrawable d = new GradientDrawable();
        d.setCornerRadius(px(16));
        d.setColor(0xF2FFFFFF);
        d.setAlpha(0);
        return d;
    }

    private TextView text(String s, float sp, float alpha) {
        TextView t = new TextView(getContext());
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(0xFFFFFFFF);
        t.setAlpha(alpha);
        t.setShadowLayer(px(6), 0, px(1), 0x59000000);
        Fonts.track(t, sp >= 30);
        return t;
    }

    private LinearLayout.LayoutParams wrap(int l, int t, int r, int b) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.setMargins(l, t, r, b);
        return lp;
    }

    private static String title(String v) {
        return v.isEmpty() ? v : Character.toUpperCase(v.charAt(0)) + v.substring(1);
    }

    static int indexOf(String[] values, String v, int def) {
        for (int i = 0; i < values.length; i++) if (values[i].equals(v)) return i;
        return def;
    }

    private static void round(View v, final float r) {
        v.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View view, Outline o) {
                o.setRoundRect(0, 0, view.getWidth(), view.getHeight(), r);
            }
        });
        v.setClipToOutline(true);
    }

    private int px(float d) {
        return Math.round(d * dp);
    }
}
