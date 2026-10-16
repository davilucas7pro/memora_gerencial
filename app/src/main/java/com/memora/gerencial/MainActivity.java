package com.memora.gerencial;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

public class MainActivity extends AppCompatActivity {

    static final String CHANNEL_ID   = "memora_ger_notif";
    static final String PREFS_NAME   = "MemoraGerPrefs";
    private static final int REQ_TORQUE_LOGIN = 40;
    private int notifId = 2000;
    private WebView web;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        SlaAlarmReceiver.createChannel(this);
        requestNotificationPermission();
        scheduleAlarm(this);

        web = findViewById(R.id.webview);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccessFromFileURLs(true);
        s.setAllowUniversalAccessFromFileURLs(true);

        // Keep the Torque (Appwrite) login session: its cookie comes from another domain than the local page
        CookieManager cookies = CookieManager.getInstance();
        cookies.setAcceptCookie(true);
        cookies.setAcceptThirdPartyCookies(web, true);

        web.addJavascriptInterface(new Object() {

            /** Show a system notification from JS */
            @JavascriptInterface
            public void showNotification(String title, String body) {
                SlaAlarmReceiver.createChannel(MainActivity.this);
                androidx.core.app.NotificationCompat.Builder builder =
                    new androidx.core.app.NotificationCompat.Builder(MainActivity.this, CHANNEL_ID)
                        .setSmallIcon(android.R.drawable.ic_dialog_alert)
                        .setContentTitle(title)
                        .setContentText(body)
                        .setStyle(new androidx.core.app.NotificationCompat.BigTextStyle().bigText(body))
                        .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
                        .setAutoCancel(true);
                android.app.NotificationManager nm = (android.app.NotificationManager)
                    getSystemService(Context.NOTIFICATION_SERVICE);
                nm.notify(notifId++, builder.build());
            }

            /** Sync SLA-active orders so the background alarm can check them */
            @JavascriptInterface
            public void savePipelineRequests(String reqJson) {
                getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit().putString("pipelineRequests", reqJson).apply();
            }

            /** Shared notifTs store — read/write from JS and Java alarm */
            @JavascriptInterface
            public void saveNotifTs(String json) {
                getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit().putString("notifTs", json).apply();
            }

            @JavascriptInterface
            public String getNotifTs() {
                return getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .getString("notifTs", "{}");
            }

            /** Persist alert records (with obs) so they survive app restart */
            @JavascriptInterface
            public void saveAlertRecords(String json) {
                getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit().putString("alertRecords", json).apply();
            }

            @JavascriptInterface
            public String getAlertRecords() {
                return getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .getString("alertRecords", "[]");
            }

            /** Open the real Torque login page; its session is then reused by this WebView */
            @JavascriptInterface
            public void openTorqueLogin() {
                runOnUiThread(() -> startActivityForResult(
                    new Intent(MainActivity.this, TorqueLoginActivity.class), REQ_TORQUE_LOGIN));
            }

            /** Sign out: drop the Torque session cookie */
            @JavascriptInterface
            public void clearTorqueSession() {
                CookieManager cm = CookieManager.getInstance();
                cm.removeAllCookies(null);
                cm.flush();
            }

        }, "AndroidBridge");

        web.loadUrl("file:///android_asset/index.html");
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_TORQUE_LOGIN && web != null) {
            // Signed in (or closed): let the page check the session and start loading orders
            web.evaluateJavascript("tqInit()", null);
        }
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                    new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 1);
            }
        }
    }

    /** Schedule (or reschedule) the 1-minute SLA background alarm */
    static void scheduleAlarm(Context context) {
        try {
            AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
            Intent intent   = new Intent(context, SlaAlarmReceiver.class);
            PendingIntent pi = PendingIntent.getBroadcast(context, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            long triggerAt = System.currentTimeMillis() + 60_000L;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !am.canScheduleExactAlarms()) {
                am.setInexactRepeating(AlarmManager.RTC_WAKEUP, triggerAt, 2 * 60_000L, pi);
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi);
            } else {
                am.setExact(AlarmManager.RTC_WAKEUP, triggerAt, pi);
            }
        } catch (Exception e) { e.printStackTrace(); }
    }
}
