package com.vektor.signals;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;
import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** Міст між WebView і FeedService: налаштування фіду, свічки, події цін і пульс. */
@CapacitorPlugin(name = "VektorFeed")
public class VektorFeedPlugin extends Plugin {

    @Override
    public void load() {
        FeedService.listener = new FeedService.Listener() {
            @Override
            public void onPrice(String symbol, double price, long t) {
                JSObject d = new JSObject();
                d.put("symbol", symbol);
                d.put("price", price);
                d.put("t", t);
                notifyListeners("price", d);
            }

            @Override
            public void onStatus(String status, String message, List<String> unavailable) {
                JSObject d = new JSObject();
                d.put("status", status);
                d.put("message", message);
                d.put("unavailable", new JSArray(unavailable));
                notifyListeners("status", d);
            }

            @Override
            public void onTick(long t) {
                JSObject d = new JSObject();
                d.put("t", t);
                notifyListeners("tick", d);
            }

            @Override
            public void onOtc(JSONObject data) {
                try {
                    notifyListeners("otc", JSObject.fromJSONObject(data));
                } catch (Exception ignored) {}
            }
        };
    }

    @Override
    protected void handleOnDestroy() {
        FeedService.listener = null;
        super.handleOnDestroy();
    }

    @PluginMethod
    public void configure(PluginCall call) {
        List<String> symbols = new ArrayList<>();
        JSArray arr = call.getArray("symbols", new JSArray());
        for (int i = 0; i < arr.length(); i++) {
            String s = arr.optString(i, "");
            if (!s.isEmpty()) symbols.add(s);
        }
        FeedService.configure(getContext(), call.getString("keys", ""), symbols, Boolean.TRUE.equals(call.getBoolean("enabled", false)));
        call.resolve();
    }

    @PluginMethod
    public void otcConfigure(PluginCall call) {
        List<Integer> ids = new ArrayList<>();
        JSArray arr = call.getArray("ids", new JSArray());
        for (int i = 0; i < arr.length(); i++) {
            int id = arr.optInt(i, -1);
            if (id >= 0) ids.add(id);
        }
        FeedService.configureOtc(getContext(), ids, Boolean.TRUE.equals(call.getBoolean("enabled", false)));
        call.resolve();
    }

    @PluginMethod
    public void otcRefresh(PluginCall call) {
        FeedService.refreshOtc(call.getInt("id"));
        call.resolve();
    }

    @PluginMethod
    public void setText(PluginCall call) {
        FeedService.setText(getContext(), call.getString("text", "Стежу за ринком"));
        call.resolve();
    }

    @PluginMethod
    public void getCandles(PluginCall call) {
        JSObject ret = new JSObject();
        try {
            ret.put("candles", JSObject.fromJSONObject(FeedService.candlesJson(getContext())));
        } catch (Exception e) {
            ret.put("candles", new JSObject());
        }
        call.resolve(ret);
    }

    @PluginMethod
    public void batteryStatus(PluginCall call) {
        JSObject ret = new JSObject();
        boolean ignoring = true;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PowerManager pm = (PowerManager) getContext().getSystemService(Context.POWER_SERVICE);
            ignoring = pm.isIgnoringBatteryOptimizations(getContext().getPackageName());
        }
        ret.put("ignoring", ignoring);
        call.resolve(ret);
    }

    /** Системний запит «дозволити роботу без обмежень батареї». */
    @SuppressLint("BatteryLife")
    @PluginMethod
    public void requestBattery(PluginCall call) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                i.setData(Uri.parse("package:" + getContext().getPackageName()));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                getContext().startActivity(i);
            } catch (Exception e) {
                Intent i = new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                getContext().startActivity(i);
            }
        }
        call.resolve();
    }
}
