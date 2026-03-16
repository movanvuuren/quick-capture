<script setup lang="ts">
import { App as CapacitorApp } from '@capacitor/app'
import { Capacitor } from '@capacitor/core'
import { LocalNotifications } from '@capacitor/local-notifications'
import { onBeforeUnmount, onMounted } from 'vue'
import { useRouter } from 'vue-router'

const router = useRouter()
let backButtonListener: { remove: () => Promise<void> } | null = null
let notificationTapListener: { remove: () => Promise<void> } | null = null

onMounted(async () => {
  backButtonListener = await CapacitorApp.addListener('backButton', () => {
    if (window.history.length > 1) {
      router.back()
    }
    else {
      CapacitorApp.exitApp()
    }
  })

  if (Capacitor.isNativePlatform()) {
    notificationTapListener = await LocalNotifications.addListener(
      'localNotificationActionPerformed',
      (event) => {
        const taskId = event.notification.extra?.taskId
        if (typeof taskId === 'string' && taskId) {
          router.push({ name: 'tasks', query: { highlight: taskId } })
        }
        else {
          router.push({ name: 'tasks' })
        }
      },
    )
  }
})

onBeforeUnmount(async () => {
  await backButtonListener?.remove()
  await notificationTapListener?.remove()
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
