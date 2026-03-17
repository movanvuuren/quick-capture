<script setup lang="ts">
import { CalendarDays, ChevronLeft, ChevronRight } from 'lucide-vue-next'
import { computed, onActivated, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { parseFrontmatter } from '../lib/lists'
import { parseTaskLine as parseSharedTaskLine } from '../lib/taskLine'
import { FolderPicker } from '../plugins/folder-picker'
import { loadSettings } from '../lib/settings'

const DAY_HEADERS = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat']
const HABIT_CONFIGS_DIR = 'habits'

type TaskState = 'pending' | 'done' | 'cancelled'

interface AgendaTask {
  id: string
  fileName: string
  lineIndex: number
  body: string
  state: TaskState
  dueDate: string
  isHighPriority: boolean
}

interface CalendarCell {
  date: string
  day: number
  isToday: boolean
  isFuture: boolean
  pendingCount: number
  scheduledHabitCount: number
  isPlaceholder: boolean
}

interface HabitSchedule {
  fileName: string
  id: string
  name: string
  icon: string
  targetCount: number
  scheduledDays: number[]
}

type HabitLogValue = number | 'skip' | 'fail'

const router = useRouter()
const settings = loadSettings()

const viewYear = ref(new Date().getFullYear())
const viewMonth = ref(new Date().getMonth()) // 0-indexed
const selectedDate = ref(todayIso())
const allTasks = ref<AgendaTask[]>([])
const habitSchedules = ref<HabitSchedule[]>([])
const habitLogValues = ref<Record<string, Record<string, HabitLogValue>>>({})
const loadedHabitLogMonths = ref<Record<string, boolean>>({})
const isLoading = ref(false)
const error = ref('')
let activeAgendaLoadPromise: Promise<void> | null = null
const AGENDA_REFRESH_COOLDOWN_MS = 1000
let lastAgendaRefreshAt = 0

function todayIso(): string {
  const now = new Date()
  return `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}-${String(now.getDate()).padStart(2, '0')}`
}

function dateToIso(year: number, month: number, day: number): string {
  return `${year}-${String(month + 1).padStart(2, '0')}-${String(day).padStart(2, '0')}`
}

function toWeekdayIndex(date: Date): number {
  const day = date.getDay()
  return day === 0 ? 7 : day
}

function parseScheduledDays(value: unknown): number[] {
  if (Array.isArray(value)) {
    const parsed = value
      .map(v => Number(v))
      .filter(v => Number.isInteger(v) && v >= 1 && v <= 7)
    return parsed.length > 0 ? parsed : [1, 2, 3, 4, 5, 6, 7]
  }

  if (typeof value === 'string') {
    const parsed = value
      .replace(/^\[/, '')
      .replace(/\]$/, '')
      .split(',')
      .map(v => Number(v.trim()))
      .filter(v => Number.isInteger(v) && v >= 1 && v <= 7)
    return parsed.length > 0 ? parsed : [1, 2, 3, 4, 5, 6, 7]
  }

  return [1, 2, 3, 4, 5, 6, 7]
}

function getHabitLogStem(habit: HabitSchedule): string {
  const base = (habit.id || habit.fileName || '').replace(/\.md$/i, '').trim().toLowerCase()
  const normalized = base
    .replace(/[^a-z0-9_-]+/g, '-')
    .replace(/-+/g, '-')
    .replace(/^-|-$/g, '')
  return normalized || 'habit'
}

function getHabitLogMonthFileName(habit: HabitSchedule, monthIso: string): string {
  return `habit-logs/${getHabitLogStem(habit)}-${monthIso}.md`
}

function normalizeHabitLogValue(raw: unknown): HabitLogValue | null {
  if (raw === 'skip' || raw === '*')
    return 'skip'
  if (raw === 'fail' || raw === '!')
    return 'fail'

  const numeric = Number(raw)
  if (Number.isFinite(numeric) && numeric >= 0)
    return Math.floor(numeric)

  return null
}

function escapeForRegex(value: string): string {
  return value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
}

function stripKnownTaskTags(body: string): string {
  let result = body

  for (const preset of settings.quickTaskPresets) {
    const tag = preset.tag?.trim()
    if (!tag)
      continue

    const tagPattern = new RegExp(`(^|\\s)${escapeForRegex(tag)}(?=\\s|$)`, 'g')
    result = result.replace(tagPattern, ' ')
  }

  return result.replace(/\s{2,}/g, ' ').trim()
}

function getPresetForTask(body: string, fileName: string) {
  const taggedPreset = settings.quickTaskPresets.find((preset) => {
    const tag = preset.tag?.trim()
    return tag ? body.includes(tag) : false
  })

  if (taggedPreset)
    return taggedPreset

  return settings.quickTaskPresets.find((preset) => {
    if (preset.saveMode === 'single_file') {
      const name = preset.fileName || settings.listFileName || 'tasks.md'
      return name === fileName
    }
    return false
  })
}

function isDatedTaskFileName(fileName: string): boolean {
  return /^tasks?-\d{4}-\d{2}-\d{2}\.md$/i.test(fileName.trim())
}

function parseTaskLine(line: string): Omit<AgendaTask, 'id' | 'fileName' | 'lineIndex'> | null {
  const parsed = parseSharedTaskLine(line)
  if (!parsed)
    return null

  const dueDate = parsed.dueDate
  if (!dueDate)
    return null

  const displayBody = stripKnownTaskTags(
    parsed.body
      .replace(/\s*📅\s*\d{4}-\d{2}-\d{2}\s*/g, '')
      .replace(/\s*🔁\s*(daily|weekly|weekdays|monthly)\s*/gi, '')
      .trim(),
  )

  return {
    body: displayBody,
    state: parsed.state as TaskState,
    dueDate,
    isHighPriority: Boolean(parsed.isHighPriority),
  }
}

async function loadAgendaData() {
  if (!settings.baseFolderUri)
    return

  const folderUri = settings.baseFolderUri

  if (activeAgendaLoadPromise)
    return activeAgendaLoadPromise

  activeAgendaLoadPromise = (async () => {
    isLoading.value = true
    try {
      const listed = await FolderPicker.listFiles({ folderUri })
      const taskMdFiles = listed.files.filter(file => file.isFile && file.name.toLowerCase().endsWith('.md'))
      const listedHabits = await FolderPicker.listFiles({
        folderUri,
        relativePath: HABIT_CONFIGS_DIR,
      })
      const habitMdFiles = listedHabits.files.filter(file => file.isFile && file.name.toLowerCase().endsWith('.md'))
      const tasks: AgendaTask[] = []
      const schedules: HabitSchedule[] = []

      for (const file of taskMdFiles) {
        try {
          const read = await FolderPicker.readFile({
            folderUri,
            fileName: file.name,
          })

          const frontmatter = parseFrontmatter(read.content)
          const isTaskFileByType = frontmatter.type === 'task'
          const isTaskFileByName = isDatedTaskFileName(file.name)

          const lines = read.content.split(/\r?\n/)
          for (let lineIndex = 0; lineIndex < lines.length; lineIndex += 1) {
            const rawLine = lines[lineIndex] || ''
            const parsed = parseTaskLine(rawLine)
            if (!parsed)
              continue

            const isPresetTask = Boolean(getPresetForTask(rawLine, file.name))
            if (!isTaskFileByType && !isTaskFileByName && !isPresetTask)
              continue

            tasks.push({
              id: `${file.name}:${lineIndex}`,
              fileName: file.name,
              lineIndex,
              ...parsed,
            })
          }
        }
        catch (fileErr) {
          console.warn('Agenda: failed to read file', file.name, fileErr)
        }
      }

      for (const file of habitMdFiles) {
        const habitFilePath = `${HABIT_CONFIGS_DIR}/${file.name}`
        try {
          const read = await FolderPicker.readFile({
            folderUri,
            fileName: habitFilePath,
          })

          const frontmatter = parseFrontmatter(read.content)
          if (frontmatter.type !== 'habit')
            continue

          schedules.push({
            fileName: habitFilePath,
            id: typeof frontmatter.id === 'string' && frontmatter.id.trim()
              ? frontmatter.id.trim()
              : file.name.replace(/\.md$/i, ''),
            name: typeof frontmatter.name === 'string' && frontmatter.name.trim()
              ? frontmatter.name.trim()
              : file.name.replace(/\.md$/i, ''),
            icon: typeof frontmatter.icon === 'string' && frontmatter.icon.trim()
              ? frontmatter.icon.trim()
              : '✓',
            targetCount: Number.isFinite(Number(frontmatter.targetCount))
              ? Math.max(1, Math.floor(Number(frontmatter.targetCount)))
              : 1,
            scheduledDays: parseScheduledDays(frontmatter.scheduledDays),
          })
        }
        catch (fileErr) {
          console.warn('Agenda: failed to read habit file', habitFilePath, fileErr)
        }
      }

      allTasks.value = tasks
      habitSchedules.value = schedules
      loadedHabitLogMonths.value = {}
    }
    catch (err) {
      console.error('Agenda: failed to load tasks', err)
      error.value = 'Could not load tasks.'
    }
    finally {
      isLoading.value = false
      activeAgendaLoadPromise = null
    }
  })()

  return activeAgendaLoadPromise
}

async function loadHabitLogsForMonth(monthIso: string) {
  if (!settings.baseFolderUri || !monthIso || loadedHabitLogMonths.value[monthIso])
    return

  loadedHabitLogMonths.value = {
    ...loadedHabitLogMonths.value,
    [monthIso]: true,
  }

  const folderUri = settings.baseFolderUri

  for (const habit of habitSchedules.value) {
    const logFileName = getHabitLogMonthFileName(habit, monthIso)
    try {
      const read = await FolderPicker.readFile({
        folderUri,
        fileName: logFileName,
      })

      const frontmatter = parseFrontmatter(read.content)
      if (frontmatter.type !== 'habit-log')
        continue

      const parsedMonth = typeof frontmatter.month === 'string' ? frontmatter.month : ''
      if (parsedMonth !== monthIso)
        continue

      const next = { ...(habitLogValues.value[habit.fileName] ?? {}) }
      for (const [key, raw] of Object.entries(frontmatter)) {
        const match = key.match(/^d(\d{2})$/)
        if (!match)
          continue

        const value = normalizeHabitLogValue(raw)
        if (value === null)
          continue

        const day = match[1]
        next[`${monthIso}-${day}`] = value
      }

      habitLogValues.value = {
        ...habitLogValues.value,
        [habit.fileName]: next,
      }
    }
    catch {
      // Missing monthly log files are expected for untouched months.
    }
  }
}

async function refreshAgendaFromExternal(force = false) {
  const now = Date.now()
  if (!force && now - lastAgendaRefreshAt < AGENDA_REFRESH_COOLDOWN_MS)
    return

  lastAgendaRefreshAt = now
  await loadAgendaData()
}

function handleVisibilityChange() {
  if (document.visibilityState === 'visible')
    void refreshAgendaFromExternal()
}

function handleWindowFocus() {
  void refreshAgendaFromExternal()
}

onMounted(async () => {
  document.addEventListener('visibilitychange', handleVisibilityChange)
  window.addEventListener('focus', handleWindowFocus)
  await loadAgendaData()
  await loadHabitLogsForMonth(selectedDate.value.slice(0, 7))
})

onActivated(() => {
  void (async () => {
    await refreshAgendaFromExternal(true)
    await loadHabitLogsForMonth(selectedDate.value.slice(0, 7))
  })()
})

onBeforeUnmount(() => {
  document.removeEventListener('visibilitychange', handleVisibilityChange)
  window.removeEventListener('focus', handleWindowFocus)
})

watch(selectedDate, (value) => {
  if (!value)
    return
  void loadHabitLogsForMonth(value.slice(0, 7))
})

const monthLabel = computed(() =>
  new Intl.DateTimeFormat(undefined, { month: 'long', year: 'numeric' })
    .format(new Date(viewYear.value, viewMonth.value, 1)),
)

const calendarCells = computed<CalendarCell[]>(() => {
  const today = todayIso()
  const year = viewYear.value
  const month = viewMonth.value
  const firstDay = new Date(year, month, 1).getDay()
  const daysInMonth = new Date(year, month + 1, 0).getDate()

  const taskCountByDate = new Map<string, number>()
  for (const task of allTasks.value) {
    if (!task.dueDate || task.state !== 'pending')
      continue
    const count = taskCountByDate.get(task.dueDate) ?? 0
    taskCountByDate.set(task.dueDate, count + 1)
  }

  const cells: CalendarCell[] = []

  for (let i = 0; i < firstDay; i++) {
    cells.push({
      date: `placeholder-${i}`,
      day: 0,
      isToday: false,
      isFuture: false,
      pendingCount: 0,
      scheduledHabitCount: 0,
      isPlaceholder: true,
    })
  }

  for (let day = 1; day <= daysInMonth; day++) {
    const iso = dateToIso(year, month, day)
    const date = new Date(year, month, day)
    const weekday = toWeekdayIndex(date)

    if (iso !== selectedDate.value) {
      cells.push({
        date: iso,
        day,
        isToday: iso === today,
        isFuture: iso > today,
        pendingCount: taskCountByDate.get(iso) ?? 0,
        scheduledHabitCount: 0,
        isPlaceholder: false,
      })
      continue
    }

    const scheduledHabitCount = habitSchedules.value.filter((habit) => {
      return habit.scheduledDays.includes(weekday)
    }).length

    cells.push({
      date: iso,
      day,
      isToday: iso === today,
      isFuture: iso > today,
      pendingCount: taskCountByDate.get(iso) ?? 0,
      scheduledHabitCount,
      isPlaceholder: false,
    })
  }

  const remainder = cells.length % 7
  if (remainder > 0) {
    for (let i = 0; i < 7 - remainder; i++) {
      cells.push({
        date: `trailing-${i}`,
        day: 0,
        isToday: false,
        isFuture: false,
        pendingCount: 0,
        scheduledHabitCount: 0,
        isPlaceholder: true,
      })
    }
  }

  return cells
})

const tasksForSelectedDate = computed(() => {
  if (!selectedDate.value)
    return []
  return allTasks.value
    .filter(t => t.dueDate === selectedDate.value && t.state === 'pending')
})

const overdueTasksForToday = computed(() => {
  if (selectedDate.value !== todayIso())
    return []
  const today = todayIso()
  return allTasks.value
    .filter(t => t.dueDate && t.dueDate < today && t.state === 'pending')
})

const incompleteHabitsForSelectedDate = computed(() => {
  if (!selectedDate.value)
    return []

  const selected = new Date(`${selectedDate.value}T00:00:00`)
  const weekday = toWeekdayIndex(selected)

  return habitSchedules.value
    .filter(habit => habit.scheduledDays.includes(weekday))
    .filter((habit) => {
      const value = habitLogValues.value[habit.fileName]?.[selectedDate.value]
      if (typeof value !== 'number')
        return true
      return value < habit.targetCount
    })
    .map((habit) => {
      const value = habitLogValues.value[habit.fileName]?.[selectedDate.value]
      return {
        ...habit,
        currentValue: typeof value === 'number' ? value : 0,
      }
    })
})

function prevMonth() {
  if (viewMonth.value === 0) {
    viewMonth.value = 11
    viewYear.value -= 1
  }
  else {
    viewMonth.value -= 1
  }
}

function nextMonth() {
  if (viewMonth.value === 11) {
    viewMonth.value = 0
    viewYear.value += 1
  }
  else {
    viewMonth.value += 1
  }
}

function selectDay(cell: CalendarCell) {
  if (cell.isPlaceholder)
    return
  selectedDate.value = cell.date
}

function isSelected(cell: CalendarCell) {
  return !cell.isPlaceholder && cell.date === selectedDate.value
}

function selectedDateLabel() {
  if (!selectedDate.value)
    return ''
  if (selectedDate.value === todayIso())
    return 'Today'

  return new Intl.DateTimeFormat(undefined, { weekday: 'long', month: 'long', day: 'numeric' })
    .format(new Date(`${selectedDate.value}T00:00:00`))
}

function openTaskFile(task: AgendaTask) {
  router.push({
    name: 'tasks',
    query: {
      highlight: task.id,
      pulse: String(Date.now()),
    },
  })
}

function openHabitForDate(habit: HabitSchedule) {
  if (!selectedDate.value)
    return

  router.push({
    path: '/habits',
    query: {
      habit: habit.fileName,
      date: selectedDate.value,
      pulse: String(Date.now()),
    },
  })
}

function goBack() {
  router.back()
}
</script>

<template>
  <div class="page">
    <div class="header">
      <button class="glass-icon-button back-button" aria-label="Go back" @click="goBack">
        ←
      </button>
      <h1>Agenda</h1>
    </div>

    <div v-if="isLoading" class="loading-inline">
      <div class="loading-spinner" aria-hidden="true" />
    </div>

    <div v-else-if="error" class="card glass-card empty-state">
      <p class="error-text">{{ error }}</p>
    </div>

    <div v-else-if="!settings.baseFolderUri" class="card glass-card empty-state">
      <p class="empty-title">No folder selected</p>
      <p class="empty-text">Choose a folder in Settings first.</p>
    </div>

    <template v-else>
      <!-- Calendar card -->
      <div class="card glass-card calendar-card">
        <div class="month-nav">
          <button class="glass-icon-button nav-btn" aria-label="Previous month" @click="prevMonth">
            <ChevronLeft :size="16" />
          </button>
          <span class="month-label">{{ monthLabel }}</span>
          <button class="glass-icon-button nav-btn" aria-label="Next month" @click="nextMonth">
            <ChevronRight :size="16" />
          </button>
        </div>

        <div class="day-headers">
          <span v-for="h in DAY_HEADERS" :key="h" class="day-header">{{ h }}</span>
        </div>

        <div class="calendar-grid">
          <button v-for="cell in calendarCells" :key="cell.date" class="day-cell" :class="{
            'is-placeholder': cell.isPlaceholder,
            'is-today': cell.isToday,
            'is-selected': isSelected(cell),
            'is-future': cell.isFuture,
            'has-tasks': cell.pendingCount > 0,
            'has-habits': cell.scheduledHabitCount > 0,
          }" :disabled="cell.isPlaceholder"
            :aria-label="cell.isPlaceholder ? undefined : `${cell.date}${cell.pendingCount > 0 ? `, ${cell.pendingCount} pending tasks` : ''}${cell.scheduledHabitCount > 0 ? `, ${cell.scheduledHabitCount} habits scheduled` : ''}`"
            @click="selectDay(cell)">
            <span v-if="!cell.isPlaceholder" class="day-number">{{ cell.day }}</span>
            <span v-if="cell.pendingCount > 0 || cell.scheduledHabitCount > 0" class="day-dots" :aria-hidden="true">
              <span v-if="cell.pendingCount > 0" class="task-dot" />
              <span v-if="cell.scheduledHabitCount > 0" class="habit-dot" />
            </span>
          </button>
        </div>
      </div>

      <!-- Overdue for today -->
      <div v-if="overdueTasksForToday.length > 0" class="card glass-card overdue-card">
        <h2 class="section-header overdue-header">
          Overdue
        </h2>
        <button v-for="task in overdueTasksForToday" :key="task.id" class="task-item task-item--overdue"
          @click="openTaskFile(task)">
          <span class="task-body">{{ task.body }}</span>
          <span class="task-due">{{ task.dueDate }}</span>
        </button>
      </div>

      <!-- Selected day tasks -->
      <div class="card glass-card day-tasks-card">
        <h2 class="section-header">
          <CalendarDays :size="14" class="section-icon" />
          {{ selectedDateLabel() }}
        </h2>
        <div v-if="tasksForSelectedDate.length === 0" class="empty-day">
          <p class="empty-text">No pending tasks due.</p>
        </div>
        <button v-for="task in tasksForSelectedDate" :key="task.id" class="task-item" @click="openTaskFile(task)">
          <span v-if="task.isHighPriority" class="priority-dot" aria-label="High priority" />
          <span class="task-body">{{ task.body }}</span>
        </button>
      </div>

      <div class="card glass-card day-habits-card">
        <h2 class="section-header">
          Habits Not Complete
        </h2>
        <div v-if="incompleteHabitsForSelectedDate.length === 0" class="empty-day">
          <p class="empty-text">All scheduled habits complete.</p>
        </div>
        <button v-for="habit in incompleteHabitsForSelectedDate" :key="habit.fileName" class="habit-item"
          @click="openHabitForDate(habit)">
          <span class="habit-name">{{ habit.icon }} {{ habit.name }}</span>
          <span class="habit-progress">{{ habit.currentValue }}/{{ habit.targetCount }}</span>
        </button>
      </div>
    </template>
  </div>
