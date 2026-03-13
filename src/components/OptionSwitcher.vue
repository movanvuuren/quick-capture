<script setup lang="ts">
import { computed } from 'vue'

interface SwitchOption {
  value: string
  label: string
  icon?: any
}

const props = defineProps<{
  modelValue: string
  options: SwitchOption[]
  ariaLabel?: string
}>()

const emit = defineEmits<{
  'update:modelValue': [value: string]
}>()

const activeIndex = computed(() => {
  const index = props.options.findIndex(option => option.value === props.modelValue)
  return index >= 0 ? index : 0
})

const switcherStyle = computed(() => ({
  '--switch-option-count': String(Math.max(props.options.length, 1)),
  '--switch-active-index': String(activeIndex.value),
}))

function select(value: string) {
  emit('update:modelValue', value)
}
</script>

<template>
  <div class="option-switcher" :style="switcherStyle" :aria-label="ariaLabel || 'Switcher'" role="group">
    <button v-for="option in options" :key="option.value" type="button" class="switch-option"
      :class="{ 'is-active': modelValue === option.value }" @click="select(option.value)">
      <component :is="option.icon" v-if="option.icon" class="switch-icon" />
      <span>{{ option.label }}</span>
    </button>
  </div>
</template>

<style scoped>
.option-switcher {
  --switch-gap: 8px;

  position: relative;
  display: flex;
  align-items: center;
  gap: var(--switch-gap);
  width: 100%;
  padding: 6px;
  border-radius: 999px;
  border: 1px solid color-mix(in srgb, var(--c-light) 16%, transparent);
  background: color-mix(in srgb, var(--c-glass) 12%, transparent);
  backdrop-filter: blur(14px) saturate(var(--saturation));
  -webkit-backdrop-filter: blur(14px) saturate(var(--saturation));
  box-shadow:
    inset 0 0 0 1px color-mix(in srgb, var(--c-light) calc(var(--glass-reflex-light) * 8%), transparent),
    inset 1px 1px 0 0 color-mix(in srgb, var(--c-light) calc(var(--glass-reflex-light) * 70%), transparent),
    inset 0 -1px 4px 0 color-mix(in srgb, var(--c-dark) calc(var(--glass-reflex-dark) * 10%), transparent);
  overflow: hidden;
}

.option-switcher::after {
  content: '';
  position: absolute;
  top: 6px;
  left: 6px;
  width: calc((100% - 12px - (var(--switch-option-count) - 1) * var(--switch-gap)) / var(--switch-option-count));
  height: calc(100% - 12px);
  border-radius: 999px;
  background: color-mix(in srgb, var(--c-glass) 28%, transparent);
  box-shadow:
    inset 0 0 0 1px color-mix(in srgb, var(--c-light) calc(var(--glass-reflex-light) * 10%), transparent),
    inset 1px 1px 0 0 color-mix(in srgb, var(--c-light) calc(var(--glass-reflex-light) * 85%), transparent),
    inset 0 -1px 3px 0 color-mix(in srgb, var(--c-dark) calc(var(--glass-reflex-dark) * 12%), transparent),
    0 4px 12px color-mix(in srgb, var(--c-dark) calc(var(--glass-reflex-dark) * 8%), transparent);
  transform: translateX(calc(var(--switch-active-index) * (100% + var(--switch-gap))));
  transition: transform 300ms cubic-bezier(0.2, 0.9, 0.2, 1);
  pointer-events: none;
}

.switch-option {
  position: relative;
  z-index: 1;
  flex: 1;
  min-height: 44px;
  border: none;
  border-radius: 999px;
  background: transparent;
  color: var(--text);
  font-size: 0.95rem;
  font-weight: 600;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  cursor: pointer;
  transition: color 180ms ease, transform 180ms ease;
}

.switch-option:hover {
  color: var(--primary);
}

.switch-option.is-active {
  color: var(--text);
}

.switch-icon {
  width: 18px;
  height: 18px;
}
</style>
