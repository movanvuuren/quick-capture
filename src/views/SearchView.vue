<script setup lang="ts">
import { CheckSquare, FileText, List } from 'lucide-vue-next'
import { computed, nextTick, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { loadDashboardData } from '../lib/listFiles'
import type { AppFile } from '../lib/listFiles'
import { loadSettings } from '../lib/settings'
import BottomActionNav from '../components/BottomActionNav.vue'
import PageHeader from '../components/PageHeader.vue'

const router = useRouter()
const settings = loadSettings()

const query = ref('')
const allFiles = ref<AppFile[]>([])
const indexedContentByFile = ref<Record<string, string>>({})
const isLoading = ref(false)
const error = ref('')
const searchInputEl = ref<HTMLInputElement | null>(null)

onMounted(async () => {
  await nextTick()
  searchInputEl.value?.focus()

  if (!settings.baseFolderUri) {
    return
  }

  isLoading.value = true
  try {
    const dashboard = await loadDashboardData(settings.baseFolderUri)
    allFiles.value = dashboard.files

    const nextIndex: Record<string, string> = {}
    for (const list of [...dashboard.lists, ...dashboard.tasks]) {
      const fileName = list.fileName
      if (!fileName)
        continue

      nextIndex[fileName] = list.items
        .map(item => item.text.trim())
        .filter(text => text.length > 0)
        .join(' ')
    }
    indexedContentByFile.value = nextIndex
  }
  catch (err) {
    console.error('Search: failed to load files', err)
    error.value = 'Could not load files.'
  }
  finally {
    isLoading.value = false
  }
})

const filteredResults = computed(() => {
  const q = query.value.trim().toLowerCase()
  if (!q)
    return { tasks: [] as AppFile[], lists: [] as AppFile[], notes: [] as AppFile[] }

  const tasks: AppFile[] = []
  const lists: AppFile[] = []
  const notes: AppFile[] = []

  for (const file of allFiles.value) {
    const indexedBody = indexedContentByFile.value[file.name] || ''
    const searchable = `${file.title} ${file.name} ${file.preview} ${indexedBody}`.toLowerCase()
    if (!searchable.includes(q))
      continue

    if (file.type === 'task')
      tasks.push(file)
    else if (file.type === 'list')
      lists.push(file)
    else
      notes.push(file)
  }

  return { tasks, lists, notes }
})

const hasQuery = computed(() => query.value.trim().length > 0)
const hasResults = computed(() => {
  const { tasks, lists, notes } = filteredResults.value
  return tasks.length > 0 || lists.length > 0 || notes.length > 0
})

function goBack() {
  router.replace('/')
}

function openFile(file: AppFile) {
  if (file.type === 'task')
    router.push('/tasks')
  else if (file.type === 'list')
    router.push(`/list/${encodeURIComponent(file.name)}`)
  else
    router.push(`/note/${encodeURIComponent(file.name)}`)
}
</script>

<template>
  <div class="page">
    <PageHeader title="Search" @back="goBack" />
    <div class="search-toolbar">
      <button class="glass-icon-button back-button" aria-label="Go home" @click="goBack">
        ←
      </button>
      <div class="search-wrap">
        <input ref="searchInputEl" v-model="query" class="glass-input search-input" type="search"
          placeholder="Search lists, tasks, notes…" autocomplete="off" autocorrect="off" autocapitalize="off"
          spellcheck="false">
      </div>
    </div>

    <div v-if="isLoading" class="loading-inline">
      <div class="loading-spinner" aria-hidden="true" />
    </div>

    <div v-else-if="error" class="card glass-card empty-state">
      <p class="error-text">{{ error }}</p>
    </div>

    <div v-else-if="!settings.baseFolderUri" class="card glass-card empty-state">
      <p class="empty-title">No folder selected</p>
      <p class="empty-text">Choose a folder in Settings to enable search.</p>
    </div>

    <div v-else-if="hasQuery && !hasResults && !isLoading" class="card glass-card empty-state">
      <p class="empty-title">No results</p>
      <p class="empty-text">Nothing matched "{{ query.trim() }}".</p>
    </div>

    <div v-else-if="!hasQuery" class="card glass-card empty-state">
      <p class="empty-text">Start typing to search across all your files.</p>
    </div>

    <template v-else>
      <!-- Tasks -->
      <section v-if="filteredResults.tasks.length > 0" class="result-section">
        <h2 class="section-label">
          <CheckSquare :size="14" class="section-icon" />
          Tasks
        </h2>
        <div class="card glass-card result-list">
          <button v-for="file in filteredResults.tasks" :key="file.name" class="result-item" @click="openFile(file)">
            <span class="result-title">{{ file.title }}</span>
            <span v-if="file.preview" class="result-preview">{{ file.preview }}</span>
          </button>
        </div>
      </section>

      <!-- Lists -->
      <section v-if="filteredResults.lists.length > 0" class="result-section">
        <h2 class="section-label">
          <List :size="14" class="section-icon" />
          Lists
        </h2>
        <div class="card glass-card result-list">
          <button v-for="file in filteredResults.lists" :key="file.name" class="result-item" @click="openFile(file)">
            <span class="result-title">{{ file.title }}</span>
            <span v-if="file.preview" class="result-preview">{{ file.preview }}</span>
          </button>
        </div>
      </section>

      <!-- Notes -->
      <section v-if="filteredResults.notes.length > 0" class="result-section">
        <h2 class="section-label">
          <FileText :size="14" class="section-icon" />
          Notes
        </h2>
        <div class="card glass-card result-list">
          <button v-for="file in filteredResults.notes" :key="file.name" class="result-item" @click="openFile(file)">
            <span class="result-title">{{ file.title }}</span>
            <span v-if="file.preview" class="result-preview">{{ file.preview }}</span>
          </button>
        </div>
      </section>
    </template>
  </div>
  <BottomActionNav />
</template>

<style scoped>
.page {
  min-height: 100vh;
  padding: var(--page-top-padding) 20px 36px;
  color: var(--text);
}

.search-toolbar {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 18px;
}

.back-button {
  display: none;
}

.search-wrap {
  flex: 1;
  display: flex;
  align-items: center;
}

.search-input {
  width: 100%;
}

.glass-input {
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--radius-small);
  padding: 8px 12px;
  color: var(--text);
  font-size: 0.95rem;
  outline: none;
  width: 100%;
}

