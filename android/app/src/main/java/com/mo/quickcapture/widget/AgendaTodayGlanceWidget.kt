package com.mo.quickcapture.widget

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.documentfile.provider.DocumentFile
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.ImageProvider
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.color.ColorProvider
import androidx.glance.layout.*
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import com.mo.quickcapture.MainActivity
import com.mo.quickcapture.R
import com.mo.quickcapture.WidgetRefreshScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey

private const val PREFS_NAME = "quick_capture_prefs"
private const val FOLDER_URI_KEY = "folder_uri"
private const val MAX_WIDGET_ITEMS = 6
private const val RECENT_DONE_WINDOW_MS = 1800L
private const val RECENT_RESCHEDULED_WINDOW_MS = 1800L
private val ACTION_REFRESH_DELAYS_MS = longArrayOf(250L, 1000L, 2000L)
private const val TAG = "WidgetRefresh"
private const val DEBUG_LOGS = false
private val REFRESH_TOKEN_KEY = longPreferencesKey("agenda_refresh_token")
private val THEME_KEY = stringPreferencesKey("agenda_theme")
private val ACCENT_KEY = stringPreferencesKey("agenda_accent")
private val CORNER_STYLE_KEY = stringPreferencesKey("agenda_corner_style")
private val STATE_RECENT_DONE_AT_KEY = longPreferencesKey("agenda_recent_done_at")
private val STATE_RECENT_DONE_FILE_KEY = stringPreferencesKey("agenda_recent_done_file")
private val STATE_RECENT_DONE_LINE_KEY = longPreferencesKey("agenda_recent_done_line")
private val STATE_RECENT_DONE_BODY_KEY = stringPreferencesKey("agenda_recent_done_body")
private val STATE_RECENT_DONE_DUE_KEY = stringPreferencesKey("agenda_recent_done_due")
private val STATE_RECENT_DONE_URGENT_KEY = stringPreferencesKey("agenda_recent_done_urgent")
private val STATE_RECENT_RESCHEDULED_AT_KEY = longPreferencesKey("agenda_recent_rescheduled_at")
private val STATE_RECENT_RESCHEDULED_FILE_KEY = stringPreferencesKey("agenda_recent_rescheduled_file")
private val STATE_RECENT_RESCHEDULED_LINE_KEY = longPreferencesKey("agenda_recent_rescheduled_line")

private data class AgendaTask(
    val fileName: String,
    val lineIndex: Int,
    val state: String,
    val body: String,
    val dueDate: String,
    val isHighPriority: Boolean,
    val isRecentlyCompleted: Boolean = false,
)

private data class WidgetPalette(
    val titleColor: Color,
    val subtextColor: Color,
    val heroColor: Color,
    val backgroundColor: Color,
    val backgroundRes: Int,
    val failColor: Color,
    val ringNeutralColor: Color,
    val successColor: Color
)

private object AgendaActionKeys {
    val file = ActionParameters.Key<String>("file")
    val line = ActionParameters.Key<Int>("line")
    val body = ActionParameters.Key<String>("body")
    val due = ActionParameters.Key<String>("due")
    val urgent = ActionParameters.Key<Boolean>("urgent")
}

class AgendaTodayGlanceWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = AgendaTodayGlanceWidget()

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        WidgetRefreshScheduler.scheduleNextMidnightRefresh(context)
    }
}

private fun parseColorOrFallback(candidate: String?, fallback: String): Color {
    if (candidate != null) {
        val trimmed = candidate.trim()
        if (trimmed.matches("^#[0-9a-fA-F]{6}$".toRegex())) {
            try {
                return Color(android.graphics.Color.parseColor(trimmed))
            } catch (ignored: IllegalArgumentException) {
            }
        }
    }
    return Color(android.graphics.Color.parseColor(fallback))
}

private fun debugLog(message: String) {
    if (DEBUG_LOGS) {
        Log.d(TAG, message)
    }
}

