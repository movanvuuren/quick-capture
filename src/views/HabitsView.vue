<script setup lang="ts">
import { computed, nextTick, onActivated, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import type { CSSProperties, ComponentPublicInstance } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Check, Eraser, Flame, SkipForward, X } from 'lucide-vue-next'
import { parseFrontmatter } from '../lib/lists'
import { getFileSignature, mapWithConcurrency } from '../lib/asyncUtils'
import { getThemeAccentColor, loadSettings } from '../lib/settings'
import { FolderPicker } from '../plugins/folder-picker'
import { WidgetSync } from '../plugins/widget-sync'
import BottomActionNav from '../components/BottomActionNav.vue'
import PageHeader from '../components/PageHeader.vue'

interface HabitCard {
  id: string
  name: string
  icon: string
  period: 'day' | 'week' | 'month'
  targetCount: number
  targetDays: number
  unit: string
  reminder: string
  allowSkip: boolean
  scheduledDays: number[]
  currentStreak: number
  bestStreak: number
  streakStatsVersion: number
  fileName: string
}

type HabitLogValue = number | 'skip' | 'fail'
type HabitCellState = 'complete' | 'partial' | 'skip' | 'fail' | 'none'

interface HeatmapCell {
  date: string
  value: HabitLogValue | null
  level: 0 | 1 | 2 | 3 | 4
  isToday: boolean
  isScheduled: boolean
  isFuture: boolean
  isPlaceholder?: boolean
}

interface HabitSnapshot {
  habits: HabitCard[]
  habitLogs: Record<string, Record<string, HabitLogValue>>
  selectedDates: Record<string, string>
  loadedHabitLogMonths: Record<string, number>
}

interface CachedHabitDefinition {
  signature: string
  habit: HabitCard | null
}

const HABIT_CONFIGS_DIR = 'habits'
const HABIT_LOGS_DIR = 'habit-logs'
const HABIT_LOG_INITIAL_MONTHS_TO_LOAD = 1
const HABIT_DETAIL_MONTH_COUNT = 12
const HABIT_LOG_READ_CONCURRENCY = 6
const HABIT_LAZY_LOAD_CONCURRENCY = 3
const HABIT_DEFINITION_READ_CONCURRENCY = 6
const HABIT_DERIVED_STATS_SYNC_CONCURRENCY = 2
const HABITS_REFRESH_DEBOUNCE_MS = 4000
const PULL_REFRESH_THRESHOLD = 72
const PULL_REFRESH_MAX_DISTANCE = 120
const DERIVED_STREAK_STATS_VERSION = 2
const SHOULD_AUTO_SEED_DEV_HABITS = import.meta.env.DEV && import.meta.env.MODE !== 'test'

let cachedFolderUri = ''
let cachedAt = 0
let cachedSnapshot: HabitSnapshot | null = null
let activeHabitsLoadPromise: Promise<void> | null = null
const habitDefinitionCache = new Map<string, CachedHabitDefinition>()

const router = useRouter()
const route = useRoute()
const settings = reactive(loadSettings())

const isLoading = ref(true)
const error = ref('')
const habits = ref<HabitCard[]>([])
const habitLogs = ref<Record<string, Record<string, HabitLogValue>>>({})
const selectedDates = ref<Record<string, string>>({})
const loadedHabitLogMonths = ref<Record<string, number>>({})
const isHabitLogLazyLoading = ref<Record<string, boolean>>({})
const habitSaving = ref<Record<string, boolean>>({})
const habitSaveErrors = ref<Record<string, string>>({})
const isCreatingExample = ref(false)
const exampleError = ref('')
const isHabitCardHydrating = ref<Record<string, boolean>>({})
const isHabitDetailLoading = ref(false)
const habitTimelineRefs = new Map<string, HTMLElement>()
const habitTimelineTouchStartX = new Map<string, number>()
const habitTimelinePointerStartX = new Map<string, number>()
let hasAttemptedDevHabitSeed = false

const hasBaseFolder = computed(() => Boolean(settings.baseFolderUri))
const EXTERNAL_REFRESH_COOLDOWN_MS = 1000
let lastExternalRefreshAt = 0
let midnightRefreshTimer: ReturnType<typeof window.setTimeout> | null = null
let habitsLoadToken = 0

const pullStartY = ref<number | null>(null)
const pullStartX = ref<number | null>(null)
const pullDistance = ref(0)
const pullAxisLock = ref<'none' | 'vertical' | 'horizontal'>('none')
const isPullRefreshing = ref(false)
const pullReadyHapticPlayed = ref(false)
const isPullReady = computed(() => pullDistance.value >= PULL_REFRESH_THRESHOLD)
const showPullIndicator = computed(() => pullDistance.value > 0 || isPullRefreshing.value)
let lastHandledRoutePulse = ''
const pageContentStyle = computed<CSSProperties>(() => ({
  transform: `translateY(${pullDistance.value}px)`,
  transition: isPullRefreshing.value || pullDistance.value === 0 ? 'transform 0.18s ease' : 'none',
}))

function getHabitDefinitionCacheKey(fileName: string, folderUri = settings.baseFolderUri || ''): string {
  return `${folderUri}::${fileName}`
}

function readQueryValue(value: unknown): string {
  if (Array.isArray(value))
    return typeof value[0] === 'string' ? value[0] : ''
  return typeof value === 'string' ? value : ''
}

function readRouteValue(value: unknown): string {
  if (Array.isArray(value))
    return typeof value[0] === 'string' ? value[0] : ''
  return typeof value === 'string' ? value : ''
}

function getRequestedHabitKey(): string {
  return readRouteValue(route.params.habitId)
}

function applyRouteHabitSelection() {
  const pulse = readQueryValue(route.query.pulse)
  if (pulse && pulse === lastHandledRoutePulse)
    return

  const habitKey = readQueryValue(route.query.habit)
  if (!habitKey)
    return

  const rawDate = readQueryValue(route.query.date)
  const selectedDate = /^\d{4}-\d{2}-\d{2}$/.test(rawDate) ? rawDate : todayIso()
  const targetHabit = habits.value.find(habit => habit.fileName === habitKey || habit.id === habitKey)
  if (!targetHabit)
    return

  selectedDates.value = {
    ...selectedDates.value,
    [targetHabit.fileName]: selectedDate,
  }

  if (pulse)
    lastHandledRoutePulse = pulse
}

function goBack() {
  router.replace('/')
}

function goToHabitList() {
  if (selectedHabit.value)
    collapseHabitTimelineToCurrent(selectedHabit.value)
  router.replace('/habits')
}

function openHabitDetail(habit: HabitCard) {
  const habitKey = habit.id || habit.fileName
  router.push({
    path: `/habits/${encodeURIComponent(habitKey)}`,
  })
}

function cloneSnapshot(snapshot: HabitSnapshot): HabitSnapshot {
  return {
    habits: snapshot.habits.map(habit => ({ ...habit, scheduledDays: [...habit.scheduledDays] })),
    habitLogs: Object.fromEntries(
      Object.entries(snapshot.habitLogs).map(([fileName, logs]) => [fileName, { ...logs }]),
    ),
    selectedDates: { ...snapshot.selectedDates },
    loadedHabitLogMonths: { ...snapshot.loadedHabitLogMonths },
  }
}

function applyHabitSnapshot(snapshot: HabitSnapshot) {
  const cloned = cloneSnapshot(snapshot)
  habits.value = cloned.habits
  habitLogs.value = cloned.habitLogs
  selectedDates.value = cloned.selectedDates
  loadedHabitLogMonths.value = cloned.loadedHabitLogMonths
}

function syncHabitCache() {
  if (!settings.baseFolderUri)
    return

  cachedFolderUri = settings.baseFolderUri
  cachedAt = Date.now()
  cachedSnapshot = cloneSnapshot({
    habits: habits.value,
    habitLogs: habitLogs.value,
    selectedDates: selectedDates.value,
    loadedHabitLogMonths: loadedHabitLogMonths.value,
  })
}

function todayIso() {
  return dateToIso(new Date())
}

