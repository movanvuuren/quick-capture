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
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.color.ColorProvider
import androidx.glance.layout.*
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import com.mo.quickcapture.R
import com.mo.quickcapture.WidgetRefreshScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private const val PREFS_NAME = "quick_capture_prefs"
private const val FOLDER_URI_KEY = "folder_uri"
private const val MAX_WIDGET_ITEMS = 6
private const val RECENT_DONE_WINDOW_MS = 1800L
private const val RECENT_RESCHEDULED_WINDOW_MS = 1800L

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

private fun getWidgetTheme(context: Context): String? {
    val prefs = context.getSharedPreferences("quick_capture_prefs", Context.MODE_PRIVATE)
    return prefs.getString("widget_theme", null)
}

private fun getWidgetAccentColor(context: Context): String? {
    val prefs = context.getSharedPreferences("quick_capture_prefs", Context.MODE_PRIVATE)
    return prefs.getString("widget_accent", null)
}

private fun buildPalette(context: Context): WidgetPalette {
    val widgetTheme = getWidgetTheme(context)
    val accentColor = getWidgetAccentColor(context)

    val isDarkSystem = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    val resolvedTheme = if (widgetTheme.isNullOrEmpty()) (if (isDarkSystem) "dark" else "light") else widgetTheme

    var hero = parseColorOrFallback(accentColor, if (resolvedTheme == "light") "#2563ff" else "#ce43b7")
    if (resolvedTheme == "dim" && (accentColor == null || accentColor.trim().isEmpty())) {
        hero = Color(android.graphics.Color.parseColor("#ff53b3"))
    }

    return when (resolvedTheme) {
        "dark" -> WidgetPalette(
            titleColor = Color(0xFFF3F4F6),
            subtextColor = Color(0xFFB8BBC2),
            heroColor = hero,
            backgroundColor = Color(android.graphics.Color.parseColor("#CC18191D")),
            failColor = Color(0xFFEF4444),
            ringNeutralColor = Color(0xFF5D6169),
            successColor = Color(0xFF34D399)
        )
        "dim" -> WidgetPalette(
            titleColor = Color(0xFFE8EDF7),
            subtextColor = Color(0xFFA7B2C4),
            heroColor = hero,
            backgroundColor = Color(android.graphics.Color.parseColor("#CC132435")),
            failColor = Color(0xFFF87171),
            ringNeutralColor = Color(0xFF6E7280),
            successColor = Color(0xFF34D399)
        )
        else -> WidgetPalette( // light
            titleColor = Color(0xFF111827),
            subtextColor = Color(0xFF6B7280),
            heroColor = hero,
            backgroundColor = Color(android.graphics.Color.parseColor("#D8ECECEF")),
            failColor = Color(0xFFDC2626),
            ringNeutralColor = Color(0xFFA5ACB8),
            successColor = Color(0xFF16A34A)
        )
    }
}

