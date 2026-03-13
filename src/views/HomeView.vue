<script setup lang="ts">
import { computed, onMounted, onActivated, ref } from 'vue'
import { useRouter } from 'vue-router'
import { FileText, List, CheckSquare, Settings, Plus } from 'lucide-vue-next'
import PinToggleButton from '../components/PinToggleButton.vue'
import { loadSettings } from '../lib/settings'
import type { AppFile } from '../lib/listFiles'
import { loadAllFiles, setFilePinned } from '../lib/listFiles'

const router = useRouter()
const settings = loadSettings()
const baseFolderUri = computed(() => settings.baseFolderUri)
const quickTaskPresets = computed(() => settings.quickTaskPresets.slice(0, 2))

const files = ref<AppFile[]>([])
const isLoading = ref(true)
const error = ref('')

async function refreshFiles() {
  if (!baseFolderUri.value) {
    files.value = []
    error.value = ''
    isLoading.value = false
    return
  }

  isLoading.value = true
  error.value = ''

  try {
    files.value = await loadAllFiles(baseFolderUri.value)
  }
  catch (err) {
    error.value = 'Failed to load files. Check folder permissions.'
    console.error(err)
  }
  finally {
    isLoading.value = false
  }
}

onMounted(refreshFiles)
onActivated(refreshFiles)

function navigateToFile(file: AppFile) {
  switch (file.type) {
    case 'list':
    case 'task':
      router.push({
        name: 'list-detail',
        params: { id: file.name },
      })
      break
    case 'note':
      router.push(`/note/${encodeURIComponent(file.name)}`)
      break
    default:
      console.warn('Unknown file type, cannot navigate', file.type)
  }
}

function getIconForFileType(type: AppFile['type']) {
  switch (type) {
    case 'list':
      return List
    case 'task':
      return CheckSquare
    case 'note':
    default:
      return FileText
  }
}

function go(path: string) {
  router.push(path)
}

function goTask(presetId: string) {
  router.push(`/task/${presetId}`)
}

function sortPinnedFirst(items: AppFile[]): AppFile[] {
  return [...items].sort((a, b) => {
    if (a.pinned && !b.pinned)
      return -1
    if (!a.pinned && b.pinned)
      return 1
    return a.name.localeCompare(b.name)
  })
}

async function toggleFilePin(file: AppFile) {
  if (!baseFolderUri.value)
    return

  const nextPinned = !file.pinned
  const previousPinned = file.pinned
  file.pinned = nextPinned
  files.value = sortPinnedFirst(files.value)

  try {
    await setFilePinned(baseFolderUri.value, file.name, nextPinned)
  }
  catch (err) {
    file.pinned = previousPinned
    files.value = sortPinnedFirst(files.value)
    console.error('Failed to update pinned state', err)
  }
}
</script>

<template>
  <div class="page">
    <div class="top-row">
      <div>
        <h1>Quick Capture</h1>
        <p class="subtitle">
          Your central vault of notes and tasks
        </p>
      </div>

      <button class="glass-icon-button" aria-label="Go to settings" @click="go('/settings')">
        <Settings :size="22" />
      </button>
    </div>

    <!-- Loading State -->
    <div v-if="isLoading" class="empty-state">
      <p>Loading files...</p>
    </div>

    <!-- Error State -->
    <div v-else-if="error" class="empty-state">
      <p class="error-text">
        {{ error }}
      </p>
    </div>

    <!-- No Folder Configured State -->
    <div v-else-if="!baseFolderUri" class="empty-state card">
      <div class="empty-icon">
        📁
      </div>
      <p class="empty-title">
        No Folder Selected
      </p>
      <p class="empty-text">
        Please select a folder in settings to begin.
      </p>
      <button class="primary-button" @click="go('/settings')">
        Go to Settings
      </button>
    </div>

    <!-- Folder is Empty State -->
    <div v-else-if="files.length === 0" class="empty-state card">
      <div class="empty-icon">
        ✨
      </div>
      <p class="empty-title">
        Folder is Empty
      </p>
      <p class="empty-text">
        Create a new list or note to get started.
      </p>
      <div class="button-group">
        <button class="primary-button" @click="go('/lists')">
          + New List
        </button>
        <button class="primary-button" @click="go('/note')">
          + New Note
        </button>
      </div>
    </div>

    <!-- File Browser Grid -->
    <div v-else class="grid">
      <div v-for="file in files" :key="file.id" class="glass-button file-card" @click="navigateToFile(file)">
        <div class="file-row">
          <div class="file-icon-wrap">
            <component :is="getIconForFileType(file.type)" :size="18" class="file-icon" />
          </div>

          <div class="file-content">
            <div class="file-title-row">
              <span class="file-title">{{ file.title }}</span>
              <PinToggleButton :pinned="file.pinned" item-label="file" @toggle="toggleFilePin(file)" />
            </div>

            <div class="file-preview">
              {{ file.preview }}
            </div>

            <div class="file-meta">
              <span class="file-type">{{ file.type }}</span>
              <span v-if="file.updated" class="file-date">{{ file.updated }}</span>
            </div>
          </div>
        </div>
      </div>
    </div>

    <div class="quick-add-bar">
      <button v-for="preset in quickTaskPresets" :key="preset.id" class="glass-icon-button quick-add-button"
        @click="goTask(preset.id)">
        {{ preset.label }}
      </button>
      <button class="glass-icon-button quick-add-button" @click="go('/lists')">
        <Plus :size="12" />
        View Lists
      </button>
      <button class="glass-icon-button quick-add-button" @click="go('/note')">
        <Plus :size="12" />
        Add Note
      </button>
    </div>
  </div>