private fun buildPalette(context: Context, widgetTheme: String?, accentColor: String?, cornerStyle: String?): WidgetPalette {
    val isDarkSystem = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    val resolvedTheme = if (widgetTheme.isNullOrEmpty()) (if (isDarkSystem) "dark" else "light") else widgetTheme

    var hero = parseColorOrFallback(accentColor, if (resolvedTheme == "light") "#2563ff" else "#ce43b7")
    if (resolvedTheme == "dim" && (accentColor == null || accentColor.trim().isEmpty())) {
        hero = Color(android.graphics.Color.parseColor("#ff53b3"))
    }

    debugLog("buildPalette: widgetTheme=$widgetTheme resolvedTheme=$resolvedTheme accent=$accentColor corners=$cornerStyle hero=$hero")

    return when (resolvedTheme) {
        "dark" -> WidgetPalette(
            titleColor = Color(0xFFF3F4F6),
            subtextColor = Color(0xFFB8BBC2),
            heroColor = hero,
            backgroundColor = Color(android.graphics.Color.parseColor("#CC18191D")),
            backgroundRes = widgetBackgroundRes("dark", cornerStyle),
            failColor = Color(0xFFEF4444),
            ringNeutralColor = Color(0xFF5D6169),
            successColor = Color(0xFF34D399)
        )
        "dim" -> WidgetPalette(
            titleColor = Color(0xFFE8EDF7),
            subtextColor = Color(0xFFA7B2C4),
            heroColor = hero,
            backgroundColor = Color(android.graphics.Color.parseColor("#CC132435")),
            backgroundRes = widgetBackgroundRes("dim", cornerStyle),
            failColor = Color(0xFFF87171),
            ringNeutralColor = Color(0xFF6E7280),
            successColor = Color(0xFF34D399)
        )
        else -> WidgetPalette( // light
            titleColor = Color(0xFF111827),
            subtextColor = Color(0xFF6B7280),
            heroColor = hero,
            backgroundColor = Color(android.graphics.Color.parseColor("#D8ECECEF")),
            backgroundRes = widgetBackgroundRes("light", cornerStyle),
            failColor = Color(0xFFDC2626),
            ringNeutralColor = Color(0xFFA5ACB8),
            successColor = Color(0xFF16A34A)
        )
    }
}

private fun normalizeCornerStyle(cornerStyle: String?): String {
    val trimmed = cornerStyle?.trim()
    return if (trimmed == "square" || trimmed == "soft" || trimmed == "round") trimmed else "round"
}

private fun widgetBackgroundRes(resolvedTheme: String, cornerStyle: String?): Int {
    return when (resolvedTheme) {
        "dark" -> when (normalizeCornerStyle(cornerStyle)) {
            "square" -> R.drawable.widget_pill_dark_square
            "soft" -> R.drawable.widget_pill_dark_soft
            else -> R.drawable.widget_pill_dark
        }
        "dim" -> when (normalizeCornerStyle(cornerStyle)) {
            "square" -> R.drawable.widget_pill_dim_square
            "soft" -> R.drawable.widget_pill_dim_soft
            else -> R.drawable.widget_pill_dim
        }
        else -> when (normalizeCornerStyle(cornerStyle)) {
            "square" -> R.drawable.widget_pill_light_square
            "soft" -> R.drawable.widget_pill_light_soft
            else -> R.drawable.widget_pill_light
        }
    }
}

class AgendaTodayGlanceWidget : GlanceAppWidget() {
    override val stateDefinition = PreferencesGlanceStateDefinition

