<script setup lang="ts">
import { Moon, Sparkles, Sun, Trash2, Folder, Paintbrush, CheckSquare, Flame } from 'lucide-vue-next'
import type { CSSProperties } from 'vue'
import { computed, nextTick, onMounted, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import OptionSwitcher from '../components/OptionSwitcher.vue'
import { parseFrontmatter } from '../lib/lists'
import { applyTheme, getThemeAccentColor, loadSettings, saveSettings } from '../lib/settings'
import { FolderPicker } from '../plugins/folder-picker'

const router = useRouter()
const MAX_QUICK_TASK_PRESETS = 3
const PULL_REFRESH_THRESHOLD = 72
const PULL_REFRESH_MAX_DISTANCE = 120
type HabitPeriod = 'day' | 'week'
type SettingsSection = 'system' | 'appearance' | 'tasks' | 'habits'

// A single reactive object for all settings, loaded from storage.
const settings = reactive(loadSettings())

// Watch for any deep change in the settings object and save automatically.
watch(
  settings,
  (newSettings) => {
    // The watcher gives us a readonly proxy, so we convert it back to a plain
    // object to ensure it can be serialized by saveSettings.
    saveSettings(JSON.parse(JSON.stringify(newSettings)))
  },
  { deep: true },
)

// Also watch the theme specifically to apply it to the document.
watch(
  () => [settings.theme, settings.accentColor],
  ([newTheme, newAccent]) => {
    applyTheme(newTheme as any, (newAccent as string | undefined))
  },
)

// Apply the initial theme when the component loads.
applyTheme(settings.theme, settings.accentColor)

function resetAccentColor() {
  settings.accentColor = undefined
}

function goBack() {
  router.back()
}

async function pickBaseFolder() {
  try {
    const result = await FolderPicker.pickFolder()
    settings.baseFolderUri = result.uri
    settings.baseFolderName = result.name
  }
  catch (error) {
    console.error(error)
  }
}

function addPreset() {
  if (settings.quickTaskPresets.length >= MAX_QUICK_TASK_PRESETS)
    return

  settings.quickTaskPresets.push({
    id: crypto.randomUUID(),
    label: '',
    tag: '',
    saveMode: settings.listSaveMode,
    fileName: settings.listFileName || 'tasks.md',
  })
}

function removePreset(id: string) {
  if (settings.quickTaskPresets.length === 1)
    return
  const index = settings.quickTaskPresets.findIndex(p => p.id === id)
  if (index > -1)
    settings.quickTaskPresets.splice(index, 1)
}

const themeOptions = [
  { value: 'light', label: '', icon: Sun },
  { value: 'dark', label: '', icon: Moon },
  { value: 'dim', label: '', icon: Sparkles },
]

const accentPickerValue = computed({
  get: () => settings.accentColor || getThemeAccentColor(settings.theme),
  set: (value: string) => {
    settings.accentColor = value
  },
})

const canAddPreset = computed(() => settings.quickTaskPresets.length < MAX_QUICK_TASK_PRESETS)

const selectedSection = ref<SettingsSection>('system')

// Bottom nav bar config for settings sections
const sectionNav = [
  { value: 'system', label: 'System', icon: Folder },
  { value: 'appearance', label: 'Appearance', icon: Paintbrush },
  { value: 'tasks', label: 'Tasks', icon: CheckSquare },
  { value: 'habits', label: 'Habits', icon: Flame },
]

const MAX_HABITS = 10

interface HabitDraft {
  name: string
  icon: string
  unit: string
  period: HabitPeriod
  targetCount: number
  targetDays: number
  reminder: string
  allowSkip: boolean
  scheduledDays: number[]
  saveError: string
  savedFileName: string
  isSaving: boolean
  currentFileName: string
  lastSavedSignature: string
  lastSavedFileName: string
}

function createDefaultHabitDraft(): HabitDraft {
  const draft: HabitDraft = {
    name: '',
    icon: '',
    unit: 'times',
    period: 'day',
    targetCount: 1,
    targetDays: 1,
    reminder: '',
    allowSkip: true,
    scheduledDays: [1, 2, 3, 4, 5, 6, 7],
    saveError: '',
    savedFileName: '',
    isSaving: false,
    currentFileName: '',
    lastSavedSignature: '',
    lastSavedFileName: '',
  }

  draft.lastSavedSignature = habitDraftSignature(draft)
  return draft
}

// Multiple habit configs
const habitDrafts = ref<HabitDraft[]>([createDefaultHabitDraft()])
// icon is an emoji or short string shown on the Android home-screen widget

const systemSectionRef = ref<HTMLElement | null>(null)
const appearanceSectionRef = ref<HTMLElement | null>(null)
const tasksSectionRef = ref<HTMLElement | null>(null)
const habitsSectionRef = ref<HTMLElement | null>(null)


// Remove single habit draft state, use habitDrafts instead
let habitAutoSaveTimers: Array<ReturnType<typeof setTimeout> | null> = []
const isHydratingHabitDrafts = ref(false)
const pullStartY = ref<number | null>(null)
const pullStartX = ref<number | null>(null)
const pullDistance = ref(0)
const pullAxisLock = ref<'none' | 'vertical' | 'horizontal'>('none')
const isPullRefreshing = ref(false)
const pullReadyHapticPlayed = ref(false)

const isPullReady = computed(() => pullDistance.value >= PULL_REFRESH_THRESHOLD)
const showPullIndicator = computed(() => pullDistance.value > 0 || isPullRefreshing.value)
const pageContentStyle = computed<CSSProperties>(() => ({
  transform: `translateY(${pullDistance.value}px)`,
  transition: isPullRefreshing.value || pullDistance.value === 0 ? 'transform 0.18s ease' : 'none',
}))

const dayOptions = [
  { value: 1, label: 'Mon' },
  { value: 2, label: 'Tue' },
  { value: 3, label: 'Wed' },
  { value: 4, label: 'Thu' },
  { value: 5, label: 'Fri' },
  { value: 6, label: 'Sat' },
  { value: 7, label: 'Sun' },
]


function parseScheduledDays(value: unknown): number[] {
  if (Array.isArray(value))
    return value.map(v => Number(v)).filter(v => Number.isInteger(v) && v >= 1 && v <= 7)

  if (typeof value === 'string') {
    const inner = value.trim().replace(/^\[/, '').replace(/\]$/, '')
    const parsed = inner
      .split(',')
      .map(v => Number(v.trim()))
      .filter(v => Number.isInteger(v) && v >= 1 && v <= 7)
    return parsed.length > 0 ? parsed : [1, 2, 3, 4, 5, 6, 7]
  }

  return [1, 2, 3, 4, 5, 6, 7]
}

function fallbackHabitId(name: string): string {
  let hash = 5381
  for (const char of name) {
    const code = char.codePointAt(0) || 0
    hash = ((hash << 5) + hash) + code
    hash |= 0
  }
  return `habit-${Math.abs(hash)}`
}

function derivedHabitId(draft: HabitDraft) {
  const trimmedName = draft.name.trim()
  if (!trimmedName)
    return ''

  const slug = toSlug(trimmedName)
  return slug || fallbackHabitId(trimmedName)
}
function canSaveHabit(draft: HabitDraft) {
  return Boolean(settings.baseFolderUri) && draft.name.trim().length > 0
}

function toSlug(value: string): string {
  return value
    .trim()
    .toLowerCase()
    .replace(/[^a-z0-9\s-]/g, '')
    .replace(/\s+/g, '-')
    .replace(/-+/g, '-')
    .replace(/^-|-$/g, '')
}

function todayIso(): string {
  const now = new Date()
  const year = now.getFullYear()
  const month = String(now.getMonth() + 1).padStart(2, '0')
  const day = String(now.getDate()).padStart(2, '0')
  return `${year}-${month}-${day}`
}

function toggleHabitScheduledDay(draft: HabitDraft, day: number) {
  if (draft.scheduledDays.includes(day)) {
    if (draft.scheduledDays.length === 1) return
    draft.scheduledDays = draft.scheduledDays.filter(current => current !== day)
    draft.targetDays = Math.min(Math.max(1, draft.targetDays), draft.scheduledDays.length)
    return
  }
  draft.scheduledDays = [...draft.scheduledDays, day].sort((a, b) => a - b)
}

function getDraftTargetDays(draft: HabitDraft): number {
  return Math.min(Math.max(1, Math.floor(draft.targetDays || 1)), draft.scheduledDays.length || 1)
}

function habitDraftSignature(draft: HabitDraft): string {
  return JSON.stringify({
    name: draft.name.trim(),
    icon: draft.icon.trim(),
    unit: draft.unit.trim() || 'times',
    period: draft.period,
    targetCount: Math.max(1, Math.floor(draft.targetCount || 1)),
    targetDays: getDraftTargetDays(draft),
    reminder: draft.reminder.trim(),
    allowSkip: draft.allowSkip,
    scheduledDays: [...draft.scheduledDays].sort((a, b) => a - b),
  })
}

function buildHabitMarkdown(draft: HabitDraft): string {
  const lines = [
    '---',
    'type: habit',
    `id: ${derivedHabitId(draft)}`,
    `name: ${JSON.stringify(draft.name.trim())}`,
    'active: true',
    `period: ${draft.period}`,
    `targetCount: ${Math.max(1, Math.floor(draft.targetCount || 1))}`,
    `scheduledDays: [${draft.scheduledDays.join(', ')}]`,
    `allowSkip: ${draft.allowSkip ? 'true' : 'false'}`,
    `unit: ${JSON.stringify(draft.unit.trim() || 'times')}`,
  ]
  if (draft.icon.trim())
    lines.push(`icon: ${JSON.stringify(draft.icon.trim())}`)
  if (draft.period === 'week')
    lines.push(`targetDays: ${getDraftTargetDays(draft)}`)
  if (draft.reminder.trim())
    lines.push(`reminder: ${JSON.stringify(draft.reminder.trim())}`)
  lines.push(`created: ${todayIso()}`)
  lines.push('---')
  lines.push('')
  lines.push('Habit definition for Quick Capture.')
  return `${lines.join('\n')}\n`
}

async function uniqueHabitFileName(baseName: string): Promise<string> {
  if (!settings.baseFolderUri)
    return baseName
  const listed = await FolderPicker.listFiles({ folderUri: settings.baseFolderUri })
  const existing = new Set(listed.files.map(file => file.name.toLowerCase()))
  if (!existing.has(baseName.toLowerCase()))
    return baseName
  const dot = baseName.lastIndexOf('.')
  const stem = dot > 0 ? baseName.slice(0, dot) : baseName
  const ext = dot > 0 ? baseName.slice(dot) : ''
  let suffix = 2
  while (existing.has(`${stem}-${suffix}${ext}`.toLowerCase()))
    suffix += 1
  return `${stem}-${suffix}${ext}`
}

async function loadHabitDrafts() {
  if (!settings.baseFolderUri) {
    habitDrafts.value = [createDefaultHabitDraft()]
    return
  }

  isHydratingHabitDrafts.value = true

  try {
    const listed = await FolderPicker.listFiles({ folderUri: settings.baseFolderUri })
    const mdFiles = listed.files.filter(file => file.isFile && file.name.toLowerCase().endsWith('.md'))

    const loaded = await Promise.all(
      mdFiles.map(async (file): Promise<HabitDraft | null> => {
        try {
          const read = await FolderPicker.readFile({ folderUri: settings.baseFolderUri as string, fileName: file.name })
          const frontmatter = parseFrontmatter(read.content)
          if (frontmatter.type !== 'habit')
            return null

          const period: HabitPeriod = frontmatter.period === 'week' ? 'week' : 'day'
          const targetCount = period === 'week' && frontmatter.targetDays === undefined
            ? Math.max(1, Number(frontmatter.dailyTargetCount || 1))
            : Math.max(1, Number(frontmatter.targetCount || 1))
          const targetDays = period === 'week'
            ? Math.max(1, Number(frontmatter.targetDays || frontmatter.targetCount || 1))
            : 1

          const draft: HabitDraft = {
            name: String(frontmatter.name || file.name.replace(/\.md$/i, '')),
            icon: String(frontmatter.icon || ''),
            unit: String(frontmatter.unit || 'times'),
            period,
            targetCount,
            targetDays,
            reminder: String(frontmatter.reminder || ''),
            allowSkip: String(frontmatter.allowSkip || 'true') !== 'false',
            scheduledDays: parseScheduledDays(frontmatter.scheduledDays),
            saveError: '',
            savedFileName: '',
            isSaving: false,
            currentFileName: file.name,
            lastSavedSignature: '',
            lastSavedFileName: file.name,
          }

          draft.lastSavedSignature = habitDraftSignature(draft)

          return draft
        }
        catch {
          return null
        }
      }),
    )

    const drafts = loaded
      .filter((draft): draft is HabitDraft => Boolean(draft))
      .sort((a, b) => a.name.localeCompare(b.name))

    habitDrafts.value = drafts.slice(0, MAX_HABITS)
    if (!habitDrafts.value.length)
      habitDrafts.value = [createDefaultHabitDraft()]
  }
  catch (error) {
    console.error('Failed to load habit drafts', error)
    if (!habitDrafts.value.length)
      habitDrafts.value = [createDefaultHabitDraft()]
  }
  finally {
    isHydratingHabitDrafts.value = false
  }
}

async function saveHabitConfig(draft: HabitDraft) {
  if (!settings.baseFolderUri || !canSaveHabit(draft) || draft.isSaving)
    return
  draft.isSaving = true
  draft.saveError = ''
  draft.savedFileName = ''
  try {
    if (!draft.currentFileName) {
      const fileStem = derivedHabitId(draft) || 'habit'
      draft.currentFileName = await uniqueHabitFileName(`habit-${fileStem}.md`)
    }
    await FolderPicker.writeFile({
      folderUri: settings.baseFolderUri,
      fileName: draft.currentFileName,
      content: buildHabitMarkdown(draft),
    })
    draft.savedFileName = draft.currentFileName
    draft.lastSavedSignature = habitDraftSignature(draft)
    draft.lastSavedFileName = draft.currentFileName
  } catch (error) {
    console.error('Failed to save habit config', error)
    draft.saveError = 'Could not save habit note.'
  } finally {
    draft.isSaving = false
  }
}

function addHabitDraft() {
  if (habitDrafts.value.length >= MAX_HABITS) return
  habitDrafts.value.push(createDefaultHabitDraft())
}
function removeHabitDraft(idx: number) {
  if (habitDrafts.value.length === 1) return
  habitDrafts.value.splice(idx, 1)
}


// Watch each habit draft for changes and auto-save
watch(
  habitDrafts,
  (newDrafts) => {
    if (isHydratingHabitDrafts.value)
      return

    newDrafts.forEach((draft, idx) => {
      if (habitAutoSaveTimers[idx]) clearTimeout(habitAutoSaveTimers[idx])
      if (!canSaveHabit(draft)) return
      const nextSignature = habitDraftSignature(draft)
      if (nextSignature === draft.lastSavedSignature && draft.currentFileName === draft.lastSavedFileName)
        return
      habitAutoSaveTimers[idx] = setTimeout(() => {
        void saveHabitConfig(draft)
      }, 1200)
    })
  },
  { deep: true },
)

watch(
  () => settings.baseFolderUri,
  () => {
    void loadHabitDrafts()
  },
)

onMounted(() => {
  void loadHabitDrafts()
})

function isPageScrolledToTop() {
  return window.scrollY <= 0
}

async function triggerHaptic(kind: 'light' | 'medium' = 'light') {
  try {
    const { Haptics, ImpactStyle } = await import('@capacitor/haptics')
    const style = kind === 'medium' ? ImpactStyle.Medium : ImpactStyle.Light
    await Haptics.impact({ style })
    return
  }
  catch {
    if (typeof navigator !== 'undefined' && typeof navigator.vibrate === 'function')
      navigator.vibrate(kind === 'medium' ? 20 : 10)
  }
}

function resetPullState() {
  pullStartY.value = null
  pullStartX.value = null
  pullDistance.value = 0
  pullAxisLock.value = 'none'
  pullReadyHapticPlayed.value = false
}

function onPullTouchStart(event: TouchEvent) {
  if (isPullRefreshing.value)
    return
  if (!isPageScrolledToTop())
    return

  const touch = event.touches[0]
  if (!touch)
    return

  pullStartY.value = touch.clientY
  pullStartX.value = touch.clientX
  pullDistance.value = 0
  pullAxisLock.value = 'none'
}

function onPullTouchMove(event: TouchEvent) {
  if (pullStartY.value === null || pullStartX.value === null)
    return

  const touch = event.touches[0]
  if (!touch)
    return

  const deltaY = touch.clientY - pullStartY.value
  const deltaX = touch.clientX - pullStartX.value

  if (pullAxisLock.value === 'none' && (Math.abs(deltaX) > 6 || Math.abs(deltaY) > 6))
    pullAxisLock.value = Math.abs(deltaY) >= Math.abs(deltaX) ? 'vertical' : 'horizontal'

  if (pullAxisLock.value === 'horizontal')
    return

  if (deltaY <= 0) {
    pullDistance.value = 0
    pullReadyHapticPlayed.value = false
    return
  }

  if (!isPageScrolledToTop()) {
    resetPullState()
    return
  }

  event.preventDefault()
  pullDistance.value = Math.min(PULL_REFRESH_MAX_DISTANCE, deltaY * 0.45)

  const reachedReadyState = pullDistance.value >= PULL_REFRESH_THRESHOLD
  if (reachedReadyState && !pullReadyHapticPlayed.value) {
    pullReadyHapticPlayed.value = true
    void triggerHaptic('light')
  }
  else if (!reachedReadyState) {
    pullReadyHapticPlayed.value = false
  }
}

async function refreshSettingsPage() {
  const latest = loadSettings()
  Object.assign(settings, latest)
  await loadHabitDrafts()
}

async function onPullTouchEnd() {
  if (pullStartY.value === null)
    return

  const shouldRefresh = pullDistance.value >= PULL_REFRESH_THRESHOLD
  pullStartY.value = null
  pullStartX.value = null
  pullAxisLock.value = 'none'

  if (!shouldRefresh) {
    pullDistance.value = 0
    return
  }

  isPullRefreshing.value = true
  pullDistance.value = 44
  void triggerHaptic('medium')

  try {
    await refreshSettingsPage()
  }
  finally {
    isPullRefreshing.value = false
    pullDistance.value = 0
  }
}

function jumpToSection(value: string) {
  if (value !== 'system' && value !== 'appearance' && value !== 'tasks' && value !== 'habits')
    return

  selectedSection.value = value

  nextTick(() => {
    const target = value === 'system'
      ? systemSectionRef.value
      : value === 'appearance'
        ? appearanceSectionRef.value
        : value === 'tasks'
          ? tasksSectionRef.value
          : habitsSectionRef.value

    target?.scrollIntoView({ behavior: 'smooth', block: 'start' })
  })
}
</script>

<template>
  <div class="page" @touchstart="onPullTouchStart" @touchmove="onPullTouchMove" @touchend="onPullTouchEnd"
    @touchcancel="onPullTouchEnd">
    <div class="pull-refresh"
      :class="{ 'is-visible': showPullIndicator, 'is-ready': isPullReady, 'is-refreshing': isPullRefreshing }"
      :aria-label="isPullRefreshing ? 'Refreshing settings' : 'Pull to refresh settings'" role="status">
      <div class="pull-refresh-spinner" aria-hidden="true" />
      <span>{{ isPullRefreshing ? 'Refreshing…' : isPullReady ? 'Release to refresh' : '' }}</span>
    </div>

    <div class="page-content" :style="pageContentStyle">
      <div class="header">
        <button class="glass-icon-button back-button" aria-label="Go back" @click="goBack">
          ←
        </button>

        <h1>Settings</h1>
      </div>


      <!-- Bottom nav bar for settings -->
      <nav class="settings-bottom-bar">
        <button v-for="section in sectionNav" :key="section.value" class="settings-bottom-bar-btn"
          :class="{ active: selectedSection === section.value }" @click="jumpToSection(section.value)">
          <component :is="section.icon" :size="22" />
          <span>{{ section.label }}</span>
        </button>
      </nav>

      <!-- system folder card -->
      <div ref="systemSectionRef" class="card">
        <div class="field">
          <h2 class="card-title">System folder</h2>
          <button class="glass-button folder-button" type="button" @click="pickBaseFolder">
            Select folder
          </button>
          <p v-if="settings.baseFolderName" class="hint">
            {{ settings.baseFolderName }}
          </p>
          <p v-else class="hint">
            No folder selected
          </p>
        </div>
      </div>

      <!-- appearance card -->
      <div ref="appearanceSectionRef" class="card">
        <h2 class="card-title">
          Appearance
        </h2>
        <OptionSwitcher v-model="settings.theme" :options="themeOptions" aria-label="Theme" />

        <div class="field accent-field">
          <span>Accent color</span>
          <div class="accent-controls">
            <input v-model="accentPickerValue" class="accent-picker" type="color" aria-label="Accent color picker">
            <button class="glass-button glass-button--secondary accent-reset" type="button" @click="resetAccentColor">
              Reset default
            </button>
          </div>
        </div>
      </div>

      <div ref="tasksSectionRef" class="section-anchor" aria-hidden="true" />

      <div v-for="(preset, index) in settings.quickTaskPresets" :key="preset.id" class="card">
        <div class="card-header">
          <h2>Task {{ index + 1 }}</h2>
          <button class="glass-icon-button remove-button" :disabled="settings.quickTaskPresets.length === 1"
            @click="removePreset(preset.id)">
            <Trash2 :size="14" />
          </button>
        </div>

        <label class="field">
          <span>Label</span>
          <input v-model="preset.label" type="text">
        </label>

        <label class="field">
          <span>Tag</span>
          <input v-model="preset.tag" type="text">
        </label>

        <div class="field">
          <span>Save mode</span>

          <div class="segmented-control" :data-active="preset.saveMode === 'daily_note' ? 'right' : 'left'">
            <button type="button" class="segmented-option" :class="{ 'is-active': preset.saveMode === 'single_file' }"
              @click="preset.saveMode = 'single_file'">
              Custom filename
            </button>

            <button type="button" class="segmented-option" :class="{ 'is-active': preset.saveMode === 'daily_note' }"
              @click="preset.saveMode = 'daily_note'">
              Today’s date
            </button>
          </div>
        </div>

        <label class="field">
          <span>Filename</span>
          <input v-model="preset.fileName" type="text" placeholder="tasks.md"
            :disabled="preset.saveMode === 'daily_note'">
        </label>
      </div>

      <button v-if="canAddPreset" class="glass-button glass-button--block glass-button--secondary add-button"
        @click="addPreset">
        + Add Task
      </button>
      <p class="hint add-limit-hint">
        {{ settings.quickTaskPresets.length }}/{{ MAX_QUICK_TASK_PRESETS }} presets used
      </p>

      <div ref="habitsSectionRef">
        <div v-for="(draft, idx) in habitDrafts" :key="idx" class="card">
          <div class="card-header">
            <h2>Habit {{ idx + 1 }}</h2>
            <button class="glass-icon-button remove-button" :disabled="habitDrafts.length === 1"
              @click="removeHabitDraft(idx)">
              <Trash2 :size="14" />
            </button>
          </div>
          <label class="field">
            <span>Habit name</span>
            <input v-model="draft.name" type="text" placeholder="Exercise" />
          </label>
          <label class="field">
            <span>Widget icon</span>
            <input v-model="draft.icon" type="text" placeholder="🏃" maxlength="8" style="width:6ch">
            <small class="hint">Emoji shown on the Android home-screen widget.</small>
          </label>
          <div class="two-col-grid">
            <label class="field">
              <span>Period</span>
              <select v-model="draft.period" class="glass-select">
                <option value="day">Daily</option>
                <option value="week">Weekly</option>
              </select>
            </label>
            <label class="field">
              <span>{{ draft.period === 'week' ? 'Target per successful day' : 'Daily target' }}</span>
              <input v-model.number="draft.targetCount" type="number" min="1" step="1">
            </label>
          </div>
          <div v-if="draft.period === 'week'" class="two-col-grid">
            <label class="field">
              <span>Days per week</span>
              <input v-model.number="draft.targetDays" type="number" min="1" :max="draft.scheduledDays.length || 1"
                step="1">
              <small class="hint">Example: 6 means you need to hit the amount goal on 6 scheduled days.</small>
            </label>
            <div class="field field-summary">
              <span>Weekly goal</span>
              <p class="habit-goal-summary">
                {{ draft.targetCount || 1 }} {{ draft.unit || 'times' }} on {{ getDraftTargetDays(draft) }}/{{
                  draft.scheduledDays.length || 1 }} days
              </p>
            </div>
          </div>
          <div class="two-col-grid">
            <label class="field">
              <span>Unit</span>
              <input v-model="draft.unit" type="text" placeholder="times">
              <small class="hint">Display label, for example times, km, pages.</small>
            </label>
            <label class="field">
              <span>Reminder</span>
              <input v-model="draft.reminder" type="time">
            </label>
          </div>
          <label class="checkbox-row">
            <input v-model="draft.allowSkip" type="checkbox">
            <span>Allow skip</span>
          </label>
          <div v-if="draft.period === 'week'" class="field">
            <span>Scheduled days</span>
            <small class="hint">For weekly habits, these are the eligible days counted toward the days-per-week
              goal.</small>
            <div class="day-row">
              <button v-for="day in dayOptions" :key="day.value" type="button" class="day-chip"
                :class="{ 'is-active': draft.scheduledDays.includes(day.value) }"
                @click="toggleHabitScheduledDay(draft, day.value)">
                {{ day.label }}
              </button>
            </div>
          </div>
          <p v-if="draft.saveError" class="hint error-text">
            {{ draft.saveError }}
          </p>
        </div>
        <button v-if="habitDrafts.length < MAX_HABITS"
          class="glass-button glass-button--block glass-button--secondary add-button" @click="addHabitDraft">
          + Add Habit
        </button>
        <p class="hint add-limit-hint">{{ habitDrafts.length }}/{{ MAX_HABITS }} habits used</p>
      </div>
    </div>
  </div>
</template>

<style scoped>
.page {
  min-height: 100vh;
  padding: var(--page-top-padding) 20px 40px;
  background: var(--bg);
  color: var(--text);
  overflow-x: hidden;
  touch-action: pan-x pan-y;
}

.page-content {
  will-change: transform;
}

.pull-refresh {
  position: fixed;
  top: calc(var(--page-top-padding) + 8px);
  left: 0;
  right: 0;
  z-index: 120;
  display: flex;
  justify-content: center;
  align-items: center;
  gap: 8px;
  font-size: 0.82rem;
  color: var(--text-soft);
  opacity: 0;
  transform: translateY(-10px);
  transition: opacity 0.18s ease, transform 0.18s ease;
  pointer-events: none;
}

.pull-refresh.is-visible {
  opacity: 1;
  transform: translateY(0);
}

.pull-refresh-spinner {
  width: 14px;
  height: 14px;
  border-radius: 999px;
  border: 2px solid color-mix(in srgb, var(--text-soft) 22%, transparent);
  border-top-color: color-mix(in srgb, var(--primary) 72%, var(--text));
}

.pull-refresh.is-ready .pull-refresh-spinner,
.pull-refresh.is-refreshing .pull-refresh-spinner {
  animation: pull-refresh-spin 0.75s linear infinite;
}

.pull-refresh:not(.is-ready):not(.is-refreshing) .pull-refresh-spinner {
  transform: scale(0.86);
}

@keyframes pull-refresh-spin {
  from {
    transform: rotate(0deg);
  }

  to {
    transform: rotate(360deg);
  }
}

.header {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 24px;
}

.card-title {
  margin-bottom: 8px;
  font-size: 1rem;
  font-weight: 600;
}

h1 {
  margin: 0;
  font-size: 1.8rem;
}

.card {
  background: var(--surface);
  border-radius: 20px;
  padding: 8px;
  box-shadow: var(--shadow);
  margin-bottom: 2px;
}


.settings-bottom-bar {
  position: fixed;
  left: 0;
  right: 0;
  bottom: 0;
  z-index: 100;
  display: flex;
  justify-content: space-around;
  align-items: center;
  background: color-mix(in srgb, var(--bg) 80%, transparent);
  backdrop-filter: blur(12px);
  -webkit-backdrop-filter: blur(12px);
  border-top: 1px solid var(--border);
  padding: 8px 0 8px 0;
}

.settings-bottom-bar-btn {
  background: none;
  border: none;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 2px;
  color: var(--text-soft);
  font-size: 0.85rem;
  font-weight: 600;
  padding: 0 8px;
  border-radius: 10px;
  transition: color 0.15s, background 0.15s;
}

.settings-bottom-bar-btn.active,
.settings-bottom-bar-btn:focus-visible {
  color: var(--primary);
  background: color-mix(in srgb, var(--primary) 10%, transparent);
}

.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 10px;
}

