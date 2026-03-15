<script setup lang="ts">
import { computed, onActivated, onBeforeUnmount, onMounted, ref } from 'vue'
import type { CSSProperties } from 'vue'
import { CheckSquare, FileText, List, Plus, Settings, Trash2 } from 'lucide-vue-next'
import { useRouter } from 'vue-router'
import PinToggleButton from '../components/PinToggleButton.vue'
import { FolderPicker } from '../plugins/folder-picker'
import { loadSettings } from '../lib/settings'
import type { AppFile } from '../lib/listFiles'
import {
  createListFile,
  loadDashboardData,
  saveListToFile,
  setFilePinned,
} from '../lib/listFiles'
import type { StoredTodoList } from '../lib/lists'

const HOME_REFRESH_DEBOUNCE_MS = 1200

interface DashboardSnapshot {
  lists: StoredTodoList[]
  tasks: StoredTodoList[]
  notes: AppFile[]
}

type DashboardCard =
  | { kind: 'list', item: StoredTodoList }
  | { kind: 'task', item: StoredTodoList }
  | { kind: 'note', item: AppFile }

const SWIPE_DELETE_WIDTH = 92
const SWIPE_OPEN_THRESHOLD = 46
const SWIPE_MOVE_THRESHOLD = 10

let cachedFolderUri = ''
let cachedAt = 0
let cachedSnapshot: DashboardSnapshot | null = null
let activeRefreshPromise: Promise<void> | null = null

const router = useRouter()
const settings = ref(loadSettings())
const baseFolderUri = computed(() => settings.value.baseFolderUri || '')

const lists = ref<StoredTodoList[]>([])
const tasks = ref<StoredTodoList[]>([])
const notes = ref<AppFile[]>([])

const isLoading = ref(true)
const error = ref('')

const selectedKinds = ref<DashboardCard['kind'][]>(['list', 'note', 'task'])
const allFilterKinds: DashboardCard['kind'][] = ['list', 'note', 'task']
const allFiltersActive = computed(() => selectedKinds.value.length === allFilterKinds.length)
const activeTheme = computed(() => settings.value.theme || 'light')

const swipeOffsets = ref<Record<string, number>>({})
const activeSwipeKey = ref<string | null>(null)
const swipeStartX = ref(0)
const swipeStartOffset = ref(0)
const swipeMoved = ref(false)

const isEmpty = computed(() => lists.value.length === 0 && tasks.value.length === 0 && notes.value.length === 0)

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

function hasDashboardContent() {
  return lists.value.length > 0 || tasks.value.length > 0 || notes.value.length > 0
}

function applySnapshot(snapshot: DashboardSnapshot) {
  lists.value = sortPinnedTodos(snapshot.lists)
  tasks.value = sortPinnedTodos(snapshot.tasks)
  notes.value = sortPinnedFiles(snapshot.notes)
}

function syncDashboardCache() {
  if (!baseFolderUri.value)
    return

  cachedFolderUri = baseFolderUri.value
  cachedAt = Date.now()
  cachedSnapshot = {
    lists: [...lists.value],
    tasks: [...tasks.value],
    notes: [...notes.value],
  }
}

async function refreshDashboard(options: { preferCache?: boolean, force?: boolean } = {}) {
  if (!baseFolderUri.value) {
    lists.value = []
    tasks.value = []
    notes.value = []
    error.value = ''
    isLoading.value = false
    return
  }

  const shouldDebounceRefresh = !options.force
    && cachedSnapshot
    && cachedFolderUri === baseFolderUri.value
    && Date.now() - cachedAt < HOME_REFRESH_DEBOUNCE_MS

  if (!options.force && options.preferCache && cachedSnapshot && cachedFolderUri === baseFolderUri.value) {
    applySnapshot(cachedSnapshot)
    isLoading.value = false
  }

  if (shouldDebounceRefresh)
    return

  if (activeRefreshPromise)
    return activeRefreshPromise

  if (!hasDashboardContent())
    isLoading.value = true

  error.value = ''

  activeRefreshPromise = (async () => {
    try {
      const dashboard = await loadDashboardData(baseFolderUri.value)
      lists.value = dashboard.lists
      tasks.value = dashboard.tasks
      notes.value = sortPinnedFiles(dashboard.files.filter(file => file.type === 'note'))
      syncDashboardCache()
    }
    catch (err) {
      console.error('Failed to load dashboard', err)
      error.value = 'Failed to load files. Check folder permissions.'
    }
    finally {
      isLoading.value = false
      activeRefreshPromise = null
    }
  })()

  return activeRefreshPromise
}

