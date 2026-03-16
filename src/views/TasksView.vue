<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { AlarmClock, AlertTriangle, ArrowUpDown, CalendarDays, Check, Filter, LayoutGrid, ListChecks, Square, Trash2 } from 'lucide-vue-next'
import OptionSwitcher from '../components/OptionSwitcher.vue'
import { Capacitor } from '@capacitor/core'
import { LocalNotifications } from '@capacitor/local-notifications'
import { FolderPicker } from '../plugins/folder-picker'
import type { FolderFileEntry } from '../plugins/folder-picker'
import { loadSettings } from '../lib/settings'
import type { QuickTaskPreset } from '../lib/settings'
import { parseFrontmatter } from '../lib/lists'

type TaskState = 'pending' | 'done' | 'cancelled'
type TaskFilter = 'all' | TaskState | 'overdue' | 'closed'
type TaskSortMode = 'status' | 'due'

interface ParsedTaskLine {
  state: TaskState
  body: string
  dueDate?: string
}

interface QuickTaskItem {
  id: string
  fileName: string
  lineIndex: number
  state: TaskState
  body: string
  dueDate?: string
  presetId?: string
  presetLabel?: string
}

interface ScannedQuickTaskLine {
  lineIndex: number
  state: TaskState
  body: string
  dueDate?: string
}

interface CachedQuickTaskFile {
  signature: string
  tasks: ScannedQuickTaskLine[]
}

const QUICK_TASK_READ_CONCURRENCY = 6
const TASK_REMINDER_STORAGE_KEY = 'quick-capture-task-reminders'

let quickTaskCacheFolderUri = ''
const quickTaskFileCache = new Map<string, CachedQuickTaskFile>()

const router = useRouter()
const route = useRoute()
const settings = loadSettings()

function isPinataPreset(preset: QuickTaskPreset): boolean {
  return preset.tag.includes('🪅') || preset.label.includes('🪅')
}

function getDefaultPresetId(): string {
  const pinataPreset = settings.quickTaskPresets.find(preset => isPinataPreset(preset))
  return pinataPreset?.id || settings.quickTaskPresets[0]?.id || ''
}

function orderedPresets(): QuickTaskPreset[] {
  return [...settings.quickTaskPresets].sort((a, b) => {
    const aPriority = isPinataPreset(a) ? 0 : 1
    const bPriority = isPinataPreset(b) ? 0 : 1
    return aPriority - bPriority
  })
}

const isLoading = ref(true)
const isSaving = ref(false)
const error = ref('')

const tasks = ref<QuickTaskItem[]>([])
const newTaskText = ref('')
const newDueDate = ref('')
const selectedPresetId = ref(getDefaultPresetId())
const selectedTaskFilter = ref<TaskFilter>('pending')
const selectedSortMode = ref<TaskSortMode>('status')
const editingTaskId = ref<string | null>(null)
const editingTaskText = ref('')
const editingDueDateTaskId = ref<string | null>(null)
const editingAlarmTaskId = ref<string | null>(null)
const reminderTimes = ref<Record<string, string>>({})
const highlightedTaskId = ref<string | null>(null)
let highlightClearTimer: ReturnType<typeof setTimeout> | null = null

const taskStateOrder: Record<TaskState, number> = {
  pending: 0,
  done: 1,
  cancelled: 2,
}

const selectedPreset = computed(() =>
  settings.quickTaskPresets.find(preset => preset.id === selectedPresetId.value),
)

const presetOptions = computed(() =>
  orderedPresets().map(preset => ({
    value: preset.id,
    label: preset.label?.trim() || 'Preset',
  })),
)

function isOverdue(task: QuickTaskItem): boolean {
  if (!task.dueDate)
    return false
  if (task.state !== 'pending')
    return false

  return task.dueDate < todayIso()
}

const presetTasks = computed(() => {
  if (!selectedPresetId.value)
    return [...tasks.value]

  return tasks.value.filter(task => task.presetId === selectedPresetId.value)
})

