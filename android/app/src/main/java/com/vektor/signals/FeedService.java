package com.vektor.signals;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.PowerManager;
import android.text.TextUtils;
import androidx.core.app.NotificationCompat;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileInputStream;
import java.io.InterruptedIOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Фонова служба VEKTOR. Живе окремо від WebView: тримає WebSocket Twelve Data, збирає
 * 15-секундні свічки і зберігає їх у файл. Тому після повернення в застосунок свічки вже є
 * і розігрів не починається наново. Щосекунди будить рушій сигналів у WebView (подія tick),
 * бо таймери прихованої сторінки Chromium сильно пригальмовує. У вихідні ж раз на 2.5 с опитує
 * ціни OTC Binarium (ті самі запити, що робить їхній термінал) і віддає сирі відповіді рушію:
 * розбирає їх src/lib/binarium.js, як і в браузері.
 */
public class FeedService extends Service {

    static final String WS_URL = "wss://ws.twelvedata.com/v1/quotes/price";
    static final String CHANNEL = "vektor_service";
    static final int NOTIF_ID = 1;
    static final long CANDLE_MS = 15_000;
    static final long GAP_RESET_MS = 120_000; // довша перерва — історія вже не суцільна
    static final int MAX_CANDLES = 140;
    static final long HEARTBEAT_MS = 10_000;
    static final long RETRY_MIN_MS = 2_000;
    static final long RETRY_MAX_MS = 60_000;
    static final String FILE = "spot_candles.json";

    // Binarium: як src/lib/binarium.js (POLL_MS, CHUNK_MS, FULL_HISTORY_MS, FULL_AFTER_MS, OVERLAP_MS).
    static final String BIN_API = "https://api.binarium.com/api/v1/assets/";
    static final long OTC_POLL_MS = 2_500;
    static final long OTC_CHUNK_MS = 8 * 60_000;
    static final long OTC_HISTORY_MS = 128 * CANDLE_MS;
    static final long OTC_FULL_AFTER_MS = 30_000;
    static final long OTC_OVERLAP_MS = 30_000;
    static final long OTC_QUOTES_MS = 90_000;

    /** Отримувач подій у WebView (плагін). Null, коли WebView немає. */
    interface Listener {
        void onPrice(String symbol, double price, long t);
        void onStatus(String status, String message, List<String> unavailable);
        void onTick(long t);
        void onOtc(JSONObject data);
    }

    static volatile Listener listener;
    static volatile FeedService instance;

    // Налаштування від JS; служба бере їх при старті і при кожному configure().
    static final Object CONFIG_LOCK = new Object();
    static String cfgKeys = "";
    static List<String> cfgSymbols = new ArrayList<>();
    static boolean cfgEnabled = false;
    static String cfgText = "Стежу за ринком";
    static List<Integer> cfgOtcIds = new ArrayList<>();
    static boolean cfgOtcOn = false;

    private HandlerThread thread;
    private Handler handler;
    private PowerManager.WakeLock wakeLock;
    private OkHttpClient client;
    private WebSocket ws;
    private int generation = 0;

    private List<String> keys = new ArrayList<>();
    private int keyIndex = 0;
    private List<String> symbols = new ArrayList<>();
    private boolean enabled = false;
    private String status = "nokey";
    private String note = "";
    private List<String> unavailable = new ArrayList<>();
    private long retryMs = RETRY_MIN_MS;

    private OkHttpClient http;
    private ExecutorService otcPool;
    private final Map<Integer, Long> otcOkTo = new HashMap<>(); // id → до якого моменту вже є дані
    private final Set<Integer> otcBusy = new HashSet<>();

    private final Object candleLock = new Object();
    private final Map<String, ArrayList<double[]>> candles = new HashMap<>();

    // ---------- керування зі сторони плагіна ----------

    static void ensureStarted(Context ctx) {
        Intent i = new Intent(ctx, FeedService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i);
        else ctx.startService(i);
    }

    static void configure(Context ctx, String keysText, List<String> syms, boolean on) {
        synchronized (CONFIG_LOCK) {
            cfgKeys = keysText == null ? "" : keysText;
            cfgSymbols = new ArrayList<>(syms);
            cfgEnabled = on;
        }
        FeedService s = instance;
        if (s != null) s.handler.post(s::applyConfig);
        else ensureStarted(ctx);
    }