async function waitForNextFrame() {
  await new Promise<void>((resolve) => {
    requestAnimationFrame(() => resolve())
  })
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

function normalizeHabitLogValue(raw: unknown): HabitLogValue | null {
  if (raw === 'skip' || raw === '*')
    return 'skip'
  if (raw === 'fail' || raw === '!')
    return 'fail'

  const numeric = Number(raw)
  if (Number.isFinite(numeric) && numeric >= 0)
    return Math.floor(numeric)

  return null
}

function toNonNegativeInteger(value: unknown): number {
  const numeric = Number(value)
  if (!Number.isFinite(numeric) || numeric < 0)
    return 0
  return Math.floor(numeric)
}

function monthIsoFromDate(date: Date): string {
  const y = date.getFullYear()
  const m = String(date.getMonth() + 1).padStart(2, '0')
  return `${y}-${m}`
}

function monthIsoFromDateIso(dateIso: string): string {
  return dateIso.slice(0, 7)
}

function getHabitLogStem(habit: HabitCard): string {
  const base = (habit.id || habit.fileName.replace(/\.md$/i, '')).trim().toLowerCase()
  const normalized = base
    .replace(/[^a-z0-9_-]+/g, '-')
    .replace(/-+/g, '-')
    .replace(/^-|-$/g, '')
  return normalized || 'habit'
}

function getHabitLogMonthFileName(habit: HabitCard, monthIso: string): string {
  return `${HABIT_LOGS_DIR}/${getHabitLogStem(habit)}-${monthIso}.md`
}

function serializeHabitMonthLog(habit: HabitCard, monthIso: string, monthEntries: Record<string, HabitLogValue>): string {
  const lines = [
    '---',
    'type: habit-log',
    `habitId: ${JSON.stringify(habit.id || getHabitLogStem(habit))}`,
    `habitFile: ${JSON.stringify(habit.fileName)}`,
    `month: ${monthIso}`,
  ]

  const dates = Object.keys(monthEntries)
    .filter(date => date.startsWith(`${monthIso}-`))
    .sort((a, b) => a.localeCompare(b))

  for (const date of dates) {
    const day = date.slice(8, 10)
    const key = `d${day}`
    const value = monthEntries[date]
    if (value === undefined)
      continue
    if (value === 'skip')
      lines.push(`${key}: skip`)
    else if (value === 'fail')
      lines.push(`${key}: fail`)
    else
      lines.push(`${key}: ${Math.max(0, Math.floor(value))}`)
  }

  lines.push(`updated: ${todayIso()}`)
  lines.push('---')
  lines.push('')
  lines.push('Habit log data file.')
  return `${lines.join('\n')}\n`
}

function createDevHabitContent(options: {
  id: string
  name: string
  icon: string
  unit: string
  reminder: string
  created: string
  fileName: string
}): string {
  return [
    '---',
    'type: habit',
    `id: ${options.id}`,
    `name: ${JSON.stringify(options.name)}`,
    'active: true',
    'period: day',
    'targetCount: 1',
    'targetDays: 1',
    'scheduledDays: [1, 2, 3, 4, 5, 6, 7]',
    'allowSkip: true',
    `unit: ${JSON.stringify(options.unit)}`,
    `icon: ${JSON.stringify(options.icon)}`,
    `reminder: ${JSON.stringify(options.reminder)}`,
    'currentStreak: 0',
    'bestStreak: 0',
    `streakStatsVersion: ${DERIVED_STREAK_STATS_VERSION}`,
    `created: ${options.created}`,
    '---',
    '',
    `Sample dev habit for ${options.name}.`,
    '',
  ].join('\n')
}

function createDevHabitEntries(pattern: (day: Date, today: string) => HabitLogValue | null, months = 6): Record<string, HabitLogValue>[] {
  const today = todayIso()
  return Array.from({ length: months }, (_, monthIndex) => {
    const monthDate = getMonthDateForOffset(monthIndex)
    const totalDays = daysInMonth(monthDate)
    const entries: Record<string, HabitLogValue> = {}

    for (let day = 1; day <= totalDays; day += 1) {
      const date = new Date(monthDate.getFullYear(), monthDate.getMonth(), day)
      const iso = dateToIso(date)
      if (iso > today)
        continue
      const value = pattern(date, today)
      if (value !== null)
        entries[iso] = value
    }

    return entries
  })
}

function isEveryNthDay(date: Date, n: number, offset = 0): boolean {
  return ((date.getDate() + offset) % n) === 0
}

async function seedDevHabitDataIfNeeded() {
  if (!SHOULD_AUTO_SEED_DEV_HABITS || !settings.baseFolderUri || hasAttemptedDevHabitSeed)
    return

  hasAttemptedDevHabitSeed = true

  try {
    const listed = await FolderPicker.listFiles({ folderUri: settings.baseFolderUri })
    const hasHabitConfig = listed.files.some(file =>
      file.isFile
      && file.name.toLowerCase().endsWith('.md')
      && file.name.toLowerCase().includes('habit'),
    )

    if (hasHabitConfig)
      return

    const created = todayIso()
    const sampleHabits = [
      {
        id: 'dev-daily-walk',
        name: 'Daily Walk',
        icon: '🚶',
        unit: 'walk',
        reminder: '06:30',
        fileName: `${HABIT_CONFIGS_DIR}/habit-dev-daily-walk.md`,
        entries: createDevHabitEntries((date) => {
          const weekday = date.getDay()
          if (weekday === 0)
            return 'skip'
          if (weekday === 6)
            return isEveryNthDay(date, 2) ? 1 : null
          return 1
        }),
      },
      {
        id: 'dev-reading',
        name: 'Read 20 min',
        icon: '📚',
        unit: 'session',
        reminder: '20:30',
        fileName: `${HABIT_CONFIGS_DIR}/habit-dev-reading.md`,
        entries: createDevHabitEntries((date, today) => {
          const iso = dateToIso(date)
          if (iso === today)
            return null
          if (date.getDay() === 2)
            return 'fail'
          return date.getDate() % 5 === 0 ? 'skip' : 1
        }),
      },
      {
        id: 'dev-stretch',
        name: 'Morning Stretch',
        icon: '\uD83E\uDDD8',
        unit: 'stretch',
        reminder: '07:00',
        fileName: `${HABIT_CONFIGS_DIR}/habit-dev-stretch.md`,
        entries: createDevHabitEntries((date, today) => {
          const iso = dateToIso(date)
          if (iso === today)
            return 1
          if (isEveryNthDay(date, 9))
            return 'fail'
          if (isEveryNthDay(date, 6, 1))
            return 'skip'
          return 1
        }),
      },
      {
        id: 'dev-journal',
        name: 'Journal',
        icon: '\u270D\uFE0F',
        unit: 'entry',
        reminder: '21:15',
        fileName: `${HABIT_CONFIGS_DIR}/habit-dev-journal.md`,
        entries: createDevHabitEntries((date, today) => {
          const iso = dateToIso(date)
          if (iso === today)
            return null
          if (date.getDay() === 5 || isEveryNthDay(date, 4))
            return null
          if (isEveryNthDay(date, 11))
            return 'fail'
          return 1
        }),
      },
    ]

    for (const habit of sampleHabits) {
      await FolderPicker.writeFile({
        folderUri: settings.baseFolderUri,
        fileName: habit.fileName,
        content: createDevHabitContent({
          id: habit.id,
          name: habit.name,
          icon: habit.icon,
          unit: habit.unit,
          reminder: habit.reminder,
          created,
          fileName: habit.fileName,
        }),
      })

      for (let monthOffset = 0; monthOffset < habit.entries.length; monthOffset += 1) {
        const monthEntries = habit.entries[monthOffset]
        if (!monthEntries || Object.keys(monthEntries).length === 0)
          continue

        const monthIso = monthIsoFromDate(getMonthDateForOffset(monthOffset))
        await FolderPicker.writeFile({
          folderUri: settings.baseFolderUri,
          fileName: getHabitLogMonthFileName({
            id: habit.id,
            name: habit.name,
            icon: habit.icon,
            period: 'day',
            targetCount: 1,
            targetDays: 1,
            unit: habit.unit,
            reminder: habit.reminder,
            allowSkip: true,
            scheduledDays: [1, 2, 3, 4, 5, 6, 7],
            currentStreak: 0,
            bestStreak: 0,
            streakStatsVersion: DERIVED_STREAK_STATS_VERSION,
            fileName: habit.fileName,
          }, monthIso),
          content: serializeHabitMonthLog({
            id: habit.id,
            name: habit.name,
            icon: habit.icon,
            period: 'day',
            targetCount: 1,
            targetDays: 1,
            unit: habit.unit,
            reminder: habit.reminder,
            allowSkip: true,
            scheduledDays: [1, 2, 3, 4, 5, 6, 7],
            currentStreak: 0,
            bestStreak: 0,
            streakStatsVersion: DERIVED_STREAK_STATS_VERSION,
            fileName: habit.fileName,
          }, monthIso, monthEntries),
        })
      }
    }
  }
  catch (err) {
    console.warn('Failed to seed dev habits', err)
  }
}

function parseHabitMonthLog(content: string): Record<string, HabitLogValue> {
  const frontmatter = parseFrontmatter(content)
  if (frontmatter.type !== 'habit-log')
    return {}

  const month = typeof frontmatter.month === 'string' ? frontmatter.month : ''
  if (!/^\d{4}-\d{2}$/.test(month))
    return {}

  const result: Record<string, HabitLogValue> = {}
  for (const [key, raw] of Object.entries(frontmatter)) {
    const match = key.match(/^d(\d{2})$/)
    if (!match)
      continue

    const day = match[1]
    if (!day)
      continue

    const value = normalizeHabitLogValue(raw)
    if (value === null)
      continue

    result[`${month}-${day}`] = value
  }

  return result
}

async function loadHabitLogsForHabit(
  habit: HabitCard,
  monthsToLoad = HABIT_LOG_INITIAL_MONTHS_TO_LOAD,
): Promise<Record<string, HabitLogValue>> {
  if (!settings.baseFolderUri)
    return {}

  const logs: Record<string, HabitLogValue> = {}
  const monthFileNames: string[] = []

  for (let offset = 0; offset < monthsToLoad; offset += 1) {
    const date = new Date()
    date.setDate(1)
    date.setMonth(date.getMonth() - offset)
    const monthIso = monthIsoFromDate(date)
    monthFileNames.push(getHabitLogMonthFileName(habit, monthIso))
  }

  const monthReads = await mapWithConcurrency(
    monthFileNames,
    HABIT_LOG_READ_CONCURRENCY,
    async (fileName) => {
      try {
        const read = await FolderPicker.readFile({ folderUri: settings.baseFolderUri as string, fileName })
        return parseHabitMonthLog(read.content)
      }
      catch {
        return null
      }
    },
  )

  for (const monthLog of monthReads) {
    if (monthLog)
      Object.assign(logs, monthLog)
  }

  return logs
}

async function loadAllHabitLogsForHabit(habit: HabitCard): Promise<Record<string, HabitLogValue>> {
  if (!settings.baseFolderUri)
    return {}

  const listed = await FolderPicker.listFiles({
    folderUri: settings.baseFolderUri,
    relativePath: HABIT_LOGS_DIR,
  })

  const fileStem = `${getHabitLogStem(habit)}-`.toLowerCase()
  const monthFileNames = listed.files
    .filter(file => file.isFile && file.name.toLowerCase().startsWith(fileStem) && file.name.toLowerCase().endsWith('.md'))
    .map(file => `${HABIT_LOGS_DIR}/${file.name}`)

  const logs: Record<string, HabitLogValue> = {}
  const monthReads = await mapWithConcurrency(
    monthFileNames,
    HABIT_LOG_READ_CONCURRENCY,
    async (fileName) => {
      try {
        const read = await FolderPicker.readFile({ folderUri: settings.baseFolderUri as string, fileName })
        return parseHabitMonthLog(read.content)
      }
      catch {
        return null
      }
    },
  )

  for (const monthLog of monthReads) {
    if (monthLog)
      Object.assign(logs, monthLog)
  }

  return logs
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

function daysInMonth(date: Date): number {
  return new Date(date.getFullYear(), date.getMonth() + 1, 0).getDate()
}

function isScheduledForDate(habit: HabitCard, date: Date): boolean {
  if (!habit.scheduledDays.length)
    return true
  return habit.scheduledDays.includes(toWeekdayIndex(date))
}

function getMonthDateForOffset(monthOffset: number): Date {
  const date = new Date()
  date.setHours(0, 0, 0, 0)
  date.setDate(1)
  date.setMonth(date.getMonth() - Math.max(0, monthOffset))
  return date
}

function isLoadingOlderMonths(habit: HabitCard): boolean {
  return Boolean(isHabitLogLazyLoading.value[habit.fileName])
}

function isHydratingHabitCard(habit: HabitCard): boolean {
  return Boolean(isHabitCardHydrating.value[habit.fileName])
}

async function ensureHabitLogsLoadedForOffset(habit: HabitCard, monthOffset: number) {
  if (!settings.baseFolderUri)
    return

  const requiredMonths = Math.max(HABIT_LOG_INITIAL_MONTHS_TO_LOAD, monthOffset + 1)
  const loadedMonths = loadedHabitLogMonths.value[habit.fileName] || HABIT_LOG_INITIAL_MONTHS_TO_LOAD

  if (requiredMonths <= loadedMonths)
    return

  if (isHabitLogLazyLoading.value[habit.fileName])
    return

  isHabitLogLazyLoading.value = {
    ...isHabitLogLazyLoading.value,
    [habit.fileName]: true,
  }

  try {
    const logs = await loadHabitLogsForHabit(habit, requiredMonths)
    habitLogs.value = {
      ...habitLogs.value,
      [habit.fileName]: {
        ...(habitLogs.value[habit.fileName] || {}),
        ...logs,
      },
    }

    loadedHabitLogMonths.value = {
      ...loadedHabitLogMonths.value,
      [habit.fileName]: requiredMonths,
    }

    syncHabitCache()
  }
  catch (err) {
    console.warn('Failed to lazy-load older habit logs', habit.fileName, err)
  }
  finally {
    isHabitLogLazyLoading.value = {
      ...isHabitLogLazyLoading.value,
      [habit.fileName]: false,
    }
  }
}

function computeHeatmapForMonth(habit: HabitCard, monthOffset: number): HeatmapCell[] {
  const monthDate = getMonthDateForOffset(monthOffset)
  const monthStart = new Date(monthDate.getFullYear(), monthDate.getMonth(), 1)
  const totalDays = daysInMonth(monthDate)
  const leadingPadding = (monthStart.getDay() + 6) % 7
  const logs = habitLogs.value[habit.fileName] || {}
  const today = todayIso()
  const cells: HeatmapCell[] = []

  for (let i = 0; i < leadingPadding; i += 1) {
    cells.push({
      date: `placeholder-leading-${i}`,
      value: null,
      level: 0,
      isToday: false,
      isScheduled: false,
      isFuture: false,
      isPlaceholder: true,
    })
  }

  for (let day = 1; day <= totalDays; day += 1) {
    const date = new Date(monthDate.getFullYear(), monthDate.getMonth(), day)
    const iso = dateToIso(date)
    const isFuture = iso > today

    if (isFuture) {
      cells.push({
        date: iso,
        value: null,
        level: 0,
        isToday: false,
        isScheduled: isScheduledForDate(habit, date),
        isFuture: true,
        isPlaceholder: false,
      })
      continue
    }

    const value = logs[iso] ?? null
    let level: 0 | 1 | 2 | 3 | 4 = 0

    if (value === 'skip') {
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
      isFuture: false,
      isPlaceholder: false,
    })
  }

  const remainder = cells.length % 7
  if (remainder > 0) {
    const trailing = 7 - remainder
    for (let i = 0; i < trailing; i += 1) {
      cells.push({
        date: `placeholder-trailing-${i}`,
        value: null,
        level: 0,
        isToday: false,
        isScheduled: false,
        isFuture: false,
        isPlaceholder: true,
      })
    }
  }

  return cells
}

function getHeatmapForMonth(habit: HabitCard, monthOffset: number): HeatmapCell[] {
  return computeHeatmapForMonth(habit, monthOffset)
}

function getLoadedMonthOffsets(habit: HabitCard): number[] {
  const loadedMonths = Math.max(1, loadedHabitLogMonths.value[habit.fileName] || HABIT_LOG_INITIAL_MONTHS_TO_LOAD)
  return Array.from({ length: loadedMonths }, (_, index) => loadedMonths - 1 - index)
}

function getTimelineMonthLabel(monthOffset: number): string {
  return new Intl.DateTimeFormat(undefined, { month: 'short' }).format(getMonthDateForOffset(monthOffset))
}

function getTimelineWeekdayLabel(index: number): string {
  return ['M', 'T', 'W', 'T', 'F', 'S', 'S'][index] || ''
}

function setHabitTimelineRef(fileName: string, element: Element | ComponentPublicInstance | null) {
  if (element instanceof HTMLElement)
    habitTimelineRefs.set(fileName, element)
  else
    habitTimelineRefs.delete(fileName)
}

async function scrollHabitTimelineToCurrent(habit: HabitCard) {
  await nextTick()
  const element = habitTimelineRefs.get(habit.fileName)
  if (!element)
    return
  element.scrollLeft = element.scrollWidth
}

async function scrollAllHabitTimelinesToCurrent() {
  await nextTick()
  await Promise.all(habits.value.map(habit => scrollHabitTimelineToCurrent(habit)))
}

async function loadOlderTimelineMonths(habit: HabitCard, element: HTMLElement) {
  if (isLoadingOlderMonths(habit))
    return

  const previousLoadedMonths = loadedHabitLogMonths.value[habit.fileName] || HABIT_LOG_INITIAL_MONTHS_TO_LOAD
  const previousScrollWidth = element.scrollWidth
  const previousScrollLeft = element.scrollLeft
  await ensureHabitLogsLoadedForOffset(habit, previousLoadedMonths)
  const nextLoadedMonths = loadedHabitLogMonths.value[habit.fileName] || HABIT_LOG_INITIAL_MONTHS_TO_LOAD
  if (nextLoadedMonths <= previousLoadedMonths)
    return

  await nextTick()
  const updatedElement = habitTimelineRefs.get(habit.fileName)
  if (!updatedElement)
    return
  updatedElement.scrollLeft = previousScrollLeft + (updatedElement.scrollWidth - previousScrollWidth)
}

function collapseHabitTimelineToCurrent(habit: HabitCard) {
  const loadedMonths = loadedHabitLogMonths.value[habit.fileName] || HABIT_LOG_INITIAL_MONTHS_TO_LOAD
  if (loadedMonths <= HABIT_LOG_INITIAL_MONTHS_TO_LOAD)
    return

  loadedHabitLogMonths.value = {
    ...loadedHabitLogMonths.value,
    [habit.fileName]: HABIT_LOG_INITIAL_MONTHS_TO_LOAD,
  }

  void scrollHabitTimelineToCurrent(habit)
}

function handleHabitTimelineTouchStart(habit: HabitCard, event: TouchEvent) {
  const touch = event.touches[0]
  if (!touch)
    return
  habitTimelineTouchStartX.set(habit.fileName, touch.clientX)
}

function handleHabitTimelinePointerDown(habit: HabitCard, event: PointerEvent) {
  if (event.pointerType === 'mouse' || event.pointerType === 'pen')
    habitTimelinePointerStartX.set(habit.fileName, event.clientX)
}

function handleHabitTimelineTouchEnd(habit: HabitCard, event: TouchEvent, isDetail = false) {
  const startX = habitTimelineTouchStartX.get(habit.fileName)
  habitTimelineTouchStartX.delete(habit.fileName)
  if (startX === undefined)
    return

  const touch = event.changedTouches[0]
  if (!touch)
    return

  const deltaX = touch.clientX - startX
  const element = habitTimelineRefs.get(habit.fileName)
  if (!element)
    return

  if (!isDetail && deltaX > 32 && element.scrollLeft <= 24)
    void loadOlderTimelineMonths(habit, element)

  const rightEdgeThreshold = Math.max(20, Math.round(element.clientWidth * 0.05))
  if (
    !isDetail
    && deltaX < -24
    && element.scrollWidth - element.clientWidth - element.scrollLeft <= rightEdgeThreshold
  ) {
    collapseHabitTimelineToCurrent(habit)
  }
}

function handleHabitTimelinePointerUp(habit: HabitCard, event: PointerEvent, isDetail = false) {
  const startX = habitTimelinePointerStartX.get(habit.fileName)
  habitTimelinePointerStartX.delete(habit.fileName)
  if (startX === undefined)
    return

  const deltaX = event.clientX - startX
  const element = habitTimelineRefs.get(habit.fileName)
  if (!element)
    return

  if (!isDetail && deltaX > 32 && element.scrollLeft <= 24)
    void loadOlderTimelineMonths(habit, element)

  const rightEdgeThreshold = Math.max(20, Math.round(element.clientWidth * 0.05))
  if (
    !isDetail
    && deltaX < -24
    && element.scrollWidth - element.clientWidth - element.scrollLeft <= rightEdgeThreshold
  ) {
    collapseHabitTimelineToCurrent(habit)
  }
}

function handleHabitTimelineScroll(habit: HabitCard, event: Event, isDetail = false) {
  const element = event.target
  if (!(element instanceof HTMLElement))
    return

  const preloadThreshold = Math.max(120, Math.round(element.clientWidth * 0.2))
  if (!isDetail && element.scrollLeft <= preloadThreshold)
    void loadOlderTimelineMonths(habit, element)
}

function getSelectedDate(habit: HabitCard): string {
  return selectedDates.value[habit.fileName] || todayIso()
}

function getSelectedDateLabel(habit: HabitCard): string {
  const selected = getSelectedDate(habit)
  if (selected === todayIso())
    return 'Today'

  return new Intl.DateTimeFormat(undefined, {
    weekday: 'short',
    month: 'short',
    day: 'numeric',
  }).format(isoToDate(selected))
}

// function getSelectedDateTitle(habit: HabitCard): string {
//   return `Editing ${getSelectedDateLabel(habit)}`
// }

function getSelectedValue(habit: HabitCard): HabitLogValue | null {
  const log = habitLogs.value[habit.fileName] || {}
  return log[getSelectedDate(habit)] ?? null
}

function selectCell(habit: HabitCard, cell: HeatmapCell) {
  if (cell.isFuture || cell.isPlaceholder)
    return

  selectedDates.value = {
    ...selectedDates.value,
    [habit.fileName]: cell.date,
  }
}

function isSelectedCell(habit: HabitCard, cell: HeatmapCell): boolean {
  if (cell.isPlaceholder)
    return false

  return getSelectedDate(habit) === cell.date
}

function getScheduledDaysCount(habit: HabitCard): number {
  return habit.scheduledDays.length || 7
}

function getRequiredDays(habit: HabitCard, eligibleDays = getScheduledDaysCount(habit)): number {
  if (habit.period === 'month')
    return Math.min(Math.max(1, habit.targetDays), 31)

  return Math.min(Math.max(1, habit.targetDays), Math.max(1, eligibleDays))
}

function getTargetSummary(habit: HabitCard): string {
  if (habit.period === 'week')
    return `Goal: ${habit.targetCount} ${habit.unit} on ${getRequiredDays(habit)}/${getScheduledDaysCount(habit)} days`

  if (habit.period === 'month')
    return `Goal: ${habit.targetCount} ${habit.unit} on ${getRequiredDays(habit)} days this month`

  return `Goal: ${habit.targetCount} ${habit.unit} per day`
}

function formatMonthLabel(monthOffset: number): string {
  return new Intl.DateTimeFormat(undefined, {
    month: 'long',
    year: 'numeric',
  }).format(getMonthDateForOffset(monthOffset))
}

const selectedHabit = computed(() => {
  const habitKey = getRequestedHabitKey()
  if (!habitKey)
    return null

  const decodedKey = decodeURIComponent(habitKey)
  return habits.value.find(habit => habit.id === decodedKey || habit.fileName === decodedKey) ?? null
})

const isHabitDetailView = computed(() => Boolean(getRequestedHabitKey() && selectedHabit.value))

const detailMonthOffsets = computed(() =>
  selectedHabit.value
    ? Array.from({ length: HABIT_DETAIL_MONTH_COUNT }, (_, index) => HABIT_DETAIL_MONTH_COUNT - 1 - index)
    : [],
)

const selectedHabitMonthSummaries = computed(() => {
  if (!selectedHabit.value)
    return []
  return detailMonthOffsets.value.map(monthOffset => ({
    offset: monthOffset,
    label: formatMonthLabel(monthOffset),
  }))
})

async function preloadHabitDetailHistory(habit: HabitCard | null) {
  if (!habit)
    return

  if (isHabitDetailLoading.value)
    return

  isHabitDetailLoading.value = true

  try {
    await ensureHabitLogsLoadedForOffset(habit, HABIT_DETAIL_MONTH_COUNT - 1)
  }
  finally {
    isHabitDetailLoading.value = false
  }
}

watch(
  () => [route.params.habitId, habits.value.map(habit => habit.fileName).join('|')] as const,
  async () => {
    await preloadHabitDetailHistory(selectedHabit.value)
  },
  { immediate: false },
)

async function writeHabitLogMonth(habit: HabitCard, dateIso: string) {
  if (!settings.baseFolderUri)
    return

  const monthIso = monthIsoFromDateIso(dateIso)
  const monthEntries = Object.fromEntries(
    Object.entries(habitLogs.value[habit.fileName] || {})
      .filter(([date]) => date.startsWith(`${monthIso}-`)),
  )

  habitSaving.value[habit.fileName] = true
  habitSaveErrors.value[habit.fileName] = ''

  try {
    const monthFileName = getHabitLogMonthFileName(habit, monthIso)
    const hasEntries = Object.keys(monthEntries).length > 0
    if (!hasEntries) {
      try {
        await FolderPicker.deleteFile({ folderUri: settings.baseFolderUri, fileName: monthFileName })
      }
      catch {
        // already absent
      }
      return
    }

    await FolderPicker.writeFile({
      folderUri: settings.baseFolderUri,
      fileName: monthFileName,
      content: serializeHabitMonthLog(habit, monthIso, monthEntries),
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

function withUpdatedHabitIcon(content: string, icon: string): string {
  const trimmedIcon = icon.trim()
  const frontmatterMatch = content.match(/^---\r?\n([\s\S]*?)\r?\n---/)
  if (!frontmatterMatch)
    return content

  const frontmatterBody = frontmatterMatch[1] || ''
  const lines = frontmatterBody.split(/\r?\n/)
  const iconLine = `icon: ${JSON.stringify(trimmedIcon)}`
  let hasIcon = false

  const nextLines = lines.flatMap((line) => {
    if (/^\s*icon\s*:/.test(line)) {
      hasIcon = true
      return trimmedIcon ? [iconLine] : []
    }
    return [line]
  })

  if (trimmedIcon && !hasIcon)
    nextLines.push(iconLine)

  const nextFrontmatter = `---\n${nextLines.join('\n')}\n---`
  return content.replace(/^---\r?\n[\s\S]*?\r?\n---/, nextFrontmatter)
}

async function syncHabitsToWidget() {
  if (!settings.baseFolderUri)
    return

  const habitsForWidget = habits.value.map(h => ({
    id: h.id,
    file: h.fileName,
    name: h.name,
    icon: h.icon,
    target: h.targetCount,
  }))

  await WidgetSync.syncHabits({
    folderUri: settings.baseFolderUri,
    habitsJson: JSON.stringify(habitsForWidget),
    theme: settings.theme,
    accentColor: settings.accentColor || getThemeAccentColor(settings.theme),
  })
}

async function editHabitIcon(habit: HabitCard) {
  if (!settings.baseFolderUri)
    return

  const response = window.prompt('Set habit icon (emoji or short text). Leave empty to clear.', habit.icon || '')
  if (response === null)
    return

  const nextIcon = response.trim().slice(0, 8)
  if (nextIcon === habit.icon)
    return

  habitSaving.value[habit.fileName] = true
  habitSaveErrors.value[habit.fileName] = ''

  try {
    const read = await FolderPicker.readFile({ folderUri: settings.baseFolderUri, fileName: habit.fileName })
    const nextContent = withUpdatedHabitIcon(read.content, nextIcon)

    await FolderPicker.writeFile({
      folderUri: settings.baseFolderUri,
      fileName: habit.fileName,
      content: nextContent,
    })

    habits.value = habits.value.map(current => current.fileName === habit.fileName
      ? { ...current, icon: nextIcon }
      : current)

    await syncHabitsToWidget()
  }
  catch (err) {
    console.error('Failed to update habit icon', err)
    habitSaveErrors.value[habit.fileName] = 'Could not update habit icon.'
  }
  finally {
    habitSaving.value[habit.fileName] = false
  }
}

async function setSelectedLogValue(habit: HabitCard, value: HabitLogValue | null) {
  await setLogValueForDate(habit, getSelectedDate(habit), value)
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

  await writeHabitLogMonth(habit, date)
  void refreshHabitDerivedStats(habit).catch(() => { })
  void syncHabitsToWidget().catch(() => { })
}

function getSelectedNumberInputValue(habit: HabitCard): string {
  const selected = getSelectedValue(habit)
  return typeof selected === 'number' ? String(selected) : ''
}

function getSelectedProgressRatio(habit: HabitCard): number {
  const selected = getSelectedValue(habit)
  if (typeof selected !== 'number')
    return 0
  if (habit.targetCount <= 0)
    return 0
  return Math.max(0, Math.min(1, selected / habit.targetCount))
}

function getCountInputStyle(habit: HabitCard): CSSProperties {
  const percent = Math.round(getSelectedProgressRatio(habit) * 100)
  return {
    background: `linear-gradient(90deg, color-mix(in srgb, var(--primary) 24%, transparent) 0%, color-mix(in srgb, var(--primary) 30%, transparent) ${percent}%, color-mix(in srgb, var(--c-glass) 10%, transparent) ${percent}%, color-mix(in srgb, var(--c-glass) 10%, transparent) 100%)`,
  }
}

async function handleSelectedNumberInput(habit: HabitCard, event: Event) {
  const target = event.target
  if (!(target instanceof HTMLInputElement))
    return

  const rawValue = target.value.trim()
  if (!rawValue) {
    await clearSelectedValue(habit)
    return
  }

  const nextValue = Math.max(0, Math.floor(Number(rawValue)))
  if (!Number.isFinite(nextValue))
    return

  await setSelectedLogValue(habit, nextValue === 0 ? null : nextValue)
}

async function markSelectedDone(habit: HabitCard) {
  await setSelectedLogValue(habit, Math.max(1, habit.targetCount))
}

async function markSelectedFail(habit: HabitCard) {
  await setSelectedLogValue(habit, 'fail')
}

async function markSelectedSkip(habit: HabitCard) {
  await setSelectedLogValue(habit, 'skip')
}

async function clearSelectedValue(habit: HabitCard) {
  await setSelectedLogValue(habit, null)
}

function getCellState(habit: HabitCard, cell: HeatmapCell): HabitCellState {
  if (cell.value === 'skip')
    return 'skip'
  if (cell.value === 'fail')
    return 'fail'
  if (typeof cell.value === 'number' && cell.value >= habit.targetCount)
    return 'complete'
  if (typeof cell.value === 'number' && cell.value > 0)
    return 'partial'
  return 'none'
}

function cellTitle(habit: HabitCard, cell: HeatmapCell): string {
  if (cell.isPlaceholder)
    return ''

  const state = getCellState(habit, cell)
  const valueText = state === 'complete'
    ? `complete (${typeof cell.value === 'number' ? cell.value : habit.targetCount}/${habit.targetCount})`
    : state === 'partial'
      ? `partial (${typeof cell.value === 'number' ? cell.value : 0}/${habit.targetCount})`
      : state === 'skip'
        ? 'skip'
        : state === 'fail'
          ? 'fail'
          : 'no entry'
  return `${cell.date}: ${valueText}`
}

function isSuccessValue(habit: HabitCard, value: HabitLogValue | null): boolean {
  if (value === 'fail' || value === null)
    return false
  if (value === 'skip')
    return false
  return value >= habit.targetCount
}

function isNeutralValue(value: HabitLogValue | null): boolean {
  return value === 'skip' || value === null
}

function computeDailyStreakStats(
  habit: HabitCard,
  log: Record<string, HabitLogValue>,
): { current: number, best: number } {
  const keys = Object.keys(log).sort()
  const today = isoToDate(todayIso())
  const fallbackStart = dateAddDays(today, -365)
  const start = keys.length > 0 ? isoToDate(keys[0] as string) : fallbackStart

  let current = 0
  for (let offset = 0; offset <= 365; offset += 1) {
    const date = dateAddDays(today, -offset)
    if (!isScheduledForDate(habit, date))
      continue

    const value = log[dateToIso(date)] ?? null

    if (isNeutralValue(value))
      continue

    if (isSuccessValue(habit, value)) {
      current += 1
      continue
    }

    break
  }

  let best = 0
  let running = 0
  for (let date = new Date(start); date <= today; date = dateAddDays(date, 1)) {
    if (!isScheduledForDate(habit, date))
      continue

    const value = log[dateToIso(date)] ?? null

    if (isNeutralValue(value))
      continue

    if (isSuccessValue(habit, value)) {
      running += 1
      if (running > best)
        best = running
    }
    else {
      running = 0
    }
  }

  return { current, best }
}

function serializeFrontmatterValue(value: string | number | boolean): string {
  if (typeof value === 'string')
    return JSON.stringify(value)
  return String(value)
}

function withUpdatedHabitFrontmatterFields(
  content: string,
  fields: Record<string, string | number | boolean>,
): string {
  const frontmatterMatch = content.match(/^---\r?\n([\s\S]*?)\r?\n---/)
  if (!frontmatterMatch)
    return content

  const frontmatterBody = frontmatterMatch[1] || ''
  let lines = frontmatterBody.split(/\r?\n/)

  for (const [key, value] of Object.entries(fields)) {
    const replacement = `${key}: ${serializeFrontmatterValue(value)}`
    const index = lines.findIndex(line => new RegExp(`^\\s*${key}\\s*:`).test(line))
    if (index >= 0)
      lines[index] = replacement
    else
      lines.push(replacement)
  }

  const nextFrontmatter = `---\n${lines.join('\n')}\n---`
  return content.replace(/^---\r?\n[\s\S]*?\r?\n---/, nextFrontmatter)
}

function applyHabitDerivedStats(habitFileName: string, currentStreak: number, bestStreak: number) {
  habits.value = habits.value.map(habit => habit.fileName === habitFileName
    ? {
        ...habit,
        currentStreak,
        bestStreak,
        streakStatsVersion: DERIVED_STREAK_STATS_VERSION,
      }
    : habit)

  const cachedDefinition = habitDefinitionCache.get(getHabitDefinitionCacheKey(habitFileName))
  if (cachedDefinition?.habit) {
    habitDefinitionCache.set(getHabitDefinitionCacheKey(habitFileName), {
      ...cachedDefinition,
      habit: {
        ...cachedDefinition.habit,
        currentStreak,
        bestStreak,
        streakStatsVersion: DERIVED_STREAK_STATS_VERSION,
      },
    })
  }
}

async function refreshHabitDerivedStats(habit: HabitCard) {
  if (!settings.baseFolderUri || habit.period === 'month')
    return

  try {
    const fullLog = await loadAllHabitLogsForHabit(habit)
    const { current, best } = computeDailyStreakStats(habit, fullLog)

    applyHabitDerivedStats(habit.fileName, current, best)
    syncHabitCache()

    const read = await FolderPicker.readFile({ folderUri: settings.baseFolderUri, fileName: habit.fileName })
    const nextContent = withUpdatedHabitFrontmatterFields(read.content, {
      currentStreak: current,
      bestStreak: best,
      streakStatsVersion: DERIVED_STREAK_STATS_VERSION,
    })

    if (nextContent !== read.content) {
      await FolderPicker.writeFile({
        folderUri: settings.baseFolderUri,
        fileName: habit.fileName,
        content: nextContent,
      })
    }
  }
  catch (err) {
    console.warn('Failed to refresh derived habit streak stats', habit.fileName, err)
  }
}

async function backfillMissingHabitDerivedStats(habitsToBackfill: HabitCard[]) {
  await mapWithConcurrency(
    habitsToBackfill,
    HABIT_DERIVED_STATS_SYNC_CONCURRENCY,
    async (habit) => {
      await refreshHabitDerivedStats(habit)
      return null
    },
  )
}

function getMonthlyScoreFromCurrentMonth(habit: HabitCard): { success: number, required: number } {
  const log = habitLogs.value[habit.fileName] || {}
  const today = new Date()
  today.setHours(0, 0, 0, 0)

  const monthStart = new Date(today.getFullYear(), today.getMonth(), 1)
  const required = getRequiredDays(habit)
  let success = 0

  for (let date = new Date(monthStart); date <= today; date = dateAddDays(date, 1)) {
    if (!isScheduledForDate(habit, date))
      continue

    const value = log[dateToIso(date)] ?? null
    if (isSuccessValue(habit, value))
      success += 1
  }

  return {
    success,
    required,
  }
}

function getCurrentStreak(habit: HabitCard): number {
  if (habit.period === 'month') {
    const { success } = getMonthlyScoreFromCurrentMonth(habit)
    return success
  }

  if (habit.streakStatsVersion === DERIVED_STREAK_STATS_VERSION)
    return habit.currentStreak

  const log = habitLogs.value[habit.fileName] || {}
  return computeDailyStreakStats(habit, log).current
}

function getBestStreak(habit: HabitCard): number {
  if (habit.period === 'month') {
    const { required } = getMonthlyScoreFromCurrentMonth(habit)
    return required
  }

  if (habit.streakStatsVersion === DERIVED_STREAK_STATS_VERSION)
    return habit.bestStreak

  const log = habitLogs.value[habit.fileName] || {}
  return computeDailyStreakStats(habit, log).best
}

function computeStreakSummary(habit: HabitCard): string {
  if (habit.period === 'month') {
    const { success, required } = getMonthlyScoreFromCurrentMonth(habit)
    return `Month: ${success}/${required} days`
  }

  const current = getCurrentStreak(habit)
  const best = getBestStreak(habit)
  return `Streak: ${current} days current, ${best} days best`
}

const streakSummaryMap = computed(() => {
  const m = new Map<string, string>()
  for (const habit of habits.value)
    m.set(habit.fileName, computeStreakSummary(habit))
  return m
})

function getStreakSummary(habit: HabitCard): string {
  return streakSummaryMap.value.get(habit.fileName) ?? ''
}

async function loadHabits(options: { preferCache?: boolean, force?: boolean } = {}) {
  const loadToken = ++habitsLoadToken
  const folderUri = settings.baseFolderUri

  if (!folderUri) {
    habits.value = []
    habitLogs.value = {}
    selectedDates.value = {}
    loadedHabitLogMonths.value = {}
    isHabitCardHydrating.value = {}
    cachedSnapshot = null
    cachedFolderUri = ''
    cachedAt = 0
    habitDefinitionCache.clear()
    isLoading.value = false
    return
  }

  const shouldDebounceRefresh = !options.force
    && cachedSnapshot
    && cachedFolderUri === folderUri
    && Date.now() - cachedAt < HABITS_REFRESH_DEBOUNCE_MS

  if (!options.force && options.preferCache && cachedSnapshot && cachedFolderUri === folderUri) {
    applyHabitSnapshot(cachedSnapshot)
    isLoading.value = false
  }

  if (shouldDebounceRefresh)
    return

  if (activeHabitsLoadPromise)
    return activeHabitsLoadPromise

  isLoading.value = true
  error.value = ''

  activeHabitsLoadPromise = (async () => {
    try {
      await seedDevHabitDataIfNeeded()

      const listed = await FolderPicker.listFiles({
        folderUri,
        relativePath: HABIT_CONFIGS_DIR,
      })
      const mdFiles = listed.files.filter(file => file.isFile && file.name.toLowerCase().endsWith('.md'))

      const loaded = await mapWithConcurrency(
        mdFiles,
        HABIT_DEFINITION_READ_CONCURRENCY,
        async (file) => {
          try {
            const habitFilePath = `${HABIT_CONFIGS_DIR}/${file.name}`
            const habitCacheKey = getHabitDefinitionCacheKey(habitFilePath, folderUri)
            const signature = getFileSignature(file)
            const cachedDefinition = habitDefinitionCache.get(habitCacheKey)

            if (signature && cachedDefinition && cachedDefinition.signature === signature)
              return cachedDefinition.habit ? { ...cachedDefinition.habit, scheduledDays: [...cachedDefinition.habit.scheduledDays] } : null

            const read = await FolderPicker.readFile({ folderUri, fileName: habitFilePath })
            const frontmatter = parseFrontmatter(read.content)
            if (frontmatter.type !== 'habit') {
              if (signature)
                habitDefinitionCache.set(habitCacheKey, { signature, habit: null })
              else
                habitDefinitionCache.delete(habitCacheKey)

              return null
            }

            const period = frontmatter.period === 'week'
              ? 'week'
              : frontmatter.period === 'month'
                ? 'month'
                : 'day'
            const targetCount = period === 'week' && frontmatter.targetDays === undefined
              ? Math.max(1, Number(frontmatter.dailyTargetCount || 1))
              : Math.max(1, Number(frontmatter.targetCount || 1))
            const targetDays = period === 'week' || period === 'month'
              ? Math.max(1, Number(frontmatter.targetDays || frontmatter.targetCount || 1))
              : 1

            const parsedHabit = {
              id: String(frontmatter.id || ''),
              name: String(frontmatter.name || file.name.replace(/\.md$/i, '')),
              icon: String(frontmatter.icon || ''),
              period,
              targetCount,
              targetDays,
              unit: String(frontmatter.unit || 'times'),
              reminder: String(frontmatter.reminder || ''),
              allowSkip: String(frontmatter.allowSkip || 'true') !== 'false',
              scheduledDays: parseScheduledDays(frontmatter.scheduledDays),
              currentStreak: toNonNegativeInteger(frontmatter.currentStreak),
              bestStreak: toNonNegativeInteger(frontmatter.bestStreak),
              streakStatsVersion: toNonNegativeInteger(frontmatter.streakStatsVersion),
              fileName: habitFilePath,
            } satisfies HabitCard

            if (signature)
              habitDefinitionCache.set(habitCacheKey, { signature, habit: parsedHabit })
            else
              habitDefinitionCache.delete(habitCacheKey)

            return parsedHabit
          }
          catch {
            return null
          }
        },
      )

      const activeNames = new Set(mdFiles.map(file => getHabitDefinitionCacheKey(`${HABIT_CONFIGS_DIR}/${file.name}`, folderUri)))
      for (const cachedName of Array.from(habitDefinitionCache.keys())) {
        if (!activeNames.has(cachedName))
          habitDefinitionCache.delete(cachedName)
      }

      habits.value = loaded
        .filter((habit): habit is HabitCard => Boolean(habit))
        .sort((a, b) => a.name.localeCompare(b.name))

      selectedDates.value = habits.value.reduce<Record<string, string>>((acc, habit) => {
        acc[habit.fileName] = selectedDates.value[habit.fileName] || todayIso()
        return acc
      }, {})
      loadedHabitLogMonths.value = habits.value.reduce<Record<string, number>>((acc, habit) => {
        acc[habit.fileName] = HABIT_LOG_INITIAL_MONTHS_TO_LOAD
        return acc
      }, {})
      habitLogs.value = habits.value.reduce<Record<string, Record<string, HabitLogValue>>>((acc, habit) => {
        acc[habit.fileName] = habitLogs.value[habit.fileName] || {}
        return acc
      }, {})
      isHabitCardHydrating.value = habits.value.reduce<Record<string, boolean>>((acc, habit) => {
        acc[habit.fileName] = true
        return acc
      }, {})

      if (habits.value.length > 0) {
        isLoading.value = false
        await waitForNextFrame()
      }

      const hydratedLogsByFile: Record<string, Record<string, HabitLogValue>> = {}
      const hydratedFlagsByFile: Record<string, boolean> = {}

      await mapWithConcurrency(
        habits.value,
        HABIT_LAZY_LOAD_CONCURRENCY,
        async (habit) => {
          const logs = await loadHabitLogsForHabit(habit, HABIT_LOG_INITIAL_MONTHS_TO_LOAD)

          if (loadToken !== habitsLoadToken)
            return null

          hydratedLogsByFile[habit.fileName] = logs
          hydratedFlagsByFile[habit.fileName] = false

          return null
        },
      )

      if (loadToken !== habitsLoadToken)
        return

      habitLogs.value = {
        ...habitLogs.value,
        ...hydratedLogsByFile,
      }
      isHabitCardHydrating.value = {
        ...isHabitCardHydrating.value,
        ...hydratedFlagsByFile,
      }
      await scrollAllHabitTimelinesToCurrent()

      syncHabitCache()

      const habitsMissingDerivedStats = habits.value.filter(habit =>
        habit.period !== 'month' && habit.streakStatsVersion !== DERIVED_STREAK_STATS_VERSION,
      )
      if (habitsMissingDerivedStats.length > 0)
        void backfillMissingHabitDerivedStats(habitsMissingDerivedStats).catch(() => { })
    }
    catch (err) {
      console.error('Failed to load habits', err)
      error.value = 'Could not load habits from your folder.'
    }
    finally {
      isLoading.value = false
      activeHabitsLoadPromise = null
    }
  })()

  return activeHabitsLoadPromise
}

async function refreshHabitsFromExternal(force = false) {
  if (!settings.baseFolderUri)
    return
  if (isLoading.value)
    return

  if (!force) {
    const now = Date.now()
    if (now - lastExternalRefreshAt < EXTERNAL_REFRESH_COOLDOWN_MS)
      return

    lastExternalRefreshAt = now
  }

  await loadHabits({ force })
}

function clearMidnightRefreshTimer() {
  if (midnightRefreshTimer !== null) {
    window.clearTimeout(midnightRefreshTimer)
    midnightRefreshTimer = null
  }
}

function scheduleMidnightRefresh() {
  clearMidnightRefreshTimer()

  const next = new Date()
  next.setHours(24, 1, 0, 0)
  const delay = Math.max(1000, next.getTime() - Date.now())

  midnightRefreshTimer = window.setTimeout(() => {
    midnightRefreshTimer = null
    void refreshHabitsFromExternal(true).finally(() => {
      scheduleMidnightRefresh()
    })
  }, delay)
}

function handleVisibilityChange() {
  if (document.visibilityState === 'visible')
    void refreshHabitsFromExternal(true)
}

function handleWindowFocus() {
  void refreshHabitsFromExternal(true)
}

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
  if (!settings.baseFolderUri || isPullRefreshing.value)
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
    await refreshHabitsFromExternal(true)
  }
  finally {
    isPullRefreshing.value = false
    pullDistance.value = 0
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
    ].join('\n')

    await FolderPicker.writeFile({
      folderUri: settings.baseFolderUri,
      fileName,
      content,
    })

    await loadHabits()
    void syncHabitsToWidget().catch(() => { })
  }
  catch (err) {
    console.error('Failed to create example habit', err)
    exampleError.value = 'Could not create example habit file.'
  }
  finally {
    isCreatingExample.value = false
  }
}

let isFirstHabitsActivation = true

onMounted(async () => {
  document.addEventListener('visibilitychange', handleVisibilityChange)
  window.addEventListener('focus', handleWindowFocus)
  scheduleMidnightRefresh()
  await loadHabits({ preferCache: true })
  applyRouteHabitSelection()
  await preloadHabitDetailHistory(selectedHabit.value)
  await scrollAllHabitTimelinesToCurrent()
})

onActivated(async () => {
  if (isFirstHabitsActivation) {
    isFirstHabitsActivation = false
    return
  }
  Object.assign(settings, loadSettings())
  await loadHabits({ preferCache: true })
  applyRouteHabitSelection()
  await preloadHabitDetailHistory(selectedHabit.value)
  await scrollAllHabitTimelinesToCurrent()
})

onBeforeUnmount(() => {
  document.removeEventListener('visibilitychange', handleVisibilityChange)
  window.removeEventListener('focus', handleWindowFocus)
  clearMidnightRefreshTimer()
})
</script>

<template>
  <div class="page" @touchstart="onPullTouchStart" @touchmove="onPullTouchMove" @touchend="onPullTouchEnd"
    @touchcancel="onPullTouchEnd">
    <div class="pull-refresh"
      :class="{ 'is-visible': showPullIndicator, 'is-ready': isPullReady, 'is-refreshing': isPullRefreshing }"
      :aria-label="isPullRefreshing ? 'Refreshing habits' : 'Pull to refresh habits'" role="status">
      <div class="pull-refresh-spinner" aria-hidden="true" />
      <span>{{ isPullRefreshing ? 'Refreshing…' : isPullReady ? 'Release to refresh' : '' }}</span>
    </div>

    <div class="page-content" :style="pageContentStyle">
      <PageHeader :title="isHabitDetailView && selectedHabit ? selectedHabit.name : 'Habits'" @back="goBack">
        <template v-if="isHabitDetailView" #right>
          <button class="glass-button glass-button--secondary habit-detail-header-button" type="button"
            @click="goToHabitList">
            All habits
          </button>
        </template>
      </PageHeader>
      <div class="header legacy-header">
        <button class="glass-icon-button back-button" aria-label="Go home" @click="goBack">
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

      <div v-else-if="isLoading && habits.length === 0" class="card glass-card empty-state">
        <div class="loading-inline" aria-label="Loading habits" role="status">
          <div class="loading-spinner" aria-hidden="true" />
        </div>
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

      <div v-else-if="isHabitDetailView && selectedHabit" class="habit-detail-view">
        <div class="card glass-card habit-detail-hero">
          <div class="habit-detail-hero-main">
            <div class="habit-detail-icon">{{ selectedHabit.icon || '•' }}</div>
            <div class="habit-detail-intro">
              <p class="habit-detail-kicker">Individual habit view</p>
              <h2>{{ selectedHabit.name }}</h2>
              <p class="habit-detail-score">{{ getStreakSummary(selectedHabit) }}</p>
              <p class="habit-detail-goal">{{ getTargetSummary(selectedHabit) }}</p>
            </div>
          </div>
          <div class="habit-detail-stats">
            <div class="habit-detail-stat">
              <span class="habit-detail-stat-label">Current streak</span>
              <strong>{{ selectedHabit.currentStreak }}</strong>
            </div>
            <div class="habit-detail-stat">
              <span class="habit-detail-stat-label">Best streak</span>
              <strong>{{ selectedHabit.bestStreak }}</strong>
            </div>
            <div class="habit-detail-stat">
              <span class="habit-detail-stat-label">Reminder</span>
              <strong>{{ selectedHabit.reminder || 'None' }}</strong>
            </div>
          </div>
        </div>

        <div class="card glass-card habit-detail-months">
          <div class="habit-detail-section-head">
            <h3>Contribution History</h3>
          </div>
          <p v-if="isHabitDetailLoading" class="hint">Loading the last 12 months...</p>
          <div class="habit-timeline-shell habit-timeline-shell--detail">
            <div class="habit-weekday-rail" aria-hidden="true">
              <span v-for="dayIndex in 7" :key="`${selectedHabit.fileName}-detail-day-${dayIndex}`"
                class="habit-weekday-label">
                {{ getTimelineWeekdayLabel(dayIndex - 1) }}
              </span>
            </div>
            <div :ref="element => setHabitTimelineRef(selectedHabit?.fileName || 'habit-detail', element)" class="habit-timeline-scroll"
              role="img" :aria-label="`Heat map for ${selectedHabit.name}`"
              @scroll="handleHabitTimelineScroll(selectedHabit, $event, true)"
              @touchstart="handleHabitTimelineTouchStart(selectedHabit, $event)"
              @touchend="handleHabitTimelineTouchEnd(selectedHabit, $event, true)"
              @touchcancel="handleHabitTimelineTouchEnd(selectedHabit, $event, true)"
              @pointerdown="handleHabitTimelinePointerDown(selectedHabit, $event)"
              @pointerup="handleHabitTimelinePointerUp(selectedHabit, $event, true)"
              @pointercancel="handleHabitTimelinePointerUp(selectedHabit, $event, true)">
              <div class="habit-timeline-track">
                <div v-for="summary in selectedHabitMonthSummaries" :key="`${selectedHabit.fileName}-month-${summary.offset}`"
                  class="habit-timeline-month">
                  <span class="heatmap-month-label">{{ getTimelineMonthLabel(summary.offset) }}</span>
                  <div class="habit-month-grid">
                    <div v-for="cell in getHeatmapForMonth(selectedHabit, summary.offset)"
                      :key="`${selectedHabit.fileName}-${summary.offset}-${cell.date}`" class="heatmap-cell"
                      :class="[
                        `level-${cell.level}`,
                        {
                          'is-placeholder': cell.isPlaceholder,
                          'is-today': cell.isToday,
                          'is-unscheduled': !cell.isScheduled,
                          'is-future': cell.isFuture,
                          'is-fail': cell.value === 'fail',
                          'is-skip': cell.value === 'skip',
                          'is-clickable': !cell.isFuture && !cell.isPlaceholder,
                          'is-selected': isSelectedCell(selectedHabit, cell),
                        },
                      ]" :title="cellTitle(selectedHabit, cell)" @click="selectCell(selectedHabit, cell)" />
                  </div>
                </div>
              </div>
            </div>
          </div>
        </div>
      </div>

      <div v-else class="habit-list">
        <div v-for="habit in habits" :key="habit.fileName" class="card glass-card habit-card">
          <div class="habit-head">
            <button class="habit-icon-wrap habit-icon-button" type="button" :title="`Change icon for ${habit.name}`"
              :aria-label="`Change icon for ${habit.name}`" @click="editHabitIcon(habit)">
              <span v-if="habit.icon" class="habit-emoji">{{ habit.icon }}</span>
              <Flame v-else :size="16" />
            </button>
            <div>
              <h2>{{ habit.name }}</h2>
            </div>
            <button class="glass-button glass-button--secondary habit-detail-button" type="button"
              :aria-label="`Open details for ${habit.name}`" @click="openHabitDetail(habit)">
              Details
            </button>
          </div>

          <div class="habit-main-row">
            <div class="heatmap-wrap">
              <template v-if="isHydratingHabitCard(habit)">
                <div class="habit-timeline-shell">
                  <div class="habit-weekday-rail" aria-hidden="true">
                    <span v-for="dayIndex in 7" :key="`${habit.fileName}-skeleton-day-${dayIndex}`"
                      class="habit-weekday-label">
                      {{ getTimelineWeekdayLabel(dayIndex - 1) }}
                    </span>
                  </div>
                  <div class="habit-timeline-scroll habit-timeline-scroll--skeleton">
                    <div class="habit-timeline-month">
                      <div class="heatmap-skeleton-header skeleton-block" />
                      <div class="habit-month-grid heatmap-grid--skeleton" aria-hidden="true">
                        <div v-for="index in 35" :key="`${habit.fileName}-skeleton-${index}`"
                          class="heatmap-cell heatmap-cell--skeleton" />
                      </div>
                    </div>
                  </div>
                </div>
              </template>
              <template v-else>
                <div class="habit-timeline-shell">
                  <div class="habit-weekday-rail" aria-hidden="true">
                    <span v-for="dayIndex in 7" :key="`${habit.fileName}-day-${dayIndex}`" class="habit-weekday-label">
                      {{ getTimelineWeekdayLabel(dayIndex - 1) }}
                    </span>
                  </div>
                  <div :ref="element => setHabitTimelineRef(habit.fileName, element)" class="habit-timeline-scroll"
                    role="img" :aria-label="`Heat map for ${habit.name}`"
                    @scroll="handleHabitTimelineScroll(habit, $event)"
                    @touchstart="handleHabitTimelineTouchStart(habit, $event)"
                    @touchend="handleHabitTimelineTouchEnd(habit, $event)"
                    @touchcancel="handleHabitTimelineTouchEnd(habit, $event)"
                    @pointerdown="handleHabitTimelinePointerDown(habit, $event)"
                    @pointerup="handleHabitTimelinePointerUp(habit, $event)"
                    @pointercancel="handleHabitTimelinePointerUp(habit, $event)">
                    <div class="habit-timeline-track">
                      <div v-for="monthOffset in getLoadedMonthOffsets(habit)" :key="`${habit.fileName}-month-${monthOffset}`"
                        class="habit-timeline-month">
                        <span class="heatmap-month-label">{{ getTimelineMonthLabel(monthOffset) }}</span>
                        <div class="habit-month-grid">
                          <div v-for="cell in getHeatmapForMonth(habit, monthOffset)"
                            :key="`${habit.fileName}-${monthOffset}-${cell.date}`" class="heatmap-cell"
                            :class="[
                              `level-${cell.level}`,
                              {
                                'is-placeholder': cell.isPlaceholder,
                                'is-today': cell.isToday,
                                'is-unscheduled': !cell.isScheduled,
                                'is-future': cell.isFuture,
                                'is-fail': cell.value === 'fail',
                                'is-skip': cell.value === 'skip',
                                'is-clickable': !cell.isFuture && !cell.isPlaceholder,
                                'is-selected': isSelectedCell(habit, cell),
                              },
                            ]" :title="cellTitle(habit, cell)" @click="selectCell(habit, cell)" />
                        </div>
                      </div>
                      <div v-if="isLoadingOlderMonths(habit)" class="heatmap-month-loading">
                        <span class="mini-spinner" aria-hidden="true" />
                      </div>
                    </div>
                  </div>
                </div>
              </template>
            </div>

            <div class="habit-controls">
              <template v-if="isHydratingHabitCard(habit)">
                <div v-if="habit.targetCount > 1" class="habit-count-entry">
                  <span class="habit-count-label skeleton-block skeleton-line skeleton-line--short" />
                  <span class="habit-input-skeleton skeleton-block" />
                </div>
                <div class="habit-actions">
                  <span class="habit-action-skeleton skeleton-block" />
                  <span class="habit-action-skeleton skeleton-block" />
                  <span v-if="habit.allowSkip" class="habit-action-skeleton skeleton-block" />
                  <span class="habit-action-skeleton skeleton-block" />
                </div>
              </template>
              <template v-else>
                <label v-if="habit.targetCount > 1" class="habit-count-entry">
                  <span class="habit-count-label">{{ getSelectedDateLabel(habit) }} {{ habit.unit }}</span>
                  <input class="glass-input habit-count-input" type="number" min="0" step="1" inputmode="numeric"
                    :value="getSelectedNumberInputValue(habit)" :placeholder="String(habit.targetCount)"
                    :style="getCountInputStyle(habit)" @change="handleSelectedNumberInput(habit, $event)">
                </label>

                <div class="habit-actions">
                  <button class="glass-button glass-button--secondary" type="button" @click="markSelectedDone(habit)">
                    <Check :size="14" />

                  </button>
                  <button class="glass-button glass-button--secondary" type="button" @click="markSelectedFail(habit)">
                    <X :size="14" />

                  </button>
                  <button v-if="habit.allowSkip" class="glass-button glass-button--secondary" type="button"
                    @click="markSelectedSkip(habit)">
                    <SkipForward :size="14" />

                  </button>
                  <button class="glass-button glass-button--secondary" type="button" aria-label="Clear entry"
                    @click="clearSelectedValue(habit)">
                    <Eraser :size="14" />
                  </button>
                </div>
              </template>

              <p v-if="habitSaving[habit.fileName]" class="hint">Saving update...</p>
              <p v-if="habitSaveErrors[habit.fileName]" class="hint error-text">{{ habitSaveErrors[habit.fileName] }}
              </p>
            </div>
          </div>
          <template v-if="isHydratingHabitCard(habit)">
            <div class="habit-score-skeleton skeleton-block" />
            <div class="habit-meta-row habit-meta-row--skeleton">
              <span class="skeleton-block skeleton-line" />
              <span v-if="habit.reminder" class="skeleton-block skeleton-line skeleton-line--short" />
            </div>
          </template>
          <template v-else>
            <p class="habit-score">{{ getStreakSummary(habit) }}</p>
            <div class="habit-meta-row">
              <!-- <span>{{ habit.period === 'week' ? 'Weekly' : 'Daily' }}</span> -->
              <span>{{ getTargetSummary(habit) }}</span>
              <!-- <span>{{ getSelectedDateTitle(habit) }}</span> -->
              <span v-if="habit.reminder">Reminder: {{ habit.reminder }}</span>
              <!-- <span class="mini-status">
                <span class="mini-status-dot mini-status-dot--skip" />
                Skip
              </span>
              <span class="mini-status">
                <span class="mini-status-dot mini-status-dot--fail" />
                Miss
              </span> -->
            </div>
          </template>
        </div>
      </div>
    </div>
  </div>
  <BottomActionNav />
