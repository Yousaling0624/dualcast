<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import {
  attachStreams,
  bindVideos,
  buildReply,
  debugSnapshot,
  canInteract,
  clearSendError,
  exitSession,
  plusOne,
  reconnect,
  replyInputLimit,
  resumePlayback,
  retryDanmaku,
  send,
  setCommentary,
  setQuality,
  store,
  swap,
  togglePlay,
  viewerLabel,
} from '../lib/store'
import type { ChatMsg } from '../lib/danmaku'
import DanmakuCanvas from './DanmakuCanvas.vue'
import ChatPanel from './ChatPanel.vue'
import SettingsDrawer from './SettingsDrawer.vue'

const zoneEl = ref<HTMLElement | null>(null)
const videoAEl = ref<HTMLVideoElement | null>(null)
const videoBEl = ref<HTMLVideoElement | null>(null)
const dmCanvas = ref<InstanceType<typeof DanmakuCanvas> | null>(null)
const chatPanel = ref<InstanceType<typeof ChatPanel> | null>(null)
const drawer = ref<InstanceType<typeof SettingsDrawer> | null>(null)
const quickInput = ref<HTMLInputElement | null>(null)

const controls = ref(true)
let hideTimer: number | null = null
const qualityOpen = ref(false)
const chatCollapsed = ref(false)
const isFullscreen = ref(false)
let collapsedBeforeFullscreen = false

/* A 解说小窗 */
const miniOn = ref(store.commentaryOn)
const miniExpanded = ref(false)
const miniPos = ref({ x: 10, y: 46 })
const miniW = ref(240)
const miniZoom = ref(1)
const miniPan = ref({ x: 0, y: 0 })
const miniH = computed(() => (miniW.value * 9) / 16)

const pointers = new Map<number, { x: number; y: number }>()
let dragging = false
let dragStart = { x: 0, y: 0, px: 0, py: 0, wx: 0, wy: 0 }
let pinchDist = 0
let pinchZoom = 1
let resizing = false
let resizeStart = { x: 0, w: 0 }

/* 点画面上的弹幕，浮出 +1 / 回复 */
const quickTarget = ref<ChatMsg | null>(null)
const quickReply = ref(false)
const quickText = ref('')
const quickHint = ref('')

const qnLabel = computed(() => {
  const slot = store.b
  return slot.qn ? (slot.acceptQn.find((x) => x.qn === slot.qn)?.desc || `${slot.qn}`) : '画质'
})

const videoFit = computed(() => {
  if (store.settings.video.scale === 'crop') return 'cover'
  if (store.settings.video.scale === 'fill') return 'fill'
  return 'contain'
})

const statusLabel = computed(() => {
  const s = store.danmakuStatus
  if (s === 'open') return viewerLabel() ? `A 弹幕 · ${viewerLabel()}` : 'A 弹幕已连接'
  if (s === 'reconnecting') return 'A 弹幕重连中…'
  if (s === 'connecting') return 'A 弹幕连接中…'
  if (s === 'failed') return 'A 弹幕连接失败'
  return 'A 弹幕未连接'
})

const quickLimit = computed(() => (quickTarget.value ? replyInputLimit(quickTarget.value) : 20))
const quickPreview = computed(() =>
  quickTarget.value && quickText.value.trim() ? buildReply(quickText.value, quickTarget.value) : ''
)

const firstError = computed(() => store.startError || store.a.error || store.b.error || store.danmakuError)

function reveal() {
  controls.value = true
  if (hideTimer) clearTimeout(hideTimer)
  hideTimer = window.setTimeout(() => {
    controls.value = false
    qualityOpen.value = false
  }, 4200)
}

function onZoneTap(e: PointerEvent) {
  const t = e.target as HTMLElement
  if (t.closest('button, .mini, .quality, .quickbar, input')) return
  const el = zoneEl.value
  if (el && dmCanvas.value) {
    const rect = el.getBoundingClientRect()
    const hit = dmCanvas.value.hitTest(e.clientX - rect.left, e.clientY - rect.top)
    if (hit) {
      openQuick(hit)
      return
    }
  }
  reveal()
}

function onMouseMove() {
  if (window.matchMedia('(hover: hover)').matches) reveal()
}

