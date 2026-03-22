package com.mo.quickcapture.widget

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.documentfile.provider.DocumentFile
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.action.ActionParameters
import androidx.glance.action.clickable
import androidx.glance.action.actionParametersOf
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.color.ColorProvider
import androidx.glance.layout.*
import androidx.glance.text.TextAlign
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.regex.Pattern

private const val PREFS_NAME = "quick_capture_prefs"
private const val FOLDER_URI_KEY = "folder_uri"
private const val MAX_WIDGET_ITEMS = 6

private data class AgendaTask(
    val fileName: String,
    val lineIndex: Int,
    val state: String,
    val body: String,
    val dueDate: String,
    val isHighPriority: Boolean,
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
        when (intent.action) {
            Intent.ACTION_DATE_CHANGED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            -> {
                CoroutineScope(Dispatchers.Default).launch {
                    AgendaTodayGlanceWidget().updateAll(context)
                }
            }
        }
    }
}

class AgendaTodayGlanceWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val tasks = loadPendingDueTodayTasks(context).take(MAX_WIDGET_ITEMS)

        provideContent {
            Column(
                modifier = GlanceModifier
                    .fillMaxSize()
                    .background(ColorProvider(day = Color(0xFFF5F7FA), night = Color(0xFF1C222B)))
                    .padding(12.dp),
            ) {
                Text(
                    text = "Agenda Today",
                    style = TextStyle(
                        color = ColorProvider(day = Color(0xFF111827), night = Color(0xFFE5E7EB)),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                    ),
                )

                Spacer(modifier = GlanceModifier.height(8.dp))

                if (tasks.isEmpty()) {
                    Text(
                        text = "🎉 All done!",
                        style = TextStyle(
                            color = ColorProvider(day = Color(0xFF6B7280), night = Color(0xFF9CA3AF)),
                            fontSize = 13.sp,
                        ),
                    )
                } else {
                    tasks.forEach { task ->
                        TaskRow(task = task)
                        Spacer(modifier = GlanceModifier.height(6.dp))
                    }
                }
            }
        }
    }
}

private fun todayIso(): String {
    return LocalDate.now().format(DateTimeFormatter.ISO_DATE)
}

private fun tomorrowIso(): String {
    return LocalDate.now().plusDays(1).format(DateTimeFormatter.ISO_DATE)
}

private val taskLineRegex = Pattern.compile("^\\s*-\\s\\[([fFxX-\\s]*?)\\](?:\\s+(.*))?$")
private val dueDateRegex = Pattern.compile("📅\\s*(\\d{4}-\\d{2}-\\d{2})")

private fun parseTaskLine(line: String): AgendaTask? {
    val match = taskLineRegex.matcher(line)
    if (!match.find())
        return null

    val markerRaw = (match.group(1) ?: "").lowercase(Locale.US)
    val body = (match.group(2) ?: "").trim()
    if (body.isEmpty())
        return null

    val isHighPriority = markerRaw.contains('f')
    val stateChar = markerRaw.replace("f", "").trim().ifEmpty { " " }
    val state = when (stateChar) {
        "x" -> "done"
        "-" -> "cancelled"
        else -> "pending"
    }

    val dueMatcher = dueDateRegex.matcher(body)
    if (!dueMatcher.find())
        return null

    val dueDate = dueMatcher.group(1) ?: return null

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

    var normalizedBody = body.replace(Regex("\\s*📅\\s*\\d{4}-\\d{2}-\\d{2}\\s*"), " ").trim()
    if (!dueDate.isNullOrBlank()) {
        normalizedBody = "$normalizedBody 📅 $dueDate".trim()
    }

    return "- [$marker] $normalizedBody"
}

private fun displayBody(body: String): String {
    return body
        .replace(Regex("\\s*📅\\s*\\d{4}-\\d{2}-\\d{2}\\s*"), " ")
        .replace(Regex("\\s*🔁\\s*(daily|weekly|weekdays|monthly)\\s*", RegexOption.IGNORE_CASE), " ")
        .replace(Regex("\\s{2,}"), " ")
        .trim()
}

