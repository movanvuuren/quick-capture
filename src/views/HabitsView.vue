<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { Check, Flame, Minus, RotateCcw, SkipForward, Plus } from 'lucide-vue-next'
import { parseFrontmatter } from '../lib/lists'
import { loadSettings } from '../lib/settings'
import { FolderPicker } from '../plugins/folder-picker'

interface HabitCard {
  id: string
  name: string
  period: string
  targetCount: number
  unit: string
  reminder: string
  allowSkip: boolean
  scheduledDays: number[]
  fileName: string
}

type HabitLogValue = number | 'skip' | 'fail'

interface HeatmapCell {
  date: string
  value: HabitLogValue | null
  level: 0 | 1 | 2 | 3 | 4
  isToday: boolean
  isScheduled: boolean
  isFuture: boolean
}

const LOG_START = '<!-- QUICK_CAPTURE_HABIT_LOG_START -->'
const LOG_END = '<!-- QUICK_CAPTURE_HABIT_LOG_END -->'
const HEATMAP_WEEKS = 4
const DAY_LABELS = ['S', 'M', 'T', 'W', 'T', 'F', 'S']

const router = useRouter()
const settings = loadSettings()

const isLoading = ref(true)
const error = ref('')
const habits = ref<HabitCard[]>([])
const habitLogs = ref<Record<string, Record<string, HabitLogValue>>>({})
const habitSaving = ref<Record<string, boolean>>({})
const habitSaveErrors = ref<Record<string, string>>({})
const isCreatingExample = ref(false)
const exampleError = ref('')

const hasBaseFolder = computed(() => Boolean(settings.baseFolderUri))

function goBack() {
  router.push('/')
}

function todayIso() {
  return new Date().toISOString().slice(0, 10)
}

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

function parseHabitLog(content: string): Record<string, HabitLogValue> {
  const result: Record<string, HabitLogValue> = {}
  const start = content.indexOf(LOG_START)
  const end = content.indexOf(LOG_END)

  if (start === -1 || end === -1 || end <= start)
    return result

  const block = content.slice(start + LOG_START.length, end)
  const lines = block.split(/\r?\n/)

  for (const line of lines) {
    const match = line.match(/^-\s+(\d{4}-\d{2}-\d{2}):\s*(\*|!|\d+)\s*$/)
    if (!match)
      continue

    const [, date, rawValue] = match
    if (!date)
      continue

    if (rawValue === '*')
      result[date] = 'skip'
    else if (rawValue === '!')
      result[date] = 'fail'
    else
      result[date] = Math.max(0, Number(rawValue))
  }

  return result
}

function serializeHabitLog(log: Record<string, HabitLogValue>): string {
  const entries = Object.entries(log)
    .filter(([, value]) => value === 'skip' || value === 'fail' || (Number.isFinite(value) && Number(value) >= 0))
    .sort((a, b) => a[0].localeCompare(b[0]))

  const lines = entries.map(([date, value]) => {
    if (value === 'skip')
      return `- ${date}: *`
    if (value === 'fail')
      return `- ${date}: !`
    return `- ${date}: ${value}`
  })
  return [LOG_START, ...lines, LOG_END].join('\n')
}

function upsertHabitLogBlock(content: string, log: Record<string, HabitLogValue>): string {
  const block = serializeHabitLog(log)
  const start = content.indexOf(LOG_START)
  const end = content.indexOf(LOG_END)

  if (start !== -1 && end !== -1 && end > start) {
    const before = content.slice(0, start).replace(/\s*$/, '')
    const after = content.slice(end + LOG_END.length).replace(/^\s*/, '')
    return `${before}\n\n${block}${after ? `\n\n${after}` : ''}\n`
  }

  const trimmed = content.replace(/\s*$/, '')
  return `${trimmed}\n\n${block}\n`
}

function toWeekdayIndex(date: Date): number {
  const day = date.getDay()
  return day === 0 ? 7 : day
}

function dateAddDays(base: Date, days: number): Date {
  const next = new Date(base)
  next.setDate(next.getDate() + days)
  return next
}

