package com.mo.quickcapture;

import android.os.Handler;
import android.os.Looper;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.mo.quickcapture.widget.AgendaTodayGlanceWidget;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;

@CapacitorPlugin(name = "WidgetSync")
public class WidgetSyncPlugin extends Plugin {
    private static final String TAG = "WidgetRefresh";
    private static final long[] FOLLOW_UP_REFRESH_DELAYS_MS = new long[] { 250L, 1000L, 2000L };

    private void refreshWidgetsWithFollowUp(Context ctx) {
        Log.d(TAG, "refreshWidgetsWithFollowUp: immediate refresh");
        WidgetRefreshScheduler.refreshAllWidgets(ctx);
        Handler handler = new Handler(Looper.getMainLooper());
        for (long delayMs : FOLLOW_UP_REFRESH_DELAYS_MS) {
            Log.d(TAG, "refreshWidgetsWithFollowUp: scheduled follow-up delayMs=" + delayMs);
            handler.postDelayed(
                () -> WidgetRefreshScheduler.refreshAllWidgets(ctx),
                delayMs
            );
        }
        WidgetRefreshScheduler.scheduleNextMidnightRefresh(ctx);
    }

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
        String widgetTheme = call.getString("theme");
        String widgetAccent = call.getString("accentColor");
        String widgetCornerStyle = normalizeCornerStyle(call.getString("cornerStyle"));

        if (folderUri == null || habitsJson == null) {
            call.reject("Missing folderUri or habitsJson");
            return;
        }

        Log.d(TAG, "syncHabits: theme=" + widgetTheme + " accent=" + widgetAccent + " corners=" + widgetCornerStyle + " folderUriSet=" + !folderUri.trim().isEmpty());

        Context ctx = getContext();
        SharedPreferences.Editor prefsEditor = ctx.getSharedPreferences("quick_capture_prefs", Context.MODE_PRIVATE)
            .edit()
            .putString("folder_uri", folderUri)
            .putString("habits_json", habitsJson)
            .putString("widget_corner_style", widgetCornerStyle);

        if (widgetTheme != null && !widgetTheme.trim().isEmpty()) {
            prefsEditor.putString("widget_theme", widgetTheme.trim());
        }

        if (widgetAccent != null && widgetAccent.trim().matches("^#[0-9a-fA-F]{6}$")) {
            prefsEditor.putString("widget_accent", widgetAccent.trim().toLowerCase());
        } else if (widgetAccent != null) {
            prefsEditor.remove("widget_accent");
        }

        if (!prefsEditor.commit()) {
            call.reject("Failed to persist widget sync preferences");
            return;
        }

        Log.d(TAG, "syncHabits: persisted prefs");

        Map<String, JSONObject> habitsByFile = new HashMap<>();
        try {
            JSONArray arr = new JSONArray(habitsJson);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject item = arr.optJSONObject(i);
                if (item == null) continue;
                String file = item.optString("file", "");
                if (!file.isEmpty()) habitsByFile.put(file, item);
            }
        } catch (Exception ignored) {}

        // Refresh every habit widget that is currently pinned to the home screen
        AppWidgetManager manager = AppWidgetManager.getInstance(ctx);
        int[] widgetIds = manager.getAppWidgetIds(
            new ComponentName(ctx, HabitWidgetProvider.class)
        );

        SharedPreferences widgetPrefs = ctx.getSharedPreferences("quick_capture_widget", Context.MODE_PRIVATE);
        SharedPreferences.Editor widgetEditor = widgetPrefs.edit();

        for (int widgetId : widgetIds) {
            String selectedFile = widgetPrefs.getString("widget_" + widgetId + "_file", null);
            if (selectedFile != null) {
                JSONObject latest = habitsByFile.get(selectedFile);
                if (latest == null && !selectedFile.contains("/")) {
                    // Migrate legacy root path habits to the new habits/<file>.md layout.
                    String migratedPath = "habits/" + selectedFile;
                    latest = habitsByFile.get(migratedPath);
                    if (latest != null) {
                        selectedFile = migratedPath;
                    }
                }
                if (latest != null) {
                    widgetEditor
                        .putString("widget_" + widgetId + "_file", selectedFile)
                        .putString("widget_" + widgetId + "_habit_id", latest.optString("id", ""))
                        .putString("widget_" + widgetId + "_name", latest.optString("name", "Habit"))
                        .putString("widget_" + widgetId + "_icon", latest.optString("icon", ""))
                        .putInt("widget_" + widgetId + "_target", Math.max(1, latest.optInt("target", 1)));
                }
            }
        }

        if (!widgetEditor.commit()) {
            call.reject("Failed to persist widget configuration");
            return;
        }

        Log.d(TAG, "syncHabits: persisted widget configuration");

        for (int widgetId : widgetIds) {
            HabitWidgetProvider.updateWidget(ctx, manager, widgetId);
        }

        // Also, refresh the Agenda Today widget via its helper method
        AgendaTodayGlanceWidget.update(ctx);
        refreshWidgetsWithFollowUp(ctx);

        call.resolve();
    }

    @PluginMethod
    public void syncAppearance(PluginCall call) {
        String widgetTheme = call.getString("theme");
        String widgetAccent = call.getString("accentColor");
        String widgetCornerStyle = normalizeCornerStyle(call.getString("cornerStyle"));
        Log.d(TAG, "syncAppearance: theme=" + widgetTheme + " accent=" + widgetAccent + " corners=" + widgetCornerStyle);

        Context ctx = getContext();
        SharedPreferences.Editor prefsEditor = ctx.getSharedPreferences("quick_capture_prefs", Context.MODE_PRIVATE)
            .edit()
            .putString("widget_corner_style", widgetCornerStyle);

        if (widgetTheme != null && !widgetTheme.trim().isEmpty()) {
            prefsEditor.putString("widget_theme", widgetTheme.trim());
        }

        if (widgetAccent != null && widgetAccent.trim().matches("^#[0-9a-fA-F]{6}$")) {
            prefsEditor.putString("widget_accent", widgetAccent.trim().toLowerCase());
        } else if (widgetAccent != null) {
            prefsEditor.remove("widget_accent");
        }

        if (!prefsEditor.commit()) {
            call.reject("Failed to persist widget appearance");
            return;
        }

        SharedPreferences prefs = ctx.getSharedPreferences("quick_capture_prefs", Context.MODE_PRIVATE);
        Log.d(
            TAG,
            "syncAppearance: stored theme=" + prefs.getString("widget_theme", null)
                + " accent=" + prefs.getString("widget_accent", null)
                + " corners=" + prefs.getString("widget_corner_style", null)
        );

        refreshWidgetsWithFollowUp(ctx);
        call.resolve();
    }

    @PluginMethod
    public void refreshWidgets(PluginCall call) {
        Context ctx = getContext();
        Log.d(TAG, "refreshWidgets: requested from JS");
        call.resolve();
        new Thread(() -> refreshWidgetsWithFollowUp(ctx)).start();
    }

    private String normalizeCornerStyle(String cornerStyle) {
        if (cornerStyle == null) {
            return "round";
        }

        String trimmed = cornerStyle.trim();
        if ("square".equals(trimmed) || "soft".equals(trimmed) || "round".equals(trimmed)) {
            return trimmed;
        }

        return "round";
    }
}
