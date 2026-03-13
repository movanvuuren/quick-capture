<script setup lang="ts">
import { Pin, PinOff } from 'lucide-vue-next'

const props = withDefaults(defineProps<{
  pinned?: boolean
  size?: number
  itemLabel?: string
}>(), {
  pinned: false,
  size: 16,
  itemLabel: 'item',
})

const emit = defineEmits<{
  toggle: []
}>()

function onClick(event: MouseEvent) {
  // Prevent parent card click handlers from firing when toggling pin.
  event.stopPropagation()
  emit('toggle')
}
</script>

<template>
  <button class="glass-icon-button pin-toggle-button" :class="{ 'is-active': pinned }" type="button"
    :aria-label="pinned ? `Unpin ${itemLabel}` : `Pin ${itemLabel}`" @click="onClick">
    <component :is="pinned ? Pin : PinOff" :size="size" />
  </button>
</template>

<style scoped>
.pin-toggle-button {
  color: var(--text-soft);
  transition:
    color 0.14s ease,
    background 0.14s ease,
    border-color 0.14s ease,
    box-shadow 0.14s ease,
    transform 0.14s ease;
}

.pin-toggle-button:hover {
  color: var(--primary);
}

.pin-toggle-button.is-active {
  color: var(--primary);
  border-color: color-mix(in srgb, var(--primary) 52%, var(--text));
  background: color-mix(in srgb, var(--primary) 16%, var(--c-glass) 14%);
  box-shadow:
    0 0 0 1px color-mix(in srgb, var(--primary) 24%, transparent),
    inset 0 0 0 1px color-mix(in srgb, var(--c-light) calc(var(--glass-reflex-light) * 10%), transparent),
    inset 1.5px 2px 0 -1px color-mix(in srgb, var(--c-light) calc(var(--glass-reflex-light) * 88%), transparent),
    inset -1px -2px 0 -1px color-mix(in srgb, var(--c-light) calc(var(--glass-reflex-light) * 68%), transparent),
    inset 0 -1px 4px 0 color-mix(in srgb, var(--c-dark) calc(var(--glass-reflex-dark) * 12%), transparent),
    0 8px 18px color-mix(in srgb, var(--primary) 20%, transparent);
}
</style>