</template>

<style scoped>
.page {
  min-height: 100vh;
  padding: var(--page-top-padding) 20px 36px;
  color: var(--text);
}

.header {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 18px;
}

h1 {
  margin: 0;
  font-size: 1.75rem;
}

.glass-card {
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: 20px;
  backdrop-filter: blur(14px) saturate(1.2);
  -webkit-backdrop-filter: blur(14px) saturate(1.2);
  box-shadow: var(--shadow);
}

.card {
  padding: 14px;
  margin-bottom: 12px;
}

.loading-inline {
  display: flex;
  justify-content: center;
  padding: 40px 0;
}

.loading-spinner {
  width: 20px;
  height: 20px;
  border-radius: 999px;
  border: 2px solid color-mix(in srgb, var(--text-soft) 22%, transparent);
  border-top-color: color-mix(in srgb, var(--primary) 72%, var(--text));
  animation: spin 0.75s linear infinite;
}

@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}

.empty-state {
  text-align: center;
  padding: 28px 16px;
}

.empty-title {
  font-weight: 600;
  margin: 0 0 6px;
}

.empty-text {
  color: var(--text-soft);
  margin: 0;
  font-size: 0.9rem;
}

.error-text {
  color: var(--danger);
}

/* Calendar */
.calendar-card {
  padding: 12px;
}

