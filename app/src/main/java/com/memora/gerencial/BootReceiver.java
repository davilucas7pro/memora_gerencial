package com.memora.gerencial;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** After a reboot: restart the order monitor and its watchdog alarm */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            SlaMonitorService.start(context);
            MainActivity.scheduleAlarm(context);
        }
    }
}