.card-header h2 {
  margin: 0;
  font-size: 1.05rem;
}

.remove-button {
  width: 34px;
  height: 34px;
  border-radius: 12px;
  color: var(--text-soft);
}

.remove-button:hover {
  color: var(--danger);
}

.field {
  display: flex;
  flex-direction: column;
  gap: 8px;
  margin-bottom: 14px;
}

.field span {
  font-size: 0.95rem;
  font-weight: 600;
}

.field input {
  border: 1px solid color-mix(in srgb, var(--c-light) 16%, transparent);
  border-radius: 16px;
  padding: 12px 14px;
  background: color-mix(in srgb, var(--c-glass) 10%, transparent);
  color: var(--text);
  backdrop-filter: blur(10px) saturate(var(--saturation));
  -webkit-backdrop-filter: blur(10px) saturate(var(--saturation));
  box-shadow:
    inset 0 0 0 1px color-mix(in srgb, var(--c-light) calc(var(--glass-reflex-light) * 8%), transparent),
    inset 0 -1px 3px 0 color-mix(in srgb, var(--c-dark) calc(var(--glass-reflex-dark) * 8%), transparent);
}

.field input:disabled {
  opacity: 0.55;
}

.glass-select {
  border: 1px solid color-mix(in srgb, var(--c-light) 16%, transparent);
  border-radius: 16px;
  padding: 12px 14px;
  background: color-mix(in srgb, var(--c-glass) 10%, transparent);
  color: var(--text);
}

