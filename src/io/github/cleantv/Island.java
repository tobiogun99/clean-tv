package io.github.cleantv;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Now playing, Dynamic Island style: a black pill at the top that grows into a player when
 * focused, peeks open by itself when the track changes, and shrinks away when playback stops.
 */
final class Island extends FrameLayout {
    private static final int COMPACT_H = 40, EXP_W = 400, EXP_H = 190; // dp
    private static final long PEEK_MS = 3500;

    private final float dp;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Runnable onLeave; // moves focus elsewhere when the island disappears under it
    private final GradientDrawable bg = new GradientDrawable();
    private final LinearLayout compact, expanded;
    private final ImageView miniArt, art, playIcon;
    private final TextView miniTitle, title, sub, elapsed, remaining;
    private final Bars miniBars, bars;
    private final Line progress;
    private final View progressRow, prev, play, next;

    private MediaController media;
    private CharSequence shownTitle;
    private boolean open, peeking;
    private ValueAnimator sizeAnim;

    private final Runnable endPeek = () -> {
        peeking = false;
        if (!hasFocus()) setOpen(false);
    };

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            updateProgress();
            if (open && hasWindowFocus()) ui.postDelayed(this, 500);
        }
    };

    private final ViewTreeObserver.OnGlobalFocusChangeListener focusWatch = (old, now) -> {
        if (now == this) { // landed on the pill: open up and hand focus to play/pause
            peeking = false;
            setOpen(true);
            post(this::focusPlay);
        } else if (inside(now)) {
            peeking = false;
            ui.removeCallbacks(endPeek);
            if (!open) setOpen(true);
        } else if (open && !peeking) {
            setOpen(false);
        }
    };

    Island(Context c, Runnable onLeave) {
        super(c);
        this.onLeave = onLeave;
        dp = c.getResources().getDisplayMetrics().density;
        setFocusable(true);
        setVisibility(GONE);
        setElevation(px(10));
        bg.setColor(0xFF000000);
        setBackground(bg);
        setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), radius());
            }
        });
        setClipToOutline(true);

        // compact: art · title · bars
        compact = new LinearLayout(c);
        compact.setGravity(Gravity.CENTER_VERTICAL);
        compact.setPadding(px(6), 0, px(14), 0);
        miniArt = image(8);
        compact.addView(miniArt, new LinearLayout.LayoutParams(px(28), px(28)));
        miniTitle = text(14, 0.95f);
        Fonts.track(miniTitle, true);
        miniTitle.setMaxWidth(px(220));
        miniTitle.setPadding(px(10), 0, px(12), 0);
        compact.addView(miniTitle);
        miniBars = new Bars(c);
        compact.addView(miniBars, new LinearLayout.LayoutParams(px(20), px(16)));
        addView(compact, new LayoutParams(-2, px(COMPACT_H), Gravity.TOP | Gravity.CENTER_HORIZONTAL));

        // expanded: art + text + bars / progress / controls
        expanded = new LinearLayout(c);
        expanded.setOrientation(LinearLayout.VERTICAL);
        expanded.setPadding(px(18), px(18), px(18), px(8));
        expanded.setAlpha(0f);
        expanded.setClipChildren(false); // let the focus ring on the buttons breathe

        LinearLayout top = new LinearLayout(c);
        top.setGravity(Gravity.CENTER_VERTICAL);
        art = image(14);
        top.addView(art, new LinearLayout.LayoutParams(px(64), px(64)));
        LinearLayout words = new LinearLayout(c);
        words.setOrientation(LinearLayout.VERTICAL);
        words.setPadding(px(16), 0, px(12), 0);
        title = text(18, 1f);
        Fonts.track(title, true);
        sub = text(13, 0.6f);
        words.addView(title);
        words.addView(sub);
        top.addView(words, new LinearLayout.LayoutParams(0, -2, 1f));
        bars = new Bars(c);
        top.addView(bars, new LinearLayout.LayoutParams(px(26), px(22)));
        expanded.addView(top);

        LinearLayout row = new LinearLayout(c);
        row.setGravity(Gravity.CENTER_VERTICAL);
        elapsed = text(11, 0.6f);
        remaining = text(11, 0.6f);
        progress = new Line(c);
        row.addView(elapsed);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(0, px(4), 1f);
        plp.setMargins(px(10), 0, px(10), 0);
        row.addView(progress, plp);
        row.addView(remaining);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, -2);
        rlp.topMargin = px(14);
        expanded.addView(row, rlp);
        progressRow = row;

        LinearLayout controls = new LinearLayout(c);
        controls.setGravity(Gravity.CENTER);
        controls.setClipChildren(false);
        prev = button(R.drawable.ic_prev, v -> { if (media != null) media.getTransportControls().skipToPrevious(); });
        play = button(R.drawable.ic_pause, v -> togglePlay());
        playIcon = (ImageView) ((FrameLayout) play).getChildAt(0);
        next = button(R.drawable.ic_next, v -> { if (media != null) media.getTransportControls().skipToNext(); });
        play.setOnLongClickListener(v -> { openApp(); return true; });
        controls.addView(prev);
        controls.addView(play);
        controls.addView(next);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-1, -2);
        clp.topMargin = px(6);
        expanded.addView(controls, clp);

        addView(expanded, new LayoutParams(px(EXP_W), px(EXP_H), Gravity.TOP | Gravity.CENTER_HORIZONTAL));
    }

    // ---- public ----

    /** Call with the current session (or null) whenever it or its state changes. */
    void show(MediaController c) {
        media = c;
        Track t = Track.of(getContext(), c);
        if (t == null) {
            hide();
            return;
        }
        miniTitle.setText(t.title);
        title.setText(t.title);
        sub.setText(t.sub);
        if (t.art != null) {
            miniArt.setImageBitmap(t.art);
            art.setImageBitmap(t.art);
        } else {
            miniArt.setImageDrawable(t.icon);
            art.setImageDrawable(t.icon);
        }
        miniBars.set(t.accent, t.playing);
        bars.set(t.accent, t.playing);
        playIcon.setImageResource(t.playing ? R.drawable.ic_pause : R.drawable.ic_play);
        updateProgress();

        boolean newTrack = shownTitle != null && !TextUtils.equals(shownTitle, t.title);
        shownTitle = t.title;
        if (getVisibility() != VISIBLE) appear();
        else if (!open) resize(compactWidth(), px(COMPACT_H), 260, 0.8f);
        if (newTrack && !hasFocus()) peek();
    }

    // ---- states ----

    private void appear() {
        setVisibility(VISIBLE);
        LayoutParams lp = (LayoutParams) getLayoutParams();
        lp.width = compactWidth();
        lp.height = px(COMPACT_H);
        setLayoutParams(lp);
        setScaleX(0.6f);
        setScaleY(0.6f);
        setAlpha(0f);
        animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(380)
                .setInterpolator(new OvershootInterpolator(1.4f)).start();
    }

    private void hide() {
        if (getVisibility() != VISIBLE) return;
        shownTitle = null;
        peeking = false;
        ui.removeCallbacks(endPeek);
        if (hasFocus()) onLeave.run();
        animate().scaleX(0.6f).scaleY(0.6f).alpha(0f).setDuration(220).withEndAction(() -> {
            setVisibility(GONE);
            open = false;
            setButtonsFocusable(false);
            compact.setAlpha(1f);
            expanded.setAlpha(0f);
        }).start();
    }

    private void peek() {
        peeking = true;
        setOpen(true);
        ui.removeCallbacks(endPeek);
        ui.postDelayed(endPeek, PEEK_MS);
    }

    private void setOpen(boolean o) {
        open = o;
        setButtonsFocusable(o);
        if (o) resize(px(EXP_W), px(EXP_H), 460, 1.1f);
        else resize(compactWidth(), px(COMPACT_H), 360, 0.6f);
        compact.animate().alpha(o ? 0f : 1f).setDuration(o ? 100 : 200).setStartDelay(o ? 0 : 160).start();
        expanded.animate().alpha(o ? 1f : 0f).setDuration(o ? 220 : 90).setStartDelay(o ? 140 : 0).start();
        ui.removeCallbacks(tick);
        if (o) tick.run();
    }

    private void resize(int toW, int toH, long ms, float bounce) {
        final LayoutParams lp = (LayoutParams) getLayoutParams();
        final int fromW = lp.width, fromH = lp.height;
        if (sizeAnim != null) sizeAnim.cancel();
        sizeAnim = ValueAnimator.ofFloat(0f, 1f);
        sizeAnim.setDuration(ms);
        sizeAnim.setInterpolator(new OvershootInterpolator(bounce)); // the springy iOS feel
        sizeAnim.addUpdateListener(a -> {
            float f = (float) a.getAnimatedValue();
            lp.width = Math.round(fromW + (toW - fromW) * f);
            lp.height = Math.max(px(COMPACT_H / 2f), Math.round(fromH + (toH - fromH) * f));
            setLayoutParams(lp);
        });
        sizeAnim.start();
    }

    private int compactWidth() {
        compact.measure(MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
                MeasureSpec.makeMeasureSpec(px(COMPACT_H), MeasureSpec.EXACTLY));
        return compact.getMeasuredWidth();
    }

    private float radius() {
        return Math.min(getHeight() / 2f, px(44));
    }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        bg.setCornerRadius(radius());
        invalidateOutline();
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        getViewTreeObserver().addOnGlobalFocusChangeListener(focusWatch);
    }

    @Override protected void onDetachedFromWindow() {
        getViewTreeObserver().removeOnGlobalFocusChangeListener(focusWatch);
        ui.removeCallbacksAndMessages(null);
        super.onDetachedFromWindow();
    }

    /** The progress clock only runs while Clean TV is on screen and the island is open. */
    @Override public void onWindowFocusChanged(boolean has) {
        super.onWindowFocusChanged(has);
        ui.removeCallbacks(tick);
        if (has && open) tick.run();
    }

    // ---- playback ----

    private void togglePlay() {
        if (media == null) return;
        PlaybackState st = media.getPlaybackState();
        if (st != null && st.getState() == PlaybackState.STATE_PLAYING) media.getTransportControls().pause();
        else media.getTransportControls().play();
    }

    private void openApp() {
        if (media == null) return;
        PackageManager pm = getContext().getPackageManager();
        Intent i = pm.getLeanbackLaunchIntentForPackage(media.getPackageName());
        if (i == null) i = pm.getLaunchIntentForPackage(media.getPackageName());
        if (i == null) return;
        try {
            getContext().startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception ignored) {
            // app went away
        }
    }

    private void updateProgress() {
        MediaMetadata md = media == null ? null : media.getMetadata();
        PlaybackState st = media == null ? null : media.getPlaybackState();
        long dur = md == null ? 0 : md.getLong(MediaMetadata.METADATA_KEY_DURATION);
        if (st == null || dur <= 0) {
            progressRow.setVisibility(INVISIBLE);
            return;
        }
        long pos = st.getPosition();
        if (st.getState() == PlaybackState.STATE_PLAYING) {
            pos += (long) ((SystemClock.elapsedRealtime() - st.getLastPositionUpdateTime()) * st.getPlaybackSpeed());
        }
        pos = Math.max(0, Math.min(dur, pos));
        progress.set(pos / (float) dur);
        elapsed.setText(time(pos));
        remaining.setText("-" + time(dur - pos));
        progressRow.setVisibility(VISIBLE);
    }

    private static String time(long ms) {
        long s = ms / 1000;
        return s >= 3600 ? String.format("%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
                : String.format("%d:%02d", s / 60, s % 60);
    }

    // ---- views ----

    private boolean inside(View v) {
        for (ViewParent p = v == null ? null : v.getParent(); p != null; p = p.getParent()) {
            if (p == this) return true;
        }
        return false;
    }

    private void focusPlay() {
        play.requestFocus();
    }

    private void setButtonsFocusable(boolean f) {
        prev.setFocusable(f);
        play.setFocusable(f);
        next.setFocusable(f);
    }

    private View button(int icon, OnClickListener click) {
        FrameLayout b = new FrameLayout(getContext());
        final GradientDrawable ring = new GradientDrawable();
        ring.setShape(GradientDrawable.OVAL);
        ring.setColor(0x33FFFFFF);
        ring.setAlpha(0);
        b.setBackground(ring);
        ImageView iv = new ImageView(getContext());
        iv.setImageResource(icon);
        b.addView(iv, new LayoutParams(px(26), px(26), Gravity.CENTER));
        b.setFocusable(false);
        b.setOnFocusChangeListener((v, has) -> {
            ObjectAnimator.ofInt(ring, "alpha", ring.getAlpha(), has ? 255 : 0).setDuration(120).start();
            v.animate().scaleX(has ? 1.1f : 1f).scaleY(has ? 1.1f : 1f).setDuration(120).start();
        });
        b.setOnClickListener(click);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(px(52), px(52));
        lp.setMargins(px(12), 0, px(12), 0);
        b.setLayoutParams(lp);
        return b;
    }

    private ImageView image(float radiusDp) {
        ImageView iv = new ImageView(getContext());
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        final float r = px(radiusDp);
        iv.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), r);
            }
        });
        iv.setClipToOutline(true);
        return iv;
    }

    private TextView text(float sp, float alpha) {
        TextView t = new TextView(getContext());
        t.setTextSize(sp);
        t.setTextColor(0xFFFFFFFF);
        t.setAlpha(alpha);
        Fonts.track(t, false);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        return t;
    }

    private int px(float dips) {
        return Math.round(dips * dp);
    }

    /** Little sound bars in the art's colour; they dance while playing and rest when paused. */
    static final class Bars extends View {
        private static final float[] SPEED = {5.1f, 7.3f, 4.2f, 6.4f};
        private static final float[] PHASE = {0f, 1.3f, 2.6f, 0.7f};
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private boolean playing;

        Bars(Context c) {
            super(c);
        }

        void set(int color, boolean playing) {
            paint.setColor(color);
            this.playing = playing;
            invalidate();
        }

        @Override protected void onDraw(Canvas c) {
            int n = SPEED.length;
            float w = getWidth(), h = getHeight(), gap = w * 0.12f, bw = (w - gap * (n - 1)) / n;
            double t = SystemClock.uptimeMillis() / 1000.0;
            for (int i = 0; i < n; i++) {
                float level = playing
                        ? 0.3f + 0.7f * (float) Math.abs(Math.sin(t * SPEED[i] / 2 + PHASE[i]))
                                * (0.6f + 0.4f * (float) Math.abs(Math.sin(t * 1.7 + i)))
                        : 0.22f;
                float bh = Math.max(bw, h * level), x = i * (bw + gap);
                r.set(x, (h - bh) / 2, x + bw, (h + bh) / 2);
                c.drawRoundRect(r, bw / 2, bw / 2, paint);
            }
            if (playing && isShown()) postInvalidateOnAnimation(); // stops by itself when hidden
        }
    }

    /** Thin rounded progress bar. */
    static final class Line extends View {
        private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG), fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private float fraction;

        Line(Context c) {
            super(c);
            track.setColor(0x40FFFFFF);
            fill.setColor(0xFFFFFFFF);
        }

        void set(float f) {
            fraction = f;
            invalidate();
        }

        @Override protected void onDraw(Canvas c) {
            float h = getHeight(), rad = h / 2;
            r.set(0, 0, getWidth(), h);
            c.drawRoundRect(r, rad, rad, track);
            r.set(0, 0, Math.max(h, getWidth() * fraction), h);
            c.drawRoundRect(r, rad, rad, fill);
        }
    }
}
