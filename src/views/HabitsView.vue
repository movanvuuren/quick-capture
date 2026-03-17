<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import type { CSSProperties } from 'vue'
import { useRouter } from 'vue-router'
import { Check, Flame, SkipForward, X } from 'lucide-vue-next'
import { parseFrontmatter } from '../lib/lists'
import { loadSettings } from '../lib/settings'
import { FolderPicker } from '../plugins/folder-picker'
import { WidgetSync } from '../plugins/widget-sync'

interface HabitCard {
  id: string
  name: string
  icon: string
  period: 'day' | 'week'
  targetCount: number
  targetDays: number
  unit: string
  reminder: string
  allowSkip: boolean
  scheduledDays: number[]
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
}

const LOG_START = '<!-- QUICK_CAPTURE_HABIT_LOG_START -->'
const LOG_END = '<!-- QUICK_CAPTURE_HABIT_LOG_END -->'
const HEATMAP_WEEKS = 4
const DAY_LABELS = ['S', 'M', 'T', 'W', 'T', 'F', 'S']
const HABIT_LOGS_DIR = 'habit-logs'
const HABIT_LOG_MONTHS_TO_LOAD = 15
const PULL_REFRESH_THRESHOLD = 72
const PULL_REFRESH_MAX_DISTANCE = 120

const router = useRouter()
const settings = loadSettings()

const isLoading = ref(true)
const error = ref('')
const habits = ref<HabitCard[]>([])
const habitLogs = ref<Record<string, Record<string, HabitLogValue>>>({})
const selectedDates = ref<Record<string, string>>({})
const habitSaving = ref<Record<string, boolean>>({})
const habitSaveErrors = ref<Record<string, string>>({})
const isCreatingExample = ref(false)
const exampleError = ref('')

const hasBaseFolder = computed(() => Boolean(settings.baseFolderUri))
const EXTERNAL_REFRESH_COOLDOWN_MS = 1000
let lastExternalRefreshAt = 0

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

function goBack() {
  router.push('/')
}

