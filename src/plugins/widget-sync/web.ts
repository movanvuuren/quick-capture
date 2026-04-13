import { WebPlugin } from '@capacitor/core'
import type { WidgetSyncPlugin } from './definitions'

export class WidgetSyncWeb extends WebPlugin implements WidgetSyncPlugin {
  async syncHabits(_options: {
    folderUri: string
    habitsJson: string
    theme?: 'light' | 'dark' | 'dim'
    accentColor?: string
    cornerStyle?: 'square' | 'soft' | 'round'
  }): Promise<void> { }

  async syncAppearance(_options: {
    theme?: 'light' | 'dark' | 'dim'
    accentColor?: string
    cornerStyle?: 'square' | 'soft' | 'round'
  }): Promise<void> { }

  async refreshWidgets(): Promise<void> { }
}
