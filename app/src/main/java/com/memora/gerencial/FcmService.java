package com.memora.gerencial;

import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;
import android.app.NotificationManager;
import android.content.Context;
import androidx.core.app.NotificationCompat;

public class FcmService extends FirebaseMessagingService {
    @Override
    public void onMessageReceived(RemoteMessage msg) {
        if (msg.getNotification() == null) return;
        SlaAlarmReceiver.createChannel(this);
        String title = msg.getNotification().getTitle();
        String body  = msg.getNotification().getBody();
        NotificationCompat.Builder nb =
            new NotificationCompat.Builder(this, MainActivity.CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle(title)
                .setContentText(body)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true);
        NotificationManager nm = (NotificationManager)
            getSystemService(Context.NOTIFICATION_SERVICE);
        nm.notify((int)(System.currentTimeMillis()/1000), nb.build());
    }
}
