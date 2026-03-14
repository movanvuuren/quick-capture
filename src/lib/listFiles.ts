import type { TodoList, StoredTodoList, Note, StoredNote } from './lists'
import { FolderPicker } from '../plugins/folder-picker'
import {
  buildFrontmatter,
  coerceBoolean,
  formatDate,
  listToMarkdown,
  markdownToList,
  noteToMarkdown,
  parseFrontmatter,
  stripFrontmatter,
} from './lists'

export interface AppFile {
  id: string
  name: string
  title: string
  type: 'list' | 'task' | 'note' | 'unknown'
  pinned?: boolean
  created?: string
  updated?: string
  preview: string
}

export type TodoFileType = 'list' | 'task'

export interface DashboardData {
  files: AppFile[]
  lists: StoredTodoList[]
  tasks: StoredTodoList[]
}

function setPinnedInMarkdown(markdown: string, pinned: boolean): string {
  const frontmatterMatch = markdown.match(/^---\r?\n([\s\S]*?)\r?\n---\r?\n?/)

  if (!frontmatterMatch) {
    const body = markdown.trimStart()
    return `---\npinned: ${pinned}\n---\n\n${body}`
  }

  const frontmatterBlock = frontmatterMatch[0]
  const frontmatterContent = frontmatterMatch[1] || ''
  const lines = frontmatterContent.split(/\r?\n/)

  let foundPinned = false
  const updatedLines = lines.map((line) => {
    if (/^\s*pinned\s*:/.test(line)) {
      foundPinned = true
      return `pinned: ${pinned}`
    }
    return line
  })

  if (!foundPinned)
    updatedLines.push(`pinned: ${pinned}`)

  const body = markdown.slice(frontmatterBlock.length).replace(/^\r?\n/, '')
  return `---\n${updatedLines.join('\n')}\n---\n\n${body}`
}


