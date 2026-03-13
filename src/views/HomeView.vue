<script setup lang="ts">
import { computed, onActivated, onMounted, ref } from 'vue'
import { CheckSquare, FileText, List, Plus, Settings } from 'lucide-vue-next'
import { useRouter } from 'vue-router'
import PinToggleButton from '../components/PinToggleButton.vue'
import { loadSettings } from '../lib/settings'
import type { AppFile } from '../lib/listFiles'
import {
  loadAllFiles,
  loadTodoFilesFromFolder,
  saveListToFile,
  setFilePinned,
} from '../lib/listFiles'
import type { StoredTodoList } from '../lib/lists'

const router = useRouter()
const settings = loadSettings()
const baseFolderUri = computed(() => settings.baseFolderUri || '')
const quickTaskPresets = computed(() => settings.quickTaskPresets.slice(0, 2))

const lists = ref<StoredTodoList[]>([])
const tasks = ref<StoredTodoList[]>([])
const notes = ref<AppFile[]>([])

const isLoading = ref(true)
const error = ref('')

const isEmpty = computed(() => lists.value.length === 0 && tasks.value.length === 0 && notes.value.length === 0)
type DashboardCard =
  | { kind: 'list', item: StoredTodoList }
  | { kind: 'task', item: StoredTodoList }
  | { kind: 'note', item: AppFile }

const selectedKinds = ref<DashboardCard['kind'][]>(['list', 'note', 'task'])
const allFilterKinds: DashboardCard['kind'][] = ['list', 'note', 'task']
const allFiltersActive = computed(() => selectedKinds.value.length === allFilterKinds.length)

const dashboardCards = computed<DashboardCard[]>(() => {
  const cards: DashboardCard[] = [
    ...lists.value.map(item => ({ kind: 'list' as const, item })),
    ...tasks.value.map(item => ({ kind: 'task' as const, item })),
    ...notes.value.map(item => ({ kind: 'note' as const, item })),
  ]

  return cards.sort((a, b) => {
    if (a.item.pinned && !b.item.pinned)
      return -1
    if (!a.item.pinned && b.item.pinned)
      return 1

    const aDate = a.item.updated || a.item.created || ''
    const bDate = b.item.updated || b.item.created || ''
    return bDate.localeCompare(aDate)
  })
})

const filteredDashboardCards = computed(() => {
  if (allFiltersActive.value)
    return dashboardCards.value

  return dashboardCards.value.filter(card => selectedKinds.value.includes(card.kind))
})

function sortPinnedTodos(items: StoredTodoList[]): StoredTodoList[] {
  return [...items].sort((a, b) => {
    if (a.pinned && !b.pinned)
      return -1
    if (!a.pinned && b.pinned)
      return 1
    return a.fileName.localeCompare(b.fileName)
  })
}

function sortPinnedFiles(items: AppFile[]): AppFile[] {
  return [...items].sort((a, b) => {
    if (a.pinned && !b.pinned)
      return -1
    if (!a.pinned && b.pinned)
      return 1
    return a.name.localeCompare(b.name)
  })
}

async function refreshDashboard() {
  if (!baseFolderUri.value) {
    lists.value = []
    tasks.value = []
    notes.value = []
    error.value = ''
    isLoading.value = false
    return
  }

  isLoading.value = true
  error.value = ''

  try {
    const [allFiles, listFiles, taskFiles] = await Promise.all([
      loadAllFiles(baseFolderUri.value),
      loadTodoFilesFromFolder(baseFolderUri.value, 'list'),
      loadTodoFilesFromFolder(baseFolderUri.value, 'task'),
    ])

    notes.value = sortPinnedFiles(allFiles.filter(file => file.type === 'note'))
    lists.value = sortPinnedTodos(listFiles)
    tasks.value = sortPinnedTodos(taskFiles)
  }
  catch (err) {
    console.error('Failed to load dashboard', err)
    error.value = 'Failed to load files. Check folder permissions.'
  }
  finally {
    isLoading.value = false
  }
}

onMounted(refreshDashboard)
onActivated(refreshDashboard)

function getActiveItems(list: StoredTodoList) {
  return list.items.filter(item => item.text.trim().length > 0 && item.state !== 'cancelled')
}

function getCompletedCount(list: StoredTodoList) {
  return list.items.filter(item => item.state === 'done' && item.text.trim().length > 0).length
}

