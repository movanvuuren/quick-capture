package com.mo.quickcapture;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.mo.quickcapture.widget.AgendaTodayGlanceWidget;

import java.util.Calendar;

public final class WidgetRefreshScheduler {

    private static final String TAG = "WidgetRefresh";
    public static final String ACTION_REFRESH_WIDGETS = "com.mo.quickcapture.action.REFRESH_WIDGETS";
    private static final int REQUEST_CODE_REFRESH_WIDGETS = 4107;

    private WidgetRefreshScheduler() {
    }

    public static void scheduleNextMidnightRefresh(Context context) {
        AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarmManager == null) {
            Log.w(TAG, "scheduleNextMidnightRefresh: alarm manager unavailable");
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

        Log.d(TAG, "scheduleNextMidnightRefresh: nextRun=" + nextRun.getTimeInMillis());

        alarmManager.setAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            nextRun.getTimeInMillis(),
            buildRefreshPendingIntent(context)
        );
    }

    public static boolean isSystemRefreshAction(String action) {
        return Intent.ACTION_BOOT_COMPLETED.equals(action)
            || Intent.ACTION_DATE_CHANGED.equals(action)
            || Intent.ACTION_TIME_CHANGED.equals(action)
            || Intent.ACTION_TIMEZONE_CHANGED.equals(action)
            || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action);
    }

    public static void refreshAllWidgets(Context context) {
        Log.d(TAG, "refreshAllWidgets: begin");
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        int[] widgetIds = manager.getAppWidgetIds(new ComponentName(context, HabitWidgetProvider.class));
        Log.d(TAG, "refreshAllWidgets: habitWidgetCount=" + widgetIds.length);
        for (int widgetId : widgetIds) {
            HabitWidgetProvider.updateWidget(context, manager, widgetId);
        }

        Log.d(TAG, "refreshAllWidgets: updating agenda widget");
        AgendaTodayGlanceWidget.update(context);
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