    companion object {
        @JvmStatic
        fun update(context: Context) {
            CoroutineScope(Dispatchers.Default).launch {
                val widget = AgendaTodayGlanceWidget()
                val manager = GlanceAppWidgetManager(context)
                val glanceIds = manager.getGlanceIds(AgendaTodayGlanceWidget::class.java)
                debugLog("AgendaTodayGlanceWidget.update: glanceIdCount=${glanceIds.size}")
                if (glanceIds.isEmpty()) {
                    return@launch
                }

                glanceIds.forEachIndexed { index, glanceId ->
                    val refreshToken = System.currentTimeMillis()
                    val sharedPrefs = context.getSharedPreferences("quick_capture_prefs", Context.MODE_PRIVATE)
                    val widgetTheme = sharedPrefs.getString("widget_theme", null)
                    val widgetAccent = sharedPrefs.getString("widget_accent", null)
                    val widgetCornerStyle = sharedPrefs.getString("widget_corner_style", null)
                    updateAppWidgetState(context, PreferencesGlanceStateDefinition, glanceId) { prefs ->
                        val mutablePrefs = mutablePreferencesOf()
                        prefs.asMap().forEach { (key, value) ->
                            @Suppress("UNCHECKED_CAST")
                            mutablePrefs[key as Preferences.Key<Any>] = value
                        }
                        mutablePrefs[REFRESH_TOKEN_KEY] = refreshToken
                        if (widgetTheme != null) {
                            mutablePrefs[THEME_KEY] = widgetTheme
                        } else {
                            mutablePrefs.remove(THEME_KEY)
                        }
                        if (widgetAccent != null) {
                            mutablePrefs[ACCENT_KEY] = widgetAccent
                        } else {
                            mutablePrefs.remove(ACCENT_KEY)
                        }
                        if (widgetCornerStyle != null) {
                            mutablePrefs[CORNER_STYLE_KEY] = widgetCornerStyle
                        } else {
                            mutablePrefs.remove(CORNER_STYLE_KEY)
                        }
                        mutablePrefs
                    }
                    debugLog("AgendaTodayGlanceWidget.update: refreshToken=$refreshToken theme=$widgetTheme accent=$widgetAccent corners=$widgetCornerStyle glanceIdIndex=$index")
                    debugLog("AgendaTodayGlanceWidget.update: updating glanceIdIndex=$index")
                    widget.update(context, glanceId)
                }
            }
        }
    }

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            val state = currentState<Preferences>()
            val refreshToken = state[REFRESH_TOKEN_KEY] ?: 0L
            val widgetTheme = state[THEME_KEY]
            val widgetAccent = state[ACCENT_KEY]
            val widgetCornerStyle = state[CORNER_STYLE_KEY]
            val (loadedTasks, error) = loadPendingDueTodayTasksWithError(context)
            val tasks = applyWidgetStateTaskOverrides(loadedTasks, state)
            val palette = buildPalette(context, widgetTheme, widgetAccent, widgetCornerStyle)
            debugLog("provideGlance: refreshToken=$refreshToken theme=$widgetTheme accent=$widgetAccent corners=$widgetCornerStyle taskCount=${tasks.size} error=$error")
            Column(
                modifier = GlanceModifier
                    .fillMaxSize()
                    .background(ImageProvider(palette.backgroundRes))
                    .padding(18.dp),
            ) {
                Row(
                    modifier = GlanceModifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Today",
                        style = TextStyle(
                            color = ColorProvider(day = palette.titleColor, night = palette.titleColor),
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Medium,
                        ),
                        modifier = GlanceModifier.defaultWeight()
                    )
                    Text(
                        text = "+",
                        style = TextStyle(
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Medium,
                            color = ColorProvider(day = palette.heroColor, night = palette.heroColor)
                        ),
                        modifier = GlanceModifier
                            .padding(end = 4.dp)
                            .clickable(actionStartActivity(buildAddTaskIntent(context)))
                    )
                    Text(
                        text = "🔄",
                        style = TextStyle(
                            fontSize = 15.sp,
                            color = ColorProvider(day = palette.subtextColor, night = palette.subtextColor)
                        ),
                        modifier = GlanceModifier
                            .padding(6.dp)
                            .clickable(actionRunCallback<RefreshAction>())
                    )
                }
                Spacer(modifier = GlanceModifier.height(8.dp))

                if (error != null) {
                    Text(
                        text = error,
                        style = TextStyle(
                            color = ColorProvider(day = palette.failColor, night = palette.failColor),
                            fontSize = 13.sp,
                        ),
                    )
                } else if (tasks.isEmpty()) {
                    Text(
                        text = "🎉 All done!",
                        style = TextStyle(
                            color = ColorProvider(day = palette.subtextColor, night = palette.subtextColor),
                            fontSize = 13.sp,
                        ),
                    )
                } else {
                    tasks.take(MAX_WIDGET_ITEMS).forEach { task ->
                        TaskRow(task = task, palette = palette)
                        Spacer(modifier = GlanceModifier.height(6.dp))
                    }
                }
            }
        }
    }
}

private fun buildAddTaskIntent(context: Context): Intent {
    val today = todayIso()
    return Intent(context, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        putExtra(MainActivity.EXTRA_OPEN_PATH, "/tasks?date=$today&pulse=${System.currentTimeMillis()}")
    }
}

private fun todayIso(): String {
    val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    return sdf.format(Date())
}

private fun tomorrowIso(): String {
    val cal = Calendar.getInstance()
    cal.add(Calendar.DAY_OF_YEAR, 1)
    val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    return sdf.format(cal.time)
}

