import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { nextTick } from 'vue'
import SearchView from '../../src/views/SearchView.vue'
import * as settings from '../../src/lib/settings'
import * as listFiles from '../../src/lib/listFiles'

vi.mock('../../src/lib/settings')
vi.mock('../../src/lib/listFiles')

const routes = [
  { path: '/', name: 'home', component: { template: '<div>Home</div>' } },
  { path: '/search', name: 'search', component: SearchView },
  { path: '/tasks', name: 'tasks', component: { template: '<div>Tasks</div>' } },
  { path: '/list/:id', name: 'list', component: { template: '<div>List</div>' } },
  { path: '/note/:id', name: 'note', component: { template: '<div>Note</div>' } },
]

function makeRouter() {
  return createRouter({
    history: createMemoryHistory(),
    routes,
  })
}

const mockSettings = {
  baseFolderUri: 'file:///mock/folder/',
  quickTaskPresets: [],
  listFileName: 'tasks.md',
}

const mockDashboard = {
  files: [
    {
      name: 'tasks.md',
      title: 'Work Tasks',
      preview: 'Pending work items',
      type: 'task',
    },
    {
      name: 'shopping-list.md',
      title: 'Shopping List',
      preview: 'Milk bread eggs',
      type: 'list',
    },
    {
      name: 'meeting-notes.md',
      title: 'Meeting Notes',
      preview: 'Project alpha discussion',
      type: 'note',
    },
  ],
  lists: [
    {
      fileName: 'shopping-list.md',
      items: [
        { text: 'Milk' },
        { text: 'Bread' },
      ],
    },
  ],
  tasks: [
    {
      fileName: 'tasks.md',
      items: [
        { text: 'Finish report' },
        { text: 'Email supplier' },
      ],
    },
  ],
}

