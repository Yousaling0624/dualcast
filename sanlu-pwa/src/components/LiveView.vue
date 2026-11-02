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
  toggleSc,
  viewerLabel,
} from '../lib/store'
import type { ChatMsg } from '../lib/danmaku'
import DanmakuCanvas from './DanmakuCanvas.vue'
import ChatPanel from './ChatPanel.vue'
import SettingsDrawer from './SettingsDrawer.vue'
import AppIcon from './AppIcon.vue'
import SuperChatOverlay from './SuperChatOverlay.vue'

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
const miniPos = ref({ x: 10, y: 62 })
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
  return slot.qn ? slot.acceptQn.find((x) => x.qn === slot.qn)?.desc || `${slot.qn}` : '画质'
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
    if (quickTarget.value) return
    controls.value = false
    qualityOpen.value = false
  }, 4600)
}

function onZoneTap(e: PointerEvent) {
  const t = e.target as HTMLElement
  if (t.closest('button, .mini, .quality, .quickbar, input, .topbar, .bottombar')) return
  const el = zoneEl.value
  if (el && dmCanvas.value) {
    const rect = el.getBoundingClientRect()
    const hit = dmCanvas.value.hitTest(e.clientX - rect.left, e.clientY - rect.top)
    if (hit) {
      openQuick(hit)
      return
    }
  }
  if (quickTarget.value) {
    closeQuick()
    return
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
    reveal()
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

function chooseQuality(qn: number) {
  setQuality('b', qn)
  qualityOpen.value = false
  reveal()
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
  if (m.kind !== 'danmu') return
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
  reveal()
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
  } else if (e.key === 's' || e.key === 'S') {
    toggleSc()
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

watch(
  () => store.settings.sc.enabled,
  (on) => {
    if (on) reveal()
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
    <div
      ref="zoneEl"
      class="video-zone"
      :class="{ 'mini-open': miniOn && !miniExpanded }"
      @pointerdown="onZoneTap"
      @mousemove="onMouseMove"
    >
      <video ref="videoBEl" class="video-b" playsinline :style="{ objectFit: videoFit }"></video>

      <DanmakuCanvas ref="dmCanvas" />

      <SuperChatOverlay />

      <!-- 浏览器拦截自动播放时，用一次点击解锁声音 -->
      <button
        v-if="store.a.blocked || store.b.blocked"
        class="play-gate"
        @pointerdown.stop
        @click.stop="resumePlayback"
      >
        <span class="gate-icon"><AppIcon name="play" :size="22" /></span>
        <span>
          <strong>点此开始播放</strong>
          <small>浏览器需要一次点击来解锁声音</small>
        </span>
      </button>

      <!-- 顶栏 -->
      <Transition name="fade">
        <div v-if="controls" class="topbar">
          <button class="ib back" title="返回" @click="goBack">
            <AppIcon name="arrow-left" :size="19" />
          </button>
          <div class="titles">
            <strong>{{ store.b.title || '直播' }}</strong>
            <small>
              <span class="live-dot" :class="store.danmakuStatus"></span>
              {{ statusLabel }}
            </small>
          </div>
          <div class="actions">
            <button class="ib" title="重连" :disabled="store.reconnecting" @click="reconnect">
              <AppIcon name="refresh" :size="18" :class="{ spin: store.reconnecting }" />
            </button>
            <button class="ib" :class="{ on: miniOn }" title="A 解说小窗" @click="toggleMini()">
              <AppIcon name="pip" :size="18" />
            </button>
            <button class="qn-pill" title="画质" @click="qualityOpen = !qualityOpen">
              {{ qnLabel }}
              <AppIcon name="chevron-down" :size="13" />
            </button>
            <button
              class="ib"
              :title="isFullscreen ? '退出全屏' : '全屏'"
              @click="toggleFullscreen"
            >
              <AppIcon :name="isFullscreen ? 'fullscreen-exit' : 'fullscreen'" :size="19" />
            </button>
          </div>
        </div>
      </Transition>

      <!-- 画质菜单 -->
      <Transition name="fade">
        <div v-if="controls && qualityOpen" class="quality" @pointerdown.stop>
          <div class="quality-head">
            <span>画质</span>
            <small>B 路画面</small>
          </div>
          <button
            v-for="q in store.b.acceptQn"
            :key="q.qn"
            :class="{ on: q.qn === store.b.qn }"
            :disabled="store.qualitySwitching"
            @click="chooseQuality(q.qn)"
          >
            <span>{{ q.desc }}</span>
            <AppIcon v-if="q.qn === store.b.qn" name="check" :size="15" />
          </button>
          <p v-if="!store.b.acceptQn.length">开播后可选</p>
        </div>
      </Transition>

      <!-- 底部控制条 -->
      <Transition name="up">
        <div v-if="controls" class="bottombar">
          <button class="dock-play" :title="store.playing ? '暂停' : '播放'" @click="togglePlay">
            <AppIcon :name="store.playing ? 'pause' : 'play'" :size="20" />
          </button>
          <span class="dock-sep"></span>
          <button class="dock-btn" :disabled="store.swapping" @click="swap">
            <AppIcon name="swap" :size="17" />
            <span>{{ store.swapping ? '切换中' : '切画面' }}</span>
          </button>
          <button
            class="dock-btn"
            :class="{ on: store.settings.danmaku.enabled }"
            title="显示 / 隐藏弹幕"
            @click="store.settings.danmaku.enabled = !store.settings.danmaku.enabled"
          >
            <AppIcon name="danmaku" :size="17" />
            <span>弹幕</span>
          </button>
          <button
            class="dock-btn"
            :class="{ on: store.settings.sc.enabled }"
            title="SC 悬浮窗"
            @click="toggleSc"
          >
            <AppIcon name="sc" :size="17" />
            <span>SC</span>
          </button>
          <button class="dock-btn" title="混音与延迟" @click="openSettings('mix')">
            <AppIcon name="tune" :size="17" />
            <span>混音</span>
          </button>
          <button class="dock-btn" title="画面设置" @click="openSettings('video')">
            <AppIcon name="quality" :size="17" />
            <span>画面</span>
          </button>
          <button class="dock-btn icon-only" title="画中画" @click="enterPip">
            <AppIcon name="pip" :size="17" />
          </button>
          <button class="dock-btn icon-only" title="设置" @click="openSettings('danmaku')">
            <AppIcon name="settings" :size="18" />
          </button>
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
          <span class="mini-tag"><i></i>A · 解说</span>
          <span class="mini-btns">
            <button
              title="重置缩放"
              @pointerdown.stop
              @click.stop="miniZoom = 1; miniPan = { x: 0, y: 0 }"
            >
              {{ miniZoom > 1 ? '1x' : '↺' }}
            </button>
            <button
              :title="miniExpanded ? '还原小窗' : '小窗全屏'"
              @pointerdown.stop
              @click.stop="toggleMiniExpand"
            >
              <AppIcon :name="miniExpanded ? 'fullscreen-exit' : 'fullscreen'" :size="13" />
            </button>
            <button title="关闭小窗" @pointerdown.stop @click.stop="toggleMini(false)">
              <AppIcon name="close" :size="13" />
            </button>
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
          <span class="quick-avatar">{{ (quickTarget.uname || '匿').slice(0, 1) }}</span>
          <div class="quick-who">
            <strong>{{ quickTarget.uname || '匿名' }}</strong>
            <small v-if="quickTarget.medal">{{ quickTarget.medal }}</small>
          </div>
          <button class="quick-x" @click="closeQuick">
            <AppIcon name="close" :size="16" />
          </button>
        </div>
        <p class="quick-text">{{ quickTarget.text }}</p>

        <template v-if="!quickReply">
          <p v-if="quickHint" class="quick-hint">{{ quickHint }}</p>
          <p v-else-if="store.sendState.error" class="quick-hint">{{ store.sendState.error }}</p>
          <div class="quick-actions">
            <template v-if="store.loggedIn">
              <button v-if="canInteract(quickTarget)" class="qbtn plus" @click="doQuickPlusOne">
                <AppIcon name="plus" :size="15" />
                +1
              </button>
              <button v-if="canInteract(quickTarget)" class="qbtn" @click="doQuickAnswer">
                <AppIcon name="reply" :size="15" />
                回复
              </button>
            </template>
            <button v-else class="qbtn plus" @click="openSettings('account')">去登录</button>
            <button class="qbtn dim" @click="closeQuick">关闭</button>
          </div>
        </template>

        <template v-else>
          <div class="quick-input-row">
            <input
              ref="quickInput"
              v-model="quickText"
              :maxlength="quickLimit"
              :placeholder="`回复 @${quickTarget.uname || '匿名'}`"
              @keydown.enter="(e) => !e.isComposing && doQuickSend()"
            />
            <button
              class="qbtn send-mini"
              :disabled="!quickText.trim() || store.sendState.sending"
              @click="doQuickSend"
            >
              <AppIcon name="send" :size="16" />
            </button>
          </div>
          <p v-if="quickPreview" class="quick-preview">将发送：{{ quickPreview }}</p>
          <p v-if="quickHint" class="quick-hint">{{ quickHint }}</p>
          <p v-else-if="store.sendState.error" class="quick-hint">{{ store.sendState.error }}</p>
          <div class="quick-actions">
            <span class="quick-count">{{ quickText.length }}/{{ quickLimit }}</span>
            <button class="qbtn dim" @click="quickReply = false; quickText = ''">取消</button>
            <button
              class="qbtn plus"
              :disabled="!quickText.trim() || store.sendState.sending"
              @click="doQuickSend"
            >
              {{ store.sendState.sending ? '发送中…' : '发送' }}
            </button>
          </div>
        </template>
      </div>

      <!-- 错误提示 -->
      <div v-if="firstError && !quickTarget" class="err-banner" @pointerdown.stop>
        <AppIcon name="warning" :size="16" />
        <span>{{ firstError }}</span>
        <button v-if="store.danmakuStatus === 'failed'" @click="retryDanmaku">重试弹幕</button>
        <button v-else @click="reconnect">重连</button>
        <button
          class="err-x"
          title="关闭"
          @click="store.a.error = ''; store.b.error = ''; store.startError = ''; store.danmakuError = ''"
        >
          <AppIcon name="close" :size="14" />
        </button>
      </div>
    </div>

    <ChatPanel
      v-if="!chatCollapsed"
      ref="chatPanel"
      @collapse="chatCollapsed = true"
      @login="openSettings('account')"
    />
    <div v-else class="chat-fab-row">
      <button class="chat-fab" @click="chatCollapsed = false">
        <AppIcon name="chat" :size="17" />
        聊天室
      </button>
    </div>

    <SettingsDrawer ref="drawer" />
  </div>
</template>

<style scoped>
.live {
  display: flex;
  flex-direction: column;
  height: 100dvh;
  background: #0b0a09;
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
  z-index: 10;
  display: flex;
  align-items: center;
  gap: 10px;
  border: 1px solid rgba(255, 255, 255, 0.18);
  border-radius: 18px;
  padding: 10px 18px 10px 10px;
  background: rgba(20, 18, 15, 0.78);
  color: #fff8ef;
  cursor: pointer;
  box-shadow: 0 18px 48px rgba(0, 0, 0, 0.5);
  backdrop-filter: blur(18px);
  animation: popIn 0.3s ease;
}

.gate-icon {
  width: 40px;
  height: 40px;
  border-radius: 50%;
  display: grid;
  place-items: center;
  background: var(--accent);
  color: #fff;
  flex: 0 0 auto;
}

.play-gate span:last-child {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 2px;
}

.play-gate strong {
  font-size: 13.5px;
  font-weight: 650;
}

.play-gate small {
  font-size: 10.5px;
  color: rgba(255, 248, 239, 0.6);
}

/* ---------- 顶栏 ---------- */

.topbar {
  position: absolute;
  top: calc(8px + var(--safe-top));
  left: calc(8px + var(--safe-left));
  right: calc(8px + var(--safe-right));
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 7px;
  border-radius: 18px;
  background: linear-gradient(180deg, rgba(30, 27, 23, 0.74), rgba(18, 16, 14, 0.62));
  border: 1px solid rgba(255, 255, 255, 0.1);
  box-shadow: 0 12px 34px rgba(0, 0, 0, 0.28);
  backdrop-filter: blur(20px) saturate(1.2);
  z-index: 5;
}

.titles {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 1px;
}

.titles strong {
  font-size: 13.5px;
  font-weight: 600;
  color: #fff8ef;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.titles small {
  font-size: 10px;
  color: rgba(255, 248, 239, 0.55);
  display: flex;
  align-items: center;
  gap: 5px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.live-dot {
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background: #7d766b;
  flex: 0 0 auto;
}

.live-dot.open {
  background: #ff7a64;
  box-shadow: 0 0 7px rgba(255, 122, 100, 0.8);
}

.live-dot.connecting,
.live-dot.reconnecting {
  background: var(--accent);
  animation: blink 1s infinite;
}

.live-dot.failed {
  background: #ff5a5f;
}

.actions {
  display: flex;
  align-items: center;
  gap: 4px;
  flex: 0 0 auto;
}

.ib {
  width: 32px;
  height: 32px;
  flex: 0 0 auto;
  border: none;
  border-radius: 50%;
  background: rgba(255, 255, 255, 0.09);
  color: #fff8ef;
  cursor: pointer;
  display: grid;
  place-items: center;
  transition:
    background 0.15s ease,
    transform 0.15s ease;
}

.ib:hover {
  background: rgba(255, 255, 255, 0.16);
}

.ib:active {
  transform: scale(0.92);
}

.ib.on {
  background: var(--accent);
  color: #fff;
}

.ib:disabled {
  opacity: 0.45;
}

.ib.back {
  background: transparent;
}

.qn-pill {
  height: 30px;
  display: inline-flex;
  align-items: center;
  gap: 3px;
  border: none;
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.09);
  color: #fff8ef;
  padding: 0 9px;
  font-size: 11px;
  cursor: pointer;
  white-space: nowrap;
}

.spin {
  animation: spin 0.9s linear infinite;
}

/* 画质菜单 */
.quality {
  position: absolute;
  top: calc(56px + var(--safe-top));
  right: calc(10px + var(--safe-right));
  background: rgba(28, 25, 22, 0.94);
  border: 1px solid rgba(255, 255, 255, 0.13);
  border-radius: 16px;
  padding: 7px;
  display: flex;
  flex-direction: column;
  gap: 2px;
  z-index: 7;
  min-width: 148px;
  box-shadow: 0 16px 44px rgba(0, 0, 0, 0.45);
  backdrop-filter: blur(22px);
  animation: popIn 0.18s ease;
}

.quality-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  padding: 5px 8px 7px;
  border-bottom: 1px solid rgba(255, 255, 255, 0.08);
  margin-bottom: 3px;
}

.quality-head span {
  font-size: 12px;
  font-weight: 600;
  color: #fff8ef;
}

.quality-head small {
  font-size: 9.5px;
  color: rgba(255, 248, 239, 0.45);
}

.quality button {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  border: none;
  background: transparent;
  color: rgba(255, 248, 239, 0.76);
  text-align: left;
  padding: 7px 8px;
  border-radius: 9px;
  cursor: pointer;
  font-size: 12px;
}

.quality button:hover {
  background: rgba(255, 255, 255, 0.08);
}

.quality button.on {
  background: var(--accent);
  color: #fff;
  font-weight: 600;
}

.quality button:disabled {
  opacity: 0.5;
}

.quality p {
  margin: 4px 8px 6px;
  font-size: 11px;
  color: rgba(255, 248, 239, 0.45);
}

/* ---------- 底部控制条 ---------- */

.bottombar {
  position: absolute;
  left: calc(8px + var(--safe-left));
  right: calc(8px + var(--safe-right));
  bottom: calc(8px + var(--safe-bottom));
  display: flex;
  align-items: center;
  gap: 3px;
  padding: 5px 7px;
  border-radius: 20px;
  background: linear-gradient(180deg, rgba(30, 27, 23, 0.78), rgba(16, 14, 12, 0.72));
  border: 1px solid rgba(255, 255, 255, 0.1);
  box-shadow: 0 14px 38px rgba(0, 0, 0, 0.36);
  backdrop-filter: blur(22px) saturate(1.15);
  z-index: 5;
  overflow-x: auto;
  scrollbar-width: none;
}

.bottombar::-webkit-scrollbar {
  display: none;
}

.dock-play {
  width: 40px;
  height: 40px;
  flex: 0 0 auto;
  border: none;
  border-radius: 50%;
  background: var(--accent);
  color: #fff;
  display: grid;
  place-items: center;
  cursor: pointer;
  box-shadow: 0 6px 16px rgba(217, 119, 87, 0.35);
}

.dock-play:active {
  transform: scale(0.93);
}

.dock-sep {
  width: 1px;
  height: 20px;
  background: rgba(255, 255, 255, 0.13);
  margin: 0 3px;
  flex: 0 0 auto;
}

.dock-btn {
  height: 36px;
  flex: 0 0 auto;
  display: inline-flex;
  align-items: center;
  gap: 5px;
  border: none;
  border-radius: 12px;
  background: transparent;
  color: rgba(255, 248, 239, 0.62);
  padding: 0 9px;
  font-size: 11px;
  cursor: pointer;
  transition:
    background 0.15s ease,
    color 0.15s ease;
}

.dock-btn:hover {
  background: rgba(255, 255, 255, 0.08);
  color: #fff8ef;
}

.dock-btn.on {
  background: rgba(217, 119, 87, 0.2);
  color: #f2b49e;
}

.dock-btn.icon-only {
  width: 34px;
  padding: 0;
  justify-content: center;
}

.dock-btn:disabled {
  opacity: 0.45;
}

/* ---------- A 小窗 ---------- */

.mini {
  position: absolute;
  top: 62px;
  right: 10px;
  background: #000;
  border: 1px solid rgba(255, 255, 255, 0.24);
  border-radius: 16px;
  overflow: hidden;
  z-index: 6;
  box-shadow: 0 16px 40px rgba(0, 0, 0, 0.55);
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
  z-index: 11;
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
  padding: 4px 5px;
  background: linear-gradient(rgba(0, 0, 0, 0.55), transparent);
}

.mini-tag {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: 9px;
  font-weight: 600;
  color: #fff;
  background: rgba(0, 0, 0, 0.38);
  border-radius: 999px;
  padding: 2px 7px;
  backdrop-filter: blur(6px);
}

.mini-tag i {
  width: 4px;
  height: 4px;
  border-radius: 50%;
  background: var(--accent);
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
  background: rgba(0, 0, 0, 0.45);
  color: #fff;
  cursor: pointer;
  font-size: 10px;
  display: grid;
  place-items: center;
  backdrop-filter: blur(6px);
}

.mini-resize {
  position: absolute;
  right: 0;
  bottom: 0;
  width: 18px;
  height: 18px;
  cursor: nwse-resize;
  background: linear-gradient(135deg, transparent 50%, rgba(255, 255, 255, 0.45) 50%);
  touch-action: none;
}

/* SC 悬浮窗和 A 小窗并排时让出空间 */
.video-zone.mini-open :deep(.sc-overlay) {
  width: min(380px, calc(100% - 280px));
}

/* ---------- 弹幕快捷条 ---------- */

.quickbar {
  position: absolute;
  left: 50%;
  transform: translateX(-50%);
  width: min(520px, calc(100% - 24px));
  bottom: calc(66px + var(--safe-bottom));
  z-index: 9;
  background: rgba(255, 251, 244, 0.98);
  border: 1px solid rgba(255, 255, 255, 0.7);
  border-radius: 22px;
  padding: 13px 14px;
  display: flex;
  flex-direction: column;
  gap: 8px;
  box-shadow: 0 22px 60px rgba(0, 0, 0, 0.42);
  animation: fadeUp 0.2s ease;
  color: var(--text);
  backdrop-filter: blur(20px);
}

.quick-head {
  display: flex;
  align-items: center;
  gap: 9px;
}

.quick-avatar {
  width: 30px;
  height: 30px;
  border-radius: 50%;
  display: grid;
  place-items: center;
  color: #fff;
  font-size: 12px;
  font-weight: 650;
  background: linear-gradient(135deg, var(--accent), var(--accent-2));
  flex: 0 0 auto;
}

.quick-who {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 1px;
}

.quick-who strong {
  font-size: 12.5px;
  color: var(--accent-strong);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.quick-who small {
  font-size: 9.5px;
  color: var(--text-faint);
}

.quick-x {
  border: none;
  background: var(--surface-2);
  color: var(--text-dim);
  width: 27px;
  height: 27px;
  border-radius: 50%;
  cursor: pointer;
  display: grid;
  place-items: center;
  flex: 0 0 auto;
}

.quick-text {
  margin: 0;
  font-size: 13px;
  line-height: 1.5;
  color: var(--text);
  max-height: 3em;
  overflow: hidden;
}

.quick-input-row {
  display: flex;
  align-items: center;
  gap: 7px;
}

.quickbar input {
  flex: 1;
  min-width: 0;
  background: var(--surface-2);
  border: 1px solid transparent;
  border-radius: 999px;
  padding: 9px 13px;
  color: var(--text);
  font-size: 13.5px;
  outline: none;
}

.quickbar input:focus {
  background: var(--surface);
  border-color: rgba(217, 119, 87, 0.5);
  box-shadow: 0 0 0 3px var(--accent-soft);
}

.quick-preview {
  margin: 0;
  font-size: 10.5px;
  color: var(--gold);
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
  color: var(--text-faint);
}

.qbtn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 4px;
  border: none;
  border-radius: 999px;
  background: var(--surface-2);
  color: var(--text);
  padding: 8px 15px;
  font-size: 12.5px;
  font-weight: 550;
  cursor: pointer;
}

.qbtn.plus {
  background: var(--accent);
  color: var(--on-accent);
  box-shadow: 0 6px 16px rgba(217, 119, 87, 0.22);
}

.qbtn.dim {
  background: transparent;
  color: var(--text-dim);
}

.qbtn.send-mini {
  width: 38px;
  height: 38px;
  padding: 0;
  background: var(--accent);
  color: #fff;
  flex: 0 0 auto;
}

.qbtn:disabled {
  opacity: 0.45;
}

/* ---------- 错误提示 ---------- */

.err-banner {
  position: absolute;
  left: 50%;
  transform: translateX(-50%);
  width: min(560px, calc(100% - 20px));
  bottom: calc(64px + var(--safe-bottom));
  z-index: 8;
  display: flex;
  align-items: center;
  gap: 8px;
  background: rgba(88, 38, 33, 0.92);
  border: 1px solid rgba(255, 158, 140, 0.3);
  color: #ffe9e2;
  border-radius: 16px;
  padding: 9px 11px;
  font-size: 11.5px;
  backdrop-filter: blur(16px);
  box-shadow: 0 14px 36px rgba(0, 0, 0, 0.35);
}

.err-banner span {
  flex: 1;
  min-width: 0;
}

.err-banner button {
  border: none;
  background: rgba(255, 255, 255, 0.12);
  color: #fff;
  border-radius: 999px;
  padding: 5px 10px;
  font-size: 10.5px;
  cursor: pointer;
  white-space: nowrap;
}

.err-x {
  width: 26px;
  height: 26px;
  padding: 0 !important;
  display: grid;
  place-items: center;
}

/* ---------- 聊天室收起后的浮动按钮 ---------- */

.chat-fab-row {
  position: absolute;
  right: 14px;
  bottom: calc(65px + var(--safe-bottom));
  z-index: 8;
}

.chat-fab {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  border: 1px solid rgba(255, 255, 255, 0.13);
  background: rgba(33, 29, 25, 0.9);
  color: #fff8ef;
  border-radius: 999px;
  padding: 10px 15px;
  font-size: 12px;
  cursor: pointer;
  box-shadow: 0 12px 30px rgba(0, 0, 0, 0.4);
  backdrop-filter: blur(16px);
}

/* ---------- 横屏手机 / 平板：左右分栏 ---------- */

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

  .video-zone.mini-open :deep(.sc-overlay) {
    width: min(340px, 34vw);
  }
}

/* ---------- PC 大屏 ---------- */

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
    top: 12px;
    left: 14px;
    right: 14px;
  }

  .titles strong {
    font-size: 14.5px;
  }

  .bottombar {
    left: 50%;
    right: auto;
    transform: translateX(-50%);
    width: max-content;
    max-width: calc(100% - 40px);
    bottom: 16px;
    padding: 6px 9px;
  }

  .video-zone.mini-open :deep(.sc-overlay) {
    width: min(380px, calc(100% - 420px));
  }
}

/* ---------- 过渡 ---------- */

.fade-enter-active,
.fade-leave-active {
  transition: opacity 0.22s ease;
}

.fade-enter-from,
.fade-leave-to {
  opacity: 0;
}

.up-enter-active,
.up-leave-active {
  transition:
    transform 0.24s cubic-bezier(0.2, 0.8, 0.2, 1),
    opacity 0.24s ease;
}

.up-enter-from,
.up-leave-to {
  transform: translateY(14px);
  opacity: 0;
}

@keyframes blink {
  50% {
    opacity: 0.3;
  }
}

@media (max-width: 600px) {
  .topbar {
    gap: 4px;
    padding: 5px;
    border-radius: 16px;
  }

  .actions {
    gap: 2px;
  }

  .ib {
    width: 30px;
    height: 30px;
  }

  .qn-pill {
    height: 28px;
    padding: 0 7px;
    font-size: 10px;
  }

  .bottombar {
    gap: 1px;
    padding: 4px 5px;
    border-radius: 17px;
  }

  .dock-btn {
    height: 34px;
    gap: 4px;
    padding: 0 7px;
    font-size: 10px;
  }

  .dock-btn.icon-only {
    width: 31px;
  }

  .dock-play {
    width: 37px;
    height: 37px;
  }

  .video-zone.mini-open :deep(.sc-overlay) {
    width: min(240px, calc(100% - 150px));
  }
}

@media (max-height: 420px) and (orientation: landscape) {
  .titles small {
    display: none;
  }

  .topbar {
    padding: 4px;
  }
}
</style>
