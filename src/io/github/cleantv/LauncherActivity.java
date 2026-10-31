package io.github.cleantv;

import android.animation.ObjectAnimator;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.Typeface;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Wallpaper, clock, three actions and a frosted dock of favourite apps. */
public final class LauncherActivity extends Activity {

    private static final String PREF_DOCK = "dock";
    private static final String PREF_WALL = "wall";
    private static final int DOCK_DEFAULT = 6;
    private static final int GRID_COLS = 6;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService bg = Executors.newSingleThreadExecutor();

    private SharedPreferences prefs;
    private float dp;
    private int screenW;

    private ImageView wall;
    private View scrim;
    private View home;
    private TextView clock;
    private TextView actionLabel;
    private Frost dockFrost;
    private HorizontalScrollView dockScroll;
    private LinearLayout dockRow;
    private FrameLayout dockBox;
    private ScrollView listScroll;
    private LinearLayout listCol;
    private View listGlow;
    private LinearLayout actions;
    private SettingsPanel settings;
    private boolean settingsOpen;
    private String returnTo; // Settings page to reopen when the picker or All apps closes
    private View dockGlow;        // the one highlight that slides between dock apps
    private boolean animateDockIn; // play the entrance on the next render
    private View gridPanel;
    private Frost gridFrost;
    private ScrollView gridScroll;
    private GridLayout grid;

    private ImageView weatherIcon;
    private TextView weatherText;
    private Weather weather;
    private Island island;
    private Sessions sessions;
    private MediaController media;
    private NowCard artCard; // now playing, shown only in Art Mode

    private List<App> apps = new ArrayList<>(); // every launchable app, A–Z
    private String wallFiles = "";              // file names behind the current wallpaper
    private String focusPkg;
    private boolean resumed;
    private boolean gridOpen;
    private boolean artMode;
    private boolean swallowKeyUp;

    // wallpaper picker + rotation
    private static final String PREF_ROTATE = "rotate";
    private static final String[] ROTATE_NAMES = {"Fixed", "Each visit", "15 min", "Hourly"};
    private static final int[] ROTATE_MINUTES = {0, 0, 15, 60};
    private View pickerPanel;
    private Frost pickerFrost;
    private ScrollView pickerScroll;
    private LinearLayout pickerList, rotateRow;
    private List<File> pickerFiles = new ArrayList<>();
    private boolean pickerOpen;
    private int previewIndex = -1, pendingPreview = -1;
    private int homeMinutes; // minutes the home screen has been on screen since the last change

    private final Runnable previewSoon = () -> preview(pendingPreview); // waits for focus to settle

