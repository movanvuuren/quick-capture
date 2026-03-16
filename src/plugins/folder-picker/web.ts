import type {
  AppendToFileOptions,
  DeleteFileOptions,
  FolderFileEntry,
  FolderPickerPlugin,
  ListFilesOptions,
  ListFilesResult,
  PickFolderResult,
  ReadFileOptions,
  ReadFileResult,
  WriteFileOptions,
} from './definitions'

// A virtual, in-memory file system to simulate a real one.
const virtualFileSystem = new Map<string, string>([
  [
    'shopping-list.md',
    `---
type: list
id: "1"
title: "Shopping List"
created: "2024-01-01"
updated: "2024-01-01"
pinned: false
---

- [ ] Milk
- [ ] Bread
- [ ] VS Code extension
- [x] Cheese
`,
  ],
  [
    'project-alpha.md',
    `---
type: list
id: "2"
title: "Project Alpha"
created: "2024-02-01"
updated: "2024-02-01"
pinned: "true"
---

- [ ] Design mockups
- [ ] Develop feature X
- [ ] Write documentation
`,
  ],
  [
    'meeting-notes.md',
    `---
type: note
id: "3"
created: "2024-03-01"
updated: "2024-03-01"
pinned: false
---

# Meeting Notes

## Agenda
- Discuss Q1 performance
- Plan for Q2

## Action Items
- [ ] @Alice to send out the report.
- [ ] @Bob to schedule the follow-up.
`,
  ],
])

const virtualMetadata = new Map<string, { size: number, lastModified: number }>()

function upsertMetadata(fileName: string, content: string) {
  virtualMetadata.set(fileName, {
    size: content.length,
    lastModified: Date.now(),
  })
}

for (const [name, content] of virtualFileSystem.entries())
  upsertMetadata(name, content)

export class FolderPickerWeb implements FolderPickerPlugin {
  async pickFolder(): Promise<PickFolderResult> {
    console.log('[Web Mock] Picking folder')
    return {
      uri: 'content://virtual-folder',
      name: 'Virtual Mock Folder',
    }
  }

  async listFiles(options: ListFilesOptions): Promise<ListFilesResult> {
    console.log('[Web Mock] Listing files in', options.folderUri)

    const files: FolderFileEntry[] = []

    for (const name of virtualFileSystem.keys()) {
      const meta = virtualMetadata.get(name)
      files.push({
        name,
        isFile: true,
        size: meta?.size,
        lastModified: meta?.lastModified,
      })
    }

    return { files }
  }

  async readFile(options: ReadFileOptions): Promise<ReadFileResult> {
    console.log(
      '[Web Mock] Reading file',
      options.fileName,
      'from',
      options.folderUri,
    )

    const content = virtualFileSystem.get(options.fileName)
    if (content !== undefined) {
      return { content }
    }

    throw new Error('File not found in virtual file system')
  }

  async writeFile(options: WriteFileOptions): Promise<void> {
    console.log(
      '[Web Mock] Writing file',
      options.fileName,
      'to',
      options.folderUri,
    )

    virtualFileSystem.set(options.fileName, options.content)
    upsertMetadata(options.fileName, options.content)
  }

  async appendToFile(options: AppendToFileOptions): Promise<void> {
    console.log(
      '[Web Mock] Appending file',
      options.fileName,
      'to',
      options.folderUri,
    )

    const existing = virtualFileSystem.get(options.fileName) ?? ''
    const nextContent = existing + options.content
    virtualFileSystem.set(options.fileName, nextContent)
    upsertMetadata(options.fileName, nextContent)
  }

  async deleteFile(options: DeleteFileOptions): Promise<void> {
    console.log(
      '[Web Mock] Deleting file',
      options.fileName,
      'from',
      options.folderUri,
    )

    if (virtualFileSystem.has(options.fileName)) {
      virtualFileSystem.delete(options.fileName)
      virtualMetadata.delete(options.fileName)
      return
    }

    throw new Error('File not found in virtual file system')
  }
}