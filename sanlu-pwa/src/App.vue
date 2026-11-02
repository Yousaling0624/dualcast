<script setup lang="ts">
import { onBeforeUnmount, watch } from 'vue'
import { store } from './lib/store'
import HomeView from './components/HomeView.vue'
import LiveView from './components/LiveView.vue'

// 主题：跟随系统 / 浅色 / 深色。写到 <html data-theme>，CSS 变量自动切换
const darkQuery = window.matchMedia('(prefers-color-scheme: dark)')

function isDark(): boolean {
  const pref = store.settings.theme
  if (pref === 'light') return false
  if (pref === 'dark') return true
  return darkQuery.matches
}

function applyTheme() {
  const dark = isDark()
  document.documentElement.dataset.theme = dark ? 'dark' : 'light'
  // 首页是暖纸色 / 深色炭黑，播放页是黑色影院；系统状态栏跟着场景切换
  const meta = document.querySelector<HTMLMetaElement>('meta[name="theme-color"]')
  if (meta) {
    meta.content = store.view === 'live' ? '#0b0a09' : dark ? '#141210' : '#f4efe6'
  }
}

watch(() => store.settings.theme, applyTheme, { immediate: true })
watch(() => store.view, applyTheme)
darkQuery.addEventListener('change', applyTheme)
onBeforeUnmount(() => darkQuery.removeEventListener('change', applyTheme))
</script>

<template>
  <HomeView v-show="store.view === 'home'" />
  <!-- 会话开始后 LiveView 一直挂着：回主页只是把它挪出屏幕，声音和弹幕继续走 -->
  <LiveView v-if="store.sessionActive" :class="{ 'bg-player': store.view !== 'live' }" />
</template>
