<script setup lang="ts">
import { Moon, Sparkles, Sun, Trash2 } from 'lucide-vue-next'
import { computed, reactive, watch } from 'vue'
import { useRouter } from 'vue-router'
import OptionSwitcher from '../components/OptionSwitcher.vue'
import { applyTheme, loadSettings, saveSettings } from '../lib/settings'
import { FolderPicker } from '../plugins/folder-picker'

const router = useRouter()
const MAX_QUICK_TASK_PRESETS = 3

// A single reactive object for all settings, loaded from storage.
const settings = reactive(loadSettings())

// Watch for any deep change in the settings object and save automatically.
watch(
  settings,
  (newSettings) => {
    // The watcher gives us a readonly proxy, so we convert it back to a plain
    // object to ensure it can be serialized by saveSettings.
    saveSettings(JSON.parse(JSON.stringify(newSettings)))
  },
  { deep: true },
)

// Also watch the theme specifically to apply it to the document.
watch(
  () => settings.theme,
  (newTheme) => {
    applyTheme(newTheme)
  },
)

// Apply the initial theme when the component loads.
applyTheme(settings.theme)

function goBack() {
  router.back()
}

async function pickBaseFolder() {
  try {
    const result = await FolderPicker.pickFolder()
    settings.baseFolderUri = result.uri
    settings.baseFolderName = result.name
  }
  catch (error) {
    console.error(error)
  }
}

function addPreset() {
  if (settings.quickTaskPresets.length >= MAX_QUICK_TASK_PRESETS)
    return

  settings.quickTaskPresets.push({
    id: crypto.randomUUID(),
    label: '',
    tag: '',
    saveMode: settings.listSaveMode,
    fileName: settings.listFileName || 'tasks.md',
  })
}

function removePreset(id: string) {
  if (settings.quickTaskPresets.length === 1)
    return
  const index = settings.quickTaskPresets.findIndex(p => p.id === id)
  if (index > -1)
    settings.quickTaskPresets.splice(index, 1)
}

const themeOptions = [
  { value: 'light', label: 'Light', icon: Sun },
  { value: 'dark', label: 'Dark', icon: Moon },
  { value: 'dim', label: 'Dim', icon: Sparkles },
]

const canAddPreset = computed(() => settings.quickTaskPresets.length < MAX_QUICK_TASK_PRESETS)
</script>

<template>
  <div class="page">
    <div class="header">
      <button class="glass-icon-button back-button" aria-label="Go back" @click="goBack">
        ←
      </button>

      <h1>Settings</h1>
    </div>

    <!-- system folder card -->
    <div class="card">
      <div class="field">
        <span>System folder</span>
        <button class="glass-button folder-button" type="button" @click="pickBaseFolder">
          Select folder
        </button>
        <p v-if="settings.baseFolderName" class="hint">
          {{ settings.baseFolderName }}
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
      <OptionSwitcher v-model="settings.theme" :options="themeOptions" aria-label="Theme" />
    </div>

    <div v-for="(preset, index) in settings.quickTaskPresets" :key="preset.id" class="card">
      <div class="card-header">
        <h2>Task {{ index + 1 }}</h2>
        <button class="glass-icon-button remove-button" :disabled="settings.quickTaskPresets.length === 1"
          @click="removePreset(preset.id)">
          <Trash2 :size="14" />
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

        <div class="segmented-control" :data-active="preset.saveMode === 'daily_note' ? 'right' : 'left'">
          <button type="button" class="segmented-option" :class="{ 'is-active': preset.saveMode === 'single_file' }"
            @click="preset.saveMode = 'single_file'">
            Custom filename
          </button>

          <button type="button" class="segmented-option" :class="{ 'is-active': preset.saveMode === 'daily_note' }"
            @click="preset.saveMode = 'daily_note'">
            Today’s date
          </button>
        </div>
      </div>

      <label class="field">
        <span>Filename</span>
        <input v-model="preset.fileName" type="text" placeholder="tasks.md"
          :disabled="preset.saveMode === 'daily_note'">
      </label>
    </div>

    <button v-if="canAddPreset" class="glass-button glass-button--block glass-button--secondary add-button"
      @click="addPreset">
      + Add Task
    </button>
    <p class="hint add-limit-hint">
      {{ settings.quickTaskPresets.length }}/{{ MAX_QUICK_TASK_PRESETS }} presets used
    </p>
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
  align-items: center;
  gap: 12px;
  margin-bottom: 24px;
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
  width: 34px;
  height: 34px;
  border-radius: 12px;
  color: var(--text-soft);
}

.remove-button:hover {
  color: var(--danger);
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

.add-limit-hint {
  margin: 6px 0 0;
  text-align: right;
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
