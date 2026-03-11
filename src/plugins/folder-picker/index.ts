import { registerPlugin } from '@capacitor/core'
import type { FolderPickerPlugin } from './definitions'

export const FolderPicker = registerPlugin<FolderPickerPlugin>('FolderPicker')

export * from './definitions'