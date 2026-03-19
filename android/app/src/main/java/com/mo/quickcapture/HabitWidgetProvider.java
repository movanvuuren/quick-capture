package com.mo.quickcapture;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.View;
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
    static final String HABIT_LOGS_DIR = "habit-logs";
    static final String STATE_SKIP = "*";
    static final String STATE_FAIL = "!";

    enum WidgetState {
        BLANK,
        PARTIAL,
        SUCCESS,
        SKIPPED,
        FAIL
    }

    static class WidgetPalette {
        int titleColor;
        int subtextColor;
        int heroColor;
        int ringNeutralColor;
        int ringFaintColor;
        int ringCenterColor;
        int failColor;
        int backgroundRes;
    }

    @Override
    public void onUpdate(Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds) {
        for (int widgetId : appWidgetIds) {
            updateWidget(context, appWidgetManager, widgetId);
        }
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        super.onReceive(context, intent);
        String action = intent.getAction();
        if (ACTION_TOGGLE.equals(action)) {
            int widgetId = intent.getIntExtra(EXTRA_WIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID);
            if (widgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
                toggleHabitState(context, widgetId);
            }
            return;
        }

        if (Intent.ACTION_DATE_CHANGED.equals(action)
            || Intent.ACTION_TIME_CHANGED.equals(action)
            || Intent.ACTION_TIMEZONE_CHANGED.equals(action)
            || Intent.ACTION_BOOT_COMPLETED.equals(action)) {
            AppWidgetManager manager = AppWidgetManager.getInstance(context);
            int[] widgetIds = manager.getAppWidgetIds(
                new android.content.ComponentName(context, HabitWidgetProvider.class)
            );
            onUpdate(context, manager, widgetIds);
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
        int targetCount = Math.max(1, prefs.getInt("widget_" + widgetId + "_target", 1));
        boolean advancedCycle = prefs.getBoolean("widget_cycle_all_states", false);

        if (folderUri == null || fileName == null) return;

        try {
            String today = getToday();
            String currentValue = getTodayValueForHabit(context, folderUri, fileName, habitId, today);

            if (isNumeric(currentValue)) {
                int existing = parseIntOrDefault(currentValue, 0);
                if (existing > 0 && existing < targetCount) {
                    prefs.edit().putInt("widget_" + widgetId + "_last_partial", existing).apply();
                }
            }

            String newValue;
            if (advancedCycle) {
                newValue = getNextValueForAdvancedCycle(prefs, widgetId, currentValue, targetCount);
            } else {
                newValue = getNextValueForDefaultCycle(prefs, widgetId, currentValue, targetCount);
            }

            if (isNumeric(newValue)) {
                int nextNumeric = parseIntOrDefault(newValue, 0);
                if (nextNumeric > 0 && nextNumeric < targetCount) {
                    prefs.edit().putInt("widget_" + widgetId + "_last_partial", nextNumeric).apply();
                }
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
        int targetCount = Math.max(1, prefs.getInt("widget_" + widgetId + "_target", 1));
        String widgetTheme = getWidgetTheme(context);
        String accentColor = getWidgetAccentColor(context);
        WidgetPalette palette = buildPalette(context, widgetTheme, accentColor);

        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.habit_widget);
        views.setInt(R.id.widget_root, "setBackgroundResource", palette.backgroundRes);
        views.setTextViewText(R.id.widget_icon, habitIcon.isEmpty() ? "⭐" : habitIcon);

        // Read today's log value
        String todayValue = null;
        if (folderUri != null && fileName != null) {
            try {
                todayValue = getTodayValueForHabit(context, folderUri, fileName, habitId, getToday());
            } catch (Exception ignored) {}
        }

        WidgetState state = resolveWidgetState(todayValue, targetCount);
        int numericValue = isNumeric(todayValue) ? parseIntOrDefault(todayValue, 0) : 0;

        Bitmap ringBitmap = renderStatusRing(context, palette, state, numericValue, targetCount);
        views.setImageViewBitmap(R.id.widget_status_ring, ringBitmap);

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

    static String getNextValueForDefaultCycle(SharedPreferences prefs, int widgetId, String currentValue, int targetCount) {
        WidgetState state = resolveWidgetState(currentValue, targetCount);
        boolean isBooleanHabit = targetCount <= 1;

        if (isBooleanHabit) {
            if (state == WidgetState.SUCCESS) return null;
            return String.valueOf(targetCount);
        }

        if (state == WidgetState.BLANK) {
            int fallback = Math.max(1, targetCount - 1);
            int lastPartial = prefs.getInt("widget_" + widgetId + "_last_partial", fallback);
            int safePartial = clamp(lastPartial, 1, Math.max(1, targetCount - 1));
            return String.valueOf(safePartial);
        }

        if (state == WidgetState.PARTIAL) return String.valueOf(targetCount);
        if (state == WidgetState.SUCCESS) return null;
        return null;
    }

    static String getNextValueForAdvancedCycle(SharedPreferences prefs, int widgetId, String currentValue, int targetCount) {
        WidgetState state = resolveWidgetState(currentValue, targetCount);
        boolean hasPartialState = targetCount > 1;

        if (state == WidgetState.BLANK) {
            if (!hasPartialState) return String.valueOf(targetCount);
            int fallback = Math.max(1, targetCount - 1);
            int lastPartial = prefs.getInt("widget_" + widgetId + "_last_partial", fallback);
            int safePartial = clamp(lastPartial, 1, Math.max(1, targetCount - 1));
            return String.valueOf(safePartial);
        }

        if (state == WidgetState.PARTIAL) return String.valueOf(targetCount);
        if (state == WidgetState.SUCCESS) return STATE_SKIP;
        if (state == WidgetState.SKIPPED) return STATE_FAIL;
        return null;
    }

    static WidgetState resolveWidgetState(String todayValue, int targetCount) {
        if (todayValue == null) return WidgetState.BLANK;
        if (STATE_SKIP.equals(todayValue)) return WidgetState.SKIPPED;
        if (STATE_FAIL.equals(todayValue)) return WidgetState.FAIL;
        if (!isNumeric(todayValue)) return WidgetState.BLANK;

        int numeric = parseIntOrDefault(todayValue, 0);
        if (numeric <= 0) return WidgetState.BLANK;
        if (numeric >= targetCount) return WidgetState.SUCCESS;
        return WidgetState.PARTIAL;
    }

    static String buildSubtext(WidgetState state, int numericValue, int targetCount) {
        if (state == WidgetState.PARTIAL) return numericValue + "/" + targetCount;
        if (state == WidgetState.SKIPPED) return "Skipped";
        if (state == WidgetState.FAIL) return "Missed";
        return "";
    }

    static Bitmap renderStatusRing(Context context, WidgetPalette palette, WidgetState state, int numericValue, int targetCount) {
        float sizeDp = 34f;
        int sizePx = Math.max(1, dpToPx(context, sizeDp));

        Bitmap bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);

        float stroke = dpToPx(context, 2.4f);
        float inset = stroke / 2f + 1f;
        RectF arcBounds = new RectF(inset, inset, sizePx - inset, sizePx - inset);

        Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setStrokeWidth(stroke);
        ringPaint.setStrokeCap(Paint.Cap.ROUND);

        Paint centerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        centerPaint.setTextAlign(Paint.Align.CENTER);
        centerPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        centerPaint.setColor(palette.ringCenterColor);

        if (state == WidgetState.BLANK) {
            ringPaint.setColor(palette.ringFaintColor);
            canvas.drawArc(arcBounds, -90f, 360f, false, ringPaint);

            Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            dotPaint.setColor(adjustAlpha(palette.ringCenterColor, 0.45f));
            float dotRadius = dpToPx(context, 1.8f);
            canvas.drawCircle(sizePx / 2f, sizePx / 2f, dotRadius, dotPaint);
            return bitmap;
        }

        if (state == WidgetState.PARTIAL) {
            ringPaint.setColor(palette.ringNeutralColor);
            canvas.drawArc(arcBounds, -90f, 360f, false, ringPaint);

            float progress = targetCount <= 0 ? 0f : (float) numericValue / (float) targetCount;
            progress = Math.max(0f, Math.min(1f, progress));
            ringPaint.setColor(palette.heroColor);
            canvas.drawArc(arcBounds, -90f, progress * 360f, false, ringPaint);

            String value = numericValue + "/" + targetCount;
            centerPaint.setTextSize(spToPx(context, 8f));
            Paint.FontMetrics fm = centerPaint.getFontMetrics();
            float y = (sizePx / 2f) - ((fm.ascent + fm.descent) / 2f);
            canvas.drawText(value, sizePx / 2f, y, centerPaint);
            return bitmap;
        }

        if (state == WidgetState.SUCCESS) {
            ringPaint.setColor(palette.heroColor);
            canvas.drawArc(arcBounds, -90f, 360f, false, ringPaint);

            centerPaint.setTextSize(spToPx(context, 12f));
            Paint.FontMetrics fm = centerPaint.getFontMetrics();
            float y = (sizePx / 2f) - ((fm.ascent + fm.descent) / 2f);
            canvas.drawText("✓", sizePx / 2f, y, centerPaint);
            return bitmap;
        }

        if (state == WidgetState.SKIPPED) {
            ringPaint.setColor(palette.ringNeutralColor);
            ringPaint.setPathEffect(new DashPathEffect(new float[] { dpToPx(context, 4f), dpToPx(context, 3f) }, 0f));
            canvas.drawArc(arcBounds, -90f, 360f, false, ringPaint);

            centerPaint.setTextSize(spToPx(context, 11f));
            Paint.FontMetrics fm = centerPaint.getFontMetrics();
            float y = (sizePx / 2f) - ((fm.ascent + fm.descent) / 2f);
            canvas.drawText("»", sizePx / 2f, y, centerPaint);
            return bitmap;
        }

        ringPaint.setColor(palette.ringNeutralColor);
        canvas.drawArc(arcBounds, -90f, 360f, false, ringPaint);

        centerPaint.setColor(palette.failColor);
        centerPaint.setTextSize(spToPx(context, 12f));
        Paint.FontMetrics fm = centerPaint.getFontMetrics();
        float y = (sizePx / 2f) - ((fm.ascent + fm.descent) / 2f);
        canvas.drawText("✕", sizePx / 2f, y, centerPaint);
        return bitmap;
    }

    static WidgetPalette buildPalette(Context context, String widgetTheme, String accentColor) {
        WidgetPalette palette = new WidgetPalette();

        boolean isDarkSystem = (context.getResources().getConfiguration().uiMode
            & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;

        String resolvedTheme = widgetTheme;
        if (resolvedTheme == null || resolvedTheme.trim().isEmpty()) {
            resolvedTheme = isDarkSystem ? "dark" : "light";
        }

        int hero = parseColorOrFallback(accentColor, "light".equals(resolvedTheme) ? "#2563ff" : "#ce43b7");
        if ("dim".equals(resolvedTheme) && (accentColor == null || accentColor.trim().isEmpty())) {
            hero = Color.parseColor("#ff53b3");
        }

        palette.heroColor = hero;

        if ("dark".equals(resolvedTheme)) {
            palette.titleColor = Color.parseColor("#F3F4F6");
            palette.subtextColor = Color.parseColor("#B8BBC2");
            palette.ringNeutralColor = Color.parseColor("#5D6169");
            palette.ringFaintColor = Color.parseColor("#4A4D54");
            palette.ringCenterColor = Color.parseColor("#E7E8EC");
            palette.failColor = Color.parseColor("#EF4444");
            palette.backgroundRes = R.drawable.widget_pill_dark;
        } else if ("dim".equals(resolvedTheme)) {
            palette.titleColor = Color.parseColor("#E8EDF7");
            palette.subtextColor = Color.parseColor("#A7B2C4");
            palette.ringNeutralColor = Color.parseColor("#6E7280");
            palette.ringFaintColor = Color.parseColor("#5D6170");
            palette.ringCenterColor = Color.parseColor("#F3F4F8");
            palette.failColor = Color.parseColor("#F87171");
            palette.backgroundRes = R.drawable.widget_pill_dim;
        } else {
            palette.titleColor = Color.parseColor("#111827");
            palette.subtextColor = Color.parseColor("#6B7280");
            palette.ringNeutralColor = Color.parseColor("#A5ACB8");
            palette.ringFaintColor = Color.parseColor("#C3C8D2");
            palette.ringCenterColor = Color.parseColor("#1F2937");
            palette.failColor = Color.parseColor("#DC2626");
            palette.backgroundRes = R.drawable.widget_pill_light;
        }

        return palette;
    }

    static String getWidgetTheme(Context context) {
        return context.getSharedPreferences("quick_capture_prefs", Context.MODE_PRIVATE)
            .getString("widget_theme", null);
    }

    static String getWidgetAccentColor(Context context) {
        return context.getSharedPreferences("quick_capture_prefs", Context.MODE_PRIVATE)
            .getString("widget_accent", null);
    }

    static int parseColorOrFallback(String candidate, String fallback) {
        if (candidate != null) {
            String trimmed = candidate.trim();
            if (trimmed.matches("^#[0-9a-fA-F]{6}$")) {
                try {
                    return Color.parseColor(trimmed);
                } catch (IllegalArgumentException ignored) {
                }
            }
        }
        return Color.parseColor(fallback);
    }

    static int clamp(int value, int min, int max) {
        if (value < min) return min;
        if (value > max) return max;
        return value;
    }

    static int dpToPx(Context context, float dp) {
        DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, metrics));
    }

    static float spToPx(Context context, float sp) {
        DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, metrics);
    }

    static int adjustAlpha(int color, float factor) {
        int alpha = Math.round(Color.alpha(color) * factor);
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
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
        if ("skip".equals(trimmed) || STATE_SKIP.equals(trimmed)) return STATE_SKIP;
        if ("fail".equals(trimmed) || STATE_FAIL.equals(trimmed)) return STATE_FAIL;
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
        if (monthly == null) return null;
        return getTodayValueFromMonthlyLog(monthly, today);
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
        else if (STATE_SKIP.equals(newValue)) fields.put(dayKey, "skip");
        else if (STATE_FAIL.equals(newValue)) fields.put(dayKey, "fail");
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

}
