<template>
  <router-view />
</template>

<script setup lang="ts">
import { onMounted, onBeforeUnmount } from 'vue'
import { useRouter } from 'vue-router'
import { App as CapacitorApp } from '@capacitor/app'

const router = useRouter()
let backButtonListener: { remove: () => Promise<void> } | null = null

onMounted(async () => {
  backButtonListener = await CapacitorApp.addListener('backButton', () => {
    if (window.history.length > 1) {
      router.back()
    } else {
      CapacitorApp.exitApp()
    }
  })
})

onBeforeUnmount(async () => {
  await backButtonListener?.remove()
})
</script>