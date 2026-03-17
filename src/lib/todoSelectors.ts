import type { TodoItem } from './lists'

interface TodoContainer {
  items: TodoItem[]
}

function isVisibleItem(item: TodoItem) {
  return item.text.trim().length > 0 && item.state !== 'cancelled'
}

export function getActiveItems(list: TodoContainer) {
  return list.items.filter(isVisibleItem)
}

export function getCompletedCount(list: TodoContainer) {
  return list.items.filter(item => item.state === 'done' && item.text.trim().length > 0).length
}

export function getProgressPercent(list: TodoContainer) {
  const active = getActiveItems(list)
  if (active.length === 0)
    return 0

  const done = active.filter(item => item.state === 'done').length
  return Math.round((done / active.length) * 100)
}

export function getPreviewItems(list: TodoContainer) {
  return list.items.filter(isVisibleItem).slice(0, 3)
}

export function getPreviewOverflowCount(list: TodoContainer) {
  const visibleCount = list.items.filter(isVisibleItem).length
  return Math.max(visibleCount - 3, 0)
}
