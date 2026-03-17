import type { ParsedTaskLine, TaskRepeat } from './taskLine'
import type { TodoState } from './lists'
import { parseTaskLine } from './taskLine'

export interface QuickTaskComparable {
  lineIndex: number
  state: TodoState
  body: string
  dueDate?: string
  isHighPriority?: boolean
  repeat?: TaskRepeat
}

export interface ScannedQuickTaskLine extends QuickTaskComparable {
  lineIndex: number
}

export function scanQuickTaskLines(content: string): ScannedQuickTaskLine[] {
  const lines = content.split(/\r?\n/)
  const scanned: ScannedQuickTaskLine[] = []

  for (let lineIndex = 0; lineIndex < lines.length; lineIndex += 1) {
    const line = lines[lineIndex] || ''
    const parsed = parseTaskLine(line)
    if (!parsed)
      continue

    scanned.push({
      lineIndex,
      state: parsed.state,
      body: parsed.body,
      dueDate: parsed.dueDate,
      isHighPriority: parsed.isHighPriority,
      repeat: parsed.repeat,
    })
  }

  return scanned
}

export function matchesScannedTask(task: QuickTaskComparable, scanned: ParsedTaskLine): boolean {
  return scanned.body === task.body
    && scanned.state === task.state
    && scanned.dueDate === task.dueDate
    && Boolean(scanned.isHighPriority) === Boolean(task.isHighPriority)
    && scanned.repeat === task.repeat
}

export function resolveTaskLineIndex(lines: string[], task: QuickTaskComparable): number {
  if (task.lineIndex >= 0 && task.lineIndex < lines.length) {
    const parsedAtStoredIndex = parseTaskLine(lines[task.lineIndex] || '')
    if (parsedAtStoredIndex && matchesScannedTask(task, parsedAtStoredIndex))
      return task.lineIndex
  }

  for (let lineIndex = 0; lineIndex < lines.length; lineIndex += 1) {
    const parsed = parseTaskLine(lines[lineIndex] || '')
    if (parsed && matchesScannedTask(task, parsed))
      return lineIndex
  }

  return -1
}