function dateToIso(date: Date): string {
  const y = date.getFullYear()
  const m = String(date.getMonth() + 1).padStart(2, '0')
  const d = String(date.getDate()).padStart(2, '0')
  return `${y}-${m}-${d}`
}

function isoToDate(iso: string): Date {
  const [year = 1970, month = 1, day = 1] = iso.split('-').map(v => Number(v))
  return new Date(year, month - 1, day)
}

function startOfWeek(date: Date): Date {
  const d = new Date(date)
  d.setHours(0, 0, 0, 0)
  const day = d.getDay()
  d.setDate(d.getDate() - day)
  return d
}

function isScheduledForDate(habit: HabitCard, date: Date): boolean {
  if (!habit.scheduledDays.length)
    return true
  return habit.scheduledDays.includes(toWeekdayIndex(date))
}

function getHeatmap(habit: HabitCard): HeatmapCell[] {
  const end = new Date()
  end.setHours(0, 0, 0, 0)
  const endWeekStart = startOfWeek(end)
  const start = dateAddDays(endWeekStart, -((HEATMAP_WEEKS - 1) * 7))
  const logs = habitLogs.value[habit.fileName] || {}
  const today = todayIso()
  const cells: HeatmapCell[] = []

  for (let i = 0; i < HEATMAP_WEEKS * 7; i += 1) {
    const date = dateAddDays(start, i)
    const iso = dateToIso(date)
    const value = logs[iso] ?? null
    const isFuture = iso > today
    let level: 0 | 1 | 2 | 3 | 4 = 0

    if (isFuture) {
      level = 0
    }
    else if (value === 'skip') {
      level = 1
    }
    else if (value === 'fail') {
      level = 0
    }
    else if (typeof value === 'number') {
      const ratio = habit.targetCount > 0 ? Math.min(1, value / habit.targetCount) : 0
      if (ratio >= 1)
        level = 4
      else if (ratio >= 0.66)
        level = 3
      else if (ratio >= 0.33)
        level = 2
      else if (ratio > 0)
        level = 1
    }

    cells.push({
      date: iso,
      value,
      level,
      isToday: iso === today,
      isScheduled: isScheduledForDate(habit, date),
      isFuture,
    })
  }

  return cells
}

function getTodayValue(habit: HabitCard): HabitLogValue | null {
  const log = habitLogs.value[habit.fileName] || {}
  return log[todayIso()] ?? null
}

async function writeHabitLog(habit: HabitCard) {
  if (!settings.baseFolderUri)
    return

  habitSaving.value[habit.fileName] = true
  habitSaveErrors.value[habit.fileName] = ''

  try {
    const read = await FolderPicker.readFile({ folderUri: settings.baseFolderUri, fileName: habit.fileName })
    const nextContent = upsertHabitLogBlock(read.content, habitLogs.value[habit.fileName] || {})

    await FolderPicker.writeFile({
      folderUri: settings.baseFolderUri,
      fileName: habit.fileName,
      content: nextContent,
    })
  }
  catch (err) {
    console.error('Failed to save habit log', err)
    habitSaveErrors.value[habit.fileName] = 'Could not save habit update.'
  }
  finally {
    habitSaving.value[habit.fileName] = false
  }
}

async function setTodayLogValue(habit: HabitCard, value: HabitLogValue | null) {
  await setLogValueForDate(habit, todayIso(), value)
}

async function setLogValueForDate(habit: HabitCard, date: string, value: HabitLogValue | null) {
  const current = { ...(habitLogs.value[habit.fileName] || {}) }

  if (value === null)
    delete current[date]
  else
    current[date] = value

  habitLogs.value = {
    ...habitLogs.value,
    [habit.fileName]: current,
  }

  await writeHabitLog(habit)
}

async function incrementToday(habit: HabitCard, by: number) {
  const today = getTodayValue(habit)
  const current = typeof today === 'number' ? today : 0
  const next = Math.max(0, current + by)
  await setTodayLogValue(habit, next === 0 ? null : next)
}

async function markDoneToday(habit: HabitCard) {
  await setTodayLogValue(habit, Math.max(1, habit.targetCount))
}

