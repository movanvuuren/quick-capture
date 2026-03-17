import { computed, ref } from 'vue'
import type { AppFile } from './listFiles'
import { loadDashboardData } from './listFiles'
import type { StoredTodoList } from './lists'
import { loadSettings } from './settings'

interface DashboardSnapshot {
  lists: StoredTodoList[]
  tasks: StoredTodoList[]
  notes: AppFile[]
}

export function useDashboardData(refreshDebounceMs = 1200) {
  let cachedFolderUri = ''
  let cachedAt = 0
  let cachedSnapshot: DashboardSnapshot | null = null
  let activeRefreshPromise: Promise<void> | null = null

  const settings = ref(loadSettings())
  const baseFolderUri = computed(() => settings.value.baseFolderUri || '')

  const lists = ref<StoredTodoList[]>([])
  const tasks = ref<StoredTodoList[]>([])
  const notes = ref<AppFile[]>([])

  const isLoading = ref(true)
  const error = ref('')

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
      && Date.now() - cachedAt < refreshDebounceMs

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

  return {
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
    refreshDashboard,
    syncSettingsAndRefresh,
  }
}
