import type { TodoList, StoredTodoList, Note, StoredNote } from './lists'
import { FolderPicker } from '../plugins/folder-picker'
import {
  coerceBoolean,
  formatDate,
  getListFileName,
  listToMarkdown,
  markdownToList,
  noteToMarkdown,
  parseFrontmatter,
  slugify,
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

export async function loadAllFiles(folderUri: string): Promise<AppFile[]> {
  const result = await FolderPicker.listFiles({ folderUri })
  const allFiles: AppFile[] = []

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

      // Logic to find title, adapted from markdownToList
      let title = ''
      const h1Match = body.match(/^#\s+(.*)/)
      const heading = h1Match?.[1]?.trim()

      if (heading) {
        title = heading
      }
      else {
        title = file.name
          .replace(/\.md$/i, '')
          .replace(/^\d{4}-\d{2}-\d{2}-/, '')
          .replace(/[-_]/g, ' ')
          .replace(/\b\w/g, char => char.toUpperCase())
      }

      // Determine file type, default to 'note' if not specified
      const type = ['list', 'task', 'note'].includes(frontmatter.type as any)
        ? (frontmatter.type as AppFile['type'])
        : 'note' // Defaulting to note

      const plainTextBody = markdownToPreviewText(body)
      const preview = plainTextBody.substring(0, 80) +
        (plainTextBody.length > 80 ? '…' : '')

      allFiles.push({
        id: file.name, // Always use the filename as the unique ID for navigation
        name: file.name,
        title: title || file.name, // Fallback title
        type,
        pinned: coerceBoolean(frontmatter.pinned),
        created: frontmatter.created as string,
        updated: frontmatter.updated as string,
        preview,
      })
    }
    catch (err) {
      console.warn('Failed to read or parse file', file.name, err)
    }
  }

  // Sort files with pinned items first, then by name
  allFiles.sort((a, b) => {
    if (a.pinned && !b.pinned)
      return -1
    if (!a.pinned && b.pinned)
      return 1
    // Add more sorting here if needed, e.g., by updated date or name
    return a.name.localeCompare(b.name)
  })

  return allFiles
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
  const base: TodoList = {
    ...list,
    created,
    updated: created,
    type: list.type === 'task' ? 'task' : 'list',
  }

  const fileName = getListFileName(base)

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

function getNoteFileName(note: Note): string {
  const created = note.created || formatDate()
  const firstLine = note.content?.split('\n')[0]?.trim() || 'untitled note'
  const base = slugify(firstLine)
  return `${created}-${base}.md`
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

  const fileName = getNoteFileName(base)

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

  await FolderPicker.writeFile({
    folderUri,
    fileName: note.fileName,
    content: noteToMarkdown({
      ...note,
      updated,
    }),
  })

  return {
    ...note,
    updated,
  }
}