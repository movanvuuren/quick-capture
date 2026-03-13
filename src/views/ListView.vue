<script setup lang="ts">
import type { ComponentPublicInstance } from 'vue'
import { GripVertical, Pin, PinOff, Trash } from 'lucide-vue-next'
import { computed, nextTick, ref, watch, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { loadSettings } from '../lib/settings'
import { createListFile, deleteListFile, loadListsFromFolder, saveListToFile } from '../lib/listFiles'
import type { StoredTodoList, TodoState, TodoList } from '../lib/lists'


const router = useRouter()
const route = useRoute()

const listId = computed(() => (route.params.id as string) || undefined)
const lists = ref<StoredTodoList[]>([])
const isLoading = ref(true)
const saveError = ref('')
// watch sortedLists mainly for view purposes but we still save underlying lists


const settings = loadSettings()
const baseFolderUri = computed(() => settings.baseFolderUri || '') // replace with your settings source

onMounted(async () => {
  if (!baseFolderUri.value) {
    isLoading.value = false
    return
  }

  try {
    lists.value = await loadListsFromFolder(baseFolderUri.value)
  }
  catch (err) {
    console.error('Failed to load lists', err)
  }
  finally {
    isLoading.value = false
  }
})


// maintain a sorted view where pinned lists appear first
const sortedLists = computed(() => {
  return [...lists.value].sort((a, b) => {
    const pa = a.pinned ? 0 : 1
    const pb = b.pinned ? 0 : 1
    return pa - pb
  })
})

lists.value.forEach((l) => {
  if (l.items.length === 0)
    l.items.push({ text: '', state: 'pending' })
})

const currentList = computed(() => {
  if (!listId.value)
    return undefined
  const found = lists.value.find(l => l.id === listId.value)
  if (!found) {
    console.warn('list not found for id', listId.value, 'existing lists', lists.value.map(l => l.id))
  }
  return found
})

const itemInputs = ref<(HTMLInputElement | null)[]>([])

function ensureInputs(): (HTMLInputElement | null)[] {
  if (!itemInputs.value)
    itemInputs.value = []
  return itemInputs.value
}

function setInputRef(
  idx: number,
  el: Element | ComponentPublicInstance | null,
) {
  const arr = ensureInputs()
  arr[idx] = el instanceof HTMLInputElement ? el : null
}

watch(listId, () => {
  itemInputs.value = []
})

// when we navigate to a specific list, focus its first input so user sees an editable field
watch(listId, (id) => {
  if (id) {
    nextTick(() => {
      const el = ensureInputs()[0]
      if (el)
        el.focus()
    })
  }
})


const saveTimers = new Map<string, number>()

function queueSave(list: StoredTodoList) {
  const existing = saveTimers.get(list.id)
  if (existing)
    window.clearTimeout(existing)

  const timer = window.setTimeout(async () => {
    try {
      const saved = await saveListToFile(baseFolderUri.value, list)
      const idx = lists.value.findIndex(l => l.id === list.id)
      if (idx !== -1)
        lists.value[idx] = saved
    }
    catch (err) {
      console.error('Failed to save list', list.title, err)
      saveError.value = `Failed to save "${list.title || 'Untitled List'}"`
    }
    finally {
      saveTimers.delete(list.id)
    }
  }, 400)

  saveTimers.set(list.id, timer)
}

function togglePin(list: StoredTodoList) {
  list.pinned = !list.pinned
  queueSave(list)
}

function addItem(list: StoredTodoList) {
  list.items.push({ text: '', state: 'pending' })
  queueSave(list)
}

function removeItem(list: StoredTodoList, idx: number) {
  list.items.splice(idx, 1)
  if (list.items.length === 0)
    list.items.push({ text: '', state: 'pending' })

  queueSave(list)
}

function cycleState(list: StoredTodoList, idx: number) {
  const item = list.items[idx]
  if (!item)
    return

  const order: TodoState[] = ['pending', 'done', 'cancelled']
  const curIndex = order.indexOf(item.state)
  const next = order[(curIndex + 1) % order.length] as TodoState
  item.state = next

  list.items.splice(idx, 1)
  if (next === 'pending')
    list.items.unshift(item)
  else
    list.items.push(item)

  queueSave(list)
}
// watch(
//   lists,
//   () => {
//     saveLists(lists.value)
//   },
//   { deep: true },
// )
watch(
  () => currentList.value?.title,
  () => {
    if (currentList.value)
      queueSave(currentList.value)
  },
)

function goBack() {
  // if we're viewing a specific list return to overview;
  // if we're already at overview just go to root instead of history.back()
  if (listId.value) {
    router.push('/list')
  }
  else {
    router.push('/')
  }
}

async function addList() {
  if (!baseFolderUri.value) {
    router.push('/settings')
    return
  }

  const newList = await createListFile(baseFolderUri.value, {
    id: crypto.randomUUID(),
    title: '',
    items: [{ text: '', state: 'pending' }],
    pinned: false,
    type: 'list',
  })

  lists.value.push(newList)
  router.push(`/list/${newList.id}`)
}

async function removeList(id: string) {
  const list = lists.value.find(l => l.id === id)
  if (!list)
    return

  const wasCurrent = id === listId.value

  try {
    await deleteListFile(baseFolderUri.value, list)
    lists.value = lists.value.filter(l => l.id !== id)
    if (wasCurrent)
      router.push('/list')
  }
  catch (err) {
    console.error('Failed to delete list', err)
  }
}


function addItemAt(list: TodoList, idx: number) {
  list.items.splice(idx + 1, 0, { text: '', state: 'pending' })
  nextTick(() => {
    const el = ensureInputs()[idx + 1]
    if (el)
      el.focus()
  })
}

function moveItem(list: StoredTodoList, from: number, to: number) {
  const item = list.items.splice(from, 1)[0]
  if (!item)
    return
  list.items.splice(to, 0, item)
  queueSave(list)
}

// drag & drop support ------------------------------------------------
const dragIndex = ref<number | null>(null)

function onDragStart(e: DragEvent, idx: number) {
  dragIndex.value = idx
  e.dataTransfer?.setData('text/plain', String(idx))
  e.dataTransfer!.effectAllowed = 'move'
}

function onDragOver(e: DragEvent) {
  e.preventDefault()
  e.dataTransfer!.dropEffect = 'move'
}

function onDrop(e: DragEvent, idx: number) {
  e.preventDefault()
  const from
    = dragIndex.value !== null
      ? dragIndex.value
      : Number.parseInt(e.dataTransfer?.getData('text/plain') || '', 10)
  if (isNaN(from) || from === idx)
    return
  if (currentList.value)
    moveItem(currentList.value, from, idx)
  dragIndex.value = null
}

function getActiveItems(list: StoredTodoList) {
  return list.items.filter(
    item => item.text.trim().length > 0 && item.state !== 'cancelled',
  )
}

function getCompletedCount(list: StoredTodoList) {
  return list.items.filter(
    item => item.state === 'done' && item.text.trim().length > 0,
  ).length
}

function getProgressPercent(list: StoredTodoList) {
  const active = getActiveItems(list)
  if (active.length === 0)
    return 0

  const done = active.filter(item => item.state === 'done').length
  return Math.round((done / active.length) * 100)
}

</script>

<template>
  <div class="page">
    <div class="header">
      <button class="glass-icon-button back-button" aria-label="Go back" @click="goBack">
        ←
      </button>

      <div v-if="!currentList">
        <h1>Lists</h1>
        <p class="subtitle">
          Your to-do lists – tap to edit
        </p>
      </div>
      <div v-if="isLoading" class="empty-state">
        <p class="empty-title">Loading lists…</p>
      </div>
      <div v-else-if="currentList" class="detail-header">
        <input v-model="currentList.title" placeholder="Title" class="header-title"
          @input="currentList && queueSave(currentList)">
      </div>
    </div>

    <div v-if="lists.length === 0" class="empty-state">
      <div class="empty-icon">
        ☰
      </div>
      <p class="empty-title">
        No lists yet
      </p>
      <p class="empty-text">
        Create your first list to get started.
      </p>
      <button class="primary-button add-main-button" @click="addList">
        + New list
      </button>
    </div>

    <!-- overview of all lists -->
    <template v-if="!currentList">
      <div v-for="list in sortedLists" :key="list.id" class="list-card summary polished-summary"
        @click="router.push(`/list/${list.id}`)">
        <div class="summary-row">
          <div class="summary-icon-wrap">
            ☰
          </div>

          <div class="summary-content">
            <div class="summary-title-row">
              <div class="card-title">
                {{ list.title || 'Untitled list' }}
              </div>
              <Pin v-if="list.pinned" :size="16" class="pin-icon" />
            </div>

            <ul class="preview-items">
              <li v-for="(item, j) in list.items
                .filter(item => item.text.trim() && item.state !== 'cancelled')
                .slice(0, 3)" :key="j" class="preview-item" :class="item.state">
                <span class="preview-state">
                  {{ item.state === 'done' ? '✓' : '○' }}
                </span>

                <span class="preview-text">
                  {{ item.text }}
                </span>
              </li>
            </ul>

            <div class="summary-progress-block">
              <div class="summary-progress-label">
                <span>{{ getCompletedCount(list) }}/{{ getActiveItems(list).length }} done</span>
                <span>{{ getProgressPercent(list) }}%</span>
              </div>
              <div class="summary-progress-track">
                <div class="summary-progress-fill" :style="{ width: `${getProgressPercent(list)}%` }" />
              </div>
            </div>

            <div class="summary-meta">
              <span class="summary-type">List</span>
              <span v-if="list.updated" class="summary-date">{{ list.updated }}</span>
            </div>
          </div>
        </div>
      </div>

      <button class="glass-button glass-button--primary glass-button--block primary-button" @click="addList">
        + New list
      </button>

      <p v-if="saveError" class="subtitle">
        {{ saveError }}
      </p>
    </template>

    <!-- detail for a single list -->
    <template v-else>
      <div class="list-card">
        <div class="card-header">
          <!-- header-buttons remain here; title moved to main header -->
          <div class="header-buttons">
            <button class="glass-icon-button" :aria-label="currentList.pinned ? 'Unpin list' : 'Pin list'"
              @click="togglePin(currentList)">
              <component :is="currentList.pinned ? Pin : PinOff" :size="20" />
            </button>

            <button class="glass-icon-button" aria-label="Delete list" @click="removeList(currentList.id)">
              <Trash :size="20" />
            </button>
          </div>
        </div>

        <div class="items">
          <!-- indicate empty list explicitly when all texts are blank -->
          <div v-if="currentList && currentList.items.every(i => !i.text)" class="empty-details">
            No items yet – tap + or press Enter to start.
          </div>

          <div v-for="(item, i) in currentList.items" :key="i" class="item-row" :class="{
            done: item.state === 'done',
            cancelled: item.state === 'cancelled',
          }" draggable="true" @dragstart="(e) => onDragStart(e, i)" @dragover="onDragOver" @drop="(e) => onDrop(e, i)">
            <GripVertical class="drag-handle" />

            <button class="state-box" :class="item.state" :aria-label="`Change state for item ${i + 1}`"
              @click="cycleState(currentList, i)">
              {{ item.state === 'done' ? '✓' : item.state === 'cancelled' ? '–' : '' }}
            </button>

            <input :ref="el => setInputRef(i, el as HTMLInputElement | null)" v-model="item.text"
              placeholder="To-do item" class="item-input" @input="queueSave(currentList)"
              @keydown.enter.prevent="addItemAt(currentList, i)">

            <button class="glass-icon-button" aria-label="Remove item" @click="removeItem(currentList, i)">
              ×
            </button>
          </div>

          <button class="glass-button glass-button--primary primary-button" @click="addItem(currentList)">
            + Add item
          </button>
        </div>
      </div>
    </template>
  </div>