function todayIso() {
  return dateToIso(new Date())
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

function parseLegacyHabitLog(content: string): Record<string, HabitLogValue> {
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

async function loadHabitLogsForHabit(habit: HabitCard): Promise<Record<string, HabitLogValue>> {
  if (!settings.baseFolderUri)
    return {}

  const logs: Record<string, HabitLogValue> = {}

  for (let offset = 0; offset < HABIT_LOG_MONTHS_TO_LOAD; offset += 1) {
    const date = new Date()
    date.setDate(1)
    date.setMonth(date.getMonth() - offset)
    const monthIso = monthIsoFromDate(date)
    const fileName = getHabitLogMonthFileName(habit, monthIso)

    try {
      const read = await FolderPicker.readFile({ folderUri: settings.baseFolderUri, fileName })
      Object.assign(logs, parseHabitMonthLog(read.content))
    }
    catch {
      // missing month file is expected
    }
  }

  if (Object.keys(logs).length > 0)
    return logs

  try {
    const read = await FolderPicker.readFile({ folderUri: settings.baseFolderUri, fileName: habit.fileName })
    return parseLegacyHabitLog(read.content)
  }
  catch {
    return {}
  }
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

function startOfDisplayWeek(date: Date): Date {
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
  const endWeekStart = startOfDisplayWeek(end)
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
  if (cell.isFuture)
    return

  selectedDates.value = {
    ...selectedDates.value,
    [habit.fileName]: cell.date,
  }
}

function isSelectedCell(habit: HabitCard, cell: HeatmapCell): boolean {
  return getSelectedDate(habit) === cell.date
}

function getScheduledDaysCount(habit: HabitCard): number {
  return habit.scheduledDays.length || 7
}

function getRequiredDays(habit: HabitCard, eligibleDays = getScheduledDaysCount(habit)): number {
  return Math.min(Math.max(1, habit.targetDays), Math.max(1, eligibleDays))
}

function getTargetSummary(habit: HabitCard): string {
  if (habit.period === 'week')
    return `Goal: ${habit.targetCount} ${habit.unit} on ${getRequiredDays(habit)}/${getScheduledDaysCount(habit)} days`

  return `Goal: ${habit.targetCount} ${habit.unit} per day`
}

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
    file: h.fileName,
    name: h.name,
    icon: h.icon,
    target: h.targetCount,
  }))

  await WidgetSync.syncHabits({
    folderUri: settings.baseFolderUri,
    habitsJson: JSON.stringify(habitsForWidget),
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
}

function getSelectedNumberInputValue(habit: HabitCard): string {
  const selected = getSelectedValue(habit)
  return typeof selected === 'number' ? String(selected) : ''
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
  return value === 'skip'
}

function getWeeklyScoreFromToday(habit: HabitCard): { success: number, total: number } {
  const log = habitLogs.value[habit.fileName] || {}
  const keys = Object.keys(log).sort()
  const firstLoggedDate = keys[0]
  if (!firstLoggedDate)
    return { success: 0, total: 0 }

  const today = isoToDate(todayIso())
  let success = 0
  let total = 0

  for (let offset = 0; offset <= 365; offset += 1) {
    const date = dateAddDays(today, -offset)
    const iso = dateToIso(date)
    if (iso < firstLoggedDate)
      break

    if (!isScheduledForDate(habit, date))
      continue

    const value = log[iso] ?? null

    if (isNeutralValue(value))
      continue

    total += 1

    if (isSuccessValue(habit, value))
      success += 1
  }

  return {
    success,
    total,
  }
}

function getCurrentStreak(habit: HabitCard): number {
  if (habit.period === 'week')
    return getWeeklyScoreFromToday(habit).success

  const log = habitLogs.value[habit.fileName] || {}
  const today = isoToDate(todayIso())
  let streak = 0

  for (let offset = 0; offset <= 365; offset += 1) {
    const date = dateAddDays(today, -offset)
    if (!isScheduledForDate(habit, date))
      continue

    const value = log[dateToIso(date)] ?? null

    if (isNeutralValue(value))
      continue

    if (isSuccessValue(habit, value)) {
      streak += 1
      continue
    }
    break
  }

  return streak
}

function getBestStreak(habit: HabitCard): number {
  if (habit.period === 'week')
    return getWeeklyScoreFromToday(habit).total

  const keys = Object.keys(habitLogs.value[habit.fileName] || {}).sort()
  const today = isoToDate(todayIso())
  const log = habitLogs.value[habit.fileName] || {}

  const fallbackStart = dateAddDays(today, -365)
  const start = keys.length > 0 ? isoToDate(keys[0] as string) : fallbackStart

  let best = 0
  let current = 0

  for (let date = new Date(start); date <= today; date = dateAddDays(date, 1)) {
    if (!isScheduledForDate(habit, date))
      continue

    const value = log[dateToIso(date)] ?? null

    if (isNeutralValue(value))
      continue

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
  if (habit.period === 'week') {
    const { success, total } = getWeeklyScoreFromToday(habit)
    return `Streak: ${success}/${total} days`
  }

  const current = getCurrentStreak(habit)
  const best = getBestStreak(habit)
  return `Streak: ${current} days current, ${best} days best`
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

          const period = frontmatter.period === 'week' ? 'week' : 'day'
          const targetCount = period === 'week' && frontmatter.targetDays === undefined
            ? Math.max(1, Number(frontmatter.dailyTargetCount || 1))
            : Math.max(1, Number(frontmatter.targetCount || 1))
          const targetDays = period === 'week'
            ? Math.max(1, Number(frontmatter.targetDays || frontmatter.targetCount || 1))
            : 1

          return {
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
        logsByFile[habit.fileName] = await loadHabitLogsForHabit(habit)
      }),
    )
    habitLogs.value = logsByFile
    selectedDates.value = habits.value.reduce<Record<string, string>>((acc, habit) => {
      acc[habit.fileName] = selectedDates.value[habit.fileName] || todayIso()
      return acc
    }, {})

    void syncHabitsToWidget().catch(() => { })
  }
  catch (err) {
    console.error('Failed to load habits', err)
    error.value = 'Could not load habits from your folder.'
  }
  finally {
    isLoading.value = false
  }
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

  await loadHabits()
}

