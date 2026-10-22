package io.github.cleantv;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;
import java.util.concurrent.ExecutorService;

/** Temperature and an icon next to the clock, from open-meteo.com (free, no account, only coordinates sent). */
final class Weather {
    private final SharedPreferences prefs;
    private final Handler ui;
    private final ExecutorService bg;
    private final ImageView weatherIcon;
    private final TextView weatherText;

    Weather(SharedPreferences prefs, Handler ui, ExecutorService bg, ImageView icon, TextView text) {
        this.prefs = prefs;
        this.ui = ui;
        this.bg = bg;
        this.weatherIcon = icon;
        this.weatherText = text;
    }


    /** ./tv.sh weather "City" sends coordinates here. */
    boolean setLocation(Intent i) {
        if (i == null || !i.hasExtra("lat") || !i.hasExtra("lon")) return false;
        prefs.edit()
                .putFloat("lat", i.getFloatExtra("lat", 0))
                .putFloat("lon", i.getFloatExtra("lon", 0))
                .remove("wx_at")
                .apply();
        refresh(true);
        return true;
    }

    /** Fetches at most every 30 minutes unless forced. */
    void refresh(boolean force) {
        if (!prefs.contains("lat")) return;
        if (!force && System.currentTimeMillis() - prefs.getLong("wx_at", 0) < 30 * 60_000) return;
        prefs.edit().putLong("wx_at", System.currentTimeMillis()).apply(); // at most one try per 30 min
        final float lat = prefs.getFloat("lat", 0), lon = prefs.getFloat("lon", 0);
        final String country = Locale.getDefault().getCountry();
        final boolean f = country.equals("US") || country.equals("LR") || country.equals("MM");
        bg.execute(() -> {
            HttpURLConnection c = null;
            try {
                URL u = new URL(String.format(Locale.US, "https://api.open-meteo.com/v1/forecast"
                        + "?latitude=%.3f&longitude=%.3f&current=temperature_2m,weather_code,is_day"
                        + "&temperature_unit=%s", lat, lon, f ? "fahrenheit" : "celsius"));
                c = (HttpURLConnection) u.openConnection();
                c.setConnectTimeout(8000);
                c.setReadTimeout(8000);
                InputStream in = c.getInputStream();
                ByteArrayOutputStream body = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                for (int n; (n = in.read(buf)) > 0; ) body.write(buf, 0, n);
                JSONObject cur = new JSONObject(body.toString("UTF-8")).getJSONObject("current");
                prefs.edit()
                        .putInt("wx_temp", (int) Math.round(cur.getDouble("temperature_2m")))
                        .putInt("wx_code", cur.getInt("weather_code"))
                        .putBoolean("wx_day", cur.optInt("is_day", 1) == 1)
                        .apply();
                ui.post(this::show);
            } catch (Exception ignored) {
                // offline: keep showing the last reading
            } finally {
                if (c != null) c.disconnect();
            }
        });
    }

    void show() {
        if (!prefs.contains("wx_temp")) {
            weatherIcon.setVisibility(View.GONE);
            weatherText.setVisibility(View.GONE);
            return;
        }
        int code = prefs.getInt("wx_code", 0);
        int icon;
        if (code <= 1) icon = prefs.getBoolean("wx_day", true) ? R.drawable.ic_w_sun : R.drawable.ic_w_moon;
        else if (code <= 3) icon = R.drawable.ic_w_cloud;
        else if (code <= 48) icon = R.drawable.ic_w_fog;
        else if (code <= 67 || (code >= 80 && code <= 82)) icon = R.drawable.ic_w_rain;
        else if (code <= 86) icon = R.drawable.ic_w_snow;
        else icon = R.drawable.ic_w_storm;
        weatherIcon.setImageResource(icon);
        weatherText.setText(prefs.getInt("wx_temp", 0) + "°");
        weatherIcon.setVisibility(View.VISIBLE);
        weatherText.setVisibility(View.VISIBLE);
    }
}