    static void setText(Context ctx, String text) {
        synchronized (CONFIG_LOCK) {
            cfgText = text;
        }
        FeedService s = instance;
        if (s != null) s.handler.post(s::refreshNotification);
        else ensureStarted(ctx);
    }

    static void configureOtc(Context ctx, List<Integer> ids, boolean on) {
        synchronized (CONFIG_LOCK) {
            cfgOtcIds = new ArrayList<>(ids);
            cfgOtcOn = on;
        }
        if (instance == null) ensureStarted(ctx);
    }

    /** Наступне опитування id (або всіх, якщо null) — з усією історією. */
    static void refreshOtc(Integer id) {
        FeedService s = instance;
        if (s == null) return;
        synchronized (s.otcOkTo) {
            if (id == null) s.otcOkTo.clear();
            else s.otcOkTo.remove(id);
        }
    }

    /** Свічки як JSON {symbol: [[t,o,h,l,c], ...]}; якщо служба не працює — з файлу. */
    static JSONObject candlesJson(Context ctx) {
        FeedService s = instance;
        if (s != null) return s.snapshotJson();
        try {
            return readFile(ctx);
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    // ---------- життєвий цикл ----------

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        startInForeground();
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "vektor:feed");
        wakeLock.setReferenceCounted(false);
        wakeLock.acquire();
        client = new OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS).retryOnConnectionFailure(true).build();
        http = client.newBuilder().pingInterval(0, TimeUnit.SECONDS).callTimeout(5, TimeUnit.SECONDS).build();
        otcPool = Executors.newFixedThreadPool(3);
        thread = new HandlerThread("vektor-feed");
        thread.start();
        handler = new Handler(thread.getLooper());
        loadCandles();
        instance = this;
        handler.post(this::applyConfig);
        handler.postDelayed(ticker, 1000);
        handler.postDelayed(heartbeat, HEARTBEAT_MS);
        handler.postDelayed(otcPoller, OTC_POLL_MS);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startInForeground();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        instance = null;
        handler.removeCallbacksAndMessages(null);
        closeWs();
        otcPool.shutdownNow();
        saveCandles();
        thread.quitSafely();
        if (wakeLock.isHeld()) wakeLock.release();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationChannel ch = new NotificationChannel(CHANNEL, "Робота у фоні", NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("Постійне сповіщення, поки бот стежить за ринком");
        ch.setShowBadge(false);
        getSystemService(NotificationManager.class).createNotificationChannel(ch);
    }