class AgendaTodayGlanceWidget : GlanceAppWidget() {
    companion object {
        @JvmStatic
        fun update(context: Context) {
            CoroutineScope(Dispatchers.Default).launch {
                AgendaTodayGlanceWidget().updateAll(context)
            }
        }
    }

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val (tasks, error) = loadPendingDueTodayTasksWithError(context)
        val palette = buildPalette(context)
        provideContent {
            Column(
                modifier = GlanceModifier
                    .fillMaxSize()
                    .background(palette.backgroundColor)
                    .padding(12.dp),
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
private val taskLineRegex by lazy { Regex("""^\s*-\s\[([fFxX\s-]*?)\](?:\s+(.*))?$""") }
private val dueDateRegex by lazy { Regex("""\uD83D\uDCC5\s*(\d{4}-\d{2}-\d{2})""") } // \uD83D\uDCC5 is 📅

private fun parseTaskLine(line: String): AgendaTask? {
    val match = taskLineRegex.find(line) ?: return null

    val markerRaw = (match.groupValues.getOrNull(1) ?: "").lowercase(Locale.US)
    val body = (match.groupValues.getOrNull(2) ?: "").trim()
    if (body.isEmpty())
        return null

    val isHighPriority = markerRaw.contains('f')
    val stateChar = markerRaw.replace("f", "").trim().ifEmpty { " " }
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
        marker = (marker + "f").trim().ifEmpty { "f" }
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
        .replace(Regex("""\s{2,}"""), " ")
        .trim()
}

private fun recentDoneKey(fileName: String, lineIndex: Int): String {
    return "agenda_recent_done_${fileName}_${lineIndex}"
}

private fun recentRescheduledKey(fileName: String, lineIndex: Int): String {
    return "agenda_recent_rescheduled_${fileName}_${lineIndex}"
}

private fun markTaskRecentlyDone(context: Context, fileName: String, lineIndex: Int) {
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .edit()
        .putLong(recentDoneKey(fileName, lineIndex), System.currentTimeMillis())
        .apply()
}

private fun markTaskRecentlyRescheduled(context: Context, fileName: String, lineIndex: Int) {
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .edit()
        .putLong(recentRescheduledKey(fileName, lineIndex), System.currentTimeMillis())
        .apply()
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
        prefs.edit().remove(key).apply()
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
    val skipParams = actionParametersOf(
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (task.isHighPriority) {
                    Image(
                        provider = ImageProvider(R.drawable.ic_flame),
                        contentDescription = "High Priority",
                        modifier = GlanceModifier.width(14.dp).height(14.dp),
                        colorFilter = ColorFilter.tint(ColorProvider(day = palette.failColor, night = palette.failColor))
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
            Text(
                text = task.fileName,
                maxLines = 1,
                style = TextStyle(
                    fontSize = 10.sp,
                    color = ColorProvider(day = palette.subtextColor, night = palette.subtextColor),
                ),
            )
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
        WidgetRefreshScheduler.refreshAllWidgets(context)
        WidgetRefreshScheduler.scheduleNextMidnightRefresh(context)
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

        val allTasks = mutableListOf<AgendaTask>()
        val today = todayIso()

        rootDoc.listFiles().forEach { file ->
            if (file.isFile && file.name?.endsWith(".md") == true) {
                context.contentResolver.openInputStream(file.uri)?.use { inputStream ->
                    BufferedReader(InputStreamReader(inputStream, StandardCharsets.UTF_8)).use { reader ->
                        var lineIndex = 0
                        var line = reader.readLine()
                        while (line != null) {
                            val task = parseTaskLine(line)
                            val recentlyCompleted = task != null && wasTaskRecentlyDone(context, file.name ?: "unknown", lineIndex)
                            val recentlyRescheduled = task != null && wasTaskRecentlyRescheduled(context, file.name ?: "unknown", lineIndex)
                            if (task != null && task.dueDate == today && (
                                (task.state == "pending" && !recentlyRescheduled) || recentlyCompleted
                            )) {
                                allTasks.add(
                                    task.copy(
                                        fileName = file.name ?: "unknown",
                                        lineIndex = lineIndex,
                                        isRecentlyCompleted = recentlyCompleted,
                                    )
                                )
                            }
                            line = reader.readLine()
                            lineIndex++
                        }
                    }
                }
            }
        }

        return allTasks.sortedByDescending { it.isHighPriority } to null
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

        updateTaskInFile(context, fileName, lineIndex, "done", body, due, urgent)
        markTaskRecentlyDone(context, fileName, lineIndex)
        WidgetRefreshScheduler.refreshAllWidgets(context)
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

        updateTaskInFile(context, fileName, lineIndex, "pending", body, tomorrowIso(), urgent)
        markTaskRecentlyRescheduled(context, fileName, lineIndex)
        WidgetRefreshScheduler.refreshAllWidgets(context)
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
    }
}