function goBack() {
  if (quickTarget.value) {
    closeQuick()
    return
  }
  if (isFullscreen.value) {
    void toggleFullscreen()
    return
  }
  exitSession()
}

async function toggleFullscreen() {
  const el = zoneEl.value
  if (!el) return
  if (document.fullscreenElement) {
    await document.exitFullscreen().catch(() => {})
    try {
      await (screen.orientation as any)?.unlock?.()
    } catch {
      /* noop */
    }
  } else {
    try {
      await el.requestFullscreen?.()
      await (screen.orientation as any)?.lock?.('landscape')
    } catch {
      /* 浏览器不支持方向锁就保持当前方向 */
    }
  }
}

function onFsChange() {
  isFullscreen.value = !!document.fullscreenElement
  if (isFullscreen.value) {
    collapsedBeforeFullscreen = chatCollapsed.value
    chatCollapsed.value = true
  } else {
    chatCollapsed.value = collapsedBeforeFullscreen
    void (screen.orientation as any)?.unlock?.()
  }
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
  const next = on ?? !miniOn.value
  miniOn.value = next
  setCommentary(next)
  if (!next) miniExpanded.value = false
  reveal()
}

function toggleMiniExpand() {
  miniExpanded.value = !miniExpanded.value
  if (!miniExpanded.value) {
    miniZoom.value = 1
    miniPan.value = { x: 0, y: 0 }
  }
  reveal()
}

function clampMiniPos() {
  const zone = zoneEl.value
  if (!zone) return
  const rect = zone.getBoundingClientRect()
  miniPos.value = {
    x: Math.max(0, Math.min(miniPos.value.x, rect.width - miniW.value - 8)),
    y: Math.max(0, Math.min(miniPos.value.y, rect.height - miniH.value - 8)),
  }
}

function onMiniPointerDown(e: PointerEvent) {
  if (miniExpanded.value) return
  const target = e.currentTarget as HTMLElement
  target.setPointerCapture(e.pointerId)
  pointers.set(e.pointerId, { x: e.clientX, y: e.clientY })
  if (pointers.size === 2) {
    const [a, b] = [...pointers.values()]
    pinchDist = Math.hypot(a.x - b.x, a.y - b.y) || 1
    pinchZoom = miniZoom.value
    dragging = false
  } else if (pointers.size === 1) {
    dragging = true
    dragStart = {
      x: e.clientX,
      y: e.clientY,
      px: miniPan.value.x,
      py: miniPan.value.y,
      wx: miniPos.value.x,
      wy: miniPos.value.y,
    }
  }
  reveal()
}

function onMiniPointerMove(e: PointerEvent) {
  if (miniExpanded.value || resizing) return
  if (pointers.has(e.pointerId)) pointers.set(e.pointerId, { x: e.clientX, y: e.clientY })
  if (pointers.size === 2) {
    const [a, b] = [...pointers.values()]
    const dist = Math.hypot(a.x - b.x, a.y - b.y)
    miniZoom.value = Math.max(1, Math.min(3, pinchZoom * (dist / pinchDist)))
    return
  }
  if (!dragging) return
  if (miniZoom.value > 1) {
    const max = ((miniZoom.value - 1) * miniW.value) / 2
    miniPan.value = {
      x: Math.max(-max, Math.min(max, dragStart.px + e.clientX - dragStart.x)),
      y: Math.max(-max, Math.min(max, dragStart.py + e.clientY - dragStart.y)),
    }
  } else {
    const zone = zoneEl.value
    if (!zone) return
    const rect = zone.getBoundingClientRect()
    miniPos.value = {
      x: Math.max(0, Math.min(dragStart.wx + (dragStart.x - e.clientX), rect.width - miniW.value - 8)),
      y: Math.max(0, Math.min(dragStart.wy + (e.clientY - dragStart.y), rect.height - miniH.value - 8)),
    }
  }
}

function onMiniPointerUp(e: PointerEvent) {
  pointers.delete(e.pointerId)
  if (pointers.size === 0) dragging = false
  else if (pointers.size === 1) {
    const [p] = [...pointers.values()]
    dragStart = {
      x: p.x,
      y: p.y,
      px: miniPan.value.x,
      py: miniPan.value.y,
      wx: miniPos.value.x,
      wy: miniPos.value.y,
    }
  }
}

