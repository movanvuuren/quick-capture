export type SaveMode = 'single_file' | 'daily_note'

export type QuickTaskPreset = {
  id: string
  label: string
  tag: string
  saveMode?: SaveMode
  fileName?: string
}

export type Theme = 'light' | 'dark' | 'dim'

export type AppSettings = {
  /** folder picked by the user for storing both lists and notes */
  baseFolderUri?: string
  baseFolderName?: string

  /** light or dark appearance */
  theme: Theme

  /** settings for the file that tasks (lists) are appended to */
  listSaveMode: SaveMode
  listFileName?: string

  /** settings for the file that notes will be saved to in future */
  noteSaveMode: SaveMode
  noteFileName?: string

  quickTaskPresets: QuickTaskPreset[]
}

const SETTINGS_KEY = 'quick-capture-settings'

export const defaultSettings: AppSettings = {
  baseFolderUri: undefined,
  baseFolderName: undefined,
  // start in dark mode by default so it’s obvious the class toggles correctly
  theme: 'dark',
  listSaveMode: 'single_file',
  listFileName: 'tasks.md',
  noteSaveMode: 'single_file',
  noteFileName: 'notes.md',
  quickTaskPresets: [
    {
      id: crypto.randomUUID(),
      label: '💼 Work',
      tag: '#💼',
      saveMode: 'single_file',
      fileName: 'tasks.md',
    },
    {
      id: crypto.randomUUID(),
      label: '🪅 Home',
      tag: '#🪅',
      saveMode: 'single_file',
      fileName: 'tasks.md',
    },
  ],
}

function normalizePreset(value: Partial<QuickTaskPreset> | undefined): QuickTaskPreset | null {
  if (!value?.label?.trim()) return null

  return {
    id: value.id ?? crypto.randomUUID(),
    label: value.label.trim(),
    tag: value.tag?.trim() ?? '',
    saveMode: value.saveMode === 'daily_note' ? 'daily_note' : 'single_file',
    fileName: value.fileName?.trim() || undefined,
  }
}

export function loadSettings(): AppSettings {
  try {
    const raw = localStorage.getItem(SETTINGS_KEY)
    if (!raw) return defaultSettings

    const parsed = JSON.parse(raw) as Partial<AppSettings>

    const presets =
      parsed.quickTaskPresets
        ?.map((preset) => normalizePreset(preset))
        .filter((preset): preset is QuickTaskPreset => preset !== null) ?? []

    return {
      baseFolderUri: parsed.baseFolderUri?.trim() || undefined,
      baseFolderName: parsed.baseFolderName?.trim() || undefined,
      theme: parsed.theme === 'dark' ? 'dark' : 'light',
      listSaveMode: parsed.listSaveMode === 'daily_note' ? 'daily_note' : 'single_file',
      listFileName: parsed.listFileName?.trim() || defaultSettings.listFileName,
      noteSaveMode: parsed.noteSaveMode === 'daily_note' ? 'daily_note' : 'single_file',
      noteFileName: parsed.noteFileName?.trim() || defaultSettings.noteFileName,
      quickTaskPresets: presets.length > 0 ? presets : defaultSettings.quickTaskPresets,
    }
  } catch {
    return defaultSettings
  }
}

export function saveSettings(settings: AppSettings): void {
  localStorage.setItem(SETTINGS_KEY, JSON.stringify(settings))
}

/**
 * Update document class to reflect chosen theme. Should be called at startup
 * and whenever the user changes the preference.
 */
export function applyTheme(theme: Theme): void {
  document.documentElement.dataset.theme = theme
}

export function resetSettings(): AppSettings {
  const reset: AppSettings = {
    baseFolderUri: defaultSettings.baseFolderUri,
    baseFolderName: defaultSettings.baseFolderName,
    theme: defaultSettings.theme,
    listSaveMode: defaultSettings.listSaveMode,
    listFileName: defaultSettings.listFileName,
    noteSaveMode: defaultSettings.noteSaveMode,
    noteFileName: defaultSettings.noteFileName,
    quickTaskPresets: defaultSettings.quickTaskPresets.map((preset) => ({
      ...preset,
      id: crypto.randomUUID(),
    })),
  }

  saveSettings(reset)
  return reset
}