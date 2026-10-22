package io.github.cleantv;

import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.PlaybackState;
import android.os.SystemClock;
import android.text.TextUtils;

/** Everything the now-playing views show, read once from a media session. */
final class Track {
    final CharSequence title, sub;
    final Bitmap art;     // null: use icon
    final Drawable icon;  // the playing app's icon
    final boolean playing;
    final int accent;     // album colour, vivid enough for dark backgrounds
    private final MediaController media;

    private Track(MediaController media, CharSequence title, CharSequence sub, Bitmap art, Drawable icon,
                  boolean playing) {
        this.media = media;
        this.title = title;
        this.sub = sub;
        this.art = art;
        this.icon = icon;
        this.playing = playing;
        this.accent = accent(art);
    }

    /** null when there's nothing worth showing. */
    static Track of(Context ctx, MediaController c) {
        MediaMetadata md = c == null ? null : c.getMetadata();
        PlaybackState st = c == null ? null : c.getPlaybackState();
        int state = st == null ? PlaybackState.STATE_NONE : st.getState();
        if (md == null || state == PlaybackState.STATE_NONE || state == PlaybackState.STATE_STOPPED
                || state == PlaybackState.STATE_ERROR) {
            return null;
        }
        PackageManager pm = ctx.getPackageManager();
        CharSequence appName = c.getPackageName();
        Drawable icon = null;
        try {
            appName = pm.getApplicationLabel(pm.getApplicationInfo(c.getPackageName(), 0));
            icon = pm.getApplicationIcon(c.getPackageName());
        } catch (PackageManager.NameNotFoundException ignored) {
            // keep the package name
        }
        CharSequence t = md.getText(MediaMetadata.METADATA_KEY_TITLE);
        if (t == null) t = md.getText(MediaMetadata.METADATA_KEY_DISPLAY_TITLE);
        if (t == null) t = appName;
        CharSequence s = null; // first artist-ish field that isn't just the title again
        for (String key : new String[] {MediaMetadata.METADATA_KEY_ARTIST,
                MediaMetadata.METADATA_KEY_ALBUM_ARTIST, MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE}) {
            CharSequence v = md.getText(key);
            if (!TextUtils.isEmpty(v) && !TextUtils.equals(v, t)) {
                s = v;
                break;
            }
        }
        Bitmap b = md.getBitmap(MediaMetadata.METADATA_KEY_ART);
        if (b == null) b = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
        if (b == null) b = md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
        if (b != null && b.getConfig() == Bitmap.Config.HARDWARE) {
            b = b.copy(Bitmap.Config.ARGB_8888, false); // so the Settings preview can draw it
        }
        return new Track(c, t, s != null ? s + "  ·  " + appName : appName, b, icon,
                state == PlaybackState.STATE_PLAYING);
    }

    /** 0..1, or -1 when the app doesn't say how long the track is. */
    float progress() {
        MediaMetadata md = media.getMetadata();
        PlaybackState st = media.getPlaybackState();
        long dur = md == null ? 0 : md.getLong(MediaMetadata.METADATA_KEY_DURATION);
        if (st == null || dur <= 0) return -1;
        long pos = st.getPosition();
        if (st.getState() == PlaybackState.STATE_PLAYING) {
            pos += (long) ((SystemClock.elapsedRealtime() - st.getLastPositionUpdateTime()) * st.getPlaybackSpeed());
        }
        return Math.max(0, Math.min(1, pos / (float) dur));
    }

    private static int accent(Bitmap b) {
        if (b == null) return 0xFFFFFFFF;
        try {
            if (b.getConfig() == Bitmap.Config.HARDWARE) b = b.copy(Bitmap.Config.ARGB_8888, false);
            int c = Bitmap.createScaledBitmap(b, 1, 1, true).getPixel(0, 0);
            float[] hsv = new float[3];
            Color.colorToHSV(c, hsv);
            hsv[1] = Math.min(1f, hsv[1] * 1.4f);
            hsv[2] = Math.max(hsv[2], 0.9f);
            return Color.HSVToColor(hsv);
        } catch (RuntimeException e) {
            return 0xFFFFFFFF;
        }
    }
}
