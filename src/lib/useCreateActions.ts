import { useRoute, useRouter } from 'vue-router'
import { createListFile } from '../lib/listFiles'
import { loadSettings } from '../lib/settings'

export function useCreateActions() {
  const router = useRouter()
  const route = useRoute()
  const settings = loadSettings()

  function makeDraftTitle(kind: 'list' | 'task') {
    const prefix = kind === 'task' ? 'Task' : 'List'
    const date = new Date().toISOString().slice(0, 10)
    return `${prefix} ${date}`
  }

  async function createTodoFile(kind: 'list' | 'task') {
    if (!settings.baseFolderUri) {
      router.push('/settings')
      return
    }

    try {
      const created = await createListFile(settings.baseFolderUri, {
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
      console.error(`Failed to create ${kind}`, err)
    }
  }

  async function navigateViaHome(path: string) {
    if (route.path === path)
      return

    if (route.path !== '/')
      await router.replace('/')

    await router.push(path)
  }

  async function createNote() {
    await navigateViaHome('/note')
  }

  async function openTasks() {
    await navigateViaHome('/tasks')
  }

  return {
    createTodoFile,
    createNote,
    openTasks,
  }
}
