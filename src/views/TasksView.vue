<script setup lang="ts">
import { computed, nextTick, onActivated, onMounted, reactive, ref, watch } from 'vue'
import type { CSSProperties } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { AlarmClock, AlertTriangle, ArrowUpDown, CalendarDays, Check, Filter, Flame, LayoutGrid, ListChecks, Square, Trash2 } from 'lucide-vue-next'
import OptionSwitcher from '../components/OptionSwitcher.vue'
import { FolderPicker } from '../plugins/folder-picker'
import { resolveTaskLineIndex } from '../lib/quickTaskFileOps'
import { invalidateQuickTaskCache, loadQuickTasksFromFolder, updateTaskInFolder } from '../lib/quickTasksData'
import { cancelTaskReminderNotification, scheduleTaskReminderNotification } from '../lib/taskReminderService'
import { loadSettings } from '../lib/settings'
import type { QuickTaskPreset } from '../lib/settings'
import { parseFrontmatter } from '../lib/lists'
import type { TaskRepeat } from '../lib/taskLine'
import BottomActionNav from '../components/BottomActionNav.vue'

type TaskState = 'pending' | 'done' | 'cancelled'
type TaskFilter = 'all' | TaskState | 'overdue' | 'closed'
type TaskSortMode = 'status' | 'due'

interface QuickTaskItem {
  id: string
  fileName: string
  lineIndex: number
  state: TaskState
  body: string
  dueDate?: string
  isHighPriority?: boolean
  repeat?: TaskRepeat
  presetId?: string
  presetLabel?: string
}

const QUICK_TASK_READ_CONCURRENCY = 6
const PULL_REFRESH_THRESHOLD = 72
const PULL_REFRESH_MAX_DISTANCE = 120
const TASK_REMINDER_STORAGE_KEY = 'quick-capture-task-reminders'
const TASK_STATE_CHANGE_SORT_DELAY = 300

const router = useRouter()
const route = useRoute()
const settings = reactive(loadSettings())

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
const newTaskIsHighPriority = ref(false)
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
let sortDelayTimer: ReturnType<typeof setTimeout> | null = null
const pullStartY = ref<number | null>(null)
const pullStartX = ref<number | null>(null)
const pullDistance = ref(0)
const pullAxisLock = ref<'none' | 'vertical' | 'horizontal'>('none')
const isPullRefreshing = ref(false)
const pullReadyHapticPlayed = ref(false)

const isPullReady = computed(() => pullDistance.value >= PULL_REFRESH_THRESHOLD)
const showPullIndicator = computed(() => pullDistance.value > 0 || isPullRefreshing.value)
const pageContentStyle = computed<CSSProperties>(() => ({
  transform: `translateY(${pullDistance.value}px)`,
  transition: isPullRefreshing.value || pullDistance.value === 0 ? 'transform 0.18s ease' : 'none',
}))

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
    // High priority tasks first
    if (!!a.isHighPriority !== !!b.isHighPriority)
      return a.isHighPriority ? -1 : 1

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
    {
      value: 'all',
      label: '',
      count: taskCounts.value.total,
      icon: LayoutGrid,
    }
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
    selectedTaskFilter.value = 'pending'
})

function goBack() {
  router.replace('/')
}

function isPageScrolledToTop() {
  return window.scrollY <= 0
}

async function triggerHaptic(kind: 'light' | 'medium' = 'light') {
  try {
    const { Haptics, ImpactStyle } = await import('@capacitor/haptics')
    const style = kind === 'medium' ? ImpactStyle.Medium : ImpactStyle.Light
    await Haptics.impact({ style })
    return
  }
  catch {
    if (typeof navigator !== 'undefined' && typeof navigator.vibrate === 'function')
      navigator.vibrate(kind === 'medium' ? 20 : 10)
  }
}

function resetPullState() {
  pullStartY.value = null
  pullStartX.value = null
  pullDistance.value = 0
  pullAxisLock.value = 'none'
  pullReadyHapticPlayed.value = false
}

