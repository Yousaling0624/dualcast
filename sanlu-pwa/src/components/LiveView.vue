<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { store, bindVideos, attachStreams, exitSession, togglePlay, reconnect, swap, setQuality } from '../lib/store'
import DanmakuCanvas from './DanmakuCanvas.vue'
import ChatPanel from './ChatPanel.vue'
import SettingsDrawer from './SettingsDrawer.vue'

const zoneEl = ref<HTMLElement | null>(null)
const videoAEl = ref<HTMLVideoElement | null>(null)
const videoBEl = ref<HTMLVideoElement | null>(null)
const drawer = ref<InstanceType<typeof SettingsDrawer> | null>(null)

const controls = ref(true)
let hideTimer: number | null = null
const qualityOpen = ref(false)
const chatCollapsed = ref(false)
const isFullscreen = ref(false)

/* A 解说小窗 */
const miniOn = ref(true)
const miniExpanded = ref(false)
const miniPos = ref({ x: 0, y: 0 }) // 相对 zone 右上角偏移，负值往左上
let dragging = false
let dragStart = { x: 0, y: 0, px: 0, py: 0 }

const qnLabel = computed(() => {
  const q = store.b.acceptQn.find((x) => x.qn === store.b.qn)
  return q?.desc || store.b.qn ? `${store.b.qn}` : '画质'
})

function reveal() {
  controls.value = true
  if (hideTimer) clearTimeout(hideTimer)
  hideTimer = window.setTimeout(() => {
    controls.value = false
    qualityOpen.value = false
  }, 4200)
}

function onZoneTap(e: PointerEvent) {
  if ((e.target as HTMLElement).closest('button, .mini, input')) return
  reveal()
}

function onMouseMove() {
  if (window.matchMedia('(hover: hover)').matches) reveal()
}

function goBack() {
  exitSession()
}

function toggleFullscreen() {
  const el = zoneEl.value
  if (!el) return
  if (document.fullscreenElement) {
    document.exitFullscreen().catch(() => {})
  } else {
    el.requestFullscreen?.().catch(() => {})
  }
}

function onFsChange() {
  isFullscreen.value = !!document.fullscreenElement
}

function enterPip() {
  const v = videoBEl.value as any
  if (!v) return
  if (document.pictureInPictureElement) {
    document.exitPictureInPicture().catch(() => {})
  } else if (v.requestPictureInPicture) {
    v.requestPictureInPicture().catch(() => {})
  } else if (v.webkitSetPresentationMode) {
    v.webkitSetPresentationMode('picture-in-picture')
  }
}

function toggleMini(on?: boolean) {
  miniOn.value = on ?? !miniOn.value
  reveal()
}

function toggleMiniExpand() {
  miniExpanded.value = !miniExpanded.value
  reveal()
}

function onMiniPointerDown(e: PointerEvent) {
  if (miniExpanded.value) return
  dragging = true
  const target = e.currentTarget as HTMLElement
  target.setPointerCapture(e.pointerId)
  dragStart = { x: e.clientX, y: e.clientY, px: miniPos.value.x, py: miniPos.value.y }
  reveal()
}

function onMiniPointerMove(e: PointerEvent) {
  if (!dragging) return
  const zone = zoneEl.value
  if (!zone) return
  const rect = zone.getBoundingClientRect()
  const miniW = Math.min(rect.width * 0.3, 320)
  const miniH = (miniW * 9) / 16
  const nx = dragStart.px + (dragStart.x - e.clientX)
  const ny = dragStart.py + (e.clientY - dragStart.y)
  miniPos.value = {
    x: Math.max(0, Math.min(nx, rect.width - miniW - 8)),
    y: Math.max(0, Math.min(ny, rect.height - miniH - 8)),
  }
}

function onMiniPointerUp() {
  dragging = false
}

function openSettings(tab: 'account' | 'danmaku' | 'mix' | 'video') {
  drawer.value?.open(tab)
}

function onKey(e: KeyboardEvent) {
  const t = e.target as HTMLElement
  if (t.tagName === 'INPUT' || t.tagName === 'TEXTAREA') return
  if (e.code === 'Space') {
    e.preventDefault()
    togglePlay()
    reveal()
  } else if (e.key === 'f' || e.key === 'F') {
    toggleFullscreen()
  } else if (e.key === 'r' || e.key === 'R') {
    void reconnect()
  }
}

onMounted(async () => {
  bindVideos(videoAEl.value!, videoBEl.value!)
  attachStreams()
  document.addEventListener('fullscreenchange', onFsChange)
  window.addEventListener('keydown', onKey)
  // 播放器在 bindVideos 后需要一次用户手势兜底（iOS 自动播放策略）
  reveal()
})

onBeforeUnmount(() => {
  document.removeEventListener('fullscreenchange', onFsChange)
  window.removeEventListener('keydown', onKey)
  if (hideTimer) clearTimeout(hideTimer)
})
</script>

