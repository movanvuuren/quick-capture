<template>
  <div :class="{'compact-mobile': isMobile}">
    <!-- Content here -->
  </div>
</template>

<script>
export default {
  data() {
    return {
      isMobile: false,
    };
  },
  // Disable drag & drop on mobile
  mounted() {
    if (/\b(Mobile|Android|iP(hone|od|ad)|Opera Mini|IEMobile|WPDesktop)\b/i.test(navigator.userAgent)) {
      this.isMobile = true;
      document.addEventListener('dragstart', (e) => e.preventDefault());
    }
  }
};
</script>

<style scoped>
.compact-mobile {
  /* Compact mobile CSS overrides */
  padding: 0.5rem;
  font-size: 0.9rem;
}
</style>