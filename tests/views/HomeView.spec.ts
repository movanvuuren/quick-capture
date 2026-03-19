import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { computed, nextTick, ref } from 'vue'
import { createMemoryHistory, createRouter } from 'vue-router'
import HomeView from '../../src/views/HomeView.vue'
import { FolderPicker } from '../../src/plugins/folder-picker'
import * as listFiles from '../../src/lib/listFiles'
import { useDashboardData } from '../../src/lib/useDashboardData'
import { defaultSettings, saveSettings } from '../../src/lib/settings'

vi.mock('../../src/lib/useDashboardData')
vi.mock('../../src/plugins/folder-picker', () => ({
  FolderPicker: {
    deleteFile: vi.fn(),
  },
}))
vi.mock('../../src/lib/listFiles', async () => {
  const actual = await vi.importActual<typeof import('../../src/lib/listFiles')>('../../src/lib/listFiles')
  return {
    ...actual,
    createListFile: vi.fn(),
    saveListToFile: vi.fn(),
    setFilePinned: vi.fn(),
  }
})
vi.mock('@capacitor/haptics', () => ({
  Haptics: {
    impact: vi.fn(),
  },
  ImpactStyle: {
    Light: 'LIGHT',
    Medium: 'MEDIUM',
  },
}))

const routes = [
  // { path: '/', name: 'home', component: HomeView },
  { path: '/search', name: 'search', component: { template: '<div>Search</div>' } },
  { path: '/agenda', name: 'agenda', component: { template: '<div>Agenda</div>' } },
  { path: '/settings', name: 'settings', component: { template: '<div>Settings</div>' } },
  { path: '/tasks', name: 'tasks', component: { template: '<div>Tasks</div>' } },
  { path: '/note', name: 'note', component: { template: '<div>Note</div>' } },
  { path: '/habits', name: 'habits', component: { template: '<div>Habits</div>' } },
  { path: '/list/:id', name: 'list', component: { template: '<div>List</div>' } },
  { path: '/task-list/:id', name: 'task-list', component: { template: '<div>Task List</div>' } },
  { path: '/note/:id', name: 'note-file', component: { template: '<div>Note File</div>' } },
]

function makeRouter() {
  return createRouter({
    history: createMemoryHistory(),
    routes,
  })
}

function makeDashboardMock() {
  const settings = ref({
    theme: 'light',
    quickTaskPresets: [
      {
        id: 'preset1',
        label: 'Default',
        tag: '#tasks',
        saveMode: 'single_file',
        fileName: 'tasks.md',
      },
    ],
    listFileName: 'tasks.md',
  })

  const baseFolderUri = ref('file:///mock/folder/')
  const lists = ref([
    {
      id: 'list-1',
      fileName: 'shopping-list.md',
      title: 'Shopping List',
      items: [
        { text: 'Milk', state: 'pending' },
        { text: 'Bread', state: 'done' },
      ],
      pinned: false,
      type: 'list',
      updated: '2026-03-18T08:00:00Z',
      created: '2026-03-17T08:00:00Z',
    },
  ])

  const tasks = ref([
    {
      id: 'task-1',
      fileName: 'tasks.md',
      title: 'Work Tasks',
      items: [
        { text: '#tasks Finish report', state: 'pending', isHighPriority: true },
        { text: '#tasks Email supplier', state: 'done' },
      ],
      pinned: false,
      type: 'task',
      updated: '2026-03-18T09:00:00Z',
      created: '2026-03-17T09:00:00Z',
    },
  ])

  const notes = ref([
    {
      id: 'note-1',
      name: 'meeting-notes.md',
      title: 'Meeting Notes',
      preview: 'Project alpha discussion',
      previewMarkdown: '**Important** update',
      pinned: false,
      type: 'note',
      updated: '2026-03-18T10:00:00Z',
      created: '2026-03-17T10:00:00Z',
    },
  ])

  const isLoading = ref(false)
  const error = ref('')

  return {
    settings,
    baseFolderUri,
    lists,
    tasks,
    notes,
    isLoading,
    error,
    sortPinnedTodos: vi.fn((items) => [...items].sort((a, b) => Number(b.pinned) - Number(a.pinned))),
    sortPinnedFiles: vi.fn((items) => [...items].sort((a, b) => Number(b.pinned) - Number(a.pinned))),
    syncDashboardCache: vi.fn(),
    syncSettingsAndRefresh: vi.fn().mockResolvedValue(undefined),
  }
}