function getProgressPercent(list: StoredTodoList) {
  const active = getActiveItems(list)
  if (active.length === 0)
    return 0

  const done = active.filter(item => item.state === 'done').length
  return Math.round((done / active.length) * 100)
}

function getPreviewItems(list: StoredTodoList) {
  return list.items
    .filter(item => item.text.trim().length > 0 && item.state !== 'cancelled')
    .slice(0, 3)
}

function go(path: string) {
  router.push(path)
}

function goTaskPreset(presetId: string) {
  router.push(`/task/${presetId}`)
}

function openList(fileName: string) {
  router.push(`/list/${encodeURIComponent(fileName)}`)
}

function openTaskList(fileName: string) {
  router.push(`/task-list/${encodeURIComponent(fileName)}`)
}

function openNote(fileName: string) {
  router.push(`/note/${encodeURIComponent(fileName)}`)
}

function openCard(card: DashboardCard) {
  if (card.kind === 'list') {
    openList(card.item.fileName)
    return
  }

  if (card.kind === 'task') {
    openTaskList(card.item.fileName)
    return
  }

  openNote(card.item.name)
}

function getCardIcon(kind: DashboardCard['kind']) {
  if (kind === 'list')
    return List
  if (kind === 'task')
    return CheckSquare
  return FileText
}

function getCardTypeLabel(kind: DashboardCard['kind']) {
  if (kind === 'list')
    return 'List'
  if (kind === 'task')
    return 'Task'
  return 'Note'
}

function resetFilters() {
  selectedKinds.value = [...allFilterKinds]
}

function isKindEnabled(kind: DashboardCard['kind']) {
  return selectedKinds.value.includes(kind)
}

function toggleKind(kind: DashboardCard['kind']) {
  if (allFiltersActive.value) {
    selectedKinds.value = [kind]
    return
  }

  if (selectedKinds.value.includes(kind)) {
    const nextKinds = selectedKinds.value.filter(value => value !== kind)
    selectedKinds.value = nextKinds.length > 0 ? nextKinds : [...allFilterKinds]
    return
  }

  selectedKinds.value = [...selectedKinds.value, kind]
}

async function toggleTodoPin(item: StoredTodoList, collection: 'list' | 'task') {
  if (!baseFolderUri.value)
    return

  const previousPinned = item.pinned
  item.pinned = !item.pinned

  if (collection === 'list')
    lists.value = sortPinnedTodos(lists.value)
  else
    tasks.value = sortPinnedTodos(tasks.value)

  try {
    await saveListToFile(baseFolderUri.value, item)
  }
  catch (err) {
    item.pinned = previousPinned
    if (collection === 'list')
      lists.value = sortPinnedTodos(lists.value)
    else
      tasks.value = sortPinnedTodos(tasks.value)

    console.error('Failed to update todo pin state', err)
  }
}

async function toggleNotePin(note: AppFile) {
  if (!baseFolderUri.value)
    return

  const nextPinned = !note.pinned
  const previousPinned = note.pinned
  note.pinned = nextPinned
  notes.value = sortPinnedFiles(notes.value)

  try {
    await setFilePinned(baseFolderUri.value, note.name, nextPinned)
  }
  catch (err) {
    note.pinned = previousPinned
    notes.value = sortPinnedFiles(notes.value)
    console.error('Failed to update note pin state', err)
  }
}
</script>