</template>

<style scoped>
.page {
  min-height: 100vh;
  padding: 24px 20px 120px;
  /* Added padding-bottom for quick-add-bar */
  background: var(--bg);
  color: var(--text);
}

.top-row {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 16px;
  margin-bottom: 24px;
}

h1 {
  margin: 0;
  font-size: 1.8rem;
}

.subtitle {
  margin: 8px 0 0;
  color: var(--text-soft);
}

.grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(200px, 1fr));
  gap: 16px;
}

.file-card {
  padding: 16px;
  text-align: left;
  display: flex;
  flex-direction: column;
  justify-content: space-between;
}

.card-title-row {
  display: flex;
  align-items: center;
  gap: 8px;
  font-weight: 600;
}

.file-icon {
  flex-shrink: 0;
  color: var(--text-soft);
}

.file-title {
  overflow: hidden;
  text-overflow: ellipsis;
  flex-grow: 1;
  display: -webkit-box;
  line-clamp: 3;
  -webkit-line-clamp: 3;
  -webkit-box-orient: vertical;
  white-space: normal;
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

.error-text {
  color: var(--c-red);
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
  gap: 12px;
  padding: 16px 20px;
  background: color-mix(in srgb, var(--bg) 80%, transparent);
  backdrop-filter: blur(12px);
  -webkit-backdrop-filter: blur(12px);
  border-top: 1px solid var(--border);
}

.quick-add-button {
  padding: 12px 42px;
  /* Increased horizontal padding */
  font-size: 0.95rem;
  font-weight: 600;
  display: flex;
  align-items: center;
  justify-content: center;
  /* Center content */
  gap: 2px;
}

.grid {
  display: grid;
  grid-template-columns: 1fr;
  gap: 16px;
}

.file-card {
  padding: 16px 18px;
  text-align: left;
  border-radius: 22px;
  min-height: 108px;
  transition:
    transform 0.16s ease,
    box-shadow 0.16s ease;
}

.file-card:active {
  transform: scale(0.985);
}

.file-row {
  display: flex;
  align-items: flex-start;
  gap: 14px;
  width: 100%;
}

.file-icon-wrap {
  width: 42px;
  height: 42px;
  flex-shrink: 0;
  display: grid;
  place-items: center;
  border-radius: 12px;
  background: var(--primary-soft);
  border: 1px solid var(--border);
  margin-top: 2px;
}

.file-icon {
  color: var(--text-soft);
}

.file-content {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  align-items: flex-start;
}

.file-title-row {
  width: 100%;
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}

.file-title {
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

.file-preview {
  margin-top: 4px;
  font-size: 0.9rem;
  line-height: 1.4;
  color: var(--text-soft);
  text-align: left;

  display: -webkit-box;
  line-clamp: 2;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.file-meta {
  width: 100%;
  margin-top: 10px;
  display: flex;
  justify-content: flex-end;
  align-items: center;
  gap: 8px;
  font-size: 0.7rem;
  color: var(--text-soft);
  opacity: 0.8;
}

.file-type {
  text-transform: capitalize;
  padding: 4px 9px;
  border-radius: 999px;
  border: 1px solid var(--border);
  background: color-mix(in srgb, var(--surface) 78%, transparent);
}

.file-date {
  white-space: nowrap;
  opacity: 0.9;
}
</style>
