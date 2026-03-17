<script setup lang="ts">
import {
  Bold,
  CheckSquare,
  Eraser,
  Heading1,
  Heading2,
  Heading3,
  Italic,
  List,
  ListOrdered,
  Strikethrough,
  Trash2,
  Undo2,
} from 'lucide-vue-next'
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import MetaWell from '../components/MetaWell.vue'
import PageHeader from '../components/PageHeader.vue'
import PinToggleButton from '../components/PinToggleButton.vue'
import { loadSettings } from '../lib/settings'
import { FolderPicker } from '../plugins/folder-picker'
import { parseFrontmatter, stripFrontmatter } from '../lib/lists'
import { createNoteFile, saveNoteToFile, setFilePinned } from '../lib/listFiles'

const router = useRouter()
const route = useRoute()
const settings = loadSettings()

const routeNoteId = computed(() => route.params.id as string | undefined)
const noteFileName = ref<string | undefined>(routeNoteId.value)
const noteTitle = ref('')
const notePinned = ref(false)
const noteCreated = ref('')
const noteUpdated = ref('')
const noteMetaDate = computed(() =>
  noteUpdated.value || noteCreated.value || (noteFileName.value ? noteFileName.value.replace(/\.md$/i, '') : todayIso()),
)

type AutosaveState = 'idle' | 'saving' | 'saved' | 'error'
interface NoteSnapshot {
  title: string
  html: string
}

const AUTOSAVE_DELAY_MS = 900
const MAX_UNDO_SNAPSHOTS = 5
const autosaveState = ref<AutosaveState>('idle')
const undoSnapshots = ref<NoteSnapshot[]>([])
const isApplyingSnapshot = ref(false)
const lastSavedSignature = ref('')

let autosaveTimer: ReturnType<typeof setTimeout> | null = null
let isPersisting = false
let pendingAutosave = false
let tagDecorateTimer: ReturnType<typeof setTimeout> | null = null

const autosaveLabel = computed(() => {
  if (autosaveState.value === 'saving')
    return 'Autosaving...'
  if (autosaveState.value === 'saved')
    return 'Saved'
  if (autosaveState.value === 'error')
    return 'Autosave failed'
  return 'Draft'
})

const editorEl = ref<HTMLDivElement | null>(null)

function todayIso(): string {
  return new Date().toISOString().slice(0, 10)
}

