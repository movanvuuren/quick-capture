import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { nextTick } from 'vue'
import HabitsView from '../../src/views/HabitsView.vue'
import * as settings from '../../src/lib/settings'
import { FolderPicker } from '../../src/plugins/folder-picker'
import { WidgetSync } from '../../src/plugins/widget-sync'

vi.mock('../../src/lib/settings')
vi.mock('../../src/plugins/folder-picker', () => ({
  FolderPicker: {
    listFiles: vi.fn(),
    readFile: vi.fn(),
    writeFile: vi.fn(),
    deleteFile: vi.fn(),
  },
}))
vi.mock('../../src/plugins/widget-sync', () => ({
  WidgetSync: {
    syncHabits: vi.fn(),
  },
}))
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
  { path: '/', name: 'home', component: { template: '<div>Home</div>' } },
  { path: '/habits', name: 'habits', component: HabitsView },
  { path: '/settings', name: 'settings', component: { template: '<div>Settings</div>' } },
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

function habitConfigContent() {
  return `---
type: habit
id: reading
name: Reading
icon: 📚
period: day
targetCount: 2
targetDays: 1
unit: pages
allowSkip: true
scheduledDays: [1,2,3,4,5,6,7]
---`
}

function habitLogContent() {
  return `---
type: habit-log
month: 2026-03
d18: 1
d17: skip
d16: fail
---`
}

