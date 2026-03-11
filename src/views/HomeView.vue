<template>
  <div class="page">
    <div class="top-row">
      <div>
        <h1>Quick Capture</h1>
        <p class="subtitle">Fast notes and tasks into your vault</p>
      </div>

      <button class="settings-button" @click="go('/settings')">
        <Settings :size="22" />
      </button>
    </div>

    <div class="grid">
      <button
        v-for="preset in presets"
        :key="preset.id"
        class="card"
        @click="goTask(preset.id)"
      >
        {{ preset.label }}
      </button>

      <button class="card" @click="go('/note')">
        Note
      </button>

      <button class="card" @click="go('/list')">
        List
      </button>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted, onActivated } from 'vue'
import { useRouter } from 'vue-router'
import { loadSettings, type QuickTaskPreset } from '../lib/settings'
import { Settings } from 'lucide-vue-next'

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

.settings-button {
  border: none;
  background: var(--surface-strong);
  border-radius: 16px;
  width: 48px;
  height: 48px;

  display: flex;
  align-items: center;
  justify-content: center;

  box-shadow: var(--shadow);
  color: var(--text);
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