async function markSkipToday(habit: HabitCard) {
  await setTodayLogValue(habit, 'skip')
}

async function clearToday(habit: HabitCard) {
  await setTodayLogValue(habit, null)
}

function getCellState(cell: HeatmapCell): 'complete' | 'skip' | 'fail' | 'none' {
  if (cell.value === 'skip')
    return 'skip'
  if (cell.value === 'fail')
    return 'fail'
  if (typeof cell.value === 'number' && cell.value > 0)
    return 'complete'
  return 'none'
}

async function cycleCellState(habit: HabitCard, cell: HeatmapCell) {
  if (cell.isFuture)
    return

  const state = getCellState(cell)

  if (state === 'none') {
    await setLogValueForDate(habit, cell.date, Math.max(1, habit.targetCount))
    return
  }

  if (state === 'complete') {
    await setLogValueForDate(habit, cell.date, 'skip')
    return
  }

  if (state === 'skip') {
    await setLogValueForDate(habit, cell.date, 'fail')
    return
  }

  await setLogValueForDate(habit, cell.date, null)
}

function cellTitle(habit: HabitCard, cell: HeatmapCell): string {
  const state = getCellState(cell)
  const valueText = state === 'complete'
    ? `complete (${typeof cell.value === 'number' ? cell.value : habit.targetCount}/${habit.targetCount})`
    : state === 'skip'
      ? 'skip'
      : state === 'fail'
        ? 'fail'
        : 'no entry'
  return `${cell.date}: ${valueText}`
}

function getTodaySummary(habit: HabitCard): string {
  const value = getTodayValue(habit)
  if (value === 'skip')
    return 'Today: skipped (*)'
  if (value === 'fail')
    return 'Today: failed (!)'
  if (typeof value === 'number')
    return `Today: ${value}/${habit.targetCount} ${habit.unit}`
  return `Today: 0/${habit.targetCount} ${habit.unit}`
}

function isSuccessValue(habit: HabitCard, value: HabitLogValue | null): boolean {
  if (value === 'skip')
    return true
  if (value === 'fail' || value === null)
    return false
  if (habit.period === 'week')
    return value > 0
  return value >= habit.targetCount
}

function getRollingWindowProgress(habit: HabitCard, endDate: Date) {
  const log = habitLogs.value[habit.fileName] || {}
  const windowSize = 7
  let successDays = 0
  let eligibleDays = 0

  for (let i = 0; i < windowSize; i += 1) {
    const date = dateAddDays(endDate, -(windowSize - 1 - i))
    if (!isScheduledForDate(habit, date))
      continue

    eligibleDays += 1

    const iso = dateToIso(date)
    const value = log[iso] ?? null

    if (isSuccessValue(habit, value))
      successDays += 1
  }

  const target = Math.max(1, habit.targetCount)
  const required = Math.min(target, eligibleDays)
  const isMet = successDays >= required

  return {
    successDays,
    eligibleDays,
    target,
    required,
    isMet,
  }
}

function getCurrentStreak(habit: HabitCard): number {
  if (habit.period === 'week') {
    const today = isoToDate(todayIso())
    let streakDays = 0

    for (let offset = 0; offset <= 365; offset += 1) {
      const date = dateAddDays(today, -offset)
      if (!isScheduledForDate(habit, date))
        continue

      const progress = getRollingWindowProgress(habit, date)
      if (!progress.isMet)
        break

      streakDays += 1
    }

    return streakDays
  }

  const log = habitLogs.value[habit.fileName] || {}
  const today = isoToDate(todayIso())
  let streak = 0

  for (let offset = 0; offset <= 365; offset += 1) {
    const date = dateAddDays(today, -offset)
    if (!isScheduledForDate(habit, date))
      continue

    const iso = dateToIso(date)
    const value = log[iso] ?? null
    if (isSuccessValue(habit, value)) {
      streak += 1
      continue
    }
    break
  }

  return streak
}