.month-nav {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 10px;
}

.month-label {
  font-weight: 600;
  font-size: 0.95rem;
}

.nav-btn {
  background: none;
  border: 1px solid var(--border);
  border-radius: 10px;
  width: 34px;
  height: 34px;
  display: grid;
  place-items: center;
  cursor: pointer;
  color: var(--text);
}

.day-headers {
  display: grid;
  grid-template-columns: repeat(7, 1fr);
  margin-bottom: 4px;
}

.day-header {
  text-align: center;
  font-size: 0.72rem;
  font-weight: 600;
  color: var(--text-soft);
  padding: 2px 0;
}

.calendar-grid {
  display: grid;
  grid-template-columns: repeat(7, 1fr);
  gap: 3px;
}

.day-cell {
  position: relative;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  aspect-ratio: 1;
  border-radius: 10px;
  border: none;
  background: none;
  cursor: pointer;
  color: var(--text);
  font-size: 0.82rem;
  transition: background 0.1s;
  gap: 2px;
}

.day-cell:disabled {
  pointer-events: none;
}

.day-cell:not(.is-placeholder):not(.is-selected):hover {
  background: color-mix(in srgb, var(--primary) 10%, transparent);
}

.day-cell.is-today .day-number {
  background: var(--primary);
  color: #fff;
  border-radius: 999px;
  width: 24px;
  height: 24px;
  display: grid;
  place-items: center;
}