async function syncSettingsAndRefresh(options: { preferCache?: boolean, force?: boolean } = {}) {
  settings.value = loadSettings()
  await refreshDashboard(options)
}

function handleWindowFocus() {
  void syncSettingsAndRefresh({ preferCache: true, force: true })
}

function handleVisibilityChange() {
  if (document.visibilityState === 'visible')
    void syncSettingsAndRefresh({ preferCache: true, force: true })
}

onMounted(() => {
  void syncSettingsAndRefresh({ preferCache: true })
  window.addEventListener('focus', handleWindowFocus)
  document.addEventListener('visibilitychange', handleVisibilityChange)
})

onActivated(() => {
  void syncSettingsAndRefresh({ preferCache: true, force: true })
})

onBeforeUnmount(() => {
  window.removeEventListener('focus', handleWindowFocus)
  document.removeEventListener('visibilitychange', handleVisibilityChange)
})

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

function getPreviewOverflowCount(list: StoredTodoList) {
  const visibleCount = list.items
    .filter(item => item.text.trim().length > 0 && item.state !== 'cancelled')
    .length
  return Math.max(visibleCount - 3, 0)
}

function go(path: string) {
  router.push(path)
}

function makeDraftTitle(kind: 'list' | 'task') {
  const prefix = kind === 'task' ? 'Task' : 'List'
  const date = new Date().toISOString().slice(0, 10)
  return `${prefix} ${date}`
}

async function createTodoFile(kind: 'list' | 'task') {
  if (!baseFolderUri.value) {
    router.push('/settings')
    return
  }

  try {
    const created = await createListFile(baseFolderUri.value, {
      id: crypto.randomUUID(),
      title: makeDraftTitle(kind),
      items: [{ text: '', state: 'pending' }],
      pinned: false,
      type: kind,
    })

    const path = kind === 'task'
      ? `/task-list/${encodeURIComponent(created.fileName)}`
      : `/list/${encodeURIComponent(created.fileName)}`

    router.push({
      path,
      query: {
        draft: '1',
        draftTitle: created.title,
      },
    })
  }
  catch (err) {
    console.error(`Failed to create ${kind} file`, err)
  }
}

function openList(fileName: string) {
  router.push(`/list/${encodeURIComponent(fileName)}`)
}

function inferTaskPresetId(task: StoredTodoList): string | undefined {
  const nonEmptyItems = task.items.filter(item => item.text.trim().length > 0)

  const tagMatches = settings.value.quickTaskPresets
    .map((preset) => {
      const tag = preset.tag?.trim()
      if (!tag)
        return { preset, count: 0 }

      const count = nonEmptyItems.filter(item => item.text.includes(tag)).length
      return { preset, count }
    })
    .filter(match => match.count > 0)
    .sort((a, b) => b.count - a.count)

  if (tagMatches.length === 1)
    return tagMatches[0]?.preset.id

  const fileNameMatches = settings.value.quickTaskPresets.filter((preset) => {
    if (preset.saveMode !== 'single_file')
      return false

    const presetFile = preset.fileName || settings.value.listFileName || 'tasks.md'
    return presetFile === task.fileName
  })

  if (fileNameMatches.length === 1)
    return fileNameMatches[0]?.id

  return undefined
}

