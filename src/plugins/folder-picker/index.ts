import { registerPlugin } from '@capacitor/core'

import type { FolderPickerPlugin } from './definitions'
import { FolderPickerWeb } from './web'

export const FolderPicker = registerPlugin<FolderPickerPlugin>('FolderPicker', {
  web: () => new FolderPickerWeb(),
})

export * from './definitions'
