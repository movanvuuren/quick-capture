<script setup lang="ts">
import type { QuickTaskPreset } from '../lib/settings'
import { Settings } from 'lucide-vue-next'
import { onActivated, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { loadSettings } from '../lib/settings'

const router = useRouter()
const presets = ref<QuickTaskPreset[]>([])

function refreshSettings() {
  presets.value = loadSettings().quickTaskPresets
}

onMounted(refreshSettings)
onActivated(refreshSettings)

function go(path: string) {
  router.push(path)
}

function goTask(id: string) {
  router.push(`/task/${id}`)
}
</script>

<template>
  <div class="page">
    <div class="top-row">
      <div>
        <h1>Quick Capture</h1>
        <p class="subtitle">
          Fast notes and tasks into your vault
        </p>
      </div>

      <button class="glass-icon-button" @click="go('/settings')">
        <Settings :size="22" />
      </button>
    </div>

    <div class="grid">
      <button
        v-for="preset in presets"
        :key="preset.id"
        class="glass-button"
        @click="goTask(preset.id)"
      >
        {{ preset.label }}
      </button>

      <button class="glass-button" @click="go('/note')">
        Note
      </button>

      <button class="glass-button" @click="go('/list')">
        List
      </button>
    </div>
  </div>
</template>

<style scoped>
.page {
  min-height: 100vh;
  padding: 24px 20px;
  background: var(--bg);
  color: var(--text);
}

.top-row {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 16px;
}

h1 {
  margin: 0;
}

.subtitle {
  margin: 8px 0 0;
  color: var(--text-soft);
}

.grid {
  margin-top: 24px;
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 16px;
}

.card {
  border: none;
  border-radius: 20px;
  padding: 28px 20px;
  background: var(--surface);
  font-size: 1.05rem;
  font-weight: 700;
  text-align: left;
  box-shadow: var(--shadow);
}
</style>
