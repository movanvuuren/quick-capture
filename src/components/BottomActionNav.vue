<script setup lang="ts">
import {
  CalendarDays,
  CheckSquare,
  FileText,
  Flame,
  House,
  List,
  Search,
  Settings,
} from 'lucide-vue-next'
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useCreateActions } from '../lib/useCreateActions'

const props = withDefaults(defineProps<{
  showHome?: boolean
  showSearch?: boolean
  showAgenda?: boolean
  showTasks?: boolean
  showLists?: boolean
  showNotes?: boolean
  showHabits?: boolean
  showSettings?: boolean
  emphasizeCreateActions?: boolean
}>(), {
  showHome: false,
  showSearch: false,
  showAgenda: true,
  showTasks: true,
  showLists: true,
  showNotes: true,
  showHabits: true,
  showSettings: false,
  emphasizeCreateActions: true,
})

const { createTodoFile, openTasks } = useCreateActions()

const router = useRouter()
const route = useRoute()

function go(path: string) {
  if (route.path === path)
    return
  router.push(path)
}

function matchesRoute(target: string | string[]) {
  const targets = Array.isArray(target) ? target : [target]

  return targets.some((candidate) => {
    if (candidate === '/')
      return route.path === '/'

    return route.path === candidate || route.path.startsWith(`${candidate}/`)
  })
}

const visibleCount = computed(() => {
  return [
    props.showHome,
    props.showSearch,
    props.showAgenda,
    props.showTasks,
    props.showLists,
    props.showNotes,
    props.showHabits,
    props.showSettings,
  ].filter(Boolean).length
})

const navItems = computed(() => [
  props.showTasks
    ? {
      key: 'tasks',
      label: 'Quick tasks',
      icon: CheckSquare,
      onClick: openTasks,
      active: matchesRoute(['/tasks', '/task-list']),
      primary: false,
    }
    : null,
  props.showLists
    ? {
      key: 'lists',
      label: 'Create list',
      icon: List,
      onClick: () => createTodoFile('list'),
      active: matchesRoute('/list'),
      primary: false,
    }
    : null,
  props.showNotes
    ? {
      key: 'notes',
      label: 'Create note',
      icon: FileText,
      onClick: () => go('/note'),
      active: matchesRoute('/note'),
      primary: false,
    }
    : null,
  props.showHabits
    ? {
      key: 'habits',
      label: 'Habits',
      icon: Flame,
      onClick: () => go('/habits'),
      active: matchesRoute('/habits'),
      primary: false,
    }
    : null,
  props.showHome
    ? {
      key: 'home',
      label: 'Home',
      icon: House,
      onClick: () => go('/'),
      active: matchesRoute(['/']),
      primary: false,
    }
    : null,
  props.showSearch
    ? {
      key: 'search',
      label: 'Search',
      icon: Search,
      onClick: () => go('/search'),
      active: matchesRoute('/search'),
      primary: false,
    }
    : null,
  props.showAgenda
    ? {
      key: 'agenda',
      label: 'Agenda',
      icon: CalendarDays,
      onClick: () => go('/agenda'),
      active: matchesRoute('/agenda'),
      primary: false,
    }
    : null,
  props.showSettings
    ? {
      key: 'settings',
      label: 'Settings',
      icon: Settings,
      onClick: () => go('/settings'),
      active: matchesRoute('/settings'),
      primary: false,
    }
    : null,
].filter(Boolean))
</script>

<template>
  <nav class="bottom-nav" aria-label="Primary navigation">
    <div class="bottom-nav-shell" :style="{ gridTemplateColumns: `repeat(${visibleCount}, 1fr)` }">
      <button v-for="item in navItems" :key="item!.key" type="button" class="glass-icon-button bottom-nav-button"
        :class="{
          'bottom-nav-button--active': item!.active,
          'bottom-nav-button--primary': emphasizeCreateActions && item!.primary,
        }" :aria-label="item!.label" :title="item!.label" @click="item!.onClick">
        <component :is="item!.icon" :size="18" />
      </button>
    </div>
  </nav>
</template>

<style scoped>
.bottom-nav {
  position: fixed;
  bottom: 0;
  left: 0;
  right: 0;
  z-index: 50;
  display: flex;
  justify-content: center;
  padding: 10px 16px calc(10px + env(safe-area-inset-bottom, 0px));
  background: color-mix(in srgb, var(--bg) 78%, transparent);
  backdrop-filter: blur(14px);
  -webkit-backdrop-filter: blur(14px);
  border-top: 1px solid var(--border);
}

.bottom-nav-shell {
  width: min(780px, 100%);
  display: grid;
  gap: 8px;
  align-items: center;
}

.bottom-nav-button {
  width: 100%;
  height: 46px;
  min-width: 0;
  padding: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 14px;
  color: var(--text-soft);
  transition:
    color 0.18s ease,
    border-color 0.18s ease,
    background 0.18s ease,
    box-shadow 0.18s ease,
    transform 0.18s ease;
}

.bottom-nav-button--primary {
  border-color: color-mix(in srgb, var(--primary) 22%, var(--border));
  background: color-mix(in srgb, var(--primary) 7%, var(--c-glass) 12%);
}

.bottom-nav-button--active {
  color: var(--primary);
  border-color: color-mix(in srgb, var(--primary) 48%, var(--border));
  background:
    radial-gradient(circle at top center, color-mix(in srgb, var(--primary) 18%, transparent), transparent 70%),
    color-mix(in srgb, var(--primary) 12%, var(--c-glass) 14%);
  box-shadow:
    0 0 0 1px color-mix(in srgb, var(--primary) 18%, transparent),
    0 0 18px color-mix(in srgb, var(--primary) 20%, transparent),
    inset 0 1px 0 color-mix(in srgb, white 16%, transparent);
}

.bottom-nav-button:active {
  transform: scale(0.97);
}

@media (max-width: 560px) {
  .bottom-nav-shell {
    gap: 6px;
  }

  .bottom-nav-button {
    height: 44px;
    border-radius: 12px;
  }
}
</style>
