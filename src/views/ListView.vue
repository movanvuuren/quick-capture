<script setup lang="ts">
import type { ComponentPublicInstance } from 'vue'
import { GripVertical, Trash2 } from 'lucide-vue-next'
import { computed, nextTick, ref, watch, onMounted } from 'vue'
import { onBeforeRouteLeave, useRoute, useRouter } from 'vue-router'
import MetaWell from '../components/MetaWell.vue'
import PageHeader from '../components/PageHeader.vue'
import PinToggleButton from '../components/PinToggleButton.vue'
import { loadSettings } from '../lib/settings'
import { deleteListFile, loadTodoFileByName, loadTodoFilesFromFolder, saveListToFile } from '../lib/listFiles'
import type { StoredTodoList, TodoState, TodoList } from '../lib/lists'


const router = useRouter()
const route = useRoute()

const listId = computed(() => {
  const id = route.params.id as string | undefined
  return id ? decodeURIComponent(id) : undefined
})
const lists = ref<StoredTodoList[]>([])
const isLoading = ref(true)
const saveError = ref('')
// watch sortedLists mainly for view purposes but we still save underlying lists


const settings = loadSettings()
const baseFolderUri = computed(() => settings.baseFolderUri || '')
const isDraftRoute = computed(() => route.query.draft === '1')
const draftTitle = computed(() => {
  const value = route.query.draftTitle
  return typeof value === 'string' ? value : ''
})
const collectionType = computed<'list' | 'task'>(() => {
  if (route.path.startsWith('/tasks') || route.path.startsWith('/task-list'))
    return 'task'

  return 'list'
})
const editorTypeLabel = computed(() => collectionType.value === 'task' ? 'Task file' : 'List')

async function loadCollection() {
  if (!baseFolderUri.value) {
    isLoading.value = false
    return
  }

  isLoading.value = true
  try {
    if (listId.value) {
      const current = await loadTodoFileByName(baseFolderUri.value, listId.value, collectionType.value)
      lists.value = current ? [current] : []
    }
    else {
      lists.value = await loadTodoFilesFromFolder(baseFolderUri.value, collectionType.value)
    }
  }
  catch (err) {
    console.error('Failed to load collection', collectionType.value, err)
  }
  finally {
    isLoading.value = false
  }
}

onMounted(async () => {
  await loadCollection()
})

watch(collectionType, async () => {
  await loadCollection()
})

watch(listId, async () => {
  await loadCollection()
})

watch(
  listId,
  (id) => {
    // This view is now detail-only. Route all overview hits back to Home.
    if (!id)
      router.replace('/')
  },
  { immediate: true },
)

lists.value.forEach((l) => {
  if (l.items.length === 0)
    l.items.push({ text: '', state: 'pending' })
})

const currentList = computed(() => {
  if (!listId.value)
    return undefined

  const found = lists.value.find(l => l.fileName === listId.value)

  if (!found) {
    console.warn(
      'list not found for fileName',
      listId.value,
      'existing lists',
      lists.value.map(l => l.fileName),
    )
  }

  return found
})

const itemInputs = ref<(HTMLTextAreaElement | null)[]>([])
const isDiscardingDraft = ref(false)

function ensureInputs(): (HTMLTextAreaElement | null)[] {
  if (!itemInputs.value)
    itemInputs.value = []
  return itemInputs.value
}

function setInputRef(
  idx: number,
  el: Element | ComponentPublicInstance | null,
) {
  const arr = ensureInputs()
  const textarea = el instanceof HTMLTextAreaElement ? el : null
  arr[idx] = textarea
  if (textarea)
    autoGrow(textarea)
}

function autoGrow(el: HTMLTextAreaElement) {
  el.style.height = 'auto'
  el.style.height = `${el.scrollHeight}px`
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
  router.push('/')
}

function isMeaningfulList(list: StoredTodoList) {
  const hasNonEmptyItems = list.items.some(item => item.text.trim().length > 0)
  const hasChangedTitle = (list.title || '').trim() !== draftTitle.value.trim()
  return hasNonEmptyItems || hasChangedTitle || !!list.pinned
}

async function discardUntouchedDraft() {
  if (isDiscardingDraft.value || !isDraftRoute.value || !baseFolderUri.value || !currentList.value)
    return

  if (isMeaningfulList(currentList.value))
    return

  isDiscardingDraft.value = true

  try {
    const draftId = currentList.value.id
    await deleteListFile(baseFolderUri.value, currentList.value)
    lists.value = lists.value.filter(list => list.id !== draftId)
  }
  catch (err) {
    console.error('Failed to discard empty draft', err)
  }
  finally {
    isDiscardingDraft.value = false
  }
}

onBeforeRouteLeave(async () => {
  await discardUntouchedDraft()
})