// Use lazy to avoid ExceptionInInitializerError during class loading
private val taskLineRegex by lazy { Regex("""^\s*-\s\[([fF*xX\s-]*?)\](?:\s+(.*))?$""") }
private val dueDateRegex by lazy { Regex("""\uD83D\uDCC5\s*(\d{4}-\d{2}-\d{2})""") } // \uD83D\uDCC5 is 📅

private fun parseTaskLine(line: String): AgendaTask? {
    val match = taskLineRegex.find(line) ?: return null

    val markerRaw = (match.groupValues.getOrNull(1) ?: "").lowercase(Locale.US)
    val body = (match.groupValues.getOrNull(2) ?: "").trim()
    if (body.isEmpty())
        return null

    val isHighPriority = markerRaw.contains('f') || markerRaw.contains('*')
    val stateChar = markerRaw.replace(Regex("[f*]"), "").trim().ifEmpty { " " }
    val state = when (stateChar) {
        "x" -> "done"
        "-" -> "cancelled"
        else -> "pending"
    }

    val dueMatch = dueDateRegex.find(body) ?: return null
    val dueDate = dueMatch.groupValues.getOrNull(1) ?: return null

    return AgendaTask(
        fileName = "",
        lineIndex = -1,
        state = state,
        body = body,
        dueDate = dueDate,
        isHighPriority = isHighPriority,
    )
}

private fun serializeTaskLine(state: String, body: String, dueDate: String?, isHighPriority: Boolean): String {
    var marker = when (state) {
        "done" -> "x"
        "cancelled" -> "-"
        else -> " "
    }

    if (isHighPriority && state == "pending") {
        marker = (marker + "*").trim().ifEmpty { "*" }
    }

    var normalizedBody = body.replace(Regex("""\s*\uD83D\uDCC5\s*\d{4}-\d{2}-\d{2}\s*"""), " ").trim()
    if (!dueDate.isNullOrBlank()) {
        normalizedBody = "$normalizedBody 📅 $dueDate".trim()
    }

    return "- [$marker] $normalizedBody"
}

private fun displayBody(body: String): String {
    return body
        .replace(Regex("""\s*\uD83D\uDCC5\s*\d{4}-\d{2}-\d{2}\s*"""), " ")
        .replace(Regex("""\s*🔁\s*(daily|weekly|weekdays|monthly)\s*""", RegexOption.IGNORE_CASE), " ")
        .replace(Regex("""(?<!\S)#[^\s#]+"""), " ")
        .replace(Regex("""\s{2,}"""), " ")
        .trim()
}

private fun displayTags(body: String): List<String> {
    return Regex("""(?<!\S)#[^\s#]+""")
        .findAll(body)
        .map { it.value.trim() }
        .filter { it.length > 1 }
        .take(2)
        .toList()
}

private fun recentDoneKey(fileName: String, lineIndex: Int): String {
    return "agenda_recent_done_${fileName}_${lineIndex}"
}

private fun recentDoneBodyKey(fileName: String, lineIndex: Int): String {
    return "agenda_recent_done_body_${fileName}_${lineIndex}"
}

private fun recentDoneDueKey(fileName: String, lineIndex: Int): String {
    return "agenda_recent_done_due_${fileName}_${lineIndex}"
}

private fun recentDoneUrgentKey(fileName: String, lineIndex: Int): String {
    return "agenda_recent_done_urgent_${fileName}_${lineIndex}"
}

private fun recentRescheduledKey(fileName: String, lineIndex: Int): String {
    return "agenda_recent_rescheduled_${fileName}_${lineIndex}"
}

private fun markTaskRecentlyDone(
    context: Context,
    fileName: String,
    lineIndex: Int,
    body: String,
    dueDate: String,
    isHighPriority: Boolean,
) {
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .edit()
        .putLong(recentDoneKey(fileName, lineIndex), System.currentTimeMillis())
        .putString(recentDoneBodyKey(fileName, lineIndex), body)
        .putString(recentDoneDueKey(fileName, lineIndex), dueDate)
        .putBoolean(recentDoneUrgentKey(fileName, lineIndex), isHighPriority)
        .commit()
}

private fun markTaskRecentlyRescheduled(context: Context, fileName: String, lineIndex: Int) {
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .edit()
        .putLong(recentRescheduledKey(fileName, lineIndex), System.currentTimeMillis())
        .commit()
}

