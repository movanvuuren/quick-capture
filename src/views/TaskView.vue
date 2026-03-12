<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { loadSettings } from '../lib/settings'
import { FolderPicker } from '../plugins/folder-picker'

const router = useRouter()
const route = useRoute()

const settings = loadSettings()

const taskText = ref('')
const selectedDate = ref('')

const preset = computed(() =>
  settings.quickTaskPresets.find(
    p => p.id === String(route.params.preset),
  ),
)

const previewTask = computed(() => {
  const text = taskText.value.trim()
  const tag = preset.value?.tag ?? ''
  const due = selectedDate.value ? ` 📅 ${selectedDate.value}` : ''

  return ['- [ ]', tag, text].filter(Boolean).join(' ') + due
})

function goBack() {
  router.back()
}

function todayIso(): string {
  const now = new Date()
  const year = now.getFullYear()
  const month = String(now.getMonth() + 1).padStart(2, '0')
  const day = String(now.getDate()).padStart(2, '0')
  return `${year}-${month}-${day}`
}

async function saveTask() {
  if (!preset.value || !taskText.value.trim())
    return

  const taskLine = previewTask.value

  // persist using the global system folder if configured
  if (settings.baseFolderUri) {
    const fileName
      = preset.value?.saveMode === 'daily_note'
        ? `${todayIso()}.md`
        : (preset.value?.fileName || settings.listFileName || 'tasks.md')

    try {
      await FolderPicker.appendToFile({
        folderUri: settings.baseFolderUri,
        fileName,
        content: `${taskLine}\n`,
      })
      console.log('Task appended to file:', taskLine)
    }
    catch (err) {
      console.error('Failed to append task to file', err)
    }
  }
  else {
    console.warn('No system folder configured – task not saved to disk')
  }

  // reset form and go back
  taskText.value = ''
  selectedDate.value = ''

  router.back()
}
</script>

<template>
  <div class="page">
    <div class="header">
      <button class="glass-icon-button back-button" aria-label="Go back" @click="goBack">
        ←
      </button>

      <div>
        <h1>{{ preset?.label ?? 'Task' }}</h1>
      </div>
    </div>

    <div v-if="preset" class="card glass-card">
      <textarea
        v-model="taskText"
        class="glass-input task-textarea"
        placeholder="Enter task..."
        rows="4"
        autofocus
      />

      <div class="field">
        <label class="field-label">Due date</label>

        <input
          v-model="selectedDate"
          class="date-input glass-input"
          type="date"
        >
      </div>

      <div class="preview glass-panel">
        <span class="preview-label">Preview</span>
        <code>{{ previewTask }}</code>
      </div>

      <button class="glass-button glass-button--primary glass-button--block primary-button" @click="saveTask">
        Add
      </button>
    </div>

    <div v-else class="card glass-card">
      Preset not found.
    </div>
  </div>
</template>

<style scoped>
.page {
  min-height: 100vh;
  padding: 24px 20px;
  background: var(--bg);
  color: var(--text);
}

.header {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 24px;
}

.header h1 {
  margin: 0;
  font-size: 1.8rem;
  line-height: 1.1;
}

.card {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.glass-card {
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: 24px;
  padding: 20px;
  backdrop-filter: blur(14px) saturate(var(--saturation));
  -webkit-backdrop-filter: blur(14px) saturate(var(--saturation));
  box-shadow: var(--shadow);
}

.field {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.field-label {
  font-size: 0.92rem;
  font-weight: 600;
  color: var(--text);
}

.glass-input {
  width: 100%;
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
  transition:
    border-color 180ms ease,
    box-shadow 180ms ease,
    background 180ms ease;
}

.glass-input:focus {
  border-color: color-mix(in srgb, var(--primary) 34%, var(--border));
  box-shadow:
    0 0 0 1px color-mix(in srgb, var(--primary) 18%, transparent),
    inset 0 0 0 1px color-mix(in srgb, var(--c-light) calc(var(--glass-reflex-light) * 10%), transparent),
    inset 0 -1px 3px 0 color-mix(in srgb, var(--c-dark) calc(var(--glass-reflex-dark) * 8%), transparent);
}

.task-textarea {
  min-height: 110px;
  resize: vertical;
  font-size: 1rem;
  line-height: 1.45;
}

.date-input {
  min-height: 44px;
}

.glass-panel {
  border: 1px solid color-mix(in srgb, var(--c-light) 14%, transparent);
  border-radius: 16px;
  padding: 12px 14px;
  background: color-mix(in srgb, var(--c-glass) 10%, transparent);
  backdrop-filter: blur(10px) saturate(var(--saturation));
  -webkit-backdrop-filter: blur(10px) saturate(var(--saturation));
  box-shadow:
    inset 0 0 0 1px color-mix(in srgb, var(--c-light) calc(var(--glass-reflex-light) * 6%), transparent),
    inset 0 -1px 3px 0 color-mix(in srgb, var(--c-dark) calc(var(--glass-reflex-dark) * 8%), transparent);
}

.glass-panel.preview {
  background:
    linear-gradient(
      135deg,
      color-mix(in srgb, var(--primary) 8%, transparent),
      color-mix(in srgb, var(--c-glass) 10%, transparent)
    );
}

.preview {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.preview-label {
  font-size: 0.78rem;
  font-weight: 600;
  color: var(--text-soft);
  text-transform: uppercase;
  letter-spacing: 0.04em;
}

.preview code {
  font-family:
    ui-monospace,
    SFMono-Regular,
    Menlo,
    Monaco,
    Consolas,
    "Liberation Mono",
    monospace;
  font-size: 0.92rem;
  color: var(--text);
  white-space: pre-wrap;
  word-break: break-word;
}

.primary-button {
  margin-top: 4px;
}
</style>