function getBestStreak(habit: HabitCard): number {
  if (habit.period === 'week') {
    const log = habitLogs.value[habit.fileName] || {}
    const keys = Object.keys(log).sort()
    const today = isoToDate(todayIso())
    const fallbackStart = dateAddDays(today, -365)
    const start = keys.length > 0 ? isoToDate(keys[0] as string) : fallbackStart

    let best = 0
    let current = 0

    for (let date = new Date(start); date <= today; date = dateAddDays(date, 1)) {
      if (!isScheduledForDate(habit, date))
        continue

      const progress = getRollingWindowProgress(habit, date)
      if (progress.isMet) {
        current += 1
        if (current > best)
          best = current
      }
      else {
        current = 0
      }
    }

    return best
  }

  const log = habitLogs.value[habit.fileName] || {}
  const keys = Object.keys(log).sort()
  const today = isoToDate(todayIso())

  const fallbackStart = dateAddDays(today, -120)
  const start = keys.length > 0 ? isoToDate(keys[0] as string) : fallbackStart

  let best = 0
  let current = 0

  for (let date = new Date(start); date <= today; date = dateAddDays(date, 1)) {
    if (!isScheduledForDate(habit, date))
      continue

    const iso = dateToIso(date)
    const value = log[iso] ?? null
    if (isSuccessValue(habit, value)) {
      current += 1
      if (current > best)
        best = current
    }
    else {
      current = 0
    }
  }

  return best
}

function getStreakSummary(habit: HabitCard): string {
  const current = getCurrentStreak(habit)
  const best = getBestStreak(habit)
  return `Streak: ${current} days current, ${best} days best`
}

function buildExampleSeedLog(): string[] {
  const today = isoToDate(todayIso())
  const pattern: Array<HabitLogValue | null> = [1, 1, 'skip', 1, null, 1, 1, 'fail', 1, 1, 1, 'skip', 1, null]
  const lines: string[] = []

  for (let i = pattern.length - 1; i >= 0; i -= 1) {
    const value = pattern[pattern.length - 1 - i]
    if (value === null)
      continue

    const date = dateAddDays(today, -i)
    const iso = dateToIso(date)
    const serialized = value === 'skip' ? '*' : value === 'fail' ? '!' : String(value)
    lines.push(`- ${iso}: ${serialized}`)
  }

  return lines
}

async function loadHabits() {
  if (!settings.baseFolderUri) {
    habits.value = []
    isLoading.value = false
    return
  }

  isLoading.value = true
  error.value = ''

  try {
    const listed = await FolderPicker.listFiles({ folderUri: settings.baseFolderUri })
    const mdFiles = listed.files.filter(file => file.isFile && file.name.toLowerCase().endsWith('.md'))

    const loaded = await Promise.all(
      mdFiles.map(async (file) => {
        try {
          const read = await FolderPicker.readFile({ folderUri: settings.baseFolderUri as string, fileName: file.name })
          const frontmatter = parseFrontmatter(read.content)
          if (frontmatter.type !== 'habit')
            return null

          return {
            id: String(frontmatter.id || ''),
            name: String(frontmatter.name || file.name.replace(/\.md$/i, '')),
            period: String(frontmatter.period || 'day'),
            targetCount: Math.max(1, Number(frontmatter.targetCount || 1)),
            unit: String(frontmatter.unit || 'times'),
            reminder: String(frontmatter.reminder || ''),
            allowSkip: String(frontmatter.allowSkip || 'true') !== 'false',
            scheduledDays: parseScheduledDays(frontmatter.scheduledDays),
            fileName: file.name,
          } satisfies HabitCard
        }
        catch {
          return null
        }
      }),
    )

    habits.value = loaded
      .filter((habit): habit is HabitCard => Boolean(habit))
      .sort((a, b) => a.name.localeCompare(b.name))

    const logsByFile: Record<string, Record<string, HabitLogValue>> = {}
    await Promise.all(
      habits.value.map(async (habit) => {
        try {
          const read = await FolderPicker.readFile({ folderUri: settings.baseFolderUri as string, fileName: habit.fileName })
          logsByFile[habit.fileName] = parseHabitLog(read.content)
        }
        catch {
          logsByFile[habit.fileName] = {}
        }
      }),
    )
    habitLogs.value = logsByFile
  }
  catch (err) {
    console.error('Failed to load habits', err)
    error.value = 'Could not load habits from your folder.'
  }
  finally {
    isLoading.value = false
  }
}

