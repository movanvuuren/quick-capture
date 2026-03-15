<script setup lang="ts">
withDefaults(defineProps<{
  title: string
  showBack?: boolean
}>(), {
  showBack: true,
})

const emit = defineEmits<{
  back: []
}>()
</script>

<template>
  <div class="page-header">
    <div class="header-main">
      <button v-if="showBack" class="glass-icon-button back-button" aria-label="Go back" @click="emit('back')">
        ←
      </button>
      <h1 class="header-title">{{ title }}</h1>
    </div>

    <div v-if="$slots.right" class="header-right">
      <slot name="right" />
    </div>
  </div>
</template>

<style scoped>
.page-header {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  align-items: center;
  gap: 12px;
  margin-bottom: 16px;
}

.header-main {
  display: inline-flex;
  align-items: center;
  gap: 12px;
  min-width: 0;
}

.header-title {
  margin: 0;
  min-width: 0;
  font-size: 1.9rem;
  line-height: 1.1;
  letter-spacing: -0.03em;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.header-right {
  justify-self: end;
}

@media (max-width: 560px) {
  .page-header {
    gap: 8px;
    margin-bottom: 16px;
  }

  .header-main {
    gap: 8px;
  }

  .header-title {
    font-size: 1.45rem;
  }
}
</style>
