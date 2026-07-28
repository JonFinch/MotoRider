package com.motorider.services;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;
import androidx.core.app.NotificationCompat;
import com.motorider.R;
import com.motorider.activities.MainActivity;

public class NavigationService extends Service {
    
    private static final String CHANNEL_ID = "MotoRiderNavigationChannel";
    private static final int NOTIFICATION_ID = 1;
    private static final String TAG = "NavigationService";
    
    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "NavigationService created");
        createNotificationChannel();
    }
    
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "NavigationService started");
        
        Notification notification = createNotification();
        startForeground(NOTIFICATION_ID, notification);
        
        return START_STICKY;
    }
    
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
    
    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "NavigationService destroyed");
        stopForeground(true);
    }
    
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "MotoRider Navigation",
                NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Navigation updates for MotoRider");
            
            NotificationManager notificationManager = getSystemService(NotificationManager.class);
            notificationManager.createNotificationChannel(channel);
        }
    }
    
    private Notification createNotification() {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            PendingIntent.FLAG_IMMUTABLE
        );
        
        return new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("MotoRider")
            .setContentText("Navigation active")
            .setSmallIcon(R.drawable.ic_motorcycle)
            .setContentIntent(pendingIntent)
            .build();
    }
}