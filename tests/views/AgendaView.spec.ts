import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createRouter, createMemoryHistory } from 'vue-router'
import { nextTick } from 'vue'
import AgendaView from '../../src/views/AgendaView.vue'
import { FolderPicker } from '../../src/plugins/folder-picker'
import * as settings from '../../src/lib/settings'

vi.mock('../../src/lib/settings')
vi.mock('../../src/plugins/folder-picker', () => ({
  FolderPicker: {
    listFiles: vi.fn(),
    readFile: vi.fn(),
  },
}))

const routes = [
  { path: '/', name: 'home', component: { template: '<div>Home</div>' } },
  { path: '/agenda', name: 'agenda', component: AgendaView },
  { path: '/tasks', name: 'tasks', component: { template: '<div>Tasks</div>' } },
  { path: '/habits', name: 'habits', component: { template: '<div>Habits</div>' } },
]

function makeRouter() {
  return createRouter({
    history: createMemoryHistory(),
    routes,
  })
}

const mockSettings = {
  baseFolderUri: 'file:///mock/folder/',
  listFileName: 'tasks.md',
  quickTaskPresets: [
    { id: 'preset1', label: 'Default', tag: '#tasks', saveMode: 'single_file', fileName: 'tasks.md' },
  ],
}

function todayIso() {
  const now = new Date()
  return `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}-${String(now.getDate()).padStart(2, '0')}`
}

describe('AgendaView.vue', () => {
  beforeEach(() => {
    vi.useFakeTimers()
    vi.setSystemTime(new Date('2026-03-18T10:00:00'))

    vi.clearAllMocks()

    vi.spyOn(settings, 'loadSettings').mockReturnValue(JSON.parse(JSON.stringify(mockSettings)))

    vi.mocked(FolderPicker.listFiles).mockImplementation(async ({ relativePath }: any) => {
      if (relativePath === 'habits') {
        return {
          files: [
            { name: 'reading.md', isFile: true },
          ],
        }
      }

      return {
        files: [
          { name: 'tasks.md', isFile: true },
          { name: 'notes.md', isFile: true },
        ],
      }
    })

    vi.mocked(FolderPicker.readFile).mockImplementation(async ({ fileName }: any) => {
      if (fileName === 'tasks.md') {
        return {
          content: `---
type: task
---

- [ ] #tasks Task due today 📅 2026-03-18
- [ ] #tasks Overdue task 📅 2026-03-17
- [x] #tasks Done task 📅 2026-03-18
- [f] #tasks High priority task 📅 2026-03-18
`,
        }
      }

      if (fileName === 'notes.md') {
        return {
          content: `# random note`,
        }
      }

      if (fileName === 'habits/reading.md') {
        return {
          content: `---
type: habit
id: reading
name: Reading
icon: 📚
targetCount: 2
scheduledDays: [1,2,3,4,5,6,7]
---`,
        }
      }

      if (fileName === 'habit-logs/reading-2026-03.md') {
        return {
          content: `---
type: habit-log
month: 2026-03
d18: 1
---`,
        }
      }

      throw new Error(`Unexpected file read: ${fileName}`)
    })
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  async function mountComponent() {
    const router = makeRouter()
    router.push('/agenda')
    await router.isReady()

    const wrapper = mount(AgendaView, {
      global: {
        plugins: [router],
        stubs: {
          CalendarDays: true,
          ChevronLeft: true,
          ChevronRight: true,
        },
      },
    })

    await flushPromises()
    await nextTick()
    await flushPromises()
    await nextTick()

    return { wrapper, router }
  }

  it('renders empty state when no folder is selected', async () => {
    vi.spyOn(settings, 'loadSettings').mockReturnValue({
      ...mockSettings,
      baseFolderUri: '',
    })

    const { wrapper } = await mountComponent()

    expect(wrapper.html()).toContain('No folder selected')
    expect(wrapper.html()).toContain('Choose a folder in Settings first.')
  })

  it('renders tasks due for the selected day', async () => {
    const { wrapper } = await mountComponent()

    expect(wrapper.html()).toContain('Task due today')
    expect(wrapper.html()).toContain('High priority task')
    expect(wrapper.html()).not.toContain('Done task')
  })

  it('renders overdue tasks for today', async () => {
    const { wrapper } = await mountComponent()

    const overdueCard = wrapper.find('.overdue-card')
    expect(overdueCard.exists()).toBe(true)
    expect(overdueCard.text()).toContain('Overdue task')
    expect(overdueCard.text()).toContain('2026-03-17')
  })

  it('renders incomplete habits for the selected day', async () => {
    const { wrapper } = await mountComponent()

    const habitsCard = wrapper.find('.day-habits-card')
    expect(habitsCard.exists()).toBe(true)
    expect(habitsCard.text()).toContain('Reading')
    expect(habitsCard.text()).toContain('1/2')
  })

  it('navigates to tasks view when a task is clicked', async () => {
    const { wrapper, router } = await mountComponent()
    const pushSpy = vi.spyOn(router, 'push')

    const taskButtons = wrapper.findAll('.day-tasks-card .task-item')
    const target = taskButtons.find(btn => btn.text().includes('Task due today'))

    expect(target).toBeTruthy()

    await target!.trigger('click')

    expect(pushSpy).toHaveBeenCalledWith(
      expect.objectContaining({
        name: 'tasks',
        query: expect.objectContaining({
          highlight: expect.stringContaining('tasks.md:'),
          pulse: expect.any(String),
        }),
      }),
    )
  })

  it('navigates to habits view when a habit is clicked', async () => {
    const { wrapper, router } = await mountComponent()
    const pushSpy = vi.spyOn(router, 'push')

    const habitButtons = wrapper.findAll('.habit-item')
    const target = habitButtons.find(btn => btn.text().includes('Reading'))

    expect(target).toBeTruthy()

    await target!.trigger('click')

    expect(pushSpy).toHaveBeenCalledWith({
      path: '/habits',
      query: expect.objectContaining({
        habit: 'habits/reading.md',
        date: '2026-03-18',
        pulse: expect.any(String),
      }),
    })
  })

  it('changes month when next month button is clicked', async () => {
    const { wrapper } = await mountComponent()

    expect(wrapper.find('.month-label').text()).toContain('March')

    const buttons = wrapper.findAll('.nav-btn')
    await buttons[1].trigger('click')

    expect(wrapper.find('.month-label').text()).toContain('April')
  })
})