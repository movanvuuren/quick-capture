export type SaveMode = 'single_file' | 'daily_note'

export interface QuickTaskPreset {
  id: string
  label: string
  tag: string
  saveMode?: SaveMode
  fileName?: string
}

export type Theme = 'light' | 'dark' | 'dim'

export interface AppSettings {
  /** folder picked by the user for storing both lists and notes */
  baseFolderUri?: string
  baseFolderName?: string

  /** light or dark appearance */
  theme: Theme

  /** optional user override for accent/action color */
  accentColor?: string

  /** settings for the file that tasks (lists) are appended to */
  listSaveMode: SaveMode
  listFileName?: string

  /** settings for the file that notes will be saved to in future */
  noteSaveMode: SaveMode
  noteFileName?: string

  quickTaskPresets: QuickTaskPreset[]

  /** show extra task metadata for troubleshooting */
  debugMode?: boolean
}

const SETTINGS_KEY = 'quick-capture-settings'

export const defaultSettings: AppSettings = {
  baseFolderUri: undefined,
  baseFolderName: undefined,
  // start in dark mode by default so it’s obvious the class toggles correctly
  theme: 'dark',
  accentColor: undefined,
  listSaveMode: 'single_file',
  listFileName: 'tasks.md',
  noteSaveMode: 'single_file',
  noteFileName: 'notes.md',
  quickTaskPresets: [
    {
      id: crypto.randomUUID(),
      label: '🪅 Home',
      tag: '#🪅',
      saveMode: 'single_file',
      fileName: 'tasks.md',
    },
    {
      id: crypto.randomUUID(),
      label: '💼 Work',
      tag: '#💼',
      saveMode: 'single_file',
      fileName: 'tasks.md',
    },
  ],
  debugMode: false,
}

export function getThemeAccentColor(theme: Theme): string {
  if (theme === 'light')
    return '#2563ff'
  if (theme === 'dark')
    return '#ce43b7'
  return '#ff53b3'
}

function normalizeAccentColor(value: unknown): string | undefined {
  if (typeof value !== 'string')
    return undefined

  const trimmed = value.trim()
  if (!trimmed)
    return undefined

  if (/^#([0-9a-fA-F]{6})$/.test(trimmed))
    return trimmed.toLowerCase()

  return undefined
}

function normalizePreset(value: Partial<QuickTaskPreset> | undefined): QuickTaskPreset | null {
  if (!value?.label?.trim())
    return null

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
    if (!raw)
      return defaultSettings

    const parsed = JSON.parse(raw) as Partial<AppSettings>

    const presets
      = parsed.quickTaskPresets
        ?.map(preset => normalizePreset(preset))
        .filter((preset): preset is QuickTaskPreset => preset !== null) ?? []

    return {
      baseFolderUri: parsed.baseFolderUri?.trim() || undefined,
      baseFolderName: parsed.baseFolderName?.trim() || undefined,
      theme: ['light', 'dark', 'dim'].includes(parsed.theme as any)
        ? (parsed.theme as Theme)
        : defaultSettings.theme,
      accentColor: normalizeAccentColor(parsed.accentColor),
      listSaveMode: parsed.listSaveMode === 'daily_note' ? 'daily_note' : 'single_file',
      listFileName: parsed.listFileName?.trim() || defaultSettings.listFileName,
      noteSaveMode: parsed.noteSaveMode === 'daily_note' ? 'daily_note' : 'single_file',
      noteFileName: parsed.noteFileName?.trim() || defaultSettings.noteFileName,
      quickTaskPresets: presets.length > 0 ? presets : defaultSettings.quickTaskPresets,
      debugMode: parsed.debugMode === true,
    }
  }
  catch {
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
export function applyTheme(theme: Theme, accentColor?: string): void {
  document.documentElement.dataset.theme = theme

  if (accentColor && /^#([0-9a-fA-F]{6})$/.test(accentColor.trim()))
    document.documentElement.style.setProperty('--c-action', accentColor)
  else
    document.documentElement.style.removeProperty('--c-action')
}

export function resetSettings(): AppSettings {
  const reset: AppSettings = {
    baseFolderUri: defaultSettings.baseFolderUri,
    baseFolderName: defaultSettings.baseFolderName,
    theme: defaultSettings.theme,
    accentColor: defaultSettings.accentColor,
    listSaveMode: defaultSettings.listSaveMode,
    listFileName: defaultSettings.listFileName,
    noteSaveMode: defaultSettings.noteSaveMode,
    noteFileName: defaultSettings.noteFileName,
    debugMode: defaultSettings.debugMode,
    quickTaskPresets: defaultSettings.quickTaskPresets.map(preset => ({
      ...preset,
      id: crypto.randomUUID(),
    })),
  }

  saveSettings(reset)
  return reset
}