function splitNoteContent(markdown: string): { title: string, body: string } {
  const normalized = markdown.replace(/\r\n/g, '\n').trim()
  const match = normalized.match(/^#\s+(.+)\n(?:\n)?([\s\S]*)$/)
  if (!match)
    return { title: '', body: normalized }
  return { title: match[1]?.trim() || '', body: (match[2] || '').trim() }
}

function buildNoteMarkdown(title: string, body: string): string {
  const t = title.trim()
  const b = body.trim()
  if (t && b)
    return `# ${t}\n\n${b}`
  if (t)
    return `# ${t}`
  return b
}

// ── Markdown → HTML renderer ──────────────────────────────────────────────────
function escapeHtml(s: string): string {
  return s
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
}

function tagHue(tag: string): number {
  let hash = 0
  for (let i = 0; i < tag.length; i++)
    hash = ((hash << 5) - hash + tag.charCodeAt(i)) | 0
  return Math.abs(hash) % 360
}

function renderMarkdown(md: string): string {
  const lines = md.split('\n')
  const out: string[] = []
  let i = 0

  function flushParagraph(buf: string[]) {
    if (buf.length)
      out.push(`<p>${inlineRender(buf.join(' '))}</p>`)
    buf.length = 0
  }

  function inlineRender(text: string): string {
    const inline = escapeHtml(text)
      .replace(/\*\*(.+?)\*\*/g, '<strong>$1</strong>')
      .replace(/~~(.+?)~~/g, '<s>$1</s>')
      .replace(/\*(.+?)\*/g, '<em>$1</em>')
      .replace(/_(.+?)_/g, '<em>$1</em>')
      .replace(/`(.+?)`/g, '<code>$1</code>')

    return inline.replace(/(^|\s)#([A-Za-z][\w-]{1,24})\b/g, (_m, lead, tag) => {
      const hue = tagHue(tag)
      return `${lead}<span class="tag-chip" style="--tag-h:${hue}">#${tag}</span>`
    })
  }

  const paraBuffer: string[] = []

  while (i < lines.length) {
    const line = lines[i] ?? ''

    // Headings
    const h3 = line.match(/^###\s+(.+)/)
    const h2 = line.match(/^##\s+(.+)/)
    const h1 = line.match(/^#\s+(.+)/)
    if (h1 || h2 || h3) {
      flushParagraph(paraBuffer)
      const level = h3 ? 3 : h2 ? 2 : 1
      const text = (h3 || h2 || h1)![1] ?? ''
      out.push(`<h${level}>${inlineRender(text)}</h${level}>`)
      i++
      continue
    }

    // Fenced code block
    if (line.startsWith('```')) {
      flushParagraph(paraBuffer)
      const codeLines: string[] = []
      i++
      while (i < lines.length && !(lines[i] ?? '').startsWith('```')) {
        codeLines.push(escapeHtml(lines[i] ?? ''))
        i++
      }
      out.push(`<pre><code>${codeLines.join('\n')}</code></pre>`)
      i++
      continue
    }

    // Unordered list
    if (/^[-*+]\s/.test(line)) {
      flushParagraph(paraBuffer)
      const items: string[] = []
      while (i < lines.length && /^[-*+]\s/.test(lines[i] ?? '')) {
        items.push(`<li>${inlineRender((lines[i] ?? '').replace(/^[-*+]\s/, ''))}</li>`)
        i++
      }
      out.push(`<ul>${items.join('')}</ul>`)
      continue
    }

    // Ordered list
    if (/^\d+\.\s/.test(line)) {
      flushParagraph(paraBuffer)
      const items: string[] = []
      while (i < lines.length && /^\d+\.\s/.test(lines[i] ?? '')) {
        items.push(`<li>${inlineRender((lines[i] ?? '').replace(/^\d+\.\s/, ''))}</li>`)
        i++
      }
      out.push(`<ol>${items.join('')}</ol>`)
      continue
    }

    // Checkbox list  - [ ]  / - [x]
    if (/^-\s\[[ xX]\]/.test(line)) {
      flushParagraph(paraBuffer)
      const items: string[] = []
      while (i < lines.length && /^-\s\[[ xX]\]/.test(lines[i] ?? '')) {
        const checked = /^-\s\[[xX]\]/.test(lines[i] ?? '')
        const text = (lines[i] ?? '').replace(/^-\s\[[ xX]\]\s*/, '')
        const checkEl = `<input type="checkbox"${checked ? ' checked' : ''}>`
        items.push(`<li class="task-item">${checkEl} ${inlineRender(text)}</li>`)
        i++
      }
      out.push(`<ul class="task-list">${items.join('')}</ul>`)
      continue
    }

    // Blank line → flush paragraph
    if (line.trim() === '') {
      flushParagraph(paraBuffer)
      i++
      continue
    }

    paraBuffer.push(line)
    i++
  }

  flushParagraph(paraBuffer)
  return out.join('\n')
}

function setEditorContentFromMarkdown(markdown: string) {
  if (!editorEl.value)
    return

  const html = renderMarkdown(markdown).trim()
  editorEl.value.innerHTML = html || '<p><br></p>'
}

function getCurrentEditorHtml(): string {
  const html = editorEl.value?.innerHTML || ''
  return html.trim() ? html : '<p><br></p>'
}

function pushUndoSnapshot() {
  if (isApplyingSnapshot.value)
    return

  const snapshot: NoteSnapshot = {
    title: noteTitle.value,
    html: getCurrentEditorHtml(),
  }

  const last = undoSnapshots.value[undoSnapshots.value.length - 1]
  if (last && last.title === snapshot.title && last.html === snapshot.html)
    return

  undoSnapshots.value = [...undoSnapshots.value, snapshot].slice(-MAX_UNDO_SNAPSHOTS)
}

function applySnapshot(snapshot: NoteSnapshot) {
  isApplyingSnapshot.value = true
  noteTitle.value = snapshot.title
  if (editorEl.value)
    editorEl.value.innerHTML = snapshot.html

  nextTick(() => {
    if (editorEl.value)
      placeCaretAtEnd(editorEl.value)
    isApplyingSnapshot.value = false
  })
}

function undoLastChange() {
  if (undoSnapshots.value.length < 2)
    return

  const next = [...undoSnapshots.value]
  next.pop()
  const previous = next[next.length - 1]
  if (!previous)
    return

  undoSnapshots.value = next
  applySnapshot(previous)
  scheduleAutosave()
}

function focusEditor() {
  editorEl.value?.focus()
}

function execRichText(command: string, value?: string) {
  focusEditor()
  document.execCommand(command, false, value)
}

function setHeading(level: 1 | 2 | 3) {
  execRichText('formatBlock', `h${level}`)
}

function clearFormatting() {
  focusEditor()
  document.execCommand('removeFormat')
}

function insertCheckbox() {
  focusEditor()
  document.execCommand('insertHTML', false, '<ul class="task-list"><li class="task-item"><input type="checkbox"><span class="task-text">\u200B</span></li></ul>')
  nextTick(() => {
    const currentItem = getActiveTaskItem()
    if (currentItem)
      placeCaretInTaskItemText(currentItem)
  })
}

function getActiveTaskItem(): HTMLLIElement | null {
  const selection = window.getSelection()
  const anchor = selection?.anchorNode
  if (!anchor)
    return null

  const element = anchor instanceof HTMLElement ? anchor : anchor.parentElement
  if (!element)
    return null

  return element.closest('li.task-item') as HTMLLIElement | null
}

function placeCaretAtEnd(element: HTMLElement) {
  const range = document.createRange()
  range.selectNodeContents(element)
  range.collapse(false)
  const selection = window.getSelection()
  selection?.removeAllRanges()
  selection?.addRange(range)
}

function placeCaretInTaskItemText(item: HTMLLIElement) {
  let textHost = item.querySelector('.task-text') as HTMLSpanElement | null
  if (!textHost) {
    textHost = document.createElement('span')
    textHost.className = 'task-text'
    item.appendChild(textHost)
  }

  let textNode = Array.from(textHost.childNodes)
    .find(node => node.nodeType === Node.TEXT_NODE) as Text | undefined

  if (!textNode) {
    textNode = document.createTextNode('\u200B')
    textHost.appendChild(textNode)
  }
  else if (!textNode.nodeValue)
    textNode.nodeValue = '\u200B'

  const range = document.createRange()
  range.setStart(textNode, textNode.nodeValue?.length ?? 1)
  range.collapse(true)

  const selection = window.getSelection()
  selection?.removeAllRanges()
  selection?.addRange(range)
}

function taskItemText(li: HTMLLIElement): string {
  return Array.from(li.childNodes)
    .filter(child => !(child instanceof HTMLElement && child.tagName.toUpperCase() === 'INPUT'))
    .map(child => child.textContent || '')
    .join('')
    .trim()
}

function handleEditorKeydown(event: KeyboardEvent) {
  if (event.key !== 'Enter' || event.shiftKey || event.ctrlKey || event.altKey || event.metaKey)
    return

  const currentItem = getActiveTaskItem()
  if (!currentItem)
    return

  const parentList = currentItem.parentElement
  if (!(parentList instanceof HTMLUListElement) || !parentList.classList.contains('task-list'))
    return

  event.preventDefault()

  if (!taskItemText(currentItem)) {
    const paragraph = document.createElement('p')
    paragraph.appendChild(document.createElement('br'))

    currentItem.remove()
    if (!parentList.querySelector('li.task-item')) {
      parentList.replaceWith(paragraph)
    }
    else {
      parentList.parentNode?.insertBefore(paragraph, parentList.nextSibling)
    }

    placeCaretAtEnd(paragraph)
    return
  }

  const nextItem = document.createElement('li')
  nextItem.className = 'task-item'
  const checkbox = document.createElement('input')
  checkbox.type = 'checkbox'
  const textHost = document.createElement('span')
  textHost.className = 'task-text'
  textHost.appendChild(document.createTextNode('\u200B'))

  nextItem.appendChild(checkbox)
  nextItem.appendChild(textHost)

  currentItem.parentNode?.insertBefore(nextItem, currentItem.nextSibling)
  placeCaretInTaskItemText(nextItem)
}

function handleEditorClick(event: MouseEvent) {
  const target = event.target
  if (!(target instanceof HTMLInputElement) || target.type !== 'checkbox')
    return

  // Keep checkbox toggles interactive inside contenteditable.
  event.stopPropagation()
}

function extractInline(node: Node): string {
  if (node.nodeType === Node.TEXT_NODE)
    return (node.nodeValue || '').replace(/\u200B/g, '')

  if (!(node instanceof HTMLElement))
    return ''

  const tag = node.tagName.toUpperCase()
  const content = Array.from(node.childNodes).map(child => extractInline(child)).join('')

  if (tag === 'STRONG' || tag === 'B')
    return `**${content}**`
  if (tag === 'S' || tag === 'STRIKE' || tag === 'DEL')
    return `~~${content}~~`
  if (tag === 'EM' || tag === 'I')
    return `*${content}*`
  if (tag === 'CODE')
    return `\`${content}\``
  if (tag === 'BR')
    return '\n'

  return content
}

function htmlToMarkdown(html: string): string {
  const doc = new DOMParser().parseFromString(`<div id="root">${html}</div>`, 'text/html')
  const root = doc.getElementById('root')
  if (!root)
    return ''

  const parts: string[] = []

  const children = Array.from(root.childNodes)
  for (const node of children) {
    if (!(node instanceof HTMLElement)) {
      const text = extractInline(node).trim()
      if (text)
        parts.push(text)
      continue
    }

    const tag = node.tagName.toUpperCase()
    if (tag === 'H1' || tag === 'H2' || tag === 'H3') {
      const hashes = tag === 'H1' ? '#' : tag === 'H2' ? '##' : '###'
      parts.push(`${hashes} ${extractInline(node).trim()}`)
      continue
    }

    if (tag === 'P' || tag === 'DIV') {
      const text = extractInline(node).trim()
      if (text)
        parts.push(text)
      continue
    }

    if (tag === 'PRE') {
      const codeEl = node.querySelector('code')
      const code = (codeEl?.textContent || node.textContent || '').replace(/\n$/, '')
      parts.push(`\`\`\`\n${code}\n\`\`\``)
      continue
    }

    if (tag === 'OL') {
      const items = Array.from(node.querySelectorAll(':scope > li'))
      items.forEach((li, index) => {
        parts.push(`${index + 1}. ${extractInline(li).trim()}`)
      })
      continue
    }

    if (tag === 'UL') {
      const items = Array.from(node.querySelectorAll(':scope > li'))
      const isTaskList = node.classList.contains('task-list') || items.some(li => li.querySelector('input[type="checkbox"]'))

      items.forEach((li) => {
        if (isTaskList) {
          const checked = Boolean(li.querySelector('input[type="checkbox"]:checked'))
          const text = Array.from(li.childNodes)
            .filter(child => !(child instanceof HTMLElement && child.tagName.toUpperCase() === 'INPUT'))
            .map(child => extractInline(child))
            .join('')
            .trim()
          parts.push(`- [${checked ? 'x' : ' '}] ${text}`)
        }
        else {
          parts.push(`- ${extractInline(li).trim()}`)
        }
      })
      continue
    }

    const fallback = extractInline(node).trim()
    if (fallback)
      parts.push(fallback)
  }

  return parts.join('\n\n').replace(/\n{3,}/g, '\n\n').trim()
}

function getCurrentNotePayload() {
  const body = htmlToMarkdown(getCurrentEditorHtml())
  const content = buildNoteMarkdown(noteTitle.value, body)
  return {
    body,
    content,
    signature: content.trim(),
  }
}

async function persistNote(isAuto = false): Promise<boolean> {
  if (!settings.baseFolderUri)
    return false

  const { content, signature } = getCurrentNotePayload()
  if (!content.trim())
    return false

  if (signature === lastSavedSignature.value) {
    if (isAuto)
      autosaveState.value = 'saved'
    return true
  }

  if (isPersisting) {
    pendingAutosave = true
    return false
  }

  isPersisting = true
  if (isAuto)
    autosaveState.value = 'saving'

  try {
    if (noteFileName.value) {
      const saved = await saveNoteToFile(settings.baseFolderUri, {
        id: noteFileName.value,
        fileName: noteFileName.value,
        content,
      })
      noteUpdated.value = saved.updated || noteUpdated.value
      noteCreated.value = saved.created || noteCreated.value
    }
    else {
      const created = await createNoteFile(settings.baseFolderUri, {
        id: crypto.randomUUID(),
        content,
      })
      noteFileName.value = created.fileName
      noteCreated.value = created.created || noteCreated.value
      noteUpdated.value = created.updated || noteUpdated.value
    }

    lastSavedSignature.value = signature
    autosaveState.value = 'saved'
    return true
  }
  catch (err) {
    console.error('Failed to save note', err)
    if (isAuto)
      autosaveState.value = 'error'
    return false
  }
  finally {
    isPersisting = false
    if (pendingAutosave) {
      pendingAutosave = false
      scheduleAutosave(true)
    }
  }
}

function scheduleAutosave(immediate = false) {
  if (!settings.baseFolderUri)
    return

  if (autosaveTimer) {
    clearTimeout(autosaveTimer)
    autosaveTimer = null
  }

  if (immediate) {
    void persistNote(true)
    return
  }

  autosaveState.value = 'idle'
  autosaveTimer = setTimeout(() => {
    autosaveTimer = null
    void persistNote(true)
  }, AUTOSAVE_DELAY_MS)
}

function handleEditorInput() {
  pushUndoSnapshot()
  scheduleAutosave()
  scheduleTagDecoration()
}

function getCaretOffsetWithin(scope: HTMLElement): number | null {
  const selection = window.getSelection()
  if (!selection || selection.rangeCount === 0)
    return null

  const range = selection.getRangeAt(0)
  if (!range.collapsed || !scope.contains(range.startContainer))
    return null

  const pre = range.cloneRange()
  pre.selectNodeContents(scope)
  pre.setEnd(range.startContainer, range.startOffset)
  return pre.toString().length
}

function restoreCaretOffsetWithin(scope: HTMLElement, targetOffset: number) {
  const selection = window.getSelection()
  if (!selection)
    return

  let remaining = Math.max(0, targetOffset)
  const walker = document.createTreeWalker(scope, NodeFilter.SHOW_TEXT)
  let textNode: Node | null = walker.nextNode()

  while (textNode) {
    const value = textNode.nodeValue || ''
    if (remaining <= value.length) {
      const range = document.createRange()
      range.setStart(textNode, remaining)
      range.collapse(true)
      selection.removeAllRanges()
      selection.addRange(range)
      return
    }
    remaining -= value.length
    textNode = walker.nextNode()
  }

  const range = document.createRange()
  range.selectNodeContents(scope)
  range.collapse(false)
  selection.removeAllRanges()
  selection.addRange(range)
}

function getActiveEditableBlock(): HTMLElement | null {
  const editor = editorEl.value
  if (!editor)
    return null

  const selection = window.getSelection()
  const anchor = selection?.anchorNode
  if (!anchor)
    return null

  const anchorEl = anchor instanceof HTMLElement ? anchor : anchor.parentElement
  if (!anchorEl || !editor.contains(anchorEl))
    return null

  const block = anchorEl.closest('p,li,h1,h2,h3,div') as HTMLElement | null
  return block && editor.contains(block) ? block : editor
}

function decorateTagsInScope(scope: HTMLElement) {
  const sourceText = scope.textContent || ''
  if (!sourceText.includes('#') && !scope.querySelector('.tag-chip'))
    return

  const caretOffset = getCaretOffsetWithin(scope)

  const existing = Array.from(scope.querySelectorAll('span.tag-chip'))
  existing.forEach((chip) => {
    const text = chip.textContent || ''
    chip.replaceWith(document.createTextNode(text))
  })

  const walker = document.createTreeWalker(scope, NodeFilter.SHOW_TEXT)
  const nodes: Text[] = []
  let current: Node | null = walker.nextNode()
  while (current) {
    if (current instanceof Text)
      nodes.push(current)
    current = walker.nextNode()
  }

  for (const textNode of nodes) {
    const parent = textNode.parentElement
    if (!parent)
      continue
    if (parent.closest('code,pre') || parent.classList.contains('tag-chip'))
      continue

    const value = textNode.nodeValue || ''
    if (!value.includes('#'))
      continue

    const regex = /(^|\s)#([A-Za-z][\w-]{1,24})\b/g
    let lastIndex = 0
    let matched = false
    const fragment = document.createDocumentFragment()
    let match: RegExpExecArray | null

    while ((match = regex.exec(value)) !== null) {
      matched = true
      const whole = match[0] || ''
      const lead = match[1] || ''
      const tag = match[2] || ''
      const matchIndex = match.index

      const leadOffset = whole.length - (`${lead}#${tag}`).length
      const tagStart = matchIndex + Math.max(0, leadOffset)

      if (tagStart > lastIndex)
        fragment.appendChild(document.createTextNode(value.slice(lastIndex, tagStart)))

      const chip = document.createElement('span')
      chip.className = 'tag-chip'
      chip.style.setProperty('--tag-h', String(tagHue(tag)))
      chip.textContent = `#${tag}`
      fragment.appendChild(chip)

      lastIndex = tagStart + `#${tag}`.length
    }

    if (!matched)
      continue

    if (lastIndex < value.length)
      fragment.appendChild(document.createTextNode(value.slice(lastIndex)))

    textNode.replaceWith(fragment)
  }

  if (caretOffset !== null)
    restoreCaretOffsetWithin(scope, caretOffset)
}

function scheduleTagDecoration() {
  if (tagDecorateTimer) {
    clearTimeout(tagDecorateTimer)
    tagDecorateTimer = null
  }

  tagDecorateTimer = setTimeout(() => {
    tagDecorateTimer = null
    const block = getActiveEditableBlock()
    if (block)
      decorateTagsInScope(block)
  }, 80)
}

const toolbarActions = [
  { label: 'Undo', icon: Undo2, action: undoLastChange },
  { label: 'Heading 1', icon: Heading1, action: () => setHeading(1) },
  { label: 'Heading 2', icon: Heading2, action: () => setHeading(2) },
  { label: 'Heading 3', icon: Heading3, action: () => setHeading(3) },
  { label: 'Bold', icon: Bold, action: () => execRichText('bold') },
  { label: 'Italic', icon: Italic, action: () => execRichText('italic') },
  { label: 'Strikethrough', icon: Strikethrough, action: () => execRichText('strikeThrough') },
  { label: 'Bullet list', icon: List, action: () => execRichText('insertUnorderedList') },
  { label: 'Numbered list', icon: ListOrdered, action: () => execRichText('insertOrderedList') },
  { label: 'Checkbox', icon: CheckSquare, action: insertCheckbox },
  { label: 'Clear formatting', icon: Eraser, action: clearFormatting },
]

// ── Data load / save ──────────────────────────────────────────────────────────
onMounted(async () => {
  noteFileName.value = routeNoteId.value

  let initialContent = ''
  if (noteFileName.value && settings.baseFolderUri) {
    try {
      const result = await FolderPicker.readFile({
        folderUri: settings.baseFolderUri,
        fileName: noteFileName.value,
      })
      const fm = parseFrontmatter(result.content)
      notePinned.value = fm.pinned === true || fm.pinned === 'true'
      noteCreated.value = typeof fm.created === 'string' ? fm.created : ''
      noteUpdated.value = typeof fm.updated === 'string' ? fm.updated : ''
      initialContent = stripFrontmatter(result.content)
    }
    catch (err) {
      console.error(`Failed to load note ${noteFileName.value}`, err)
    }
  }
  else {
    noteCreated.value = todayIso()
  }

  const parsed = splitNoteContent(initialContent)
  noteTitle.value = parsed.title || (!noteFileName.value ? `Note ${todayIso()}` : '')
  setEditorContentFromMarkdown(parsed.body)

  lastSavedSignature.value = getCurrentNotePayload().signature
  autosaveState.value = 'saved'
  pushUndoSnapshot()
})

onBeforeUnmount(() => {
  if (autosaveTimer) {
    clearTimeout(autosaveTimer)
    autosaveTimer = null
  }
  if (tagDecorateTimer) {
    clearTimeout(tagDecorateTimer)
    tagDecorateTimer = null
  }
})

watch(() => noteTitle.value, () => {
  pushUndoSnapshot()
  scheduleAutosave()
})

function goBack() {
  router.back()
}

async function toggleNotePin() {
  if (!noteFileName.value || !settings.baseFolderUri)
    return
  notePinned.value = !notePinned.value
  try {
    await setFilePinned(settings.baseFolderUri, noteFileName.value, notePinned.value)
  }
  catch (err) {
    console.error('Failed to toggle pin', err)
    notePinned.value = !notePinned.value
  }
}

async function deleteNote() {
  if (!noteFileName.value || !settings.baseFolderUri)
    return
  try {
    await FolderPicker.deleteFile({
      folderUri: settings.baseFolderUri,
      fileName: noteFileName.value,
    })
    router.back()
  }
  catch (err) {
    console.error('Failed to delete note', err)
  }
}

async function saveNote() {
  const saved = await persistNote(false)
  if (saved)
    router.back()
}
</script>

<template>
  <div class="page">
    <PageHeader title="Note" @back="goBack">
      <template #right>
        <div class="detail-meta">
          <MetaWell :date="noteMetaDate">
            <PinToggleButton :pinned="notePinned" :size="20" item-label="note" variant="glass"
              @toggle="toggleNotePin" />
            <button v-if="noteFileName" class="glass-icon-button" aria-label="Delete note" @click="deleteNote">
              <Trash2 :size="20" />
            </button>
          </MetaWell>
        </div>
      </template>
    </PageHeader>

    <div class="detail-header glass-panel">
      <input v-model="noteTitle" class="title-input" type="text" placeholder="Note title">
    </div>

    <div class="glass-card card">
      <div class="toolbar" role="toolbar" aria-label="Formatting">
        <button v-for="item in toolbarActions" :key="item.label" type="button" class="toolbar-btn"
          :aria-label="item.label" :title="item.label" @mousedown.prevent="item.action()">
          <component :is="item.icon" :size="16" />
        </button>
      </div>

      <div ref="editorEl" class="editor-surface prose" contenteditable="true" spellcheck="true"
        @keydown="handleEditorKeydown" @click="handleEditorClick" @input="handleEditorInput" />

      <div class="editor-actions">
        <span class="autosave-status" :class="`is-${autosaveState}`">{{ autosaveLabel }}</span>
        <button class="glass-button glass-button--primary save-button" @click="saveNote">
          Save
        </button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.page {
  min-height: 100vh;
  padding: var(--page-top-padding) 20px 36px;
  background: var(--bg);
  color: var(--text);
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
  display: block;
  padding: 8px 14px;
  margin-bottom: 12px;
  position: relative;
  border-radius: 16px;
  z-index: 2;
}

.detail-meta {
  display: inline-flex;
  align-items: center;
  justify-self: end;
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
  gap: 0;
}

.glass-card {
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: 16px;
  padding: 0;
  overflow: hidden;
  position: relative;
  z-index: 1;
  backdrop-filter: blur(14px) saturate(var(--saturation));
  -webkit-backdrop-filter: blur(14px) saturate(var(--saturation));
  box-shadow: var(--shadow);
}

/* ── Toolbar ── */
.toolbar {
  display: flex;
  align-items: center;
  gap: 2px;
  padding: 6px 8px;
  border-bottom: 1px solid var(--border);
  flex-wrap: wrap;
}

.toolbar-btn {
  display: grid;
  place-items: center;
  width: 32px;
  height: 32px;
  border: none;
  border-radius: 8px;
  background: none;
  color: var(--text-soft);
  cursor: pointer;
  transition: background 0.1s, color 0.1s;
  flex-shrink: 0;
}

.toolbar-btn:hover,
.toolbar-btn:active {
  background: color-mix(in srgb, var(--primary) 14%, transparent);
  color: var(--text);
}

.toolbar-sep {
  flex: 1;
}

.editor-surface {
  padding: 14px 16px;
  min-height: 320px;
  border-bottom: 1px solid var(--border);
  outline: none;
}

/* Prose styling */
.prose :deep(h1) {
  font-size: 1.4rem;
  font-weight: 700;
  margin: 0.6em 0 0.3em;
}

.prose :deep(h2) {
  font-size: 1.15rem;
  font-weight: 600;
  margin: 0.6em 0 0.3em;
}

.prose :deep(h3) {
  font-size: 1rem;
  font-weight: 600;
  margin: 0.5em 0 0.25em;
}

.prose :deep(p) {
  margin: 0 0 0.65em;
  line-height: 1.6;
}

.prose :deep(strong) {
  font-weight: 700;
}

.prose :deep(em) {
  font-style: italic;
}

.prose :deep(code) {
  font-family: monospace;
  font-size: 0.85em;
  background: color-mix(in srgb, var(--primary) 10%, transparent);
  padding: 1px 4px;
  border-radius: 4px;
}

.prose :deep(pre) {
  background: color-mix(in srgb, var(--primary) 8%, var(--surface));
  border-radius: 8px;
  padding: 10px 14px;
  overflow-x: auto;
  margin: 0.5em 0;
}

.prose :deep(pre code) {
  background: none;
  padding: 0;
}

.prose :deep(ul),
.prose :deep(ol) {
  margin: 0 0 0.65em 1.4em;
  padding: 0;
}

.prose :deep(li) {
  margin-bottom: 0.2em;
  line-height: 1.5;
}

.prose :deep(.task-list) {
  list-style: none;
  margin-left: 0.3em;
}

.prose :deep(.task-list li) {
  list-style: none;
}

.prose :deep(.task-item) {
  display: flex;
  align-items: flex-start;
  justify-content: flex-start;
  gap: 6px;
}

.prose :deep(.task-item input) {
  margin-top: 3px;
  margin-right: 8px;
  margin-left: 0;
  float: none;
  position: static;
  order: 0;
  align-self: flex-start;
  accent-color: var(--primary);
  flex-shrink: 0;
}

.prose :deep(.empty-hint) {
  color: var(--text-soft);
  font-style: italic;
}

.prose :deep(.tag-chip) {
  display: inline-flex;
  align-items: center;
  border-radius: 999px;
  padding: 1px 7px;
  margin: 0 1px;
  line-height: 1.25;
  font-size: 0.82em;
  font-weight: 600;
  color: color-mix(in srgb, hsl(var(--tag-h) 76% 42%) 76%, var(--text));
  background: color-mix(in srgb, hsl(var(--tag-h) 82% 58%) 24%, transparent);
  border: 1px solid color-mix(in srgb, hsl(var(--tag-h) 80% 58%) 40%, transparent);
}

.save-button {
  margin: 0;
}

.editor-actions {
  margin: 14px 16px 14px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.autosave-status {
  font-size: 0.8rem;
  color: var(--text-soft);
}

.autosave-status.is-saving {
  color: var(--primary);
}

.autosave-status.is-error {
  color: var(--danger);
}

@media (max-width: 560px) {
  .detail-meta {
    justify-self: end;
  }

  .title-input {
    font-size: 1.15rem;
  }
}
</style>
