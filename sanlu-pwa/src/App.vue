<script setup lang="ts">
import { store } from './lib/store'
import HomeView from './components/HomeView.vue'
import LiveView from './components/LiveView.vue'
</script>

<template>
  <HomeView v-if="store.view === 'home'" />
  <LiveView v-else />
</template>
