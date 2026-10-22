package io.github.cleantv;

import android.animation.ObjectAnimator;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.service.dreams.DreamService;
import android.text.format.DateFormat;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import java.io.File;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Screensaver: your wallpaper folder as a slow, cross-fading slideshow with a small clock. */
public final class ArtDream extends DreamService {
    private static final long SLIDE_MS = 45_000;
    private static final long FADE_MS = 2_500;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService bg = Executors.newSingleThreadExecutor();
    private ImageView front, back;
    private TextView clock;
    private NowCard now;
    private Sessions sessions;
    private List<File> files = Collections.emptyList();
    private int index;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            clock.setText(DateFormat.getTimeFormat(ArtDream.this).format(new Date()));
            ui.postDelayed(this, 60_000 - System.currentTimeMillis() % 60_000);
        }
    };

    private final Runnable next = new Runnable() {
        @Override public void run() { showNext(); }
    };

    @Override public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false); // any button wakes the TV back up
        setFullscreen(true);
        float dp = getResources().getDisplayMetrics().density;

        FrameLayout root = new FrameLayout(this);
        root.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[] {0xFF6E5646, 0xFF2E2621, 0xFF121011}));
        back = image();
        front = image();
        root.addView(back, new FrameLayout.LayoutParams(-1, -1));
        root.addView(front, new FrameLayout.LayoutParams(-1, -1));

        clock = new TextView(this);
        clock.setTextSize(34);
        clock.setTextColor(0xE6FFFFFF);
        Fonts.track(clock, false);
        clock.setShadowLayer(8 * dp, 0, dp, 0x80000000);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
        lp.setMargins(Math.round(64 * dp), Math.round(36 * dp), 0, 0); // level with the now-playing card
        root.addView(clock, lp);
        now = new NowCard(this);
        FrameLayout.LayoutParams nlp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        nlp.setMargins(0, Math.round(32 * dp), Math.round(48 * dp), 0);
        root.addView(now, nlp);
        sessions = new Sessions(this, now::show);
        setContentView(root);
    }

    @Override public void onDreamingStarted() {
        super.onDreamingStarted();
        tick.run();
        sessions.start();
        files = Walls.files(this);
        index = getSharedPreferences("cleantv", MODE_PRIVATE).getInt("wall", 0); // start on the home wallpaper
        showNext();
    }

    @Override public void onDreamingStopped() {
        ui.removeCallbacksAndMessages(null);
        sessions.stop();
        super.onDreamingStopped();
    }

    @Override public void onDetachedFromWindow() {
        bg.shutdownNow();
        super.onDetachedFromWindow();
    }

    private ImageView image() {
        ImageView iv = new ImageView(this);
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        return iv;
    }

    private void showNext() {
        if (files.isEmpty()) return; // the gradient background stays
        final File f = files.get(Math.floorMod(index++, files.size()));
        final DisplayMetrics m = getResources().getDisplayMetrics();
        bg.execute(() -> {
            final Bitmap b;
            try {
                b = Walls.decode(f, m.widthPixels, m.heightPixels);
            } catch (Throwable t) {
                ui.post(next);
                return;
            }
            ui.post(() -> crossfade(b));
        });
    }

    private void crossfade(Bitmap b) {
        ImageView incoming = back;
        Walls.releaseLater(ui, incoming.getDrawable(), null, FADE_MS + 500); // the photo from two slides ago
        incoming.setImageBitmap(b);
        incoming.setAlpha(0f);
        incoming.setScaleX(1f);
        incoming.setScaleY(1f);
        incoming.bringToFront();
        clock.bringToFront();
        now.bringToFront();
        incoming.animate().alpha(1f).setDuration(FADE_MS).start();
        // slow push-in while the picture is up
        ObjectAnimator.ofFloat(incoming, "scaleX", 1f, 1.06f).setDuration(SLIDE_MS + FADE_MS).start();
        ObjectAnimator.ofFloat(incoming, "scaleY", 1f, 1.06f).setDuration(SLIDE_MS + FADE_MS).start();
        back = front;
        front = incoming;
        if (files.size() > 1) ui.postDelayed(next, SLIDE_MS);
    }
}