const visibleTasks = computed(() => {
  let filteredTasks: QuickTaskItem[]

  if (selectedTaskFilter.value === 'all')
    filteredTasks = [...presetTasks.value]

  else if (selectedTaskFilter.value === 'overdue')
    filteredTasks = presetTasks.value.filter(task => isOverdue(task))

  else if (selectedTaskFilter.value === 'closed')
    filteredTasks = presetTasks.value.filter(task => task.state === 'done' || task.state === 'cancelled')

  else
    filteredTasks = presetTasks.value.filter(task => task.state === selectedTaskFilter.value)

  return filteredTasks.sort((a, b) => {
    if (selectedSortMode.value === 'due') {
      const aDue = a.dueDate || '9999-99-99'
      const bDue = b.dueDate || '9999-99-99'

      if (aDue !== bDue)
        return aDue.localeCompare(bDue)

      if (taskStateOrder[a.state] !== taskStateOrder[b.state])
        return taskStateOrder[a.state] - taskStateOrder[b.state]

      return a.fileName.localeCompare(b.fileName)
    }

    if (taskStateOrder[a.state] !== taskStateOrder[b.state])
      return taskStateOrder[a.state] - taskStateOrder[b.state]

    const aDue = a.dueDate || '9999-99-99'
    const bDue = b.dueDate || '9999-99-99'
    if (aDue !== bDue)
      return aDue.localeCompare(bDue)

    return a.fileName.localeCompare(b.fileName)
  })
})

const taskCounts = computed(() => {
  const pending = presetTasks.value.filter(task => task.state === 'pending').length
  const done = presetTasks.value.filter(task => task.state === 'done').length
  const cancelled = presetTasks.value.filter(task => task.state === 'cancelled').length
  const closed = done + cancelled
  const overdue = presetTasks.value.filter(task => isOverdue(task)).length

  return {
    pending,
    done,
    cancelled,
    closed,
    overdue,
    total: presetTasks.value.length,
  }
})

const taskFilterOptions = computed(() => {
  const options = [
    {
      value: 'all',
      label: '',
      count: taskCounts.value.total,
      icon: LayoutGrid,
    },
    {
      value: 'pending',
      label: '',
      count: taskCounts.value.pending,
      icon: Square,
    },
    {
      value: 'closed',
      label: '',
      count: taskCounts.value.closed,
      icon: Check,
    },
    {
      value: 'overdue',
      label: '',
      count: taskCounts.value.overdue,
      icon: AlertTriangle,
    },
  ]

  return options
    .filter(option => option.count > 0)
    .map(option => ({
      value: option.value,
      label: option.label,
      countLabel: String(option.count),
      icon: option.icon,
    }))
})

const taskSortOptions = [
  { value: 'status', label: 'By status', icon: ListChecks },
  { value: 'due', label: 'By due date', icon: CalendarDays },
]

function onTaskFilterChange(value: string) {
  if (value === 'all' || value === 'pending' || value === 'done' || value === 'cancelled' || value === 'closed' || value === 'overdue')
    selectedTaskFilter.value = value
}

function onTaskSortChange(value: string) {
  if (value === 'status' || value === 'due')
    selectedSortMode.value = value
}

watch(taskFilterOptions, (options) => {
  const selectedStillVisible = options.some(option => option.value === selectedTaskFilter.value)
  if (!selectedStillVisible)
    selectedTaskFilter.value = 'all'
})

function goBack() {
  router.push('/')
}

function todayIso(): string {
  const now = new Date()
  const year = now.getFullYear()
  const month = String(now.getMonth() + 1).padStart(2, '0')
  const day = String(now.getDate()).padStart(2, '0')
  return `${year}-${month}-${day}`
}

function loadReminderTimes() {
  try {
    const raw = window.localStorage.getItem(TASK_REMINDER_STORAGE_KEY)
    if (!raw) {
      reminderTimes.value = {}
      return
    }

    const parsed = JSON.parse(raw) as Record<string, string>
    reminderTimes.value = typeof parsed === 'object' && parsed !== null ? parsed : {}
  }
  catch {
    reminderTimes.value = {}
  }
}

function persistReminderTimes() {
  window.localStorage.setItem(TASK_REMINDER_STORAGE_KEY, JSON.stringify(reminderTimes.value))
}

function notificationIdForTask(task: QuickTaskItem): number {
  const source = `task-reminder:${task.fileName}:${task.lineIndex}`
  let hash = 0
  for (let i = 0; i < source.length; i += 1)
    hash = ((hash << 5) - hash + source.charCodeAt(i)) | 0

  return Math.abs(hash) || 1
}

function getReminderTime(task: QuickTaskItem): string {
  return reminderTimes.value[task.id] || ''
}

function hasReminder(task: QuickTaskItem): boolean {
  return !!getReminderTime(task)
}