<template>
  <div class="live" :class="{ collapsed: chatCollapsed }">
    <div ref="zoneEl" class="video-zone" @pointerdown="onZoneTap" @mousemove="onMouseMove">
      <video ref="videoBEl" class="video-b" playsinline :class="store.settings.cover ? 'cover' : 'contain'"></video>

      <DanmakuCanvas />

      <!-- 顶栏 -->
      <Transition name="fade">
        <div v-if="controls" class="topbar">
          <button class="ib" title="返回" @click="goBack">←</button>
          <div class="titles">
            <strong>{{ store.b.title || '直播' }}</strong>
            <small>
              A 弹幕 ·
              {{ store.danmakuStatus === 'open' ? `${store.viewer} 人` : '连接中' }}
            </small>
          </div>
          <div class="actions">
            <button class="ib" title="重连" @click="reconnect">⟳</button>
            <button class="ib" :class="{ on: miniOn }" title="A 解说小窗" @click="toggleMini()">▣</button>
            <button class="ib" title="画中画" @click="enterPip">⧉</button>
            <button class="pill" title="画质" @click="qualityOpen = !qualityOpen">{{ qnLabel }}</button>
            <button class="ib" :title="isFullscreen ? '退出全屏' : '全屏'" @click="toggleFullscreen">⛶</button>
          </div>
        </div>
      </Transition>

      <!-- 画质菜单 -->
      <Transition name="fade">
        <div v-if="controls && qualityOpen" class="quality">
          <p>画质（B 路）</p>
          <button
            v-for="q in store.b.acceptQn"
            :key="q.qn"
            :class="{ on: q.qn === store.b.qn }"
            @click="setQuality('b', q.qn); qualityOpen = false"
          >
            {{ q.desc }}
          </button>
          <p v-if="!store.b.acceptQn.length">没有可选画质</p>
        </div>
      </Transition>

      <!-- 底部控制条 -->
      <Transition name="up">
        <div v-if="controls" class="bottombar">
          <button class="pill big" @click="togglePlay">{{ store.playing ? '⏸' : '▶' }}</button>
          <button class="pill" :disabled="store.swapping" @click="swap">{{ store.swapping ? '切换中…' : '⇄ 切画面' }}</button>
          <button class="pill" :class="{ on: store.settings.danmaku.enabled }" @click="store.settings.danmaku.enabled = !store.settings.danmaku.enabled">
            弹幕
          </button>
          <button class="pill" @click="openSettings('mix')">🎚 混音</button>
          <button class="pill" @click="openSettings('danmaku')">设置</button>
        </div>
      </Transition>

      <!-- A 解说小窗 -->
      <div
        class="mini"
        :class="{ expanded: miniExpanded, hidden: !miniOn }"
        :style="miniExpanded ? undefined : { right: miniPos.x + 'px', top: miniPos.y + 'px' }"
        @pointerdown="onMiniPointerDown"
        @pointermove="onMiniPointerMove"
        @pointerup="onMiniPointerUp"
        @pointercancel="onMiniPointerUp"
      >
        <video ref="videoAEl" playsinline></video>
        <div class="mini-top">
          <span class="mini-tag">A · 解说</span>
          <span class="mini-btns">
            <button :title="miniExpanded ? '还原小窗' : '小窗全屏'" @pointerdown.stop @click="toggleMiniExpand">
              {{ miniExpanded ? '⤡' : '⛶' }}
            </button>
            <button title="关闭小窗" @pointerdown.stop @click="toggleMini(false)">×</button>
          </span>
        </div>
      </div>
    </div>

    <ChatPanel v-if="!chatCollapsed" @collapse="chatCollapsed = true" />
    <div v-else class="chat-fab-row">
      <button class="chat-fab" @click="chatCollapsed = false">💬 聊天室</button>
    </div>

    <SettingsDrawer ref="drawer" />
  </div>
</template>

<style scoped>
.live {
  display: flex;
  flex-direction: column;
  height: 100dvh;
  background: #000;
  padding-top: var(--safe-top);
}

/* 竖屏：上画面下聊天 */
.video-zone {
  position: relative;
  flex: 0 0 auto;
  aspect-ratio: 16 / 9;
  background: #000;
  overflow: hidden;
}

.live.collapsed .video-zone {
  flex: 1;
  aspect-ratio: auto;
}

.video-b {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  background: #000;
}

.video-b.contain {
  object-fit: contain;
}

.video-b.cover {
  object-fit: cover;
}

/* 顶栏 */
.topbar {
  position: absolute;
  top: 0;
  left: 0;
  right: 0;
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px calc(10px + var(--safe-right)) 8px calc(10px + var(--safe-left));
  background: linear-gradient(rgba(0, 0, 0, 0.75), transparent);
  z-index: 5;
}

.titles {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
}

.titles strong {
  font-size: 14px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.titles small {
  font-size: 11px;
  color: var(--text-dim);
}

.actions {
  display: flex;
  align-items: center;
  gap: 6px;
}

.ib {
  width: 34px;
  height: 34px;
  flex: 0 0 auto;
  border: none;
  border-radius: 50%;
  background: rgba(255, 255, 255, 0.14);
  color: #fff;
  font-size: 15px;
  cursor: pointer;
  display: grid;
  place-items: center;
}

.ib.on {
  background: rgba(91, 140, 255, 0.55);
}

.pill {
  height: 34px;
  border: none;
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.14);
  color: #fff;
  padding: 0 12px;
  font-size: 12px;
  cursor: pointer;
  white-space: nowrap;
}