async function createExampleHabit() {
  if (!settings.baseFolderUri || isCreatingExample.value)
    return

  isCreatingExample.value = true
  exampleError.value = ''

  try {
    const baseName = 'habit-example-daily-walk.md'
    const listed = await FolderPicker.listFiles({ folderUri: settings.baseFolderUri })
    const existing = new Set(listed.files.map(file => file.name.toLowerCase()))

    let fileName = baseName
    if (existing.has(baseName.toLowerCase())) {
      let suffix = 2
      while (existing.has(`habit-example-daily-walk-${suffix}.md`.toLowerCase()))
        suffix += 1
      fileName = `habit-example-daily-walk-${suffix}.md`
    }

    const content = [
      '---',
      'type: habit',
      'id: example-daily-walk',
      'name: "Daily Walk"',
      'active: true',
      'period: day',
      'targetCount: 1',
      'scheduledDays: [1, 2, 3, 4, 5, 6, 7]',
      'allowSkip: true',
      'unit: "walk"',
      `created: ${todayIso()}`,
      '---',
      '',
      'Example habit created from the Habits screen.',
      '',
      LOG_START,
      ...buildExampleSeedLog(),
      LOG_END,
      '',
    ].join('\n')

    await FolderPicker.writeFile({
      folderUri: settings.baseFolderUri,
      fileName,
      content,
    })

    await loadHabits()
  }
  catch (err) {
    console.error('Failed to create example habit', err)
    exampleError.value = 'Could not create example habit file.'
  }
  finally {
    isCreatingExample.value = false
  }
}

onMounted(async () => {
  await loadHabits()
})
</script>

