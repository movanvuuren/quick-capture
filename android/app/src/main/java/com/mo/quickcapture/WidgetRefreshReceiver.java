package com.mo.quickcapture;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class WidgetRefreshReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        if (WidgetRefreshScheduler.ACTION_REFRESH_WIDGETS.equals(action)
            || WidgetRefreshScheduler.isSystemRefreshAction(action)) {
            WidgetRefreshScheduler.refreshAllWidgets(context);
        }

        WidgetRefreshScheduler.scheduleNextMidnightRefresh(context);
    }
}