.pill.on {
  background: rgba(91, 140, 255, 0.55);
}

.pill.big {
  width: 44px;
  padding: 0;
  font-size: 17px;
}

.quality {
  position: absolute;
  top: 50px;
  right: 10px;
  background: rgba(15, 20, 33, 0.96);
  border: 1px solid var(--line);
  border-radius: 12px;
  padding: 10px;
  display: flex;
  flex-direction: column;
  gap: 4px;
  z-index: 6;
  min-width: 120px;
}

.quality p {
  margin: 0 0 4px;
  font-size: 11px;
  color: var(--text-dim);
}

.quality button {
  border: none;
  background: transparent;
  color: var(--text);
  text-align: left;
  padding: 8px 10px;
  border-radius: 8px;
  cursor: pointer;
  font-size: 13px;
}

.quality button.on {
  background: rgba(91, 140, 255, 0.25);
  color: #fff;
}

/* 底部控制条 */
.bottombar {
  position: absolute;
  bottom: 0;
  left: 0;
  right: 0;
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 10px calc(10px + var(--safe-right)) calc(10px + var(--safe-bottom));
  background: linear-gradient(transparent, rgba(0, 0, 0, 0.8));
  z-index: 5;
  overflow-x: auto;
}

/* A 小窗 */
.mini {
  position: absolute;
  top: 46px;
  right: 10px;
  width: min(30%, 320px);
  aspect-ratio: 16 / 9;
  background: #000;
  border: 1px solid rgba(255, 255, 255, 0.35);
  border-radius: 10px;
  overflow: hidden;
  z-index: 6;
  box-shadow: 0 8px 24px rgba(0, 0, 0, 0.5);
  touch-action: none;
  cursor: grab;
}

.mini video {
  width: 100%;
  height: 100%;
  object-fit: cover;
  pointer-events: none;
}

.mini.expanded {
  inset: 0;
  width: 100%;
  border-radius: 0;
  border: none;
  cursor: default;
}

.mini.hidden {
  opacity: 0;
  pointer-events: none;
  transform: translateX(200vw);
}

.mini-top {
  position: absolute;
  top: 0;
  left: 0;
  right: 0;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 4px 6px;
  background: linear-gradient(rgba(0, 0, 0, 0.6), transparent);
}

.mini-tag {
  font-size: 10px;
  color: #fff;
  background: rgba(0, 0, 0, 0.4);
  border-radius: 999px;
  padding: 2px 7px;
}

.mini-btns {
  display: flex;
  gap: 4px;
}

.mini-btns button {
  width: 24px;
  height: 24px;
  border: none;
  border-radius: 50%;
  background: rgba(0, 0, 0, 0.5);
  color: #fff;
  cursor: pointer;
  font-size: 12px;
  display: grid;
  place-items: center;
}

/* 聊天室收起后的浮动按钮 */
.chat-fab-row {
  position: absolute;
  right: 14px;
  bottom: calc(64px + var(--safe-bottom));
  z-index: 8;
}

.chat-fab {
  border: none;
  background: rgba(30, 39, 64, 0.92);
  color: var(--text);
  border-radius: 999px;
  padding: 10px 16px;
  font-size: 13px;
  cursor: pointer;
  box-shadow: 0 6px 20px rgba(0, 0, 0, 0.45);
}

/* 横屏手机 / 平板：左右分栏 */
@media (orientation: landscape) and (max-width: 1023px) {
  .live {
    flex-direction: row;
    padding-top: 0;
  }

  .video-zone {
    flex: 1;
    aspect-ratio: auto;
    height: 100dvh;
  }

  .live :deep(.chat) {
    width: clamp(280px, 38vw, 400px);
    height: 100dvh;
  }

  .chat-fab-row {
    bottom: calc(16px + var(--safe-bottom));
  }
}

/* PC 大屏 */
@media (min-width: 1024px) {
  .live {
    display: grid;
    grid-template-columns: 1fr clamp(320px, 28vw, 420px);
    padding-top: 0;
  }

  .video-zone {
    height: 100dvh;
    aspect-ratio: auto;
  }

  .live.collapsed {
    grid-template-columns: 1fr;
  }

  .live.collapsed .chat-fab-row {
    position: fixed;
  }

  .topbar {
    padding-top: 12px;
  }

  .titles strong {
    font-size: 15px;
  }
}

/* 过渡动画 */
.fade-enter-active,
.fade-leave-active {
  transition: opacity 0.25s ease;
}

.fade-enter-from,
.fade-leave-to {
  opacity: 0;
}

.up-enter-active,
.up-leave-active {
  transition:
    transform 0.25s ease,
    opacity 0.25s ease;
}

.up-enter-from,
.up-leave-to {
  transform: translateY(16px);
  opacity: 0;
}
</style>