describe('HabitsView.vue', () => {
  beforeEach(() => {
    vi.useFakeTimers()
    vi.setSystemTime(new Date('2026-03-18T10:00:00'))
    vi.clearAllMocks()

    vi.spyOn(settings, 'loadSettings').mockReturnValue(
      JSON.parse(JSON.stringify(mockSettings)),
    )

    vi.stubGlobal('requestAnimationFrame', (cb: FrameRequestCallback) => {
      cb(0)
      return 1
    })

    vi.mocked(FolderPicker.listFiles).mockImplementation(async ({ relativePath, folderUri }: any) => {
      if (relativePath === 'habits') {
        return {
          files: [
            {
              name: 'reading.md',
              isFile: true,
              size: 100,
              lastModified: 123,
            },
          ],
        }
      }

      return {
        files: [],
      }
    })

    vi.mocked(FolderPicker.readFile).mockImplementation(async ({ fileName }: any) => {
      if (fileName === 'habits/reading.md') {
        return { content: habitConfigContent() }
      }

      if (fileName === 'habit-logs/reading-2026-03.md') {
        return { content: habitLogContent() }
      }

      throw new Error(`Unexpected file read: ${fileName}`)
    })

    vi.mocked(FolderPicker.writeFile).mockResolvedValue(undefined)
    vi.mocked(FolderPicker.deleteFile).mockResolvedValue(undefined)
    vi.mocked(WidgetSync.syncHabits).mockResolvedValue(undefined)

    vi.spyOn(window, 'prompt').mockReturnValue('🔥')
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    vi.useRealTimers()
  })

  async function mountComponent() {
    const router = makeRouter()
    router.push('/habits')
    await router.isReady()

    const wrapper = mount(HabitsView, {
      global: {
        plugins: [router],
        stubs: {
          Check: true,
          ChevronLeft: true,
          ChevronRight: true,
          Flame: true,
          SkipForward: true,
          X: true,
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

    expect(wrapper.text()).toContain('No folder selected')
    expect(wrapper.text()).toContain('Choose a system folder in Settings before using habits.')
  })

  it('renders loading state while habits are loading', async () => {
    vi.mocked(FolderPicker.listFiles).mockImplementation(() => new Promise(() => { }))

    const router = makeRouter()
    router.push('/habits')
    await router.isReady()

    const wrapper = mount(HabitsView, {
      global: {
        plugins: [router],
      },
    })

    await nextTick()

    expect(wrapper.find('[aria-label="Loading habits"]').exists()).toBe(true)
  })

  it('renders habits after loading', async () => {
    const { wrapper } = await mountComponent()

    expect(wrapper.find('.habit-card').exists()).toBe(true)
    expect(wrapper.text()).toContain('Reading')
    expect(wrapper.text()).toContain('Streak:')
    expect(wrapper.text()).toContain('Goal: 2 pages per day')
  })

  it('renders selected date count input from habit log', async () => {
    const { wrapper } = await mountComponent()

    const input = wrapper.find('.habit-count-input')
    expect(input.exists()).toBe(true)
    expect((input.element as HTMLInputElement).value).toBe('1')
  })

  it('marks selected habit as done', async () => {
    const { wrapper } = await mountComponent()

    const buttons = wrapper.findAll('.habit-actions button')
    expect(buttons.length).toBeGreaterThan(0)

    await buttons[0].trigger('click')

    await flushPromises()
    await nextTick()

    expect(FolderPicker.writeFile).toHaveBeenCalled()

    const call = vi.mocked(FolderPicker.writeFile).mock.calls[0][0]
    expect(call.fileName).toBe('habit-logs/reading-2026-03.md')
    expect(call.content).toContain('type: habit-log')
    expect(call.content).toContain('d18: 2')
  })

  it('marks selected habit as fail', async () => {
    const { wrapper } = await mountComponent()

    const buttons = wrapper.findAll('.habit-actions button')
    await buttons[1].trigger('click')

    await flushPromises()
    await nextTick()

    expect(FolderPicker.writeFile).toHaveBeenCalled()
    const call = vi.mocked(FolderPicker.writeFile).mock.calls[0][0]
    expect(call.content).toContain('d18: fail')
  })

  it('marks selected habit as skip', async () => {
    const { wrapper } = await mountComponent()

    const buttons = wrapper.findAll('.habit-actions button')
    await buttons[2].trigger('click')

    await flushPromises()
    await nextTick()

    expect(FolderPicker.writeFile).toHaveBeenCalled()
    const call = vi.mocked(FolderPicker.writeFile).mock.calls[0][0]
    expect(call.content).toContain('d18: skip')
  })

  it('clears selected habit value', async () => {
    const { wrapper } = await mountComponent()

    const buttons = wrapper.findAll('.habit-actions button')
    const clearButton = buttons.find(btn => btn.text().includes('Clear'))

    expect(clearButton).toBeTruthy()

    await clearButton!.trigger('click')

    await flushPromises()
    await nextTick()

    expect(FolderPicker.writeFile).toHaveBeenCalled()
    const call = vi.mocked(FolderPicker.writeFile).mock.calls[0][0]
    expect(call.content).not.toContain('d18:')
  })

  it('updates value from number input', async () => {
    const { wrapper } = await mountComponent()

    const input = wrapper.find('.habit-count-input')
    await input.setValue('3')
    await input.trigger('change')

    await flushPromises()
    await nextTick()

    expect(FolderPicker.writeFile).toHaveBeenCalled()
    const call = vi.mocked(FolderPicker.writeFile).mock.calls[0][0]
    expect(call.content).toContain('d18: 3')
  })

  it('edits the habit icon', async () => {
    const { wrapper } = await mountComponent()

    const iconButton = wrapper.find('.habit-icon-button')
    expect(iconButton.exists()).toBe(true)

    await iconButton.trigger('click')

    await flushPromises()
    await nextTick()

    expect(window.prompt).toHaveBeenCalled()
    expect(FolderPicker.writeFile).toHaveBeenCalled()

    const call = vi.mocked(FolderPicker.writeFile).mock.calls[0][0]
    expect(call.fileName).toBe('habits/reading.md')
    expect(call.content).toContain('icon: "🔥"')
    expect(WidgetSync.syncHabits).toHaveBeenCalled()
  })

  it('changes visible month when previous month button is clicked', async () => {
    const { wrapper } = await mountComponent()

    const monthLabelBefore = wrapper.find('.heatmap-month-label').text()
    expect(monthLabelBefore).toContain('March')

    const monthButtons = wrapper.findAll('.heatmap-nav-button')
    await monthButtons[0].trigger('click')

    await flushPromises()
    await nextTick()

    const monthLabelAfter = wrapper.find('.heatmap-month-label').text()
    expect(monthLabelAfter).toContain('February')
  })

  it('navigates to settings from empty state button', async () => {
    vi.spyOn(settings, 'loadSettings').mockReturnValue(
      JSON.parse(JSON.stringify(mockSettings)),
    )
    vi.mocked(FolderPicker.listFiles).mockResolvedValue({ files: [] })

    const { wrapper, router } = await mountComponent()
    const pushSpy = vi.spyOn(router, 'push')

    const settingsButton = wrapper.findAll('button').find(btn => btn.text().includes('Open Settings'))
    expect(settingsButton).toBeTruthy()

    await settingsButton!.trigger('click')

    expect(pushSpy).toHaveBeenCalledWith('/settings')
  })

  it('creates example habit when no habits exist', async () => {
    vi.mocked(FolderPicker.listFiles).mockImplementation(async ({ relativePath }: any) => {
      if (relativePath === 'habits') {
        return { files: [] }
      }
      return { files: [] }
    })

    const { wrapper } = await mountComponent()

    const createButton = wrapper.findAll('button').find(btn => btn.text().includes('Create Example Habit'))
    expect(createButton).toBeTruthy()

    await createButton!.trigger('click')

    await flushPromises()
    await nextTick()

    expect(FolderPicker.writeFile).toHaveBeenCalled()
    const call = vi.mocked(FolderPicker.writeFile).mock.calls[0][0]
    expect(call.fileName).toContain('habit-example-daily-walk')
    expect(call.content).toContain('type: habit')
    expect(call.content).toContain('name: "Daily Walk"')
  })
})