function onMiniDoubleClick() {
  if (miniExpanded.value) return
  miniZoom.value = miniZoom.value > 1 ? 1 : 2
  miniPan.value = { x: 0, y: 0 }
}

function onResizeDown(e: PointerEvent) {
  e.stopPropagation()
  ;(e.currentTarget as HTMLElement).setPointerCapture(e.pointerId)
  resizing = true
  resizeStart = { x: e.clientX, w: miniW.value }
}

function onResizeMove(e: PointerEvent) {
  if (!resizing) return
  const zone = zoneEl.value
  if (!zone) return
  const rect = zone.getBoundingClientRect()
  miniW.value = Math.max(120, Math.min(rect.width - 16, resizeStart.w + (e.clientX - resizeStart.x)))
  clampMiniPos()
}

function onResizeUp() {
  resizing = false
}

function openQuick(m: ChatMsg) {
  quickTarget.value = m
  quickReply.value = false
  quickText.value = ''
  quickHint.value = ''
  clearSendError()
  controls.value = false
}

function closeQuick() {
  quickTarget.value = null
  quickReply.value = false
  quickText.value = ''
  quickHint.value = ''
}

function doQuickPlusOne() {
  if (!quickTarget.value) return
  if (!store.loggedIn) {
    quickHint.value = '先登录 B 站账号才能 +1'
    return
  }
  void plusOne(quickTarget.value).then((ok) => {
    if (ok) closeQuick()
  })
}

async function doQuickAnswer() {
  if (!store.loggedIn) {
    quickHint.value = '先登录 B 站账号才能回复'
    return
  }
  quickReply.value = true
  await nextTick()
  quickInput.value?.focus()
}

async function doQuickSend() {
  if (!quickTarget.value || !quickText.value.trim()) return
  if (!store.loggedIn) {
    quickHint.value = '先登录 B 站账号才能回复'
    return
  }
  const ok = await send(quickText.value, quickTarget.value)
  if (ok) closeQuick()
}

async function openSettings(tab: 'account' | 'danmaku' | 'mix' | 'video' | 'relay' | 'about') {
  // 设置面板 Teleport 到 body，不在全屏子树里；先退出全屏再打开
  if (document.fullscreenElement) {
    await document.exitFullscreen().catch(() => {})
  }
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
    void toggleFullscreen()
  } else if (e.key === 'r' || e.key === 'R') {
    void reconnect()
  } else if (e.key === 'd' || e.key === 'D') {
    store.settings.danmaku.enabled = !store.settings.danmaku.enabled
  } else if (e.key === 'Escape') {
    if (quickTarget.value) closeQuick()
    else if (isFullscreen.value) void toggleFullscreen()
  }
}

function onWindowResize() {
  const zone = zoneEl.value
  if (!zone) return
  const max = zone.getBoundingClientRect().width - 16
  miniW.value = Math.min(miniW.value, Math.max(120, max))
  clampMiniPos()
}

watch(
  () => store.commentaryOn,
  (v) => {
    miniOn.value = v
    if (!v) miniExpanded.value = false
  }
)

watch(
  () => store.a.roomId,
  () => {
    closeQuick()
    chatCollapsed.value = false
  }
)

onMounted(() => {
  if (new URLSearchParams(location.search).has('debug')) {
    ;(window as any).__dcDebug = debugSnapshot
  }
  if (videoAEl.value && videoBEl.value) bindVideos(videoAEl.value, videoBEl.value)
  attachStreams()
  document.addEventListener('fullscreenchange', onFsChange)
  window.addEventListener('keydown', onKey)
  window.addEventListener('resize', onWindowResize)
  const zone = zoneEl.value
  if (zone) miniW.value = Math.min(zone.clientWidth * 0.3, 320)
  clampMiniPos()
  reveal()
})

onBeforeUnmount(() => {
  document.removeEventListener('fullscreenchange', onFsChange)
  window.removeEventListener('keydown', onKey)
  window.removeEventListener('resize', onWindowResize)
  if (hideTimer) clearTimeout(hideTimer)
})
</script>

