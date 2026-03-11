export interface PickFolderResult {
  uri: string
  name: string
}

export interface AppendToFileOptions {
  /** The URI of the folder returned by `pickFolder` */
  folderUri: string
  /** Name of the file to append to (will be created if missing) */
  fileName: string
  /** Text content to append */
  content: string
}

export interface WriteFileOptions {
  /** The URI of the folder returned by `pickFolder` */
  folderUri: string
  /** Name of the file to write (will be created or truncated) */
  fileName: string
  /** Full file contents */
  content: string
}

export interface FolderPickerPlugin {
  pickFolder(): Promise<PickFolderResult>
  /**
   * Append the provided content to a file inside the previously selected folder.
   * The file will be created if it does not already exist.
   */
  appendToFile(options: AppendToFileOptions): Promise<void>
  /**
   * Write the provided content to a file, replacing any existing data.
   * Creates the file if it doesn't exist.
   */
  writeFile(options: WriteFileOptions): Promise<void>
}