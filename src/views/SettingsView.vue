<template>
  <div class="page">
    <div class="header">
      <button class="back-button" @click="goBack" aria-label="Go back">←</button>

      <div>
        <h1>Settings</h1>
        <p class="subtitle">Choose folder and filenames, plus quick task presets</p>
      </div>
    </div>

    <!-- system folder card -->
    <div class="card">
      <div class="field">
        <span>System folder</span>
        <button class="folder-button" type="button" @click="pickBaseFolder">
          Select folder
        </button>
        <p class="hint" v-if="baseFolderName">{{ baseFolderName }}</p>
        <p class="hint" v-else>No folder selected</p>
      </div>
    </div>

    <!-- appearance card -->
    <div class="card">
      <h2 class="card-title">Appearance</h2>
      <div class="field row" style="align-items: center;">
        <button @click="toggleTheme" class="theme-toggle-button" aria-label="Toggle theme">
          <Moon v-if="theme === 'light'" :size="20" />
          <Sun v-if="theme === 'dark'" :size="20" />
        </button>
        <span class="toggle-label">{{ theme === 'dark' ? 'Dark' : 'Light' }}</span>
      </div>
    </div>

    <!-- lists card -->
    <div class="card">
      <h2 class="card-title">Lists file</h2>
      <div class="field row">
        <label class="radio-option">
          <input
            :checked="listSaveMode === 'single_file'"
            type="radio"
            value="single_file"
            @change="listSaveMode = 'single_file'"
          />
          <span>Custom filename</span>
        </label>
        <input
          class="flex-grow"
          v-model="listFileName"
          type="text"
          placeholder="tasks.md"
          :disabled="listSaveMode === 'daily_note'"
        />
      </div>
      <div class="field row">
        <label class="radio-option">
          <input
            :checked="listSaveMode === 'daily_note'"
            type="radio"
            value="daily_note"
            @change="listSaveMode = 'daily_note'"
          />
          <span>Today's date</span>
        </label>
      </div>
    </div>

    <!-- notes card -->
    <div class="card">
      <h2 class="card-title">Notes file</h2>
      <div class="field row">
        <label class="radio-option">
          <input
            :checked="noteSaveMode === 'single_file'"
            type="radio"
            value="single_file"
            @change="noteSaveMode = 'single_file'"
          />
          <span>Custom filename</span>
        </label>
        <input
          class="flex-grow"
          v-model="noteFileName"
          type="text"
          placeholder="notes.md"
          :disabled="noteSaveMode === 'daily_note'"
        />
      </div>
      <div class="field row">
        <label class="radio-option">
          <input
            :checked="noteSaveMode === 'daily_note'"
            type="radio"
            value="daily_note"
            @change="noteSaveMode = 'daily_note'"
          />
          <span>Today's date</span>
        </label>
      </div>
    </div>

    <div
      v-for="(preset, index) in presets"
      :key="preset.id"
      class="card"
    >
      <div class="card-header">
        <h2>Task {{ index + 1 }}</h2>
        <button
          class="remove-button"
          @click="removePreset(preset.id)"
          :disabled="presets.length === 1"
        >
          Remove
        </button>
      </div>

      <label class="field">
        <span>Label</span>
        <input v-model="preset.label" type="text" />
      </label>

      <label class="field">
        <span>Tag</span>
        <input v-model="preset.tag" type="text" />
      </label>

      <div class="field">
        <span>Save mode</span>
        <label class="radio-option">
          <input
            :checked="preset.saveMode === 'single_file'"
            type="radio"
            value="single_file"
            @change="preset.saveMode = 'single_file'"
          />
          <span>Custom filename</span>
        </label>
        <label class="radio-option">
          <input
            :checked="preset.saveMode === 'daily_note'"
            type="radio"
            value="daily_note"
            @change="preset.saveMode = 'daily_note'"
          />
          <span>Today’s date</span>
        </label>
      </div>

      <label class="field">
        <span>Filename</span>
        <input
          v-model="preset.fileName"
          type="text"
          placeholder="tasks.md"
          :disabled="preset.saveMode === 'daily_note'"
        />
      </label>

    </div>

    <button class="add-button" @click="addPreset">+ Add Task</button>

    <div class="actions">
      <button class="secondary-button" @click="handleReset">Reset</button>
      <button class="primary-button" @click="handleSave">Save</button>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { Moon, Sun } from 'lucide-vue-next'
import {
  loadSettings,
  saveSettings,
  resetSettings,
  applyTheme,
  type QuickTaskPreset,
  type SaveMode,
  type Theme,
} from '../lib/settings'
import { FolderPicker } from '../plugins/folder-picker'

const router = useRouter()

const settings = loadSettings()

const baseFolderUri = ref<string | undefined>(settings.baseFolderUri)
const baseFolderName = ref<string | undefined>(settings.baseFolderName)
const theme = ref<Theme>(settings.theme)

// apply immediately and whenever the theme value changes
applyTheme(theme.value)
watch(theme, (t) => {
  console.log('theme changed to', t)
  applyTheme(t)
})
const listSaveMode = ref<SaveMode>(settings.listSaveMode)
const listFileName = ref<string | undefined>(settings.listFileName)
const noteSaveMode = ref<SaveMode>(settings.noteSaveMode)
const noteFileName = ref<string | undefined>(settings.noteFileName)

const presets = ref<QuickTaskPreset[]>(
  settings.quickTaskPresets.map((preset) => ({ ...preset })),
)

function goBack() {
  router.back()
}

// toggleTheme now mutates the reactive 'theme' ref from toRefs
function toggleTheme() {
  theme.value = theme.value === 'light' ? 'dark' : 'light'
}

