import { createApp } from 'vue'
import App from './App.vue'
import { applyTheme, loadSettings } from './lib/settings'

import router from './router'

// global CSS variables and theme definitions
import './style.css'

// immediately apply saved theme so the UI doesn't flash
const settings = loadSettings()
applyTheme(settings.theme)

createApp(App).use(router).mount('#app')
