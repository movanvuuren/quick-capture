package com.mo.quickcapture;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

import java.util.Calendar;

public final class WidgetRefreshScheduler {

    public static final String ACTION_REFRESH_WIDGETS = "com.mo.quickcapture.action.REFRESH_WIDGETS";
    private static final int REQUEST_CODE_REFRESH_WIDGETS = 4107;

    private WidgetRefreshScheduler() {
    }

    public static void scheduleNextMidnightRefresh(Context context) {
        AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarmManager == null) {
            return;
        }

        Calendar nextRun = Calendar.getInstance();
        nextRun.set(Calendar.HOUR_OF_DAY, 0);
        nextRun.set(Calendar.MINUTE, 1);
        nextRun.set(Calendar.SECOND, 0);
        nextRun.set(Calendar.MILLISECOND, 0);

        if (nextRun.getTimeInMillis() <= System.currentTimeMillis()) {
            nextRun.add(Calendar.DAY_OF_YEAR, 1);
        }

        alarmManager.setAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            nextRun.getTimeInMillis(),
            buildRefreshPendingIntent(context)
        );
    }

    public static PendingIntent buildRefreshPendingIntent(Context context) {
        Intent intent = new Intent(context, WidgetRefreshReceiver.class);
        intent.setAction(ACTION_REFRESH_WIDGETS);
        return PendingIntent.getBroadcast(
            context,
            REQUEST_CODE_REFRESH_WIDGETS,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
    }
}