describe('SearchView.vue', () => {
  beforeEach(() => {
    vi.clearAllMocks()

    vi.spyOn(settings, 'loadSettings').mockReturnValue(
      JSON.parse(JSON.stringify(mockSettings)),
    )

    vi.spyOn(listFiles, 'loadDashboardData').mockResolvedValue(
      JSON.parse(JSON.stringify(mockDashboard)),
    )
  })

  async function mountComponent() {
    const router = makeRouter()
    router.push('/search')
    await router.isReady()

    const wrapper = mount(SearchView, {
      global: {
        plugins: [router],
        stubs: {
          CheckSquare: true,
          FileText: true,
          List: true,
        },
      },
    })

    await flushPromises()
    await nextTick()
    await flushPromises()
    await nextTick()

    return { wrapper, router }
  }

  it('renders no-folder state when no folder is selected', async () => {
    vi.spyOn(settings, 'loadSettings').mockReturnValue({
      ...mockSettings,
      baseFolderUri: '',
    })

    const { wrapper } = await mountComponent()

    expect(wrapper.text()).toContain('No folder selected')
    expect(wrapper.text()).toContain('Choose a folder in Settings to enable search.')
  })

  it('renders loading state while dashboard is loading', async () => {
    vi.spyOn(listFiles, 'loadDashboardData').mockImplementation(() => new Promise(() => { }))

    const router = makeRouter()
    router.push('/search')
    await router.isReady()

    const wrapper = mount(SearchView, {
      global: {
        plugins: [router],
      },
    })

    await nextTick()
    await nextTick()

    expect(wrapper.find('.loading-inline').exists()).toBe(true)
  })

  it('renders empty prompt before typing', async () => {
    const { wrapper } = await mountComponent()

    expect(wrapper.text()).toContain('Start typing to search across all your files.')
  })

  it('shows task results when matching task content', async () => {
    const { wrapper } = await mountComponent()

    const input = wrapper.find('.search-input')
    await input.setValue('report')
    await nextTick()

    expect(wrapper.text()).toContain('Tasks')
    expect(wrapper.text()).toContain('Work Tasks')
    expect(wrapper.text()).not.toContain('Shopping List')
    expect(wrapper.text()).not.toContain('Meeting Notes')
  })

  it('shows list results when matching list content', async () => {
    const { wrapper } = await mountComponent()

    const input = wrapper.find('.search-input')
    await input.setValue('milk')
    await nextTick()

    expect(wrapper.text()).toContain('Lists')
    expect(wrapper.text()).toContain('Shopping List')
    expect(wrapper.text()).not.toContain('Work Tasks')
  })

  it('shows note results when matching note preview/title', async () => {
    const { wrapper } = await mountComponent()

    const input = wrapper.find('.search-input')
    await input.setValue('alpha')
    await nextTick()

    expect(wrapper.text()).toContain('Notes')
    expect(wrapper.text()).toContain('Meeting Notes')
    expect(wrapper.text()).not.toContain('Shopping List')
  })

  it('shows grouped mixed results', async () => {
    const { wrapper } = await mountComponent()

    const input = wrapper.find('.search-input')
    await input.setValue('project')
    await nextTick()

    expect(wrapper.text()).toContain('Notes')
    expect(wrapper.text()).toContain('Meeting Notes')
  })

  it('shows no results state when nothing matches', async () => {
    const { wrapper } = await mountComponent()

    const input = wrapper.find('.search-input')
    await input.setValue('zzzzzz')
    await nextTick()

    expect(wrapper.text()).toContain('No results')
    expect(wrapper.text()).toContain('Nothing matched "zzzzzz".')
  })

  it('renders an error state if loading fails', async () => {
    vi.spyOn(listFiles, 'loadDashboardData').mockRejectedValue(new Error('boom'))

    const { wrapper } = await mountComponent()

    expect(wrapper.text()).toContain('Could not load files.')
  })

  it('navigates to tasks when clicking a task result', async () => {
    const { wrapper, router } = await mountComponent()
    const pushSpy = vi.spyOn(router, 'push')

    await wrapper.find('.search-input').setValue('report')
    await nextTick()

    const buttons = wrapper.findAll('.result-item')
    const taskButton = buttons.find(btn => btn.text().includes('Work Tasks'))

    expect(taskButton).toBeTruthy()

    await taskButton!.trigger('click')

    expect(pushSpy).toHaveBeenCalledWith('/tasks')
  })

  it('navigates to list when clicking a list result', async () => {
    const { wrapper, router } = await mountComponent()
    const pushSpy = vi.spyOn(router, 'push')

    await wrapper.find('.search-input').setValue('milk')
    await nextTick()

    const buttons = wrapper.findAll('.result-item')
    const listButton = buttons.find(btn => btn.text().includes('Shopping List'))

    expect(listButton).toBeTruthy()

    await listButton!.trigger('click')

    expect(pushSpy).toHaveBeenCalledWith('/list/shopping-list.md')
  })

  it('navigates to note when clicking a note result', async () => {
    const { wrapper, router } = await mountComponent()
    const pushSpy = vi.spyOn(router, 'push')

    await wrapper.find('.search-input').setValue('alpha')
    await nextTick()

    const buttons = wrapper.findAll('.result-item')
    const noteButton = buttons.find(btn => btn.text().includes('Meeting Notes'))

    expect(noteButton).toBeTruthy()

    await noteButton!.trigger('click')

    expect(pushSpy).toHaveBeenCalledWith('/note/meeting-notes.md')
  })

  it('goes back when back button is clicked', async () => {
    const { wrapper, router } = await mountComponent()
    expect(wrapper.find('.back-button').attributes('aria-label')).toBe('Go home')

    await wrapper.find('.back-button').trigger('click')
    await flushPromises()

    expect(router.currentRoute.value.fullPath).toBe('/')
  })

  it('focuses the search input on mount', async () => {
    const focusSpy = vi.spyOn(HTMLInputElement.prototype, 'focus').mockImplementation(() => { })

    await mountComponent()

    expect(focusSpy).toHaveBeenCalled()
  })
})
