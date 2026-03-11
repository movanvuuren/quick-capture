import { createApp } from 'vue'
import App from './App.vue'
import router from './router'

// global CSS variables and theme definitions
import './style.css'

import { loadSettings, applyTheme } from './lib/settings'

// immediately apply saved theme so the UI doesn't flash
const settings = loadSettings()
applyTheme(settings.theme)

createApp(App).use(router).mount('#app')