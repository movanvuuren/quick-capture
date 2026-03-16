package com.mo.quickcapture;

import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;

import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

@CapacitorPlugin(name = "WidgetSync")
public class WidgetSyncPlugin extends Plugin {

    /**
     * Called from JavaScript after habits are loaded.
     * Stores the folder URI and a JSON array of habit descriptors in SharedPreferences
     * so that the home-screen widget can read them without launching the WebView.
     *
     * Expected call options:
     *   folderUri  – the SAF tree URI string (same one used by FolderPickerPlugin)
     *   habitsJson – JSON array: [{ file, name, icon, target }, ...]
     */
    @PluginMethod
    public void syncHabits(PluginCall call) {
        String folderUri = call.getString("folderUri");
        String habitsJson = call.getString("habitsJson");

        if (folderUri == null || habitsJson == null) {
            call.reject("Missing folderUri or habitsJson");
            return;
        }

        Context ctx = getContext();
        ctx.getSharedPreferences("quick_capture_prefs", Context.MODE_PRIVATE)
            .edit()
            .putString("folder_uri", folderUri)
            .putString("habits_json", habitsJson)
            .apply();

        // Refresh every habit widget that is currently pinned to the home screen
        AppWidgetManager manager = AppWidgetManager.getInstance(ctx);
        int[] widgetIds = manager.getAppWidgetIds(
            new ComponentName(ctx, HabitWidgetProvider.class)
        );
        for (int widgetId : widgetIds) {
            HabitWidgetProvider.updateWidget(ctx, manager, widgetId);
        }

        call.resolve();
    }
}
