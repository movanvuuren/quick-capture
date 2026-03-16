import { registerPlugin } from '@capacitor/core'
import type { WidgetSyncPlugin } from './definitions'

const WidgetSync = registerPlugin<WidgetSyncPlugin>('WidgetSync', {
  web: () => import('./web').then(m => new m.WidgetSyncWeb()),
})

export * from './definitions'
export { WidgetSync }