<template>
  <div class="page">
    <div class="header">
      <button class="glass-icon-button back-button" aria-label="Go back" @click="goBack">
        ←
      </button>
      <h1>Habits</h1>
    </div>

    <div v-if="!hasBaseFolder" class="card glass-card empty-state">
      <p class="empty-title">No folder selected</p>
      <p class="empty-text">Choose a system folder in Settings before using habits.</p>
      <button class="glass-button glass-button--primary" @click="router.push('/settings')">
        Open Settings
      </button>
    </div>

    <div v-else-if="isLoading" class="card glass-card empty-state">
      Loading habits...
    </div>

    <div v-else-if="error" class="card glass-card empty-state error-text">
      {{ error }}
    </div>

    <div v-else-if="habits.length === 0" class="card glass-card empty-state">
      <p class="empty-title">No habits yet</p>
      <p class="empty-text">Create one from Settings, or insert a ready-to-use example habit.</p>
      <div class="empty-actions">
        <button class="glass-button glass-button--primary" :disabled="isCreatingExample" @click="createExampleHabit">
          {{ isCreatingExample ? 'Creating example...' : 'Create Example Habit' }}
        </button>
        <button class="glass-button glass-button--secondary" @click="router.push('/settings')">
          Open Settings
        </button>
      </div>
      <p v-if="exampleError" class="hint error-text">{{ exampleError }}</p>
      <p class="hint">Example habit: Daily Walk (target 1 walk/day).</p>
    </div>

    <div v-else class="habit-list">
      <div v-for="habit in habits" :key="habit.fileName" class="card glass-card habit-card">
        <div class="habit-head">
          <div class="habit-icon-wrap">
            <Flame :size="16" />
          </div>
          <div>
            <h2>{{ habit.name }}</h2>
            <p class="habit-id">{{ habit.id || habit.fileName }}</p>
          </div>
        </div>

        <div class="habit-meta-row">
          <span>{{ habit.period === 'week' ? 'Weekly' : 'Daily' }}</span>
          <span>Target: {{ habit.targetCount }} {{ habit.unit }}</span>
          <span v-if="habit.reminder">Reminder: {{ habit.reminder }}</span>
          <span>{{ getStreakSummary(habit) }}</span>
        </div>

        <div class="habit-controls">
          <p class="today-summary">{{ getTodaySummary(habit) }}</p>

          <div class="habit-actions">
            <button class="glass-icon-button habit-action" aria-label="Decrease today"
              @click="incrementToday(habit, -1)">
              <Minus :size="14" />
            </button>
            <button class="glass-icon-button habit-action" aria-label="Increase today"
              @click="incrementToday(habit, 1)">
              <Plus :size="14" />
            </button>
            <button class="glass-button glass-button--secondary" type="button" @click="markDoneToday(habit)">
              <Check :size="14" />
              Done
            </button>
            <button v-if="habit.allowSkip" class="glass-button glass-button--secondary" type="button"
              @click="markSkipToday(habit)">
              <SkipForward :size="14" />
              Skip
            </button>
            <button class="glass-button glass-button--secondary" type="button" @click="clearToday(habit)">
              <RotateCcw :size="14" />
              Clear
            </button>
          </div>

          <p v-if="habitSaving[habit.fileName]" class="hint">Saving update...</p>
          <p v-if="habitSaveErrors[habit.fileName]" class="hint error-text">{{ habitSaveErrors[habit.fileName] }}</p>
        </div>

        <div class="heatmap-wrap">
          <div class="heatmap-days" aria-hidden="true">
            <span v-for="day in DAY_LABELS" :key="`${habit.fileName}-${day}`" class="heatmap-day-label">{{ day }}</span>
          </div>
          <div class="heatmap-grid" role="img" :aria-label="`Heat map for ${habit.name}`">
            <div v-for="cell in getHeatmap(habit)" :key="`${habit.fileName}-${cell.date}`" class="heatmap-cell" :class="[
              `level-${cell.level}`,
              {
                'is-today': cell.isToday,
                'is-unscheduled': !cell.isScheduled,
                'is-future': cell.isFuture,
                'is-fail': cell.value === 'fail',
                'is-skip': cell.value === 'skip',
                'is-clickable': !cell.isFuture,
              },
            ]" :title="cellTitle(habit, cell)" @click="cycleCellState(habit, cell)" />
          </div>
          <div class="heatmap-legend">
            <span>Less</span>
            <span class="legend-dot level-0" />
            <span class="legend-dot level-1" />
            <span class="legend-dot level-2" />
            <span class="legend-dot level-3" />
            <span class="legend-dot level-4" />
            <span class="legend-dot fail-dot" />
            <span>More</span>
          </div>
          <p class="hint">Tap a day to cycle: complete, skip, fail, no entry.</p>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.page {
  min-height: 100vh;
  padding: var(--page-top-padding) 20px 36px;
  color: var(--text);
}

.header {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 18px;
}

h1 {
  margin: 0;
  font-size: 1.75rem;
}

.glass-card {
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: 20px;
  backdrop-filter: blur(14px) saturate(var(--saturation));
  -webkit-backdrop-filter: blur(14px) saturate(var(--saturation));
  box-shadow: var(--shadow);
}

.card {
  padding: 14px;
  margin-bottom: 12px;
}

.empty-state {
  display: grid;
  gap: 8px;
}

.empty-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.empty-title {
  margin: 0;
  font-size: 1rem;
  font-weight: 700;
}

.empty-text {
  margin: 0;
  color: var(--text-soft);
}

.habit-list {
  display: grid;
  gap: 10px;
}

.habit-card {
  display: grid;
  gap: 10px;
}

.habit-head {
  display: grid;
  grid-template-columns: auto 1fr;
  align-items: center;
  gap: 10px;
}

.habit-head h2 {
  margin: 0;
  font-size: 1rem;
}

.habit-id {
  margin: 2px 0 0;
  font-size: 0.78rem;
  color: var(--text-soft);
}

.habit-icon-wrap {
  width: 30px;
  height: 30px;
  border-radius: 10px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  color: color-mix(in srgb, var(--primary) 78%, var(--text));
  background: color-mix(in srgb, var(--primary) 16%, var(--surface));
}

.habit-meta-row {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  font-size: 0.82rem;
  color: var(--text-soft);
}

.habit-controls {
  display: grid;
  gap: 8px;
}