function onPullTouchStart(event: TouchEvent) {
  if (!settings.baseFolderUri || isPullRefreshing.value)
    return
  if (!isPageScrolledToTop())
    return

  const touch = event.touches[0]
  if (!touch)
    return

  pullStartY.value = touch.clientY
  pullStartX.value = touch.clientX
  pullDistance.value = 0
  pullAxisLock.value = 'none'
}

function onPullTouchMove(event: TouchEvent) {
  if (pullStartY.value === null || pullStartX.value === null)
    return

  const touch = event.touches[0]
  if (!touch)
    return

  const deltaY = touch.clientY - pullStartY.value
  const deltaX = touch.clientX - pullStartX.value

  if (pullAxisLock.value === 'none' && (Math.abs(deltaX) > 6 || Math.abs(deltaY) > 6))
    pullAxisLock.value = Math.abs(deltaY) >= Math.abs(deltaX) ? 'vertical' : 'horizontal'

  if (pullAxisLock.value === 'horizontal')
    return

  if (deltaY <= 0) {
    pullDistance.value = 0
    pullReadyHapticPlayed.value = false
    return
  }

  if (!isPageScrolledToTop()) {
    resetPullState()
    return
  }

  event.preventDefault()
  pullDistance.value = Math.min(PULL_REFRESH_MAX_DISTANCE, deltaY * 0.45)

  const reachedReadyState = pullDistance.value >= PULL_REFRESH_THRESHOLD
  if (reachedReadyState && !pullReadyHapticPlayed.value) {
    pullReadyHapticPlayed.value = true
    void triggerHaptic('light')
  }
  else if (!reachedReadyState) {
    pullReadyHapticPlayed.value = false
  }
}