<template>
  <div class="live" :class="{ collapsed: chatCollapsed }">
    <div ref="zoneEl" class="video-zone" @pointerdown="onZoneTap" @mousemove="onMouseMove">
      <video ref="videoBEl" class="video-b" playsinline :style="{ objectFit: videoFit }"></video>

      <DanmakuCanvas ref="dmCanvas" />

      <!-- 浏览器拦截自动播放时，用一次点击解锁声音 -->
      <button
        v-if="store.a.blocked || store.b.blocked"
        class="play-gate"
        @pointerdown.stop
        @click.stop="resumePlayback"
      >
        ▶ 点此开始播放
      </button>

      <!-- 顶栏 -->
      <Transition name="fade">
        <div v-if="controls" class="topbar">
          <button class="ib" title="返回" @click="goBack">←</button>
          <div class="titles">
            <strong>{{ store.b.title || '直播' }}</strong>
            <small>
              <span class="live-dot" :class="store.danmakuStatus"></span>
              {{ statusLabel }}
            </small>
          </div>
          <div class="actions">
            <button class="ib" title="重连" :disabled="store.reconnecting" @click="reconnect">
              {{ store.reconnecting ? '…' : '⟳' }}
            </button>
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
            :disabled="store.qualitySwitching"
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
          <button class="pill" :disabled="store.swapping" @click="swap">
            {{ store.swapping ? '切换中…' : '⇄ 切画面' }}
          </button>
          <button
            class="pill"
            :class="{ on: store.settings.danmaku.enabled }"
            @click="store.settings.danmaku.enabled = !store.settings.danmaku.enabled"
          >
            弹幕
          </button>
          <button class="pill" @click="openSettings('mix')">🎚 混音</button>
          <button class="pill" @click="openSettings('video')">画面</button>
          <button class="pill" @click="openSettings('danmaku')">设置</button>
        </div>
      </Transition>

      <!-- A 解说小窗 -->
      <div
        class="mini"
        :class="{ expanded: miniExpanded, hidden: !miniOn }"
        :style="
          miniExpanded
            ? undefined
            : { right: miniPos.x + 'px', top: miniPos.y + 'px', width: miniW + 'px', height: miniH + 'px' }
        "
        @pointerdown="onMiniPointerDown"
        @pointermove="onMiniPointerMove"
        @pointerup="onMiniPointerUp"
        @pointercancel="onMiniPointerUp"
        @dblclick="onMiniDoubleClick"
      >
        <video
          ref="videoAEl"
          playsinline
          :style="{
            transform: `translate(${miniPan.x}px, ${miniPan.y}px) scale(${miniZoom})`,
          }"
        ></video>
        <div class="mini-top">
          <span class="mini-tag">A · 解说</span>
          <span class="mini-btns">
            <button title="重置缩放" @pointerdown.stop @click.stop="miniZoom = 1; miniPan = { x: 0, y: 0 }">
              {{ miniZoom > 1 ? '1x' : '⟲' }}
            </button>
            <button
              :title="miniExpanded ? '还原小窗' : '小窗全屏'"
              @pointerdown.stop
              @click.stop="toggleMiniExpand"
            >
              {{ miniExpanded ? '⤡' : '⛶' }}
            </button>
            <button title="关闭小窗" @pointerdown.stop @click.stop="toggleMini(false)">×</button>
          </span>
        </div>
        <div
          v-if="!miniExpanded"
          class="mini-resize"
          title="拖动缩放"
          @pointerdown="onResizeDown"
          @pointermove="onResizeMove"
          @pointerup="onResizeUp"
          @pointercancel="onResizeUp"
        ></div>
      </div>

      <!-- 点中画面弹幕后的快捷条 -->
      <div v-if="quickTarget" class="quickbar" @pointerdown.stop>
        <div class="quick-head">
          <strong>{{ quickTarget.uname || '匿名' }}</strong>
          <button class="quick-x" @click="closeQuick">×</button>
        </div>
        <p class="quick-text">{{ quickTarget.text }}</p>

        <template v-if="!quickReply">
          <p v-if="quickHint" class="quick-hint">{{ quickHint }}</p>
          <p v-else-if="store.sendState.error" class="quick-hint">{{ store.sendState.error }}</p>
          <div class="quick-actions">
            <template v-if="store.loggedIn">
              <button v-if="canInteract(quickTarget)" class="qbtn plus" @click="doQuickPlusOne">+1</button>
              <button v-if="canInteract(quickTarget)" class="qbtn" @click="doQuickAnswer">回复</button>
            </template>
            <button v-else class="qbtn" @click="openSettings('account')">去登录</button>
            <button class="qbtn dim" @click="closeQuick">关闭</button>
          </div>
        </template>

        <template v-else>
          <input
            ref="quickInput"
            v-model="quickText"
            :maxlength="quickLimit"
            :placeholder="`回复 @${quickTarget.uname || '匿名'}`"
            @keydown.enter="(e) => !e.isComposing && doQuickSend()"
          />
          <p v-if="quickPreview" class="quick-preview">将发送：{{ quickPreview }}</p>
          <p v-if="quickHint" class="quick-hint">{{ quickHint }}</p>
          <p v-else-if="store.sendState.error" class="quick-hint">{{ store.sendState.error }}</p>
          <div class="quick-actions">
            <span class="quick-count">{{ quickText.length }}/{{ quickLimit }}</span>
            <button class="qbtn dim" @click="quickReply = false; quickText = ''">取消</button>
            <button class="qbtn plus" :disabled="!quickText.trim() || store.sendState.sending" @click="doQuickSend">
              {{ store.sendState.sending ? '发送中…' : '发送' }}
            </button>
          </div>
        </template>
      </div>

      <!-- 错误提示 -->
      <div v-if="firstError && !quickTarget" class="err-banner" @pointerdown.stop>
        <span>{{ firstError }}</span>
        <button v-if="store.danmakuStatus === 'failed'" @click="retryDanmaku">重试弹幕</button>
        <button v-else @click="reconnect">重连</button>
        <button class="err-x" @click="store.a.error = ''; store.b.error = ''; store.startError = ''; store.danmakuError = ''">×</button>
      </div>
    </div>

    <ChatPanel
      v-if="!chatCollapsed"
      ref="chatPanel"
      @collapse="chatCollapsed = true"
      @login="openSettings('account')"
    />
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
  touch-action: manipulation;
}