    private final Runnable rotateTick = new Runnable() {
        @Override public void run() {
            int every = ROTATE_MINUTES[rotation()];
            if (every > 0 && !gridOpen && !pickerOpen && ++homeMinutes >= every) {
                homeMinutes = 0;
                cycleWall(1);
            }
            ui.postDelayed(this, 60_000);
        }
    };

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            clock.setText(DateFormat.getTimeFormat(LauncherActivity.this).format(new Date()));
            weather.refresh(false);
            ui.postDelayed(this, 60_000 - System.currentTimeMillis() % 60_000);
        }
    };

    private final BroadcastReceiver packageReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { loadApps(); }
    };

    // ---- lifecycle ----

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        DisplayMetrics m = getResources().getDisplayMetrics();
        dp = m.density;
        screenW = m.widthPixels;
        prefs = getSharedPreferences("cleantv", MODE_PRIVATE);

        FrameLayout root = new FrameLayout(this);
        wall = new ImageView(this);
        wall.setScaleType(ImageView.ScaleType.CENTER_CROP);
        root.addView(wall, match());

        scrim = new View(this);
        scrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[] {0x66000000, 0x00000000, 0x00000000, 0x40000000}));
        root.addView(scrim, match());

        home = buildHome();
        root.addView(home, match());
        artCard = new NowCard(this);
        artCard.setActive(false);
        root.addView(artCard, at(Gravity.TOP | Gravity.END, 0, px(32), px(48), 0));
        sessions = new Sessions(this, this::showNowPlaying);
        gridPanel = buildGrid();
        gridPanel.setVisibility(View.GONE);
        root.addView(gridPanel, match());
        pickerPanel = buildPicker();
        pickerPanel.setVisibility(View.GONE);
        root.addView(pickerPanel, match());
        settings = new SettingsPanel(this, settingsHost);
        settings.setVisibility(View.GONE);
        root.addView(settings, match());
        setContentView(root);

        applyWall(Wall.gradient(), false);

        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_PACKAGE_ADDED);
        f.addAction(Intent.ACTION_PACKAGE_REMOVED);
        f.addAction(Intent.ACTION_PACKAGE_CHANGED);
        f.addDataScheme("package");
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(packageReceiver, f, RECEIVER_NOT_EXPORTED);
        else registerReceiver(packageReceiver, f);

        loadApps();
        weather.setLocation(getIntent());
        weather.show();
    }

    @Override protected void onStart() {
        super.onStart();
        if (gridOpen || pickerOpen || settingsOpen) return;
        if (appRow().getChildCount() > 0) {         // back from an app
            dockIn();
            if (rotation() == 1) cycleWall(1);
        }
        else animateDockIn = true;                   // first launch: after apps load
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        tick.run();
        ui.postDelayed(rotateTick, 60_000);
        startMedia();
        loadWall(false); // picks up pictures pushed while we were away; a no-op otherwise
    }

    @Override protected void onPause() {
        super.onPause();
        resumed = false;
        ui.removeCallbacks(tick);
        ui.removeCallbacks(rotateTick);
        stopMedia();
        if (artMode) exitArt();
    }

    @Override protected void onDestroy() {
        unregisterReceiver(packageReceiver);
        bg.shutdownNow();
        super.onDestroy();
    }

    /** Home pressed while already home: step back out of whatever is open. */
    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (weather.setLocation(intent)) return;
        boolean panel = gridOpen || pickerOpen || settingsOpen;
        if (resumed && !panel) {          // already home: back to the first app
            onBackPressed();
            return;
        }
        returnTo = null;                  // Home means the home screen, from any panel
        if (pickerOpen) closePicker();
        if (gridOpen) showGrid(false);
        if (settingsOpen) closeSettings();
    }

    @Override public void onBackPressed() {
        if (artMode) exitArt();
        else if (settingsOpen) { if (!settings.back()) closeSettings(); }
        else if (pickerOpen) closePicker();
        else if (gridOpen) showGrid(false);
        else goTop();
    }

    private void goTop() {
        focusPkg = null;
        if (appRow().getChildCount() > 0) appRow().getChildAt(0).requestFocus();
        dockScroll.smoothScrollTo(0, 0);
        listScroll.smoothScrollTo(0, 0);
    }

    // ---- keys (art mode) ----

    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        int k = e.getKeyCode();
        if (swallowKeyUp && e.getAction() == KeyEvent.ACTION_UP) {
            swallowKeyUp = false; // the press that woke us from art mode shouldn't also click
            return true;
        }
        if (!artMode || k == KeyEvent.KEYCODE_VOLUME_UP || k == KeyEvent.KEYCODE_VOLUME_DOWN
                || k == KeyEvent.KEYCODE_VOLUME_MUTE) {
            return super.dispatchKeyEvent(e);
        }
        if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) {
            if (k == KeyEvent.KEYCODE_DPAD_LEFT) cycleWall(-1);
            else if (k == KeyEvent.KEYCODE_DPAD_RIGHT) cycleWall(1);
            else {
                exitArt();
                swallowKeyUp = true;
            }
        }
        return true;
    }

    private void enterArt() {
        artMode = true;
        artCard.setActive(true);
        home.animate().alpha(0f).setDuration(300).start();
        scrim.animate().alpha(0f).setDuration(300).start();
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    private void exitArt() {
        artMode = false;
        artCard.setActive(false);
        home.animate().alpha(1f).setDuration(200).start();
        scrim.animate().alpha(1f).setDuration(200).start();
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    // ---- views ----

    private View buildHome() {
        FrameLayout h = new FrameLayout(this);
        h.setClipChildren(false);

        LinearLayout clockRow = new LinearLayout(this);
        clockRow.setGravity(Gravity.CENTER_VERTICAL);
        clock = label(28);
        clockRow.addView(clock);
        weatherIcon = new ImageView(this);
        weatherIcon.setAlpha(0.9f);
        LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(px(24), px(24));
        wlp.setMargins(px(22), px(2), px(8), 0);
        clockRow.addView(weatherIcon, wlp);
        weatherText = label(22);
        clockRow.addView(weatherText);
        weather = new Weather(prefs, ui, bg, weatherIcon, weatherText);
        h.addView(clockRow, at(Gravity.TOP | Gravity.START, px(64), px(30), 0, 0));

        FrameLayout top = new FrameLayout(this);
        top.setClipChildren(false);
        actions = new LinearLayout(this);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(actions);
        actionLabel = label(13);
        actionLabel.setAlpha(0f);
        top.addView(actionLabel, at(Gravity.TOP | Gravity.START, 0, px(40), 0, 0));
        h.addView(top, at(Gravity.TOP | Gravity.END, 0, px(32), px(54), 0));
        buildActions();

        // dock layout: a frosted bar along the bottom
        dockBox = new FrameLayout(this);
        clipRound(dockBox, px(22));
        dockFrost = new Frost(this, 0);
        dockBox.addView(dockFrost, match());
        dockScroll = new HorizontalScrollView(this);
        dockScroll.setHorizontalScrollBarEnabled(false);
        dockScroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        dockScroll.setPadding(px(8), px(8), px(8), px(8));
        dockScroll.setClipToPadding(false);
        dockRow = new LinearLayout(this);
        FrameLayout dockContent = new FrameLayout(this);
        dockGlow = new View(this);
        dockGlow.setBackground(rounded(0x2EFFFFFF, 0, px(16)));
        dockGlow.setAlpha(0f);
        dockContent.addView(dockGlow, new FrameLayout.LayoutParams(px(140), px(104)));
        dockContent.addView(dockRow);
        dockScroll.addView(dockContent);
        dockBox.addView(dockScroll, new FrameLayout.LayoutParams(-1, -2));
        dockBox.setForeground(rounded(0, 0x26FFFFFF, px(22)));
        FrameLayout.LayoutParams lp = at(Gravity.BOTTOM, px(48), 0, px(48), px(48));
        lp.width = ViewGroup.LayoutParams.MATCH_PARENT;
        lp.height = px(104 + 16); // one row of items plus padding
        h.addView(dockBox, lp);

        // list layout: a column of apps down the left
        listScroll = new ScrollView(this);
        listScroll.setVerticalScrollBarEnabled(false);
        listScroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        listScroll.setClipToPadding(false);
        listScroll.setClipChildren(false);
        listScroll.setPadding(0, px(12), px(24), px(12));
        FrameLayout listContent = new FrameLayout(this);
        listContent.setClipChildren(false);
        listGlow = new View(this);
        listGlow.setBackground(rounded(0x2EFFFFFF, 0, px(16)));
        listGlow.setAlpha(0f);
        listContent.addView(listGlow, new FrameLayout.LayoutParams(px(300), px(60)));
        listCol = new LinearLayout(this);
        listCol.setOrientation(LinearLayout.VERTICAL);
        listContent.addView(listCol, new FrameLayout.LayoutParams(-2, -2));
        listScroll.addView(listContent);
        FrameLayout.LayoutParams llp = at(Gravity.START | Gravity.TOP, px(44), px(118), 0, px(36));
        llp.height = ViewGroup.LayoutParams.MATCH_PARENT; // just under the clock; scrolls if there are many apps
        h.addView(listScroll, llp);

        island = new Island(this, () -> {
            if (appRow().getChildCount() > 0) appRow().getChildAt(0).requestFocus();
        });
        h.addView(island, new FrameLayout.LayoutParams(px(160), px(40), Gravity.TOP | Gravity.CENTER_HORIZONTAL));
        ((FrameLayout.LayoutParams) island.getLayoutParams()).topMargin = px(30);
        showLayout();
        return h;
    }

    // ---- appearance (Settings) ----

    private String look(String key, String def) {
        return prefs.getString(key, def);
    }

    private boolean isList() {
        return "list".equals(look("layout", "dock"));
    }

    private boolean typeFocus() {
        return "type".equals(look("focus", "glass"));
    }

    private LinearLayout appRow() {
        return isList() ? listCol : dockRow;
    }

    private View appGlow() {
        return isList() ? listGlow : dockGlow;
    }

    private void showLayout() {
        boolean list = isList();
        dockBox.setVisibility(list ? View.GONE : View.VISIBLE);
        listScroll.setVisibility(list ? View.VISIBLE : View.GONE);
    }

    /** Re-lays the home screen after a Settings change, keeping the same app selected. */
    private void applyAppearance() {
        showLayout();
        buildActions();
        render();
    }

    private void buildActions() {
        String mode = look("controls", "icons");
        actions.removeAllViews();
        actionLabel.setVisibility("icons".equals(mode) ? View.VISIBLE : View.GONE);
        actions.addView(action(R.drawable.ic_art, "Art Mode", mode, v -> enterArt()));
        actions.addView(action(R.drawable.ic_wallpaper, "Wallpaper", mode, v -> openPicker()));
        actions.addView(action(R.drawable.ic_apps, "All apps", mode, v -> showGrid(true)));
        actions.addView(action(R.drawable.ic_settings, "Settings", mode, v -> openSettings()));
    }

    private View buildGrid() {
        FrameLayout p = new FrameLayout(this);
        gridFrost = new Frost(this, 0x99000000);
        p.addView(gridFrost, match());

        gridScroll = new ScrollView(this);
        gridScroll.setVerticalScrollBarEnabled(false);
        gridScroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        gridScroll.setClipToPadding(false);
        gridScroll.setPadding(px(58), px(36), px(58), px(36));
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView title = label(24);
        title.setText("All apps");
        title.setPadding(px(6), 0, 0, px(14));
        col.addView(title);
        grid = new GridLayout(this);
        grid.setColumnCount(GRID_COLS);
        col.addView(grid);
        gridScroll.addView(col);
        p.addView(gridScroll, match());
        return p;
    }

    /** A home control: an icon (name shown underneath on focus), its name, or both side by side. */
    private View action(int icon, final String text, String mode, View.OnClickListener click) {
        final LinearLayout a = new LinearLayout(this);
        a.setGravity(Gravity.CENTER);
        a.setFocusable(true);
        final boolean icons = "icons".equals(mode), words = !icons;
        final ImageView iv = new ImageView(this);
        iv.setImageResource(icon);
        iv.setAlpha(0.8f);
        if (!"text".equals(mode)) a.addView(iv, new LinearLayout.LayoutParams(px(icons ? 22 : 18), px(icons ? 22 : 18)));
        final TextView t = label(15);
        t.setAlpha(0.8f);
        if (words) {
            t.setText(text);
            t.setPadding("both".equals(mode) ? px(8) : 0, 0, 0, 0);
            a.addView(t);
            a.setPadding(px(12), px(6), px(12), px(6));
            a.setLayoutParams(new LinearLayout.LayoutParams(-2, px(36)));
        } else {
            a.setLayoutParams(new LinearLayout.LayoutParams(px(44), px(36)));
        }
        final GradientDrawable plate = rounded(0x2EFFFFFF, 0, px(18));
        plate.setAlpha(0);
        if (words) a.setBackground(plate);
        a.setOnFocusChangeListener((v, has) -> {
            iv.animate().alpha(has ? 1f : 0.8f).scaleX(has && icons ? 1.15f : 1f).scaleY(has && icons ? 1.15f : 1f)
                    .setDuration(120).start();
            t.animate().alpha(has ? 1f : 0.8f).setDuration(120).start();
            if (words && !typeFocus()) ObjectAnimator.ofInt(plate, "alpha", plate.getAlpha(), has ? 255 : 0).setDuration(140).start();
            if (icons) {
                if (has) {
                    actionLabel.setText(text);
                    actionLabel.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
                    actionLabel.setTranslationX(v.getLeft() + v.getWidth() / 2f - actionLabel.getMeasuredWidth() / 2f);
                }
                actionLabel.animate().alpha(has ? 1f : 0f).setDuration(120).start();
            }
        });
        a.setOnClickListener(click);
        return a;
    }

    private static final int KIND_DOCK = 0, KIND_GRID = 1, KIND_LIST = 2;

    private View item(final App a, final int kind) {
        final boolean docked = kind != KIND_GRID;
        final String display = kind == KIND_GRID ? "both" : look("apps", "both");
        final boolean showIcon = !"text".equals(display), alwaysLabel = !"icons".equals(display);
        final boolean type = docked && typeFocus();
        final float rest = type ? 0.5f : 0.75f; // how bright an app is when it isn't selected

        LinearLayout v = new LinearLayout(this);
        v.setOrientation(kind == KIND_LIST ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        v.setGravity(kind == KIND_LIST ? Gravity.CENTER_VERTICAL : Gravity.CENTER);
        v.setFocusable(true);
        if (kind == KIND_LIST) v.setPadding(px(18), 0, px(26), 0);
        final GradientDrawable bg = rounded(0x2EFFFFFF, 0, px(16));
        bg.setAlpha(0);
        if (!docked) v.setBackground(bg);

        final ImageView iv = new ImageView(this);
        if (showIcon) {
            LinearLayout.LayoutParams ilp;
            if (a.glyph != null) {
                iv.setImageBitmap(a.glyph);
                iv.setColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN);
                iv.setScaleType(kind == KIND_LIST ? ImageView.ScaleType.FIT_START : ImageView.ScaleType.FIT_CENTER);
                ilp = kind == KIND_LIST ? new LinearLayout.LayoutParams(px(56), px(28))
                        : new LinearLayout.LayoutParams(px(84), px(34));
            } else {
                iv.setImageDrawable(a.icon);
                clipRound(iv, px(8));
                ilp = kind == KIND_LIST ? new LinearLayout.LayoutParams(px(30), px(30))
                        : new LinearLayout.LayoutParams(px(36), px(36));
                if (kind == KIND_LIST) ilp.rightMargin = px(26);
            }
            v.addView(iv, ilp);
        }

        final TextView t = label(kind == KIND_LIST ? 24 : showIcon ? 16 : 20);
        t.setText(a.label);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        if (kind == KIND_LIST) {
            t.setPadding(showIcon ? px(18) : 0, 0, 0, 0);
            v.addView(t, new LinearLayout.LayoutParams(-2, -2));
        } else {
            t.setGravity(Gravity.CENTER);
            t.setPadding(px(10), showIcon ? px(10) : 0, px(10), 0);
            v.addView(t, new LinearLayout.LayoutParams(-1, -2));
        }
        if (docked) {
            t.setAlpha(alwaysLabel ? rest : 0f);
            if (type) iv.setAlpha(rest);
        }

        v.setOnFocusChangeListener((view, has) -> {
            if (docked) {
                if (kind == KIND_DOCK) {
                    iv.animate().scaleX(has ? 1.1f : 1f).scaleY(has ? 1.1f : 1f)
                            .translationY(has ? -px(3) : 0).setDuration(180).setInterpolator(EASE).start();
                } else {
                    view.animate().translationX(has && type ? px(10) : 0).setDuration(180).setInterpolator(EASE).start();
                }
                if (type) iv.animate().alpha(has ? 1f : rest).setDuration(180).start();
                t.animate().alpha(has ? 1f : alwaysLabel ? rest : 0f).setDuration(180).start();
                if (!type) glide(view, has, kind == KIND_LIST);
            } else {
                ObjectAnimator.ofInt(bg, "alpha", bg.getAlpha(), has ? 255 : 0).setDuration(140).start();
            }
            if (has) focusPkg = a.pkg;
        });
        v.setOnClickListener(view -> {
            pulse(view); // runs alongside the launch, never delays it
            launch(a);
        });
        v.setOnLongClickListener(view -> { menu(a, docked); return true; });
        return v;
    }

    private static final DecelerateInterpolator EASE = new DecelerateInterpolator(2f);

    /** Slides the shared highlight to the focused app, or fades it when focus leaves the apps. */
    private void glide(View item, boolean has, boolean vertical) {
        View glow = vertical ? listGlow : dockGlow;
        if (!has) {
            glow.animate().alpha(0f).setDuration(160).start(); // cancelled if a neighbour takes focus
            return;
        }
        ViewGroup.LayoutParams lp = glow.getLayoutParams();
        int w = vertical ? Math.max(item.getWidth(), listCol.getWidth()) : item.getWidth();
        if (w > 0 && (lp.width != w || lp.height != item.getHeight())) {
            lp.width = w;
            lp.height = item.getHeight();
            glow.setLayoutParams(lp);
        }
        float to = vertical ? item.getTop() : item.getLeft();
        if (glow.getAlpha() < 0.05f) { // arriving: appear in place
            if (vertical) glow.setTranslationY(to);
            else glow.setTranslationX(to);
        }
        (vertical ? glow.animate().translationY(to) : glow.animate().translationX(to))
                .alpha(1f).setDuration(220).setInterpolator(EASE).start();
    }

    private void pulse(View v) {
        v.animate().scaleX(0.94f).scaleY(0.94f).setDuration(80).withEndAction(() ->
                v.animate().scaleX(1f).scaleY(1f).setDuration(160).setInterpolator(new OvershootInterpolator(2f)).start())
                .start();
    }

    /** Apps fade into place one after another. */
    private void dockIn() {
        LinearLayout row = appRow();
        boolean list = isList();
        for (int i = 0; i < row.getChildCount(); i++) {
            View v = row.getChildAt(i);
            v.setAlpha(0f);
            if (list) v.setTranslationX(-px(14));
            else v.setTranslationY(px(14));
            v.animate().alpha(1f).translationX(0).translationY(0).setStartDelay(i * 28L).setDuration(280)
                    .setInterpolator(EASE).start();
        }
        View glow = appGlow();
        glow.setAlpha(0f); // follows focus once the row has settled
        glow.animate().alpha(row.hasFocus() && !typeFocus() ? 1f : 0f).setStartDelay(120).setDuration(200).start();
    }

    private void render() {
        Map<String, App> byPkg = new LinkedHashMap<>();
        for (App a : apps) byPkg.put(a.pkg, a);
        View target = null;
        boolean list = isList();
        LinearLayout row = appRow();

        dockRow.removeAllViews();
        listCol.removeAllViews();
        dockGlow.setAlpha(0f);
        listGlow.setAlpha(0f);
        for (String pkg : dock()) {
            App a = byPkg.get(pkg);
            if (a == null) continue;
            View v = item(a, list ? KIND_LIST : KIND_DOCK);
            row.addView(v, list ? new LinearLayout.LayoutParams(-2, px(60)) : new LinearLayout.LayoutParams(px(140), px(104)));
            if (!gridOpen && pkg.equals(focusPkg)) target = v;
        }
        if (row.getChildCount() == 0) {
            TextView v = label(16);
            v.setText("+  Add apps");
            v.setGravity(Gravity.CENTER);
            v.setFocusable(true);
            v.setBackground(rounded(0x2EFFFFFF, 0, px(16)));
            v.setOnClickListener(view -> showGrid(true));
            row.addView(v, new LinearLayout.LayoutParams(px(160), list ? px(60) : px(104)));
        }

        grid.removeAllViews();
        int margin = px(6);
        int width = (screenW - px(116) - GRID_COLS * 2 * margin) / GRID_COLS;
        for (App a : apps) {
            View v = item(a, KIND_GRID);
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.width = width;
            lp.height = px(104);
            lp.setMargins(margin, margin, margin, margin);
            grid.addView(v, lp);
            if (gridOpen && a.pkg.equals(focusPkg)) target = v;
        }

        if (target == null) {
            ViewGroup g = gridOpen ? grid : row;
            if (g.getChildCount() > 0) target = g.getChildAt(0);
        }
        if (target != null && !artMode && !settingsOpen && !pickerOpen) target.requestFocus();
        if (animateDockIn && row.getChildCount() > 0) {
            animateDockIn = false;
            row.post(this::dockIn); // after layout, so the glow knows where the item is
        }
    }

    private void showGrid(boolean open) {
        gridOpen = open;
        focusPkg = null;
        if (open) {
            gridPanel.setAlpha(0f);
            gridPanel.setVisibility(View.VISIBLE);
            gridPanel.animate().alpha(1f).setDuration(160).start();
            home.setVisibility(View.INVISIBLE);
            gridScroll.scrollTo(0, 0);
            if (grid.getChildCount() > 0) grid.getChildAt(0).requestFocus();
        } else {
            gridPanel.setVisibility(View.GONE);
            home.setVisibility(View.VISIBLE);
            render();
            backToSettings();
        }
    }

    // ---- apps ----

    private void loadApps() {
        bg.execute(() -> {
            final List<App> list = App.query(this);
            ui.post(() -> {
                apps = list;
                render();
            });
        });
    }

    private void launch(App a) {
        HomeKeyService.allowStock = a.pkg.equals(Stock.GOOGLE_TV);
        try {
            startActivity(a.launch);
        } catch (Exception e) {
            Toast.makeText(this, "Can't open " + a.label, Toast.LENGTH_SHORT).show();
            loadApps();
        }
    }

    // ---- dock ----

    /** First-run dock order. Anything not installed is skipped. */
    private static final String[] POPULAR = {
            "com.google.android.youtube.tv", "com.netflix.ninja", "com.disney.disneyplus",
            "com.amazon.amazonvideo.livingroom", "com.wbd.stream", "com.apple.atve.androidtv.appletv",
            "com.spotify.tv.android", "com.plexapp.android", "com.hulu.livingroomplus",
            "com.google.android.youtube.tvmusic", "com.google.android.apps.youtube.music"};

    private List<String> dock() {
        String s = prefs.getString(PREF_DOCK, null);
        if (s == null) { // first run: start with a few apps, then it's yours
            List<String> first = new ArrayList<>();
            Set<String> installed = new HashSet<>();
            for (App a : apps) installed.add(a.pkg);
            for (String pkg : POPULAR) { // the apps most people open, if they have them
                if (first.size() < DOCK_DEFAULT && installed.contains(pkg)) first.add(pkg);
            }
            for (App a : apps) { // then anything else, A to Z, skipping the stock home tile
                if (first.size() >= DOCK_DEFAULT) break;
                if (!first.contains(a.pkg) && !a.pkg.equals(Stock.GOOGLE_TV)) first.add(a.pkg);
            }
            if (!first.isEmpty()) saveDock(first);
            return first;
        }
        return s.isEmpty() ? new ArrayList<String>() : new ArrayList<>(Arrays.asList(s.split(",")));
    }

    private void saveDock(List<String> pkgs) {
        prefs.edit().putString(PREF_DOCK, TextUtils.join(",", pkgs)).apply();
    }

    private void menu(final App a, boolean docked) {
        final List<String> names = new ArrayList<>();
        final List<Runnable> actions = new ArrayList<>();
        final List<String> d = dock();
        final boolean inDock = d.contains(a.pkg);
        if (docked) {
            names.add("Move left");
            actions.add(() -> moveInDock(a, -1));
            names.add("Move right");
            actions.add(() -> moveInDock(a, 1));
        }
        names.add(inDock ? "Remove from dock" : "Add to dock");
        actions.add(() -> {
            if (inDock) d.remove(a.pkg);
            else d.add(a.pkg);
            saveDock(d);
            if (inDock && docked) focusPkg = null;
            render();
        });
        names.add("App info");
        actions.add(() -> open(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", a.pkg, null))));
        names.add("Uninstall");
        actions.add(() -> open(new Intent(Intent.ACTION_DELETE, Uri.fromParts("package", a.pkg, null))));

        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(a.label)
                .setItems(names.toArray(new String[0]), (dialog, which) -> actions.get(which).run())
                .show();
    }

    private void moveInDock(App a, int delta) {
        List<String> d = dock();
        int from = d.indexOf(a.pkg);
        int to = Math.max(0, Math.min(d.size() - 1, from + delta));
        if (from < 0 || from == to) return;
        d.add(to, d.remove(from));
        saveDock(d);
        focusPkg = a.pkg;
        render();
    }

    // ---- wallpaper ----

    private int rotation() {
        return Math.max(0, Math.min(ROTATE_NAMES.length - 1, prefs.getInt(PREF_ROTATE, 0)));
    }

    // ---- wallpaper picker: browse to preview live, OK applies, Back puts the old one back ----

    private View buildPicker() {
        FrameLayout p = new FrameLayout(this);
        FrameLayout card = new FrameLayout(this);
        clipRound(card, px(26));
        pickerFrost = new Frost(this, 0x8C000000);
        card.addView(pickerFrost, match());

        LinearLayout pane = new LinearLayout(this);
        pane.setOrientation(LinearLayout.VERTICAL);
        pane.setPadding(px(28), px(26), px(28), 0);
        TextView title = label(24);
        title.setText("Wallpaper");
        pane.addView(title);
        TextView hint = label(13);
        hint.setAlpha(0.6f);
        hint.setText("Browse to preview  ·  OK to apply  ·  Back to close");
        hint.setPadding(0, px(4), 0, 0);
        pane.addView(hint);

        TextView rot = label(13);
        rot.setAlpha(0.6f);
        rot.setText("Rotation");
        rot.setPadding(0, px(18), 0, px(8));
        pane.addView(rot);
        rotateRow = new LinearLayout(this);
        for (int i = 0; i < ROTATE_NAMES.length; i++) {
            final int mode = i;
            TextView chip = label(14);
            chip.setText(ROTATE_NAMES[i]);
            chip.setShadowLayer(0, 0, 0, 0);
            chip.setFocusable(true);
            chip.setPadding(px(13), px(7), px(13), px(7));
            chip.setOnFocusChangeListener((v, has) -> refreshChips());
            chip.setOnClickListener(v -> {
                prefs.edit().putInt(PREF_ROTATE, mode).apply();
                homeMinutes = 0;
                refreshChips();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.rightMargin = px(6);
            rotateRow.addView(chip, lp);
        }
        pane.addView(rotateRow);

        pickerScroll = new ScrollView(this);
        pickerScroll.setVerticalScrollBarEnabled(false);
        pickerScroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        pickerScroll.setClipToPadding(true); // scrolled thumbnails stop at the list's top edge
        pickerScroll.setPadding(0, px(18), 0, px(26));
        pickerList = new LinearLayout(this);
        pickerList.setOrientation(LinearLayout.VERTICAL);
        pickerList.setClipChildren(false);
        pickerScroll.addView(pickerList);
        pane.addView(pickerScroll, new LinearLayout.LayoutParams(-1, 0, 1f));
        card.addView(pane, match());

        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(px(380), -1, Gravity.START);
        clp.setMargins(px(40), px(36), 0, px(36));
        p.addView(card, clp);
        return p;
    }

    private void refreshChips() {
        int mode = rotation();
        for (int i = 0; i < rotateRow.getChildCount(); i++) {
            TextView chip = (TextView) rotateRow.getChildAt(i);
            boolean focused = chip.isFocused();
            chip.setBackground(rounded(focused ? 0xFFFFFFFF : i == mode ? 0x33FFFFFF : 0, 0, px(16)));
            chip.setTextColor(focused ? 0xFF000000 : 0xF2FFFFFF);
        }
    }

    // ---- settings ----

    private void openSettings() {
        openSettingsAt(null);
    }

    private void openSettingsAt(String page) {
        settingsOpen = true;
        settings.setAlpha(0f);
        settings.setVisibility(View.VISIBLE);
        settings.animate().alpha(1f).setDuration(180).start();
        home.setVisibility(View.INVISIBLE); // still laid out, so the preview can draw it
        if (page == null || page.isEmpty()) settings.open();
        else settings.openPage(page);
    }

    private void closeSettings() {
        settingsOpen = false;
        settings.setVisibility(View.GONE);
        home.setVisibility(View.VISIBLE);
        render();
    }

    /** The picker or All apps was opened from Settings: go back there. */
    private boolean backToSettings() {
        if (returnTo == null) return false;
        String page = returnTo;
        returnTo = null;
        openSettingsAt(page);
        return true;
    }

    /** The real home screen, drawn small for the Settings preview. */
    private Bitmap snapshot(int w, int h) {
        View root = (View) home.getParent();
        if (root.getWidth() == 0) return null;
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        c.scale(w / (float) root.getWidth(), h / (float) root.getHeight());
        try {
            wall.draw(c);
            scrim.draw(c);
            home.draw(c);
        } catch (RuntimeException e) {
            // a view that can't draw in software: show what we have
        }
        return b;
    }

    private final SettingsPanel.Host settingsHost = new SettingsPanel.Host() {
        @Override public SharedPreferences prefs() { return prefs; }
        @Override public void applyAppearance() { LauncherActivity.this.applyAppearance(); }
        @Override public Bitmap snapshot(int w, int h) { return LauncherActivity.this.snapshot(w, h); }

        @Override public String wallpaperName() {
            List<File> files = Walls.files(LauncherActivity.this);
            if (files.isEmpty()) return "Warm gradient";
            return prettyName(files.get(Math.floorMod(prefs.getInt(PREF_WALL, 0), files.size())));
        }

        @Override public int homeAppCount() {
            int n = 0;
            for (String pkg : dock()) for (App a : apps) if (a.pkg.equals(pkg)) { n++; break; }
            return n;
        }

        @Override public void openWallpaperPicker() {
            settingsOpen = false;
            settings.setVisibility(View.GONE);
            openPicker();
            returnTo = pickerOpen ? "background" : null;
            if (!pickerOpen) openSettingsAt("background"); // nothing to pick from
        }

        @Override public void openAllApps() {
            settingsOpen = false;
            settings.setVisibility(View.GONE);
            returnTo = "";
            showGrid(true);
        }

        @Override public void openStockHome() {
            ComponentName stock = Stock.find(LauncherActivity.this);
            if (stock == null) return;
            HomeKeyService.allowStock = true;
            open(Stock.intent(stock));
        }

        @Override public void openTvSettings() { open(new Intent(Settings.ACTION_SETTINGS)); }

        @Override public void openSystemPage(String action) {
            try {
                startActivity(new Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Exception e) { // this TV has no direct screen for it
                open(new Intent(Settings.ACTION_SETTINGS));
            }
        }
    };

    private void openPicker() {
        pickerFiles = Walls.files(this);
        if (pickerFiles.isEmpty()) {
            Toast.makeText(this, "No wallpapers yet. Add some with ./tv.sh wallpaper photo.jpg", Toast.LENGTH_LONG).show();
            return;
        }
        pickerOpen = true;
        previewIndex = -1;
        final int applied = Math.floorMod(prefs.getInt(PREF_WALL, 0), pickerFiles.size());
        pickerList.removeAllViews();
        final List<ImageView> thumbs = new ArrayList<>();
        for (int i = 0; i < pickerFiles.size(); i++) thumbs.add(pickerItem(i, i == applied));
        refreshChips();

        pickerPanel.setAlpha(0f);
        pickerPanel.setVisibility(View.VISIBLE);
        pickerPanel.animate().alpha(1f).setDuration(180).start();
        home.setVisibility(View.INVISIBLE);
        pickerList.post(() -> pickerList.getChildAt(applied).requestFocus());

        final List<File> files = pickerFiles;
        final int w = px(324), h = px(182);
        bg.execute(() -> { // small thumbnails, loaded after the panel is already up
            for (int i = 0; i < files.size(); i++) {
                final int n = i;
                final Bitmap b;
                try {
                    b = Walls.decode(files.get(i), w, h);
                } catch (Throwable t) {
                    continue;
                }
                ui.post(() -> { if (pickerOpen && files == pickerFiles) thumbs.get(n).setImageBitmap(b); });
            }
        });
    }

    private ImageView pickerItem(final int index, boolean applied) {
        FrameLayout item = new FrameLayout(this);
        item.setFocusable(true);
        clipRound(item, px(14));
        item.setBackground(rounded(0xFF1C1C1F, 0, px(14)));

        ImageView img = new ImageView(this);
        img.setScaleType(ImageView.ScaleType.CENTER_CROP);
        item.addView(img, match());
        View shade = new View(this);
        shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[] {0x00000000, 0x00000000, 0x99000000}));
        item.addView(shade, match());

        TextView name = label(14);
        name.setText(prettyName(pickerFiles.get(index)));
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        name.setPadding(px(12), 0, px(40), px(10));
        item.addView(name, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));

        TextView check = label(13);
        check.setText("✓");
        check.setGravity(Gravity.CENTER);
        check.setShadowLayer(0, 0, 0, 0);
        check.setTextColor(0xFF000000);
        GradientDrawable dot = new GradientDrawable();
        dot.setShape(GradientDrawable.OVAL);
        dot.setColor(0xFFFFFFFF);
        check.setBackground(dot);
        check.setTag("check");
        check.setVisibility(applied ? View.VISIBLE : View.GONE);
        FrameLayout.LayoutParams ck = new FrameLayout.LayoutParams(px(24), px(24), Gravity.TOP | Gravity.END);
        ck.setMargins(0, px(10), px(10), 0);
        item.addView(check, ck);

        final GradientDrawable ring = rounded(0, 0xFFFFFFFF, px(14));
        ring.setStroke(px(3), 0xFFFFFFFF);
        item.setOnFocusChangeListener((v, has) -> {
            v.setForeground(has ? ring : null);
            if (has) {
                v.post(() -> centerInPicker(v));
                pendingPreview = index;
                ui.removeCallbacks(previewSoon);
                ui.postDelayed(previewSoon, 220);
            }
        });
        item.setOnClickListener(v -> applyPick(index));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, px(182));
        lp.bottomMargin = px(14);
        pickerList.addView(item, lp);
        return img;
    }

    /** Keeps the focused thumbnail in the middle of the list, caption and outline in full view. */
    private void centerInPicker(View item) {
        int middle = item.getTop() + item.getHeight() / 2 + pickerScroll.getPaddingTop();
        int target = middle - pickerScroll.getHeight() / 2;
        int max = Math.max(0, pickerList.getHeight() + pickerScroll.getPaddingTop()
                + pickerScroll.getPaddingBottom() - pickerScroll.getHeight());
        pickerScroll.smoothScrollTo(0, Math.max(0, Math.min(max, target)));
    }

    private static String prettyName(File f) {
        String n = f.getName().replaceFirst("\\.[^.]+$", "").replaceFirst("^\\d+[-_ ]*", "").replace('-', ' ').replace('_', ' ');
        return n.isEmpty() ? f.getName() : Character.toUpperCase(n.charAt(0)) + n.substring(1);
    }

    private void preview(final int index) {
        if (!pickerOpen || index < 0 || index == previewIndex) return;
        previewIndex = index;
        final File f = pickerFiles.get(index);
        bg.execute(() -> {
            final Wall w;
            try {
                w = Wall.fromFile(this, f);
            } catch (Throwable t) {
                return;
            }
            ui.post(() -> { if (pickerOpen && previewIndex == index) applyWall(w, true); });
        });
    }

    private void applyPick(int index) {
        prefs.edit().putInt(PREF_WALL, index).apply();
        wallFiles = pickerFiles + "#" + index; // the preview on screen already is this wallpaper
        homeMinutes = 0;
        ui.removeCallbacks(previewSoon);
        preview(index);
        for (int i = 0; i < pickerList.getChildCount(); i++) {
            pickerList.getChildAt(i).findViewWithTag("check").setVisibility(i == index ? View.VISIBLE : View.GONE);
        }
    }

    private void closePicker() {
        pickerOpen = false;
        ui.removeCallbacks(previewSoon);
        pickerPanel.setVisibility(View.GONE);
        pickerList.removeAllViews(); // lets the thumbnails go
        home.setVisibility(View.VISIBLE);
        int applied = Math.floorMod(prefs.getInt(PREF_WALL, 0), Math.max(1, pickerFiles.size()));
        if (previewIndex != -1 && previewIndex != applied) { // browsed without applying: put it back
            wallFiles = "";
            loadWall(true);
        }
        if (!backToSettings()) render();
    }

    private void cycleWall(int delta) {
        prefs.edit().putInt(PREF_WALL, prefs.getInt(PREF_WALL, 0) + delta).apply();
        wallFiles = ""; // force a reload
        loadWall(true);
    }

    private void loadWall(final boolean fade) {
        bg.execute(() -> {
            List<File> files = Walls.files(this);
            String key = files + "#" + prefs.getInt(PREF_WALL, 0);
            if (key.equals(wallFiles)) return;
            Wall w = null;
            if (!files.isEmpty()) {
                File f = files.get(Math.floorMod(prefs.getInt(PREF_WALL, 0), files.size()));
                try {
                    w = Wall.fromFile(this, f);
                } catch (Throwable ignored) {
                    // unreadable picture: fall back to the gradient
                }
            }
            final Wall done = w != null ? w : Wall.gradient();
            final String k = key;
            ui.post(() -> {
                wallFiles = k;
                applyWall(done, fade);
            });
        });
    }

    private void applyWall(final Wall w, boolean fade) {
        Runnable set = () -> {
            Drawable old = wall.getDrawable();
            wall.setImageDrawable(w.image);
            Walls.releaseLater(ui, old, w.image, 1500);
            dockFrost.set(w);
            gridFrost.set(w);
            pickerFrost.set(w);
        };
        if (!fade) {
            set.run();
            return;
        }
        wall.animate().alpha(0f).setDuration(180).withEndAction(() -> {
            set.run();
            wall.animate().alpha(1f).setDuration(260).start();
        }).start();
    }

    // ---- now playing ----

    private void startMedia() {
        sessions.start();
    }

    private void stopMedia() {
        sessions.stop();
        media = null;
    }

    private void showNowPlaying(MediaController c) {
        media = c;
        island.show(c);
        artCard.show(c);
    }

    // ---- helpers ----

    private void open(Intent i) {
        try {
            startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception e) {
            Toast.makeText(this, "Not available on this device", Toast.LENGTH_SHORT).show();
        }
    }

    private TextView label(float sp) {
        TextView t = new TextView(this);
        t.setTextSize(sp);
        t.setTextColor(0xF2FFFFFF);
        Fonts.track(t, false);
        t.setShadowLayer(px(6), 0, px(1), 0x59000000);
        return t;
    }

    private GradientDrawable rounded(int fill, int stroke, float radius) {
        GradientDrawable d = new GradientDrawable();
        d.setCornerRadius(radius);
        d.setColor(fill);
        if (stroke != 0) d.setStroke(Math.max(1, px(1)), stroke);
        return d;
    }

    private static void clipRound(View v, final float radius) {
        v.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View view, Outline o) {
                o.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
            }
        });
        v.setClipToOutline(true);
    }

    private static FrameLayout.LayoutParams match() {
        return new FrameLayout.LayoutParams(-1, -1);
    }

    private static FrameLayout.LayoutParams at(int gravity, int l, int t, int r, int b) {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2, gravity);
        lp.setMargins(l, t, r, b);
        return lp;
    }

    private int px(float dips) {
        return Math.round(dips * dp);
    }
}
