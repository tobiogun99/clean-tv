package io.github.cleantv;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.ComponentName;
import android.content.Intent;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

/**
 * Google TV sends Home to its own launcher no matter what the default is. This service
 * takes a Home tap and opens Clean TV instead; holding Home still opens Google's dashboard panel.
 * If the stock home screen shows up any other way (boot, a crash), Clean TV replaces it.
 */
public final class HomeKeyService extends AccessibilityService {
    private static final long TAP_MS = 500;

    /** Set by Clean TV when the user opens the stock home on purpose. */
    static volatile boolean allowStock;

    private ComponentName stock;
    private boolean held;

    @Override protected void onServiceConnected() {
        stock = Stock.find(this);
        AccessibilityServiceInfo info = getServiceInfo(); // belt and braces with the XML flag
        info.flags |= AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS;
        setServiceInfo(info);
    }

    @Override protected boolean onKeyEvent(KeyEvent e) {
        if (e.getKeyCode() != KeyEvent.KEYCODE_HOME) return false;
        if (e.getAction() == KeyEvent.ACTION_DOWN) {
            if (e.getRepeatCount() == 0) held = false;
            else held = true;
            return !held; // let holds through: the system shows its dashboard
        }
        if (!held && e.getEventTime() - e.getDownTime() < TAP_MS) goHome();
        return !held;
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent e) {
        if (e.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || stock == null) return;
        CharSequence pkg = e.getPackageName(), cls = e.getClassName();
        if (pkg == null) return;
        if (pkg.toString().equals(stock.getPackageName())) {
            // only the home screen itself; the dashboard and other panels stay usable
            if (!allowStock && cls != null && cls.toString().equals(stock.getClassName())) goHome();
        } else if (!pkg.toString().equals("com.android.systemui")
                && !pkg.toString().equals(getPackageName())) {
            allowStock = false;
        }
    }

    @Override public void onInterrupt() {}

    private void goHome() {
        allowStock = false;
        try {
            startActivity(new Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_HOME)
                    .setClass(this, LauncherActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED));
        } catch (Exception ignored) {
            // Never trap the user: if Clean TV can't start, the stock home stays usable.
        }
    }
}