.glass-input:focus {
  border-color: color-mix(in srgb, var(--primary) 60%, transparent);
  box-shadow: 0 0 0 3px color-mix(in srgb, var(--primary) 18%, transparent);
}

.glass-card {
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--radius-card);
  backdrop-filter: blur(14px) saturate(1.2);
  -webkit-backdrop-filter: blur(14px) saturate(1.2);
  box-shadow: var(--shadow);
}

.card {
  padding: 14px;
  margin-bottom: 12px;
}

.loading-inline {
  display: flex;
  justify-content: center;
  padding: 32px 0;
}

.loading-spinner {
  width: 20px;
  height: 20px;
  border-radius: 999px;
  border: 2px solid color-mix(in srgb, var(--text-soft) 22%, transparent);
  border-top-color: color-mix(in srgb, var(--primary) 72%, var(--text));
  animation: spin 0.75s linear infinite;
}

@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}

.empty-state {
  text-align: center;
  padding: 28px 16px;
}

.empty-title {
  font-weight: 600;
  margin: 0 0 6px;
}

.empty-text {
  color: var(--text-soft);
  margin: 0;
  font-size: 0.9rem;
}

.error-text {
  color: var(--danger);
}

.result-section {
  margin-bottom: 4px;
}

.section-label {
  font-size: 0.75rem;
  font-weight: 600;
  letter-spacing: 0.08em;
  text-transform: uppercase;
  color: var(--text-soft);
  margin: 0 0 6px 4px;
  display: flex;
  align-items: center;
  gap: 6px;
}

.section-icon {
  opacity: 0.7;
}

.result-list {
  padding: 0;
  overflow: hidden;
}

.result-item {
  display: flex;
  flex-direction: column;
  gap: 3px;
  width: 100%;
  padding: 12px 16px;
  background: none;
  border: none;
  border-bottom: 1px solid var(--border);
  text-align: left;
  cursor: pointer;
  color: var(--text);
  transition: background 0.12s;
}

.result-item:last-child {
  border-bottom: none;
}

.result-item:active {
  background: color-mix(in srgb, var(--primary) 8%, transparent);
}

.result-title {
  font-size: 0.95rem;
  font-weight: 500;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.result-preview {
  font-size: 0.8rem;
  color: var(--text-soft);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
</style>
