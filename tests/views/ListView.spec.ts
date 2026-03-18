import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { mount } from '@vue/test-utils';
import ListView from '../../src/views/ListView.vue';
import { createRouter, createWebHistory } from 'vue-router';
import * as settings from '../../src/lib/settings';
import * as listFiles from '../../src/lib/listFiles';
import { StoredTodoList } from '../../src/lib/lists';
import { nextTick } from 'vue';

// Mock dependencies
vi.mock('../../src/lib/settings');
vi.mock('../../src/lib/listFiles');

const routes = [
  { path: '/', name: 'home', component: { template: '<div>Home</div>' } },
  { path: '/list/:id', name: 'list', component: ListView },
];

const router = createRouter({
  history: createWebHistory(),
  routes,
});

const mockList: StoredTodoList = {
  id: '123',
  fileName: 'test-list.json',
  title: 'Test List',
  items: [
    { text: 'Item 1', state: 'pending' },
    { text: 'Item 2', state: 'done' },
  ],
  created: '2023-01-01T12:00:00.000Z',
  updated: '2023-01-01T12:00:00.000Z',
  pinned: false,
  type: 'list',
};

describe('ListView.vue', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    vi.clearAllMocks();

    vi.spyOn(settings, 'loadSettings').mockReturnValue({
      baseFolderUri: 'file:///mock/folder/',
      sort: 'title-asc',
      showArchived: false,
      showCompleted: true,
      showFuture: true,
    });

    const saveSpy = vi.spyOn(listFiles, 'saveListToFile').mockImplementation(async (uri, list) => {
        return { ...list, updated: new Date().toISOString() } as StoredTodoList
    });
    vi.spyOn(listFiles, 'loadTodoFileByName').mockResolvedValue(JSON.parse(JSON.stringify(mockList)));
    vi.spyOn(listFiles, 'deleteListFile').mockResolvedValue(undefined);
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it('renders a loading state initially', async () => {
    vi.spyOn(listFiles, 'loadTodoFileByName').mockImplementation(() => new Promise(() => {})); // Never resolves
    router.push('/list/test-list.json');
    await router.isReady();

    const wrapper = mount(ListView, {
      global: {
        plugins: [router],
      },
    });

    expect(wrapper.find('.empty-state--loading').exists()).toBe(true);
  });

  it('renders list details when data is loaded', async () => {
    router.push('/list/test-list.json');
    await router.isReady();

    const wrapper = mount(ListView, {
      global: {
        plugins: [router],
      },
    });
    
    await vi.runAllTimersAsync();
    await nextTick();

    expect(wrapper.find('[data-testid="list-title-input"]').element.value).toBe('Test List');
    const items = wrapper.findAll('.item-row');
    expect(items).toHaveLength(2);

    const textareas = wrapper.findAll('.item-input');
    const values = textareas.map(t => t.element.value);
    expect(values).toContain('Item 1');
    expect(values).toContain('Item 2');
  });

  it('shows an empty state if the list is not found', async () => {
    vi.spyOn(listFiles, 'loadTodoFileByName').mockResolvedValue(undefined);
    router.push('/list/non-existent-list.json');
    await router.isReady();

    const wrapper = mount(ListView, {
      global: {
        plugins: [router],
      },
    });

    await vi.runAllTimersAsync();
    await nextTick();

    expect(wrapper.find('.empty-state').exists()).toBe(true);
    expect(wrapper.html()).toContain('This item is no longer available');
  });

  it('queues a save when the title is edited', async () => {
    router.push('/list/test-list.json');
    await router.isReady();
    const wrapper = mount(ListView, { global: { plugins: [router] } });
    await vi.runAllTimersAsync();
    await nextTick();

    const titleInput = wrapper.find('[data-testid="list-title-input"]');
    await titleInput.setValue('New Title');
    
    vi.runAllTimers();

    expect(listFiles.saveListToFile).toHaveBeenCalled();
    const savedList = (listFiles.saveListToFile as any).mock.calls[0][1];
    expect(savedList.title).toBe('New Title');
  });

  it('adds a new item when "+ Add item" is clicked', async () => {
    router.push('/list/test-list.json');
    await router.isReady();
    const wrapper = mount(ListView, { global: { plugins: [router] } });
    await vi.runAllTimersAsync();
    await nextTick();

    const initialItemCount = wrapper.findAll('.item-row').length;
    await wrapper.find('.primary-button').trigger('click');
    await nextTick();

    const newItemCount = wrapper.findAll('.item-row').length;
    expect(newItemCount).toBe(initialItemCount + 1);

    vi.runAllTimers();
    expect(listFiles.saveListToFile).toHaveBeenCalled();
  });

  it('deletes an item when the delete button is clicked', async () => {
    router.push('/list/test-list.json');
    await router.isReady();
    const wrapper = mount(ListView, { global: { plugins: [router] } });
    await vi.runAllTimersAsync();
    await nextTick();

    const initialItemCount = wrapper.findAll('.item-row').length;
    await wrapper.find('.delete-item-button').trigger('click');
    await nextTick();
    
    const newItemCount = wrapper.findAll('.item-row').length;
    expect(newItemCount).toBe(initialItemCount - 1);
    
    vi.runAllTimers();
    expect(listFiles.saveListToFile).toHaveBeenCalled();
  });
  
  it('deletes the entire list when delete button in header is clicked', async () => {
    const pushSpy = vi.spyOn(router, 'push');
    router.push('/list/test-list.json');
    await router.isReady();
    const wrapper = mount(ListView, { global: { plugins: [router] } });
    await vi.runAllTimersAsync();
    await nextTick();

    await wrapper.find('button[aria-label="Delete list"]').trigger('click');
    await vi.runAllTimersAsync();
    
    expect(listFiles.deleteListFile).toHaveBeenCalled();
    expect(pushSpy).toHaveBeenCalledWith('/');
  });
});
