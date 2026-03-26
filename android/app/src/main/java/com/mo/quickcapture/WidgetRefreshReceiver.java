package com.mo.quickcapture;

import android.appwidget.AppWidgetManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;

import com.mo.quickcapture.widget.AgendaTodayGlanceWidget;

public class WidgetRefreshReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        refreshAllWidgets(context);
        WidgetRefreshScheduler.scheduleNextMidnightRefresh(context);
    }

    static void refreshAllWidgets(Context context) {
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        int[] widgetIds = manager.getAppWidgetIds(new ComponentName(context, HabitWidgetProvider.class));
        for (int widgetId : widgetIds) {
            HabitWidgetProvider.updateWidget(context, manager, widgetId);
        }

        AgendaTodayGlanceWidget.update(context);
    }
}
