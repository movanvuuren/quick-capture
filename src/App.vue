<script setup lang="ts">
import { App as CapacitorApp } from '@capacitor/app'
import { onBeforeUnmount, onMounted } from 'vue'
import { useRouter } from 'vue-router'

const router = useRouter()
let backButtonListener: { remove: () => Promise<void> } | null = null

onMounted(async () => {
  backButtonListener = await CapacitorApp.addListener('backButton', () => {
    if (window.history.length > 1) {
      router.back()
    }
    else {
      CapacitorApp.exitApp()
    }
  })
})

onBeforeUnmount(async () => {
  await backButtonListener?.remove()
})
</script>

<template>
  <router-view v-slot="{ Component, route }">
    <keep-alive>
      <component :is="Component" v-if="route.meta.keepAlive" />
    </keep-alive>
    <component :is="Component" v-if="!route.meta.keepAlive" />
  </router-view>
</template>
