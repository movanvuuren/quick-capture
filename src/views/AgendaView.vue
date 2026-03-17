<script setup lang="ts">
import { CalendarDays, ChevronLeft, ChevronRight } from 'lucide-vue-next'
import { computed, onActivated, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { parseFrontmatter } from '../lib/lists'
import { FolderPicker } from '../plugins/folder-picker'
import { loadSettings } from '../lib/settings'

const DAY_HEADERS = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat']

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
  scheduledDays: number[]
}

const router = useRouter()
const settings = loadSettings()

const viewYear = ref(new Date().getFullYear())
const viewMonth = ref(new Date().getMonth()) // 0-indexed
const selectedDate = ref(todayIso())
const allTasks = ref<AgendaTask[]>([])
const habitSchedules = ref<HabitSchedule[]>([])
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

function parseTaskLine(line: string): Omit<AgendaTask, 'id' | 'fileName' | 'lineIndex'> | null {
  const match = line.match(/^\s*-\s\[([fFxX-\s]*?)\]\s+(.*)$/)
  if (!match)
    return null

  const [, markerRaw = '', rawBody = ''] = match
  const body = rawBody.trim()
  const dueDate = body.match(/📅\s*(\d{4}-\d{2}-\d{2})/)?.[1]
  if (!dueDate)
    return null

  const markerStr = markerRaw.toLowerCase()
  const isHighPriority = markerStr.includes('f')
  const stateChar = markerStr.replace(/f/g, '').trim() || ' '

  let state: TaskState = 'pending'
  if (stateChar === 'x')
    state = 'done'
  else if (stateChar === '-')
    state = 'cancelled'

  const displayBody = stripKnownTaskTags(
    body
      .replace(/\s*📅\s*\d{4}-\d{2}-\d{2}\s*/g, '')
      .replace(/\s*🔁\s*(daily|weekly|weekdays|monthly)\s*/gi, '')
      .trim(),
  )

  return {
    body: displayBody,
    state,
    dueDate,
    isHighPriority,
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
      const mdFiles = listed.files.filter(file => file.isFile && file.name.toLowerCase().endsWith('.md'))
      const tasks: AgendaTask[] = []
      const schedules: HabitSchedule[] = []

      for (const file of mdFiles) {
        try {
          const read = await FolderPicker.readFile({
            folderUri,
            fileName: file.name,
          })

          const frontmatter = parseFrontmatter(read.content)
          if (frontmatter.type === 'habit') {
            schedules.push({
              fileName: file.name,
              scheduledDays: parseScheduledDays(frontmatter.scheduledDays),
            })
          }

          const lines = read.content.split(/\r?\n/)
          for (let lineIndex = 0; lineIndex < lines.length; lineIndex += 1) {
            const parsed = parseTaskLine(lines[lineIndex] || '')
            if (!parsed)
              continue

            if (!getPresetForTask(parsed.body, file.name))
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

      allTasks.value = tasks
      habitSchedules.value = schedules
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
})

onActivated(() => {
  void refreshAgendaFromExternal(true)
})

onBeforeUnmount(() => {
  document.removeEventListener('visibilitychange', handleVisibilityChange)
  window.removeEventListener('focus', handleWindowFocus)
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
    const scheduledHabitCount = habitSchedules.value.filter((habit) => {
      if (habit.scheduledDays.length === 0)
        return true
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
