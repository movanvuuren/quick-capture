import { useRouter } from 'vue-router'
import { createListFile } from '../lib/listFiles'
import { loadSettings } from '../lib/settings'

export function useCreateActions() {
  const router = useRouter()
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

  function createNote() {
    router.push('/note')
  }

  function openTasks() {
    router.push('/tasks')
  }

  return {
    createTodoFile,
    createNote,
    openTasks,
  }
}