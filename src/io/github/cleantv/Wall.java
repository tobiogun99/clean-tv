package io.github.cleantv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.util.DisplayMetrics;

import java.io.File;

/** A wallpaper ready to show: the picture, a tiny pre-blurred copy for the glass, and a tint from its colours. */
final class Wall {
    final Drawable image;
    final Bitmap blur; // tiny, pre-blurred copy the frosted panels draw from
    final int tint;

    Wall(Drawable image, Bitmap blur, int tint) {
        this.image = image;
        this.blur = blur;
        this.tint = tint;
    }

    /** The warm gradient used until you add pictures. */
    static Wall gradient() {
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[] {0xFF6E5646, 0xFF2E2621, 0xFF121011});
        Bitmap small = Bitmap.createBitmap(64, 36, Bitmap.Config.ARGB_8888);
        g.setBounds(0, 0, 64, 36);
        g.draw(new Canvas(small));
        return from(g, small);
    }

    static Wall fromFile(Context c, File f) {
        DisplayMetrics m = c.getResources().getDisplayMetrics();
        Bitmap full = Walls.decode(f, m.widthPixels, m.heightPixels);
        Bitmap small = Bitmap.createScaledBitmap(full, 96,
                Math.max(1, 96 * full.getHeight() / full.getWidth()), true);
        return from(new BitmapDrawable(c.getResources(), full), small);
    }

    private static Wall from(Drawable image, Bitmap small) {
        int w = small.getWidth(), h = small.getHeight();
        int[] a = new int[w * h], b = new int[w * h];
        small.getPixels(a, 0, w, 0, 0, w, h);
        for (int pass = 0; pass < 3; pass++) {
            boxBlur(a, b, w, h, 3, true);
            boxBlur(b, a, w, h, 3, false);
        }
        long r = 0, g = 0, bl = 0;
        for (int p : a) {
            r += p >> 16 & 255;
            g += p >> 8 & 255;
            bl += p & 255;
        }
        int n = a.length;
        // darker version of the picture's average colour, so the glass takes on its mood
        int tint = Color.argb(0x8C, (int) (r / n * 0.4), (int) (g / n * 0.4), (int) (bl / n * 0.4));
        Bitmap blur = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        blur.setPixels(a, 0, w, 0, 0, w, h);
        return new Wall(image, blur, tint);
    }

    private static void boxBlur(int[] in, int[] out, int w, int h, int radius, boolean horizontal) {
        int len = horizontal ? w : h, lines = horizontal ? h : w;
        for (int line = 0; line < lines; line++) {
            for (int i = 0; i < len; i++) {
                int r = 0, g = 0, b = 0, c = 0;
                for (int k = Math.max(0, i - radius); k <= Math.min(len - 1, i + radius); k++) {
                    int p = in[horizontal ? line * w + k : k * w + line];
                    r += p >> 16 & 255;
                    g += p >> 8 & 255;
                    b += p & 255;
                    c++;
                }
                out[horizontal ? line * w + i : i * w + line] = 0xFF000000 | (r / c) << 16 | (g / c) << 8 | b / c;
            }
        }
    }
}
