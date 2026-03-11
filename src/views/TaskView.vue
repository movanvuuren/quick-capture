<template>
  <div class="page">
    <div class="header">
            <button class="glass-icon-button back-button" @click="goBack" aria-label="Go back">
        ←
      </button>

      <div>
        <h1>{{ preset?.label ?? 'Task' }}</h1>
      </div>
    </div>

    <div class="card" v-if="preset">
      <textarea
        v-model="taskText"
        placeholder="Enter task..."
        rows="4"
        autofocus
      />

      <div class="field">
        <label class="field-label">Due date</label>

        <input
          v-model="selectedDate"
          class="date-input"
          type="date"
        />
      </div>


      <div class="preview">
        <span class="preview-label">Preview</span>
        <code>{{ previewTask }}</code>
      </div>

      <button class="save-button" @click="saveTask">
        Add
      </button>
    </div>

    <div class="card" v-else>
      Preset not found.
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { loadSettings } from '../lib/settings'
import { FolderPicker } from '../plugins/folder-picker'

const router = useRouter()
const route = useRoute()

const settings = loadSettings()

const taskText = ref('')
const selectedDate = ref('')

const preset = computed(() =>
  settings.quickTaskPresets.find(
    (p) => p.id === String(route.params.preset),
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
  if (!preset.value || !taskText.value.trim()) return

  const taskLine = previewTask.value

  // persist using the global system folder if configured
  if (settings.baseFolderUri) {
    const fileName =
      preset.value?.saveMode === 'daily_note'
        ? `${todayIso()}.md`
        : (preset.value?.fileName || settings.listFileName || 'tasks.md')

    try {
      await FolderPicker.appendToFile({
        folderUri: settings.baseFolderUri,
        fileName,
        content: taskLine + '\n',
      })
      console.log('Task appended to file:', taskLine)
    } catch (err) {
      console.error('Failed to append task to file', err)
    }
  } else {
    console.warn('No system folder configured – task not saved to disk')
  }

  // reset form and go back
  taskText.value = ''
  selectedDate.value = ''

  router.back()
}
</script>

<style scoped>
.page {
  min-height: 100vh;
  padding: 24px 20px;
  background: var(--bg);
  color: var(--text);
} 

.header {
  display: flex;
  gap: 12px;
  margin-bottom: 24px;
}

.back-button {
  /* button is already .icon-button so it gets colors from CSS variables */
  border: none;
  /* use surface color so dark mode shows correctly */
  background: var(--surface-strong);
  width: 42px;
  height: 42px;
  border-radius: 999px;
  color: var(--text);
}

.card {
  background: var(--surface);
  padding: 20px;
  border-radius: 20px;
  display: flex;
  flex-direction: column;
  gap: 14px;
} 

textarea {
  border: 1px solid var(--border);
  border-radius: 12px;
  padding: 14px;
  font-size: 16px;
  min-height: 110px;
  color: var(--text);
  background: var(--surface-strong);
}

.field {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.field-label {
  font-size: 14px;
  font-weight: 600;
}

.date-input {
  border: 1px solid var(--border);
  border-radius: 12px;
  padding: 10px;
  color: var(--text);
  background: var(--surface-strong);
}

.preview {
  background: var(--surface-strong);
  border-radius: 12px;
  padding: 12px;
  display: flex;
  flex-direction: column;
  gap: 6px;
} 

.preview-label {
  font-size: 12px;
  font-weight: 600;
  color: var(--text-soft);
}



.save-button {
  border: none;
  background: var(--text);
  color: var(--bg);
  padding: 12px;
  border-radius: 12px;
} 
</style>