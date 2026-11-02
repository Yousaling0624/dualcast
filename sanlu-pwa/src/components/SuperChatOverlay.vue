<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { shouldShowSourceBadges, store } from '../lib/store'
import type { ChatMsg } from '../lib/danmaku'
import AppIcon from './AppIcon.vue'

interface ActiveSc {
  key: number
  msg: ChatMsg
  duration: number
}

const active = ref<ActiveSc[]>([])
const timers = new Map<number, number>()
let lastSeq = 0
const showSourceBadges = computed(() => shouldShowSourceBadges())

function avatarUrl(u?: string): string {
  if (!u) return ''
  return u.startsWith('//') ? `https:${u}` : u
}

function tier(price = 0): string {
  if (price >= 500) return 'tier-legend'
  if (price >= 100) return 'tier-high'
  if (price >= 30) return 'tier-mid'
  return 'tier-low'
}

function initial(name: string): string {
  return (name || 'SC').trim().slice(0, 1).toUpperCase()
}

function clearTimer(key: number) {
  const t = timers.get(key)
  if (t) window.clearTimeout(t)
  timers.delete(key)
}

function remove(key: number) {
  clearTimer(key)
  active.value = active.value.filter((x) => x.key !== key)
}

function trimToMax() {
  const max = Math.max(1, Number(store.settings.sc.max) || 3)
  if (active.value.length <= max) return
  for (const dropped of active.value.slice(max)) clearTimer(dropped.key)
  active.value = active.value.slice(0, max)
}

function add(msg: ChatMsg) {
  const fallback = Math.max(4, Number(store.settings.sc.duration) || 8) * 1000
  const duration = Math.min(60000, Math.max(4000, Number(msg.scDuration) || fallback))
  const item: ActiveSc = { key: msg.seq, msg, duration }
  active.value = [item, ...active.value]
  trimToMax()
  timers.set(
    item.key,
    window.setTimeout(() => remove(item.key), duration)
  )
}

function drain() {
  while (store.scFeed.length) {
    const msg = store.scFeed.shift()
    if (!msg || msg.kind !== 'sc' || msg.seq <= lastSeq) continue
    lastSeq = msg.seq
    if (!store.settings.sc.enabled) continue
    add(msg)
  }
}

function clearAll() {
  for (const key of timers.keys()) clearTimer(key)
  active.value = []
}

watch(
  () => store.scFeed.length,
  () => drain(),
  { flush: 'post' }
)

watch(
  () => store.settings.sc.enabled,
  (on) => {
    if (!on) clearAll()
    else drain()
  }
)

watch(
  () => store.settings.sc.max,
  () => trimToMax()
)

// 关掉 A+B 同屏时，B 路已经浮出的 SC 也一起收掉
watch(
  () => store.settings.danmaku.includeB,
  (on) => {
    if (on) return
    for (const item of active.value) {
      if (item.msg.sourceId === 'b') clearTimer(item.key)
    }
    active.value = active.value.filter((x) => x.msg.sourceId !== 'b')
  }
)

onMounted(() => {
  drain()
})

onBeforeUnmount(clearAll)
</script>

<template>
  <div v-if="store.settings.sc.enabled" class="sc-overlay" aria-live="polite">
    <TransitionGroup name="sc" tag="div" class="sc-stack">
      <article
        v-for="item in active"
        :key="item.key"
        class="sc-card"
        :class="tier(item.msg.price)"
      >
        <div class="sc-avatar">
          <img
            v-if="item.msg.avatar"
            :src="avatarUrl(item.msg.avatar)"
            referrerpolicy="no-referrer"
            alt=""
          />
          <span v-else>{{ initial(item.msg.uname) }}</span>
        </div>

        <div class="sc-content">
          <div class="sc-meta">
            <span
              v-if="showSourceBadges && item.msg.sourceColor"
              class="sc-src"
              :class="{ dot: !item.msg.badge }"
              :style="{ background: item.msg.sourceColor }"
              :title="item.msg.sourceName"
            >
              {{ item.msg.badge }}
            </span>
            <strong>{{ item.msg.uname || '匿名' }}</strong>
            <span v-if="store.settings.sc.showPrice && item.msg.price" class="sc-price">
              ¥{{ item.msg.price }}
            </span>
            <span class="sc-tag">SC</span>
          </div>
          <p class="sc-text">{{ item.msg.text }}</p>
        </div>

        <div class="sc-time">
          <AppIcon name="clock" :size="12" />
          {{ Math.round(item.duration / 1000) }}s
        </div>

        <span
          class="sc-progress"
          :style="{ animationDuration: `${item.duration}ms` }"
        ></span>
      </article>
    </TransitionGroup>
  </div>
</template>

<style scoped>
.sc-overlay {
  position: absolute;
  top: calc(54px + var(--safe-top));
  left: calc(12px + var(--safe-left));
  z-index: 8;
  width: min(380px, calc(100% - 24px));
  pointer-events: none;
}