async function removeList(id: string) {
  const list = lists.value.find(l => l.id === id)
  if (!list)
    return

  const wasCurrent = list.fileName === listId.value

  try {
    await deleteListFile(baseFolderUri.value, list)
    lists.value = lists.value.filter(l => l.id !== id)
    if (wasCurrent)
      router.push('/')
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

</script>

<template>
  <div class="page">
    <PageHeader :title="editorTypeLabel" @back="goBack">
      <template #right>
        <div v-if="currentList" class="detail-meta">
          <MetaWell :date="currentList.updated || currentList.created || ''">
            <PinToggleButton :pinned="currentList.pinned" :size="20" item-label="list" variant="glass"
              @toggle="togglePin(currentList)" />
            <button class="glass-icon-button" aria-label="Delete list" @click="removeList(currentList.id)">
              <Trash2 :size="20" />
            </button>
          </MetaWell>
        </div>
      </template>
    </PageHeader>

    <div v-if="isLoading" class="empty-state">
      <p class="empty-title">Loading…</p>
    </div>

    <template v-else-if="currentList">
      <div class="detail-header glass-panel">
        <input v-model="currentList.title" placeholder="Title" class="header-title"
          @input="currentList && queueSave(currentList)">
      </div>

      <div class="list-card glass-panel">
        <div class="items">
          <!-- <div v-if="currentList && currentList.items.every(i => !i.text)" class="empty-details glass-panel--soft">
            No items yet - tap + or press Enter to start.
          </div> -->

          <div v-for="(item, i) in currentList.items" :key="i" class="item-row" :class="{
            done: item.state === 'done',
            cancelled: item.state === 'cancelled',
          }" draggable="false" @dragstart="(e) => onDragStart(e, i)" @dragover="onDragOver"
            @drop="(e) => onDrop(e, i)">
            <GripVertical class="drag-handle" />

            <button class="state-box" :class="item.state" :aria-label="`Change state for item ${i + 1}`"
              @click="cycleState(currentList, i)">
              {{ item.state === 'done' ? '✓' : item.state === 'cancelled' ? '–' : '' }}
            </button>

            <textarea :ref="el => setInputRef(i, el as HTMLTextAreaElement | null)" v-model="item.text"
              placeholder="To-do item" class="item-input" rows="1"
              @input="(e) => { autoGrow(e.target as HTMLTextAreaElement); currentList && queueSave(currentList) }"
              @keydown.enter.prevent="currentList && addItemAt(currentList, i)" />

            <button class="glass-icon-button delete-item-button" aria-label="Remove item"
              @click="removeItem(currentList, i)">
              <Trash2 :size="14" />
            </button>
          </div>

          <button class="glass-button glass-button--primary primary-button" @click="addItem(currentList)">
            + Add item
          </button>
        </div>
      </div>
    </template>

    <div v-else class="empty-state">
      <div class="empty-icon">
        !
      </div>
      <p class="empty-title">This item is no longer available</p>
      <p class="empty-text">
        It may have been renamed, deleted, or moved.
      </p>
      <button class="glass-button glass-button--primary" @click="goBack">Back Home</button>
    </div>
  </div>
</template>

<style scoped>
.page {
  min-height: 100vh;
  padding: var(--page-top-padding) 20px 32px;
  color: var(--text);
}


.empty-state,
.list-card,
.glass-panel {
  background:
    radial-gradient(circle at top right, color-mix(in srgb, var(--primary) 12%, transparent), transparent 28%),
    linear-gradient(180deg,
      color-mix(in srgb, var(--c-light) 12%, transparent),
      color-mix(in srgb, var(--c-glass) 10%, transparent)),
    color-mix(in srgb, var(--surface) 84%, transparent);
  backdrop-filter: blur(14px) saturate(var(--saturation));
  -webkit-backdrop-filter: blur(14px) saturate(var(--saturation));
  border: 1px solid color-mix(in srgb, var(--c-light) 20%, transparent);
  border-radius: 24px;
  box-shadow:
    inset 0 0 0 1px color-mix(in srgb, var(--c-light) calc(var(--glass-reflex-light) * 8%), transparent),
    inset 0 -1px 4px 0 color-mix(in srgb, var(--c-dark) calc(var(--glass-reflex-dark) * 9%), transparent),
    0 18px 36px color-mix(in srgb, var(--c-dark) calc(var(--glass-reflex-dark) * 14%), transparent);
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
  position: relative;
  border-radius: 16px;
  z-index: 1;
}

/* header title shown when editing a specific list */
.header-title {
  font-size: 1.3rem;
  font-weight: 700;
  width: 100%;
  padding: 4px 0;
  border: none;
  outline: none;
  background: transparent;
  color: var(--text);
}

.detail-header {
  display: block;
  border-radius: 16px;
  padding: 8px 14px;
  margin-bottom: 12px;
  position: relative;
  z-index: 2;
}

.detail-meta {
  display: inline-flex;
  align-items: center;
  justify-self: end;
}

.card-title-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

/* give the empty-details message some breathing room */
.empty-details {
  padding: 10px 12px;
  margin-bottom: 8px;
  font-size: 0.9rem;
  color: var(--text-soft);
  text-align: center;
  border-radius: 12px;
}

.glass-panel--soft {
  background: color-mix(in srgb, var(--c-light) 12%, transparent);
  border: 1px solid color-mix(in srgb, var(--c-light) 14%, transparent);
}

.items {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.item-row {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  padding: 8px;
  border-radius: 18px;
  background: color-mix(in srgb, var(--c-light) 10%, transparent);
  border: 1px solid color-mix(in srgb, var(--c-light) 14%, transparent);
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
  color: var(--text);
  resize: none;
  overflow: hidden;
  line-height: 1.45;
  word-break: break-word;
  white-space: pre-wrap;
}

.delete-item-button {
  width: 34px;
  height: 34px;
  border-radius: 12px;
  color: var(--text-soft);
}

.delete-item-button:hover {
  color: var(--danger);
}

@media (min-width: 640px) {
  .page {
    max-width: 760px;
    margin: 0 auto;
    padding: 28px 20px 40px;
  }
}

@media (max-width: 560px) {
  .header-title {
    font-size: 1.15rem;

    /* Reduce padding and size for mobile view */
    .page-padding {
      padding: 8px;
    }

    .list-card {
      padding: 10px;
    }

    .items-gap {
      margin-bottom: 8px;
    }

    .item-row {
      padding: 4px;
      margin: 4px 0;
      border-radius: 4px;
    }

    .item-input {
      padding: 6px;
      font-size: 14px;
      line-height: 1.4;
    }

    .drag-handle {
      display: none;
    }

    .delete-button {
      width: 16px;
      height: 16px;
    }
  }
}
</style>
