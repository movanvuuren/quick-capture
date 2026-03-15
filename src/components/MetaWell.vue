<script setup lang="ts">
defineProps<{
  date: string
}>()
</script>

<template>
  <div class="meta-well">
    <span class="meta-date">{{ date }}</span>
    <slot />
  </div>
</template>

<style scoped>
.meta-well {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 4px 6px 4px 12px;
  border-radius: 16px;
  border: 1px solid color-mix(in srgb, var(--c-light) 16%, transparent);
  background: color-mix(in srgb, var(--c-light) 10%, transparent);
  overflow: hidden;
  position: relative;
  z-index: 1;
  justify-content: flex-end;
  max-width: 100%;
}

.meta-date {
  color: var(--text-soft);
  font-size: 0.8rem;
  margin-right: 4px;
  max-width: 170px;
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}

@media (max-width: 560px) {
  .meta-well {
    padding: 2px 4px 2px 8px;
    gap: 2px;
  }

  .meta-date {
    max-width: 110px;
  }
}
</style>
