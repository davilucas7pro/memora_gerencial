package com.memora.gerencial;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Reschedule the SLA alarm after device reboot */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            MainActivity.scheduleAlarm(context);
        }
    }
}