</template>

<style scoped>
.page {
  min-height: 100vh;
  padding: var(--page-top-padding) 20px calc(40px + 72px + env(safe-area-inset-bottom));
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
  z-index: 5;
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

.loading-inline {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
}

.loading-spinner {
  width: 14px;
  height: 14px;
  border-radius: 999px;
  border: 2px solid color-mix(in srgb, var(--text-soft) 22%, transparent);
  border-top-color: color-mix(in srgb, var(--primary) 72%, var(--text));
  animation: pull-refresh-spin 0.75s linear infinite;
}

.skeleton-block {
  position: relative;
  overflow: hidden;
  border-radius: 10px;
  background: color-mix(in srgb, var(--surface) 84%, var(--c-light) 16%);
}

.skeleton-block::after {
  content: '';
  position: absolute;
  inset: 0;
  transform: translateX(-100%);
  background: linear-gradient(90deg, transparent, color-mix(in srgb, white 36%, transparent), transparent);
  animation: skeleton-shimmer 1.1s ease-in-out infinite;
}

@keyframes skeleton-shimmer {
  100% {
    transform: translateX(100%);
  }
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

.habit-detail-header-button {
  white-space: nowrap;
}

.habit-detail-view {
  display: grid;
  gap: 12px;
}

.habit-detail-hero {
  display: grid;
  gap: 14px;
}

.habit-detail-hero-main {
  display: flex;
  align-items: center;
  gap: 14px;
}

.habit-detail-icon {
  width: 54px;
  height: 54px;
  border-radius: 18px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  font-size: 1.8rem;
  color: color-mix(in srgb, var(--primary) 82%, var(--text));
  background:
    linear-gradient(135deg, color-mix(in srgb, var(--primary) 24%, transparent), color-mix(in srgb, white 16%, transparent)),
    var(--surface);
  border: 1px solid color-mix(in srgb, var(--primary) 20%, var(--border));
}

.habit-detail-intro {
  min-width: 0;
}

.habit-detail-kicker,
.habit-detail-goal {
  margin: 0;
  color: var(--text-soft);
}

.habit-detail-kicker {
  margin-bottom: 4px;
  font-size: 0.8rem;
  letter-spacing: 0.08em;
  text-transform: uppercase;
}

.habit-detail-intro h2,
.habit-detail-section-head h3,
.habit-detail-month-head h3 {
  margin: 0;
}

.habit-detail-score {
  margin: 6px 0 4px;
  font-size: 1rem;
  font-weight: 700;
}

.habit-detail-stats {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 10px;
}

.habit-detail-stat {
  padding: 12px;
  border-radius: 16px;
  background: color-mix(in srgb, var(--surface) 82%, var(--c-light) 18%);
  border: 1px solid color-mix(in srgb, var(--primary) 10%, var(--border));
}

.habit-detail-stat-label {
  display: block;
  margin-bottom: 6px;
  font-size: 0.76rem;
  color: var(--text-soft);
  text-transform: uppercase;
  letter-spacing: 0.06em;
}

.habit-detail-summary {
  display: grid;
  gap: 12px;
}

.habit-detail-section-head {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  align-items: end;
}

.habit-detail-months {
  display: grid;
  gap: 12px;
}

.habit-detail-month-row {
  display: grid;
  gap: 10px;
  padding-top: 12px;
  border-top: 1px solid color-mix(in srgb, var(--border) 82%, transparent);
}

.habit-detail-month-row:first-of-type {
  padding-top: 0;
  border-top: 0;
}

.habit-detail-month-head {
  display: flex;
  gap: 12px;
  align-items: center;
}

.habit-detail-heatmap {
  gap: 6px;
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
  grid-template-columns: auto 1fr auto;
  align-items: center;
  gap: 10px;
}

.habit-head h2 {
  margin: 0;
  font-size: 1rem;
}

.habit-detail-button {
  white-space: nowrap;
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

.habit-icon-button {
  border: 0;
  padding: 0;
  cursor: pointer;
}

.habit-emoji {
  line-height: 1;
  font-size: 16px;
}

.habit-meta-row {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  font-size: 0.82rem;
  color: var(--text-soft);
}

.mini-status {
  display: inline-flex;
  align-items: center;
  gap: 4px;
}

.mini-status-dot {
  width: 10px;
  height: 10px;
  border-radius: 3px;
  border: 1px solid color-mix(in srgb, var(--c-light) 12%, transparent);
}

.mini-status-dot--skip {
  position: relative;
  background: color-mix(in srgb, var(--surface) 86%, var(--primary) 14%);
  border-color: color-mix(in srgb, var(--primary) 38%, transparent);
}

.mini-status-dot--skip::after {
  content: '';
  position: absolute;
  left: 1px;
  right: 1px;
  top: 50%;
  height: 2px;
  transform: translateY(-50%);
  border-radius: 999px;
  background: color-mix(in srgb, var(--primary) 82%, var(--text) 18%);
}

.mini-status-dot--fail {
  background: color-mix(in srgb, var(--danger) 72%, var(--surface));
  border-color: color-mix(in srgb, var(--danger) 60%, transparent);
}

.habit-main-row {
  display: grid;
  gap: 10px;
}

.habit-controls {
  display: grid;
  gap: 6px;
  min-width: 0;
}

.habit-score {
  margin: 0;
  color: var(--text-soft);
  font-size: 0.86rem;
}

.habit-score-skeleton {
  width: 148px;
  height: 14px;
}

.habit-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.habit-actions :deep(.glass-button) {
  min-height: 30px;
  padding: 6px 8px;
  border-radius: 10px;
}

.glass-input {
  border: 1px solid color-mix(in srgb, var(--c-light) 16%, transparent);
  border-radius: 14px;
  padding: 10px 12px;
  background: color-mix(in srgb, var(--c-glass) 10%, transparent);
  color: var(--text);
}

.habit-count-entry {
  display: grid;
  gap: 4px;
  max-width: 144px;
}

.habit-count-label {
  font-size: 0.8rem;
  color: var(--text-soft);
}

.habit-count-input {
  width: 100%;
  transition: background 0.2s ease;
  min-height: 34px;
  padding: 7px 10px;
}

.habit-action {
  width: 24px;
  height: 24px;
  border-radius: 9px;
}

.hint {
  margin: 0;
  font-size: 0.82rem;
  color: var(--text-soft);
}

.heatmap-wrap {
  display: grid;
  gap: 8px;
  min-width: 0;
}

.heatmap-skeleton-header {
  width: 38px;
  height: 14px;
}

.heatmap-month-label {
  display: inline-block;
  margin-bottom: 6px;
  font-size: 0.74rem;
  font-weight: 700;
  color: var(--text-soft);
}

.heatmap-month-loading {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  font-size: 0.68rem;
  color: var(--text-soft);
  opacity: 0.9;
}

.mini-spinner {
  width: 10px;
  height: 10px;
  border-radius: 999px;
  border: 1.5px solid color-mix(in srgb, var(--text-soft) 28%, transparent);
  border-top-color: color-mix(in srgb, var(--primary) 72%, var(--text));
  animation: pull-refresh-spin 0.75s linear infinite;
}

.habit-timeline-shell {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr);
  gap: 12px;
  align-items: start;
  padding-left: 8px;
}

.habit-weekday-rail {
  display: grid;
  grid-template-rows: repeat(7, 12px);
  gap: 3px;
  padding-top: 22px;
  width: 42px;
  justify-items: start;
  flex: 0 0 auto;
}

.habit-weekday-label {
  font-size: 0.66rem;
  line-height: 12px;
  color: var(--text-soft);
  font-weight: 700;
}

.habit-timeline-scroll {
  overflow-x: auto;
  overflow-y: hidden;
  padding-bottom: 6px;
  padding-left: 4px;
  scrollbar-width: thin;
  scroll-snap-type: x proximity;
  -webkit-overflow-scrolling: touch;
}

.habit-timeline-scroll--skeleton {
  overflow: hidden;
}

.habit-timeline-track {
  display: inline-flex;
  gap: 10px;
  min-width: max-content;
}

.habit-timeline-month {
  display: grid;
  align-content: start;
  min-width: max-content;
  scroll-snap-align: start;
}

.habit-month-grid {
  display: grid;
  grid-auto-flow: column;
  grid-template-rows: repeat(7, 14px);
  grid-auto-columns: 14px;
  gap: 4px;
  align-items: center;
}

.habit-timeline-month .heatmap-cell {
  width: 14px;
  height: 14px;
  border-radius: 4px;
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

.heatmap-grid--skeleton {
  pointer-events: none;
}

.heatmap-cell {
  width: 12px;
  height: 12px;
  border-radius: 3px;
  border: 1px solid color-mix(in srgb, var(--c-light) 12%, transparent);
  background: color-mix(in srgb, var(--c-dark) 8%, var(--surface));
}

.heatmap-cell--skeleton {
  border-color: transparent;
}

.heatmap-cell.is-placeholder {
  border-color: transparent;
  background: transparent;
  pointer-events: none;
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

.heatmap-cell.is-selected {
  transform: scale(1.12);
  box-shadow: 0 0 0 2px color-mix(in srgb, var(--primary) 46%, transparent);
}

.error-text {
  color: var(--danger);
}

.habit-meta-row--skeleton {
  align-items: center;
}

.skeleton-line {
  display: inline-block;
  width: 190px;
  height: 12px;
  border-radius: 999px;
}

.skeleton-line--short {
  width: 110px;
}

.habit-input-skeleton {
  width: 100%;
  max-width: 180px;
  height: 40px;
  border-radius: 14px;
}

.habit-action-skeleton {
  width: 36px;
  height: 36px;
  border-radius: 12px;
}

.legacy-header {
  display: none;
}

@media (max-width: 560px) {
  .habit-detail-stats {
    grid-template-columns: 1fr;
  }

  .habit-detail-section-head,
  .habit-detail-month-head {
    display: grid;
    gap: 8px;
  }

  .habit-weekday-rail {
    grid-template-rows: repeat(7, 10px);
    gap: 2px;
    width: 26px;
  }

  .heatmap-days {
    grid-template-columns: repeat(7, 10px);
    gap: 2px;
  }

  .heatmap-grid,
  .habit-month-grid {
    grid-template-columns: repeat(7, 10px);
    grid-auto-columns: 12px;
    gap: 3px;
  }

  .habit-timeline-month .heatmap-cell {
    width: 12px;
    height: 12px;
  }

  .heatmap-cell {
    width: 10px;
    height: 10px;
  }
}
</style>