function handleVisibilityChange() {
  if (document.visibilityState === 'visible')
    void refreshHabitsFromExternal()
}

function handleWindowFocus() {
  void refreshHabitsFromExternal()
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
  document.addEventListener('visibilitychange', handleVisibilityChange)
  window.addEventListener('focus', handleWindowFocus)
  await loadHabits()
})

onBeforeUnmount(() => {
  document.removeEventListener('visibilitychange', handleVisibilityChange)
  window.removeEventListener('focus', handleWindowFocus)
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
            <button class="habit-icon-wrap habit-icon-button" type="button" :title="`Change icon for ${habit.name}`"
              :aria-label="`Change icon for ${habit.name}`" @click="editHabitIcon(habit)">
              <span v-if="habit.icon" class="habit-emoji">{{ habit.icon }}</span>
              <Flame v-else :size="16" />
            </button>
            <div>
              <h2>{{ habit.name }}</h2>
            </div>
          </div>

          <div class="habit-main-row">
            <div class="heatmap-wrap">
              <div class="heatmap-days" aria-hidden="true">
                <span v-for="day in DAY_LABELS" :key="`${habit.fileName}-${day}`" class="heatmap-day-label">{{ day
                }}</span>
              </div>
              <div class="heatmap-grid" role="img" :aria-label="`Heat map for ${habit.name}`">
                <div v-for="cell in getHeatmap(habit)" :key="`${habit.fileName}-${cell.date}`" class="heatmap-cell"
                  :class="[
                    `level-${cell.level}`,
                    {
                      'is-today': cell.isToday,
                      'is-unscheduled': !cell.isScheduled,
                      'is-future': cell.isFuture,
                      'is-fail': cell.value === 'fail',
                      'is-skip': cell.value === 'skip',
                      'is-clickable': !cell.isFuture,
                      'is-selected': isSelectedCell(habit, cell),
                    },
                  ]" :title="cellTitle(habit, cell)" @click="selectCell(habit, cell)" />
              </div>
            </div>

            <div class="habit-controls">
              <label v-if="habit.targetCount > 1" class="habit-count-entry">
                <span class="habit-count-label">{{ getSelectedDateLabel(habit) }} {{ habit.unit }}</span>
                <input class="glass-input habit-count-input" type="number" min="0" step="1" inputmode="numeric"
                  :value="getSelectedNumberInputValue(habit)" :placeholder="String(habit.targetCount)"
                  @change="handleSelectedNumberInput(habit, $event)">
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
              </div>

              <p v-if="habitSaving[habit.fileName]" class="hint">Saving update...</p>
              <p v-if="habitSaveErrors[habit.fileName]" class="hint error-text">{{ habitSaveErrors[habit.fileName] }}
              </p>
            </div>
          </div>
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
  grid-template-columns: auto 1fr;
  gap: 12px;
  align-items: start;
}

.habit-controls {
  display: grid;
  gap: 8px;
  min-width: 150px;
}

.habit-score {
  margin: 0;
  color: var(--text-soft);
  font-size: 0.86rem;
}

.habit-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
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
  gap: 6px;
  max-width: 180px;
}

.habit-count-label {
  font-size: 0.8rem;
  color: var(--text-soft);
}

.habit-count-input {
  width: 100%;
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

.heatmap-cell.is-selected {
  transform: scale(1.12);
  box-shadow: 0 0 0 2px color-mix(in srgb, var(--primary) 46%, transparent);
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