private fun wasTaskRecentlyDone(context: Context, fileName: String, lineIndex: Int): Boolean {
    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    val key = recentDoneKey(fileName, lineIndex)
    val timestamp = prefs.getLong(key, 0L)
    if (timestamp <= 0L) {
        return false
    }

    val isRecent = System.currentTimeMillis() - timestamp <= RECENT_DONE_WINDOW_MS
    if (!isRecent) {
        prefs.edit()
            .remove(key)
            .remove(recentDoneBodyKey(fileName, lineIndex))
            .remove(recentDoneDueKey(fileName, lineIndex))
            .remove(recentDoneUrgentKey(fileName, lineIndex))
            .apply()
    }
    return isRecent
}

private fun wasTaskRecentlyRescheduled(context: Context, fileName: String, lineIndex: Int): Boolean {
    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    val key = recentRescheduledKey(fileName, lineIndex)
    val timestamp = prefs.getLong(key, 0L)
    if (timestamp <= 0L) {
        return false
    }

    val isRecent = System.currentTimeMillis() - timestamp <= RECENT_RESCHEDULED_WINDOW_MS
    if (!isRecent) {
        prefs.edit().remove(key).apply()
    }
    return isRecent
}

private fun refreshAgendaWidgetWithFollowUps(context: Context) {
    debugLog("refreshAgendaWidgetWithFollowUps: immediate + follow-ups")
    WidgetRefreshScheduler.refreshAllWidgets(context)
    CoroutineScope(Dispatchers.Default).launch {
        ACTION_REFRESH_DELAYS_MS.forEach { delayMs ->
            delay(delayMs)
            debugLog("refreshAgendaWidgetWithFollowUps: follow-up delayMs=$delayMs")
            WidgetRefreshScheduler.refreshAllWidgets(context)
        }
    }
    WidgetRefreshScheduler.scheduleNextMidnightRefresh(context)
}

private fun recentlyCompletedTaskOverlay(context: Context, fileName: String, lineIndex: Int): AgendaTask? {
    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    if (!wasTaskRecentlyDone(context, fileName, lineIndex)) {
        return null
    }

    val body = prefs.getString(recentDoneBodyKey(fileName, lineIndex), null) ?: return null
    val dueDate = prefs.getString(recentDoneDueKey(fileName, lineIndex), null) ?: return null
    val isHighPriority = prefs.getBoolean(recentDoneUrgentKey(fileName, lineIndex), false)

    return AgendaTask(
        fileName = fileName,
        lineIndex = lineIndex,
        state = "done",
        body = body,
        dueDate = dueDate,
        isHighPriority = isHighPriority,
        isRecentlyCompleted = true,
    )
}

private fun applyWidgetStateTaskOverrides(tasks: List<AgendaTask>, state: Preferences): List<AgendaTask> {
    val now = System.currentTimeMillis()
    val recentDoneAt = state[STATE_RECENT_DONE_AT_KEY] ?: 0L
    val recentDoneFile = state[STATE_RECENT_DONE_FILE_KEY]
    val recentDoneLine = state[STATE_RECENT_DONE_LINE_KEY]?.toInt()
    val recentDoneBody = state[STATE_RECENT_DONE_BODY_KEY]
    val recentDoneDue = state[STATE_RECENT_DONE_DUE_KEY]
    val recentDoneUrgent = (state[STATE_RECENT_DONE_URGENT_KEY] ?: "false").toBoolean()
    val recentDoneActive = recentDoneAt > 0L &&
        now - recentDoneAt <= RECENT_DONE_WINDOW_MS &&
        recentDoneFile != null &&
        recentDoneLine != null &&
        recentDoneBody != null &&
        recentDoneDue != null

    val recentRescheduledAt = state[STATE_RECENT_RESCHEDULED_AT_KEY] ?: 0L
    val recentRescheduledFile = state[STATE_RECENT_RESCHEDULED_FILE_KEY]
    val recentRescheduledLine = state[STATE_RECENT_RESCHEDULED_LINE_KEY]?.toInt()
    val recentRescheduledActive = recentRescheduledAt > 0L &&
        now - recentRescheduledAt <= RECENT_RESCHEDULED_WINDOW_MS &&
        recentRescheduledFile != null &&
        recentRescheduledLine != null

    val overriddenTasks = tasks.mapNotNull { task ->
        val matchesRecentDone = recentDoneFile == task.fileName && recentDoneLine == task.lineIndex
        val matchesRecentRescheduled = recentRescheduledFile == task.fileName && recentRescheduledLine == task.lineIndex

        when {
            recentDoneActive && matchesRecentDone -> {
                task.copy(state = "done", isRecentlyCompleted = true)
            }
            matchesRecentDone -> {
                null
            }
            recentRescheduledActive && matchesRecentRescheduled -> {
                null
            }
            else -> task
        }
    }.toMutableList()

    val hasRecentDoneRow = recentDoneFile != null && recentDoneLine != null &&
        overriddenTasks.any { it.fileName == recentDoneFile && it.lineIndex == recentDoneLine }

    if (recentDoneActive && !hasRecentDoneRow) {
        overriddenTasks.add(
            0,
            AgendaTask(
                fileName = recentDoneFile!!,
                lineIndex = recentDoneLine!!,
                state = "done",
                body = recentDoneBody!!,
                dueDate = recentDoneDue!!,
                isHighPriority = recentDoneUrgent,
                isRecentlyCompleted = true,
            )
        )
    }

    return overriddenTasks.sortedByDescending { it.isHighPriority }
}

