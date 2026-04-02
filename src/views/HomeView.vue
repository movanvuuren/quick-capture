<script setup lang="ts">
import type { CSSProperties } from 'vue'
import type { AppFile } from '../lib/listFiles'
import type { StoredTodoList } from '../lib/lists'
import { Archive, CheckSquare, FileText, List, Search, Settings, Star, Trash2 } from 'lucide-vue-next'
import { computed, onActivated, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import BottomActionNav from '../components/BottomActionNav.vue'
import PinToggleButton from '../components/PinToggleButton.vue'
import {
  addTagToFrontmatter,
  createListFile,
  saveListToFile,
  setFilePinned,
} from '../lib/listFiles'
import { escapeHtml, renderNotePreviewMarkdown } from '../lib/notePreviewRenderer'
import { getActiveItems, getCompletedCount, getPreviewItems, getPreviewOverflowCount, getProgressPercent } from '../lib/todoSelectors'
import { useDashboardData } from '../lib/useDashboardData'
import { FolderPicker } from '../plugins/folder-picker'

type DashboardCard
  = | { kind: 'list', item: StoredTodoList }
  | { kind: 'task', item: StoredTodoList }
  | { kind: 'note', item: AppFile }

type DashboardCardEntry = DashboardCard & {
  sortPinned: number
  sortDate: number
  sortName: string
}

const SWIPE_DELETE_WIDTH = 92
const SWIPE_OPEN_THRESHOLD = 46
const SWIPE_MOVE_THRESHOLD = 10
const PULL_REFRESH_THRESHOLD = 72
const PULL_REFRESH_MAX_DISTANCE = 120

const router = useRouter()
const {
  settings,
  baseFolderUri,
  lists,
  tasks,
  notes,
  isLoading,
  error,
  sortPinnedTodos,
  sortPinnedFiles,
  syncDashboardCache,
  syncSettingsAndRefresh,
} = useDashboardData()
const notePreviewHtmlCache = new Map<string, string>()

const selectedKinds = ref<DashboardCard['kind'][]>(['list', 'note', 'task'])
const allFilterKinds: DashboardCard['kind'][] = ['list', 'note', 'task']
const allFiltersActive = computed(() => selectedKinds.value.length === allFilterKinds.length)
const activeTheme = computed(() => settings.value.theme || 'light')

const swipeOffsets = ref<Record<string, number>>({})
const activeSwipeKey = ref<string | null>(null)
const swipeStartX = ref(0)
const swipeStartY = ref(0)
const swipeStartOffset = ref(0)
const swipeMoved = ref(false)

const pullStartY = ref<number | null>(null)
const pullStartX = ref<number | null>(null)
const pullDistance = ref(0)
const pullAxisLock = ref<'none' | 'vertical' | 'horizontal'>('none')
const isPullRefreshing = ref(false)
const pullReadyHapticPlayed = ref(false)

const isEmpty = computed(() => lists.value.length === 0 && tasks.value.length === 0 && notes.value.length === 0)
const dashboardCards = ref<DashboardCardEntry[]>([])
const isPullReady = computed(() => pullDistance.value >= PULL_REFRESH_THRESHOLD)
const showPullIndicator = computed(() => pullDistance.value > 0 || isPullRefreshing.value)
const pageContentStyle = computed<CSSProperties>(() => ({
  transform: `translateY(${pullDistance.value}px)`,
  transition: isPullRefreshing.value || pullDistance.value === 0 ? 'transform 0.18s ease' : 'none',
}))

function toSortDate(item: { updated?: string, created?: string }) {
  const date = item.updated || item.created || ''
  const parsed = Date.parse(date)
  return Number.isFinite(parsed) ? parsed : 0
}

function buildSortedDashboardCards() {
  const cards: DashboardCardEntry[] = [
    ...lists.value.map(item => ({
      kind: 'list' as const,
      item,
      sortPinned: item.pinned ? 0 : 1,
      sortDate: toSortDate(item),
      sortName: item.fileName,
    })),
    ...tasks.value.map(item => ({
      kind: 'task' as const,
      item,
      sortPinned: item.pinned ? 0 : 1,
      sortDate: toSortDate(item),
      sortName: item.fileName,
    })),
    ...notes.value.map(item => ({
      kind: 'note' as const,
      item,
      sortPinned: item.pinned ? 0 : 1,
      sortDate: toSortDate(item),
      sortName: item.name,
    })),
  ]

  cards.sort((a, b) => {
    if (a.sortPinned !== b.sortPinned)
      return a.sortPinned - b.sortPinned
    if (a.sortDate !== b.sortDate)
      return b.sortDate - a.sortDate
    return a.sortName.localeCompare(b.sortName)
  })

  dashboardCards.value = cards
}

const filteredDashboardCards = computed(() => {
  if (allFiltersActive.value)
    return dashboardCards.value

  return dashboardCards.value.filter(card => selectedKinds.value.includes(card.kind))
})

async function archiveCard(card: DashboardCard) {
  if (!baseFolderUri.value)
    return

  try {
    const fileName = card.kind === 'note' ? card.item.name : card.item.fileName

    await addTagToFrontmatter(baseFolderUri.value, fileName, settings.value.archiveTag || 'qcArchive')

    if (card.kind === 'list')
      lists.value = lists.value.filter(list => list.fileName !== card.item.fileName)
    else if (card.kind === 'task')
      tasks.value = tasks.value.filter(task => task.fileName !== card.item.fileName)
    else
      notes.value = notes.value.filter(note => note.name !== card.item.name)

    buildSortedDashboardCards()
    syncDashboardCache()
  }
  catch (err) {
    console.error('Failed to archive card item', err)
  }
}

async function syncDashboard(options: { preferCache?: boolean, force?: boolean } = {}) {
  await syncSettingsAndRefresh(options)
  buildSortedDashboardCards()
}

function handleWindowFocus() {
  void syncDashboard({ preferCache: true, force: true })
}

function handleVisibilityChange() {
  if (document.visibilityState === 'visible')
    void syncDashboard({ preferCache: true, force: true })
}

onMounted(() => {
  void syncDashboard({ preferCache: true })
  window.addEventListener('focus', handleWindowFocus)
  document.addEventListener('visibilitychange', handleVisibilityChange)
})

onActivated(() => {
  void syncDashboard({ preferCache: true, force: true })
})

onBeforeUnmount(() => {
  window.removeEventListener('focus', handleWindowFocus)
  document.removeEventListener('visibilitychange', handleVisibilityChange)
})

function getNotePreviewHtml(note: AppFile) {
  const source = (note.previewMarkdown || '').trim()
  const cacheKey = `${note.name}|${note.updated || note.created || ''}|${source}`
  const cached = notePreviewHtmlCache.get(cacheKey)
  if (cached)
    return cached

  const html = source
    ? renderNotePreviewMarkdown(source)
    : `<span class="empty">${escapeHtml(note.preview || 'No preview available.')}</span>`

  notePreviewHtmlCache.set(cacheKey, html)
  if (notePreviewHtmlCache.size > 300)
    notePreviewHtmlCache.clear()

  return html
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

function isCardMetaVisible() {
  return false
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
  swipeStartY.value = touch.clientY
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

  const deltaY = touch.clientY - swipeStartY.value
  if (Math.abs(deltaY) > Math.abs(swipeStartX.value - touch.clientX) * 0.6)
    return

  if (event.cancelable)
    event.preventDefault()

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

    buildSortedDashboardCards()

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

function isPageScrolledToTop() {
  return window.scrollY <= 0
}

async function triggerHaptic(kind: 'light' | 'medium' = 'light') {
  try {
    const { Haptics, ImpactStyle } = await import('@capacitor/haptics')
    const style = kind === 'medium' ? ImpactStyle.Medium : ImpactStyle.Light
    await Haptics.impact({ style })
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
  if (!baseFolderUri.value || isPullRefreshing.value)
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
    await syncDashboard({ force: true })
  }
  finally {
    isPullRefreshing.value = false
    pullDistance.value = 0
  }
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

  buildSortedDashboardCards()

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

    buildSortedDashboardCards()

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
  buildSortedDashboardCards()

  try {
    await setFilePinned(baseFolderUri.value, note.name, nextPinned)
    syncDashboardCache()
  }
  catch (err) {
    note.pinned = previousPinned
    notes.value = sortPinnedFiles(notes.value)
    buildSortedDashboardCards()
    console.error('Failed to update note pin state', err)
  }
}
</script>

<template>
  <div class="page" @touchstart="onPullTouchStart" @touchmove="onPullTouchMove" @touchend="onPullTouchEnd"
    @touchcancel="onPullTouchEnd">
    <div class="pull-refresh"
      :class="{ 'is-visible': showPullIndicator, 'is-ready': isPullReady, 'is-refreshing': isPullRefreshing }"
      :aria-label="isPullRefreshing ? 'Refreshing dashboard' : 'Pull to refresh dashboard'" role="status">
      <div class="pull-refresh-spinner" aria-hidden="true" />
      <span>{{ isPullRefreshing ? 'Refreshing…' : isPullReady ? 'Release to refresh' : '' }}</span>
    </div>

    <div class="page-content" :style="pageContentStyle">
      <div class="top-row">
        <h1>Quick Capture</h1>

        <div class="top-actions">
          <button class="glass-icon-button" aria-label="Search" @click="go('/search')">
            <Search :size="20" />
          </button>
          <button class="glass-icon-button" aria-label="Go to settings" @click="go('/settings')">
            <Settings :size="20" />
          </button>
        </div>
      </div>

      <div v-if="isLoading" class="empty-state card">
        <p>Loading dashboard...</p>
      </div>

      <div v-else-if="error" class="empty-state card">
        <p class="error-text">
          {{ error }}
        </p>
      </div>

      <div v-else-if="!baseFolderUri" class="empty-state card">
        <div class="empty-icon">
          #
        </div>
        <p class="empty-title">
          No Folder Selected
        </p>
        <p class="empty-text">
          Please select a folder in settings to begin.
        </p>
        <button class="glass-button glass-button--primary" @click="go('/settings')">
          Go to Settings
        </button>
      </div>

      <div v-else-if="isEmpty" class="empty-state card">
        <div class="empty-icon">
          *
        </div>
        <p class="empty-title">
          Folder is Empty
        </p>
        <p class="empty-text">
          Create a new list, task file, or note to get started.
        </p>
        <div class="button-group">
          <button class="glass-button glass-button--primary" @click="createTodoFile('list')">
            New List
          </button>
          <button class="glass-button glass-button--primary" @click="go('/tasks')">
            Quick Tasks
          </button>
          <button class="glass-button glass-button--primary" @click="go('/note')">
            New Note
          </button>
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

            <div class="glass-button collection-card" :class="{
              'collection-card--swiping': activeSwipeKey === getCardKey(card),
              'collection-card--meta-hidden': !isCardMetaVisible(),
            }" :style="getCardStyle(card)" @click="openCard(card)">
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
                      <!-- eslint-disable-next-line vue/no-v-html -->
                      <p class="note-preview" v-html="getNotePreviewHtml(card.item)" />
                    </template>

                    <template v-else>
                      <ul class="todo-preview-list">
                        <li v-for="(previewItem, index) in getPreviewItems(card.item)" :key="index"
                          class="todo-preview-item">
                          <span class="todo-preview-dot" :class="{ 'is-done': previewItem.state === 'done' }" />
                          <span
                            v-if="card.kind === 'task' && previewItem.state === 'pending' && previewItem.isHighPriority"
                            class="todo-preview-flame" aria-label="High priority" title="High priority">
                            <Star :size="11" fill="currentColor" />
                          </span>
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
                  <button class="glass-icon-button home-card-action-button archive-icon-button" type="button"
                    :aria-label="`Archive ${getCardTypeLabel(card.kind).toLowerCase()}`"
                    @click.stop="archiveCard(card)">
                    <Archive :size="16" />
                  </button>
                  <button class="glass-icon-button home-card-action-button delete-icon-button" type="button"
                    :aria-label="`Delete ${getCardTypeLabel(card.kind).toLowerCase()}`" @click.stop="deleteCard(card)">
                    <Trash2 :size="16" />
                  </button>
                </div>
              </div>

              <div v-if="card.kind !== 'note'" class="summary-progress-track">
                <div class="summary-progress-fill" :style="{ width: `${getProgressPercent(card.item)}%` }" />
              </div>

              <div v-if="isCardMetaVisible()" class="card-footer"
                :class="{ 'card-footer--note': card.kind === 'note' }">
                <div class="footer-spacer" />
                <div class="footer-meta">
                  <span class="type-pill">{{ getCardTypeLabel(card.kind) }}</span>
                  <span v-if="card.kind !== 'note'" class="meta-line meta-line--stat">{{ getCompletedCount(card.item)
                  }}/{{ getActiveItems(card.item).length }} done</span>
                  <!-- <span v-if="card.kind !== 'note'" class="meta-line meta-line--percent">{{
                    getProgressPercent(card.item)
                  }}%</span> -->
                  <span class="meta-line">{{ card.item.updated || card.item.created || '' }}</span>
                </div>
              </div>
            </div>
          </div>
        </div>
      </template>
    </div>

    <BottomActionNav @create-list="createTodoFile('list')" />
  </div>
</template>

<style scoped>
.page {
  min-height: 100vh;
  padding: var(--page-top-padding) 20px calc(92px + env(safe-area-inset-bottom, 0px));
  color: var(--text);
  overscroll-behavior-y: contain;
}

.page-content {
  will-change: transform;
}

.pull-refresh {
  height: 0;
  overflow: hidden;
  opacity: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  color: var(--text-soft);
  font-size: 0.82rem;
  font-weight: 700;
  transition: height 0.16s ease, opacity 0.16s ease;
}

.pull-refresh.is-visible {
  height: 30px;
  opacity: 1;
}

.pull-refresh-spinner {
  width: 15px;
  height: 15px;
  border-radius: 999px;
  border: 2px solid color-mix(in srgb, var(--primary) 20%, transparent);
  border-top-color: var(--primary);
  border-right-color: color-mix(in srgb, var(--primary) 74%, white 8%);
  animation: pull-refresh-spin 0.75s linear infinite;
}

.pull-refresh:not(.is-ready):not(.is-refreshing) .pull-refresh-spinner {
  animation-play-state: paused;
  opacity: 0.8;
}

.pull-refresh.is-ready {
  color: color-mix(in srgb, var(--primary) 74%, var(--text));
}

@keyframes pull-refresh-spin {
  to {
    transform: rotate(360deg);
  }
}

.top-row {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 16px;
  margin-bottom: 22px;
}

.top-actions {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-shrink: 0;
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
  touch-action: pan-y;
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
  min-height: 172px;
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

.collection-card--meta-hidden {
  min-height: 148px;
  gap: 10px;
  padding-bottom: 12px;
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
  font-size: 0.95rem;
  line-height: 1.45;
  display: -webkit-box;
  line-clamp: 3;
  -webkit-line-clamp: 3;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.note-preview :deep(strong) {
  color: color-mix(in srgb, var(--text) 88%, var(--primary) 12%);
  font-weight: 650;
}

.note-preview :deep(em) {
  font-style: italic;
}

.note-preview :deep(s) {
  opacity: 0.8;
}

.note-preview :deep(code) {
  font-family: ui-monospace, 'Cascadia Code', Menlo, Monaco, monospace;
  font-size: 0.84em;
  background: color-mix(in srgb, var(--primary) 10%, transparent);
  border-radius: 4px;
  padding: 1px 4px;
}

.note-preview :deep(.empty) {
  opacity: 0.86;
}

.note-preview :deep(.tag-chip) {
  display: inline-flex;
  align-items: center;
  border-radius: 999px;
  padding: 1px 7px;
  margin: 0 1px;
  line-height: 1.25;
  font-size: 0.82em;
  font-weight: 600;
  color: color-mix(in srgb, hsl(var(--tag-h) 76% 42%) 76%, var(--text));
  background: color-mix(in srgb, hsl(var(--tag-h) 82% 58%) 24%, transparent);
  border: 1px solid color-mix(in srgb, hsl(var(--tag-h) 80% 58%) 40%, transparent);
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

.todo-preview-flame {
  width: 13px;
  height: 13px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  color: #facc15;
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
