import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { nextTick } from 'vue'
import NoteView from '../../src/views/NoteView.vue'
import * as settings from '../../src/lib/settings'
import { FolderPicker } from '../../src/plugins/folder-picker'
import * as listFiles from '../../src/lib/listFiles'

vi.mock('../../src/lib/settings')
vi.mock('../../src/plugins/folder-picker', () => ({
  FolderPicker: {
    readFile: vi.fn(),
    deleteFile: vi.fn(),
  },
}))
vi.mock('../../src/lib/listFiles', () => ({
  createNoteFile: vi.fn(),
  saveNoteToFile: vi.fn(),
  setFilePinned: vi.fn(),
}))

const routes = [
  { path: '/', name: 'home', component: { template: '<div>Home</div>' } },
  { path: '/note', name: 'note-new', component: NoteView },
  { path: '/note/:id', name: 'note', component: NoteView },
]

function makeRouter(initialPath = '/note/test-note.md') {
  const router = createRouter({
    history: createMemoryHistory(),
    routes,
  })
  router.push(initialPath)
  return router
}

const mockSettings = {
  baseFolderUri: 'file:///mock/folder/',
  quickTaskPresets: [],
  listFileName: 'tasks.md',
}

describe('NoteView.vue', () => {
  beforeEach(() => {
    vi.useFakeTimers()
    vi.setSystemTime(new Date('2026-03-18T10:00:00'))

    vi.clearAllMocks()

    vi.spyOn(settings, 'loadSettings').mockReturnValue(
      JSON.parse(JSON.stringify(mockSettings)),
    )

    vi.mocked(FolderPicker.readFile).mockResolvedValue({
      content: `---
pinned: true
created: 2026-03-10
updated: 2026-03-17
---

# Existing title

This is **note** body.`,
    })

    vi.mocked(FolderPicker.deleteFile).mockResolvedValue(undefined)

    vi.mocked(listFiles.createNoteFile).mockResolvedValue({
      fileName: 'new-note.md',
      created: '2026-03-18',
      updated: '2026-03-18',
    })

    vi.mocked(listFiles.saveNoteToFile).mockResolvedValue({
      fileName: 'test-note.md',
      created: '2026-03-10',
      updated: '2026-03-18',
    })

    vi.mocked(listFiles.setFilePinned).mockResolvedValue(undefined)

    vi.stubGlobal('crypto', {
      randomUUID: () => 'mock-uuid',
    })

    Object.defineProperty(document, 'execCommand', {
      writable: true,
      value: vi.fn(() => true),
    })

    vi.spyOn(window, 'getSelection').mockImplementation(() => ({
      rangeCount: 0,
      removeAllRanges: vi.fn(),
      addRange: vi.fn(),
      getRangeAt: vi.fn(),
      anchorNode: null,
    }) as any)
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    vi.useRealTimers()
  })

  async function mountComponent(initialPath = '/note/test-note.md') {
    const router = makeRouter(initialPath)
    await router.isReady()

    const wrapper = mount(NoteView, {
      global: {
        plugins: [router],
        stubs: {
          Bold: true,
          CheckSquare: true,
          Eraser: true,
          Heading1: true,
          Heading2: true,
          Heading3: true,
          Italic: true,
          List: true,
          ListOrdered: true,
          Strikethrough: true,
          Trash2: true,
          Undo2: true,
          PageHeader: {
            props: ['title'],
            emits: ['back'],
            template: `
              <div class="page-header-stub">
                <button class="page-header-back" @click="$emit('back')">Back</button>
                <slot name="right" />
              </div>
            `,
          },
          MetaWell: {
            props: ['date'],
            template: `<div class="meta-well-stub"><slot /></div>`,
          },
          PinToggleButton: {
            props: ['pinned'],
            emits: ['toggle'],
            template: `<button class="pin-toggle-stub" @click="$emit('toggle')">Pin</button>`,
          },
        },
      },
    })

    await flushPromises()
    await nextTick()
    await flushPromises()
    await nextTick()

    return { wrapper, router }
  }

  it('loads an existing note title and content', async () => {
    const { wrapper } = await mountComponent()

    expect(FolderPicker.readFile).toHaveBeenCalledWith({
      folderUri: 'file:///mock/folder/',
      fileName: 'test-note.md',
    })

    const titleInput = wrapper.find('.title-input')
    expect((titleInput.element as HTMLInputElement).value).toBe('Existing title')

    const editor = wrapper.find('.editor-surface')
    expect(editor.html()).toContain('This is')
    expect(editor.html()).toContain('<strong>note</strong>')
  })

  it('uses a default title for a new note', async () => {
    const { wrapper } = await mountComponent('/note')

    expect(FolderPicker.readFile).not.toHaveBeenCalled()

    const titleInput = wrapper.find('.title-input')
    expect((titleInput.element as HTMLInputElement).value).toBe('Note 2026-03-18')
  })

  it('toggles note pin', async () => {
    const { wrapper } = await mountComponent()

    await wrapper.find('.pin-toggle-stub').trigger('click')

    expect(listFiles.setFilePinned).toHaveBeenCalledWith(
      'file:///mock/folder/',
      'test-note.md',
      false,
    )
  })

  it('deletes note and navigates back', async () => {
    const { wrapper, router } = await mountComponent()
    const backSpy = vi.spyOn(router, 'back')

    const deleteButton = wrapper.find('[aria-label="Delete note"]')
    expect(deleteButton.exists()).toBe(true)

    await deleteButton.trigger('click')
    await flushPromises()

    expect(FolderPicker.deleteFile).toHaveBeenCalledWith({
      folderUri: 'file:///mock/folder/',
      fileName: 'test-note.md',
    })
    expect(backSpy).toHaveBeenCalled()
  })

  it('autosaves after title change', async () => {
    const { wrapper } = await mountComponent()

    const titleInput = wrapper.find('.title-input')
    await titleInput.setValue('Updated title')

    await vi.advanceTimersByTimeAsync(900)
    await flushPromises()
    await nextTick()

    expect(listFiles.saveNoteToFile).toHaveBeenCalled()
    const saveArg = vi.mocked(listFiles.saveNoteToFile).mock.calls[0][1]
    expect(saveArg.fileName).toBe('test-note.md')
    expect(saveArg.content).toContain('# Updated title')
  })

  it('autosaves after editor input', async () => {
    const { wrapper } = await mountComponent()

    const editor = wrapper.find('.editor-surface')
      ; (editor.element as HTMLDivElement).innerHTML = '<p>Hello world</p>'

    await editor.trigger('input')

    await vi.advanceTimersByTimeAsync(900)
    await flushPromises()
    await nextTick()

    expect(listFiles.saveNoteToFile).toHaveBeenCalled()
    const saveArg = vi.mocked(listFiles.saveNoteToFile).mock.calls[0][1]
    expect(saveArg.content).toContain('Hello world')
  })

  it('manual save persists changes and navigates back', async () => {
    const { wrapper, router } = await mountComponent()
    const backSpy = vi.spyOn(router, 'back')

    const titleInput = wrapper.find('.title-input')
    await titleInput.setValue('Updated before save')
    await nextTick()

    await wrapper.find('.save-button').trigger('click')
    await flushPromises()

    expect(listFiles.saveNoteToFile).toHaveBeenCalled()
    expect(backSpy).toHaveBeenCalled()
  })

  it('creates a new note on save when no file exists yet', async () => {
    const { wrapper } = await mountComponent('/note')

    const titleInput = wrapper.find('.title-input')
    await titleInput.setValue('Brand new')

    const editor = wrapper.find('.editor-surface')
      ; (editor.element as HTMLDivElement).innerHTML = '<p>Fresh content</p>'

    await wrapper.find('.save-button').trigger('click')
    await flushPromises()

    expect(listFiles.createNoteFile).toHaveBeenCalledWith(
      'file:///mock/folder/',
      expect.objectContaining({
        id: 'mock-uuid',
        content: expect.stringContaining('# Brand new'),
      }),
    )
  })

  it('back button flushes pending autosave before navigating', async () => {
    const { wrapper, router } = await mountComponent()

    const titleInput = wrapper.find('.title-input')
    await titleInput.setValue('Needs flush')

    await wrapper.find('.page-header-back').trigger('click')
    await flushPromises()

    expect(listFiles.saveNoteToFile).toHaveBeenCalled()
    // Check that the route is now '/'
    expect(router.currentRoute.value.fullPath).toBe('/')
  })

  it('executes formatting command from toolbar', async () => {
    const { wrapper } = await mountComponent()

    const toolbarButtons = wrapper.findAll('.toolbar-btn')
    expect(toolbarButtons.length).toBeGreaterThan(0)

    await toolbarButtons[4].trigger('mousedown')

    expect(document.execCommand).toHaveBeenCalled()
  })
})