@Composable
private fun TaskRow(task: AgendaTask, palette: WidgetPalette) {
    val checkParams = actionParametersOf(
        AgendaActionKeys.file to task.fileName,
        AgendaActionKeys.line to task.lineIndex,
        AgendaActionKeys.body to task.body,
        AgendaActionKeys.due to task.dueDate,
        AgendaActionKeys.urgent to task.isHighPriority,
    )
    val nextParams = actionParametersOf(
        AgendaActionKeys.file to task.fileName,
        AgendaActionKeys.line to task.lineIndex,
        AgendaActionKeys.body to task.body,
        AgendaActionKeys.due to task.dueDate,
        AgendaActionKeys.urgent to task.isHighPriority,
    )
    Row(
        modifier = GlanceModifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val showCompleted = task.isRecentlyCompleted || task.state == "done"

        Box(
            modifier = GlanceModifier
                .width(22.dp)
                .height(22.dp)
                .background(if (showCompleted) palette.successColor else palette.ringNeutralColor)
                .padding(2.dp)
                .clickable(actionRunCallback<CheckOffTaskAction>(checkParams)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "✓",
                style = TextStyle(
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = ColorProvider(
                        day = if (showCompleted) Color(0xFFFFFFFF) else palette.backgroundColor,
                        night = if (showCompleted) Color(0xFFFFFFFF) else palette.backgroundColor
                    )
                ),
            )
        }
        Spacer(modifier = GlanceModifier.width(8.dp))
        Column(modifier = GlanceModifier.defaultWeight()) {
            val tags = displayTags(task.body)
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (task.isHighPriority) {
                    Text(
                        text = "★",
                        style = TextStyle(
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = ColorProvider(day = Color(0xFFFACC15), night = Color(0xFFFACC15))
                        ),
                    )
                    Spacer(modifier = GlanceModifier.width(4.dp))
                }
                Text(
                    text = displayBody(task.body),
                    maxLines = 1,
                    style = TextStyle(
                        fontSize = 13.sp,
                        color = ColorProvider(day = palette.titleColor, night = palette.titleColor)
                    ),
                )
            }
            if (tags.isNotEmpty()) {
                Spacer(modifier = GlanceModifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    tags.forEachIndexed { index, tag ->
                        Box(
                            modifier = GlanceModifier
                                .background(palette.heroColor)
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = tag,
                                maxLines = 1,
                                style = TextStyle(
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = ColorProvider(day = Color(0xFFFFFFFF), night = Color(0xFFFFFFFF))
                                ),
                            )
                        }
                        if (index < tags.lastIndex) {
                            Spacer(modifier = GlanceModifier.width(4.dp))
                        }
                    }
                }
            }
        }
        Spacer(modifier = GlanceModifier.width(8.dp))
        Text(
            text = "⏩",
            style = TextStyle(
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = ColorProvider(day = palette.heroColor, night = palette.heroColor),
                textAlign = TextAlign.End,
            ),
            modifier = GlanceModifier.clickable(actionRunCallback<RescheduleTaskAction>(nextParams))
        )
        
    }
}

class RefreshAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
    debugLog("RefreshAction.onAction")
        refreshAgendaWidgetWithFollowUps(context)
    }
}

