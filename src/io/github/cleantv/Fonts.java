package io.github.cleantv;

import android.content.Context;
import android.content.res.AssetManager;
import android.graphics.Typeface;
import android.widget.TextView;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * The home screen's typeface, chosen in Settings. Every label registers here, so switching font
 * restyles everything on screen at once.
 *
 * Bundled: Geist and Newsreader (SIL OFL, see assets/fonts) and the system font. Fonts you own but
 * can't redistribute go in fonts-private/ (git-ignored); the build copies them in and they show up
 * here by file name, e.g. fonts-private/PP Neue Montreal.otf -> "PP Neue Montreal".
 */
final class Fonts {
    private static final String PRIVATE_DIR = "fonts-private";

    private static String[] ids, names;
    private static String family;
    private static final Map<String, Typeface[]> cache = new HashMap<>();
    private static final Map<TextView, Boolean> tracked = new WeakHashMap<>();

    private Fonts() {}

    /** Font ids for Settings: bundled first, then any private fonts. */
    static String[] ids(Context c) {
        load(c);
        return ids;
    }

    static String[] names(Context c) {
        load(c);
        return names;
    }

    private static void load(Context c) {
        if (ids != null) return;
        List<String> i = new ArrayList<>(), n = new ArrayList<>();
        i.add("geist");
        n.add("Geist");
        i.add("newsreader");
        n.add("Newsreader");
        i.add("system");
        n.add("System");
        try {
            String[] files = c.getAssets().list(PRIVATE_DIR);
            if (files != null) {
                for (String f : files) {
                    String lower = f.toLowerCase();
                    if (!lower.endsWith(".ttf") && !lower.endsWith(".otf")) continue;
                    i.add(0, "private:" + f); // your own fonts first: you added them on purpose
                    n.add(0, f.replaceFirst("\\.[^.]+$", "").replace('_', ' ').replace('-', ' '));
                }
            }
        } catch (IOException ignored) {
            // no private fonts
        }
        ids = i.toArray(new String[0]);
        names = n.toArray(new String[0]);
    }

    static String family(Context c) {
        if (family == null) {
            family = c.getSharedPreferences("cleantv", Context.MODE_PRIVATE).getString("font", ids(c)[0]);
        }
        return family;
    }

    static String name(Context c) {
        String[] i = ids(c), n = names(c);
        for (int k = 0; k < i.length; k++) if (i[k].equals(family(c))) return n[k];
        return n[0];
    }

    static Typeface book(Context c) {
        return faces(c, family(c))[0];
    }

    static Typeface medium(Context c) {
        return faces(c, family(c))[1];
    }

    /** Sets the face now and keeps it in step with later font changes. */
    static void track(TextView t, boolean medium) {
        t.setTypeface(medium ? medium(t.getContext()) : book(t.getContext()));
        tracked.put(t, medium);
    }

    static void untrack(TextView t) {
        tracked.remove(t);
    }

    static void setFamily(Context c, String id) {
        family = id;
        c.getSharedPreferences("cleantv", Context.MODE_PRIVATE).edit().putString("font", id).apply();
        for (Map.Entry<TextView, Boolean> e : tracked.entrySet()) {
            e.getKey().setTypeface(e.getValue() ? medium(c) : book(c));
        }
    }

    /** For drawing a sample in another font without restyling the screen. */
    static Typeface book(Context c, String id) {
        return faces(c, id)[0];
    }

    private static Typeface[] faces(Context c, String id) {
        Typeface[] f = cache.get(id);
        if (f != null) return f;
        AssetManager a = c.getAssets();
        try {
            if (id.startsWith("private:")) {
                Typeface t = Typeface.createFromAsset(a, PRIVATE_DIR + "/" + id.substring(8));
                f = new Typeface[] {t, t};
            } else if (id.equals("newsreader")) {
                f = new Typeface[] {variable(a, "fonts/newsreader.ttf", 400), variable(a, "fonts/newsreader.ttf", 500)};
            } else if (id.equals("system")) {
                f = new Typeface[] {Typeface.create("sans-serif", Typeface.NORMAL),
                        Typeface.create("sans-serif-medium", Typeface.NORMAL)};
            } else { // geist, and the fallback for a font that has since been removed
                f = new Typeface[] {variable(a, "fonts/geist.ttf", 400), variable(a, "fonts/geist.ttf", 500)};
            }
        } catch (RuntimeException e) {
            f = new Typeface[] {Typeface.DEFAULT, Typeface.DEFAULT_BOLD};
        }
        cache.put(id, f);
        return f;
    }

    private static Typeface variable(AssetManager a, String path, int weight) {
        return new Typeface.Builder(a, path).setFontVariationSettings("'wght' " + weight).build();
    }
}