</template>

<style scoped>
.page {
  min-height: 100vh;
  padding: 20px 16px 32px;
  color: var(--text);
}


.header {
  display: flex;
  align-items: center;
  gap: 14px;
  margin-bottom: 20px;
}

.header h1 {
  margin: 0;
  font-size: 1.9rem;
  line-height: 1.1;
  letter-spacing: -0.03em;
}

.subtitle {
  margin: 4px 0 0;
  color: var(--text-soft);
  font-size: 0.95rem;
}

.empty-state,
.list-card {
  background: var(--surface);
  backdrop-filter: blur(14px);
  -webkit-backdrop-filter: blur(14px);
  border: 1px solid var(--border);
  border-radius: 24px;
  box-shadow: var(--shadow);
}

.empty-state {
  padding: 28px 20px;
  text-align: center;
  margin-bottom: 18px;
}

.empty-icon {
  width: 56px;
  height: 56px;
  margin: 0 auto 14px;
  display: grid;
  place-items: center;
  border-radius: 18px;
  background: var(--primary-soft);
  font-size: 1.5rem;
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

.list-card {
  padding: 16px;
  margin-bottom: 16px;
}

.card-header {
  display: flex;
  flex-direction: column;
  gap: 12px;
  margin-bottom: 14px;
}

/* header title shown when editing a specific list */
.header-title {
  font-size: 1.3rem;
  font-weight: 600;
  width: 100%;
  padding: 0;
  margin-left: 8px;
  border: none;
  background: transparent;
  color: var(--text);
}

.detail-header {
  flex: 1;
  display: flex;
  align-items: center;
}


.card-title-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

/* give the empty-details message some breathing room */
.empty-details {
  padding: 8px 12px;
  margin-bottom: 8px;
  font-size: 0.9rem;
  color: var(--text-soft);
  text-align: center;
  background: rgba(248, 250, 252, 0.6);
  border-radius: 12px;
}

.header-buttons {
  display: flex;
  gap: 10px;
  flex-wrap: wrap;
}

.items {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.item-row {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px;
  border-radius: 18px;
  background: rgba(255, 255, 255, 0.5);
  transition: background 0.2s ease;
}

.drag-handle {
  cursor: grab;
  width: 20px;
  height: 20px;
  flex-shrink: 0;
  color: var(--text-soft);
}

.drag-handle:active {
  cursor: grabbing;
}

.item-input {
  flex: 1;
  min-width: 0;
  padding: 12px 14px;
  border-radius: 14px;
  font-size: 0.98rem;
  border: none;
  background: transparent;
}

@media (min-width: 640px) {
  .page {
    max-width: 760px;
    margin: 0 auto;
    padding: 28px 20px 40px;
  }

  .card-header {
    flex-direction: row;
    align-items: center;
  }

  .header-buttons {
    flex-shrink: 0;
  }
}

.polished-summary {
  padding: 16px 18px;
  min-height: 108px;
  cursor: pointer;
}

.summary-row {
  display: flex;
  align-items: flex-start;
  gap: 14px;
  width: 100%;
}

.summary-icon-wrap {
  width: 42px;
  height: 42px;
  flex-shrink: 0;
  display: grid;
  place-items: center;
  border-radius: 12px;
  background: var(--primary-soft);
  border: 1px solid var(--border);
  color: var(--text-soft);
  font-size: 1.05rem;
  font-weight: 700;
  margin-top: 2px;
}

.summary-content {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  align-items: flex-start;
}

.summary-title-row {
  width: 100%;
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}

.card-title {
  flex: 1;
  min-width: 0;
  font-size: 1rem;
  font-weight: 700;
  line-height: 1.25;
  text-align: left;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.pin-icon {
  flex-shrink: 0;
  color: var(--primary);
}

.preview-items {
  margin: 6px 0 0;
  padding: 0;
  list-style: none;
  width: 100%;
}

.preview-item {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  font-size: 0.9rem;
  line-height: 1.4;
  color: var(--text-soft);
  text-align: left;
}

.preview-state {
  width: 16px;
  flex-shrink: 0;
  text-align: center;
  opacity: 0.9;
}

.preview-text {
  flex: 1;
  min-width: 0;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}


.preview-item.done .preview-text {
  text-decoration: line-through;
  opacity: 0.75;
}

.preview-item.cancelled .preview-text {
  opacity: 0.55;
}

.summary-progress-block {
  width: 100%;
  margin-top: 10px;
}

.summary-progress-label {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 6px;
  font-size: 0.74rem;
  color: var(--text-soft);
}

.summary-progress-track {
  width: 100%;
  height: 6px;
  border-radius: 999px;
  background: color-mix(in srgb, var(--surface) 85%, black 15%);
  overflow: hidden;
}

.summary-progress-fill {
  height: 100%;
  border-radius: 999px;
  transition: width 0.2s ease;
  background: var(--primary);
}

.summary-progress-fill[style*="100%"] {
  height: 100%;
  border-radius: 999px;
  transition: width 0.2s ease;
  background: #22c55e;
}

.summary-meta {
  width: 100%;
  margin-top: 10px;
  display: flex;
  justify-content: flex-end;
  align-items: center;
  gap: 8px;
  font-size: 0.74rem;
  color: var(--text-soft);
}

.summary-type {
  text-transform: capitalize;
  padding: 4px 9px;
  border-radius: 999px;
  border: 1px solid var(--border);
  background: color-mix(in srgb, var(--surface) 78%, transparent);
}

.summary-date {
  white-space: nowrap;
  opacity: 0.9;
}
</style>