.today-summary {
  margin: 0;
  color: var(--text-soft);
  font-size: 0.86rem;
}

.habit-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.habit-action {
  width: 34px;
  height: 34px;
  border-radius: 12px;
}

.hint {
  margin: 0;
  font-size: 0.82rem;
  color: var(--text-soft);
}

.heatmap-wrap {
  display: grid;
  gap: 8px;
}

.heatmap-days {
  display: grid;
  grid-template-columns: repeat(7, 12px);
  gap: 3px;
  width: fit-content;
}

.heatmap-day-label {
  font-size: 0.66rem;
  text-align: center;
  color: var(--text-soft);
  font-weight: 700;
}

.heatmap-grid {
  display: grid;
  grid-template-columns: repeat(7, 12px);
  grid-auto-rows: 12px;
  gap: 3px;
  align-items: center;
  width: fit-content;
}

.heatmap-cell {
  width: 12px;
  height: 12px;
  border-radius: 3px;
  border: 1px solid color-mix(in srgb, var(--c-light) 12%, transparent);
  background: color-mix(in srgb, var(--c-dark) 8%, var(--surface));
}

.heatmap-cell.is-clickable {
  cursor: pointer;
}

.heatmap-cell.is-skip {
  position: relative;
  background: color-mix(in srgb, var(--surface) 86%, var(--primary) 14%);
  border-color: color-mix(in srgb, var(--primary) 38%, transparent);
}

.heatmap-cell.is-skip::after {
  content: '';
  position: absolute;
  left: 2px;
  right: 2px;
  top: 50%;
  height: 2px;
  transform: translateY(-50%);
  border-radius: 999px;
  background: color-mix(in srgb, var(--primary) 82%, var(--text) 18%);
}

.heatmap-cell.level-1 {
  background: color-mix(in srgb, var(--primary) 22%, var(--surface));
}

.heatmap-cell.level-2 {
  background: color-mix(in srgb, var(--primary) 45%, var(--surface));
}

.heatmap-cell.level-3 {
  background: color-mix(in srgb, var(--primary) 68%, var(--surface));
}

.heatmap-cell.level-4 {
  background: color-mix(in srgb, var(--primary) 88%, var(--surface));
}

.heatmap-cell.is-unscheduled {
  opacity: 0.45;
}

.heatmap-cell.is-future {
  opacity: 0.24;
}

.heatmap-cell.is-fail {
  background: color-mix(in srgb, var(--danger) 72%, var(--surface));
  border-color: color-mix(in srgb, var(--danger) 60%, transparent);
}

.heatmap-cell.is-today {
  border-color: color-mix(in srgb, var(--text) 65%, transparent);
}

.heatmap-legend {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  font-size: 0.72rem;
  color: var(--text-soft);
}

.legend-dot {
  width: 10px;
  height: 10px;
  border-radius: 3px;
  border: 1px solid color-mix(in srgb, var(--c-light) 12%, transparent);
}

.legend-dot.level-0 {
  background: color-mix(in srgb, var(--c-dark) 8%, var(--surface));
}

.legend-dot.level-1 {
  background: color-mix(in srgb, var(--primary) 22%, var(--surface));
}

.legend-dot.level-2 {
  background: color-mix(in srgb, var(--primary) 45%, var(--surface));
}

.legend-dot.level-3 {
  background: color-mix(in srgb, var(--primary) 68%, var(--surface));
}

.legend-dot.level-4 {
  background: color-mix(in srgb, var(--primary) 88%, var(--surface));
}

.legend-dot.fail-dot {
  background: color-mix(in srgb, var(--danger) 72%, var(--surface));
  border-color: color-mix(in srgb, var(--danger) 60%, transparent);
}

.error-text {
  color: var(--danger);
}

@media (max-width: 560px) {
  .heatmap-days {
    grid-template-columns: repeat(7, 10px);
    gap: 2px;
  }

  .heatmap-grid {
    grid-template-columns: repeat(7, 10px);
    gap: 2px;
  }

  .heatmap-cell {
    width: 10px;
    height: 10px;
  }
}
</style>