async function onPullTouchEnd() {
  if (pullStartY.value === null)
    return

  const shouldRefresh = pullDistance.value >= PULL_REFRESH_THRESHOLD
  pullStartY.value = null
  pullStartX.value = null
  pullAxisLock.value = 'none'

  if (!shouldRefresh) {
    pullDistance.value = 0
    return
  }

  isPullRefreshing.value = true
  pullDistance.value = 44
  void triggerHaptic('medium')

  try {
    invalidateQuickTaskCache()
    await loadQuickTasks()
  }
  finally {
    isPullRefreshing.value = false
    pullDistance.value = 0
  }
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

function getReminderTime(task: QuickTaskItem): string {
  return reminderTimes.value[task.id] || ''
}

function hasReminder(task: QuickTaskItem): boolean {
  return !!getReminderTime(task)
}

async function clearTaskReminder(task: QuickTaskItem) {
  delete reminderTimes.value[task.id]
  persistReminderTimes()
  editingAlarmTaskId.value = editingAlarmTaskId.value === task.id ? null : editingAlarmTaskId.value
  await cancelTaskReminderNotification(task.fileName, task.lineIndex)
}

async function setTaskReminderTime(task: QuickTaskItem, time: string) {
  const normalized = time.trim()
  if (!normalized) {
    await clearTaskReminder(task)
    return
  }

  await scheduleTaskReminderNotification({
    fileName: task.fileName,
    lineIndex: task.lineIndex,
    dueDate: task.dueDate,
    taskId: task.id,
    body: displayTaskBody(task),
  }, normalized)
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

  if (tasks.value.length === 0)
    isLoading.value = true
  error.value = ''

  try {
    const loadedTasks = await loadQuickTasksFromFolder({
      folderUri,
      readConcurrency: QUICK_TASK_READ_CONCURRENCY,
      matchPreset: (body, fileName) => getPresetForTask(body, fileName),
    })
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
  nextIsHighPriority?: boolean,
) {
  if (!settings.baseFolderUri)
    return
  await updateTaskInFolder({
    folderUri: settings.baseFolderUri,
    task,
    nextState,
    nextDueDate,
    nextBody,
    nextIsHighPriority,
  })
}

function advanceDueDate(dateIso: string, repeat: string): string {
  const d = new Date(`${dateIso}T00:00:00`)
  switch (repeat) {
    case 'daily':
      d.setDate(d.getDate() + 1)
      break
    case 'weekly':
      d.setDate(d.getDate() + 7)
      break
    case 'weekdays':
      d.setDate(d.getDate() + 1)
      while (d.getDay() === 0 || d.getDay() === 6)
        d.setDate(d.getDate() + 1)
      break
    case 'monthly':
      d.setMonth(d.getMonth() + 1)
      break
    default:
      d.setDate(d.getDate() + 1)
  }
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}

async function toggleTaskState(task: QuickTaskItem) {
  if (isSaving.value)
    return

  isSaving.value = true
  const previousState = task.state
  const previousDue = task.dueDate
  const nextState = cycleState(previousState)

  // Recurring tasks: advance due date instead of marking done
  if (task.repeat && task.dueDate && previousState === 'pending' && nextState === 'done') {
    const nextDue = advanceDueDate(task.dueDate, task.repeat)
    task.dueDate = nextDue
    try {
      await updateTaskInFile(task, 'pending', nextDue, undefined, task.isHighPriority)
    }
    catch (err) {
      task.dueDate = previousDue
      console.error('Failed to advance recurring task', err)
      error.value = 'Could not advance recurring task.'
    }
    finally {
      isSaving.value = false
    }
    return
  }

  task.state = nextState

  try {
    await updateTaskInFile(task, nextState, task.dueDate, undefined, task.isHighPriority)

    // Force immediate re-render without re-sorting by clearing any pending sort delay
    if (sortDelayTimer !== null)
      clearTimeout(sortDelayTimer)

    // Add delay before re-sorting so user can see the status change
    sortDelayTimer = setTimeout(() => {
      sortDelayTimer = null
    }, TASK_STATE_CHANGE_SORT_DELAY)
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

async function toggleTaskPriority(task: QuickTaskItem) {
  if (isSaving.value)
    return

  isSaving.value = true
  const previousPriority = task.isHighPriority
  task.isHighPriority = !previousPriority

  try {
    await updateTaskInFile(task, task.state, task.dueDate, undefined, task.isHighPriority)

    // Force immediate re-render without re-sorting
    if (sortDelayTimer !== null)
      clearTimeout(sortDelayTimer)
  }
  catch (err) {
    task.isHighPriority = previousPriority
    console.error('Failed to toggle task priority', err)
    error.value = 'Could not update task priority.'
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

function displayTaskBody(task: QuickTaskItem): string {
  const withoutDueDate = task.body.replace(/\s*📅\s*\d{4}-\d{2}-\d{2}\s*/g, '').trim()
  const withoutRepeat = withoutDueDate.replace(/\s*🔁\s*(daily|weekly|weekdays|monthly)\s*/gi, '').trim()
  return stripKnownTaskTags(withoutRepeat)
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
  const withTag = existingTag && !trimmed.includes(existingTag)
    ? `${existingTag} ${trimmed}`.trim()
    : trimmed
  const hasRepeatToken = /🔁\s*(daily|weekly|weekdays|monthly)/i.test(withTag)
  const nextBody = task.repeat && !hasRepeatToken
    ? `${withTag} 🔁 ${task.repeat}`.trim()
    : withTag

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
    const lineIndex = resolveTaskLineIndex(lines, task)
    if (lineIndex < 0)
      throw new Error('Could not locate task line in file')

    lines.splice(lineIndex, 1)

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
    return `task-${todayIso()}.md`

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
  const marker = newTaskIsHighPriority.value ? 'f' : ' '
  const line = `- [${marker}] ${tagPart}${newTaskText.value.trim()}${duePart}`

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
        const normalizedExisting = existingContent
          .replace(/\r\n/g, '\n')
          .trimStart()
          .replace(/\n*$/, '\n')
        newContent = `---\ntype: task\n---\n\n${normalizedExisting}${line}\n`
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
    newTaskIsHighPriority.value = false
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

  const nextQuery = { ...route.query }
  delete nextQuery.highlight
  delete nextQuery.pulse
  router.replace({ query: nextQuery })

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
  () => [route.query.highlight, route.query.pulse],
  ([taskId]) => {
    if (typeof taskId === 'string' && taskId && !isLoading.value)
      applyHighlight(taskId)
  },
)

let isFirstTasksActivation = true

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

onActivated(async () => {
  if (isFirstTasksActivation) {
    isFirstTasksActivation = false
    return
  }
  Object.assign(settings, loadSettings())
  await loadQuickTasks()
})
</script>

<template>
  <div class="page" @touchstart="onPullTouchStart" @touchmove="onPullTouchMove" @touchend="onPullTouchEnd"
    @touchcancel="onPullTouchEnd">
    <div class="pull-refresh"
      :class="{ 'is-visible': showPullIndicator, 'is-ready': isPullReady, 'is-refreshing': isPullRefreshing }"
      :aria-label="isPullRefreshing ? 'Refreshing tasks' : 'Pull to refresh tasks'" role="status">
      <div class="pull-refresh-spinner" aria-hidden="true" />
      <span>{{ isPullRefreshing ? 'Refreshing…' : isPullReady ? 'Release to refresh' : '' }}</span>
    </div>

    <div class="page-content" :style="pageContentStyle">
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
            <div class="quick-add-date-priority">
              <input v-model="newDueDate" class="glass-input date-input quick-add-date" type="date">
              <button class="glass-icon-button priority-toggle-button" :class="{ 'is-active': newTaskIsHighPriority }"
                :aria-label="newTaskIsHighPriority ? 'High priority (click to remove)' : 'Not high priority (click to set)'"
                @click="newTaskIsHighPriority = !newTaskIsHighPriority">
                <Flame :size="14" />
              </button>
            </div>

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
          :class="[task.state, { overdue: isOverdue(task), highlighted: highlightedTaskId === task.id, 'is-high-priority': task.isHighPriority }]">
          <button class="state-button" :class="task.state" :aria-label="`Toggle task state (${task.state})`"
            @click="toggleTaskState(task)">
            <Flame v-if="task.isHighPriority && task.state === 'pending'" :size="14" class="state-icon-flame"
              aria-hidden="true" />
            <span v-else class="state-icon">
              <Square v-if="task.state === 'pending'" :size="12" :stroke-width="2.2" aria-hidden="true"
                class="state-icon-square" />
              <span v-else>{{ task.state === 'done' ? '✓' : '–' }}</span>
            </span>
            <Flame v-if="task.isHighPriority && task.state !== 'pending'" :size="12" class="state-flame"
              aria-hidden="true" />
          </button>

          <div class="task-content">
            <div class="task-main-line">
              <input v-if="editingTaskId === task.id" v-model="editingTaskText" class="glass-input task-main-edit"
                type="text" @keydown.enter.prevent="saveTaskText(task)" @keydown.esc.prevent="cancelEditTask"
                @blur="saveTaskText(task)">
              <button v-else class="task-main task-main-button" type="button" @click="startEditTask(task)">
                {{ displayTaskBody(task) }}
                <span v-if="task.repeat" class="repeat-badge" :title="`Repeats ${task.repeat}`">🔁</span>
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

              <button class="glass-icon-button priority-task-button" :class="{ 'is-active': task.isHighPriority }"
                type="button" :disabled="isSaving"
                :aria-label="task.isHighPriority ? 'High priority (click to remove)' : 'Set as high priority'"
                @click="toggleTaskPriority(task)">
                <Flame :size="14" />
              </button>

              <button class="glass-icon-button delete-task-button" type="button" :disabled="isSaving"
                aria-label="Delete task" @click="deleteTask(task)">
                <Trash2 :size="14" />
              </button>
            </div>
          </div>
        </div>
      </div>
    </div>

  </div>
  <BottomActionNav />
</template>

<style scoped>
.page {
  min-height: 100vh;
  padding: var(--page-top-padding) 20px 40px;
  color: var(--text);
  overflow-x: hidden;
  touch-action: pan-y;
  overscroll-behavior-x: contain;
}

.page-content {
  will-change: transform;
}

.pull-refresh {
  position: fixed;
  top: calc(var(--page-top-padding) + 8px);
  left: 0;
  right: 0;
  z-index: 5;
  display: flex;
  justify-content: center;
  align-items: center;
  gap: 8px;
  font-size: 0.82rem;
  color: var(--text-soft);
  opacity: 0;
  transform: translateY(-10px);
  transition: opacity 0.18s ease, transform 0.18s ease;
  pointer-events: none;
}

.pull-refresh.is-visible {
  opacity: 1;
  transform: translateY(0);
}

.pull-refresh-spinner {
  width: 14px;
  height: 14px;
  border-radius: 999px;
  border: 2px solid color-mix(in srgb, var(--text-soft) 22%, transparent);
  border-top-color: color-mix(in srgb, var(--primary) 72%, var(--text));
}

.pull-refresh.is-ready .pull-refresh-spinner,
.pull-refresh.is-refreshing .pull-refresh-spinner {
  animation: pull-refresh-spin 0.75s linear infinite;
}

.pull-refresh:not(.is-ready):not(.is-refreshing) .pull-refresh-spinner {
  transform: scale(0.86);
}

@keyframes pull-refresh-spin {
  from {
    transform: rotate(0deg);
  }

  to {
    transform: rotate(360deg);
  }
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
  gap: 6px;
}

.quick-add-date-priority {
  display: grid;
  grid-template-columns: 1fr auto;
  align-items: center;
  gap: 6px;
}

.date-input {

  padding-left: 8px;
  padding-right: 8px;
}

.quick-add-date {
  width: 100%;
}

.quick-add-submit {
  min-height: 40px;
  width: 100%;
  padding: 0 12px;
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
  transition: transform 0.2s ease;
  touch-action: pan-y;
}

.state-button {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
  border-radius: 10px;
  border: 1px solid var(--state-pending-border);
  background: var(--state-pending-bg);
  color: var(--state-pending-text);
  font-size: 0.95rem;
  font-weight: 700;
  position: relative;
  gap: 2px;
}

.state-icon {
  display: flex;
  align-items: center;
  justify-content: center;
}

.state-icon-square {
  display: block;
}

.state-icon-flame {
  color: #ef4444;
}

.state-flame {
  position: absolute;
  bottom: -4px;
  right: -4px;
  color: #ef4444;
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
  grid-template-columns: minmax(0, 1fr) 130px 34px 34px;
  align-items: start;
  gap: 8px;
}

.task-datetime-stack {
  display: grid;
  gap: 6px;
  align-content: start;
  width: 130px;
  min-width: 130px;
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
  width: 130px;
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

.priority-task-button {
  width: 34px;
  height: 34px;
  border-radius: 12px;
  color: var(--text-soft);
}

.priority-task-button:hover {
  color: #ef4444;
}

.priority-task-button.is-active {
  color: #ef4444;
  background: color-mix(in srgb, #ef4444 14%, transparent);
}

.priority-toggle-button {
  width: 34px;
  height: 34px;
  border-radius: 12px;
  color: var(--text-soft);
  flex-shrink: 0;
}

.priority-toggle-button:hover {
  color: #ef4444;
}

.priority-toggle-button.is-active {
  color: #ef4444;
  background: color-mix(in srgb, #ef4444 14%, transparent);
}

.task-row.is-high-priority {
  border-color: color-mix(in srgb, #ef4444 35%, var(--border));
}

.repeat-badge {
  font-size: 0.75rem;
  margin-left: 4px;
  vertical-align: middle;
  opacity: 0.7;
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

  .task-main-line {
    grid-template-columns: minmax(0, 1fr) 124px 34px 34px;
  }

  .task-datetime-stack,
  .add-date-button {
    width: 124px;
    min-width: 124px;
  }
}
</style>

.repeat-badge {
font-size: 0.75rem;
margin-left: 4px;
vertical-align: middle;
opacity: 0.7;
}
