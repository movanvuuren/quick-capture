<script setup lang="ts">
import { computed, onActivated, onMounted, ref } from 'vue'
import { FileText, Plus } from 'lucide-vue-next'
import { useRouter } from 'vue-router'
import PinToggleButton from '../components/PinToggleButton.vue'
import { loadAllFiles, setFilePinned } from '../lib/listFiles'
import type { AppFile } from '../lib/listFiles'
import { loadSettings } from '../lib/settings'

const router = useRouter()
const settings = loadSettings()
const baseFolderUri = computed(() => settings.baseFolderUri || '')

const notes = ref<AppFile[]>([])
const isLoading = ref(true)
const error = ref('')

function sortPinnedFirst(items: AppFile[]): AppFile[] {
  return [...items].sort((a, b) => {
    if (a.pinned && !b.pinned)
      return -1
    if (!a.pinned && b.pinned)
      return 1
    return a.name.localeCompare(b.name)
  })
}

async function refreshNotes() {
  if (!baseFolderUri.value) {
    notes.value = []
    isLoading.value = false
    return
  }

  isLoading.value = true
  error.value = ''

  try {
    const files = await loadAllFiles(baseFolderUri.value)
    notes.value = sortPinnedFirst(files.filter(file => file.type === 'note'))
  }
  catch (err) {
    console.error('Failed to load notes', err)
    error.value = 'Failed to load notes.'
  }
  finally {
    isLoading.value = false
  }
}

onMounted(refreshNotes)
onActivated(refreshNotes)

function goBack() {
  router.push('/')
}

function openNote(fileName: string) {
  router.push(`/note/${encodeURIComponent(fileName)}`)
}

function createNote() {
  router.push('/note')
}

async function togglePin(note: AppFile) {
  if (!baseFolderUri.value)
    return

  const nextPinned = !note.pinned
  const previousPinned = note.pinned
  note.pinned = nextPinned
  notes.value = sortPinnedFirst(notes.value)

  try {
    await setFilePinned(baseFolderUri.value, note.name, nextPinned)
  }
  catch (err) {
    note.pinned = previousPinned
    notes.value = sortPinnedFirst(notes.value)
    console.error('Failed to update pinned state', err)
  }
}
</script>

<template>
  <div class="page">
    <div class="header">
      <button class="glass-icon-button back-button" aria-label="Go back" @click="goBack">
        ←
      </button>

      <div>
        <h1>Notes</h1>
        <p class="subtitle">Your notes - pinned first</p>
      </div>
    </div>

    <div v-if="isLoading" class="empty-state">
      Loading notes...
    </div>

    <div v-else-if="error" class="empty-state">
      {{ error }}
    </div>

    <div v-else-if="notes.length === 0" class="empty-state">
      <p class="empty-title">No notes yet</p>
      <button class="glass-button glass-button--primary" @click="createNote">
        <Plus :size="16" />
        New note
      </button>
    </div>

    <template v-else>
      <div class="notes-grid">
        <div v-for="note in notes" :key="note.id" class="glass-button note-card" @click="openNote(note.name)">
          <div class="note-head">
            <div class="icon-wrap">
              <FileText :size="16" />
            </div>
            <PinToggleButton :pinned="note.pinned" item-label="note" @toggle="togglePin(note)" />
          </div>

          <h3 class="note-title">{{ note.title }}</h3>
          <p class="note-preview">{{ note.preview || 'No preview available.' }}</p>

          <div class="note-meta">
            <span>Note</span>
            <span v-if="note.updated">{{ note.updated }}</span>
          </div>
        </div>
      </div>

      <button class="glass-button glass-button--primary glass-button--block" @click="createNote">
        <Plus :size="16" />
        New note
      </button>
    </template>
  </div>
</template>

<style scoped>
.page {
  min-height: 100vh;
  padding: 24px 20px 32px;
  color: var(--text);
}

.header {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 20px;
}

.header h1 {
  margin: 0;
  font-size: 1.9rem;
}

.subtitle {
  margin: 4px 0 0;
  color: var(--text-soft);
}

.empty-state {
  padding: 24px;
  text-align: center;
  border: 1px solid var(--border);
  border-radius: 20px;
  background: var(--surface);
  display: grid;
  gap: 12px;
}

.empty-title {
  margin: 0;
  font-weight: 700;
}

.notes-grid {
  display: grid;
  gap: 14px;
  margin-bottom: 16px;
}

.note-card {
  text-align: left;
  border-radius: 20px;
  padding: 14px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.note-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.icon-wrap {
  width: 34px;
  height: 34px;
  border-radius: 12px;
  display: grid;
  place-items: center;
  border: 1px solid var(--border);
  background: var(--primary-soft);
}

.note-title {
  margin: 0;
  font-size: 1rem;
  line-height: 1.25;
}

.note-preview {
  margin: 0;
  color: var(--text-soft);
  font-size: 0.9rem;
  display: -webkit-box;
  line-clamp: 2;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.note-meta {
  display: flex;
  justify-content: space-between;
  color: var(--text-soft);
  font-size: 0.75rem;
}
</style>
