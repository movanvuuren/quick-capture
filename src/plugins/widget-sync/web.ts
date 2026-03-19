import { WebPlugin } from '@capacitor/core'
import type { WidgetSyncPlugin } from './definitions'

export class WidgetSyncWeb extends WebPlugin implements WidgetSyncPlugin {
  // No-op on web/iOS – only meaningful on Android
  async syncHabits(_options: {
    folderUri: string
    habitsJson: string
    theme?: 'light' | 'dark' | 'dim'
    accentColor?: string
  }): Promise<void> { }
}