function openQuickTasks(task: StoredTodoList) {
  const presetId = inferTaskPresetId(task)

  if (presetId) {
    router.push({ path: '/tasks', query: { preset: presetId } })
    return
  }

  router.push('/tasks')
}

function openNote(fileName: string) {
  router.push(`/note/${encodeURIComponent(fileName)}`)
}

function openCard(card: DashboardCard) {
  if (getCardOffset(card) > 0) {
    closeSwipe(card)
    return
  }

  if (swipeMoved.value) {
    swipeMoved.value = false
    return
  }

  if (card.kind === 'list') {
    openList(card.item.fileName)
    return
  }

  if (card.kind === 'task') {
    openQuickTasks(card.item)
    return
  }

  openNote(card.item.name)
}

function getCardKey(card: DashboardCard) {
  if (card.kind === 'note')
    return `note:${card.item.name}`

  return `${card.kind}:${card.item.fileName}`
}

function getCardOffset(card: DashboardCard) {
  return swipeOffsets.value[getCardKey(card)] ?? 0
}

function getCardStyle(card: DashboardCard) {
  return {
    transform: `translateX(-${getCardOffset(card)}px)`,
  }
}

function getDeleteButtonStyle(card: DashboardCard): CSSProperties {
  const offset = getCardOffset(card)
  const opacity = Math.max(0, Math.min(1, offset / SWIPE_OPEN_THRESHOLD))

  return {
    opacity,
    pointerEvents: offset > 6 ? 'auto' : 'none',
  }
}

function closeAllSwipes(exceptKey?: string) {
  const nextOffsets: Record<string, number> = {}

  for (const [key, value] of Object.entries(swipeOffsets.value)) {
    if (exceptKey && key === exceptKey && value > 0)
      nextOffsets[key] = value
  }

  swipeOffsets.value = nextOffsets
}

function closeSwipe(card: DashboardCard) {
  const key = getCardKey(card)
  if (!swipeOffsets.value[key])
    return

  swipeOffsets.value = {
    ...swipeOffsets.value,
    [key]: 0,
  }
}

function onSwipeStart(card: DashboardCard, event: TouchEvent) {
  const touch = event.touches[0]
  if (!touch)
    return

  const key = getCardKey(card)
  closeAllSwipes(key)

  activeSwipeKey.value = key
  swipeStartX.value = touch.clientX
  swipeStartOffset.value = swipeOffsets.value[key] ?? 0
  swipeMoved.value = false
}

function onSwipeMove(card: DashboardCard, event: TouchEvent) {
  const touch = event.touches[0]
  if (!touch)
    return

  const key = getCardKey(card)
  if (activeSwipeKey.value !== key)
    return

  const deltaX = swipeStartX.value - touch.clientX
  const nextOffset = Math.min(
    SWIPE_DELETE_WIDTH,
    Math.max(0, swipeStartOffset.value + deltaX),
  )

  if (Math.abs(deltaX) > SWIPE_MOVE_THRESHOLD)
    swipeMoved.value = true

  swipeOffsets.value = {
    ...swipeOffsets.value,
    [key]: nextOffset,
  }
}

function onSwipeEnd(card: DashboardCard) {
  const key = getCardKey(card)
  if (activeSwipeKey.value !== key)
    return

  const currentOffset = swipeOffsets.value[key] ?? 0
  const shouldOpen = currentOffset >= SWIPE_OPEN_THRESHOLD

  swipeOffsets.value = {
    ...swipeOffsets.value,
    [key]: shouldOpen ? SWIPE_DELETE_WIDTH : 0,
  }

  activeSwipeKey.value = null
}