<template>
  <div class="page">
    <div class="top-row">
      <div>
        <h1>Quick Capture</h1>
        <p class="subtitle">Lists, notes, and task files in one place</p>
      </div>

      <button class="glass-icon-button" aria-label="Go to settings" @click="go('/settings')">
        <Settings :size="22" />
      </button>
    </div>

    <div v-if="isLoading" class="empty-state card">
      <p>Loading dashboard...</p>
    </div>

    <div v-else-if="error" class="empty-state card">
      <p class="error-text">{{ error }}</p>
    </div>

    <div v-else-if="!baseFolderUri" class="empty-state card">
      <div class="empty-icon">#</div>
      <p class="empty-title">No Folder Selected</p>
      <p class="empty-text">Please select a folder in settings to begin.</p>
      <button class="glass-button glass-button--primary" @click="go('/settings')">Go to Settings</button>
    </div>

    <div v-else-if="isEmpty" class="empty-state card">
      <div class="empty-icon">*</div>
      <p class="empty-title">Folder is Empty</p>
      <p class="empty-text">Create a new list, task file, or note to get started.</p>
      <div class="button-group">
        <button class="glass-button glass-button--primary" @click="go('/lists')">New List</button>
        <button class="glass-button glass-button--primary" @click="go('/note')">New Note</button>
      </div>
    </div>

    <template v-else>
      <div class="filter-bar">
        <button
          class="glass-button filter-chip"
          :class="{ 'filter-chip--active': allFiltersActive }"
          type="button"
          @click="resetFilters"
        >
          All
        </button>
        <button
          class="glass-button filter-chip"
          :class="{ 'filter-chip--active': isKindEnabled('list') && !allFiltersActive }"
          type="button"
          @click="toggleKind('list')"
        >
          Lists
        </button>
        <button
          class="glass-button filter-chip"
          :class="{ 'filter-chip--active': isKindEnabled('note') && !allFiltersActive }"
          type="button"
          @click="toggleKind('note')"
        >
          Notes
        </button>
        <button
          class="glass-button filter-chip"
          :class="{ 'filter-chip--active': isKindEnabled('task') && !allFiltersActive }"
          type="button"
          @click="toggleKind('task')"
        >
          Tasks
        </button>
      </div>

      <div class="card-grid">
        <button v-for="card in filteredDashboardCards"
        :key="card.kind === 'note' ? card.item.id : card.item.fileName"
        class="glass-button collection-card"
        @click="openCard(card)">
        <div class="collection-top">
          <div class="collection-main">
            <div class="type-icon-wrap">
              <component :is="getCardIcon(card.kind)" :size="18" class="type-icon" />
            </div>
            <div class="collection-body">
              <h3>{{ card.item.title || ('name' in card.item ? card.item.name : 'Untitled') }}</h3>

              <template v-if="card.kind === 'note'">
                <p class="note-preview">{{ card.item.preview || 'No preview available.' }}</p>
              </template>

              <template v-else>
                <ul class="todo-preview-list">
                  <li v-for="(previewItem, index) in getPreviewItems(card.item)" :key="index" class="todo-preview-item">
                    <span class="todo-preview-dot" :class="{ 'is-done': previewItem.state === 'done' }" />
                    <span class="todo-preview-text" :class="{ 'is-done': previewItem.state === 'done' }">{{ previewItem.text }}</span>
                  </li>
                </ul>
              </template>
            </div>
          </div>

          <PinToggleButton
            :pinned="card.item.pinned"
            :item-label="getCardTypeLabel(card.kind).toLowerCase()"
            @toggle="card.kind === 'note' ? toggleNotePin(card.item) : toggleTodoPin(card.item, card.kind)"
          />
        </div>

        <div v-if="card.kind !== 'note'" class="summary-progress-track">
          <div class="summary-progress-fill" :style="{ width: `${getProgressPercent(card.item)}%` }" />
        </div>

        <div class="card-footer" :class="{ 'card-footer--note': card.kind === 'note' }">
          <div class="footer-spacer" />
          <div class="footer-meta">
            <span class="type-pill">{{ getCardTypeLabel(card.kind) }}</span>
            <span v-if="card.kind !== 'note'" class="meta-line meta-line--stat">{{ getCompletedCount(card.item) }}/{{ getActiveItems(card.item).length }} done</span>
            <span v-if="card.kind !== 'note'" class="meta-line meta-line--percent">{{ getProgressPercent(card.item) }}%</span>
            <span class="meta-line">{{ card.item.updated || card.item.created || '' }}</span>
          </div>
        </div>
      </button>
      </div>
    </template>

    <div class="quick-add-bar">
      <button v-for="preset in quickTaskPresets" :key="preset.id" class="glass-icon-button quick-add-button"
        @click="goTaskPreset(preset.id)">
        {{ preset.label }}
      </button>
      <button class="glass-icon-button quick-add-button" @click="go('/lists')">
        <Plus :size="12" />
        Lists
      </button>
      <button class="glass-icon-button quick-add-button" @click="go('/tasks')">
        <Plus :size="12" />
        Tasks
      </button>
      <button class="glass-icon-button quick-add-button" @click="go('/note')">
        <Plus :size="12" />
        Note
      </button>
    </div>
  </div>
