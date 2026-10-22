package io.github.cleantv;

import android.content.Context;
import android.graphics.Outline;
import android.graphics.drawable.GradientDrawable;
import android.media.session.MediaController;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/** A quiet now-playing card for Art Mode and the screensaver. Not focusable: look, don't touch. */
final class NowCard extends LinearLayout {
    private final float dp;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ImageView art;
    private final TextView title, sub;
    private final Island.Bars bars;
    private final Island.Line line;
    private Track track;
    private boolean active = true;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (track == null || !isShown()) return;
            float p = track.progress();
            line.setVisibility(p < 0 ? INVISIBLE : VISIBLE);
            if (p >= 0) line.set(p);
            ui.postDelayed(this, 1000);
        }
    };

    NowCard(Context c) {
        super(c);
        dp = c.getResources().getDisplayMetrics().density;
        setGravity(Gravity.CENTER_VERTICAL);
        setPadding(px(8), px(8), px(14), px(8));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0x8C000000);
        bg.setCornerRadius(px(16));
        setBackground(bg);
        setAlpha(0f);
        setVisibility(GONE);

        art = new ImageView(c);
        art.setScaleType(ImageView.ScaleType.CENTER_CROP);
        art.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), px(9));
            }
        });
        art.setClipToOutline(true);
        addView(art, new LayoutParams(px(44), px(44)));

        LinearLayout col = new LinearLayout(c);
        col.setOrientation(VERTICAL);
        col.setPadding(px(12), 0, px(12), 0);
        title = text(15, 1f);
        Fonts.track(title, true);
        sub = text(12, 0.7f);
        col.addView(title);
        col.addView(sub);
        line = new Island.Line(c);
        LayoutParams llp = new LayoutParams(px(170), px(2));
        llp.topMargin = px(7);
        col.addView(line, llp);
        addView(col);

        bars = new Island.Bars(c);
        addView(bars, new LayoutParams(px(16), px(13)));
    }

    /** Art Mode only wants the card while it's showing. */
    void setActive(boolean on) {
        active = on;
        refresh();
    }

    void show(MediaController c) {
        track = Track.of(getContext(), c);
        if (track != null) {
            title.setText(track.title);
            sub.setText(track.sub);
            if (track.art != null) art.setImageBitmap(track.art);
            else art.setImageDrawable(track.icon);
            bars.set(track.accent, track.playing);
        }
        refresh();
    }

    private void refresh() {
        boolean want = active && track != null;
        ui.removeCallbacks(tick);
        if (want) {
            if (getVisibility() != VISIBLE) {
                setVisibility(VISIBLE);
                setTranslationY(-px(10));
            }
            animate().alpha(1f).translationY(0).setDuration(600).start();
            ui.post(tick);
        } else if (getVisibility() == VISIBLE) {
            animate().alpha(0f).translationY(-px(10)).setDuration(400).withEndAction(() -> setVisibility(GONE)).start();
        }
    }

    @Override protected void onDetachedFromWindow() {
        ui.removeCallbacksAndMessages(null);
        super.onDetachedFromWindow();
    }

    private TextView text(float sp, float alpha) {
        TextView t = new TextView(getContext());
        t.setTextSize(sp);
        t.setTextColor(0xFFFFFFFF);
        t.setAlpha(alpha);
        Fonts.track(t, false);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setMaxWidth(px(240));
        return t;
    }

    private int px(float dips) {
        return Math.round(dips * dp);
    }
}
