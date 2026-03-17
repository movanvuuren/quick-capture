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
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class HabitWidgetProvider extends AppWidgetProvider {

    static final String ACTION_TOGGLE = "com.mo.quickcapture.HABIT_WIDGET_TOGGLE";
    static final String EXTRA_WIDGET_ID = "widgetId";
    static final String LOG_START = "<!-- QUICK_CAPTURE_HABIT_LOG_START -->";
    static final String LOG_END = "<!-- QUICK_CAPTURE_HABIT_LOG_END -->";
    static final String HABIT_LOGS_DIR = "habit-logs";

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
            editor.remove("widget_" + widgetId + "_habit_id");
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
        String habitId = prefs.getString("widget_" + widgetId + "_habit_id", null);
        int targetCount = prefs.getInt("widget_" + widgetId + "_target", 1);

        if (folderUri == null || fileName == null) return;

        try {
            String today = getToday();
            String currentValue = getTodayValueForHabit(context, folderUri, fileName, habitId, today);

            // Cycle: blank -> done -> skip -> fail -> blank.
            // Partial numeric values (< target) advance to done first to match app semantics.
            String newValue;
            if (currentValue == null) {
                newValue = String.valueOf(targetCount);
            } else if (isNumeric(currentValue)) {
                int numeric = parseIntOrDefault(currentValue, targetCount);
                if (numeric < targetCount) {
                    newValue = String.valueOf(targetCount); // complete from partial
                } else {
                    newValue = "*"; // skip
                }
            } else if ("*".equals(currentValue)) {
                newValue = "!"; // fail
            } else {
                newValue = null; // clear
            }

            writeTodayValueForHabit(context, folderUri, fileName, habitId, today, newValue);
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
        String habitId = prefs.getString("widget_" + widgetId + "_habit_id", null);
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
                todayValue = getTodayValueForHabit(context, folderUri, fileName, habitId, getToday());
            } catch (Exception ignored) {}
        }

        // Colour-tint the circle based on state
        int stateColor;
        String stateLabel;
        if (todayValue != null && isNumeric(todayValue)) {
            int numeric = parseIntOrDefault(todayValue, 0);
            if (numeric >= targetCount) {
                stateColor = Color.parseColor("#388E3C"); // green  – done
                stateLabel = "✓ Done";
            } else {
                stateColor = Color.parseColor("#1976D2"); // blue   – partial
                stateLabel = numeric + "/" + targetCount;
            }
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

    static int parseIntOrDefault(String value, int fallback) {
        if (value == null) return fallback;
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    static String sanitizeRelativePath(String path) {
        if (path == null) return "";
        String normalized = path.replace('\\', '/').trim();
        while (normalized.startsWith("/")) normalized = normalized.substring(1);
        while (normalized.endsWith("/")) normalized = normalized.substring(0, normalized.length() - 1);
        return normalized;
    }

    static DocumentFile resolveDirectoryPath(DocumentFile baseDir, String relativePath, boolean createIfMissing) {
        String normalized = sanitizeRelativePath(relativePath);
        if (normalized.isEmpty()) return baseDir;

        String[] parts = normalized.split("/");
        DocumentFile current = baseDir;
        for (String rawPart : parts) {
            String part = rawPart == null ? "" : rawPart.trim();
            if (part.isEmpty()) continue;

            DocumentFile child = current.findFile(part);
            if (child == null) {
                if (!createIfMissing) return null;
                child = current.createDirectory(part);
            }

            if (child == null || !child.isDirectory()) return null;
            current = child;
        }

        return current;
    }

    static DocumentFile findFileByRelativePath(DocumentFile baseDir, String filePath) {
        String normalized = sanitizeRelativePath(filePath);
        if (normalized.isEmpty()) return null;

        int slash = normalized.lastIndexOf('/');
        String parentPath = slash >= 0 ? normalized.substring(0, slash) : "";
        String fileName = slash >= 0 ? normalized.substring(slash + 1) : normalized;
        if (fileName.isEmpty()) return null;

        DocumentFile parentDir = resolveDirectoryPath(baseDir, parentPath, false);
        if (parentDir == null) return null;

        DocumentFile file = parentDir.findFile(fileName);
        if (file == null || !file.isFile()) return null;
        return file;
    }

    static DocumentFile getOrCreateFileByRelativePath(DocumentFile baseDir, String filePath, String mime) {
        String normalized = sanitizeRelativePath(filePath);
        if (normalized.isEmpty()) return null;

        int slash = normalized.lastIndexOf('/');
        String parentPath = slash >= 0 ? normalized.substring(0, slash) : "";
        String fileName = slash >= 0 ? normalized.substring(slash + 1) : normalized;
        if (fileName.isEmpty()) return null;

        DocumentFile parentDir = resolveDirectoryPath(baseDir, parentPath, true);
        if (parentDir == null) return null;

        DocumentFile existing = parentDir.findFile(fileName);
        if (existing != null) return existing.isFile() ? existing : null;

        return parentDir.createFile(mime, fileName);
    }

    static String readFile(Context context, String folderUriStr, String fileName) throws IOException {
        Uri treeUri = Uri.parse(folderUriStr);
        DocumentFile tree = DocumentFile.fromTreeUri(context, treeUri);
        if (tree == null) return null;
        DocumentFile file = findFileByRelativePath(tree, fileName);
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
        String mime = (fileName.toLowerCase().endsWith(".md") || fileName.toLowerCase().endsWith(".markdown"))
            ? "text/markdown"
            : "text/plain";
        DocumentFile file = getOrCreateFileByRelativePath(tree, fileName, mime);
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

    static String getTodayValueFromMonthlyLog(String content, String today) {
        String month = today.substring(0, 7);
        String dayKey = "d" + today.substring(8, 10);
        boolean inFrontmatter = false;
        String monthValue = null;

        for (String line : content.split("\n", -1)) {
            String trimmed = line.trim();
            if (trimmed.equals("---")) {
                if (!inFrontmatter) {
                    inFrontmatter = true;
                    continue;
                }
                break;
            }
            if (!inFrontmatter || trimmed.isEmpty()) continue;

            int idx = line.indexOf(':');
            if (idx <= 0) continue;
            String key = line.substring(0, idx).trim();
            String value = line.substring(idx + 1).trim();
            if ("month".equals(key)) monthValue = value;
            if (dayKey.equals(key)) {
                if (monthValue == null || month.equals(monthValue)) return normalizeStoredValue(value);
            }
        }

        return null;
    }

    static String normalizeStoredValue(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        if (trimmed.isEmpty()) return null;
        if ("skip".equals(trimmed) || "*".equals(trimmed)) return "*";
        if ("fail".equals(trimmed) || "!".equals(trimmed)) return "!";
        if (isNumeric(trimmed)) return trimmed;
        return null;
    }

    static String habitLogStem(String habitId, String fileName) {
        String source = habitId;
        if (source == null || source.trim().isEmpty()) {
            source = fileName == null ? "" : fileName.replaceAll("(?i)\\.md$", "");
        }
        String base = source.toLowerCase(Locale.US);
        String normalized = base.replaceAll("[^a-z0-9_-]+", "-")
            .replaceAll("-+", "-")
            .replaceAll("^-|-$", "");
        return normalized.isEmpty() ? "habit" : normalized;
    }

    static String monthLogFileName(String habitFileName, String habitId, String today) {
        String month = today.substring(0, 7);
        return HABIT_LOGS_DIR + "/" + habitLogStem(habitId, habitFileName) + "-" + month + ".md";
    }

    static String getTodayValueForHabit(Context context, String folderUri, String habitFileName, String habitId, String today) throws IOException {
        String logPath = monthLogFileName(habitFileName, habitId, today);
        String monthly = readFile(context, folderUri, logPath);
        if (monthly != null) {
            String fromMonthly = getTodayValueFromMonthlyLog(monthly, today);
            if (fromMonthly != null) return fromMonthly;
        }

        // Backward compatibility: old builds used file-name-based stems.
        String legacyLogPath = monthLogFileName(habitFileName, null, today);
        if (!legacyLogPath.equals(logPath)) {
            String legacyMonthly = readFile(context, folderUri, legacyLogPath);
            if (legacyMonthly != null) {
                String fromLegacyMonthly = getTodayValueFromMonthlyLog(legacyMonthly, today);
                if (fromLegacyMonthly != null) return fromLegacyMonthly;
            }
        }

        String legacy = readFile(context, folderUri, habitFileName);
        if (legacy == null) return null;
        return getTodayValue(legacy, today);
    }

    static String upsertMonthLogContent(String existingContent, String habitFileName, String today, String newValue) {
        String month = today.substring(0, 7);
        String dayKey = "d" + today.substring(8, 10);

        Map<String, String> fields = new LinkedHashMap<>();
        if (existingContent != null) {
            boolean inFrontmatter = false;
            for (String line : existingContent.split("\n", -1)) {
                String trimmed = line.trim();
                if (trimmed.equals("---")) {
                    if (!inFrontmatter) {
                        inFrontmatter = true;
                        continue;
                    }
                    break;
                }
                if (!inFrontmatter || trimmed.isEmpty()) continue;

                int idx = line.indexOf(':');
                if (idx <= 0) continue;
                String key = line.substring(0, idx).trim();
                String value = line.substring(idx + 1).trim();
                fields.put(key, value);
            }
        }

        fields.put("type", "habit-log");
        fields.put("habitFile", quoteYamlString(habitFileName));
        fields.put("month", month);

        if (newValue == null) fields.remove(dayKey);
        else if ("*".equals(newValue)) fields.put(dayKey, "skip");
        else if ("!".equals(newValue)) fields.put(dayKey, "fail");
        else fields.put(dayKey, newValue);

        fields.put("updated", today);

        List<String> dayKeys = new ArrayList<>();
        for (String key : fields.keySet()) {
            if (key.matches("d\\d{2}")) dayKeys.add(key);
        }
        Collections.sort(dayKeys);

        List<String> lines = new ArrayList<>();
        lines.add("---");
        lines.add("type: habit-log");
        lines.add("habitFile: " + fields.get("habitFile"));
        lines.add("month: " + month);
        for (String key : dayKeys) {
            lines.add(key + ": " + fields.get(key));
        }
        lines.add("updated: " + today);
        lines.add("---");
        lines.add("");
        lines.add("Habit log data file.");

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) sb.append('\n');
            sb.append(lines.get(i));
        }
        sb.append('\n');
        return sb.toString();
    }

    static String quoteYamlString(String value) {
        if (value == null) return "\"\"";
        String escaped = value.replace("\\", "\\\\").replace("\"", "\\\"");
        return "\"" + escaped + "\"";
    }

    static void writeTodayValueForHabit(Context context, String folderUri, String habitFileName, String habitId, String today, String newValue) throws IOException {
        String logPath = monthLogFileName(habitFileName, habitId, today);
        String existing = readFile(context, folderUri, logPath);
        String updated = upsertMonthLogContent(existing, habitFileName, today, newValue);
        writeFile(context, folderUri, logPath, updated);
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
