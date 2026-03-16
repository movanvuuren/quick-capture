package com.mo.quickcapture;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.widget.RemoteViews;

import androidx.documentfile.provider.DocumentFile;

import java.io.BufferedReader;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class HabitWidgetProvider extends AppWidgetProvider {

    static final String ACTION_TOGGLE = "com.mo.quickcapture.HABIT_WIDGET_TOGGLE";
    static final String EXTRA_WIDGET_ID = "widgetId";
    static final String LOG_START = "<!-- QUICK_CAPTURE_HABIT_LOG_START -->";
    static final String LOG_END = "<!-- QUICK_CAPTURE_HABIT_LOG_END -->";

    @Override
    public void onUpdate(Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds) {
        for (int widgetId : appWidgetIds) {
            updateWidget(context, appWidgetManager, widgetId);
        }
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        super.onReceive(context, intent);
        if (ACTION_TOGGLE.equals(intent.getAction())) {
            int widgetId = intent.getIntExtra(EXTRA_WIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID);
            if (widgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
                toggleHabitState(context, widgetId);
            }
        }
    }

    @Override
    public void onDeleted(Context context, int[] appWidgetIds) {
        SharedPreferences.Editor editor = getWidgetPrefs(context).edit();
        for (int widgetId : appWidgetIds) {
            editor.remove("widget_" + widgetId + "_file");
            editor.remove("widget_" + widgetId + "_name");
            editor.remove("widget_" + widgetId + "_icon");
            editor.remove("widget_" + widgetId + "_target");
        }
        editor.apply();
    }

    private void toggleHabitState(Context context, int widgetId) {
        SharedPreferences prefs = getWidgetPrefs(context);
        String folderUri = getFolderUri(context);
        String fileName = prefs.getString("widget_" + widgetId + "_file", null);
        int targetCount = prefs.getInt("widget_" + widgetId + "_target", 1);

        if (folderUri == null || fileName == null) return;

        try {
            String content = readFile(context, folderUri, fileName);
            if (content == null) return;

            String today = getToday();
            String currentValue = getTodayValue(content, today);

            // Cycle: blank → done → skip → fail → blank
            String newValue;
            if (currentValue == null) {
                newValue = String.valueOf(targetCount);
            } else if (isNumeric(currentValue)) {
                newValue = "*"; // skip
            } else if ("*".equals(currentValue)) {
                newValue = "!"; // fail
            } else {
                newValue = null; // clear
            }

            String updated = updateTodayValue(content, today, newValue);
            writeFile(context, folderUri, fileName, updated);
        } catch (Exception e) {
            // silently fail; widget will show last known state
        }

        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        updateWidget(context, manager, widgetId);
    }

    static void updateWidget(Context context, AppWidgetManager manager, int widgetId) {
        SharedPreferences prefs = getWidgetPrefs(context);
        String folderUri = getFolderUri(context);
        String fileName = prefs.getString("widget_" + widgetId + "_file", null);
        String habitName = prefs.getString("widget_" + widgetId + "_name", "Habit");
        String habitIcon = prefs.getString("widget_" + widgetId + "_icon", "");
        int targetCount = prefs.getInt("widget_" + widgetId + "_target", 1);

        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.habit_widget);
        views.setTextViewText(R.id.widget_icon, habitIcon.isEmpty() ? "⭐" : habitIcon);
        views.setTextViewText(R.id.widget_name, habitName);

        // Read today's log value
        String todayValue = null;
        if (folderUri != null && fileName != null) {
            try {
                String content = readFile(context, folderUri, fileName);
                if (content != null) {
                    todayValue = getTodayValue(content, getToday());
                }
            } catch (Exception ignored) {}
        }

        // Colour-tint the circle based on state
        int stateColor;
        String stateLabel;
        if (todayValue != null && isNumeric(todayValue)) {
            stateColor = Color.parseColor("#388E3C"); // green  – done
            stateLabel = "✓ Done";
        } else if ("*".equals(todayValue)) {
            stateColor = Color.parseColor("#F57C00"); // amber  – skip
            stateLabel = "→ Skip";
        } else if ("!".equals(todayValue)) {
            stateColor = Color.parseColor("#C62828"); // red    – fail
            stateLabel = "✗ Fail";
        } else {
            stateColor = Color.parseColor("#546E7A"); // slate  – blank
            stateLabel = "—";
        }
        views.setInt(R.id.widget_icon_bg, "setColorFilter", stateColor);
        views.setTextViewText(R.id.widget_status, stateLabel);

        // Click → cycle state
        Intent toggleIntent = new Intent(context, HabitWidgetProvider.class);
        toggleIntent.setAction(ACTION_TOGGLE);
        toggleIntent.putExtra(EXTRA_WIDGET_ID, widgetId);
        PendingIntent pendingIntent = PendingIntent.getBroadcast(
            context, widgetId, toggleIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        views.setOnClickPendingIntent(R.id.widget_root, pendingIntent);

        manager.updateAppWidget(widgetId, views);
    }

    // ── Shared helpers ──────────────────────────────────────────────

    static SharedPreferences getWidgetPrefs(Context context) {
        return context.getSharedPreferences("quick_capture_widget", Context.MODE_PRIVATE);
    }

    static String getFolderUri(Context context) {
        return context.getSharedPreferences("quick_capture_prefs", Context.MODE_PRIVATE)
            .getString("folder_uri", null);
    }

    static String getToday() {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
    }

    static boolean isNumeric(String s) {
        if (s == null) return false;
        try { Integer.parseInt(s); return true; }
        catch (NumberFormatException e) { return false; }
    }

    static String readFile(Context context, String folderUriStr, String fileName) throws IOException {
        Uri treeUri = Uri.parse(folderUriStr);
        DocumentFile tree = DocumentFile.fromTreeUri(context, treeUri);
        if (tree == null) return null;
        DocumentFile file = tree.findFile(fileName);
        if (file == null || !file.isFile()) return null;

        try (InputStream input = context.getContentResolver().openInputStream(file.getUri())) {
            if (input == null) return null;
            BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            String line;
            boolean first = true;
            while ((line = reader.readLine()) != null) {
                if (!first) sb.append('\n');
                sb.append(line);
                first = false;
            }
            return sb.toString();
        }
    }

    static void writeFile(Context context, String folderUriStr, String fileName, String content) throws IOException {
        Uri treeUri = Uri.parse(folderUriStr);
        DocumentFile tree = DocumentFile.fromTreeUri(context, treeUri);
        if (tree == null) return;
        DocumentFile file = tree.findFile(fileName);
        if (file == null || !file.isFile()) return;

        // "wt" = write + truncate (overwrites fully)
        ParcelFileDescriptor pfd = context.getContentResolver().openFileDescriptor(file.getUri(), "wt");
        if (pfd == null) throw new IOException("Could not open file descriptor");
        try (FileOutputStream fos = new FileOutputStream(pfd.getFileDescriptor())) {
            fos.write(content.getBytes(StandardCharsets.UTF_8));
        }
        pfd.close();
    }

    /** Returns the raw value string for today ("1", "*", "!", or null if absent). */
    static String getTodayValue(String content, String today) {
        String prefix = "- " + today + ": ";
        boolean inLog = false;
        for (String line : content.split("\n", -1)) {
            if (line.equals(LOG_START)) { inLog = true; continue; }
            if (line.equals(LOG_END)) break;
            if (inLog && line.startsWith(prefix)) {
                return line.substring(prefix.length()).trim();
            }
        }
        return null;
    }

    /**
     * Returns updated file content with today's log entry set to newValue.
     * Passing null removes today's entry.
     */
    static String updateTodayValue(String content, String today, String newValue) {
        String prefix = "- " + today + ": ";
        String[] lines = content.split("\n", -1);
        List<String> result = new ArrayList<>();
        boolean inLog = false;
        boolean foundLogBlock = false;
        boolean foundToday = false;
        boolean addedNewValue = false;

        for (String line : lines) {
            if (line.equals(LOG_START)) {
                inLog = true;
                foundLogBlock = true;
                result.add(line);
                continue;
            }
            if (line.equals(LOG_END)) {
                // Add new entry just before the closing marker if we haven't already
                if (!foundToday && newValue != null && !addedNewValue) {
                    result.add(prefix + newValue);
                    addedNewValue = true;
                }
                inLog = false;
                result.add(line);
                continue;
            }
            if (inLog && line.startsWith(prefix)) {
                foundToday = true;
                if (newValue != null && !addedNewValue) {
                    result.add(prefix + newValue); // replace
                    addedNewValue = true;
                }
                // else: skip (effectively removes today's entry)
                continue;
            }
            result.add(line);
        }

        // No log block in file at all – append one
        if (!foundLogBlock && newValue != null) {
            result.add("");
            result.add(LOG_START);
            result.add(prefix + newValue);
            result.add(LOG_END);
        }

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < result.size(); i++) {
            if (i > 0) sb.append('\n');
            sb.append(result.get(i));
        }
        return sb.toString();
    }
}