.two-col-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px;
}

.checkbox-row {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 12px;
}

.checkbox-row span {
  font-size: 0.95rem;
  font-weight: 600;
}

.day-row {
  display: grid;
  grid-template-columns: repeat(7, minmax(0, 1fr));
  gap: 6px;
}

.day-chip {
  border: 1px solid color-mix(in srgb, var(--c-light) 20%, transparent);
  border-radius: 10px;
  min-height: 34px;
  background: color-mix(in srgb, var(--c-glass) 8%, transparent);
  color: var(--text-soft);
  font-weight: 700;
}

.day-chip.is-active {
  color: var(--text);
  border-color: color-mix(in srgb, var(--primary) 45%, var(--c-light));
  background: color-mix(in srgb, var(--primary) 20%, var(--surface));
}

.success-text {
  color: color-mix(in srgb, #34d399 78%, var(--text));
}

.error-text {
  color: var(--danger);
}

.accent-field {
  margin-top: 14px;
  margin-bottom: 0;
}

.accent-controls {
  display: flex;
  align-items: center;
  gap: 8px;
}

.accent-picker {
  width: 38px;
  height: 38px;
  padding: 2px;
  border-radius: 10px;
  border: 1px solid color-mix(in srgb, var(--c-light) 16%, transparent);
  background: color-mix(in srgb, var(--c-glass) 10%, transparent);
  cursor: pointer;
}

.field input.accent-picker {
  padding: 2px;
  border-radius: 10px;
  box-shadow: none;
  backdrop-filter: none;
  -webkit-backdrop-filter: none;
}

.accent-picker::-webkit-color-swatch-wrapper {
  padding: 0;
}

.accent-picker::-webkit-color-swatch {
  border: none;
  border-radius: 8px;
}

.accent-picker::-moz-color-swatch {
  border: none;
  border-radius: 8px;
}

.accent-reset {
  min-height: 38px;
  padding: 0 12px;
  border-radius: 12px;
}

.radio-option {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 0.98rem;
  color: var(--text);
}

.radio-option input {
  margin: 0;
}

.theme-button {
  padding: 6px 14px;
  border-radius: 999px;
  background: var(--surface);
  color: var(--text);
  font-weight: 600;
  border: 1px solid transparent;
  transition: background 0.2s ease, border-color 0.2s ease;
}

.theme-button.active {
  background: var(--primary);
  color: white;
  border-color: var(--primary);
}

.theme-toggle-button {
  background: transparent;
  border: none;
  color: var(--text);
  cursor: pointer;
  padding: 4px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
}

.theme-toggle-button:hover {
  background: var(--surface-strong);
}

.toggle-label {
  margin-left: 10px;
  font-weight: 600;
}

.hint {
  margin: 2px 0 0;
  font-size: 0.9rem;
  color: var(--text-soft);
}

.add-button {
  margin-top: 4px;
}

.add-limit-hint {
  margin: 6px 0 0;
  text-align: right;
}

.primary-button,
.secondary-button {
  flex: 1;
}

.actions {
  display: flex;
  gap: 12px;
  margin-top: 20px;
}

@media (max-width: 860px) {
  .two-col-grid {
    grid-template-columns: 1fr;
  }

  .day-row {
    grid-template-columns: repeat(4, minmax(0, 1fr));
  }
}
</style>
