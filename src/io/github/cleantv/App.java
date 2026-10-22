package io.github.cleantv;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.Drawable;
import android.os.Build;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A launchable app, and how the list of them (with white logo glyphs) is built. */
final class App {
    final String pkg;
    final Intent launch;
    final CharSequence label;
    final Bitmap glyph;  // white logo silhouette; null when the icon has no clean shape
    final Drawable icon; // full-colour fallback

    App(String pkg, Intent launch, CharSequence label, Bitmap glyph, Drawable icon) {
        this.pkg = pkg;
        this.launch = launch;
        this.label = label;
        this.glyph = glyph;
        this.icon = icon;
    }

    /** Every launchable app, A to Z, plus the stock home as a "Google TV" tile. */
    static List<App> query(Context c) {
        PackageManager pm = c.getPackageManager();
        Map<String, App> byPkg = new LinkedHashMap<>();
        collect(pm, Intent.CATEGORY_LEANBACK_LAUNCHER, byPkg); // TV entry points win duplicates
        collect(pm, Intent.CATEGORY_LAUNCHER, byPkg);           // sideloaded phone apps
        byPkg.remove(c.getPackageName());

        ComponentName stock = Stock.find(c); // the backup, as a "Google TV" tile
        if (stock != null && !byPkg.containsKey(stock.getPackageName())) {
            try {
                Drawable icon = pm.getApplicationIcon(stock.getPackageName());
                byPkg.put(stock.getPackageName(), new App(stock.getPackageName(), Stock.intent(stock),
                        "Google TV", glyph(icon), icon));
            } catch (PackageManager.NameNotFoundException ignored) {
                // no stock home on this device
            }
        }

        List<App> list = new ArrayList<>(byPkg.values());
        final Collator collator = Collator.getInstance();
        Collections.sort(list, (a, b) -> collator.compare(a.label.toString(), b.label.toString()));
        return list;
    }

    private static void collect(PackageManager pm, String category, Map<String, App> out) {
        Intent query = new Intent(Intent.ACTION_MAIN).addCategory(category);
        for (ResolveInfo ri : pm.queryIntentActivities(query, 0)) {
            ActivityInfo ai = ri.activityInfo;
            if (out.containsKey(ai.packageName)) continue;
            Intent launch = new Intent(Intent.ACTION_MAIN)
                    .addCategory(category)
                    .setComponent(new ComponentName(ai.packageName, ai.name))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            Drawable icon = ri.loadIcon(pm);
            out.put(ai.packageName, new App(ai.packageName, launch, ri.loadLabel(pm), glyph(icon), icon));
        }
    }

    /**
     * Turns an app icon into a white logo: the themed (monochrome) layer if the app ships one,
     * else the adaptive foreground, else the icon itself, as long as it is a shape on a clear
     * background. Full-bleed icons return null and are shown in colour instead.
     */
    private static Bitmap glyph(Drawable icon) {
        Drawable src = icon;
        boolean adaptive = icon instanceof AdaptiveIconDrawable;
        if (adaptive) {
            AdaptiveIconDrawable ad = (AdaptiveIconDrawable) icon;
            Drawable mono = Build.VERSION.SDK_INT >= 33 ? ad.getMonochrome() : null;
            src = mono != null ? mono : ad.getForeground();
            if (src == null) return null;
        }
        int s = 144;
        Bitmap b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
        if (adaptive) src.setBounds(-s / 4, -s / 4, s + s / 4, s + s / 4); // keep the 72/108 safe zone
        else src.setBounds(0, 0, s, s);
        src.draw(new Canvas(b));

        int[] px = new int[s * s];
        b.getPixels(px, 0, s, 0, 0, s, s);
        int solid = 0, l = s, t = s, r = -1, btm = -1;
        for (int y = 0; y < s; y++) {
            for (int x = 0; x < s; x++) {
                if ((px[y * s + x] >>> 24) < 40) continue;
                solid++;
                if (x < l) l = x;
                if (x > r) r = x;
                if (y < t) t = y;
                if (y > btm) btm = y;
            }
        }
        if (solid == 0 || solid > s * s * 0.5f) return null;
        return Bitmap.createBitmap(b, l, t, r - l + 1, btm - t + 1); // trimmed so logos line up
    }
}
