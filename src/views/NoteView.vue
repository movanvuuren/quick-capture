<template>
  <div class="page">
    <div class="header">
      <button class="icon-button back-button" @click="goBack" aria-label="Go back">
        ←
      </button>
      <div>
        <h1>Note</h1>
      </div>
    </div>

    <div class="card">
      <textarea ref="textarea" placeholder="Start writing your note..." />

      <button class="save-button" @click="saveNote">
        Save
      </button>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import SimpleMDE from 'simplemde'
import 'simplemde/dist/simplemde.min.css'
import { marked } from 'marked'
import { useRouter } from 'vue-router'
import { loadSettings } from '../lib/settings'
import { FolderPicker } from '../plugins/folder-picker'

const router = useRouter()
const settings = loadSettings()

const textarea = ref<HTMLTextAreaElement | null>(null)
let simplemde: SimpleMDE | null = null

onMounted(() => {
  if (textarea.value) {
    simplemde = new SimpleMDE({
      element: textarea.value,
      spellChecker: false,
      previewRender: (text: string) => marked(text),
    })
  }
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

async function saveNote() {
  const text = simplemde?.value().trim() || ''
  if (!text) return

  if (settings.baseFolderUri) {
    const fileName =
      settings.noteSaveMode === 'daily_note'
        ? `${todayIso()}.md`
        : settings.noteFileName || 'notes.md'

    try {
      await FolderPicker.appendToFile({
        folderUri: settings.baseFolderUri,
        fileName,
        content: text + '\n\n',
      })
      console.log('Note appended to file')
    } catch (err) {
      console.error('Failed to append note', err)
    }
  } else {
    console.warn('No system folder configured – note not saved to disk')
  }

  simplemde?.value('')
  router.back()
}
</script>

<style scoped>
/* reuse styles from TaskView for consistency */
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
  border: none;
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

.save-button {
  border: none;
  background: var(--text);
  color: var(--bg);
  padding: 12px;
  border-radius: 12px;
}
</style>