    private Notification buildNotification() {
        String text;
        synchronized (CONFIG_LOCK) {
            text = cfgText;
        }
        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_vektor)
            .setContentTitle("VEKTOR працює у фоні")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(pi)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build();
    }

    private void startInForeground() {
        Notification n = buildNotification();
        if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        else startForeground(NOTIF_ID, n);
    }

    private void refreshNotification() {
        getSystemService(NotificationManager.class).notify(NOTIF_ID, buildNotification());
    }

    // ---------- WebSocket Twelve Data ----------

    private void applyConfig() {
        String k;
        List<String> syms;
        boolean on;
        synchronized (CONFIG_LOCK) {
            k = cfgKeys;
            syms = new ArrayList<>(cfgSymbols);
            on = cfgEnabled;
        }
        List<String> newKeys = parseKeys(k);
        boolean changed = !newKeys.equals(keys) || !syms.equals(symbols) || on != enabled;
        if (!newKeys.equals(keys)) keyIndex = 0;
        keys = newKeys;
        symbols = syms;
        enabled = on;
        if (changed || ws == null) connect();
        else emitStatus();
    }

    private String key() {
        return keyIndex < keys.size() ? keys.get(keyIndex) : "";
    }

    private String keyLabel() {
        return keys.size() > 1 ? "ключ " + (keyIndex + 1) + " з " + keys.size() : "";
    }

    private String info(String... parts) {
        List<String> all = new ArrayList<>(Arrays.asList(parts));
        all.add(keyLabel());
        all.add(note);
        StringBuilder sb = new StringBuilder();
        for (String p : all) {
            if (p == null || p.isEmpty()) continue;
            if (sb.length() > 0) sb.append(" · ");
            sb.append(p);
        }
        return sb.toString();
    }

    private void setStatus(String st, String message) {
        status = st;
        lastMessage = message;
        emitStatus();
    }

    private String lastMessage = "";

    private void emitStatus() {
        Listener l = listener;
        if (l != null) l.onStatus(status, lastMessage, new ArrayList<>(unavailable));
    }

    private void closeWs() {
        generation++;
        if (ws != null) {
            ws.cancel();
            ws = null;
        }
    }

    private void connect() {
        closeWs();
        handler.removeCallbacks(reconnect);
        if (!enabled) {
            setStatus("idle", "");
            return;
        }
        if (key().isEmpty()) {
            setStatus("nokey", "");
            return;
        }
        if (symbols.isEmpty()) {
            setStatus("idle", "");
            return;
        }
        unavailable = new ArrayList<>();
        note = "";
        setStatus("connecting", info());
        final int gen = generation;
        String url;
        try {
            url = WS_URL + "?apikey=" + URLEncoder.encode(key(), "UTF-8");
        } catch (Exception e) {
            url = WS_URL + "?apikey=" + key();
        }
        ws = client.newWebSocket(new Request.Builder().url(url).build(), new WebSocketListener() {
            @Override
            public void onOpen(WebSocket socket, Response response) {
                handler.post(() -> {
                    if (gen != generation) return;
                    try {
                        JSONObject params = new JSONObject().put("symbols", TextUtils.join(",", symbols));
                        socket.send(new JSONObject().put("action", "subscribe").put("params", params).toString());
                    } catch (Exception ignored) {}
                });
            }

            @Override
            public void onMessage(WebSocket socket, String text) {
                handler.post(() -> {
                    if (gen == generation) handle(text);
                });
            }

            @Override
            public void onClosed(WebSocket socket, int code, String reason) {
                handler.post(() -> {
                    if (gen == generation) scheduleReconnect();
                });
            }

            @Override
            public void onFailure(WebSocket socket, Throwable t, Response response) {
                handler.post(() -> {
                    if (gen == generation) scheduleReconnect();
                });
            }
        });
    }

    private final Runnable reconnect = this::connect;

    private void scheduleReconnect() {
        ws = null;
        if (!enabled) return;
        if (!"error".equals(status)) setStatus("connecting", info("перепідключення…"));
        handler.removeCallbacks(reconnect);
        handler.postDelayed(reconnect, retryMs);
        retryMs = Math.min(retryMs * 2, RETRY_MAX_MS);
    }

    /** Поточний ключ відмовив — наступний; після останнього знову перший, але з паузою. */
    private void failKey(String message) {
        boolean last = keyIndex >= keys.size() - 1;
        String label = keyLabel();
        setStatus("error", label.isEmpty() ? message : label + " · " + message);
        closeWs();
        if (last) {
            keyIndex = 0;
            handler.removeCallbacks(reconnect);
            handler.postDelayed(reconnect, retryMs);
            retryMs = Math.min(retryMs * 2, RETRY_MAX_MS);
        } else {
            keyIndex++;
            connect();
        }
    }

    private static List<String> symbolsOf(JSONArray arr) {
        List<String> out = new ArrayList<>();
        if (arr == null) return out;
        for (int i = 0; i < arr.length(); i++) {
            Object o = arr.opt(i);
            if (o instanceof JSONObject) {
                String s = ((JSONObject) o).optString("symbol", "");
                if (!s.isEmpty()) out.add(s);
            } else if (o != null) out.add(o.toString());
        }
        return out;
    }

    private void handle(String raw) {
        JSONObject m;
        try {
            m = new JSONObject(raw);
        } catch (Exception e) {
            return;
        }
        String event = m.optString("event", "");
        if ("price".equals(event)) {
            double price = m.optDouble("price", Double.NaN);
            String sym = m.optString("symbol", "");
            if (Double.isNaN(price) || sym.isEmpty()) return;
            long t = System.currentTimeMillis();
            if (!"live".equals(status)) setStatus("live", info());
            retryMs = RETRY_MIN_MS;
            applyPrice(sym, price, t);
            Listener l = listener;
            if (l != null) l.onPrice(sym, price, t);
        } else if ("subscribe-status".equals(event)) {
            List<String> ok = symbolsOf(m.optJSONArray("success"));
            List<String> fails = symbolsOf(m.optJSONArray("fails"));
            if (ok.isEmpty() && (!"ok".equals(m.optString("status")) || !fails.isEmpty())) {
                failKey(fails.isEmpty() ? m.optString("message", "підписка не вдалась") : "тариф не дає: " + TextUtils.join(", ", fails));
                return;
            }
            unavailable = fails;
            note = fails.isEmpty() ? "" : "тариф не дає: " + TextUtils.join(", ", fails);
            if ("live".equals(status)) setStatus("live", info());
            else setStatus("connecting", info("чекаю першу ціну…"));
        } else if ("error".equals(m.optString("status")) || "error".equals(event)) {
            failKey(m.optString("message", "помилка Twelve Data"));
        }
    }

    private final Runnable heartbeat = new Runnable() {
        @Override
        public void run() {
            if (ws != null) ws.send("{\"action\":\"heartbeat\"}");
            handler.postDelayed(this, HEARTBEAT_MS);
        }
    };

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            Listener l = listener;
            if (l != null) l.onTick(System.currentTimeMillis());
            handler.postDelayed(this, 1000);
        }
    };

    // ---------- OTC Binarium ----------

    private final Runnable otcPoller = new Runnable() {
        @Override
        public void run() {
            List<Integer> ids;
            boolean on;
            synchronized (CONFIG_LOCK) {
                ids = new ArrayList<>(cfgOtcIds);
                on = cfgOtcOn;
            }
            // Без WebView нікому віддавати — не опитуємо; потім почнемо з повної історії.
            if (!on || listener == null) {
                synchronized (otcOkTo) {
                    otcOkTo.clear();
                }
            } else {
                for (Integer id : ids) {
                    synchronized (otcBusy) {
                        if (!otcBusy.add(id)) continue;
                    }
                    otcPool.execute(() -> {
                        try {
                            pollOtc(id);
                        } finally {
                            synchronized (otcBusy) {
                                otcBusy.remove(id);
                            }
                        }
                    });
                }
            }
            handler.postDelayed(this, OTC_POLL_MS);
        }
    };

    private static String iso(long ms) {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.format(new Date(ms));
    }

    private static String rangeUrl(int id, String kind, long from, long to, String detalization) {
        HttpUrl base = HttpUrl.parse(BIN_API + id + "/" + kind);
        return base.newBuilder()
            .addQueryParameter("from", iso(from))
            .addQueryParameter("to", iso(to))
            .addQueryParameter("detalization", detalization)
            .build()
            .toString();
    }

    /** Тіло відповіді; помилку кидає з коротким поясненням, як у binarium.js. */
    private String get(String url) throws Exception {
        Request req = new Request.Builder().url(url).header("Accept", "application/json").build();
        try (Response r = http.newCall(req).execute()) {
            if (!r.isSuccessful()) throw new Exception("HTTP " + r.code());
            return r.body() != null ? r.body().string() : "";
        } catch (InterruptedIOException e) {
            throw new Exception("сервер не відповідає");
        } catch (java.io.IOException e) {
            throw new Exception("немає з’єднання");
        }
    }

    /** Порожня відповідь свічок — далі в минуле історії немає. */
    private static boolean emptyList(String body) {
        try {
            Object v = new org.json.JSONTokener(body).nextValue();
            JSONArray arr = v instanceof JSONArray ? (JSONArray) v : v instanceof JSONObject ? ((JSONObject) v).optJSONArray("data") : null;
            return arr == null || arr.length() == 0;
        } catch (Exception e) {
            return true;
        }
    }

    private void pollOtc(int id) {
        long now = System.currentTimeMillis();
        Long okTo;
        synchronized (otcOkTo) {
            okTo = otcOkTo.get(id);
        }
        boolean full = okTo == null || now - okTo > OTC_FULL_AFTER_MS;
        long from = full ? now - OTC_HISTORY_MS : okTo - OTC_OVERLAP_MS;

        JSONArray candles = new JSONArray();
        String candleError = null;
        for (long b = now; b > from; b -= OTC_CHUNK_MS) {
            String body;
            try {
                body = get(rangeUrl(id, "candles", Math.max(from, b - OTC_CHUNK_MS), b, "15s"));
            } catch (Exception e) {
                if (b == now) candleError = e.getMessage();
                break;
            }
            candles.put(body);
            if (emptyList(body)) break;
        }
        String quotes = null;
        String quoteError = null;
        try {
            quotes = get(rangeUrl(id, "quotes", now - OTC_QUOTES_MS, now, "1s"));
        } catch (Exception e) {
            quoteError = e.getMessage();
        }
        if (candleError == null || quoteError == null) {
            synchronized (otcOkTo) {
                otcOkTo.put(id, now);
            }
        }
        try {
            JSONObject d = new JSONObject()
                .put("id", id)
                .put("full", full)
                .put("now", now)
                .put("candles", candles)
                .put("candleError", candleError == null ? JSONObject.NULL : candleError)
                .put("quotes", quotes == null ? JSONObject.NULL : quotes)
                .put("quoteError", quoteError == null ? JSONObject.NULL : quoteError);
            Listener l = listener;
            if (l != null) l.onOtc(d);
        } catch (Exception ignored) {}
    }

    static List<String> parseKeys(String text) {
        LinkedHashSet<String> set = new LinkedHashSet<>();
        for (String k : text.split("[\\s,;\"']+")) {
            String t = k.trim();
            if (!t.isEmpty()) set.add(t);
        }
        return new ArrayList<>(set);
    }

    // ---------- свічки ----------

    private void applyPrice(String sym, double p, long t) {
        boolean closed = false;
        synchronized (candleLock) {
            ArrayList<double[]> list = candles.get(sym);
            if (list == null) {
                list = new ArrayList<>();
                candles.put(sym, list);
            }
            double[] last = list.isEmpty() ? null : list.get(list.size() - 1);
            if (last != null && t - (long) last[0] > GAP_RESET_MS) {
                list.clear();
                last = null;
            }
            long b = (t / CANDLE_MS) * CANDLE_MS;
            if (last != null && (long) last[0] == b) {
                last[4] = p;
                if (p > last[2]) last[2] = p;
                if (p < last[3]) last[3] = p;
            } else {
                list.add(new double[] { b, last != null ? last[4] : p, p, p, p });
                while (list.size() > MAX_CANDLES) list.remove(0);
                closed = last != null;
            }
        }
        if (closed) saveCandles();
    }

    private JSONObject snapshotJson() {
        JSONObject out = new JSONObject();
        synchronized (candleLock) {
            try {
                for (Map.Entry<String, ArrayList<double[]>> e : candles.entrySet()) {
                    JSONArray arr = new JSONArray();
                    for (double[] c : e.getValue()) {
                        arr.put(new JSONArray().put((long) c[0]).put(c[1]).put(c[2]).put(c[3]).put(c[4]));
                    }
                    out.put(e.getKey(), arr);
                }
            } catch (Exception ignored) {}
        }
        return out;
    }

    private void saveCandles() {
        try (FileOutputStream os = new FileOutputStream(new File(getFilesDir(), FILE))) {
            os.write(snapshotJson().toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {}
    }

    private static JSONObject readFile(Context ctx) throws Exception {
        File f = new File(ctx.getFilesDir(), FILE);
        if (!f.exists()) return new JSONObject();
        byte[] data = new byte[(int) f.length()];
        try (FileInputStream is = new FileInputStream(f)) {
            int off = 0;
            while (off < data.length) {
                int n = is.read(data, off, data.length - off);
                if (n < 0) break;
                off += n;
            }
        }
        return new JSONObject(new String(data, StandardCharsets.UTF_8));
    }

    /** Після перезапуску служби — свічки з файлу, якщо перерва коротка. */
    private void loadCandles() {
        try {
            JSONObject json = readFile(this);
            long now = System.currentTimeMillis();
            synchronized (candleLock) {
                java.util.Iterator<String> it = json.keys();
                while (it.hasNext()) {
                    String sym = it.next();
                    JSONArray arr = json.getJSONArray(sym);
                    ArrayList<double[]> list = new ArrayList<>();
                    for (int i = 0; i < arr.length(); i++) {
                        JSONArray c = arr.getJSONArray(i);
                        list.add(new double[] { c.getLong(0), c.getDouble(1), c.getDouble(2), c.getDouble(3), c.getDouble(4) });
                    }
                    if (!list.isEmpty() && now - (long) list.get(list.size() - 1)[0] <= GAP_RESET_MS) candles.put(sym, list);
                }
            }
        } catch (Exception ignored) {}
    }
}
