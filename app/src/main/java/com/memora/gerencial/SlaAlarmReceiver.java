package com.memora.gerencial;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/**
 * Watchdog: fires every ~1 min (rescheduled after each fire) and makes sure the
 * SlaMonitorService is running. The service does the Torque checks and notifications.
 */
public class SlaAlarmReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        createChannel(context);
        SlaMonitorService.start(context);
        MainActivity.scheduleAlarm(context);  // reschedule next check
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
