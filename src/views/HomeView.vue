<script setup lang="ts">
import { computed, onMounted, onActivated, ref } from 'vue'
import { useRouter } from 'vue-router'
import { FileText, List, CheckSquare, Settings, Pin, Plus } from 'lucide-vue-next'
import { loadSettings } from '../lib/settings'
import type { AppFile } from '../lib/listFiles'
import { loadAllFiles } from '../lib/listFiles'

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
      router.push(`/list/${encodeURIComponent(file.name)}`)
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
        <button class="primary-button" @click="go('/list')">
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
        <div class="card-title-row">
          <component :is="getIconForFileType(file.type)" :size="18" class="file-icon" />
          <span class="file-title">{{ file.title }}</span>
          <Pin v-if="file.pinned" :size="16" class="pin-icon" />
        </div>
      </div>
    </div>

    <div class="quick-add-bar">
      <button v-for="preset in quickTaskPresets" :key="preset.id" class="glass-icon-button quick-add-button"
        @click="goTask(preset.id)">
        {{ preset.label }}
      </button>
      <button class="glass-icon-button quick-add-button" @click="go('/list')">
        <Plus :size="16" />
        List
      </button>
      <button class="glass-icon-button quick-add-button" @click="go('/note')">
        <Plus :size="16" />
        Note
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
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  flex-grow: 1;
}

.pin-icon {
  flex-shrink: 0;
  color: var(--primary);
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
  padding: 12px 32px;
  /* Increased horizontal padding */
  font-size: 0.95rem;
  font-weight: 600;
  display: flex;
  align-items: center;
  justify-content: center;
  /* Center content */
  gap: 6px;
}
</style>
