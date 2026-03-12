<script setup lang="ts">
import type { QuickTaskPreset, SaveMode, Theme } from '../lib/settings'
import { Moon, Sparkles, Sun, Trash } from 'lucide-vue-next'
import { ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import {
  applyTheme,
  loadSettings,

  resetSettings,

  saveSettings,

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
  settings.quickTaskPresets.map(preset => ({ ...preset })),
)

function goBack() {
  router.back()
}

// toggleTheme now mutates the reactive 'theme' ref from toRefs
function setTheme(next: Theme) {
  theme.value = next
  applyTheme(next)
}

async function pickBaseFolder() {
  try {
    const result = await FolderPicker.pickFolder()
    baseFolderUri.value = result.uri
    baseFolderName.value = result.name
  }
  catch (error) {
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
  if (presets.value.length === 1)
    return
  presets.value = presets.value.filter(preset => preset.id !== id)
}

function handleSave() {
  const current = loadSettings()

  const cleaned = presets.value
    .map((preset) => {
      const mode: SaveMode
        = preset.saveMode === 'daily_note' ? 'daily_note' : 'single_file'

      return {
        ...preset,
        label: preset.label.trim(),
        tag: preset.tag.trim(),
        saveMode: mode,
        fileName: preset.fileName?.trim() || undefined,
      }
    })
    .filter(preset => preset.label.length > 0)

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
  presets.value = reset.quickTaskPresets.map(preset => ({ ...preset }))
}
</script>

<template>
  <div class="page">
    <div class="header">
      <button class="glass-icon-button back-button" aria-label="Go back" @click="goBack">
        ←
      </button>

      <div>
        <h1>Settings</h1>
        <p class="subtitle">
          Choose folder and filenames, plus quick task presets
        </p>
      </div>
    </div>

    <!-- system folder card -->
    <div class="card">
      <div class="field">
        <span>System folder</span>
        <button class="glass-button folder-button" type="button" @click="pickBaseFolder">
          Select folder
        </button>
        <p v-if="baseFolderName" class="hint">
          {{ baseFolderName }}
        </p>
        <p v-else class="hint">
          No folder selected
        </p>
      </div>
    </div>

    <!-- appearance card -->
    <div class="card">
      <h2 class="card-title">
        Appearance
      </h2>
      <div class="theme-switcher" :data-active="theme">
        <button
          class="theme-option"
          :class="{ 'is-active': theme === 'light' }"
          aria-label="Light theme"
          @click="setTheme('light')"
        >
          <Sun />
        </button>

        <button
          class="theme-option"
          :class="{ 'is-active': theme === 'dark' }"
          aria-label="Dark theme"
          @click="setTheme('dark')"
        >
          <Moon />
        </button>

        <button
          class="theme-option"
          :class="{ 'is-active': theme === 'dim' }"
          aria-label="Dim theme"
          @click="setTheme('dim')"
        >
          <Sparkles />
        </button>
      </div>
    </div>

    <!-- lists card -->
    <div class="card">
      <h2 class="card-title">
        Lists file
      </h2>
      <div class="field row">
        <div class="field">
          <div
            class="segmented-control"
            :data-active="listSaveMode === 'daily_note' ? 'right' : 'left'"
          >
            <button
              type="button"
              class="segmented-option"
              :class="{ 'is-active': listSaveMode === 'single_file' }"
              @click="listSaveMode = 'single_file'"
            >
              Custom filename
            </button>

            <button
              type="button"
              class="segmented-option"
              :class="{ 'is-active': listSaveMode === 'daily_note' }"
              @click="listSaveMode = 'daily_note'"
            >
              Today’s date
            </button>
          </div>
        </div>

        <input
          v-model="listFileName"
          type="text"
          placeholder="tasks.md"
          :disabled="listSaveMode === 'daily_note'"
        >
      </div>
    </div>

    <!-- notes card -->
    <div class="card">
      <h2 class="card-title">
        Notes file
      </h2>
      <div class="field row">
        <div class="field">
          <div
            class="segmented-control"
            :data-active="noteSaveMode === 'daily_note' ? 'right' : 'left'"
          >
            <button
              type="button"
              class="segmented-option"
              :class="{ 'is-active': noteSaveMode === 'single_file' }"
              @click="noteSaveMode = 'single_file'"
            >
              Custom filename
            </button>

            <button
              type="button"
              class="segmented-option"
              :class="{ 'is-active': noteSaveMode === 'daily_note' }"
              @click="noteSaveMode = 'daily_note'"
            >
              Today’s date
            </button>
          </div>

          <input
            v-model="noteFileName"
            type="text"
            placeholder="notes.md"
            :disabled="noteSaveMode === 'daily_note'"
          >
        </div>
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
          class="glass-icon-button remove-button"
          :disabled="presets.length === 1"
          @click="removePreset(preset.id)"
        >
          <Trash />
        </button>
      </div>

      <label class="field">
        <span>Label</span>
        <input v-model="preset.label" type="text">
      </label>

      <label class="field">
        <span>Tag</span>
        <input v-model="preset.tag" type="text">
      </label>

      <div class="field">
        <span>Save mode</span>

        <div
          class="segmented-control"
          :data-active="preset.saveMode === 'daily_note' ? 'right' : 'left'"
        >
          <button
            type="button"
            class="segmented-option"
            :class="{ 'is-active': preset.saveMode === 'single_file' }"
            @click="preset.saveMode = 'single_file'"
          >
            Custom filename
          </button>

          <button
            type="button"
            class="segmented-option"
            :class="{ 'is-active': preset.saveMode === 'daily_note' }"
            @click="preset.saveMode = 'daily_note'"
          >
            Today’s date
          </button>
        </div>
      </div>

      <label class="field">
        <span>Filename</span>
        <input
          v-model="preset.fileName"
          type="text"
          placeholder="tasks.md"
          :disabled="preset.saveMode === 'daily_note'"
        >
      </label>
    </div>

    <button class="glass-button glass-button--block glass-button--secondary add-button" @click="addPreset">
      + Add Task
    </button>

    <div class="actions">
      <button class="glass-button glass-button--secondary secondary-button" @click="handleReset">
        Reset
      </button>
      <button class="glass-button glass-button--primary primary-button" @click="handleSave">
        Save
      </button>
    </div>
  </div>
</template>

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
  padding: 6px 14px;
  border-radius: 999px;
  background: var(--surface);
  color: var(--text);
  font-weight: 600;
  border: 1px solid transparent;
  transition: background 0.2s ease, border-color 0.2s ease;
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
  border: 1px solid color-mix(in srgb, var(--c-light) 16%, transparent);
  border-radius: 16px;
  padding: 12px 14px;
  background: color-mix(in srgb, var(--c-glass) 10%, transparent);
  color: var(--text);
  backdrop-filter: blur(10px) saturate(var(--saturation));
  -webkit-backdrop-filter: blur(10px) saturate(var(--saturation));
  box-shadow:
    inset 0 0 0 1px color-mix(in srgb, var(--c-light) calc(var(--glass-reflex-light) * 8%), transparent),
    inset 0 -1px 3px 0 color-mix(in srgb, var(--c-dark) calc(var(--glass-reflex-dark) * 8%), transparent);
}

.field input:disabled {
  opacity: 0.55;
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

.add-button {
  margin-top: 4px;
}

.primary-button,
.secondary-button {
  flex: 1;
}

.actions {
  display: flex;
  gap: 12px;
  margin-top: 20px;
}
</style>