.day-cell.is-selected:not(.is-today) {
  background: color-mix(in srgb, var(--primary) 14%, transparent);
}

.day-cell.is-selected.is-today {
  background: color-mix(in srgb, var(--primary) 20%, transparent);
}

.day-cell.is-future {
  color: var(--text-soft);
}

.day-cell.is-placeholder {
  opacity: 0;
}

.day-number {
  line-height: 1;
  font-size: 0.82rem;
}

.day-dots {
  display: inline-flex;
  align-items: center;
  gap: 3px;
}

.task-dot {
  width: 5px;
  height: 5px;
  border-radius: 999px;
  background: var(--primary);
  opacity: 0.85;
}

.habit-dot {
  width: 5px;
  height: 5px;
  border-radius: 999px;
  background: color-mix(in srgb, var(--success) 78%, var(--text));
  opacity: 0.85;
}

/* Day task list */
.section-header {
  font-size: 0.82rem;
  font-weight: 600;
  color: var(--text-soft);
  text-transform: uppercase;
  letter-spacing: 0.06em;
  margin: 0 0 10px;
  display: flex;
  align-items: center;
  gap: 6px;
}

.section-icon {
  opacity: 0.7;
}

.overdue-header {
  color: var(--danger);
}

.overdue-card {
  border-color: color-mix(in srgb, var(--danger) 20%, var(--border));
  padding: 12px 14px;
}

