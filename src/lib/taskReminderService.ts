import { Capacitor } from '@capacitor/core'
import { LocalNotifications } from '@capacitor/local-notifications'

export interface TaskReminderTarget {
  fileName: string
  lineIndex: number
  dueDate?: string
  taskId: string
  body: string
}

function notificationIdForTask(fileName: string, lineIndex: number): number {
  const source = `task-reminder:${fileName}:${lineIndex}`
  let hash = 0
  for (let i = 0; i < source.length; i += 1)
    hash = ((hash << 5) - hash + source.charCodeAt(i)) | 0

  return Math.abs(hash) || 1
}

function reminderDateFromDueDate(dueDate: string | undefined, time: string): Date | null {
  if (!dueDate || !time)
    return null

  const [hourRaw, minuteRaw] = time.split(':')
  const hour = Number.parseInt(hourRaw || '', 10)
  const minute = Number.parseInt(minuteRaw || '', 10)
  if (!Number.isInteger(hour) || !Number.isInteger(minute))
    return null

  const date = new Date(`${dueDate}T00:00:00`)
  if (Number.isNaN(date.getTime()))
    return null

  date.setHours(hour, minute, 0, 0)
  if (date.getTime() <= Date.now())
    return null

  return date
}

async function ensureNotificationPermission(): Promise<boolean> {
  if (!Capacitor.isNativePlatform())
    return true

  const status = await LocalNotifications.checkPermissions()
  if (status.display === 'granted')
    return true

  const requested = await LocalNotifications.requestPermissions()
  return requested.display === 'granted'
}

export async function cancelTaskReminderNotification(fileName: string, lineIndex: number) {
  if (!Capacitor.isNativePlatform())
    return

  try {
    await LocalNotifications.cancel({
      notifications: [{ id: notificationIdForTask(fileName, lineIndex) }],
    })
  }
  catch {
    // Ignore cleanup errors to avoid blocking task operations.
  }
}

export async function scheduleTaskReminderNotification(target: TaskReminderTarget, time: string) {
  if (!Capacitor.isNativePlatform())
    return

  const at = reminderDateFromDueDate(target.dueDate, time)
  if (!at)
    throw new Error('Reminder date/time must be in the future')

  const hasPermission = await ensureNotificationPermission()
  if (!hasPermission)
    throw new Error('Notification permission not granted')

  const id = notificationIdForTask(target.fileName, target.lineIndex)
  await LocalNotifications.cancel({ notifications: [{ id }] })
  await LocalNotifications.schedule({
    notifications: [
      {
        id,
        title: 'Task reminder',
        body: target.body,
        smallIcon: 'ic_stat_quick_capture',
        iconColor: '#22c55e',
        schedule: { at },
        extra: { taskId: target.taskId },
      },
    ],
  })
}