function reminderDateFromTask(task: QuickTaskItem, time: string): Date | null {
  if (!task.dueDate || !time)
    return null

  const [hourRaw, minuteRaw] = time.split(':')
  const hour = Number.parseInt(hourRaw || '', 10)
  const minute = Number.parseInt(minuteRaw || '', 10)
  if (!Number.isInteger(hour) || !Number.isInteger(minute))
    return null

  const date = new Date(`${task.dueDate}T00:00:00`)
  if (Number.isNaN(date.getTime()))
    return null

  date.setHours(hour, minute, 0, 0)
  if (date.getTime() <= Date.now())
    return null

  return date
}

async function ensureNotificationPermission(): Promise<boolean> {
  if (!Capacitor.isNativePlatform())
    return true

  const status = await LocalNotifications.checkPermissions()
  if (status.display === 'granted')
    return true

  const requested = await LocalNotifications.requestPermissions()
  return requested.display === 'granted'
}

async function cancelReminderNotification(task: QuickTaskItem) {
  if (!Capacitor.isNativePlatform())
    return

  try {
    await LocalNotifications.cancel({
      notifications: [{ id: notificationIdForTask(task) }],
    })
  }
  catch {
    // Ignore cleanup errors to avoid blocking task operations.
  }
}

async function scheduleReminderNotification(task: QuickTaskItem, time: string) {
  if (!Capacitor.isNativePlatform())
    return

  const at = reminderDateFromTask(task, time)
  if (!at)
    throw new Error('Reminder date/time must be in the future')

  const hasPermission = await ensureNotificationPermission()
  if (!hasPermission)
    throw new Error('Notification permission not granted')

  const id = notificationIdForTask(task)
  await LocalNotifications.cancel({ notifications: [{ id }] })
  await LocalNotifications.schedule({
    notifications: [
      {
        id,
        title: 'Task reminder',
        body: displayTaskBody(task),
        schedule: { at },
        extra: { taskId: task.id },
      },
    ],
  })
}

async function clearTaskReminder(task: QuickTaskItem) {
  delete reminderTimes.value[task.id]
  persistReminderTimes()
  editingAlarmTaskId.value = editingAlarmTaskId.value === task.id ? null : editingAlarmTaskId.value
  await cancelReminderNotification(task)
}

async function setTaskReminderTime(task: QuickTaskItem, time: string) {
  const normalized = time.trim()
  if (!normalized) {
    await clearTaskReminder(task)
    return
  }

  await scheduleReminderNotification(task, normalized)
  reminderTimes.value = {
    ...reminderTimes.value,
    [task.id]: normalized,
  }
  persistReminderTimes()
}

function showAlarmEditor(task: QuickTaskItem) {
  if (!task.dueDate)
    return

  editingAlarmTaskId.value = task.id
}

function hideAlarmEditor(task: QuickTaskItem) {
  if (!hasReminder(task))
    editingAlarmTaskId.value = null
}

async function changeReminderTime(task: QuickTaskItem, value: string) {
  try {
    await setTaskReminderTime(task, value)
  }
  catch (err) {
    console.error('Failed to set reminder', err)
    error.value = 'Could not set reminder notification.'
  }
}

function parseTaskLine(line: string): ParsedTaskLine | null {
  const match = line.match(/^\s*-\s\[([ xX-])\]\s+(.*)$/)
  if (!match)
    return null

  const [, marker = ' ', rawBody = ''] = match
  const body = rawBody.trim()

  const dueMatch = body.match(/📅\s*(\d{4}-\d{2}-\d{2})/)
  const dueDate = dueMatch?.[1]

  let state: TaskState = 'pending'
  if (marker === 'x' || marker === 'X')
    state = 'done'
  else if (marker === '-')
    state = 'cancelled'

  return {
    state,
    body,
    dueDate,
  }
}

function getFileSignature(file: FolderFileEntry): string | null {
  if (typeof file.lastModified !== 'number' || typeof file.size !== 'number')
    return null

  return `${file.lastModified}:${file.size}`
}

async function mapWithConcurrency<T, R>(
  items: T[],
  concurrency: number,
  mapper: (item: T, index: number) => Promise<R>,
): Promise<R[]> {
  if (items.length === 0)
    return []

  const results = new Array<R>(items.length)
  const runnerCount = Math.max(1, Math.min(concurrency, items.length))
  let cursor = 0

  const workers = Array.from({ length: runnerCount }, async () => {
    while (true) {
      const index = cursor
      cursor += 1

      if (index >= items.length)
        return

      results[index] = await mapper(items[index] as T, index)
    }
  })

  await Promise.all(workers)
  return results
}

