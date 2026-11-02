<script setup lang="ts">
import { watch } from 'vue'
import { store } from './lib/store'
import HomeView from './components/HomeView.vue'
import LiveView from './components/LiveView.vue'

// 首页是暖纸色，播放页是黑色影院；状态栏跟着场景切换
watch(
  () => store.view,
  (view) => {
    const meta = document.querySelector<HTMLMetaElement>('meta[name="theme-color"]')
    if (meta) meta.content = view === 'live' ? '#0b0a09' : '#f4efe6'
  },
  { immediate: true }
)
</script>

<template>
  <HomeView v-if="store.view === 'home'" />
  <LiveView v-else />
</template>