</template>

<style scoped>
.page {
  min-height: 100vh;
  padding: 24px 20px 120px;
  color: var(--text);
}

.top-row {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 16px;
  margin-bottom: 22px;
}

h1 {
  margin: 0;
  font-size: 1.8rem;
}

.subtitle {
  margin: 8px 0 0;
  color: var(--text-soft);
}

.filter-bar {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  margin-bottom: 14px;
}

.filter-chip {
  min-height: 40px;
  padding: 10px 16px;
  border-radius: 999px;
  font-size: 0.9rem;
  justify-content: center;
}

.filter-chip--active {
  border-color: color-mix(in srgb, var(--primary) 48%, var(--text));
  color: var(--primary);
  background: color-mix(in srgb, var(--primary) 10%, var(--c-glass) 12%);
  box-shadow:
    0 0 0 1px color-mix(in srgb, var(--primary) 18%, transparent),
    inset 0 0 0 1px color-mix(in srgb, var(--c-light) calc(var(--glass-reflex-light) * 10%), transparent),
    inset 1.5px 2px 0 -1px color-mix(in srgb, var(--c-light) calc(var(--glass-reflex-light) * 88%), transparent),
    inset -1px -2px 0 -1px color-mix(in srgb, var(--c-light) calc(var(--glass-reflex-light) * 68%), transparent),
    inset 0 -1px 4px 0 color-mix(in srgb, var(--c-dark) calc(var(--glass-reflex-dark) * 12%), transparent),
    0 8px 18px color-mix(in srgb, var(--primary) 14%, transparent);
}

.card-grid {
  display: grid;
  gap: 12px;
  grid-template-columns: 1fr;
  margin-bottom: 20px;
}

.collection-card {
  text-align: left;
  border-radius: 30px;
  padding: 18px 18px 16px;
  display: flex;
  flex-direction: column;
  align-items: stretch;
  justify-content: flex-start;
  gap: 12px;
  min-height: 190px;
  width: 100%;
  border-color: color-mix(in srgb, var(--c-light) 18%, transparent);
  background:
    radial-gradient(circle at top right, color-mix(in srgb, var(--primary) 14%, transparent), transparent 26%),
    linear-gradient(180deg,
      color-mix(in srgb, var(--c-light) 14%, transparent),
      color-mix(in srgb, var(--c-glass) 12%, transparent)),
    color-mix(in srgb, var(--surface) 84%, transparent);
  color: var(--text);
  box-shadow:
    inset 0 0 0 1px color-mix(in srgb, var(--c-light) calc(var(--glass-reflex-light) * 10%), transparent),
    inset 0 1px 0 color-mix(in srgb, var(--c-light) calc(var(--glass-reflex-light) * 24%), transparent),
    inset 0 -1px 4px color-mix(in srgb, var(--c-dark) calc(var(--glass-reflex-dark) * 10%), transparent),
    0 18px 40px color-mix(in srgb, var(--c-dark) calc(var(--glass-reflex-dark) * 16%), transparent);
}

.collection-top {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 14px;
}

.collection-main {
  display: flex;
  align-items: flex-start;
  gap: 14px;
  min-width: 0;
  flex: 1;
}

.type-icon-wrap {
  width: 46px;
  height: 52px;
  flex-shrink: 0;
  border-radius: 16px;
  display: grid;
  place-items: center;
  border: 1px solid color-mix(in srgb, var(--c-light) 24%, transparent);
  background:
    linear-gradient(180deg,
      color-mix(in srgb, var(--c-light) 78%, var(--surface) 22%),
      color-mix(in srgb, var(--c-light) 52%, var(--c-glass) 48%));
  color: color-mix(in srgb, var(--text-soft) 74%, var(--text) 26%);
  box-shadow:
    inset 0 1px 0 color-mix(in srgb, var(--c-light) 78%, transparent),
    0 6px 14px color-mix(in srgb, var(--c-dark) calc(var(--glass-reflex-dark) * 10%), transparent);
}

.type-icon {
  color: inherit;
  stroke: currentColor;
  stroke-width: 2.2;
  flex-shrink: 0;
}

.collection-body {
  min-width: 0;
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  text-align: left;
}