function scanQuickTaskLines(content: string): ScannedQuickTaskLine[] {
  const lines = content.split(/\r?\n/)
  const scanned: ScannedQuickTaskLine[] = []

  for (let lineIndex = 0; lineIndex < lines.length; lineIndex += 1) {
    const line = lines[lineIndex] || ''
    const parsed = parseTaskLine(line)
    if (!parsed)
      continue

    scanned.push({
      lineIndex,
      state: parsed.state,
      body: parsed.body,
      dueDate: parsed.dueDate,
    })
  }

  return scanned
}

function invalidateQuickTaskCache(fileName?: string) {
  if (fileName) {
    quickTaskFileCache.delete(fileName)
    return
  }

  quickTaskFileCache.clear()
}

function serializeTaskLine(state: TaskState, body: string, dueDate?: string): string {
  const marker = state === 'done' ? 'x' : state === 'cancelled' ? '-' : ' '

  let normalizedBody = body.replace(/\s*📅\s*\d{4}-\d{2}-\d{2}\s*/g, ' ').trim()
  if (dueDate)
    normalizedBody = `${normalizedBody} 📅 ${dueDate}`.trim()

  return `- [${marker}] ${normalizedBody}`
}

function getPresetForTask(body: string, fileName: string): QuickTaskPreset | undefined {
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

async function loadQuickTasks() {
  if (!settings.baseFolderUri) {
    tasks.value = []
    invalidateQuickTaskCache()
    isLoading.value = false
    return
  }

  const folderUri = settings.baseFolderUri

  if (quickTaskCacheFolderUri !== folderUri) {
    invalidateQuickTaskCache()
    quickTaskCacheFolderUri = folderUri
  }

  isLoading.value = true
  error.value = ''

  try {
    const listed = await FolderPicker.listFiles({ folderUri })
    const mdFiles = listed.files.filter(file => file.isFile && file.name.toLowerCase().endsWith('.md'))
    const activeNames = new Set(mdFiles.map(file => file.name))

    const scannedFiles = await mapWithConcurrency(
      mdFiles,
      QUICK_TASK_READ_CONCURRENCY,
      async (file) => {
        try {
          const signature = getFileSignature(file)
          const cached = quickTaskFileCache.get(file.name)

          if (signature && cached && cached.signature === signature) {
            return {
              fileName: file.name,
              tasks: cached.tasks.map(task => ({ ...task })),
            }
          }

          const read = await FolderPicker.readFile({
            folderUri,
            fileName: file.name,
          })

          const scanned = scanQuickTaskLines(read.content)

          if (signature) {
            quickTaskFileCache.set(file.name, {
              signature,
              tasks: scanned,
            })
          }
          else {
            quickTaskFileCache.delete(file.name)
          }

          return {
            fileName: file.name,
            tasks: scanned.map(task => ({ ...task })),
          }
        }
        catch (fileErr) {
          console.warn('Failed to scan task file', file.name, fileErr)
          return null
        }
      },
    )

    const loadedTasks: QuickTaskItem[] = []

    for (const scannedFile of scannedFiles) {
      if (!scannedFile)
        continue

      for (const task of scannedFile.tasks) {
        const preset = getPresetForTask(task.body, scannedFile.fileName)
        if (!preset)
          continue

        loadedTasks.push({
          id: `${scannedFile.fileName}:${task.lineIndex}`,
          fileName: scannedFile.fileName,
          lineIndex: task.lineIndex,
          state: task.state,
          body: task.body,
          dueDate: task.dueDate,
          presetId: preset.id,
          presetLabel: preset.label,
        })
      }
    }

    for (const cachedName of Array.from(quickTaskFileCache.keys())) {
      if (!activeNames.has(cachedName))
        quickTaskFileCache.delete(cachedName)
    }

    tasks.value = loadedTasks
  }
  catch (err) {
    console.error('Failed to load quick tasks', err)
    error.value = 'Failed to load tasks from your folder.'
  }
  finally {
    isLoading.value = false
  }
}

function cycleState(state: TaskState): TaskState {
  if (state === 'pending')
    return 'done'
  if (state === 'done')
    return 'cancelled'
  return 'pending'
}

async function updateTaskInFile(
  task: QuickTaskItem,
  nextState: TaskState,
  nextDueDate?: string,
  nextBody?: string,
) {
  if (!settings.baseFolderUri)
    return

  const read = await FolderPicker.readFile({
    folderUri: settings.baseFolderUri,
    fileName: task.fileName,
  })

  const lines = read.content.split(/\r?\n/)
  if (task.lineIndex < 0 || task.lineIndex >= lines.length)
    return

  lines[task.lineIndex] = serializeTaskLine(nextState, nextBody ?? task.body, nextDueDate)

  await FolderPicker.writeFile({
    folderUri: settings.baseFolderUri,
    fileName: task.fileName,
    content: lines.join('\n'),
  })

  invalidateQuickTaskCache(task.fileName)
}

async function toggleTaskState(task: QuickTaskItem) {
  if (isSaving.value)
    return

  isSaving.value = true
  const previousState = task.state
  const nextState = cycleState(previousState)
  task.state = nextState

  try {
    await updateTaskInFile(task, nextState, task.dueDate)
  }
  catch (err) {
    task.state = previousState
    console.error('Failed to toggle task state', err)
    error.value = 'Could not update task state.'
  }
  finally {
    isSaving.value = false
  }
}

async function changeDueDate(task: QuickTaskItem, dueDate: string) {
  if (isSaving.value)
    return

  isSaving.value = true
  const previousDue = task.dueDate
  const normalized = dueDate || undefined
  const previousReminder = getReminderTime(task)
  task.dueDate = normalized

  try {
    await updateTaskInFile(task, task.state, normalized)

    if (!normalized)
      await clearTaskReminder(task)
    else if (previousReminder) {
      try {
        await setTaskReminderTime(task, previousReminder)
      }
      catch {
        await clearTaskReminder(task)
      }
    }
  }
  catch (err) {
    task.dueDate = previousDue
    console.error('Failed to change due date', err)
    error.value = 'Could not update due date.'
  }
  finally {
    isSaving.value = false
  }
}

function showDueDateEditor(task: QuickTaskItem) {
  editingDueDateTaskId.value = task.id
}

function hideDueDateEditor(task: QuickTaskItem) {
  if (!task.dueDate)
    editingDueDateTaskId.value = null
}

function displayTaskBody(task: QuickTaskItem): string {
  return task.body.replace(/\s*📅\s*\d{4}-\d{2}-\d{2}\s*/g, '').trim()
}

function startEditTask(task: QuickTaskItem) {
  editingTaskId.value = task.id
  editingTaskText.value = displayTaskBody(task)
}

function cancelEditTask() {
  editingTaskId.value = null
  editingTaskText.value = ''
}

async function saveTaskText(task: QuickTaskItem) {
  const trimmed = editingTaskText.value.trim()
  if (!trimmed) {
    cancelEditTask()
    return
  }

  if (isSaving.value)
    return

  isSaving.value = true
  const previousBody = task.body

  const existingTag = selectedPreset.value?.tag?.trim()
  const nextBody = existingTag && !trimmed.includes(existingTag)
    ? `${existingTag} ${trimmed}`.trim()
    : trimmed

  task.body = nextBody

  try {
    await updateTaskInFile(task, task.state, task.dueDate, nextBody)
    cancelEditTask()
  }
  catch (err) {
    task.body = previousBody
    console.error('Failed to update task text', err)
    error.value = 'Could not update task text.'
  }
  finally {
    isSaving.value = false
  }
}

async function deleteTask(task: QuickTaskItem) {
  if (!settings.baseFolderUri || isSaving.value)
    return

  isSaving.value = true

  try {
    const read = await FolderPicker.readFile({
      folderUri: settings.baseFolderUri,
      fileName: task.fileName,
    })

    const lines = read.content.split(/\r?\n/)
    if (task.lineIndex >= 0 && task.lineIndex < lines.length)
      lines.splice(task.lineIndex, 1)

    await FolderPicker.writeFile({
      folderUri: settings.baseFolderUri,
      fileName: task.fileName,
      content: lines.join('\n'),
    })

    invalidateQuickTaskCache(task.fileName)

    if (editingTaskId.value === task.id)
      cancelEditTask()
    if (editingDueDateTaskId.value === task.id)
      editingDueDateTaskId.value = null
    if (editingAlarmTaskId.value === task.id)
      editingAlarmTaskId.value = null

    await clearTaskReminder(task)

    await loadQuickTasks()
  }
  catch (err) {
    console.error('Failed to delete task', err)
    error.value = 'Could not delete task.'
  }
  finally {
    isSaving.value = false
  }
}

function getTargetFileName(preset: QuickTaskPreset): string {
  if (preset.saveMode === 'daily_note')
    return `${todayIso()}.md`

  return preset.fileName || settings.listFileName || 'tasks.md'
}

async function addQuickTask() {
  if (!settings.baseFolderUri || !selectedPreset.value || !newTaskText.value.trim())
    return

  isSaving.value = true
  error.value = ''

  const preset = selectedPreset.value
  const fileName = getTargetFileName(preset)
  const duePart = newDueDate.value ? ` 📅 ${newDueDate.value}` : ''
  const tagPart = preset.tag?.trim() ? `${preset.tag.trim()} ` : ''
  const line = `- [ ] ${tagPart}${newTaskText.value.trim()}${duePart}`

  try {
    let existingContent = ''
    try {
      const read = await FolderPicker.readFile({ folderUri: settings.baseFolderUri, fileName })
      existingContent = read.content
    }
    catch {
      // File doesn't exist yet
    }

    let newContent: string
    if (!existingContent) {
      // New file — write with proper type:task frontmatter
      newContent = `---\ntype: task\n---\n\n${line}\n`
    }
    else {
      const fm = parseFrontmatter(existingContent)
      if (!fm.type) {
        // Existing file without frontmatter — prepend type:task
        newContent = `---\ntype: task\n---\n\n${existingContent.trimStart()}${line}\n`
      }
      else {
        // File already has frontmatter — just append the task line
        newContent = `${existingContent.trimEnd()}\n${line}\n`
      }
    }

    await FolderPicker.writeFile({
      folderUri: settings.baseFolderUri,
      fileName,
      content: newContent,
    })

    invalidateQuickTaskCache(fileName)

    newTaskText.value = ''
    newDueDate.value = ''
    await loadQuickTasks()
  }
  catch (err) {
    console.error('Failed to add quick task', err)
    error.value = 'Could not add task.'
  }
  finally {
    isSaving.value = false
  }
}

function applyHighlight(taskId: string) {
  if (highlightClearTimer !== null)
    clearTimeout(highlightClearTimer)

  // Ensure the task is visible by switching to "all" filter
  selectedTaskFilter.value = 'all'
  highlightedTaskId.value = taskId

  nextTick(() => {
    const el = document.querySelector(`[data-task-id="${CSS.escape(taskId)}"]`)
    el?.scrollIntoView({ behavior: 'smooth', block: 'center' })
  })

  highlightClearTimer = setTimeout(() => {
    highlightedTaskId.value = null
    highlightClearTimer = null
  }, 3000)
}

watch(
  () => route.query.highlight,
  (taskId) => {
    if (typeof taskId === 'string' && taskId && !isLoading.value)
      applyHighlight(taskId)
  },
)

onMounted(async () => {
  loadReminderTimes()

  const presetFromQuery = route.query.preset
  if (typeof presetFromQuery === 'string') {
    const exists = settings.quickTaskPresets.some(preset => preset.id === presetFromQuery)
    if (exists)
      selectedPresetId.value = presetFromQuery
  }

  await loadQuickTasks()

  const highlightQuery = route.query.highlight
  if (typeof highlightQuery === 'string' && highlightQuery)
    applyHighlight(highlightQuery)
})
</script>

<template>
  <div class="page">
    <div class="header">
      <button class="glass-icon-button back-button" aria-label="Go back" @click="goBack">
        ←
      </button>

      <h1>Quick Tasks</h1>
    </div>

    <div class="card glass-card quick-add-card">
      <div class="quick-add-row">
        <div class="preset-switcher-wrap">
          <OptionSwitcher v-model="selectedPresetId" :options="presetOptions" aria-label="Task preset" />
        </div>

        <input v-model="newTaskText" class="glass-input quick-task-input" type="text" placeholder="Add quick task..."
          @keydown.enter.prevent="addQuickTask">

        <div class="quick-add-actions">
          <input v-model="newDueDate" class="glass-input date-input quick-add-date" type="date">

          <button class="glass-button glass-button--primary quick-add-submit" :disabled="isSaving"
            @click="addQuickTask">
            Add
          </button>
        </div>
      </div>
    </div>

    <div class="stats-row card glass-card">
      <div v-if="taskFilterOptions.length > 0" class="filter-switcher-row">
        <Filter :size="16" class="switcher-icon" />
        <OptionSwitcher :model-value="selectedTaskFilter" :options="taskFilterOptions" aria-label="Task status filter"
          @update:model-value="onTaskFilterChange" />
      </div>
      <div class="sort-switcher-row">
        <ArrowUpDown :size="16" class="switcher-icon" />
        <OptionSwitcher :model-value="selectedSortMode" :options="taskSortOptions" aria-label="Task sort mode"
          @update:model-value="onTaskSortChange" />
      </div>
    </div>

    <div v-if="isLoading" class="card glass-card empty-state">
      Loading tasks...
    </div>

    <div v-else-if="error" class="card glass-card empty-state error-text">
      {{ error }}
    </div>

    <div v-else-if="visibleTasks.length === 0" class="card glass-card empty-state">
      No tasks found yet.
    </div>

    <div v-else class="task-list">
      <div v-for="task in visibleTasks" :key="task.id" class="card glass-card task-row" :data-task-id="task.id"
        :class="[task.state, { overdue: isOverdue(task), highlighted: highlightedTaskId === task.id }]">
        <button class="state-button" :class="task.state" :aria-label="`Toggle task state (${task.state})`"
          @click="toggleTaskState(task)">
          {{ task.state === 'done' ? '✓' : task.state === 'cancelled' ? '–' : '○' }}
        </button>

        <div class="task-content">
          <div class="task-main-line">
            <input v-if="editingTaskId === task.id" v-model="editingTaskText" class="glass-input task-main-edit"
              type="text" @keydown.enter.prevent="saveTaskText(task)" @keydown.esc.prevent="cancelEditTask"
              @blur="saveTaskText(task)">
            <button v-else class="task-main task-main-button" type="button" @click="startEditTask(task)">
              {{ displayTaskBody(task) }}
            </button>

            <div v-if="task.dueDate || editingDueDateTaskId === task.id" class="task-datetime-stack">
              <input class="glass-input inline-date" type="date" :value="task.dueDate || ''"
                @change="changeDueDate(task, ($event.target as HTMLInputElement).value)"
                @blur="hideDueDateEditor(task)">

              <input v-if="task.dueDate && (hasReminder(task) || editingAlarmTaskId === task.id)"
                class="glass-input inline-time" type="time" :value="getReminderTime(task)"
                @change="changeReminderTime(task, ($event.target as HTMLInputElement).value)"
                @blur="hideAlarmEditor(task)">
              <button v-else-if="task.dueDate" class="glass-icon-button alarm-task-button" type="button"
                aria-label="Set reminder" @click="showAlarmEditor(task)">
                <AlarmClock :size="14" />
              </button>
            </div>
            <button v-else class="glass-button glass-button--secondary add-date-button" type="button"
              @click="showDueDateEditor(task)">
              Add date
            </button>

            <button class="glass-icon-button delete-task-button" type="button" :disabled="isSaving"
              aria-label="Delete task" @click="deleteTask(task)">
              <Trash2 :size="14" />
            </button>
          </div>

          <div class="task-meta">
            <span>{{ task.presetLabel || 'Preset' }}</span>
            <span>{{ task.fileName }}</span>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.page {
  min-height: 100vh;
  padding: var(--page-top-padding) 20px 40px;
  color: var(--text);
}

.header {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 20px;
}

h1 {
  margin: 0;
  font-size: 1.8rem;
}

.glass-card {
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: 20px;
  backdrop-filter: blur(14px) saturate(var(--saturation));
  -webkit-backdrop-filter: blur(14px) saturate(var(--saturation));
  box-shadow: var(--shadow);
}

.card {
  padding: 14px;
  margin-bottom: 12px;
}

.quick-add-card {
  margin-bottom: 12px;
}

.preset-switcher-wrap {
  min-width: 0;
}

.field-label {
  font-size: 0.92rem;
  font-weight: 600;
  color: var(--text);
}

.quick-add-row {
  display: grid;
  grid-template-columns: 190px minmax(0, 1fr) auto;
  gap: 10px;
}

.quick-task-input {
  min-width: 0;
}

.quick-add-actions {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  align-items: center;
  gap: 8px;
}

.date-input {

  padding-left: 10px;
  padding-right: 10px;
}

.quick-add-date {
  width: 100%;
}

.quick-add-submit {
  min-height: 40px;
  width: 100%;
  padding: 0 14px;
}

.glass-input {
  border: 1px solid color-mix(in srgb, var(--c-light) 16%, transparent);
  border-radius: 14px;
  padding: 10px 12px;
  background: color-mix(in srgb, var(--c-glass) 10%, transparent);
  color: var(--text);
}

.stats-row {
  display: grid;
  grid-template-columns: 1.7fr 1fr;
  gap: 10px;
  color: var(--text-soft);
  font-size: 0.92rem;
}

.filter-switcher-row {
  display: grid;
  grid-template-columns: auto 1fr;
  align-items: center;
  gap: 8px;
}

.sort-switcher-row {
  display: grid;
  grid-template-columns: auto 1fr;
  align-items: center;
  gap: 8px;
}

.switcher-icon {
  color: var(--text-soft);
}

.empty-state {
  text-align: center;
  color: var(--text-soft);
}

.error-text {
  color: var(--danger);
}

.task-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.task-row {
  display: grid;
  grid-template-columns: auto 1fr;
  align-items: center;
  gap: 8px;
}

.state-button {
  width: 32px;
  height: 32px;
  border-radius: 10px;
  border: 1px solid var(--state-pending-border);
  background: var(--state-pending-bg);
  color: var(--state-pending-text);
  font-size: 0.95rem;
  font-weight: 700;
}

.state-button.done {
  border-color: var(--state-done-border);
  background: var(--state-done-bg);
  color: var(--state-done-text);
}

.state-button.cancelled {
  border-color: var(--state-cancelled-border);
  background: var(--state-cancelled-bg);
  color: var(--state-cancelled-text);
}

.task-content {
  min-width: 0;
}

.task-main-line {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto auto;
  align-items: start;
  gap: 8px;
}

.task-datetime-stack {
  display: grid;
  gap: 6px;
  align-content: start;
}

.task-main {
  font-size: 0.95rem;
  line-height: 1.25;
  word-break: break-word;
}

.task-main-button {
  border: none;
  background: transparent;
  color: inherit;
  text-align: left;
  padding: 0;
  width: 100%;
  cursor: text;
}

.task-main-edit {
  width: 100%;
  min-height: 34px;
  padding: 7px 10px;
}

.task-row.done .task-main {
  text-decoration: line-through;
  opacity: 0.8;
}

.task-row.cancelled .task-main {
  opacity: 0.65;
}

.task-row.overdue {
  border-color: color-mix(in srgb, #fca5a5 46%, var(--border));
  background:
    linear-gradient(180deg,
      color-mix(in srgb, #fca5a5 16%, var(--surface) 84%),
      color-mix(in srgb, #ef4444 9%, var(--surface) 91%));
}

.task-row.highlighted {
  animation: task-highlight-pulse 3s ease-out forwards;
}

@keyframes task-highlight-pulse {

  0%,
  20% {
    border-color: color-mix(in srgb, #6ee7b7 70%, var(--border));
    background: color-mix(in srgb, #6ee7b7 18%, var(--surface));
    box-shadow: var(--shadow), 0 0 0 2px color-mix(in srgb, #6ee7b7 40%, transparent);
  }

  100% {
    border-color: var(--border);
    background: var(--surface);
    box-shadow: var(--shadow);
  }
}

.task-row.overdue .inline-date,
.task-row.overdue .add-date-button {
  border-color: color-mix(in srgb, #f87171 40%, var(--c-light));
}

.task-meta {
  margin-top: 2px;
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  font-size: 0.72rem;
  color: var(--text-soft);
}

.inline-date {
  width: 130px;
  min-height: 34px;
  padding: 7px 9px;
  font-size: 0.82rem;
}

.add-date-button {
  min-height: 34px;
  padding: 6px 10px;
  border-radius: 12px;
  font-size: 0.78rem;
  white-space: nowrap;
}

.inline-time {
  width: 130px;
  min-height: 34px;
  padding: 7px 8px;
  font-size: 0.82rem;
}

.alarm-task-button {
  width: 130px;
  height: 34px;
  border-radius: 12px;
  color: var(--text-soft);
}

.alarm-task-button:hover {
  color: color-mix(in srgb, var(--primary) 78%, var(--text));
}

.delete-task-button {
  width: 34px;
  height: 34px;
  border-radius: 12px;
  color: var(--text-soft);
}

.delete-task-button:hover {
  color: var(--danger);
}

@media (max-width: 860px) {
  .stats-row {
    grid-template-columns: 1fr;
  }

  .quick-add-row {
    grid-template-columns: 1fr;
  }

  .quick-add-actions {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .quick-add-date {
    width: 100%;
  }

  .inline-date {
    width: 124px;
  }

  .inline-time,
  .alarm-task-button {
    width: 124px;
  }
}
</style>