export function markdownToPreviewText(markdown: string): string {
  return markdown
    // remove fenced code blocks
    .replace(/```[\s\S]*?```/g, ' ')
    // remove inline code
    .replace(/`([^`]+)`/g, '$1')
    // images: ![alt](url) -> alt
    .replace(/!\[([^\]]*)\]\([^)]+\)/g, '$1')
    // links: [text](url) -> text
    .replace(/\[([^\]]+)\]\([^)]+\)/g, '$1')
    // headings / blockquotes
    .replace(/^\s{0,3}(#{1,6}|>)+\s?/gm, '')
    // bold / italic / strikethrough
    .replace(/(\*\*|__|\*|_|~~)/g, '')
    // task list markers / bullets
    .replace(/^\s*[-*+]\s+\[.\]\s+/gm, '')
    .replace(/^\s*[-*+]\s+/gm, '')
    .replace(/^\s*\d+\.\s+/gm, '')
    // horizontal rules
    .replace(/^\s*---+\s*$/gm, ' ')
    // collapse whitespace
    .replace(/\s+/g, ' ')
    .trim()
}

function getFileTitle(fileName: string, markdownBody: string): string {
  const h1Match = markdownBody.match(/^#\s+(.*)/)
  const heading = h1Match?.[1]?.trim()

  if (heading)
    return heading

  return fileName
    .replace(/\.md$/i, '')
    .replace(/^\d{4}-\d{2}-\d{2}-/, '')
    .replace(/[-_]/g, ' ')
    .replace(/\b\w/g, char => char.toUpperCase())
}

function sortAppFiles(items: AppFile[]): AppFile[] {
  return [...items].sort((a, b) => {
    if (a.pinned && !b.pinned)
      return -1
    if (!a.pinned && b.pinned)
      return 1
    return a.name.localeCompare(b.name)
  })
}

function sortTodoFiles(items: StoredTodoList[]): StoredTodoList[] {
  return [...items].sort((a, b) => {
    if (a.pinned && !b.pinned)
      return -1
    if (!a.pinned && b.pinned)
      return 1
    return a.fileName.localeCompare(b.fileName)
  })
}

function getDefaultDisplayName(type: 'list' | 'task' | 'note', date: string): string {
  const label = type === 'task' ? 'Task' : type === 'list' ? 'List' : 'Note'
  return `${label} ${date}`
}

function getDefaultFileStem(type: 'list' | 'task' | 'note', date: string): string {
  return `${type} ${date}`
}

async function getUniqueMarkdownFileName(folderUri: string, stem: string): Promise<string> {
  const result = await FolderPicker.listFiles({ folderUri })
  const existingFileNames = new Set(
    result.files
      .filter(file => file.isFile)
      .map(file => file.name.toLowerCase()),
  )

  let candidate = `${stem}.md`
  let counter = 1

  while (existingFileNames.has(candidate.toLowerCase())) {
    candidate = `${stem}-${counter}.md`
    counter += 1
  }

  return candidate
}

export async function loadDashboardData(folderUri: string): Promise<DashboardData> {
  const result = await FolderPicker.listFiles({ folderUri })
  const files: AppFile[] = []
  const lists: StoredTodoList[] = []
  const tasks: StoredTodoList[] = []

  for (const file of result.files) {
    if (!file.isFile || !file.name.toLowerCase().endsWith('.md'))
      continue

    try {
      const fileContent = await FolderPicker.readFile({
        folderUri,
        fileName: file.name,
      })

      const frontmatter = parseFrontmatter(fileContent.content)
      const body = stripFrontmatter(fileContent.content)
      const type = ['list', 'task', 'note'].includes(frontmatter.type as any)
        ? (frontmatter.type as AppFile['type'])
        : 'note'

      const plainTextBody = markdownToPreviewText(body)
      const preview = plainTextBody.substring(0, 80) + (plainTextBody.length > 80 ? '…' : '')

      files.push({
        id: file.name,
        name: file.name,
        title: getFileTitle(file.name, body),
        type,
        pinned: coerceBoolean(frontmatter.pinned),
        created: frontmatter.created as string,
        updated: frontmatter.updated as string,
        preview,
      })

      if (type === 'list' || type === 'task') {
        const list = markdownToList(fileContent.content, file.name)
        const storedList = {
          ...list,
          fileName: file.name,
        }

        if (type === 'task')
          tasks.push(storedList)
        else
          lists.push(storedList)
      }
    }
    catch (err) {
      console.warn('Failed to read or parse file', file.name, err)
    }
  }

  return {
    files: sortAppFiles(files),
    lists: sortTodoFiles(lists),
    tasks: sortTodoFiles(tasks),
  }
}

export async function loadAllFiles(folderUri: string): Promise<AppFile[]> {
  const dashboard = await loadDashboardData(folderUri)
  return dashboard.files
}

export async function setFilePinned(
  folderUri: string,
  fileName: string,
  pinned: boolean,
): Promise<void> {
  const read = await FolderPicker.readFile({
    folderUri,
    fileName,
  })

  await FolderPicker.writeFile({
    folderUri,
    fileName,
    content: setPinnedInMarkdown(read.content, pinned),
  })
}

export async function loadListsFromFolder(folderUri: string): Promise<StoredTodoList[]> {
  return loadTodoFilesFromFolder(folderUri, 'list')
}

export async function loadTodoFilesFromFolder(
  folderUri: string,
  type: TodoFileType,
): Promise<StoredTodoList[]> {
  const result = await FolderPicker.listFiles({ folderUri })
  const lists: StoredTodoList[] = []

  for (const file of result.files) {
    if (!file.isFile)
      continue

    if (!file.name.toLowerCase().endsWith('.md'))
      continue

    try {
      const read = await FolderPicker.readFile({
        folderUri,
        fileName: file.name,
      })

      const frontmatter = parseFrontmatter(read.content)
      if (frontmatter.type !== type)
        continue

      const list = markdownToList(read.content, file.name)

      lists.push({
        ...list,
        fileName: file.name,
      })
    }
    catch (err) {
      console.warn('Failed to read list file', file.name, err)
    }
  }

  return lists
}

export async function saveListToFile(
  folderUri: string,
  list: StoredTodoList,
): Promise<StoredTodoList> {
  const updated = formatDate()

  await FolderPicker.writeFile({
    folderUri,
    fileName: list.fileName,
    content: listToMarkdown({
      ...list,
      updated,
    }),
  })

  return {
    ...list,
    updated,
  }
}

export async function createListFile(
  folderUri: string,
  list: TodoList,
): Promise<StoredTodoList> {
  const created = list.created || formatDate()
  const todoType = list.type === 'task' ? 'task' : 'list'
  const defaultTitle = getDefaultDisplayName(todoType, created)
  const base: TodoList = {
    ...list,
    title: list.title?.trim() || defaultTitle,
    created,
    updated: created,
    type: todoType,
  }

  const fileName = await getUniqueMarkdownFileName(folderUri, getDefaultFileStem(todoType, created))

  await FolderPicker.writeFile({
    folderUri,
    fileName,
    content: listToMarkdown(base),
  })

  return {
    ...base,
    fileName,
  }
}

export async function deleteListFile(
  folderUri: string,
  list: StoredTodoList,
): Promise<void> {
  await FolderPicker.deleteFile({
    folderUri,
    fileName: list.fileName,
  })
}

export async function createNoteFile(
  folderUri: string,
  note: Note,
): Promise<StoredNote> {
  const created = note.created || formatDate()
  const base: Note = {
    ...note,
    created,
    updated: created,
    type: 'note',
  }

  const fileName = await getUniqueMarkdownFileName(folderUri, getDefaultFileStem('note', created))

  await FolderPicker.writeFile({
    folderUri,
    fileName,
    content: noteToMarkdown(base),
  })

  return {
    ...base,
    fileName,
  }
}

export async function saveNoteToFile(
  folderUri: string,
  note: StoredNote,
): Promise<StoredNote> {
  const updated = formatDate()

  // Preserve the existing file's type so editing a task/list file in NoteView
  // doesn't accidentally reclassify it as a note.
  let preservedType = 'note'
  try {
    const existing = await FolderPicker.readFile({ folderUri, fileName: note.fileName })
    const existingFm = parseFrontmatter(existing.content)
    if (existingFm.type)
      preservedType = existingFm.type as string
  }
  catch {
    // New file — default to note
  }

  const created = note.created || formatDate()
  const frontmatter = buildFrontmatter({
    type: preservedType,
    id: note.id,
    created,
    updated,
    pinned: !!note.pinned,
  })

  await FolderPicker.writeFile({
    folderUri,
    fileName: note.fileName,
    content: `${frontmatter}\n\n${note.content}`,
  })

  return {
    ...note,
    updated,
  }
}