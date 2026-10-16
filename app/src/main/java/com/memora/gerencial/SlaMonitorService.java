package com.memora.gerencial;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.webkit.CookieManager;
import androidx.core.app.NotificationCompat;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Foreground service that checks the Torque orders every 30 s, with the app open or closed,
 * and notifies when an order sits too long in a warehouse column:
 *   Retira  (Urgente 1 / Urgente 2): alert at 5 min, SLA breach at 10 min
 *   Entrega (Prazo 2h / Entregar no dia): alert at 10 min, SLA breach at 15 min
 * (the SLA breaks 5 minutes after the first alert)
 * It is the single source of SLA notifications and of the records shown in the Alertas tab.
 * Reads with the Torque session cookie the user created on the Torque login page.
 */
public class SlaMonitorService extends Service {

    static final String FG_CHANNEL = "memora_ger_monitor";
    static final int FG_ID = 1;
    static final long INTERVAL_MS = 30_000L;

    static final String HOST = "https://appwrite.fmtorque.com.br";
    static final String API = HOST + "/v1/tablesdb/main-db/tables/";
    static final String PROJECT = "fm-torque";

    /** Columns where time counts (same as the app's SLA_ACTIVE) */
    static final String[] OPEN = {"ORDER_READY", "READY_TO_COLLECT", "COLLECTING", "COLLECTED", "NEEDS_CHANGE", "ORDER_CHANGE", "IN_EXPEDITION"};

    private HandlerThread thread;
    private Handler handler;
    private PowerManager.WakeLock wake;
    private final Map<String, String> branchNames = new HashMap<>();

