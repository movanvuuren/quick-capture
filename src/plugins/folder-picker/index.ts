import type { FolderPickerPlugin } from './definitions'
import { registerPlugin } from '@capacitor/core'

export const FolderPicker = registerPlugin<FolderPickerPlugin>('FolderPicker')

export * from './definitions'