describe('HomeView.vue', () => {
  let dashboardMock: ReturnType<typeof makeDashboardMock>

  beforeEach(() => {
    vi.clearAllMocks()
    dashboardMock = makeDashboardMock()

    vi.mocked(useDashboardData).mockReturnValue(dashboardMock as any)
    vi.mocked(FolderPicker.deleteFile).mockResolvedValue(undefined)
    vi.mocked(listFiles.createListFile).mockResolvedValue({
      id: 'new-list-id',
      fileName: 'List 2026-03-18.md',
      title: 'List 2026-03-18',
      items: [{ text: '', state: 'pending' }],
      pinned: false,
      type: 'list',
    } as any)
    vi.mocked(listFiles.saveListToFile).mockResolvedValue(undefined)
    vi.mocked(listFiles.setFilePinned).mockResolvedValue(undefined)

    vi.spyOn(window, 'confirm').mockReturnValue(true)
  })

  afterEach(() => {
    vi.restoreAllMocks()
    localStorage.clear()
  })

  async function mountComponent() {
    const router = makeRouter()
    router.push('/')
    await router.isReady()

    const wrapper = mount(HomeView, {
      global: {
        plugins: [router],
        stubs: {
          CalendarDays: true,
          CheckSquare: true,
          FileText: true,
          Flame: true,
          List: true,
          Plus: false,
          Search: true,
          Settings: true,
          Trash2: true,
          PinToggleButton: {
            props: ['pinned', 'itemLabel', 'size'],
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

  it('renders loading state', async () => {
    dashboardMock.isLoading.value = true

    const { wrapper } = await mountComponent()

    expect(wrapper.text()).toContain('Loading dashboard...')
  })

  it('renders error state', async () => {
    dashboardMock.error.value = 'Something went wrong'

    const { wrapper } = await mountComponent()

    expect(wrapper.text()).toContain('Something went wrong')
  })

  it('renders no-folder state', async () => {
    dashboardMock.baseFolderUri.value = ''

    const { wrapper } = await mountComponent()

    expect(wrapper.text()).toContain('No Folder Selected')
    expect(wrapper.text()).toContain('Please select a folder in settings to begin.')
  })

  it('renders empty folder state', async () => {
    dashboardMock.lists.value = []
    dashboardMock.tasks.value = []
    dashboardMock.notes.value = []

    const { wrapper } = await mountComponent()

    expect(wrapper.text()).toContain('Folder is Empty')
    expect(wrapper.text()).toContain('Create a new list, task file, or note to get started.')
  })

  it('renders dashboard cards', async () => {
    const { wrapper } = await mountComponent()

    expect(wrapper.text()).toContain('Shopping List')
    expect(wrapper.text()).toContain('Work Tasks')
    expect(wrapper.text()).toContain('Meeting Notes')
  })

  it('shows note preview html', async () => {
    const { wrapper } = await mountComponent()

    expect(wrapper.html()).toContain('Important')
  })

  it('filters to lists only', async () => {
    const { wrapper } = await mountComponent()

    const buttons = wrapper.findAll('.filter-chip')
    const listsFilter = buttons.find(btn => btn.text() === 'Lists')

    expect(listsFilter).toBeTruthy()
    await listsFilter!.trigger('click')
    await nextTick()

    expect(wrapper.text()).toContain('Shopping List')
    expect(wrapper.text()).not.toContain('Work Tasks')
    expect(wrapper.text()).not.toContain('Meeting Notes')
  })

  it('resets filters with All button', async () => {
    const { wrapper } = await mountComponent()

    const buttons = wrapper.findAll('.filter-chip')
    const listsFilter = buttons.find(btn => btn.text() === 'Lists')
    const allFilter = buttons.find(btn => btn.text() === 'All')

    await listsFilter!.trigger('click')
    await nextTick()

    expect(wrapper.text()).toContain('Shopping List')
    expect(wrapper.text()).not.toContain('Work Tasks')

    await allFilter!.trigger('click')
    await nextTick()

    expect(wrapper.text()).toContain('Shopping List')
    expect(wrapper.text()).toContain('Work Tasks')
    expect(wrapper.text()).toContain('Meeting Notes')
  })

  it('navigates to search and settings from top actions', async () => {
    const { wrapper, router } = await mountComponent()
    const pushSpy = vi.spyOn(router, 'push')

    const buttons = wrapper.findAll('.top-actions button')

    await buttons[0].trigger('click')
    expect(pushSpy).toHaveBeenCalledWith('/search')

    await buttons[1].trigger('click')
    expect(pushSpy).toHaveBeenCalledWith('/settings')
  })

  it('navigates to agenda when agenda button is clicked', async () => {
    const { wrapper, router } = await mountComponent()
    const pushSpy = vi.spyOn(router, 'push')

    const calendarButton = wrapper.find('button[aria-label="Agenda"]')

    expect(calendarButton.exists()).toBeTruthy()
    await calendarButton.trigger('click')

    expect(pushSpy).toHaveBeenCalledWith('/agenda')
  })

  it('navigates to a list when list card is clicked', async () => {
    const { wrapper, router } = await mountComponent()
    const pushSpy = vi.spyOn(router, 'push')

    const cards = wrapper.findAll('.collection-card')
    const listCard = cards.find(card => card.text().includes('Shopping List'))

    expect(listCard).toBeTruthy()
    await listCard!.trigger('click')

    expect(pushSpy).toHaveBeenCalledWith('/list/shopping-list.md')
  })

  it('navigates to tasks when task card is clicked', async () => {
    const { wrapper, router } = await mountComponent()
    const pushSpy = vi.spyOn(router, 'push')

    const cards = wrapper.findAll('.collection-card')
    const taskCard = cards.find(card => card.text().includes('Work Tasks'))

    expect(taskCard).toBeTruthy()
    await taskCard!.trigger('click')

    expect(pushSpy).toHaveBeenCalledWith({
      path: '/tasks',
      query: { preset: 'preset1' },
    })
  })

  it('navigates to note when note card is clicked', async () => {
    const { wrapper, router } = await mountComponent()
    const pushSpy = vi.spyOn(router, 'push')

    const cards = wrapper.findAll('.collection-card')
    const noteCard = cards.find(card => card.text().includes('Meeting Notes'))

    expect(noteCard).toBeTruthy()
    await noteCard!.trigger('click')

    expect(pushSpy).toHaveBeenCalledWith('/note/meeting-notes.md')
  })

  it('creates a new list from quick add', async () => {
    saveSettings({ ...defaultSettings, baseFolderUri: 'file:///mock/folder/' })
    const { wrapper, router } = await mountComponent()
    const pushSpy = vi.spyOn(router, 'push')

    const createListButton = wrapper.find('button[aria-label="Create list"]')

    await createListButton.trigger('click')

    expect(listFiles.createListFile).toHaveBeenCalled()
    expect(pushSpy).toHaveBeenCalledWith({
      path: '/list/List%202026-03-18.md',
      query: {
        draft: '1',
        draftTitle: 'List 2026-03-18',
      },
    })
  })

  it('navigates from quick add buttons', async () => {
    const { wrapper, router } = await mountComponent()
    const pushSpy = vi.spyOn(router, 'push')

    await wrapper.find('button[aria-label="Quick tasks"]').trigger('click')
    expect(pushSpy).toHaveBeenCalledWith('/tasks')

    await wrapper.find('button[aria-label="Create note"]').trigger('click')
    expect(pushSpy).toHaveBeenCalledWith('/note')

    await wrapper.find('button[aria-label="Habits"]').trigger('click')
    expect(pushSpy).toHaveBeenCalledWith('/habits')
  })

  it('deletes a note card', async () => {
    const { wrapper } = await mountComponent()

    const deleteButtons = wrapper.findAll('.delete-icon-button')
    const noteDeleteButton = deleteButtons[0]

    await noteDeleteButton.trigger('click')

    expect(window.confirm).toHaveBeenCalled()
    expect(FolderPicker.deleteFile).toHaveBeenCalled()
  })

  it('toggles pin for note card', async () => {
    const { wrapper } = await mountComponent()

    const cards = wrapper.findAll('.collection-card')
    const noteCardIndex = cards.findIndex(card => card.text().includes('Meeting Notes'))
    expect(noteCardIndex).toBeGreaterThanOrEqual(0)

    const pinButtons = wrapper.findAll('.pin-toggle-stub')
    await pinButtons[noteCardIndex].trigger('click')

    expect(listFiles.setFilePinned).toHaveBeenCalledWith(
      'file:///mock/folder/',
      'meeting-notes.md',
      true,
    )
  })

  it('toggles pin for list card', async () => {
    const { wrapper } = await mountComponent()

    const cards = wrapper.findAll('.collection-card')
    const listCardIndex = cards.findIndex(card => card.text().includes('Shopping List'))
    expect(listCardIndex).toBeGreaterThanOrEqual(0)

    const pinButtons = wrapper.findAll('.pin-toggle-stub')
    await pinButtons[listCardIndex].trigger('click')

    expect(listFiles.saveListToFile).toHaveBeenCalled()
  })

  it('goes to settings when no folder state button is clicked', async () => {
    dashboardMock.baseFolderUri.value = ''

    const { wrapper, router } = await mountComponent()
    const pushSpy = vi.spyOn(router, 'push')

    const settingsButton = wrapper.findAll('button').find(btn => btn.text().includes('Go to Settings'))
    expect(settingsButton).toBeTruthy()

    await settingsButton!.trigger('click')

    expect(pushSpy).toHaveBeenCalledWith('/settings')
  })

  it('goes to settings when trying to create list without base folder', async () => {
    saveSettings({ ...defaultSettings, baseFolderUri: '' })
    dashboardMock.baseFolderUri.value = ''

    const { wrapper, router } = await mountComponent()
    const pushSpy = vi.spyOn(router, 'push')

    const createListButton = wrapper.find('button[aria-label="Create list"]')
    expect(createListButton.exists()).toBe(true)

    await createListButton.trigger('click')

    expect(pushSpy).toHaveBeenCalledWith('/settings')
  })
})