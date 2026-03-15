<template>
  <div>
    <div class="item-row" :draggable="!isTouchDevice" @dragstart="handleDragStart" @dragover.prevent @drop="handleDrop">
      <!-- Item content goes here -->
    </div>
  </div>
</template>

<script>
export default {
  computed: {
    isTouchDevice() {
      return matchMedia('(pointer: coarse)').matches;
    }
  }
};
</script>

<style scoped>
@media (max-width: 560px) {
  /* Reduce padding and size for mobile view */
  .page-padding { padding: 8px; }
  .list-card { padding: 10px; }
  .items-gap { margin-bottom: 8px; }
  .item-row { padding: 4px; margin: 4px 0; border-radius: 4px; }
  .item-input { padding: 6px; font-size: 14px; line-height: 1.4; }
  .drag-handle { display: none; }
  .delete-button { width: 16px; height: 16px; }
}
</style>