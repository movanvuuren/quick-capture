<template>
  <div>
    <filter-chip :class="{ 'active': allFiltersActive.value }">All</filter-chip>
    <filter-chip :class="{ 'active': allFiltersActive.value }">Lists</filter-chip>
    <filter-chip :class="{ 'active': allFiltersActive.value }">Notes</filter-chip>
    <filter-chip :class="{ 'active': allFiltersActive.value }">Tasks</filter-chip>
  </div>
</template>

<script>
export default {
  setup() {
    // your setup code here
  }
};
</script>
