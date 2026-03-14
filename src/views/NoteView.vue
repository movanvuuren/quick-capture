<script setup lang="ts">
import Editor from '@toast-ui/editor'
import { Trash } from 'lucide-vue-next'
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import PinToggleButton from '../components/PinToggleButton.vue'
import { loadSettings } from '../lib/settings'
import { FolderPicker } from '../plugins/folder-picker'
import { parseFrontmatter, stripFrontmatter } from '../lib/lists'
import { createNoteFile, saveNoteToFile, setFilePinned } from '../lib/listFiles'
import '@toast-ui/editor/dist/toastui-editor.css'
import '@toast-ui/editor/dist/theme/toastui-editor-dark.css'

const router = useRouter()
const route = useRoute()
const settings = loadSettings()

const noteId = computed(() => route.params.id as string | undefined)
const noteTitle = ref('')
const notePinned = ref(false)
const noteCreated = ref('')
const noteUpdated = ref('')

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
      const fm = parseFrontmatter(result.content)
      notePinned.value = fm.pinned === true || fm.pinned === 'true'
      noteCreated.value = typeof fm.created === 'string' ? fm.created : ''
      noteUpdated.value = typeof fm.updated === 'string' ? fm.updated : ''
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

async function toggleNotePin() {
  if (!noteId.value || !settings.baseFolderUri)
    return
  notePinned.value = !notePinned.value
  try {
    await setFilePinned(settings.baseFolderUri, noteId.value, notePinned.value)
  }
  catch (err) {
    console.error('Failed to toggle pin', err)
    notePinned.value = !notePinned.value
  }
}

async function deleteNote() {
  if (!noteId.value || !settings.baseFolderUri)
    return
  try {
    await FolderPicker.deleteFile({
      folderUri: settings.baseFolderUri,
      fileName: noteId.value,
    })
    router.back()
  }
  catch (err) {
    console.error('Failed to delete note', err)
  }
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
      <h1>Note</h1>
    </div>

    <div class="detail-header glass-panel">
      <input v-model="noteTitle" class="title-input" type="text" placeholder="Note title">
      <div class="detail-meta">
        <div class="meta-well">
          <span class="meta-date">{{ noteUpdated || noteCreated || (noteId ? noteId.replace(/\.md$/i, '') : '')
          }}</span>
          <PinToggleButton :pinned="notePinned" :size="20" item-label="note" variant="glass" @toggle="toggleNotePin" />
          <button v-if="noteId" class="glass-icon-button" aria-label="Delete note" @click="deleteNote">
            <Trash :size="20" />
          </button>
        </div>
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
  align-items: center;
  gap: 12px;
  margin-bottom: 24px;
}

.glass-panel {
  background:
    radial-gradient(circle at top right, color-mix(in srgb, var(--primary) 12%, transparent), transparent 28%),
    linear-gradient(180deg,
      color-mix(in srgb, var(--c-light) 12%, transparent),
      color-mix(in srgb, var(--c-glass) 10%, transparent)),
    color-mix(in srgb, var(--surface) 84%, transparent);
  backdrop-filter: blur(14px) saturate(var(--saturation));
  -webkit-backdrop-filter: blur(14px) saturate(var(--saturation));
  border: 1px solid color-mix(in srgb, var(--c-light) 20%, transparent);
  border-radius: 24px;
  box-shadow:
    inset 0 0 0 1px color-mix(in srgb, var(--c-light) calc(var(--glass-reflex-light) * 8%), transparent),
    inset 0 -1px 4px 0 color-mix(in srgb, var(--c-dark) calc(var(--glass-reflex-dark) * 9%), transparent),
    0 18px 36px color-mix(in srgb, var(--c-dark) calc(var(--glass-reflex-dark) * 14%), transparent);
}

.detail-header {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  align-items: start;
  gap: 12px;
  padding: 8px 14px;
  margin-bottom: 12px;
  position: relative;
  z-index: 2;
}

.detail-meta {
  display: inline-flex;
  align-items: center;
  white-space: nowrap;
  justify-self: end;
}

.meta-well {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 2px 4px 2px 10px;
  border-radius: 16px;
  border: 1px solid color-mix(in srgb, var(--c-light) 16%, transparent);
  background: color-mix(in srgb, var(--c-light) 10%, transparent);
  overflow: hidden;
  position: relative;
  z-index: 1;
  max-width: 100%;
}

.meta-date {
  color: var(--text-soft);
  font-size: 0.8rem;
  margin-right: 4px;
  max-width: 170px;
  overflow: hidden;
  text-overflow: ellipsis;
}

.title-input {
  width: 100%;
  border: none;
  outline: none;
  padding: 4px 0;
  background: transparent;
  color: var(--text);
  font-size: 1.3rem;
  font-weight: 700;
}

.title-input:focus {
  outline: none;
}

.card {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.glass-card {
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: 16px;
  padding: 16px;
  position: relative;
  z-index: 1;
  backdrop-filter: blur(14px) saturate(var(--saturation));
  -webkit-backdrop-filter: blur(14px) saturate(var(--saturation));
  box-shadow: var(--shadow);
}

@media (max-width: 560px) {
  .detail-header {
    grid-template-columns: 1fr;
    gap: 8px;
  }

  .detail-meta {
    justify-self: start;
  }
}
</style>
