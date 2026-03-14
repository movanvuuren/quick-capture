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
const noteTitle = ref('')

const editorElement = ref<HTMLElement | null>(null)
let editorInstance: any = null

function todayIso(): string {
  return new Date().toISOString().slice(0, 10)
}

function splitNoteContent(markdown: string): { title: string, body: string } {
  const normalized = markdown.replace(/\r\n/g, '\n').trim()
  const match = normalized.match(/^#\s+(.+)\n(?:\n)?([\s\S]*)$/)

  if (!match)
    return { title: '', body: normalized }

  return {
    title: match[1]?.trim() || '',
    body: (match[2] || '').trim(),
  }
}

function buildNoteMarkdown(title: string, body: string): string {
  const trimmedTitle = title.trim()
  const trimmedBody = body.trim()

  if (trimmedTitle && trimmedBody)
    return `# ${trimmedTitle}\n\n${trimmedBody}`

  if (trimmedTitle)
    return `# ${trimmedTitle}`

  return trimmedBody
}

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

  const parsed = splitNoteContent(initialContent)
  noteTitle.value = parsed.title || (!noteId.value ? `Note ${todayIso()}` : '')
  initEditor(settings.theme, parsed.body)
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
  const body = editorInstance.getMarkdown().trim()
  const content = buildNoteMarkdown(noteTitle.value, body)
  if (!content.trim())
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
      <input v-model="noteTitle" class="title-input" type="text" placeholder="Note title">

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

.title-input {
  width: 100%;
  border: 1px solid color-mix(in srgb, var(--c-light) 16%, transparent);
  border-radius: 16px;
  padding: 12px 14px;
  background: color-mix(in srgb, var(--c-glass) 10%, transparent);
  color: var(--text);
  font-size: 1.05rem;
  font-weight: 700;
  backdrop-filter: blur(10px) saturate(var(--saturation));
  -webkit-backdrop-filter: blur(10px) saturate(var(--saturation));
}

.title-input:focus {
  outline: none;
  border-color: color-mix(in srgb, var(--primary) 34%, var(--border));
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