.sc-stack {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.sc-card {
  position: relative;
  display: flex;
  align-items: flex-start;
  gap: 10px;
  padding: 10px 12px 12px;
  border-radius: 18px;
  overflow: hidden;
  color: #2b2115;
  background: linear-gradient(135deg, rgba(255, 251, 242, 0.98), rgba(255, 232, 196, 0.97));
  border: 1px solid rgba(217, 119, 87, 0.35);
  box-shadow: 0 12px 34px rgba(0, 0, 0, 0.28);
  backdrop-filter: blur(18px);
}

.sc-card.tier-mid {
  background: linear-gradient(135deg, rgba(255, 243, 218, 0.98), rgba(255, 205, 158, 0.97));
  border-color: rgba(217, 119, 87, 0.5);
}

.sc-card.tier-high {
  color: #fff8ef;
  background: linear-gradient(135deg, rgba(215, 111, 78, 0.97), rgba(176, 67, 52, 0.97));
  border-color: rgba(255, 214, 182, 0.55);
}

.sc-card.tier-legend {
  color: #2b2115;
  background: linear-gradient(135deg, #ffe9a8, #f3b44a 55%, #e48d3d);
  border-color: rgba(255, 246, 208, 0.8);
}

.sc-avatar {
  width: 38px;
  height: 38px;
  border-radius: 50%;
  overflow: hidden;
  flex: 0 0 auto;
  display: grid;
  place-items: center;
  color: #fff;
  font-size: 15px;
  font-weight: 700;
  background: linear-gradient(135deg, #d97757, #6679cf);
  border: 2px solid rgba(255, 255, 255, 0.55);
}

.sc-avatar img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.sc-content {
  min-width: 0;
  flex: 1;
  padding-right: 2px;
}

.sc-meta {
  display: flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
}

.sc-src {
  flex: 0 0 auto;
  display: inline-grid;
  place-items: center;
  min-width: 16px;
  height: 16px;
  padding: 0 4px;
  border-radius: 5px;
  color: #fff;
  font-size: 9.5px;
  font-weight: 750;
  line-height: 1;
}

.sc-src.dot {
  min-width: 8px;
  width: 8px;
  height: 14px;
  padding: 0;
  border-radius: 999px;
}

.sc-meta strong {
  font-size: 13px;
  line-height: 1.3;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.sc-price {
  flex: 0 0 auto;
  font-size: 11px;
  font-weight: 750;
  padding: 1px 7px;
  border-radius: 999px;
  background: rgba(217, 119, 87, 0.18);
  color: #a64f35;
}

.tier-high .sc-price,
.tier-legend .sc-price {
  background: rgba(255, 255, 255, 0.32);
  color: inherit;
}

.sc-tag {
  flex: 0 0 auto;
  margin-left: auto;
  font-size: 9px;
  font-weight: 800;
  letter-spacing: 0.08em;
  padding: 2px 6px;
  border-radius: 999px;
  background: rgba(217, 119, 87, 0.16);
  color: #a64f35;
}

.tier-high .sc-tag,
.tier-legend .sc-tag {
  background: rgba(255, 255, 255, 0.35);
  color: inherit;
}

.sc-text {
  margin: 3px 0 0;
  font-size: 13.5px;
  line-height: 1.48;
  font-weight: 550;
  word-break: break-word;
  display: -webkit-box;
  -webkit-line-clamp: 4;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.sc-time {
  position: absolute;
  right: 10px;
  bottom: 7px;
  display: flex;
  align-items: center;
  gap: 3px;
  font-size: 9px;
  opacity: 0.55;
}

.sc-progress {
  position: absolute;
  left: 0;
  bottom: 0;
  height: 3px;
  width: 100%;
  background: currentColor;
  opacity: 0.38;
  transform-origin: left center;
  animation-name: scProgress;
  animation-timing-function: linear;
  animation-fill-mode: forwards;
}

@keyframes scProgress {
  from {
    transform: scaleX(1);
  }
  to {
    transform: scaleX(0);
  }
}

.sc-enter-active {
  transition:
    opacity 0.25s ease,
    transform 0.3s cubic-bezier(0.2, 0.9, 0.2, 1.15);
}

.sc-leave-active {
  transition:
    opacity 0.2s ease,
    transform 0.2s ease;
}

.sc-enter-from {
  opacity: 0;
  transform: translateX(-18px) scale(0.96);
}

.sc-leave-to {
  opacity: 0;
  transform: translateX(-12px) scale(0.97);
}

.sc-move {
  transition: transform 0.25s ease;
}

@media (max-width: 640px) {
  .sc-overlay {
    top: calc(48px + var(--safe-top));
    width: min(320px, calc(100% - 20px));
  }

  .sc-card {
    padding: 7px 9px 9px;
    border-radius: 14px;
    gap: 8px;
  }

  .sc-avatar {
    width: 30px;
    height: 30px;
    border-width: 1px;
  }

  .sc-meta strong {
    font-size: 12px;
  }

  .sc-price {
    font-size: 10px;
    padding: 0 6px;
  }

  .sc-tag {
    font-size: 8px;
    padding: 1px 5px;
  }

  .sc-text {
    margin-top: 2px;
    font-size: 12px;
    line-height: 1.4;
    -webkit-line-clamp: 2;
  }

  .sc-time {
    display: none;
  }

  .sc-progress {
    height: 2px;
  }

  /* 竖屏视频区域不高，一次只保留最新一条，避免盖住控制条 */
  .sc-stack .sc-card:nth-child(n + 2) {
    display: none;
  }
}

@media (max-height: 420px) and (orientation: landscape) {
  .sc-overlay {
    top: calc(44px + var(--safe-top));
    width: min(340px, 32vw);
  }

  .sc-stack .sc-card:nth-child(n + 3) {
    display: none;
  }
}
</style>
