package com.example.csvsmssender;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.telephony.SmsManager;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SmsService extends Service {
    public static final String ACTION_UPDATE = "com.example.csvsmssender.UPDATE";
    public static final String ACTION_STOP = "com.example.csvsmssender.STOP";
    public static final String EXTRA_SENT = "sent";
    public static final String EXTRA_FAILED = "failed";
    public static final String EXTRA_PROGRESS = "progress";
    public static final String EXTRA_TOTAL = "total";
    public static final String EXTRA_STATUS = "status";
    public static final String EXTRA_LOG = "log";
    public static final String EXTRA_FINISHED = "finished";

    private static final String CHANNEL_ID = "SmsServiceChannel";
    private static final int NOTIFICATION_ID = 1;

    public static StringBuilder logHistory = new StringBuilder();
    public static boolean isRunning = false;
    public static int currentSent = 0;
    public static int currentFailed = 0;
    public static int currentProgress = 0;
    public static int currentTotal = 0;
    public static String currentStatus = "";

    private ExecutorService executor = Executors.newSingleThreadExecutor();
    private boolean stopped = false;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopped = true;
            isRunning = false;
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }

        createNotificationChannel();
        Notification notification = createNotification("Preparing to send SMS...");
        startForeground(NOTIFICATION_ID, notification);

        if (intent != null && !isRunning) {
            ArrayList<String> numbers = intent.getStringArrayListExtra("numbers");
            ArrayList<String> messages = intent.getStringArrayListExtra("messages");
            ArrayList<String> fullRows = intent.getStringArrayListExtra("fullRows");
            int delay = intent.getIntExtra("delay", 15);

            if (numbers != null && messages != null && fullRows != null) {
                logHistory.setLength(0);
                isRunning = true;
                currentSent = 0;
                currentFailed = 0;
                currentProgress = 0;
                currentTotal = numbers.size();
                startSending(numbers, messages, fullRows, delay);
            }
        }

        return START_NOT_STICKY;
    }

    private void startSending(ArrayList<String> numbers, ArrayList<String> messages, ArrayList<String> fullRows, int delay) {
        executor.execute(() -> {
            int total = numbers.size();
            SmsManager sms;
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                sms = getSystemService(SmsManager.class);
            } else {
                //noinspection deprecation
                sms = SmsManager.getDefault();
            }

            for (int i = 0; i < total && !stopped; i++) {
                String number = numbers.get(i);
                String message = messages.get(i);
                String fullRow = fullRows.get(i);

                currentStatus = "Sending " + (i + 1) + " of " + total;
                updateNotification(currentStatus);

                try {
                    ArrayList<String> parts = sms.divideMessage(message);
                    if (parts.size() == 1) sms.sendTextMessage(number, null, message, null, null);
                    else sms.sendMultipartTextMessage(number, null, parts, null, null);
                    currentSent++;
                    String log = "✅ SENT: " + fullRow + "\n\n";
                    logHistory.append(log);
                    currentProgress = i + 1;
                    broadcastUpdate(currentSent, currentFailed, currentProgress, total, currentStatus, log, false);
                } catch (Exception e) {
                    currentFailed++;
                    String log = "❌ FAILED: " + fullRow + " - " + e.getMessage() + "\n\n";
                    logHistory.append(log);
                    currentProgress = i + 1;
                    broadcastUpdate(currentSent, currentFailed, currentProgress, total, currentStatus, log, false);
                }

                if (i < total - 1 && !stopped) {
                    try {
                        Thread.sleep(delay * 1000L);
                    } catch (InterruptedException ignored) {
                        break;
                    }
                }
            }

            currentStatus = stopped ? "Stopped" : "Finished";
            isRunning = false;
            broadcastUpdate(currentSent, currentFailed, total, total, currentStatus, "", true);
            stopForeground(true);
            stopSelf();
        });
    }

    private void broadcastUpdate(int sent, int failed, int progress, int total, String status, String log, boolean finished) {
        Intent intent = new Intent(ACTION_UPDATE);
        intent.putExtra(EXTRA_SENT, sent);
        intent.putExtra(EXTRA_FAILED, failed);
        intent.putExtra(EXTRA_PROGRESS, progress);
        intent.putExtra(EXTRA_TOTAL, total);
        intent.putExtra(EXTRA_STATUS, status);
        intent.putExtra(EXTRA_LOG, log);
        intent.putExtra(EXTRA_FINISHED, finished);
        sendBroadcast(intent);
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel serviceChannel = new NotificationChannel(
                    CHANNEL_ID,
                    "SMS Service Channel",
                    NotificationManager.IMPORTANCE_LOW
            );
            NotificationManager manager = getSystemService(NotificationManager.class);
            manager.createNotificationChannel(serviceChannel);
        }
    }

    private Notification createNotification(String contentText) {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this,
                0, notificationIntent, PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Sending SMS")
                .setContentText(contentText)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentIntent(pendingIntent)
                .build();
    }

    private void updateNotification(String contentText) {
        Notification notification = createNotification(contentText);
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        manager.notify(NOTIFICATION_ID, notification);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        stopped = true;
        executor.shutdownNow();
        super.onDestroy();
    }
}
