<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ArrowUpDown, CalendarDays, Filter, ListChecks } from 'lucide-vue-next'
import OptionSwitcher from '../components/OptionSwitcher.vue'
import { FolderPicker } from '../plugins/folder-picker'
import { loadSettings } from '../lib/settings'
import type { QuickTaskPreset } from '../lib/settings'
import { parseFrontmatter } from '../lib/lists'

type TaskState = 'pending' | 'done' | 'cancelled'
type TaskFilter = 'all' | TaskState | 'overdue'
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

const router = useRouter()
const route = useRoute()
const settings = loadSettings()

const isLoading = ref(true)
const isSaving = ref(false)
const error = ref('')

const tasks = ref<QuickTaskItem[]>([])
const newTaskText = ref('')
const newDueDate = ref('')
const selectedPresetId = ref(settings.quickTaskPresets[0]?.id || '')
const selectedTaskFilter = ref<TaskFilter>('all')
const selectedSortMode = ref<TaskSortMode>('status')
const editingTaskId = ref<string | null>(null)
const editingTaskText = ref('')
const editingDueDateTaskId = ref<string | null>(null)

const taskStateOrder: Record<TaskState, number> = {
  pending: 0,
  done: 1,
  cancelled: 2,
}

const selectedPreset = computed(() =>
  settings.quickTaskPresets.find(preset => preset.id === selectedPresetId.value),
)

const presetOptions = computed(() =>
  settings.quickTaskPresets.map(preset => ({
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
  const overdue = presetTasks.value.filter(task => isOverdue(task)).length

  return {
    pending,
    done,
    cancelled,
    overdue,
    total: presetTasks.value.length,
  }
})

const taskFilterOptions = computed(() => [
  { value: 'all', label: `All · ${taskCounts.value.total}` },
  { value: 'pending', label: `Pending · ${taskCounts.value.pending}` },
  { value: 'done', label: `Done · ${taskCounts.value.done}` },
  { value: 'cancelled', label: `Cancelled · ${taskCounts.value.cancelled}` },
  { value: 'overdue', label: `Overdue · ${taskCounts.value.overdue}` },
])

const taskSortOptions = [
  { value: 'status', label: 'By status', icon: ListChecks },
  { value: 'due', label: 'By due date', icon: CalendarDays },
]

function onTaskFilterChange(value: string) {
  if (value === 'all' || value === 'pending' || value === 'done' || value === 'cancelled' || value === 'overdue')
    selectedTaskFilter.value = value
}

function onTaskSortChange(value: string) {
  if (value === 'status' || value === 'due')
    selectedSortMode.value = value
}

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
    isLoading.value = false
    return
  }

  isLoading.value = true
  error.value = ''

  try {
    const listed = await FolderPicker.listFiles({ folderUri: settings.baseFolderUri })
    const mdFiles = listed.files.filter(file => file.isFile && file.name.toLowerCase().endsWith('.md'))

    const loadedTasks: QuickTaskItem[] = []

    for (const file of mdFiles) {
      try {
        const read = await FolderPicker.readFile({
          folderUri: settings.baseFolderUri,
          fileName: file.name,
        })

        const lines = read.content.split(/\r?\n/)
        for (let lineIndex = 0; lineIndex < lines.length; lineIndex += 1) {
          const line = lines[lineIndex] || ''
          const parsed = parseTaskLine(line)
          if (!parsed)
            continue

          const preset = getPresetForTask(parsed.body, file.name)
          if (!preset)
            continue

          loadedTasks.push({
            id: `${file.name}:${lineIndex}`,
            fileName: file.name,
            lineIndex,
            state: parsed.state,
            body: parsed.body,
            dueDate: parsed.dueDate,
            presetId: preset.id,
            presetLabel: preset.label,
          })
        }
      }
      catch (fileErr) {
        console.warn('Failed to scan task file', file.name, fileErr)
      }
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
  task.dueDate = normalized

  try {
    await updateTaskInFile(task, task.state, normalized)
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

onMounted(async () => {
  const presetFromQuery = route.query.preset
  if (typeof presetFromQuery === 'string') {
    const exists = settings.quickTaskPresets.some(preset => preset.id === presetFromQuery)
    if (exists)
      selectedPresetId.value = presetFromQuery
  }

  await loadQuickTasks()
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
      <div class="filter-switcher-row">
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
      No quick-preset tasks found yet.
    </div>

    <div v-else class="task-list">
      <div v-for="task in visibleTasks" :key="task.id" class="card glass-card task-row"
        :class="[task.state, { overdue: isOverdue(task) }]">
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

            <input v-if="task.dueDate || editingDueDateTaskId === task.id" class="glass-input inline-date" type="date"
              :value="task.dueDate || ''" @change="changeDueDate(task, ($event.target as HTMLInputElement).value)"
              @blur="hideDueDateEditor(task)">
            <button v-else class="glass-button glass-button--secondary add-date-button" type="button"
              @click="showDueDateEditor(task)">
              Add date
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
  padding: 24px 20px 40px;
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
  grid-template-columns: minmax(0, 1fr) auto;
  align-items: center;
  gap: 8px;
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
}

.add-date-button {
  min-height: 34px;
  padding: 6px 10px;
  border-radius: 12px;
  font-size: 0.78rem;
  white-space: nowrap;
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
}
</style>
