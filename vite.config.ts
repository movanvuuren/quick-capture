import { defineConfig } from 'vite';
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  // Default to root for Android/Capacitor and local builds.
  // GitHub Pages can override with VITE_BASE_PATH=/quick-capture/.
  base: process.env.VITE_BASE_PATH || '/',
  // existing config goes here
});