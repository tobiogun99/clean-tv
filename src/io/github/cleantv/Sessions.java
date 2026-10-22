package io.github.cleantv;

import android.content.ComponentName;
import android.content.Context;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;

import java.util.List;

/** Follows whatever is playing (playing beats paused) and reports every change. */
final class Sessions {
    interface Listener {
        void onMedia(MediaController current); // null when nothing is playing
    }

    private final Context context;
    private final Listener listener;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private MediaSessionManager manager;
    private MediaController current;

    private final MediaSessionManager.OnActiveSessionsChangedListener changed = this::pick;

    private final MediaController.Callback callback = new MediaController.Callback() {
        @Override public void onPlaybackStateChanged(PlaybackState state) { listener.onMedia(current); }
        @Override public void onMetadataChanged(MediaMetadata metadata) { listener.onMedia(current); }
        @Override public void onSessionDestroyed() { pick(null); }
    };

    Sessions(Context context, Listener listener) {
        this.context = context;
        this.listener = listener;
    }

    void start() {
        manager = context.getSystemService(MediaSessionManager.class);
        try {
            manager.addOnActiveSessionsChangedListener(changed, me(), ui);
            pick(manager.getActiveSessions(me()));
        } catch (SecurityException notAllowed) {
            manager = null; // ./tv.sh nowplaying-on hasn't been run: nothing to show, no cost
        }
    }

    void stop() {
        if (manager != null) manager.removeOnActiveSessionsChangedListener(changed);
        if (current != null) current.unregisterCallback(callback);
        manager = null;
        current = null;
    }

    private ComponentName me() {
        return new ComponentName(context, MediaListener.class);
    }

    private void pick(List<MediaController> list) {
        if (list == null && manager != null) {
            try {
                list = manager.getActiveSessions(me());
            } catch (SecurityException e) {
                list = null;
            }
        }
        MediaController best = null;
        if (list != null) {
            for (MediaController c : list) {
                PlaybackState st = c.getPlaybackState();
                if (c.getMetadata() == null || st == null) continue;
                if (st.getState() == PlaybackState.STATE_PLAYING) { best = c; break; }
                if (best == null && st.getState() == PlaybackState.STATE_PAUSED) best = c;
            }
        }
        if (current != null && (best == null || !best.getSessionToken().equals(current.getSessionToken()))) {
            current.unregisterCallback(callback);
            current = null;
        }
        if (best != null && current == null) {
            current = best;
            current.registerCallback(callback, ui);
        }
        listener.onMedia(current);
    }
}