async function pickBaseFolder() {
  try {
    const result = await FolderPicker.pickFolder()
    baseFolderUri.value = result.uri;
    baseFolderName.value = result.name
  } catch (error) {
    console.error(error)
  }
}

function addPreset() {
  presets.value.push({
    id: crypto.randomUUID(),
    label: '',
    tag: '',
    saveMode: listSaveMode.value,
    fileName: listFileName.value || 'tasks.md',
  })
}

function removePreset(id: string) {
  if (presets.value.length === 1) return
  presets.value = presets.value.filter((preset) => preset.id !== id)
}

function handleSave() {
  const current = loadSettings()

  const cleaned = presets.value
    .map((preset) => {
      const mode: SaveMode =
        preset.saveMode === 'daily_note' ? 'daily_note' : 'single_file'

      return {
        ...preset,
        label: preset.label.trim(),
        tag: preset.tag.trim(),
        saveMode: mode,
        fileName: preset.fileName?.trim() || undefined,
      }
    })
    .filter((preset) => preset.label.length > 0)

  saveSettings({
    baseFolderUri: baseFolderUri.value,
    baseFolderName: baseFolderName.value,
    theme: theme.value,

    listSaveMode: listSaveMode.value,
    listFileName:
      listSaveMode.value === 'daily_note'
        ? current.listFileName
        : listFileName.value?.trim() || current.listFileName,

    noteSaveMode: noteSaveMode.value,
    noteFileName:
      noteSaveMode.value === 'daily_note'
        ? current.noteFileName
        : noteFileName.value?.trim() || current.noteFileName,

    quickTaskPresets: cleaned.length > 0 ? cleaned : current.quickTaskPresets,
  })

  router.push('/')
}

function handleReset() {
  const reset = resetSettings()
  baseFolderUri.value = reset.baseFolderUri
  baseFolderName.value = reset.baseFolderName
  theme.value = reset.theme
  listSaveMode.value = reset.listSaveMode
  listFileName.value = reset.listFileName
  noteSaveMode.value = reset.noteSaveMode
  noteFileName.value = reset.noteFileName
  presets.value = reset.quickTaskPresets.map((preset) => ({ ...preset }))
}
</script>

<style scoped>
.page {
  min-height: 100vh;
  padding: 24px 20px 40px;
  background: var(--bg);
  color: var(--text);
} 

.header {
  display: flex;
  align-items: flex-start;
  gap: 12px;
  margin-bottom: 24px;
}

.row {
  display: flex;
  align-items: center;
  gap: 8px;
}

.flex-grow {
  flex: 1;
}

.card-title {
  margin-bottom: 8px;
  font-size: 1rem;
  font-weight: 600;
}

.back-button {
  border: none;
  background: var(--surface-strong);
  border-radius: 999px;
  width: 42px;
  height: 42px;
  font-size: 1.2rem;
  box-shadow: var(--shadow);
  flex-shrink: 0;
} 

h1 {
  margin: 0;
  font-size: 1.8rem;
}

.subtitle {
  margin: 6px 0 0;
  color: var(--text-soft);
} 

.card {
  background: var(--surface);
  border-radius: 20px;
  padding: 20px;
  box-shadow: var(--shadow);
  margin-bottom: 16px;
} 

.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16px;
}

.card-header h2 {
  margin: 0;
  font-size: 1.05rem;
}

.remove-button {
  border: none;
  background: var(--danger-soft);
  color: var(--danger);
  border-radius: 12px;
  padding: 8px 12px;
  font-weight: 600;
} 

.field {
  display: flex;
  flex-direction: column;
  gap: 8px;
  margin-bottom: 14px;
}

.field span {
  font-size: 0.95rem;
  font-weight: 600;
}

.field input {
  border: 1px solid var(--border);
  border-radius: 14px;
  padding: 12px 14px;
  background: var(--surface-strong);
  font-size: 1rem;
  color: var(--text);
}

.radio-option {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 0.98rem;
  color: var(--text);
} 

.radio-option input {
  margin: 0;
}

.theme-button {
  padding: 6px 14px;
  border-radius: 999px;
  background: var(--surface);
  color: var(--text);
  font-weight: 600;
  border: 1px solid transparent;
  transition: background 0.2s ease, border-color 0.2s ease;
}

.theme-button.active {
  background: var(--primary);
  color: white;
  border-color: var(--primary);
}

.theme-toggle-button {
  background: transparent;
  border: none;
  color: var(--text);
  cursor: pointer;
  padding: 4px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
}
.theme-toggle-button:hover {
  background: var(--surface-strong);
}

.toggle-label {
  margin-left: 10px;
  font-weight: 600;
}

.hint {
  margin: 2px 0 0;
  font-size: 0.9rem;
  color: var(--text-soft);
}

.folder-button {
  border: none;
  border-radius: 14px;
  padding: 12px 14px;
  background: var(--text);
  color: var(--bg);
  font-size: 1rem;
  font-weight: 600;
} 

.add-button {
  width: 100%;
  border: none;
  border-radius: 16px;
  padding: 14px 18px;
  background: var(--surface);
  color: var(--text);
  font-size: 1rem;
  font-weight: 600;
  box-shadow: var(--shadow);
  margin-top: 4px;
} 

.actions {
  display: flex;
  gap: 12px;
  margin-top: 20px;
}

.primary-button,
.secondary-button {
  border: none;
  border-radius: 16px;
  padding: 14px 18px;
  font-size: 1rem;
  font-weight: 600;
  flex: 1;
}

.primary-button {
  background: var(--text);
  color: var(--bg);
} 

.secondary-button {
  background: var(--surface);
  color: var(--text);
  box-shadow: var(--shadow);
} 
</style>