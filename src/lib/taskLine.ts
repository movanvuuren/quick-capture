import type { TodoState } from './lists'

export type TaskRepeat = 'daily' | 'weekly' | 'weekdays' | 'monthly'

export interface ParsedTaskLine {
  state: TodoState
  body: string
  dueDate?: string
  isHighPriority?: boolean
  repeat?: TaskRepeat
}

const TASK_LINE_REGEX = /^\s*-\s\[([fFxX-\s]*?)\](?:\s+(.*))?$/

export function stateToCheckbox(state: TodoState): string {
  switch (state) {
    case 'done':
      return 'x'
    case 'cancelled':
      return '-'
    default:
      return ' '
  }
}

export function checkboxToState(value: string): TodoState {
  if (value === 'x' || value === 'X')
    return 'done'
  if (value === '-')
    return 'cancelled'
  return 'pending'
}

export function parseTaskLine(line: string): ParsedTaskLine | null {
  const match = line.match(TASK_LINE_REGEX)
  if (!match)
    return null

  const [, markerRaw = '', rawBody = ''] = match
  const body = rawBody.trim()

  const markerStr = markerRaw.toLowerCase()
  const isHighPriority = markerStr.includes('f')
  const stateChar = markerStr.replace(/f/g, '').trim() || ' '

  const dueMatch = body.match(/📅\s*(\d{4}-\d{2}-\d{2})/)
  const dueDate = dueMatch?.[1]

  const repeatMatch = body.match(/🔁\s*(daily|weekly|weekdays|monthly)/i)
  const repeatValue = repeatMatch?.[1]?.toLowerCase()
  const repeat = repeatValue as TaskRepeat | undefined

  return {
    state: checkboxToState(stateChar),
    body,
    dueDate,
    isHighPriority: isHighPriority || undefined,
    repeat,
  }
}

export function serializeTaskLine(
  state: TodoState,
  body: string,
  dueDate?: string,
  isHighPriority?: boolean,
): string {
  let marker = stateToCheckbox(state)
  if (isHighPriority && state === 'pending')
    marker = `${marker}f`.trim() || 'f'

  let normalizedBody = body.replace(/\s*📅\s*\d{4}-\d{2}-\d{2}\s*/g, ' ').trim()
  if (dueDate)
    normalizedBody = `${normalizedBody} 📅 ${dueDate}`.trim()

  return `- [${marker}] ${normalizedBody}`
}
