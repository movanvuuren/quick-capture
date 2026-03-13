<script setup lang="ts">
import Editor from '@toast-ui/editor'
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { loadSettings } from '../lib/settings'
import { FolderPicker } from '../plugins/folder-picker'
import { stripFrontmatter } from '../lib/lists'
import { createNoteFile, saveNoteToFile } from '../lib/listFiles'
import '@toast-ui/editor/dist/toastui-editor.css'
import '@toast-ui/editor/dist/theme/toastui-editor-dark.css'

const router = useRouter()
const route = useRoute()
const settings = loadSettings()

const noteId = computed(() => route.params.id as string | undefined)

const editorElement = ref<HTMLElement | null>(null)
let editorInstance: any = null

function initEditor(theme: 'light' | 'dark' | 'dim', initialValue = '') {
  if (editorInstance)
    destroyEditor()

  if (editorElement.value) {
    editorInstance = new Editor({
      el: editorElement.value,
      height: '400px',
      initialEditType: 'wysiwyg',
      initialValue,
      previewStyle: 'vertical',
      theme,
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

onMounted(async () => {
  let initialContent = ''
  if (noteId.value && settings.baseFolderUri) {
    try {
      const result = await FolderPicker.readFile({
        folderUri: settings.baseFolderUri,
        fileName: noteId.value,
      })
      // Strip the frontmatter so the user only edits the note body
      initialContent = stripFrontmatter(result.content)
    }
    catch (err) {
      console.error(`Failed to load note ${noteId.value}`, err)
    }
  }
  initEditor(settings.theme, initialContent)
})

onBeforeUnmount(() => {
  destroyEditor()
})

function goBack() {
  router.back()
}

async function saveNote() {
  if (!editorInstance)
    return
  const content = editorInstance.getMarkdown().trim()
  if (!content)
    return

  if (!settings.baseFolderUri) {
    console.warn('No system folder configured – note not saved to disk')
    return
  }

  try {
    if (noteId.value) {
      // We are editing an existing note
      await saveNoteToFile(settings.baseFolderUri, {
        id: noteId.value,
        fileName: noteId.value,
        content,
      })
    }
    else {
      // We are creating a new note
      await createNoteFile(settings.baseFolderUri, {
        id: crypto.randomUUID(),
        content,
      })
    }
  }
  catch (err) {
    console.error('Failed to save note', err)
  }

  editorInstance.setMarkdown('')
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
