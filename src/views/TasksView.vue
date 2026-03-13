<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { FolderPicker } from '../plugins/folder-picker'
import { loadSettings } from '../lib/settings'
import type { QuickTaskPreset } from '../lib/settings'

type TaskState = 'pending' | 'done' | 'cancelled'

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
const settings = loadSettings()

const isLoading = ref(true)
const isSaving = ref(false)
const error = ref('')

const tasks = ref<QuickTaskItem[]>([])
const newTaskText = ref('')
const newDueDate = ref('')
const selectedPresetId = ref(settings.quickTaskPresets[0]?.id || '')

const selectedPreset = computed(() =>
  settings.quickTaskPresets.find(preset => preset.id === selectedPresetId.value),
)

const sortedTasks = computed(() => {
  const order: Record<TaskState, number> = {
    pending: 0,
    done: 1,
    cancelled: 2,
  }

  return [...tasks.value].sort((a, b) => {
    if (order[a.state] !== order[b.state])
      return order[a.state] - order[b.state]

    const aDue = a.dueDate || '9999-99-99'
    const bDue = b.dueDate || '9999-99-99'
    if (aDue !== bDue)
      return aDue.localeCompare(bDue)

    return a.fileName.localeCompare(b.fileName)
  })
})

const taskCounts = computed(() => {
  const pending = tasks.value.filter(task => task.state === 'pending').length
  const done = tasks.value.filter(task => task.state === 'done').length
  const cancelled = tasks.value.filter(task => task.state === 'cancelled').length

  return {
    pending,
    done,
    cancelled,
    total: tasks.value.length,
  }
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

async function updateTaskInFile(task: QuickTaskItem, nextState: TaskState, nextDueDate?: string) {
  if (!settings.baseFolderUri)
    return

  const read = await FolderPicker.readFile({
    folderUri: settings.baseFolderUri,
    fileName: task.fileName,
  })

  const lines = read.content.split(/\r?\n/)
  if (task.lineIndex < 0 || task.lineIndex >= lines.length)
    return

  lines[task.lineIndex] = serializeTaskLine(nextState, task.body, nextDueDate)

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
    await FolderPicker.appendToFile({
      folderUri: settings.baseFolderUri,
      fileName,
      content: `${line}\n`,
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
  await loadQuickTasks()
})
</script>

<template>
  <div class="page">
    <div class="header">
      <button class="glass-icon-button back-button" aria-label="Go back" @click="goBack">
        ←
      </button>

      <div>
        <h1>Quick Tasks</h1>
        <p class="subtitle">Obsidian-style tasks from your quick presets</p>
      </div>
    </div>

    <div class="card glass-card quick-add-card">
      <div class="quick-add-row">
        <select v-model="selectedPresetId" class="glass-input preset-select">
          <option v-for="preset in settings.quickTaskPresets" :key="preset.id" :value="preset.id">
            {{ preset.label || 'Preset' }}
          </option>
        </select>

        <input v-model="newTaskText" class="glass-input" type="text" placeholder="Add quick task..."
          @keydown.enter.prevent="addQuickTask">

        <input v-model="newDueDate" class="glass-input date-input" type="date">

        <button class="glass-button glass-button--primary" :disabled="isSaving" @click="addQuickTask">
          Add
        </button>
      </div>
    </div>

    <div class="stats-row card glass-card">
      <span>Pending: {{ taskCounts.pending }}</span>
      <span>Done: {{ taskCounts.done }}</span>
      <span>Cancelled: {{ taskCounts.cancelled }}</span>
      <span>Total: {{ taskCounts.total }}</span>
    </div>

    <div v-if="isLoading" class="card glass-card empty-state">
      Loading tasks...
    </div>

    <div v-else-if="error" class="card glass-card empty-state error-text">
      {{ error }}
    </div>

    <div v-else-if="sortedTasks.length === 0" class="card glass-card empty-state">
      No quick-preset tasks found yet.
    </div>

    <div v-else class="task-list">
      <div v-for="task in sortedTasks" :key="task.id" class="card glass-card task-row" :class="task.state">
        <button class="state-button" :class="task.state" :aria-label="`Toggle task state (${task.state})`"
          @click="toggleTaskState(task)">
          {{ task.state === 'done' ? '✓' : task.state === 'cancelled' ? '–' : '○' }}
        </button>

        <div class="task-content">
          <div class="task-main">
            {{ task.body.replace(/\s*📅\s*\d{4}-\d{2}-\d{2}\s*/g, '').trim() }}
          </div>
          <div class="task-meta">
            <span>{{ task.presetLabel || 'Preset' }}</span>
            <span>{{ task.fileName }}</span>
          </div>
        </div>

        <input class="glass-input inline-date" type="date" :value="task.dueDate || ''"
          @change="changeDueDate(task, ($event.target as HTMLInputElement).value)">
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
  align-items: flex-start;
  gap: 12px;
  margin-bottom: 20px;
}

h1 {
  margin: 0;
  font-size: 1.8rem;
}

.subtitle {
  margin: 6px 0 0;
  color: var(--text-soft);
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

.quick-add-row {
  display: grid;
  grid-template-columns: minmax(140px, 0.8fr) 1fr minmax(140px, 0.6fr) auto;
  gap: 10px;
}

.glass-input {
  border: 1px solid color-mix(in srgb, var(--c-light) 16%, transparent);
  border-radius: 14px;
  padding: 10px 12px;
  background: color-mix(in srgb, var(--c-glass) 10%, transparent);
  color: var(--text);
}

.stats-row {
  display: flex;
  flex-wrap: wrap;
  gap: 10px 18px;
  color: var(--text-soft);
  font-size: 0.92rem;
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
  grid-template-columns: auto 1fr auto;
  align-items: center;
  gap: 12px;
}

.state-button {
  width: 36px;
  height: 36px;
  border-radius: 12px;
  border: 1px solid var(--state-pending-border);
  background: var(--state-pending-bg);
  color: var(--state-pending-text);
  font-size: 1rem;
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

.task-main {
  font-size: 1rem;
  line-height: 1.35;
  word-break: break-word;
}

.task-row.done .task-main {
  text-decoration: line-through;
  opacity: 0.8;
}

.task-row.cancelled .task-main {
  opacity: 0.65;
}

.task-meta {
  margin-top: 4px;
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  font-size: 0.78rem;
  color: var(--text-soft);
}

.inline-date {
  width: 146px;
}

@media (max-width: 860px) {
  .quick-add-row {
    grid-template-columns: 1fr;
  }

  .inline-date {
    width: 100%;
  }

  .task-row {
    grid-template-columns: auto 1fr;
  }
}
</style>
