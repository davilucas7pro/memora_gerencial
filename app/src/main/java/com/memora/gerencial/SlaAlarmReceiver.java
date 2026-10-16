package com.memora.gerencial;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import androidx.core.app.NotificationCompat;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Fires every ~1 min (rescheduled after each fire).
 * Checks pipelineRequests from SharedPrefs and fires SLA notifications
 * even when the app is in the background.
 *
 * SLA rules: Retirada → alert 5min / max 10min
 *            Entrega  → alert 10min / max 20min
 */
public class SlaAlarmReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        createChannel(context);
        checkSla(context);
        MainActivity.scheduleAlarm(context);  // reschedule next check
    }

    private void checkSla(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(
            MainActivity.PREFS_NAME, Context.MODE_PRIVATE);
        String reqsJson  = prefs.getString("pipelineRequests", "[]");
        String notifJson = prefs.getString("notifTs", "{}");
        long   nowMs     = System.currentTimeMillis();

        // SLA config: { alertMs, slaMs } per tipo
        // Retirada: 5min / 10min | Entrega: 10min / 20min
        String[]  tipoKeys  = {"urg1_retira","nao_em_loja","entrega_2h","entrega_dia"};
        String[]  tipoNames = {"Ret. Urg.1","Não em Loja","Entrega 2h","Entrega Dia"};
        long[][]  slaCfg    = {
            { 5*60_000L, 10*60_000L },
            { 5*60_000L, 10*60_000L },
            {10*60_000L, 20*60_000L },
            {10*60_000L, 20*60_000L }
        };
        Set<String> activeStatuses = new HashSet<>(Arrays.asList(
            "pedido_pronto","em_coleta","validar_alteracao","alterar_pedido","em_expedicao"
        ));

        try {
            JSONArray  reqs    = new JSONArray(reqsJson);
            JSONObject notifTs = new JSONObject(notifJson);
            NotificationManager nm = (NotificationManager)
                context.getSystemService(Context.NOTIFICATION_SERVICE);
            int notifId = (int)(nowMs / 1000);
            boolean changed = false;

            for (int i = 0; i < reqs.length(); i++) {
                JSONObject r   = reqs.getJSONObject(i);
                String reqId   = r.optString("id","");
                String status  = r.optString("status","");
                String tipo    = r.optString("tipo","");
                if (!activeStatuses.contains(status) || tipo.isEmpty()) continue;

                long alertMs = -1, slaMs = -1;
                String tipoName = tipo;
                for (int j = 0; j < tipoKeys.length; j++) {
                    if (tipoKeys[j].equals(tipo)) {
                        alertMs  = slaCfg[j][0];
                        slaMs    = slaCfg[j][1];
                        tipoName = tipoNames[j];
                        break;
                    }
                }
                if (alertMs < 0) continue;  // entrega_outro_dia or unknown

                long changedAt = r.optLong("statusChangedAt", r.optLong("createdAt", 0));
                long elapsed   = nowMs - changedAt;
                String slaKey  = "sla_" + reqId;
                long lastFired = notifTs.optLong(slaKey, 0);

                if (changedAt > 0 && elapsed >= alertMs && lastFired < changedAt) {
                    long elMin    = elapsed / 60_000L;
                    long slaMin   = slaMs   / 60_000L;
                    boolean breach = elapsed >= slaMs;
                    String title  = breach
                        ? "🔴 SLA Estourado — " + tipoName
                        : "⚠️ Pedido Parado — " + tipoName;
                    String body   = "\"" + r.optString("title","Pedido") + "\""
                        + " — " + statusLabel(status)
                        + " há " + elMin + "min (SLA: " + slaMin + "min)";

                    NotificationCompat.Builder nb =
                        new NotificationCompat.Builder(context, MainActivity.CHANNEL_ID)
                            .setSmallIcon(android.R.drawable.ic_dialog_alert)
                            .setContentTitle(title)
                            .setContentText(body)
                            .setStyle(new NotificationCompat.BigTextStyle().bigText(body))
                            .setPriority(NotificationCompat.PRIORITY_HIGH)
                            .setAutoCancel(true);
                    nm.notify(notifId + i, nb.build());
                    notifTs.put(slaKey, changedAt);
                    changed = true;
                }
            }

            if (changed) {
                prefs.edit().putString("notifTs", notifTs.toString()).apply();
            }
        } catch (Exception e) { e.printStackTrace(); }
    }

    private String statusLabel(String s) {
        switch (s) {
            case "pedido_pronto":     return "Pedido Pronto";
            case "em_coleta":         return "Em Coleta";
            case "validar_alteracao": return "Validar Alteração";
            case "alterar_pedido":    return "Alterar Pedido";
            case "em_expedicao":      return "Em Expedição";
            default:                  return s;
        }
    }

    static void createChannel(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                MainActivity.CHANNEL_ID, "Alertas SLA",
                NotificationManager.IMPORTANCE_HIGH);
            ch.setDescription("Notificações de SLA do Memora Gerencial");
            NotificationManager nm = context.getSystemService(NotificationManager.class);
            nm.createNotificationChannel(ch);
        }
    }
}
