export interface PickFolderResult {
  uri: string
  name: string
}

export interface AppendToFileOptions {
  folderUri: string
  fileName: string
  content: string
}

export interface WriteFileOptions {
  folderUri: string
  fileName: string
  content: string
}

export interface ReadFileOptions {
  folderUri: string
  fileName: string
}

export interface ReadFileResult {
  content: string
}

export interface ListFilesOptions {
  folderUri: string
}

export interface FolderFileEntry {
  name: string
  isFile: boolean
  size?: number
  lastModified?: number
}

export interface ListFilesResult {
  files: FolderFileEntry[]
}

export interface DeleteFileOptions {
  folderUri: string
  fileName: string
}

export interface FolderPickerPlugin {
  pickFolder: () => Promise<PickFolderResult>

  appendToFile: (options: AppendToFileOptions) => Promise<void>

  writeFile: (options: WriteFileOptions) => Promise<void>

  readFile: (options: ReadFileOptions) => Promise<ReadFileResult>

  listFiles: (options: ListFilesOptions) => Promise<ListFilesResult>

  deleteFile: (options: DeleteFileOptions) => Promise<void>
}