.day-tasks-card {
  padding: 12px 14px;
}

.day-habits-card {
  padding: 12px 14px;
}

.empty-day {
  padding: 8px 0 4px;
}

.task-item {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  width: 100%;
  padding: 9px 0;
  border: none;
  border-bottom: 1px solid var(--border);
  background: none;
  cursor: pointer;
  text-align: left;
  color: var(--text);
  font-size: 0.9rem;
}

.task-item:last-child {
  border-bottom: none;
}

.task-item:active {
  opacity: 0.7;
}

.task-item--overdue .task-body {
  color: var(--danger);
}

.priority-dot {
  flex-shrink: 0;
  margin-top: 4px;
  width: 7px;
  height: 7px;
  border-radius: 999px;
  background: var(--danger);
}

.task-body {
  flex: 1;
  line-height: 1.4;
}

.task-due {
  flex-shrink: 0;
  font-size: 0.78rem;
  color: var(--danger);
  opacity: 0.8;
}

.habit-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  width: 100%;
  padding: 9px 0;
  border-bottom: 1px solid var(--border);
  border-left: none;
  border-right: none;
  border-top: none;
  background: none;
  color: var(--text);
  text-align: left;
  cursor: pointer;
}

.habit-item:last-child {
  border-bottom: none;
}

.habit-name {
  flex: 1;
  line-height: 1.4;
}

.habit-progress {
  flex-shrink: 0;
  font-size: 0.82rem;
  color: var(--text-soft);
}

.back-button {
  flex-shrink: 0;
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: 12px;
  width: 40px;
  height: 40px;
  display: grid;
  place-items: center;
  cursor: pointer;
  color: var(--text);
}
</style>