.live.collapsed .video-zone {
  flex: 1;
  aspect-ratio: auto;
}

.video-zone:fullscreen {
  width: 100vw;
  height: 100dvh;
  aspect-ratio: auto;
  background: #000;
}

.video-b {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  background: #000;
}

.play-gate {
  position: absolute;
  left: 50%;
  top: 50%;
  transform: translate(-50%, -50%);
  z-index: 6;
  border: none;
  border-radius: 999px;
  padding: 12px 22px;
  background: rgba(0, 0, 0, 0.72);
  color: #fff;
  font-size: 14px;
  font-weight: 600;
  cursor: pointer;
  box-shadow: 0 6px 24px rgba(0, 0, 0, 0.5);
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
  display: flex;
  align-items: center;
  gap: 5px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.live-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: #777;
  flex: 0 0 auto;
}

.live-dot.open {
  background: #ff5a5f;
  box-shadow: 0 0 6px rgba(255, 90, 95, 0.8);
}

.live-dot.connecting,
.live-dot.reconnecting {
  background: var(--accent2);
  animation: blink 1s infinite;
}

.live-dot.failed {
  background: var(--danger);
}

.actions {
  display: flex;
  align-items: center;
  gap: 5px;
}

.ib {
  width: 32px;
  height: 32px;
  flex: 0 0 auto;
  border: none;
  border-radius: 50%;
  background: rgba(255, 255, 255, 0.14);
  color: #fff;
  font-size: 14px;
  cursor: pointer;
  display: grid;
  place-items: center;
}

.ib.on {
  background: rgba(91, 140, 255, 0.55);
}

.ib:disabled {
  opacity: 0.5;
}

.pill {
  height: 32px;
  border: none;
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.14);
  color: #fff;
  padding: 0 11px;
  font-size: 12px;
  cursor: pointer;
  white-space: nowrap;
}

.pill.on {
  background: rgba(91, 140, 255, 0.55);
}

.pill.big {
  width: 42px;
  padding: 0;
  font-size: 16px;
}

