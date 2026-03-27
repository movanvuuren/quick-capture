package com.mo.quickcapture;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

public class WidgetRefreshReceiver extends BroadcastReceiver {
    private static final String TAG = "WidgetRefresh";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        Log.d(TAG, "WidgetRefreshReceiver.onReceive action=" + action);
        if (WidgetRefreshScheduler.ACTION_REFRESH_WIDGETS.equals(action)
            || WidgetRefreshScheduler.isSystemRefreshAction(action)) {
            WidgetRefreshScheduler.refreshAllWidgets(context);
        }

        WidgetRefreshScheduler.scheduleNextMidnightRefresh(context);
    }
}