.collection-top h3 {
  margin: 0;
  padding-top: 4px;
  font-size: 1.25rem;
  font-weight: 700;
  line-height: 1.18;
  text-align: left;
  color: var(--text);
}

.summary-progress-track {
  width: 100%;
  height: 6px;
  border-radius: 999px;
  background: color-mix(in srgb, var(--c-dark) 8%, var(--c-light) 12%);
  overflow: hidden;
}

.summary-progress-fill {
  height: 100%;
  border-radius: 999px;
  transition: width 0.2s ease;
  background: linear-gradient(90deg,
    color-mix(in srgb, var(--primary) 84%, white 10%),
    color-mix(in srgb, #7dc8ff 68%, var(--primary) 32%));
}

.note-preview {
  margin: 0;
  color: var(--text-soft);
  font-size: 1rem;
  line-height: 1.45;
  display: -webkit-box;
  line-clamp: 3;
  -webkit-line-clamp: 3;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.meta-line {
  margin: 0;
  color: var(--text-soft);
  font-size: 0.8rem;
  text-align: left;
}

.todo-preview-list {
  margin: 0;
  padding: 0 0 0 2px;
  list-style: none;
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.todo-preview-item {
  display: flex;
  align-items: center;
  gap: 10px;
  color: var(--text-soft);
}

.todo-preview-dot {
  width: 13px;
  height: 13px;
  border-radius: 999px;
  border: 1.6px solid color-mix(in srgb, var(--text-soft) 60%, transparent);
  flex-shrink: 0;
}

.todo-preview-dot.is-done {
  background: color-mix(in srgb, var(--primary) 80%, var(--c-light) 20%);
  border-color: color-mix(in srgb, var(--primary) 80%, var(--c-light) 20%);
}

.todo-preview-text {
  min-width: 0;
  font-size: 0.95rem;
  line-height: 1.25;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.todo-preview-text.is-done {
  text-decoration: line-through;
  opacity: 0.7;
}

.card-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-top: 2px;
}

.card-footer--note {
  margin-top: auto;
}

.footer-spacer {
  flex: 1;
}

.footer-meta {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 10px;
  flex-wrap: wrap;
  margin-left: auto;
}

.type-pill {
  display: inline-flex;
  align-items: center;
  min-height: 34px;
  padding: 0 14px;
  border-radius: 999px;
  border: 1px solid color-mix(in srgb, var(--c-light) 18%, transparent);
  background: color-mix(in srgb, var(--c-light) 10%, transparent);
  color: var(--text-soft);
  font-size: 0.85rem;
  font-weight: 500;
}

.meta-line--stat,
.meta-line--percent {
  font-weight: 600;
}

.empty-state {
  padding: 28px 20px;
  text-align: center;
  margin-top: 24px;
}

.card.empty-state {
  background: var(--surface);
  backdrop-filter: blur(14px);
  -webkit-backdrop-filter: blur(14px);
  border: 1px solid var(--border);
  border-radius: 24px;
  box-shadow: var(--shadow);
}

.empty-icon {
  width: 56px;
  height: 56px;
  margin: 0 auto 14px;
  display: grid;
  place-items: center;
  border-radius: 18px;
  background: var(--primary-soft);
  font-size: 1.2rem;
  font-weight: 700;
}

.empty-title {
  margin: 0;
  font-size: 1.1rem;
  font-weight: 700;
}

.empty-text {
  margin: 8px 0 18px;
  color: var(--text-soft);
  font-size: 0.95rem;
}

.error-text {
  color: var(--danger);
}

.button-group {
  display: flex;
  gap: 12px;
  justify-content: center;
}

.quick-add-bar {
  position: fixed;
  bottom: 0;
  left: 0;
  right: 0;
  display: flex;
  justify-content: center;
  gap: 10px;
  padding: 16px 20px;
  background: color-mix(in srgb, var(--bg) 80%, transparent);
  backdrop-filter: blur(12px);
  -webkit-backdrop-filter: blur(12px);
  border-top: 1px solid var(--border);
}

.quick-add-button {
  padding: 10px 18px;
  font-size: 0.9rem;
  font-weight: 600;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 4px;
}

@media (min-width: 720px) {
  .page {
    max-width: 980px;
    margin: 0 auto;
  }

  .card-grid {
    gap: 16px;
  }
}
</style>
