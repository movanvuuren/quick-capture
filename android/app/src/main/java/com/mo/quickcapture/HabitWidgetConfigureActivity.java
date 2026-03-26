package com.mo.quickcapture;

import android.appwidget.AppWidgetManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class HabitWidgetConfigureActivity extends AppCompatActivity {

    private int appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID;

    static class HabitItem {
        String id;
        String file;
        String name;
        String icon;
        int target;

        @Override
        public String toString() {
            String prefix = (icon != null && !icon.isEmpty()) ? icon + "  " : "";
            return prefix + name;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Default result: cancelled (user pressed back without selecting)
        setResult(RESULT_CANCELED);

        Bundle extras = getIntent().getExtras();
        if (extras != null) {
            appWidgetId = extras.getInt(
                AppWidgetManager.EXTRA_APPWIDGET_ID,
                AppWidgetManager.INVALID_APPWIDGET_ID
            );
        }
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish();
            return;
        }

        SharedPreferences prefs = getSharedPreferences("quick_capture_prefs", Context.MODE_PRIVATE);
        String habitsJson = prefs.getString("habits_json", "[]");

        List<HabitItem> items = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(habitsJson);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.getJSONObject(i);
                HabitItem item = new HabitItem();
                item.id = obj.optString("id", "");
                item.file = obj.optString("file", "");
                item.name = obj.optString("name", "Habit");
                item.icon = obj.optString("icon", "");
                item.target = obj.optInt("target", 1);
                if (!item.file.isEmpty()) items.add(item);
            }
        } catch (Exception ignored) {}

        if (items.isEmpty()) {
            TextView tv = new TextView(this);
            tv.setText(
                "No habits found.\n\n" +
                "Open Quick Capture, go to Settings → Habits, save at least one habit, " +
                "then try adding this widget again."
            );
            tv.setPadding(48, 64, 48, 48);
            tv.setTextSize(15f);
            setContentView(tv);
            return;
        }

        setContentView(R.layout.habit_widget_configure);

        ArrayAdapter<HabitItem> adapter = new ArrayAdapter<>(
            this, android.R.layout.simple_list_item_1, items
        );
        ListView listView = findViewById(R.id.habit_list);
        listView.setAdapter(adapter);
        listView.setOnItemClickListener((parent, view, position, id) ->
            saveAndFinish(items.get(position))
        );
    }

    private void saveAndFinish(HabitItem habit) {
        getSharedPreferences("quick_capture_widget", Context.MODE_PRIVATE)
            .edit()
            .putString("widget_" + appWidgetId + "_habit_id", habit.id != null ? habit.id : "")
            .putString("widget_" + appWidgetId + "_file", habit.file)
            .putString("widget_" + appWidgetId + "_name", habit.name)
            .putString("widget_" + appWidgetId + "_icon", habit.icon != null ? habit.icon : "")
            .putInt("widget_" + appWidgetId + "_target", Math.max(1, habit.target))
            .commit();

        AppWidgetManager manager = AppWidgetManager.getInstance(this);
        HabitWidgetProvider.updateWidget(this, manager, appWidgetId);

        Intent result = new Intent();
        result.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId);
        setResult(RESULT_OK, result);
        finish();
    }
}
