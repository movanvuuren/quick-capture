
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { mount } from '@vue/test-utils';
import TasksView from '../../src/views/TasksView.vue';
import { createRouter, createWebHistory } from 'vue-router';
import * as settings from '../../src/lib/settings';
import * as quickTasksData from '../../src/lib/quickTasksData';
import { FolderPicker } from '../../src/plugins/folder-picker';
import { nextTick } from 'vue';

// Mock dependencies
vi.mock('../../src/lib/settings');
vi.mock('../../src/lib/quickTasksData');
vi.mock('../../src/plugins/folder-picker', () => ({
    FolderPicker: {
        readFile: vi.fn(),
        writeFile: vi.fn(),
    },
}));
vi.mock('@capacitor/haptics', () => ({
    Haptics: {
        impact: vi.fn(),
    },
    ImpactStyle: {
        Light: 'LIGHT',
        Medium: 'MEDIUM',
    }
}));


const routes = [
    { path: '/', name: 'home', component: { template: '<div>Home</div>' } },
    { path: '/tasks', name: 'tasks', component: TasksView },
];

const router = createRouter({
    history: createWebHistory(),
    routes,
});

const mockTasks = [
    { id: '1', fileName: 'test.md', lineIndex: 1, state: 'pending', body: 'Pending Task', presetId: 'preset1' },
    { id: '2', fileName: 'test.md', lineIndex: 2, state: 'done', body: 'Done Task', presetId: 'preset1' },
    { id: '3', fileName: 'test.md', lineIndex: 3, state: 'pending', body: 'Overdue Task', dueDate: '2020-01-01', presetId: 'preset1' },
];

const mockSettings = {
    baseFolderUri: 'file:///mock/folder/',
    sort: 'title-asc',
    showArchived: false,
    showCompleted: true,
    showFuture: true,
    quickTaskPresets: [
        { id: 'preset1', label: 'Default', tag: '#tasks', saveMode: 'single_file', fileName: 'tasks.md' },
        { id: 'preset2', label: 'Other', tag: '#other', saveMode: 'single_file', fileName: 'other.md' },
    ]
};

describe('TasksView.vue', () => {
    beforeEach(() => {
        vi.useFakeTimers();
        vi.clearAllMocks();

        vi.spyOn(settings, 'loadSettings').mockReturnValue(JSON.parse(JSON.stringify(mockSettings)));

        vi.spyOn(quickTasksData, 'loadQuickTasksFromFolder').mockResolvedValue(JSON.parse(JSON.stringify(mockTasks)));
        vi.spyOn(quickTasksData, 'updateTaskInFolder').mockResolvedValue(undefined);
        vi.spyOn(quickTasksData, 'invalidateQuickTaskCache').mockReturnValue(undefined);

        vi.mocked(FolderPicker.readFile).mockResolvedValue({ content: '' });
        vi.mocked(FolderPicker.writeFile).mockResolvedValue(undefined);

        const storage: Record<string, string> = {};
        vi.spyOn(window.localStorage, 'getItem').mockImplementation(key => storage[key] || null);
        vi.spyOn(window.localStorage, 'setItem').mockImplementation((key, value) => {
            storage[key] = value;
        });
    });

    afterEach(() => {
        vi.useRealTimers();
    });

    async function mountComponent() {
        router.push('/tasks');
        await router.isReady();
        const wrapper = mount(TasksView, {
            global: {
                plugins: [router],
                stubs: {
                    OptionSwitcher: true, // Stub the child component
                }
            },
        });
        await vi.runAllTimersAsync();
        await nextTick();
        return wrapper;
    }

    it('renders a loading state initially', async () => {
        vi.spyOn(quickTasksData, 'loadQuickTasksFromFolder').mockImplementation(() => new Promise(() => { })); // Never resolves
        const wrapper = await mountComponent();
        expect(wrapper.html()).toContain('Loading tasks...');
    });

    it('renders tasks when data is loaded', async () => {
        const wrapper = await mountComponent();
        const taskRows = wrapper.findAll('.task-row');
        // By default, only pending tasks are shown
        expect(taskRows).toHaveLength(2);
        expect(wrapper.html()).toContain('Pending Task');
        expect(wrapper.html()).toContain('Overdue Task');
        expect(wrapper.html()).not.toContain('Done Task');
    });

    it('renders an error message if loading fails', async () => {
        vi.spyOn(quickTasksData, 'loadQuickTasksFromFolder').mockRejectedValue(new Error('Failed to load'));
        const wrapper = await mountComponent();
        expect(wrapper.find('.error-text').exists()).toBe(true);
        expect(wrapper.html()).toContain('Failed to load tasks from your folder.');
    });

    it('renders an empty state when no tasks are found', async () => {
        vi.spyOn(quickTasksData, 'loadQuickTasksFromFolder').mockResolvedValue([]);
        const wrapper = await mountComponent();
        expect(wrapper.html()).toContain('No tasks found yet.');
    });

    it('adds a new task when quick add form is used', async () => {
        const wrapper = await mountComponent();

        await wrapper.find('.quick-task-input').setValue('New Task from Test');
        await wrapper.find('.quick-add-submit').trigger('click');

        await vi.runAllTimersAsync();

        expect(FolderPicker.writeFile).toHaveBeenCalled();
        const writeArgs = (FolderPicker.writeFile as any).mock.calls[0][0];
        expect(writeArgs.fileName).toBe('tasks.md');
        expect(writeArgs.content).toContain('- [ ] #tasks New Task from Test');
    });

    it('toggles task state when state button is clicked', async () => {
        const wrapper = await mountComponent();

        const taskRows = wrapper.findAll('.task-row');
        const pendingTaskRow = taskRows.find(row => row.text().includes('Pending Task'));

        expect(pendingTaskRow).toBeTruthy();

        await pendingTaskRow!.find('.state-button').trigger('click');

        await vi.runAllTimersAsync();
        await nextTick();

        expect(quickTasksData.updateTaskInFolder).toHaveBeenCalled();

        const updateArgs = vi.mocked(quickTasksData.updateTaskInFolder).mock.calls[0][0];
        expect(updateArgs.task.id).toBe('1');
        expect(updateArgs.nextState).toBe('done');
    });
});
