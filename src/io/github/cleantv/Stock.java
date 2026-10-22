package io.github.cleantv;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ResolveInfo;

/** Finds and opens the TV's own home screen, which stays installed as a backup. */
final class Stock {
    static final String GOOGLE_TV = "com.google.android.apps.tv.launcherx";

    private Stock() {}

    static ComponentName find(Context c) {
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        ComponentName best = null;
        int bestPriority = Integer.MIN_VALUE;
        for (ResolveInfo ri : c.getPackageManager().queryIntentActivities(home, 0)) {
            ActivityInfo ai = ri.activityInfo;
            if (ai.packageName.equals(c.getPackageName())
                    || ai.packageName.contains("setupwraith")   // setup wizard
                    || ai.name.endsWith("FallbackHome")) continue;
            if (ai.packageName.equals(GOOGLE_TV)) return new ComponentName(ai.packageName, ai.name);
            if (ri.priority > bestPriority) {
                best = new ComponentName(ai.packageName, ai.name);
                bestPriority = ri.priority;
            }
        }
        return best;
    }

    static Intent intent(ComponentName stock) {
        return new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME)
                .setComponent(stock)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }
}
