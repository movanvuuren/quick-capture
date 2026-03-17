<script setup lang="ts">
import { App as CapacitorApp } from '@capacitor/app'
import { Capacitor } from '@capacitor/core'
import { LocalNotifications } from '@capacitor/local-notifications'
import { onBeforeUnmount, onMounted } from 'vue'
import { useRouter } from 'vue-router'

const router = useRouter()
let backButtonListener: { remove: () => Promise<void> } | null = null
let notificationTapListener: { remove: () => Promise<void> } | null = null
const BLOCK_BACK_EDGE_PX = 28
let edgeStartX = 0
let edgeStartY = 0
let isBlockingOppositeEdgeBack = false

function onGlobalTouchStart(event: TouchEvent) {
  const touch = event.touches[0]
  if (!touch) {
    isBlockingOppositeEdgeBack = false
    return
  }

  edgeStartX = touch.clientX
  edgeStartY = touch.clientY
  isBlockingOppositeEdgeBack = touch.clientX >= window.innerWidth - BLOCK_BACK_EDGE_PX
}

function onGlobalTouchMove(event: TouchEvent) {
  if (!isBlockingOppositeEdgeBack)
    return

  const touch = event.touches[0]
  if (!touch)
    return

  const deltaX = edgeStartX - touch.clientX
  const deltaY = touch.clientY - edgeStartY

  // Block right-edge inward horizontal gestures so only default back edge remains active.
  if (deltaX > 8 && Math.abs(deltaX) > Math.abs(deltaY) && event.cancelable)
    event.preventDefault()
}

function onGlobalTouchEnd() {
  isBlockingOppositeEdgeBack = false
}

onMounted(async () => {
  document.addEventListener('touchstart', onGlobalTouchStart, { passive: true })
  document.addEventListener('touchmove', onGlobalTouchMove, { passive: false })
  document.addEventListener('touchend', onGlobalTouchEnd, { passive: true })
  document.addEventListener('touchcancel', onGlobalTouchEnd, { passive: true })

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
          router.push({ name: 'tasks', query: { highlight: taskId, pulse: String(Date.now()) } })
        }
        else {
          router.push({ name: 'tasks' })
        }
      },
    )
  }
})

onBeforeUnmount(async () => {
  document.removeEventListener('touchstart', onGlobalTouchStart)
  document.removeEventListener('touchmove', onGlobalTouchMove)
  document.removeEventListener('touchend', onGlobalTouchEnd)
  document.removeEventListener('touchcancel', onGlobalTouchEnd)

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
