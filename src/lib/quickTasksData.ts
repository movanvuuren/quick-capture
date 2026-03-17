import { FolderPicker } from '../plugins/folder-picker'
import { getFileSignature, mapWithConcurrency } from './asyncUtils'
import type { TodoState } from './lists'
import type { TaskRepeat } from './taskLine'
import { parseTaskLine, serializeTaskLine } from './taskLine'
import { resolveTaskLineIndex, scanQuickTaskLines } from './quickTaskFileOps'

export interface QuickTaskPresetMatch {
  id: string
  label?: string
}

export interface QuickTaskRecord {
  id: string
  fileName: string
  lineIndex: number
  state: TodoState
  body: string
  dueDate?: string
  isHighPriority?: boolean
  repeat?: TaskRepeat
  presetId?: string
  presetLabel?: string
}

interface CachedQuickTaskFile {
  signature: string
  tasks: ReturnType<typeof scanQuickTaskLines>
}

let quickTaskCacheFolderUri = ''
const quickTaskFileCache = new Map<string, CachedQuickTaskFile>()

export function invalidateQuickTaskCache(fileName?: string) {
  if (fileName) {
    quickTaskFileCache.delete(fileName)
    return
  }

  quickTaskFileCache.clear()
}

export async function loadQuickTasksFromFolder(options: {
  folderUri: string
  readConcurrency: number
  matchPreset: (body: string, fileName: string) => QuickTaskPresetMatch | undefined
}): Promise<QuickTaskRecord[]> {
  const { folderUri, readConcurrency, matchPreset } = options

  if (quickTaskCacheFolderUri !== folderUri) {
    invalidateQuickTaskCache()
    quickTaskCacheFolderUri = folderUri
  }

  const listed = await FolderPicker.listFiles({ folderUri })
  const mdFiles = listed.files.filter(file => file.isFile && file.name.toLowerCase().endsWith('.md'))
  const activeNames = new Set(mdFiles.map(file => file.name))

  const scannedFiles = await mapWithConcurrency(
    mdFiles,
    readConcurrency,
    async (file) => {
      try {
        const signature = getFileSignature(file)
        const cached = quickTaskFileCache.get(file.name)

        if (signature && cached && cached.signature === signature) {
          return {
            fileName: file.name,
            tasks: cached.tasks.map(task => ({ ...task })),
          }
        }

        const read = await FolderPicker.readFile({
          folderUri,
          fileName: file.name,
        })

        const scanned = scanQuickTaskLines(read.content)

        if (signature) {
          quickTaskFileCache.set(file.name, {
            signature,
            tasks: scanned,
          })
        }
        else {
          quickTaskFileCache.delete(file.name)
        }

        return {
          fileName: file.name,
          tasks: scanned.map(task => ({ ...task })),
        }
      }
      catch (fileErr) {
        console.warn('Failed to scan task file', file.name, fileErr)
        return null
      }
    },
  )

  const loadedTasks: QuickTaskRecord[] = []

  for (const scannedFile of scannedFiles) {
    if (!scannedFile)
      continue

    for (const task of scannedFile.tasks) {
      const preset = matchPreset(task.body, scannedFile.fileName)
      if (!preset)
        continue

      loadedTasks.push({
        id: `${scannedFile.fileName}:${task.lineIndex}`,
        fileName: scannedFile.fileName,
        lineIndex: task.lineIndex,
        state: task.state,
        body: task.body,
        dueDate: task.dueDate,
        isHighPriority: task.isHighPriority,
        repeat: task.repeat,
        presetId: preset.id,
        presetLabel: preset.label,
      })
    }
  }

  for (const cachedName of Array.from(quickTaskFileCache.keys())) {
    if (!activeNames.has(cachedName))
      quickTaskFileCache.delete(cachedName)
  }

  return loadedTasks
}

export async function updateTaskInFolder(options: {
  folderUri: string
  task: QuickTaskRecord
  nextState: TodoState
  nextDueDate?: string
  nextBody?: string
  nextIsHighPriority?: boolean
}): Promise<boolean> {
  const { folderUri, task, nextState, nextDueDate, nextBody, nextIsHighPriority } = options

  const read = await FolderPicker.readFile({
    folderUri,
    fileName: task.fileName,
  })

  const lines = read.content.split(/\r?\n/)
  let lineIndex = -1
  if (task.lineIndex >= 0 && task.lineIndex < lines.length) {
    const parsedAtStoredIndex = parseTaskLine(lines[task.lineIndex] || '')
    if (parsedAtStoredIndex)
      lineIndex = task.lineIndex
  }

  if (lineIndex < 0)
    lineIndex = resolveTaskLineIndex(lines, task)

  if (lineIndex < 0)
    return false

  lines[lineIndex] = serializeTaskLine(
    nextState,
    nextBody ?? task.body,
    nextDueDate,
    nextIsHighPriority ?? task.isHighPriority,
  )

  await FolderPicker.writeFile({
    folderUri,
    fileName: task.fileName,
    content: lines.join('\n'),
  })

  invalidateQuickTaskCache(task.fileName)
  return true
}
