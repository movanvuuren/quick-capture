export type TodoState = 'pending' | 'done' | 'cancelled'

export interface TodoItem {
  text: string
  state: TodoState
}

export interface TodoList {
  id: string
  title: string
  items: TodoItem[]
  pinned?: boolean // optional for backwards compatibility
}

const STORAGE_KEY = 'quick-capture-lists'

export function loadLists(): TodoList[] {
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    if (!raw)
      return []
    const parsed = JSON.parse(raw) as any[]
    return parsed.map((list) => {
      const items: TodoItem[] = []
      if (Array.isArray(list.items)) {
        list.items.forEach((it: any) => {
          if (typeof it === 'string') {
            // legacy string, treat as pending
            const txt = it.replace(/^- \[.\] ?/, '')
            const c = it.match(/^- \[(.)\]/)?.[1]
            const state: TodoState = c === 'x' ? 'done' : c === '-' ? 'cancelled' : 'pending'
            items.push({ text: txt, state })
          }
          else if (it && typeof it.text === 'string') {
            items.push({ text: it.text, state: it.state || 'pending' })
          }
        })
      }
      if (items.length === 0) {
        items.push({ text: '', state: 'pending' })
      }
      return {
        id: list.id,
        title: list.title || '',
        items,
        pinned: !!list.pinned,
      }
    })
  }
  catch {
    return []
  }
}

export function saveLists(lists: TodoList[]): void {
  localStorage.setItem(STORAGE_KEY, JSON.stringify(lists))
}

export function slugify(str: string): string {
  return str
    .toLowerCase()
    .trim()
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-+|-+$/g, '')
}