@Composable
private fun TaskRow(task: AgendaTask) {
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
        Box(
            modifier = GlanceModifier
                .width(22.dp)
                .height(22.dp)
                .background(ColorProvider(day = Color(0xFFD1D5DB), night = Color(0xFF4B5563)))
                .padding(2.dp)
                .clickable(actionRunCallback<CheckOffTaskAction>(checkParams)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "✓",
                style = TextStyle(
                    fontSize = 12.sp,
                    color = ColorProvider(day = Color(0xFF374151), night = Color(0xFFE5E7EB)),
                ),
            )
        }

        Spacer(modifier = GlanceModifier.width(8.dp))

        // In Glance, if defaultWeight() is not found, it is likely that the 
        // weight modifier is provided via ColumnScope or RowScope.
        // However, Glance usually uses GlanceModifier.defaultWeight() or GlanceModifier.weight(float).
        // If both are unresolved, we'll use fillMaxWidth() as a fallback for the text container.
        Column(modifier = GlanceModifier.defaultWeight()) {
            Text(
                text = if (task.isHighPriority) "[f] ${displayBody(task.body)}" else displayBody(task.body),
                maxLines = 1,
                style = TextStyle(
                    fontSize = 13.sp,
                    color = if (task.isHighPriority) {
                        ColorProvider(day = Color(0xFFB91C1C), night = Color(0xFFFCA5A5))
                    } else {
                        ColorProvider(day = Color(0xFF111827), night = Color(0xFFF3F4F6))
                    },
                ),
            )
            Text(
                text = task.fileName,
                maxLines = 1,
                style = TextStyle(
                    fontSize = 10.sp,
                    color = ColorProvider(day = Color(0xFF6B7280), night = Color(0xFF9CA3AF)),
                ),
            )
        }

        Spacer(modifier = GlanceModifier.width(8.dp))

        Text(
            text = "Next",
            style = TextStyle(
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = ColorProvider(day = Color(0xFF1D4ED8), night = Color(0xFF93C5FD)),
                textAlign = TextAlign.End,
            ),
            modifier = GlanceModifier.clickable(actionRunCallback<MoveTaskToTomorrowAction>(nextParams)),
        )
    }
}

class CheckOffTaskAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val fileName = parameters[AgendaActionKeys.file] ?: return
        val lineIndex = parameters[AgendaActionKeys.line] ?: return
        val body = parameters[AgendaActionKeys.body] ?: return
        val dueDate = parameters[AgendaActionKeys.due] ?: return
        val urgent = parameters[AgendaActionKeys.urgent] ?: false

        updateTaskLine(
            context = context,
            fileName = fileName,
            lineIndex = lineIndex,
            body = body,
            dueDate = dueDate,
            urgent = urgent,
            nextState = "done",
            nextDueDate = dueDate,
        )

        AgendaTodayGlanceWidget().updateAll(context)
    }
}

class MoveTaskToTomorrowAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val fileName = parameters[AgendaActionKeys.file] ?: return
        val lineIndex = parameters[AgendaActionKeys.line] ?: return
        val body = parameters[AgendaActionKeys.body] ?: return
        val dueDate = parameters[AgendaActionKeys.due] ?: return
        val urgent = parameters[AgendaActionKeys.urgent] ?: false

        if (dueDate != todayIso())
            return

        updateTaskLine(
            context = context,
            fileName = fileName,
            lineIndex = lineIndex,
            body = body,
            dueDate = dueDate,
            urgent = urgent,
            nextState = "pending",
            nextDueDate = tomorrowIso(),
        )

        AgendaTodayGlanceWidget().updateAll(context)
    }
}

private fun loadPendingDueTodayTasks(context: Context): List<AgendaTask> {
    return try {
        val folderUri = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(FOLDER_URI_KEY, null)
            ?: return emptyList()

        val root = DocumentFile.fromTreeUri(context, Uri.parse(folderUri)) ?: return emptyList()
        val today = todayIso()
        val tasks = mutableListOf<AgendaTask>()

        val files = root.listFiles().filter { it.isFile && it.name?.lowercase(Locale.US)?.endsWith(".md") == true }
        for (file in files) {
            val fileName = file.name ?: continue
            val content = readFile(context, file.uri)
            content.lines().forEachIndexed { index, line ->
                val task = parseTaskLine(line)
                if (task != null && task.state == "pending" && task.dueDate == today) {
                    tasks.add(task.copy(fileName = fileName, lineIndex = index))
                }
            }
        }
        tasks
    } catch (e: Exception) {
        e.printStackTrace()
        return emptyList()
    }
}

private fun readFile(context: Context, uri: Uri): String {
    val stringBuilder = StringBuilder()
    try {
        context.contentResolver.openInputStream(uri)?.use { inputStream ->
            BufferedReader(InputStreamReader(inputStream, StandardCharsets.UTF_8)).use { reader ->
                var line: String? = reader.readLine()
                while (line != null) {
                    stringBuilder.append(line).append('\n')
                    line = reader.readLine()
                }
            }
        }
    } catch (e: IOException) {
        e.printStackTrace()
    }
    return stringBuilder.toString()
}

private fun updateTaskLine(
    context: Context,
    fileName: String,
    lineIndex: Int,
    body: String,
    dueDate: String,
    urgent: Boolean,
    nextState: String,
    nextDueDate: String?,
) {
    val folderUri = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .getString(FOLDER_URI_KEY, null)
        ?: return

    val root = DocumentFile.fromTreeUri(context, Uri.parse(folderUri)) ?: return
    val file = root.findFile(fileName) ?: return

    try {
        val content = readFile(context, file.uri)
        val lines = content.lines().toMutableList()
        if (lineIndex >= 0 && lineIndex < lines.size) {
            lines[lineIndex] = serializeTaskLine(nextState, body, nextDueDate, urgent)
            val newContent = lines.joinToString("\n")
            context.contentResolver.openFileDescriptor(file.uri, "w")?.use { pfd ->
                FileOutputStream(pfd.fileDescriptor).use { fos ->
                    fos.write(newContent.toByteArray(StandardCharsets.UTF_8))
                }
            }
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }
}