    static void start(Context c) {
        Intent i = new Intent(c, SlaMonitorService.class);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) c.startForegroundService(i);
            else c.startService(i);
        } catch (Exception e) {
            // Android may refuse a start from the background; the watchdog alarm retries
            e.printStackTrace();
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createChannels(this);
        Notification n = foregroundNotification("Iniciando monitoramento…");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(FG_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(FG_ID, n);
        }
        thread = new HandlerThread("sla-monitor");
        thread.start();
        handler = new Handler(thread.getLooper());
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "memora:sla-monitor");
        wake.setReferenceCounted(false);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        handler.removeCallbacks(tick);
        handler.post(tick);
        return START_STICKY;
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            wake.acquire(25_000L);
            try {
                check();
            } catch (Exception e) {
                e.printStackTrace();
                updateForeground("Sem conexão com o Torque · " + hhmm(System.currentTimeMillis()));
                writeStatus(SlaMonitorService.this, "error", 0, e.getClass().getSimpleName() + ": " + e.getMessage());
            } finally {
                if (wake.isHeld()) wake.release();
            }
            handler.postDelayed(this, INTERVAL_MS);
        }
    };

    // ── the check ────────────────────────────────────────────────────────────

    private void check() throws Exception {
        SharedPreferences prefs = getSharedPreferences(MainActivity.PREFS_NAME, MODE_PRIVATE);
        String cookie = readCookie();
        if (cookie == null || cookie.isEmpty()) {
            sessionMissing(prefs);
            return;
        }

        JSONArray orders;
        try {
            orders = rows("sales_orders_whsl", list(
                q("equal", "status", OPEN),
                select("$id", "$createdAt", "displayCode", "title", "internalId", "status", "priority", "branchId"),
                limit(500)), cookie);
        } catch (SessionExpired e) {
            sessionMissing(prefs);
            return;
        }
        prefs.edit().putBoolean("sessionWarned", false).apply();
        if (branchNames.isEmpty()) loadBranches(cookie);

        // when each order entered its current column = latest history row into that status
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < orders.length(); i++) ids.add(orders.getJSONObject(i).getString("$id"));
        Map<String, Long> enteredAt = new HashMap<>();
        for (int i = 0; i < ids.size(); i += 100) {
            List<String> part = ids.subList(i, Math.min(ids.size(), i + 100));
            JSONArray h = rows("sales_orders_whsl_status_history", list(
                q("equal", "salesOrderWholesaleId", part.toArray(new String[0])),
                select("$id", "salesOrderWholesaleId", "toStatus", "arrivedAt"),
                limit(5000)), cookie);
            for (int j = 0; j < h.length(); j++) {
                JSONObject r = h.getJSONObject(j);
                String key = r.optString("salesOrderWholesaleId") + "|" + r.optString("toStatus");
                long at = parseTime(r.optString("arrivedAt"));
                Long prev = enteredAt.get(key);
                if (prev == null || at > prev) enteredAt.put(key, at);
            }
        }

        JSONObject fired = new JSONObject(prefs.getString("svcFired", "{}"));
        List<JSONObject> newRecords = new ArrayList<>();
        Set<String> live = new HashSet<>();
        long now = System.currentTimeMillis();
        int watched = 0;
        boolean changed = false;

        for (int i = 0; i < orders.length(); i++) {
            JSONObject o = orders.getJSONObject(i);
            String id = o.getString("$id");
            String status = o.optString("status");
            String priority = o.optString("priority");
            long[] sla = slaFor(priority);
            if (sla == null) continue;
            watched++;

            Long at = enteredAt.get(id + "|" + status);
            long changedAt = at != null && at > 0 ? at : parseTime(o.optString("$createdAt"));
            if (changedAt <= 0) continue;
            long elapsed = now - changedAt;
            int level = elapsed >= sla[1] ? 2 : elapsed >= sla[0] ? 1 : 0;

            String key = id + "|" + status + "|" + changedAt;
            live.add(key);
            int done = fired.optInt(key, 0);
            if (level > done) {
                String title = o.optString("displayCode", o.optString("title", id));
                notifyOrder(title, status, priority, elapsed, sla[1], level == 2, key.hashCode());
                newRecords.add(alertRecord(o, title, status, priority, changedAt, now, elapsed, level == 2));
                fired.put(key, level);
                changed = true;
            }
        }
        // forget orders that left the watched columns
        Iterator<String> it = fired.keys();
        List<String> stale = new ArrayList<>();
        while (it.hasNext()) { String k = it.next(); if (!live.contains(k)) stale.add(k); }
        for (String k : stale) { fired.remove(k); changed = true; }

        if (changed) prefs.edit().putString("svcFired", fired.toString()).apply();
        if (!newRecords.isEmpty()) {
            synchronized (LOCK) {   // the page may be moving a record to "Justificados" at the same time
                JSONArray records = new JSONArray(prefs.getString("alertRecords", "[]"));
                for (JSONObject r : newRecords) {
                    // one open alert per order: the newest replaces any older unjustified one
                    String reqId = r.optString("reqId");
                    JSONArray keep = new JSONArray();
                    for (int i = 0; i < records.length(); i++) {
                        JSONObject a = records.getJSONObject(i);
                        if (!reqId.equals(a.optString("reqId"))) keep.put(a);
                    }
                    records = prepend(keep, r, 200);
                }
                prefs.edit().putString("alertRecords", records.toString()).commit();
            }
        }
        updateForeground("Monitorando " + watched + " pedido" + (watched == 1 ? "" : "s")
            + " · verificado às " + hhmm(now));
        writeStatus(this, "ok", watched, "");
    }

    // ── shared state with the page (MainActivity bridge) ─────────────────────

    static final Object LOCK = new Object();

    /** Monitor health shown in the Alertas page: state ok / nosession / error, when, how many orders watched */
    static void writeStatus(Context c, String state, int watched, String error) {
        try {
            JSONObject s = new JSONObject();
            s.put("state", state);
            s.put("at", System.currentTimeMillis());
            s.put("watched", watched);
            s.put("error", error == null ? "" : error);
            c.getSharedPreferences(MainActivity.PREFS_NAME, MODE_PRIVATE).edit().putString("svcStatus", s.toString()).apply();
        } catch (Exception e) { e.printStackTrace(); }
    }

    /** Move an alert from "Registros de alerta" to "Justificados", keeping the user's note */
    static boolean resolveAlert(Context c, String alertId, String obs) {
        synchronized (LOCK) {
            try {
                SharedPreferences p = c.getSharedPreferences(MainActivity.PREFS_NAME, MODE_PRIVATE);
                JSONArray open = new JSONArray(p.getString("alertRecords", "[]"));
                JSONArray keep = new JSONArray();
                JSONObject found = null;
                for (int i = 0; i < open.length(); i++) {
                    JSONObject a = open.getJSONObject(i);
                    if (found == null && alertId.equals(a.optString("id"))) found = a; else keep.put(a);
                }
                if (found == null) return false;
                found.put("obs", obs);
                found.put("resolvedAt", System.currentTimeMillis());
                JSONArray done = prepend(new JSONArray(p.getString("alertJustified", "[]")), found, 1000);
                p.edit().putString("alertRecords", keep.toString()).putString("alertJustified", done.toString()).commit();
                return true;
            } catch (Exception e) { e.printStackTrace(); return false; }
        }
    }

    /** Drop an open alert without justifying it (e.g. a duplicate) */
    static boolean deleteAlert(Context c, String alertId) {
        synchronized (LOCK) {
            try {
                SharedPreferences p = c.getSharedPreferences(MainActivity.PREFS_NAME, MODE_PRIVATE);
                JSONArray open = new JSONArray(p.getString("alertRecords", "[]"));
                JSONArray keep = new JSONArray();
                boolean found = false;
                for (int i = 0; i < open.length(); i++) {
                    JSONObject a = open.getJSONObject(i);
                    if (!found && alertId.equals(a.optString("id"))) found = true; else keep.put(a);
                }
                if (!found) return false;
                p.edit().putString("alertRecords", keep.toString()).commit();
                return true;
            } catch (Exception e) { e.printStackTrace(); return false; }
        }
    }

    /** The WebView cookie store is read on the main thread (safe even before any WebView exists) */
    private String readCookie() {
        final String[] out = new String[1];
        final CountDownLatch done = new CountDownLatch(1);
        new Handler(Looper.getMainLooper()).post(() -> {
            try { out[0] = CookieManager.getInstance().getCookie(HOST); } catch (Exception e) { out[0] = null; }
            done.countDown();
        });
        try { done.await(5, TimeUnit.SECONDS); } catch (InterruptedException ignored) { }
        return out[0];
    }

    private void sessionMissing(SharedPreferences prefs) {
        updateForeground("Entre com o Torque no app para monitorar os pedidos");
        writeStatus(this, "nosession", 0, "");
        if (!prefs.getBoolean("sessionWarned", false)) {
            prefs.edit().putBoolean("sessionWarned", true).apply();
            post("Memora Gerencial sem acesso ao Torque",
                "Abra o app e toque em \"Entrar com o Torque\". Sem isso os pedidos parados não são avisados.", 777);
        }
    }

    // ── SLA rules and labels (same as the app) ───────────────────────────────

    /** {alertMs, slaMs} per Torque priority; null = no SLA (Entregar no outro dia) */
    static long[] slaFor(String priority) {
        switch (priority) {
            case "URGENCY_1":
            case "URGENCY_2":        return new long[]{5 * 60_000L, 10 * 60_000L};
            case "DEADLINE_2H":
            case "DELIVER_SAME_DAY": return new long[]{10 * 60_000L, 15 * 60_000L};
            default:                 return null;
        }
    }

    static String priorityLabel(String p) {
        switch (p) {
            case "URGENCY_1":        return "Urgente 1 (Esta na loja)";
            case "URGENCY_2":        return "Urgente 2 (Não esta na loja)";
            case "DEADLINE_2H":      return "Prazo 2h";
            case "DELIVER_SAME_DAY": return "Entregar no dia";
            default:                 return p;
        }
    }

    static String statusLabel(String s) {
        switch (s) {
            case "ORDER_READY":
            case "READY_TO_COLLECT": return "Pedido pronto";
            case "COLLECTING":       return "Em coleta";
            case "COLLECTED":        return "Coletado";
            case "NEEDS_CHANGE":     return "Validar alteração";
            case "ORDER_CHANGE":     return "Alterar pedido";
            case "IN_EXPEDITION":    return "Em expedição";
            default:                 return s;
        }
    }

    /** App keys used by the Alertas tab */
    static String appStatus(String s) {
        switch (s) {
            case "ORDER_READY":
            case "READY_TO_COLLECT": return "pedido_pronto";
            case "COLLECTING":       return "em_coleta";
            case "COLLECTED":        return "coletado";
            case "NEEDS_CHANGE":     return "validar_alteracao";
            case "ORDER_CHANGE":     return "alterar_pedido";
            case "IN_EXPEDITION":    return "em_expedicao";
            default:                 return s.toLowerCase(Locale.ROOT);
        }
    }

    static String appTipo(String p) {
        switch (p) {
            case "URGENCY_1":        return "urg1_retira";
            case "URGENCY_2":        return "nao_em_loja";
            case "DEADLINE_2H":      return "entrega_2h";
            case "DELIVER_SAME_DAY": return "entrega_dia";
            default:                 return "entrega_outro_dia";
        }
    }

    // ── notifications ────────────────────────────────────────────────────────

    private void notifyOrder(String title, String status, String priority, long elapsed, long slaMs, boolean breach, int id) {
        long min = elapsed / 60_000L;
        String head = (breach ? "🔴 SLA estourado — " : "⚠️ Pedido parado — ") + priorityLabel(priority);
        String body = title + " — " + statusLabel(status) + " há " + min + " min (SLA " + (slaMs / 60_000L) + " min)";
        post(head, body, id);
    }

    private void post(String title, String body, int id) {
        Intent open = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification n = new NotificationCompat.Builder(this, MainActivity.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(new NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build();
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        nm.notify(id, n);
    }

    private Notification foregroundNotification(String text) {
        Intent open = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 1, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this, FG_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setContentTitle("Memora Gerencial")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pi)
            .build();
    }

    private void updateForeground(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        nm.notify(FG_ID, foregroundNotification(text));
    }

    static void createChannels(Context c) {
        SlaAlarmReceiver.createChannel(c);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(FG_CHANNEL, "Monitoramento", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("Mantém o monitoramento dos pedidos do Torque ativo");
            c.getSystemService(NotificationManager.class).createNotificationChannel(ch);
        }
    }

    // ── alert records for the Alertas tab (same shape the page uses) ─────────

    private JSONObject alertRecord(JSONObject o, String title, String status, String priority,
                                   long changedAt, long now, long elapsed, boolean breach) throws Exception {
        String branch = o.optString("branchId");
        JSONObject a = new JSONObject();
        a.put("id", now + "_" + o.getString("$id"));
        a.put("reqId", o.getString("$id"));
        a.put("token", branch);
        a.put("reqTitle", title);
        a.put("contactName", branchNames.containsKey(branch) ? branchNames.get(branch) : branch);
        a.put("tipo", appTipo(priority));
        a.put("status", appStatus(status));
        a.put("changedAt", changedAt);
        a.put("firedAt", now);
        a.put("elapsedMs", elapsed);
        a.put("isBreach", breach);
        a.put("obs", "");
        return a;
    }

    private static JSONArray prepend(JSONArray list, JSONObject item, int max) throws Exception {
        JSONArray out = new JSONArray();
        out.put(item);
        for (int i = 0; i < list.length() && out.length() < max; i++) out.put(list.get(i));
        return out;
    }

    private void loadBranches(String cookie) {
        try {
            JSONArray b = rows("branches", list(select("$id", "name"), limit(100)), cookie);
            for (int i = 0; i < b.length(); i++) {
                JSONObject r = b.getJSONObject(i);
                branchNames.put(r.optString("$id"), r.optString("name"));
            }
        } catch (Exception e) { /* names are cosmetic */ }
    }

    // ── Torque REST ──────────────────────────────────────────────────────────

    static class SessionExpired extends IOException {}

    private JSONArray rows(String table, List<String> queries, String cookie) throws Exception {
        StringBuilder u = new StringBuilder(API).append(table).append("/rows?");
        for (String qq : queries) u.append("queries%5B%5D=").append(URLEncoder.encode(qq, "UTF-8")).append('&');
        HttpURLConnection c = (HttpURLConnection) new URL(u.toString()).openConnection();
        c.setConnectTimeout(15_000);
        c.setReadTimeout(20_000);
        c.setRequestProperty("X-Appwrite-Project", PROJECT);
        c.setRequestProperty("Cookie", cookie);
        try {
            int code = c.getResponseCode();
            if (code == 401) throw new SessionExpired();
            if (code >= 400) throw new IOException("HTTP " + code);
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"))) {
                String line;
                while ((line = r.readLine()) != null) sb.append(line);
            }
            JSONArray rows = new JSONObject(sb.toString()).optJSONArray("rows");
            return rows != null ? rows : new JSONArray();
        } finally {
            c.disconnect();
        }
    }

    private static List<String> list(String... items) {
        List<String> l = new ArrayList<>();
        for (String s : items) l.add(s);
        return l;
    }

    private static String q(String method, String attribute, String[] values) {
        try {
            JSONObject o = new JSONObject();
            o.put("method", method);
            o.put("attribute", attribute);
            JSONArray v = new JSONArray();
            for (String s : values) v.put(s);
            o.put("values", v);
            return o.toString();
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    private static String select(String... fields) {
        try {
            JSONObject o = new JSONObject();
            o.put("method", "select");
            JSONArray v = new JSONArray();
            for (String s : fields) v.put(s);
            o.put("values", v);
            return o.toString();
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    private static String limit(int n) {
        return "{\"method\":\"limit\",\"values\":[" + n + "]}";
    }

    /** Appwrite timestamps: 2026-10-16T15:07:12.308+00:00 (always UTC) */
    static long parseTime(String s) {
        if (s == null || s.length() < 19) return 0;
        try {
            SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.ROOT);
            f.setTimeZone(TimeZone.getTimeZone("UTC"));
            long t = f.parse(s.substring(0, 19)).getTime();
            if (s.length() >= 23 && s.charAt(19) == '.') t += Long.parseLong(s.substring(20, 23));
            return t;
        } catch (Exception e) { return 0; }
    }

    private static String hhmm(long t) {
        return new SimpleDateFormat("HH:mm", Locale.ROOT).format(new Date(t));
    }

    @Override
    public void onDestroy() {
        if (handler != null) handler.removeCallbacksAndMessages(null);
        if (thread != null) thread.quitSafely();
        if (wake != null && wake.isHeld()) wake.release();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
