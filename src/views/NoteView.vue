<template>
  <div class="page">
    <div class="header">
      <button class="glass-icon-button back-button" @click="goBack" aria-label="Go back">
        ←
      </button>
      <div>
        <h1>Note</h1>
      </div>
    </div>

    <div class="glass-card card">
      <div ref="editorElement" />

      <button class="glass-button glass-button--primary save-button" @click="saveNote">
        Save
      </button>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted, onBeforeUnmount } from 'vue'
import Editor from '@toast-ui/editor'
import '@toast-ui/editor/dist/toastui-editor.css'
import '@toast-ui/editor/dist/theme/toastui-editor-dark.css'
import { useRouter } from 'vue-router'
import { loadSettings } from '../lib/settings'
import { FolderPicker } from '../plugins/folder-picker'

const router = useRouter()
const settings = loadSettings()

const editorElement = ref<HTMLElement | null>(null)
let editorInstance: any = null

function initEditor(theme: 'light' | 'dark' | 'dim', initialValue = '') {
  if (editorElement.value) {
    editorInstance = new Editor({
      el: editorElement.value,
      height: '400px',
      initialEditType: 'wysiwyg',
      initialValue,
      previewStyle: 'vertical',
      theme: theme,
      placeholder: 'Start writing your note...',
    })
  }
}

function destroyEditor() {
  if (editorInstance) {
    editorInstance.destroy()
    editorInstance = null
  }
}

onMounted(() => {
  initEditor(settings.theme)
})

onBeforeUnmount(() => {
  destroyEditor()
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
  if (!editorInstance) return
  const text = editorInstance.getMarkdown().trim()
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

  editorInstance.setMarkdown('')
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


.card {
  background: var(--surface);
  padding: 20px;
  border-radius: 20px;
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.glass-card {
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: 16px;
  padding: 10px;
  
  backdrop-filter: blur(14px) saturate(var(--saturation));
  -webkit-backdrop-filter: blur(14px) saturate(var(--saturation));
  box-shadow: var(--shadow);
}

</style>