async function deleteCard(card: DashboardCard) {
  if (!baseFolderUri.value)
    return

  const typeLabel = getCardTypeLabel(card.kind).toLowerCase()
  const confirmed = window.confirm(`Are you sure you want to delete this ${typeLabel}?`)
  if (!confirmed)
    return

  closeSwipe(card)

  try {
    const fileName = card.kind === 'note' ? card.item.name : card.item.fileName
    await FolderPicker.deleteFile({
      folderUri: baseFolderUri.value,
      fileName,
    })

    if (card.kind === 'list')
      lists.value = lists.value.filter(list => list.fileName !== card.item.fileName)
    else if (card.kind === 'task')
      tasks.value = tasks.value.filter(task => task.fileName !== card.item.fileName)
    else
      notes.value = notes.value.filter(note => note.name !== card.item.name)

    const key = getCardKey(card)
    if (key in swipeOffsets.value) {
      const nextOffsets = { ...swipeOffsets.value }
      delete nextOffsets[key]
      swipeOffsets.value = nextOffsets
    }

    syncDashboardCache()
  }
  catch (err) {
    console.error('Failed to delete card item', err)
  }
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
    syncDashboardCache()
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
    syncDashboardCache()
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
      <h1>Quick Capture</h1>

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
        <button class="glass-button glass-button--primary" @click="createTodoFile('list')">New List</button>
        <button class="glass-button glass-button--primary" @click="go('/tasks')">Quick Tasks</button>
        <button class="glass-button glass-button--primary" @click="go('/note')">New Note</button>
      </div>
    </div>

    <template v-else>
      <div class="filter-bar">
        <button class="glass-button filter-chip" :class="{ 'filter-chip--active': allFiltersActive }" type="button"
          @click="resetFilters">
          All
        </button>
        <button class="glass-button filter-chip"
          :class="{ 'filter-chip--active': isKindEnabled('list') && !allFiltersActive }" type="button"
          @click="toggleKind('list')">
          Lists
        </button>
        <button class="glass-button filter-chip"
          :class="{ 'filter-chip--active': isKindEnabled('note') && !allFiltersActive }" type="button"
          @click="toggleKind('note')">
          Notes
        </button>
        <button class="glass-button filter-chip"
          :class="{ 'filter-chip--active': isKindEnabled('task') && !allFiltersActive }" type="button"
          @click="toggleKind('task')">
          Tasks
        </button>
      </div>

      <div class="card-grid">
        <div v-for="card in filteredDashboardCards" :key="card.kind === 'note' ? card.item.id : card.item.fileName"
          class="swipe-item" @touchstart="onSwipeStart(card, $event)" @touchmove="onSwipeMove(card, $event)"
          @touchend="onSwipeEnd(card)" @touchcancel="onSwipeEnd(card)">
          <button class="swipe-delete-button" type="button" :style="getDeleteButtonStyle(card)"
            :aria-label="`Delete ${getCardTypeLabel(card.kind).toLowerCase()}`" @click.stop="deleteCard(card)">
            <Trash2 :size="18" />
            <span>Delete</span>
          </button>

          <div class="glass-button collection-card"
            :class="{ 'collection-card--swiping': activeSwipeKey === getCardKey(card) }" :style="getCardStyle(card)"
            @click="openCard(card)">
            <div class="collection-top">
              <div class="collection-main">
                <div class="type-icon-wrap" :class="{
                  'type-icon-wrap--dark': activeTheme === 'dark',
                  'type-icon-wrap--dim': activeTheme === 'dim',
                }">
                  <component :is="getCardIcon(card.kind)" :size="18" class="type-icon" />
                </div>
                <div class="collection-body">
                  <h3>{{ card.item.title || ('name' in card.item ? card.item.name : 'Untitled') }}</h3>

                  <template v-if="card.kind === 'note'">
                    <p class="note-preview">{{ card.item.preview || 'No preview available.' }}</p>
                  </template>

                  <template v-else>
                    <ul class="todo-preview-list">
                      <li v-for="(previewItem, index) in getPreviewItems(card.item)" :key="index"
                        class="todo-preview-item">
                        <span class="todo-preview-dot" :class="{ 'is-done': previewItem.state === 'done' }" />
                        <span class="todo-preview-text" :class="{ 'is-done': previewItem.state === 'done' }">{{
                          previewItem.text }}</span>
                      </li>
                      <li v-if="getPreviewOverflowCount(card.item) > 0" class="todo-preview-more">
                        ... and {{ getPreviewOverflowCount(card.item) }} more
                      </li>
                    </ul>
                  </template>
                </div>
              </div>

              <div class="card-actions" @click.stop>
                <PinToggleButton class="home-card-action-button" :pinned="card.item.pinned" :size="16"
                  :item-label="getCardTypeLabel(card.kind).toLowerCase()"
                  @toggle="card.kind === 'note' ? toggleNotePin(card.item) : toggleTodoPin(card.item, card.kind)" />
                <button class="glass-icon-button home-card-action-button delete-icon-button" type="button"
                  :aria-label="`Delete ${getCardTypeLabel(card.kind).toLowerCase()}`" @click.stop="deleteCard(card)">
                  <Trash2 :size="16" />
                </button>
              </div>
            </div>

            <div v-if="card.kind !== 'note'" class="summary-progress-track">
              <div class="summary-progress-fill" :style="{ width: `${getProgressPercent(card.item)}%` }" />
            </div>

            <div class="card-footer" :class="{ 'card-footer--note': card.kind === 'note' }">
              <div class="footer-spacer" />
              <div class="footer-meta">
                <span class="type-pill">{{ getCardTypeLabel(card.kind) }}</span>
                <span v-if="card.kind !== 'note'" class="meta-line meta-line--stat">{{ getCompletedCount(card.item)
                }}/{{ getActiveItems(card.item).length }} done</span>
                <span v-if="card.kind !== 'note'" class="meta-line meta-line--percent">{{
                  getProgressPercent(card.item)
                }}%</span>
                <span class="meta-line">{{ card.item.updated || card.item.created || '' }}</span>
              </div>
            </div>
          </div>
        </div>
      </div>
    </template>

    <div class="quick-add-bar">
      <div class="quick-add-shell">
        <div class="quick-add-heading">
          <Plus :size="14" />
          <span>Quick Add</span>
        </div>

        <div class="quick-add-actions">

          <button class="glass-icon-button quick-add-button quick-add-button--icon" title="Quick tasks"
            aria-label="Quick tasks" @click="go('/tasks')">
            <CheckSquare :size="16" />
          </button>

          <button class="glass-icon-button quick-add-button quick-add-button--icon" title="Create list"
            aria-label="Create list" @click="createTodoFile('list')">
            <List :size="16" />
          </button>

          <button class="glass-icon-button quick-add-button quick-add-button--icon" title="Create note"
            aria-label="Create note" @click="go('/note')">
            <FileText :size="16" />
          </button>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.page {
  min-height: 100vh;
  padding: var(--page-top-padding) 20px calc(176px + env(safe-area-inset-bottom, 0px));
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

.filter-bar {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  margin-bottom: 14px;
}

.filter-chip {
  min-height: 40px;
  padding: 10px 16px;
  border-radius: 16px;
  font-size: 0.9rem;
  justify-content: center;
}

.filter-chip--active {
  border-color: color-mix(in srgb, var(--primary) 48%, var(--text));
  color: var(--primary);
  background: color-mix(in srgb, var(--primary) 10%, var(--c-glass) 12%);
}

.card-grid {
  display: grid;
  gap: 12px;
  grid-template-columns: 1fr;
  margin-bottom: 20px;
}

.swipe-item {
  position: relative;
  border-radius: 30px;
  overflow: hidden;
}

.swipe-delete-button {
  position: absolute;
  top: 0;
  right: 0;
  bottom: 0;
  width: 92px;
  border: 0;
  border-radius: 0 30px 30px 0;
  display: inline-flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 6px;
  font-size: 0.73rem;
  font-weight: 700;
  letter-spacing: 0.01em;
  color: color-mix(in srgb, white 95%, var(--danger) 5%);
  background:
    linear-gradient(180deg,
      color-mix(in srgb, var(--danger) 84%, black 6%),
      color-mix(in srgb, var(--danger) 74%, black 16%));
  z-index: 1;
  opacity: 0;
  transition: opacity 0.18s ease;
}

.swipe-delete-button:active {
  filter: brightness(0.94);
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
  position: relative;
  z-index: 2;
  transition: transform 0.2s ease;
}

.collection-card--swiping {
  transition: none;
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
}

.type-icon {
  color: inherit;
  stroke: currentColor;
  stroke-width: 2.2;
}

.type-icon-wrap--dark,
.type-icon-wrap--dim {
  border-color: color-mix(in srgb, var(--c-light) 42%, var(--c-dark) 18%);
  background:
    linear-gradient(180deg,
      color-mix(in srgb, var(--c-dark) 66%, var(--c-glass) 8%),
      color-mix(in srgb, var(--c-dark) 56%, var(--c-light) 14%));
  color: color-mix(in srgb, var(--c-light) 84%, white 10%);
}

.collection-body {
  min-width: 0;
  flex: 1;
  width: 0;
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  text-align: left;
  overflow: hidden;
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

.card-actions {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  flex-shrink: 0;
}

.home-card-action-button {
  width: 34px;
  height: 34px;
  min-width: 34px;
  min-height: 34px;
  padding: 0;
  border-radius: 11px;
  color: var(--text-soft);
}

.home-card-action-button:hover,
.home-card-action-button:focus-visible {
  color: var(--primary);
}

.delete-icon-button {
  border-color: color-mix(in srgb, var(--text) 18%, transparent);
  background: color-mix(in srgb, var(--c-glass) 14%, transparent);
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
  width: 100%;
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
  width: 100%;
}

.todo-preview-item {
  display: flex;
  align-items: center;
  gap: 10px;
  color: var(--text-soft);
  min-width: 0;
  max-width: 100%;
}

.todo-preview-more {
  color: var(--text-soft);
  font-size: 0.88rem;
  font-weight: 600;
  opacity: 0.85;
  padding-left: 23px;
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
  border: 1px solid var(--border);
  border-radius: 24px;
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
  flex-wrap: wrap;
}

.quick-add-bar {
  position: fixed;
  bottom: 0;
  left: 0;
  right: 0;
  z-index: 50;
  display: flex;
  justify-content: center;
  padding: 14px 20px calc(14px + env(safe-area-inset-bottom, 0px));
  background: color-mix(in srgb, var(--bg) 80%, transparent);
  backdrop-filter: blur(12px);
  -webkit-backdrop-filter: blur(12px);
  border-top: 1px solid var(--border);
}

.quick-add-shell {
  width: min(780px, 100%);
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
}

.quick-add-heading {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  color: var(--text-soft);
  font-size: 0.78rem;
  font-weight: 700;
  letter-spacing: 0.06em;
  text-transform: uppercase;
}

.quick-add-actions {
  display: flex;
  flex-wrap: wrap;
  justify-content: center;
  gap: 10px;
}

.quick-add-button {
  width: 44px;
  height: 44px;
  padding: 0;
  display: flex;
  align-items: center;
  justify-content: center;
}

.quick-add-button--icon {
  border-radius: 14px;
}

@media (max-width: 560px) {
  .collection-top {
    display: grid;
    grid-template-columns: minmax(0, 1fr) auto;
    align-items: start;
  }

  .collection-main {
    width: auto;
    min-width: 0;
  }

  .collection-body {
    min-width: 0;
  }

  .card-actions {
    width: auto;
    flex-direction: column;
    justify-content: flex-start;
    margin-top: 0;
  }
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
