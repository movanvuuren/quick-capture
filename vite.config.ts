import { defineConfig } from 'vite';
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  base: '/quick-capture/',
  // existing config goes here
});