.quality {
  position: absolute;
  top: 48px;
  right: 10px;
  background: rgba(15, 20, 33, 0.96);
  border: 1px solid var(--line);
  border-radius: 12px;
  padding: 10px;
  display: flex;
  flex-direction: column;
  gap: 4px;
  z-index: 7;
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
  transform-origin: center center;
  transition: transform 0.08s linear;
}

.mini.expanded {
  inset: 0;
  width: 100% !important;
  height: 100% !important;
  border-radius: 0;
  border: none;
  cursor: default;
}

.mini.expanded video {
  transition: none;
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
  padding: 3px 5px;
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
  gap: 3px;
}

.mini-btns button {
  width: 23px;
  height: 23px;
  border: none;
  border-radius: 50%;
  background: rgba(0, 0, 0, 0.5);
  color: #fff;
  cursor: pointer;
  font-size: 11px;
  display: grid;
  place-items: center;
}

.mini-resize {
  position: absolute;
  right: 0;
  bottom: 0;
  width: 18px;
  height: 18px;
  cursor: nwse-resize;
  background: linear-gradient(135deg, transparent 50%, rgba(255, 255, 255, 0.5) 50%);
  touch-action: none;
}

/* 弹幕快捷条 */
.quickbar {
  position: absolute;
  left: calc(12px + var(--safe-left));
  right: calc(12px + var(--safe-right));
  bottom: calc(14px + var(--safe-bottom));
  z-index: 9;
  background: rgba(22, 29, 46, 0.97);
  border: 1px solid var(--line);
  border-radius: 16px;
  padding: 12px 13px;
  display: flex;
  flex-direction: column;
  gap: 7px;
  box-shadow: 0 10px 30px rgba(0, 0, 0, 0.55);
  animation: fadeUp 0.18s ease;
  max-width: 560px;
  margin: 0 auto;
}

.quick-head {
  display: flex;
  align-items: center;
  gap: 8px;
}

.quick-head strong {
  flex: 1;
  font-size: 13px;
  color: #9db7ff;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.quick-x {
  border: none;
  background: var(--surface2);
  color: var(--text-dim);
  width: 24px;
  height: 24px;
  border-radius: 50%;
  cursor: pointer;
}

.quick-text {
  margin: 0;
  font-size: 13px;
  line-height: 1.5;
  color: var(--text);
  max-height: 3em;
  overflow: hidden;
}

.quickbar input {
  background: var(--bg2);
  border: 1px solid var(--line);
  border-radius: 999px;
  padding: 9px 13px;
  color: var(--text);
  font-size: 14px;
  outline: none;
}

.quick-preview {
  margin: 0;
  font-size: 11px;
  color: var(--accent2);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.quick-hint {
  margin: 0;
  font-size: 11px;
  color: var(--danger);
}

.quick-actions {
  display: flex;
  align-items: center;
  gap: 7px;
}

.quick-count {
  flex: 1;
  font-size: 10px;
  color: var(--text-dim);
}

.qbtn {
  border: none;
  border-radius: 999px;
  background: var(--surface2);
  color: var(--text);
  padding: 7px 14px;
  font-size: 13px;
  cursor: pointer;
}

.qbtn.plus {
  background: rgba(255, 184, 77, 0.18);
  color: var(--accent2);
  font-weight: 600;
}

.qbtn.dim {
  background: transparent;
  color: var(--text-dim);
}

.qbtn:disabled {
  opacity: 0.5;
}

/* 错误提示 */
.err-banner {
  position: absolute;
  left: 10px;
  right: 10px;
  bottom: 58px;
  z-index: 8;
  display: flex;
  align-items: center;
  gap: 8px;
  background: rgba(255, 90, 95, 0.92);
  color: #fff;
  border-radius: 10px;
  padding: 8px 10px;
  font-size: 12px;
}

.err-banner span {
  flex: 1;
  min-width: 0;
}

.err-banner button {
  border: none;
  background: rgba(0, 0, 0, 0.22);
  color: #fff;
  border-radius: 999px;
  padding: 4px 9px;
  font-size: 11px;
  cursor: pointer;
  white-space: nowrap;
}

.err-x {
  width: 22px;
  height: 22px;
  padding: 0 !important;
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

@keyframes blink {
  50% {
    opacity: 0.3;
  }
}
</style>