private fun loadPendingDueTodayTasksWithError(context: Context): Pair<List<AgendaTask>, String?> {
    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    val folderUriStr = prefs.getString(FOLDER_URI_KEY, null)
    if (folderUriStr == null) {
        return emptyList<AgendaTask>() to "No folder selected in app"
    }

    try {
        val folderUri = Uri.parse(folderUriStr)
        val rootDoc = DocumentFile.fromTreeUri(context, folderUri)
        if (rootDoc == null || !rootDoc.exists()) {
            return emptyList<AgendaTask>() to "Folder no longer exists"
        }

        val allTasks = linkedMapOf<String, AgendaTask>()
        val today = todayIso()
        debugLog("loadPendingDueTodayTasksWithError: today=$today")

        rootDoc.listFiles().forEach { file ->
            if (file.isFile && file.name?.endsWith(".md") == true) {
                context.contentResolver.openInputStream(file.uri)?.use { inputStream ->
                    BufferedReader(InputStreamReader(inputStream, StandardCharsets.UTF_8)).use { reader ->
                        var lineIndex = 0
                        var line = reader.readLine()
                        while (line != null) {
                            val task = parseTaskLine(line)
                            val resolvedFileName = file.name ?: "unknown"
                            val taskKey = "$resolvedFileName:$lineIndex"
                            val recentlyCompleted = wasTaskRecentlyDone(context, resolvedFileName, lineIndex)
                            val recentlyRescheduled = wasTaskRecentlyRescheduled(context, resolvedFileName, lineIndex)

                            if (task != null && task.dueDate == today) {
                                if (recentlyRescheduled) {
                                    allTasks.remove(taskKey)
                                } else if (recentlyCompleted) {
                                    allTasks[taskKey] = task.copy(
                                        fileName = resolvedFileName,
                                        lineIndex = lineIndex,
                                        state = "done",
                                        isRecentlyCompleted = true,
                                    )
                                } else if (task.state == "pending") {
                                    allTasks[taskKey] = task.copy(
                                        fileName = resolvedFileName,
                                        lineIndex = lineIndex,
                                    )
                                }
                            } else if (recentlyCompleted) {
                                val overlayTask = recentlyCompletedTaskOverlay(context, resolvedFileName, lineIndex)
                                if (overlayTask != null && overlayTask.dueDate == today) {
                                    allTasks[taskKey] = overlayTask
                                }
                            }
                            line = reader.readLine()
                            lineIndex++
                        }
                    }
                }
            }
        }

        val taskSummaries = allTasks.values.joinToString(" | ") {
            "${it.fileName}:${it.lineIndex}:${it.state}:${displayBody(it.body)}"
        }
        debugLog("loadPendingDueTodayTasksWithError: loadedCount=${allTasks.size} tasks=$taskSummaries")
        return allTasks.values.sortedByDescending { it.isHighPriority } to null
    } catch (e: Exception) {
        Log.e("AgendaTodayWidget", "Error loading tasks", e)
        return emptyList<AgendaTask>() to "Error: ${e.message}"
    }
}

class CheckOffTaskAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val fileName = parameters[AgendaActionKeys.file] ?: return
        val lineIndex = parameters[AgendaActionKeys.line] ?: return
        val body = parameters[AgendaActionKeys.body] ?: return
        val due = parameters[AgendaActionKeys.due] ?: return
        val urgent = parameters[AgendaActionKeys.urgent] ?: false
        debugLog("CheckOffTaskAction.onAction file=$fileName lineIndex=$lineIndex due=$due urgent=$urgent")

        updateTaskInFile(context, fileName, lineIndex, "done", body, due, urgent)
        markTaskRecentlyDone(context, fileName, lineIndex, body, due, urgent)
        updateAppWidgetState(context, PreferencesGlanceStateDefinition, glanceId) { prefs ->
            val mutablePrefs = mutablePreferencesOf()
            prefs.asMap().forEach { (key, value) ->
                @Suppress("UNCHECKED_CAST")
                mutablePrefs[key as Preferences.Key<Any>] = value
            }
            mutablePrefs[STATE_RECENT_DONE_AT_KEY] = System.currentTimeMillis()
            mutablePrefs[STATE_RECENT_DONE_FILE_KEY] = fileName
            mutablePrefs[STATE_RECENT_DONE_LINE_KEY] = lineIndex.toLong()
            mutablePrefs[STATE_RECENT_DONE_BODY_KEY] = body
            mutablePrefs[STATE_RECENT_DONE_DUE_KEY] = due
            mutablePrefs[STATE_RECENT_DONE_URGENT_KEY] = urgent.toString()
            mutablePrefs.remove(STATE_RECENT_RESCHEDULED_AT_KEY)
            mutablePrefs.remove(STATE_RECENT_RESCHEDULED_FILE_KEY)
            mutablePrefs.remove(STATE_RECENT_RESCHEDULED_LINE_KEY)
            mutablePrefs
        }
        refreshAgendaWidgetWithFollowUps(context)
        CoroutineScope(Dispatchers.Default).launch {
            delay(RECENT_DONE_WINDOW_MS)
            WidgetRefreshScheduler.refreshAllWidgets(context)
        }
    }
}

class RescheduleTaskAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val fileName = parameters[AgendaActionKeys.file] ?: return
        val lineIndex = parameters[AgendaActionKeys.line] ?: return
        val body = parameters[AgendaActionKeys.body] ?: return
        val due = parameters[AgendaActionKeys.due] ?: return
        val urgent = parameters[AgendaActionKeys.urgent] ?: false
        debugLog("RescheduleTaskAction.onAction file=$fileName lineIndex=$lineIndex due=$due urgent=$urgent")

        updateTaskInFile(context, fileName, lineIndex, "pending", body, tomorrowIso(), urgent)
        markTaskRecentlyRescheduled(context, fileName, lineIndex)
        updateAppWidgetState(context, PreferencesGlanceStateDefinition, glanceId) { prefs ->
            val mutablePrefs = mutablePreferencesOf()
            prefs.asMap().forEach { (key, value) ->
                @Suppress("UNCHECKED_CAST")
                mutablePrefs[key as Preferences.Key<Any>] = value
            }
            mutablePrefs[STATE_RECENT_RESCHEDULED_AT_KEY] = System.currentTimeMillis()
            mutablePrefs[STATE_RECENT_RESCHEDULED_FILE_KEY] = fileName
            mutablePrefs[STATE_RECENT_RESCHEDULED_LINE_KEY] = lineIndex.toLong()
            mutablePrefs.remove(STATE_RECENT_DONE_AT_KEY)
            mutablePrefs.remove(STATE_RECENT_DONE_FILE_KEY)
            mutablePrefs.remove(STATE_RECENT_DONE_LINE_KEY)
            mutablePrefs.remove(STATE_RECENT_DONE_BODY_KEY)
            mutablePrefs.remove(STATE_RECENT_DONE_DUE_KEY)
            mutablePrefs.remove(STATE_RECENT_DONE_URGENT_KEY)
            mutablePrefs
        }
        refreshAgendaWidgetWithFollowUps(context)
        CoroutineScope(Dispatchers.Default).launch {
            delay(RECENT_RESCHEDULED_WINDOW_MS)
            WidgetRefreshScheduler.refreshAllWidgets(context)
        }
    }
}

private fun updateTaskInFile(
    context: Context,
    fileName: String,
    lineIndex: Int,
    newState: String,
    body: String,
    dueDate: String?,
    isHighPriority: Boolean
) {
    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    val folderUriStr = prefs.getString(FOLDER_URI_KEY, null) ?: return
    debugLog("updateTaskInFile: file=$fileName lineIndex=$lineIndex newState=$newState dueDate=$dueDate urgent=$isHighPriority")
    val folderUri = Uri.parse(folderUriStr)
    val rootDoc = DocumentFile.fromTreeUri(context, folderUri) ?: return
    val file = rootDoc.findFile(fileName) ?: return

    val lines = mutableListOf<String>()
    context.contentResolver.openInputStream(file.uri)?.use { inputStream ->
        BufferedReader(InputStreamReader(inputStream, StandardCharsets.UTF_8)).use { reader ->
            var line = reader.readLine()
            while (line != null) {
                lines.add(line)
                line = reader.readLine()
            }
        }
    }

    if (lineIndex >= 0 && lineIndex < lines.size) {
        lines[lineIndex] = serializeTaskLine(newState, body, dueDate, isHighPriority)
        val content = lines.joinToString("\n")
        context.contentResolver.openFileDescriptor(file.uri, "wt")?.use { pfd ->
            FileOutputStream(pfd.fileDescriptor).use { outputStream ->
                outputStream.write(content.toByteArray(StandardCharsets.UTF_8))
            }
        }
        debugLog("updateTaskInFile: write complete file=$fileName")
    }
}
