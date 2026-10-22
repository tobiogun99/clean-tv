package io.github.cleantv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Pictures in the app's own folder on the TV: /sdcard/Android/data/<app id>/files/wallpapers/ (./tv.sh wallpaper). */
final class Walls {
    private Walls() {}

    static List<File> files(Context c) {
        List<File> out = new ArrayList<>();
        File dir = c.getExternalFilesDir("wallpapers");
        File[] files = dir == null ? null : dir.listFiles();
        if (files == null) return out;
        for (File f : files) {
            String n = f.getName().toLowerCase(Locale.ROOT);
            if (n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png") || n.endsWith(".webp")) {
                out.add(f);
            }
        }
        Collections.sort(out);
        return out;
    }

    /**
     * Decodes at exactly the size needed to cover w x h, never bigger: a 2560px photo on a 1080p
     * screen would otherwise sit in memory (and on the GPU) at almost twice the pixels it shows.
     */
    static Bitmap decode(File f, int w, int h) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), o);
        int sample = 1;
        while (o.outWidth / (sample * 2) >= w && o.outHeight / (sample * 2) >= h) sample *= 2;
        o = new BitmapFactory.Options();
        o.inSampleSize = sample;
        Bitmap b = BitmapFactory.decodeFile(f.getPath(), o);
        if (b == null) return null;
        float s = Math.max(w / (float) b.getWidth(), h / (float) b.getHeight());
        if (s < 0.98f) { // still larger than the screen: scale down to just cover it
            Bitmap fit = Bitmap.createScaledBitmap(b, Math.round(b.getWidth() * s), Math.round(b.getHeight() * s), true);
            if (fit != b) b.recycle();
            b = fit;
        }
        return b;
    }

    /** Frees a picture that has just been replaced on screen, once the fade has finished drawing it. */
    static void releaseLater(android.os.Handler ui, android.graphics.drawable.Drawable old, Object current, long delayMs) {
        if (!(old instanceof android.graphics.drawable.BitmapDrawable) || old == current) return;
        final Bitmap b = ((android.graphics.drawable.BitmapDrawable) old).getBitmap();
        if (b == null) return;
        ui.postDelayed(() -> { if (!b.isRecycled()) b.recycle(); }, delayMs);
    }
}
