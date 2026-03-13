export interface TodoItem {
  text: string
  state: TodoState
}

export interface TodoList {
  id: string
  title: string
  items: TodoItem[]
  pinned?: boolean
  created?: string
  updated?: string
  type?: string
  fileName?: string
}

export interface FrontmatterData {
  type?: string
  created?: string
  updated?: string
  pinned?: boolean
  id?: string
  [key: string]: string | boolean | undefined
}

export function slugify(str: string): string {
  return str
    .toLowerCase()
    .trim()
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-+|-+$/g, '')
}

export function formatDate(date = new Date()): string {
  return date.toISOString().slice(0, 10)
}

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

export function escapeYamlValue(value: string): string {
  if (value === '')
    return '""'

  // Quote values that may confuse simple YAML parsing
  if (/[:#\-\n]|^\s|\s$/.test(value))
    return JSON.stringify(value)

  return value
}

export function parseYamlValue(raw: string): string | boolean {
  const value = raw.trim()

  if (value === 'true')
    return true
  if (value === 'false')
    return false

  // Handles JSON-style quoted strings like "my value"
  if (
    (value.startsWith('"') && value.endsWith('"'))
    || (value.startsWith('\'') && value.endsWith('\''))
  ) {
    try {
      return JSON.parse(value)
    }
    catch {
      return value.slice(1, -1)
    }
  }

  return value
}

export function parseFrontmatter(markdown: string): FrontmatterData {
  const match = markdown.match(/^---\r?\n([\s\S]*?)\r?\n---/)
if (!match)
  return {}

const [, frontmatter = ''] = match
const result: FrontmatterData = {}
const lines = frontmatter.split(/\r?\n/)

  for (const line of lines) {
    const trimmed = line.trim()
    if (!trimmed || trimmed.startsWith('#'))
      continue

    const idx = line.indexOf(':')
    if (idx === -1)
      continue

    const key = line.slice(0, idx).trim()
    const rawValue = line.slice(idx + 1).trim()

    result[key] = parseYamlValue(rawValue) as string | boolean
  }

  return result
}

export function stripFrontmatter(markdown: string): string {
  return markdown.replace(/^---\r?\n[\s\S]*?\r?\n---\r?\n?/, '')
}

export function getListFileName(list: TodoList): string {
  const created = list.created || formatDate()
  const base = slugify(list.title || 'untitled-list')
  return `${created}-${base}.md`
}

export function buildFrontmatter(data: FrontmatterData): string {
  const lines: string[] = ['---']

  if (data.type)
    lines.push(`type: ${escapeYamlValue(String(data.type))}`)
  if (data.created)
    lines.push(`created: ${escapeYamlValue(String(data.created))}`)
  if (data.updated)
    lines.push(`updated: ${escapeYamlValue(String(data.updated))}`)
  if (typeof data.pinned === 'boolean')
    lines.push(`pinned: ${data.pinned}`)
  if (data.id)
    lines.push(`id: ${escapeYamlValue(String(data.id))}`)

  lines.push('---')
  return lines.join('\n')
}

export function listToMarkdown(list: TodoList): string {
  const created = list.created || formatDate()
  const updated = formatDate()
  const title = (list.title || 'Untitled List').trim()

  const frontmatter = buildFrontmatter({
    type: 'list',
    created,
    updated,
    pinned: !!list.pinned,
    id: list.id,
  })

  const items = list.items
    .filter(item => item.text.trim().length > 0)
    .map(item => `- [${stateToCheckbox(item.state)}] ${item.text.trim()}`)
    .join('\n')

  return [
    frontmatter,
    '',
    `# ${title}`,
    '',
    items || '- [ ] ',
    '',
  ].join('\n')
}

export function markdownToList(markdown: string, fileName = ''): TodoList {
  const frontmatter = parseFrontmatter(markdown)
  const body = stripFrontmatter(markdown).trim()

  const lines = body.split(/\r?\n/)

  let title = ''
  const items: TodoItem[] = []

  for (const line of lines) {
    if (!title && line.startsWith('# ')) {
      title = line.slice(2).trim()
      continue
    }

    const match = line.match(/^- \[([ xX-])\]\s+(.*)$/)
    if (match) {
      const [, rawState = ' ', rawText = ''] = match

      items.push({
        text: rawText.trim(),
        state: checkboxToState(rawState),
      })
    }
  }

  if (!title) {
    title = fileName
      .replace(/\.md$/i, '')
      .replace(/^\d{4}-\d{2}-\d{2}-/, '')
      .replace(/-/g, ' ')
      .replace(/\b\w/g, char => char.toUpperCase())
  }

  return {
    id: typeof frontmatter.id === 'string'
      ? frontmatter.id
      : fileName || slugify(title),
    title,
    items: items.length > 0 ? items : [{ text: '', state: 'pending' }],
    pinned: frontmatter.pinned === true,
    created: typeof frontmatter.created === 'string' ? frontmatter.created : undefined,
    updated: typeof frontmatter.updated === 'string' ? frontmatter.updated : undefined,
    type: typeof frontmatter.type === 'string' ? frontmatter.type : 'list',
    fileName,
  }
}

export function isListMarkdown(markdown: string): boolean {
  const frontmatter = parseFrontmatter(markdown)
  return frontmatter.type === 'list'
}

export type TodoState = 'pending' | 'done' | 'cancelled'


export interface StoredTodoList extends TodoList {
  fileName: string
}