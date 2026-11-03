<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import {
  addSource,
  attachStreams,
  bindVideos,
  blockUser,
  debugSnapshot,
  canInteract,
  clearSendError,
  exitSession,
  leavePlayer,
  plusOne,
  reconnect,
  removeSource,
  replyInputLimit,
  resetOffset,
  setOffset,
  resumePlayback,
  retryDanmaku,
  send,
  setCommentary,
  setQuality,
  store,
  swap,
  togglePlay,
  toggleSc,
  updateSource,
  viewerLabel,
} from '../lib/store'
import type { ChatMsg } from '../lib/danmaku'
import type { ExtraRoom } from '../lib/store'
import { badgePreviewText } from '../lib/sources'
import {
  cropThroughView,
  cropTransform,
  defaultMiniWidth,
  fitAspectBox,
  isUsableCrop,
  normalizeCrop,
  proportionalResize,
  type CropRect,
  type MiniBox,
  type MiniCorner,
} from '../lib/layout'
import DanmakuCanvas from './DanmakuCanvas.vue'
import ChatPanel from './ChatPanel.vue'
import SettingsDrawer from './SettingsDrawer.vue'
import AppIcon from './AppIcon.vue'
import MultiWall from './MultiWall.vue'
import SuperChatOverlay from './SuperChatOverlay.vue'

const zoneEl = ref<HTMLElement | null>(null)
const videoAEl = ref<HTMLVideoElement | null>(null)
const videoBEl = ref<HTMLVideoElement | null>(null)
const dmCanvas = ref<InstanceType<typeof DanmakuCanvas> | null>(null)
const chatPanel = ref<InstanceType<typeof ChatPanel> | null>(null)
const drawer = ref<InstanceType<typeof SettingsDrawer> | null>(null)
const quickInput = ref<HTMLInputElement | null>(null)

const controls = ref(true)
/** 底栏弹幕输入框是否聚焦：聚焦时不让控件自动隐藏 */
const dockFocused = ref(false)
let hideTimer: number | null = null
const chatCollapsed = ref(false)
const isFullscreen = ref(false)
let collapsedBeforeFullscreen = false

/* A 解说小窗 */
const miniOn = ref(store.commentaryOn)
const miniExpanded = ref(false)
/** 点一下画面隐藏小窗按钮，像 B 站移动端一样只留干净画面 */
const miniImmersive = ref(false)
const miniImmHint = ref(false)
const miniPos = ref({ x: 16, y: 70 })
const miniW = ref(300)
const miniZoom = ref(1)
const miniPan = ref({ x: 0, y: 0 })
const miniH = computed(() => (miniW.value * 9) / 16)
/** 画面区域尺寸和安全区，用来算小窗「全屏」的盒子 */
const zoneSize = ref({ width: 0, height: 0 })
const safeInset = ref({ left: 0, right: 0, top: 0, bottom: 0 })
const expandedBox = computed(() =>
  fitAspectBox(zoneSize.value, undefined, 14, safeInset.value)
)
const expandedStyle = computed(() => {
  const b = expandedBox.value
  return {
    left: `${b.left}px`,
    top: `${b.top}px`,
    width: `${b.width}px`,
    height: `${b.height}px`,
    right: 'auto',
    bottom: 'auto',
  }
})
let miniTapTimer: number | null = null
let miniHintTimer: number | null = null
let miniTapCandidate = false
let miniTapStart = { x: 0, y: 0 }

/* 小窗框选裁切 */
const miniCrop = ref<CropRect | null>(null)
const cropMode = ref(false)
const cropDraft = ref<CropRect | null>(null)
let cropStart = { x: 0, y: 0 }
let cropping = false
const miniCropStyle = computed(() => cropTransform(miniCrop.value))

const pointers = new Map<number, { x: number; y: number }>()
let dragging = false
let dragStart = { x: 0, y: 0, px: 0, py: 0, wx: 0, wy: 0 }
let pinchDist = 0
let pinchZoom = 1
let pinchMid = { x: 0, y: 0 }
let pinchPan = { x: 0, y: 0 }
let resizing = false
let zoneObserver: ResizeObserver | null = null
/** 裁切模式里的双指缩放/拖动 */
const cropPointers = new Map<number, { x: number; y: number }>()
let resizeStart: {
  corner: MiniCorner
  box: MiniBox
  bounds: { width: number; height: number }
  startX: number
  startY: number
} | null = null

interface DmHit {
  msg: ChatMsg
  x: number
  y: number
  w: number
  h: number
  bottom: number
}

interface QuickAnchor {
  left: number
  top: number
  below: boolean
  /** 画面上下空间有限，让快捷条内部滚动而不是被裁掉 */
  maxHeight: number
}

/* 点 / 悬停画面上的弹幕，浮出 +1 / 回复 */
const quickTarget = ref<ChatMsg | null>(null)
const quickAnchor = ref<QuickAnchor | null>(null)
const quickMode = ref<'click' | 'hover'>('click')
const quickReply = ref(false)
const quickText = ref('')
const quickHint = ref('')

let hoverOpenTimer: number | null = null
let hoverCloseTimer: number | null = null
let hoverCandidate = { seq: 0, x: 0, y: 0 }

const videoFit = computed(() => {
  if (store.settings.video.scale === 'crop') return 'cover'
  if (store.settings.video.scale === 'fill') return 'fill'
  return 'contain'
})

const statusLabel = computed(() => {
  const s = store.danmakuStatus
  const extra = store.sources.length ? ` + ${store.sources.length} 路` : ''
  const ab = store.settings.danmaku.includeB && store.b.roomId ? ' · A+B' : ''
  if (s === 'open') {
    return viewerLabel() ? `A 弹幕 · ${viewerLabel()}${extra}${ab}` : `A 弹幕已连接${extra}${ab}`
  }
  if (s === 'reconnecting') return `A 弹幕重连中…${extra}${ab}`
  if (s === 'connecting') return `A 弹幕连接中…${extra}${ab}`
  if (s === 'failed') return `A 弹幕连接失败${extra}${ab}`
  return `A 弹幕未连接${extra}${ab}`
})

const quickLimit = computed(() => (quickTarget.value ? replyInputLimit(quickTarget.value) : 20))

const firstError = computed(
  () =>
    store.startError ||
    store.a.error ||
    store.b.error ||
    store.danmakuError ||
    (store.settings.danmaku.includeB ? store.bDanmakuError : '')
)

function armHideTimer() {
  if (hideTimer) clearTimeout(hideTimer)
  hideTimer = window.setTimeout(() => {
    // 快捷回复 / 多路面板 / 更多菜单还开着，或正在底栏输入弹幕时不自动收，
    // 避免打字打一半输入框跟着控件一起消失
    if (quickTarget.value || multiOpen.value || moreOpen.value || dockFocused.value) return
    hideControls()
  }, 4600)
}

function reveal() {
  controls.value = true
  exitMiniImmersive()
  armHideTimer()
}

/** 底栏输入框聚焦期间锁住控件，键盘弹起 / 慢慢打字都不会把输入框收走 */
function onDockFocus() {
  dockFocused.value = true
  dockHint.value = ''
  if (hideTimer) {
    clearTimeout(hideTimer)
    hideTimer = null
  }
}

function onDockBlur() {
  dockFocused.value = false
  // 发送后输入框失焦，重新给控件计时；面板 / 快捷条开着时由定时器自己跳过
  if (controls.value) armHideTimer()
}

function hideControls() {
  // 输入框聚焦时一律不自动收控件，否则正在打的弹幕会跟着输入框一起消失
  if (dockFocused.value) return
  controls.value = false
  multiOpen.value = false
  moreOpen.value = false
  // 主画面控件和小窗按钮统一成一套沉浸状态：收就一起收
  if (miniOn.value && !miniExpanded.value) miniImmersive.value = true
  if (hideTimer) {
    clearTimeout(hideTimer)
    hideTimer = null
  }
}

/** B 站移动端式：控件开着就收掉；关着时点空白处唤出，点弹幕则冻结查看 */
function onZoneTap(e: PointerEvent) {
  const t = e.target as HTMLElement
  if (
    t.closest(
      'button, .mini, .quickbar, .multi-wall, input, .topbar, .bottombar, .crop-layer, .crop-actions'
    )
  )
    return
  // 延迟悬浮条开着时，点画面空白处先把它收起来
  if (offsetOpen.value) {
    closeOffsetBar()
    return
  }
  // 正在输入弹幕时点画面：先收起键盘，不立刻把输入框和控件一起收掉
  if (dockFocused.value) {
    ;(document.activeElement as HTMLElement | null)?.blur()
    return
  }
  if (quickTarget.value) {
    closeQuick()
    return
  }
  // 控件开着时第一下先收起来：弹幕密集时也不会点中弹幕而收不掉按钮
  if (controls.value) {
    hideControls()
    return
  }
  const el = zoneEl.value
  if (el && dmCanvas.value) {
    const rect = el.getBoundingClientRect()
    const hit = dmCanvas.value.freezeAt(e.clientX - rect.left, e.clientY - rect.top)
    if (hit) {
      openQuick(hit, 'click')
      return
    }
  }
  reveal()
}

function clearHoverOpen() {
  if (hoverOpenTimer) {
    clearTimeout(hoverOpenTimer)
    hoverOpenTimer = null
  }
  hoverCandidate.seq = 0
}

function cancelHoverClose() {
  if (hoverCloseTimer) {
    clearTimeout(hoverCloseTimer)
    hoverCloseTimer = null
  }
}

function scheduleHoverClose() {
  if (quickMode.value !== 'hover' || hoverCloseTimer) return
  hoverCloseTimer = window.setTimeout(() => {
    hoverCloseTimer = null
    closeQuick()
  }, 550)
}

function pointerInRect(e: PointerEvent, hit: DmHit | null): boolean {
  if (!hit) return false
  const zone = zoneEl.value
  if (!zone) return false
  const rect = zone.getBoundingClientRect()
  const x = e.clientX - rect.left
  const y = e.clientY - rect.top
  return x >= hit.x - 10 && x <= hit.x + hit.w + 10 && y >= hit.y - 8 && y <= hit.bottom + 8
}

/**
 * 电脑端鼠标悬停 320ms 触发：冻结这条弹幕并浮出快捷条。
 * 鼠标移开后 550ms 自动恢复飘动；触屏走点击逻辑。
 */
function onZoneMove(e: PointerEvent) {
  // 触屏只在点按时切换控件；鼠标移动仍然自动唤出，贴近桌面播放器习惯
  if (e.pointerType === 'mouse') reveal()
  // 只认鼠标指针，不再依赖 matchMedia('(hover: hover)')：
  // 触屏笔记本、后接鼠标的平板也能悬停；手指 / 手写笔仍然走点按
  if (e.pointerType !== 'mouse') return
  const t = e.target as HTMLElement
  if (t.closest('.quickbar')) {
    cancelHoverClose()
    return
  }
  if (t.closest('.topbar, .bottombar, .mini, .multi-wall, button, input')) return
  if (quickTarget.value) {
    const hit = dmCanvas.value?.rectFor(quickTarget.value.seq) || null
    if (pointerInRect(e, hit)) cancelHoverClose()
    else scheduleHoverClose()
    return
  }
  if (!store.settings.danmaku.enabled) return
  const el = zoneEl.value
  if (!el || !dmCanvas.value) return
  const rect = el.getBoundingClientRect()
  const x = e.clientX - rect.left
  const y = e.clientY - rect.top
  const hit = dmCanvas.value.hitRect(x, y)
  if (!hit) {
    clearHoverOpen()
    return
  }
  if (hoverCandidate.seq === hit.msg.seq) return
  clearHoverOpen()
  hoverCandidate = { seq: hit.msg.seq, x, y }
  hoverOpenTimer = window.setTimeout(() => {
    hoverOpenTimer = null
    if (quickTarget.value) return
    const frozen = dmCanvas.value?.freezeSeq(hoverCandidate.seq)
    if (frozen) openQuick(frozen, 'hover')
    hoverCandidate.seq = 0
  }, 320)
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
  // 回主页不打断播放：声音、弹幕、多路都留在后台，首页会出现「正在播放」条
  if (store.sessionActive) {
    leavePlayer()
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
  syncZoneSize()
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
}

function clearMiniTap() {
  if (miniTapTimer) {
    clearTimeout(miniTapTimer)
    miniTapTimer = null
  }
  miniTapCandidate = false
}

function exitMiniImmersive() {
  miniImmersive.value = false
  miniImmHint.value = false
  if (miniHintTimer) {
    clearTimeout(miniHintTimer)
    miniHintTimer = null
  }
}

function scheduleMiniTap() {
  // 小窗全屏时顶部按钮还要用来裁切 / 退出，不参与点按沉浸
  if (miniExpanded.value) return
  if (miniTapTimer) clearTimeout(miniTapTimer)
  // 先记住这一下要进入还是退出，避免 240ms 内自动隐藏先改了状态
  const target = !miniImmersive.value
  miniTapTimer = window.setTimeout(() => {
    miniTapTimer = null
    miniImmersive.value = target
    if (target) {
      // 小窗也进入沉浸时，把主画面控件一起收掉，避免两层按钮同时挡画面
      hideControls()
      miniImmHint.value = true
      if (miniHintTimer) clearTimeout(miniHintTimer)
      miniHintTimer = window.setTimeout(() => {
        miniHintTimer = null
        miniImmHint.value = false
      }, 1900)
    } else {
      reveal()
    }
  }, 240)
}

function toggleMini(on?: boolean) {
  const next = on ?? !miniOn.value
  miniOn.value = next
  setCommentary(next)
  if (!next) {
    miniExpanded.value = false
    exitMiniImmersive()
  }
  reveal()
}

function toggleMiniExpand() {
  miniExpanded.value = !miniExpanded.value
  exitMiniImmersive()
  // 两个方向进出都回到原始视图，避免把一套坐标系的缩放/平移带进另一套
  miniZoom.value = 1
  miniPan.value = { x: 0, y: 0 }
  if (!miniExpanded.value) {
    cropMode.value = false
    cropDraft.value = null
  }
  reveal()
}

/** 小窗当前显示区域的尺寸：平时是 16:9 小窗，「全屏」时是适配后的盒子 */
function currentMiniSize() {
  return miniExpanded.value ? expandedBox.value : { width: miniW.value, height: miniH.value }
}

/** 缩放后平移不能露出画面外：每个方向最多 (zoom - 1) × 尺寸 / 2 */
function clampPan(px: number, py: number, zoom = miniZoom.value) {
  const box = currentMiniSize()
  const maxX = ((zoom - 1) * box.width) / 2
  const maxY = ((zoom - 1) * box.height) / 2
  return {
    x: Math.max(-maxX, Math.min(maxX, px)),
    y: Math.max(-maxY, Math.min(maxY, py)),
  }
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
  miniTapCandidate = !cropMode.value
  miniTapStart = { x: e.clientX, y: e.clientY }
  const target = e.currentTarget as HTMLElement
  target.setPointerCapture(e.pointerId)
  pointers.set(e.pointerId, { x: e.clientX, y: e.clientY })
  if (pointers.size === 2) {
    const [a, b] = [...pointers.values()]
    pinchDist = Math.hypot(a.x - b.x, a.y - b.y) || 1
    pinchZoom = miniZoom.value
    pinchMid = { x: (a.x + b.x) / 2, y: (a.y + b.y) / 2 }
    pinchPan = { x: miniPan.value.x, y: miniPan.value.y }
    dragging = false
    miniTapCandidate = false
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
  if (miniExpanded.value) reveal()
}

function onMiniPointerMove(e: PointerEvent) {
  // 手指点按难免有几像素抖动，触屏放宽一些，避免点一下被当成拖动
  const slop = e.pointerType === 'mouse' ? 8 : 16
  if (
    miniTapCandidate &&
    Math.hypot(e.clientX - miniTapStart.x, e.clientY - miniTapStart.y) > slop
  ) {
    miniTapCandidate = false
  }
  if (resizing) return
  if (pointers.has(e.pointerId)) pointers.set(e.pointerId, { x: e.clientX, y: e.clientY })
  if (pointers.size === 2) {
    const [a, b] = [...pointers.values()]
    const dist = Math.hypot(a.x - b.x, a.y - b.y)
    const midX = (a.x + b.x) / 2
    const midY = (a.y + b.y) / 2
    const zoom = Math.max(1, Math.min(3, pinchZoom * (dist / pinchDist)))
    miniZoom.value = zoom
    // 双指整体移动 = 拖动画面，缩放/拖动在全屏和窗口里都能用
    miniPan.value = clampPan(
      pinchPan.x + midX - pinchMid.x,
      pinchPan.y + midY - pinchMid.y,
      zoom
    )
    return
  }
  if (!dragging) return
  if (miniExpanded.value || miniZoom.value > 1) {
    miniPan.value = clampPan(
      dragStart.px + e.clientX - dragStart.x,
      dragStart.py + e.clientY - dragStart.y
    )
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
  const wasTap = miniTapCandidate && pointers.size <= 1 && !resizing
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
    // 双指松开一根后，剩下那根还能接着拖
    dragging = true
  }
  if (wasTap && pointers.size === 0) scheduleMiniTap()
  miniTapCandidate = false
}

function onMiniDoubleClick() {
  clearMiniTap()
  miniZoom.value = miniZoom.value > 1 ? 1 : 2
  miniPan.value = { x: 0, y: 0 }
}

function onResizeDown(e: PointerEvent, corner: MiniCorner) {
  e.stopPropagation()
  e.preventDefault()
  const zone = zoneEl.value
  if (!zone) return
  ;(e.currentTarget as HTMLElement).setPointerCapture(e.pointerId)
  const rect = zone.getBoundingClientRect()
  resizing = true
  resizeStart = {
    corner,
    box: { right: miniPos.value.x, top: miniPos.value.y, width: miniW.value, height: miniH.value },
    bounds: { width: rect.width, height: rect.height },
    startX: e.clientX,
    startY: e.clientY,
  }
}

function onResizeMove(e: PointerEvent) {
  if (!resizing || !resizeStart) return
  const next = proportionalResize(
    resizeStart.box,
    resizeStart.corner,
    e.clientX - resizeStart.startX,
    e.clientY - resizeStart.startY,
    resizeStart.bounds
  )
  miniW.value = Math.round(next.width)
  miniPos.value = { x: Math.round(next.right), y: Math.round(next.top) }
  clampMiniPos()
}

function onResizeUp() {
  resizing = false
  resizeStart = null
}

/* ---------- 小窗框选裁切 ---------- */

function toggleCropMode() {
  cropMode.value = !cropMode.value
  if (cropMode.value) {
    exitMiniImmersive()
    // 从完整画面开始选；已有裁切只作为初始预览框，双指还能再缩放对准摄像头
    miniZoom.value = 1
    miniPan.value = { x: 0, y: 0 }
    cropDraft.value = miniCrop.value ? { ...miniCrop.value } : { x: 0, y: 0, w: 1, h: 1 }
  } else {
    cropDraft.value = null
  }
  reveal()
}

function onCropDown(e: PointerEvent) {
  const el = e.currentTarget as HTMLElement
  const rect = el.getBoundingClientRect()
  el.setPointerCapture(e.pointerId)
  cropPointers.set(e.pointerId, { x: e.clientX, y: e.clientY })
  if (cropPointers.size === 2) {
    // 两指：先缩放/拖动对准画面，抬手后再画框
    const [a, b] = [...cropPointers.values()]
    pinchDist = Math.hypot(a.x - b.x, a.y - b.y) || 1
    pinchZoom = miniZoom.value
    pinchMid = { x: (a.x + b.x) / 2, y: (a.y + b.y) / 2 }
    pinchPan = { x: miniPan.value.x, y: miniPan.value.y }
    cropping = false
    return
  }
  cropStart = {
    x: Math.max(0, Math.min(rect.width, e.clientX - rect.left)),
    y: Math.max(0, Math.min(rect.height, e.clientY - rect.top)),
  }
  cropping = true
  cropDraft.value = normalizeCrop(cropStart.x, cropStart.y, cropStart.x, cropStart.y, rect.width, rect.height)
}

function onCropMove(e: PointerEvent) {
  const el = e.currentTarget as HTMLElement
  if (cropPointers.has(e.pointerId)) {
    cropPointers.set(e.pointerId, { x: e.clientX, y: e.clientY })
  }
  if (cropPointers.size === 2) {
    const [a, b] = [...cropPointers.values()]
    const dist = Math.hypot(a.x - b.x, a.y - b.y)
    const midX = (a.x + b.x) / 2
    const midY = (a.y + b.y) / 2
    const zoom = Math.max(1, Math.min(3, pinchZoom * (dist / pinchDist)))
    miniZoom.value = zoom
    miniPan.value = clampPan(
      pinchPan.x + midX - pinchMid.x,
      pinchPan.y + midY - pinchMid.y,
      zoom
    )
    return
  }
  if (!cropping) return
  const rect = el.getBoundingClientRect()
  const x = Math.max(0, Math.min(rect.width, e.clientX - rect.left))
  const y = Math.max(0, Math.min(rect.height, e.clientY - rect.top))
  cropDraft.value = normalizeCrop(cropStart.x, cropStart.y, x, y, rect.width, rect.height)
}

function onCropUp(e: PointerEvent) {
  cropPointers.delete(e.pointerId)
  if (cropPointers.size > 0) {
    // 双指变单指：不接着画框，等手指都抬起再重新框
    cropping = false
    return
  }
  cropping = false
  if (!isUsableCrop(cropDraft.value)) {
    cropDraft.value = miniCrop.value ? { ...miniCrop.value } : { x: 0, y: 0, w: 1, h: 1 }
  }
}

function applyCrop() {
  const draft = cropDraft.value
  if (isUsableCrop(draft)) {
    // 框是在当前缩放/平移后的视图上画的，换回完整画面的坐标再保存
    const box = currentMiniSize()
    const mapped = cropThroughView(
      draft,
      miniZoom.value,
      miniPan.value.x,
      miniPan.value.y,
      box.width,
      box.height
    )
    if (mapped) {
      const full = mapped.w >= 0.999 && mapped.h >= 0.999
      miniCrop.value = full ? null : mapped
    }
  }
  cropMode.value = false
  cropDraft.value = null
  miniZoom.value = 1
  miniPan.value = { x: 0, y: 0 }
  reveal()
}

/** 只重置预览框，点「应用裁切」后才真正生效；「取消」能保留原来的裁切 */
function resetCrop() {
  cropDraft.value = { x: 0, y: 0, w: 1, h: 1 }
}

function cancelCrop() {
  cropMode.value = false
  cropDraft.value = null
  reveal()
}

function openQuick(hit: DmHit, mode: 'click' | 'hover') {
  const m = hit.msg
  if (m.kind !== 'danmu') return
  cancelHoverClose()
  clearHoverOpen()
  quickTarget.value = m
  quickMode.value = mode
  quickReply.value = false
  quickText.value = ''
  quickHint.value = ''
  clearSendError()

  const zone = zoneEl.value
  const rect = zone?.getBoundingClientRect()
  if (rect) {
    const spaceAbove = Math.max(0, hit.y - 10)
    const spaceBelow = Math.max(0, rect.height - hit.bottom - 10)
    // 操作条只有一条胶囊高，上方有一点空间就够；放不下再翻到下方。
    const below = spaceAbove < 90 && spaceBelow > spaceAbove
    const half = Math.min(190, Math.max(70, (rect.width - 16) / 2))
    const left = Math.max(half + 4, Math.min(rect.width - half - 4, hit.x + hit.w / 2))
    const top = below ? Math.min(rect.height - 8, hit.bottom + 8) : Math.max(6, hit.y - 8)
    quickAnchor.value = {
      left,
      top,
      below,
      maxHeight: Math.max(120, below ? rect.height - top - 10 : top - 10),
    }
  } else {
    quickAnchor.value = null
  }
  controls.value = false
}

function closeQuick() {
  const seq = quickTarget.value?.seq
  if (seq) dmCanvas.value?.release(seq)
  quickTarget.value = null
  quickAnchor.value = null
  quickReply.value = false
  quickText.value = ''
  quickHint.value = ''
  cancelHoverClose()
  clearHoverOpen()
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

/** 屏蔽这条弹幕的发送者：他的弹幕、SC 和聊天室消息都会一起消失 */
function doQuickBlockUser() {
  if (!quickTarget.value) return
  blockUser(quickTarget.value)
  closeQuick()
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

function openSettings(
  tab: 'account' | 'danmaku' | 'multi' | 'mix' | 'video' | 'relay' | 'about'
) {
  // 全屏时面板会 Teleport 进全屏元素，不用再退出全屏
  closeOffsetBar()
  drawer.value?.open(tab)
}

/* ---------- 多路直播间快捷面板（不用进设置里翻） ---------- */

const multiOpen = ref(false)
const multiInput = ref('')
const multiPopEl = ref<HTMLElement | null>(null)
/** 面板内容超出高度时给底部加渐隐，提示还能往下滑 */
const multiFade = ref(false)

function updateMultiFade() {
  const el = multiPopEl.value
  multiFade.value = !!el && el.scrollHeight - el.scrollTop - el.clientHeight > 8
}

function toggleMultiPanel() {
  multiOpen.value = !multiOpen.value
  if (multiOpen.value) {
    moreOpen.value = false
    reveal()
    nextTick(updateMultiFade)
  }
}

/* ---------- 底栏发弹幕 + 更多功能（二级菜单） ---------- */

const moreOpen = ref(false)
const dockText = ref('')
const dockHint = ref('')
let dockHintTimer: number | null = null

/* ---------- 延迟悬浮调节条：全屏时也不再打开整页设置 ---------- */

const offsetOpen = ref(false)
let offsetTimer: number | null = null

const offsetLabel = computed(() => {
  const ms = store.sessionOffset
  const s = (Math.abs(ms) / 1000).toFixed(1)
  if (!ms) return '0.0s'
  // 正数表示 A（解说）晚，负数表示 B（画面）晚
  return `${ms > 0 ? 'A+' : 'B+'}${s}s`
})

function closeOffsetBar() {
  offsetOpen.value = false
  if (offsetTimer) {
    clearTimeout(offsetTimer)
    offsetTimer = null
  }
}

/** 像系统亮度条一样，松手几秒后自动收起 */
function bumpOffsetTimer() {
  if (offsetTimer) clearTimeout(offsetTimer)
  offsetTimer = window.setTimeout(() => {
    offsetTimer = null
    offsetOpen.value = false
  }, 6000)
}

function openOffsetBar() {
  moreOpen.value = false
  offsetOpen.value = true
  reveal()
  bumpOffsetTimer()
}

function onOffsetInput(e: Event) {
  setOffset(Number((e.target as HTMLInputElement).value))
  bumpOffsetTimer()
}

function resetOffsetFloat() {
  resetOffset()
  bumpOffsetTimer()
}

function showDockHint(text: string) {
  dockHint.value = text
  if (dockHintTimer) clearTimeout(dockHintTimer)
  dockHintTimer = window.setTimeout(() => {
    dockHintTimer = null
    dockHint.value = ''
  }, 4200)
}

async function dockSend() {
  const text = dockText.value.trim()
  if (!text || store.sendState.sending) return
  if (!store.loggedIn) {
    showDockHint('先登录 B 站账号才能发弹幕')
    return
  }
  const ok = await send(text)
  if (ok) {
    dockText.value = ''
    dockHint.value = ''
  } else {
    showDockHint(store.sendState.error || '发送失败')
  }
}

function toggleMore() {
  moreOpen.value = !moreOpen.value
  if (moreOpen.value) {
    multiOpen.value = false
    reveal()
  }
}

function moreAct(fn: () => void | Promise<unknown>) {
  moreOpen.value = false
  reveal()
  void fn()
}

watch(
  () => [store.sources.length, multiOpen.value],
  () => nextTick(updateMultiFade)
)

async function quickAddSource() {
  if (store.sourceBusy || !multiInput.value.trim()) return
  const source = await addSource(multiInput.value)
  if (source) multiInput.value = ''
  reveal()
}

function multiBadge(s: ExtraRoom): string {
  return badgePreviewText(s.badgeMode, {
    uname: s.uname,
    custom: s.badgeText,
    color: s.color,
  })
}

function multiStatus(s: ExtraRoom): string {
  if (s.audioError) return s.audioError
  if (s.status === 'failed') return s.error || '连接失败'
  if (s.status === 'connecting') return '连接中'
  if (s.status === 'reconnecting') return '重连中'
  if (s.mediaState === 'error') return '播放失败'
  if (s.mediaState === 'loading') return '准备中'
  return ''
}

function toggleSourceAudio(s: ExtraRoom) {
  updateSource(s.id, { commentary: !s.commentary })
}

function toggleSourceVideo(s: ExtraRoom) {
  updateSource(s.id, { video: !s.video })
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
    if (cropMode.value) cancelCrop()
    else if (miniImmersive.value) exitMiniImmersive()
    else if (quickTarget.value) closeQuick()
    else if (isFullscreen.value) void toggleFullscreen()
  }
}

/** 记录画面区域尺寸/安全区，小窗「全屏」按这个算适配盒子 */
function syncZoneSize() {
  const zone = zoneEl.value
  if (!zone) return
  zoneSize.value = { width: zone.clientWidth, height: zone.clientHeight }
  const css = getComputedStyle(zone)
  const px = (name: string) => parseFloat(css.getPropertyValue(name)) || 0
  safeInset.value = {
    left: px('--safe-left'),
    right: px('--safe-right'),
    top: px('--safe-top'),
    bottom: px('--safe-bottom'),
  }
}

function onWindowResize() {
  const zone = zoneEl.value
  if (!zone) return
  syncZoneSize()
  const max = zone.getBoundingClientRect().width - 16
  miniW.value = Math.min(miniW.value, Math.max(120, max))
  clampMiniPos()
}

watch(
  () => store.commentaryOn,
  (v) => {
    miniOn.value = v
    if (!v) {
      miniExpanded.value = false
      exitMiniImmersive()
    }
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
  if (zone) {
    syncZoneSize()
    miniW.value = defaultMiniWidth(zone.clientWidth)
    // 窗口 / 全屏 / 侧边聊天栏都会改画面区域尺寸
    zoneObserver = new ResizeObserver(syncZoneSize)
    zoneObserver.observe(zone)
  }
  clampMiniPos()
  reveal()
})

// 从主页「回到播放」时重新亮出控件（后台播放期间 LiveView 一直挂着，onMounted 不会再跑）
watch(
  () => store.view,
  (v) => {
    if (v === 'live') {
      reveal()
      nextTick(syncZoneSize)
    }
  }
)

onBeforeUnmount(() => {
  document.removeEventListener('fullscreenchange', onFsChange)
  window.removeEventListener('keydown', onKey)
  window.removeEventListener('resize', onWindowResize)
  zoneObserver?.disconnect()
  zoneObserver = null
  if (hideTimer) clearTimeout(hideTimer)
  if (dockHintTimer) clearTimeout(dockHintTimer)
  if (offsetTimer) clearTimeout(offsetTimer)
  clearMiniTap()
  exitMiniImmersive()
  cancelHoverClose()
  clearHoverOpen()
})
</script>

<template>
  <div class="live" :class="{ collapsed: chatCollapsed }">
    <div
      ref="zoneEl"
      class="video-zone"
      :class="{ 'mini-open': miniOn && !miniExpanded }"
      @pointerdown="onZoneTap"
      @pointermove="onZoneMove"
    >
      <video ref="videoBEl" class="video-b" playsinline :style="{ objectFit: videoFit }"></video>

      <DanmakuCanvas ref="dmCanvas" />

      <SuperChatOverlay />

      <MultiWall />

      <!-- 浏览器拦截自动播放时，用一次点击解锁声音 -->
      <button
        v-if="store.a.blocked || store.b.blocked || store.sources.some((s) => s.blocked)"
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
        </div>
      </Transition>

      <!-- 多路直播间快捷面板 -->
      <Transition name="fade">
        <div
          v-if="controls && multiOpen"
          ref="multiPopEl"
          class="multi-pop"
          :class="{ 'scroll-fade': multiFade }"
          @pointerdown.stop
          @click.stop
          @scroll.passive="updateMultiFade"
        >
          <div class="mp-head">
            <span class="mp-title"><AppIcon name="pip" :size="14" />多路直播间</span>
            <b v-if="store.sources.length">{{ store.sources.length }} 路</b>
            <button class="mp-more" @click="openSettings('multi')">详细设置</button>
          </div>

          <div class="mp-import">
            <input
              v-model="multiInput"
              placeholder="房间号或直播间链接"
              @keydown.enter="quickAddSource"
            />
            <button
              class="mp-add"
              :disabled="store.sourceBusy || !multiInput.trim()"
              @click="quickAddSource"
            >
              {{ store.sourceBusy ? '…' : '添加' }}
            </button>
          </div>
          <p v-if="store.sourceError" class="mp-err">{{ store.sourceError }}</p>

          <div v-for="s in store.sources" :key="s.id" class="mp-row">
            <span class="mp-badge" :style="{ background: s.color }">{{ multiBadge(s) }}</span>
            <span class="mp-who">
              <strong>{{ s.uname || s.title || `房间 ${s.roomId}` }}</strong>
              <small v-if="multiStatus(s)">{{ multiStatus(s) }}</small>
            </span>
            <button
              class="mp-toggle"
              :class="{ on: s.commentary }"
              :title="s.commentary ? '关闭这一路声音' : '打开这一路声音'"
              @click="toggleSourceAudio(s)"
            >
              <AppIcon :name="s.commentary ? 'volume' : 'volume-off'" :size="14" />
              <span>声音</span>
            </button>
            <button
              class="mp-toggle"
              :class="{ on: s.video }"
              :title="s.video ? '从多路画面移除' : '加入多路画面'"
              @click="toggleSourceVideo(s)"
            >
              <AppIcon name="pip" :size="14" />
              <span>画面</span>
            </button>
            <button class="mp-remove" title="移除这个直播间" @click="removeSource(s.id)">
              <AppIcon name="close" :size="13" />
            </button>
          </div>

          <p v-if="!store.sources.length" class="mp-empty">
            粘贴其他直播间，就能把它的弹幕、声音、画面加进来。
          </p>
        </div>
      </Transition>

      <!-- 底部控制条：播放 + 直接发弹幕 + 常用开关，其余都收进「更多」二级菜单 -->
      <Transition name="up">
        <div v-if="controls" class="bottombar">
          <button class="dock-play" :title="store.playing ? '暂停' : '播放'" @click="togglePlay">
            <AppIcon :name="store.playing ? 'pause' : 'play'" :size="18" />
            <span>{{ store.playing ? '暂停' : '播放' }}</span>
          </button>

          <div class="dock-compose" :class="{ disabled: !store.loggedIn }">
            <input
              v-model="dockText"
              class="dock-input"
              :maxlength="20"
              :placeholder="store.loggedIn ? '发个弹幕…' : '登录后发弹幕'"
              enterkeyhint="send"
              aria-label="发弹幕"
              @focus="onDockFocus"
              @blur="onDockBlur"
              @keydown.enter="(e) => !e.isComposing && dockSend()"
            />
            <button
              class="dock-send"
              title="发送弹幕"
              :disabled="!dockText.trim() || store.sendState.sending"
              @click="dockSend"
            >
              <AppIcon name="send" :size="15" />
            </button>
          </div>

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
            :class="{ on: store.sources.length > 0 }"
            title="添加多路直播间：弹幕 / 声音 / 画面"
            @click="toggleMultiPanel"
          >
            <AppIcon name="plus" :size="17" />
            <span>多路</span>
          </button>
          <button
            class="dock-btn"
            :class="{ on: moreOpen }"
            title="更多功能：切画面、画质、混音、延迟、画面、画中画、设置"
            @click="toggleMore"
          >
            <AppIcon name="more" :size="17" />
            <span>更多</span>
          </button>
          <button
            class="dock-btn dock-fs"
            :title="isFullscreen ? '退出全屏' : '全屏'"
            @click="toggleFullscreen"
          >
            <AppIcon :name="isFullscreen ? 'fullscreen-exit' : 'fullscreen'" :size="17" />
            <span>{{ isFullscreen ? '退出' : '全屏' }}</span>
          </button>

          <div v-if="dockHint" class="dock-hint" @pointerdown.stop @click.stop>
            <AppIcon name="warning" :size="13" />
            <span>{{ dockHint }}</span>
            <button v-if="!store.loggedIn" @click="openSettings('account')">去登录</button>
          </div>
        </div>
      </Transition>

      <!-- 更多功能（二级菜单） -->
      <Transition name="fade">
        <div v-if="moreOpen" class="more-layer" @pointerdown.stop @click.stop>
          <div class="more-scrim" @click="moreOpen = false"></div>
          <div class="more-sheet" role="dialog" aria-label="更多功能">
            <div class="more-head">
              <span class="more-title"><AppIcon name="more" :size="15" />更多功能</span>
              <button class="more-x" title="关闭" @click="moreOpen = false">
                <AppIcon name="close" :size="16" />
              </button>
            </div>
            <div class="more-grid">
              <button class="more-item" :disabled="store.swapping" @click="moreAct(swap)">
                <span class="mi-icon"><AppIcon name="swap" :size="18" /></span>
                <span class="mi-text">
                  <strong>切画面</strong>
                  <small>{{ store.swapping ? '切换中…' : 'A / B 互换' }}</small>
                </span>
              </button>
              <button class="more-item" @click="moreAct(toggleSc)">
                <span class="mi-icon"><AppIcon name="sc" :size="18" /></span>
                <span class="mi-text">
                  <strong>SC 悬浮窗</strong>
                  <small>{{ store.settings.sc.enabled ? '已开启' : '已关闭' }}</small>
                </span>
              </button>
              <button class="more-item" @click="moreAct(() => openSettings('mix'))">
                <span class="mi-icon"><AppIcon name="tune" :size="18" /></span>
                <span class="mi-text">
                  <strong>混音</strong>
                  <small>两路音量配比</small>
                </span>
              </button>
              <button class="more-item" @click="moreAct(openOffsetBar)">
                <span class="mi-icon"><AppIcon name="clock" :size="18" /></span>
                <span class="mi-text">
                  <strong>延迟</strong>
                  <small>悬浮调节条</small>
                </span>
              </button>
              <button class="more-item" @click="moreAct(() => openSettings('video'))">
                <span class="mi-icon"><AppIcon name="quality" :size="18" /></span>
                <span class="mi-text">
                  <strong>画面设置</strong>
                  <small>比例与清晰度</small>
                </span>
              </button>
              <button class="more-item" @click="moreAct(enterPip)">
                <span class="mi-icon"><AppIcon name="pip" :size="18" /></span>
                <span class="mi-text">
                  <strong>画中画</strong>
                  <small>系统悬浮小窗</small>
                </span>
              </button>
              <button class="more-item" @click="moreAct(toggleMini)">
                <span class="mi-icon"><AppIcon name="commentary" :size="18" /></span>
                <span class="mi-text">
                  <strong>解说小窗</strong>
                  <small>{{ miniOn ? '已显示，点击关闭' : '显示 A 路解说' }}</small>
                </span>
              </button>
              <button
                class="more-item"
                :disabled="store.reconnecting"
                @click="moreAct(reconnect)"
              >
                <span class="mi-icon">
                  <AppIcon name="refresh" :size="18" :class="{ spin: store.reconnecting }" />
                </span>
                <span class="mi-text">
                  <strong>重连</strong>
                  <small>{{ store.reconnecting ? '重连中…' : '重新拉流' }}</small>
                </span>
              </button>
              <button class="more-item" @click="moreAct(() => openSettings('danmaku'))">
                <span class="mi-icon"><AppIcon name="danmaku" :size="18" /></span>
                <span class="mi-text">
                  <strong>弹幕设置</strong>
                  <small>字号 / 速度 / 屏蔽</small>
                </span>
              </button>
              <button class="more-item wide" @click="moreAct(() => openSettings('account'))">
                <span class="mi-icon"><AppIcon name="settings" :size="18" /></span>
                <span class="mi-text">
                  <strong>设置</strong>
                  <small>账号、主题与更多</small>
                </span>
              </button>
            </div>

            <div class="more-quality">
              <div class="mq-head">
                <span>B 路画质</span>
                <small>{{ store.qualitySwitching ? '切换中…' : store.b.title || '直播画面' }}</small>
              </div>
              <div class="mq-pills">
                <button
                  v-for="q in store.b.acceptQn"
                  :key="q.qn"
                  :class="{ on: q.qn === store.b.qn }"
                  :disabled="store.qualitySwitching"
                  @click="chooseQuality(q.qn)"
                >
                  {{ q.desc }}
                </button>
                <span v-if="!store.b.acceptQn.length" class="mq-empty">开播后可选</span>
              </div>
            </div>
          </div>
        </div>
      </Transition>

      <!-- 延迟悬浮调节条：像系统亮度条，调完几秒自动收 -->
      <Transition name="up">
        <div v-if="offsetOpen" class="offset-float" @pointerdown.stop @click.stop>
          <span class="of-icon"><AppIcon name="clock" :size="15" /></span>
          <input
            class="of-range"
            type="range"
            min="-20000"
            max="20000"
            step="50"
            :value="store.sessionOffset"
            :style="{ '--fill': ((store.sessionOffset + 20000) / 40000) * 100 + '%' }"
            aria-label="音画延迟"
            @input="onOffsetInput"
          />
          <button
            class="of-value"
            :class="{ zero: !store.sessionOffset }"
            :title="store.sessionOffset > 0 ? 'A 路（解说）晚一些' : store.sessionOffset < 0 ? 'B 路（画面）晚一些' : '两路同步，点按归零'"
            @click="resetOffsetFloat"
          >
            {{ offsetLabel }}
          </button>
          <button class="of-x" title="收起" @click="closeOffsetBar">
            <AppIcon name="close" :size="14" />
          </button>
        </div>
      </Transition>

      <!-- A 解说小窗 -->
      <div
        class="mini"
        :class="{
          expanded: miniExpanded,
          hidden: !miniOn,
          cropping: cropMode,
          immersive: miniImmersive,
        }"
        :style="
          miniExpanded
            ? expandedStyle
            : { right: miniPos.x + 'px', top: miniPos.y + 'px', width: miniW + 'px', height: miniH + 'px' }
        "
        @pointerdown="onMiniPointerDown"
        @pointermove="onMiniPointerMove"
        @pointerup="onMiniPointerUp"
        @pointercancel="onMiniPointerUp"
        @dblclick="onMiniDoubleClick"
      >
        <!-- 框选时先看完整画面，应用裁切后再套变换，方便重新框 -->
        <div class="mini-frame" :style="miniCropStyle && !cropMode ? { transform: miniCropStyle } : undefined">
          <video
            ref="videoAEl"
            playsinline
            :style="{
              transform: `translate(${miniPan.x}px, ${miniPan.y}px) scale(${miniZoom})`,
            }"
          ></video>
        </div>

        <div
          v-if="miniExpanded && cropMode"
          class="crop-layer"
          @pointerdown.stop="onCropDown"
          @pointermove.stop="onCropMove"
          @pointerup.stop="onCropUp"
          @pointercancel.stop="onCropUp"
        >
          <div
            v-if="cropDraft"
            class="crop-box"
            :style="{
              left: cropDraft.x * 100 + '%',
              top: cropDraft.y * 100 + '%',
              width: cropDraft.w * 100 + '%',
              height: cropDraft.h * 100 + '%',
            }"
          ></div>
          <span class="crop-tip">双指缩放对准，拖动框选画面区域</span>
        </div>

        <Transition name="fade">
          <span v-if="miniImmersive && miniImmHint" class="mini-imm-hint">
            点击画面显示按钮
          </span>
        </Transition>

        <div class="mini-top">
          <span class="mini-tag"><i></i>A · 解说{{ miniCrop ? ' · 已裁切' : '' }}</span>
          <span class="mini-btns">
            <button
              v-if="miniExpanded"
              :class="{ on: cropMode }"
              title="框选裁切画面"
              @pointerdown.stop
              @click.stop="toggleCropMode"
            >
              <AppIcon name="crop" :size="13" />
            </button>
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

        <div v-if="miniExpanded && cropMode" class="crop-actions" @pointerdown.stop>
          <button class="crop-btn primary" @click="applyCrop">应用裁切</button>
          <button class="crop-btn" :disabled="!miniCrop" @click="resetCrop">清除</button>
          <button class="crop-btn dim" @click="cancelCrop">取消</button>
        </div>

        <template v-if="!miniExpanded">
          <div
            v-for="corner in (['tl', 'tr', 'bl', 'br'] as const)"
            :key="corner"
            class="mini-resize"
            :class="corner"
            title="拖动边角等比缩放"
            @pointerdown.stop="onResizeDown($event, corner)"
            @pointermove.stop="onResizeMove"
            @pointerup.stop="onResizeUp"
            @pointercancel.stop="onResizeUp"
          ></div>
        </template>
      </div>

      <!-- 点中 / 悬停画面弹幕后浮出的快捷条 -->
      <div
        v-if="quickTarget"
        class="quickbar"
        :class="{ below: quickAnchor?.below, 'from-hover': quickMode === 'hover' }"
        :style="
          quickAnchor
            ? { left: quickAnchor.left + 'px', top: quickAnchor.top + 'px', maxHeight: quickAnchor.maxHeight + 'px' }
            : undefined
        "
        @pointerdown.stop
        @mouseenter="cancelHoverClose"
        @mouseleave="scheduleHoverClose"
      >
        <template v-if="!quickReply">
          <div class="quick-pill" role="toolbar" aria-label="弹幕快捷操作">
            <button
              v-if="canInteract(quickTarget)"
              class="qbtn plus"
              title="+1"
              @click="doQuickPlusOne"
            >
              <AppIcon name="plus" :size="14" />
              +1
            </button>
            <button
              v-if="canInteract(quickTarget)"
              class="qbtn"
              title="回复"
              @click="doQuickAnswer"
            >
              <AppIcon name="reply" :size="14" />
              回复
            </button>
            <button
              v-if="quickTarget.uname && !quickTarget.self"
              class="qbtn"
              title="以后不再显示这个用户的弹幕"
              @click="doQuickBlockUser"
            >
              <AppIcon name="block" :size="14" />
              屏蔽
            </button>
            <button class="quick-x" title="关闭" @click="closeQuick">
              <AppIcon name="close" :size="14" />
            </button>
          </div>
          <p v-if="quickHint || store.sendState.error" class="quick-hint">
            {{ quickHint || store.sendState.error }}
          </p>
        </template>

        <template v-else>
          <div class="quick-pill reply">
            <input
              ref="quickInput"
              v-model="quickText"
              :maxlength="quickLimit"
              placeholder="回复…"
              @keydown.enter="(e) => !e.isComposing && doQuickSend()"
            />
            <button
              class="qbtn plus send-mini"
              title="发送"
              :disabled="!quickText.trim() || store.sendState.sending"
              @click="doQuickSend"
            >
              <AppIcon name="send" :size="15" />
            </button>
            <button class="quick-x" title="取消" @click="closeQuick">
              <AppIcon name="close" :size="14" />
            </button>
          </div>
          <p v-if="quickHint || store.sendState.error" class="quick-hint">
            {{ quickHint || store.sendState.error }}
          </p>
        </template>
      </div>

      <!-- 错误提示 -->
      <div
        v-if="firstError && !quickTarget && !multiOpen && !moreOpen && !dockHint"
        class="err-banner"
        @pointerdown.stop
      >
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
      @import="openSettings('danmaku')"
    />
    <div v-else class="chat-fab-row">
      <button class="chat-fab" @click="chatCollapsed = false">
        <AppIcon name="chat" :size="17" />
        聊天室
      </button>
    </div>

    <SettingsDrawer ref="drawer" :fullscreen-el="isFullscreen ? zoneEl : null" />
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

/* 全屏不显示顶栏，只留画面和底栏 */
.video-zone:fullscreen .topbar {
  display: none;
}

/* 全屏时底栏也居中，和平时保持一致 */
.video-zone:fullscreen .bottombar {
  left: 50%;
  right: auto;
  width: max-content;
  max-width: calc(100% - 28px);
  transform: translateX(-50%);
}

/* 全屏底栏更高，提示条跟着抬高，别压到按钮上 */
.video-zone:fullscreen .err-banner {
  bottom: calc(84px + var(--safe-bottom));
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

.spin {
  animation: spin 0.9s linear infinite;
}

/* ---------- 底部控制条 ---------- */

.bottombar {
  position: absolute;
  left: calc(8px + var(--safe-left));
  right: calc(8px + var(--safe-right));
  bottom: calc(8px + var(--safe-bottom));
  display: flex;
  align-items: stretch;
  gap: 4px;
  padding: 4px 6px;
  border-radius: 18px;
  background: linear-gradient(180deg, rgba(30, 27, 23, 0.78), rgba(16, 14, 12, 0.72));
  border: 1px solid rgba(255, 255, 255, 0.1);
  box-shadow: 0 14px 38px rgba(0, 0, 0, 0.36);
  backdrop-filter: blur(22px) saturate(1.15);
  z-index: 5;
}

/* 每个按钮都是「图标 + 短文字」，不用猜图标是什么意思 */
.dock-play {
  height: 46px;
  min-width: 46px;
  padding: 2px 10px;
  flex: 0 0 auto;
  border: none;
  border-radius: 14px;
  background: var(--accent);
  color: #fff;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 3px;
  font-size: 9.5px;
  font-weight: 650;
  cursor: pointer;
  box-shadow: 0 6px 16px rgba(217, 119, 87, 0.35);
}

.dock-play span {
  line-height: 1;
}

.dock-play:active {
  transform: scale(0.96);
}

/* 底栏直接发弹幕：输入框吃掉剩余宽度，回车 / 发送键发送 */
.dock-compose {
  flex: 1 1 180px;
  min-width: 0;
  max-width: 360px;
  /* 和两侧按钮同高、同圆角，底栏看起来才是一条线 */
  height: 46px;
  align-self: center;
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 0 4px 0 12px;
  border-radius: 14px;
  background: rgba(255, 255, 255, 0.09);
  border: 1px solid rgba(255, 255, 255, 0.14);
  transition:
    border-color 0.15s ease,
    background 0.15s ease;
}

.dock-compose:focus-within {
  border-color: rgba(217, 119, 87, 0.55);
  background: rgba(255, 255, 255, 0.13);
}

.dock-input {
  flex: 1;
  min-width: 0;
  border: none;
  background: transparent;
  color: #fff8ef;
  font-size: 13px;
  outline: none;
}

.dock-input::placeholder {
  color: rgba(255, 248, 239, 0.5);
}

.dock-compose.disabled .dock-input::placeholder {
  color: rgba(255, 248, 239, 0.36);
}

.dock-send {
  flex: 0 0 auto;
  width: 28px;
  height: 28px;
  display: grid;
  place-items: center;
  border: none;
  border-radius: 50%;
  background: var(--accent);
  color: var(--on-accent);
  cursor: pointer;
}

.dock-send:disabled {
  opacity: 0.4;
}

/* 发送失败 / 未登录时，在底栏上方浮一条提示 */
.dock-hint {
  position: absolute;
  left: 50%;
  transform: translateX(-50%);
  bottom: calc(100% + 6px);
  z-index: 2;
  /* 底栏是定位父级，不写 max-content 的话可用宽度只有左半截，提示会被截断 */
  width: max-content;
  display: flex;
  align-items: center;
  gap: 6px;
  max-width: min(420px, calc(100vw - 24px));
  padding: 7px 8px 7px 10px;
  border-radius: 12px;
  background: rgba(88, 38, 33, 0.95);
  border: 1px solid rgba(255, 158, 140, 0.32);
  color: #ffe9e2;
  font-size: 11px;
  box-shadow: 0 10px 28px rgba(0, 0, 0, 0.4);
}

.dock-hint span {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.dock-hint button {
  flex: 0 0 auto;
  border: none;
  border-radius: 999px;
  padding: 4px 9px;
  background: rgba(255, 255, 255, 0.14);
  color: #fff;
  font-size: 10.5px;
  cursor: pointer;
}

.dock-btn {
  height: 46px;
  flex: 0 1 auto;
  min-width: 40px;
  display: inline-flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 3px;
  border: none;
  border-radius: 12px;
  background: transparent;
  color: rgba(255, 248, 239, 0.62);
  padding: 2px 8px;
  font-size: 9.5px;
  white-space: nowrap;
  cursor: pointer;
  transition:
    background 0.15s ease,
    color 0.15s ease;
}

.dock-btn span {
  line-height: 1;
}

.dock-btn:hover {
  background: rgba(255, 255, 255, 0.08);
  color: #fff8ef;
}

.dock-btn.on {
  background: rgba(217, 119, 87, 0.2);
  color: #f2b49e;
}

/* 全屏固定在最右侧，右下角更好按 */
.bottombar .dock-fs {
  flex: 0 0 auto;
  min-width: 46px;
  color: #fff8ef;
  background: rgba(217, 119, 87, 0.22);
}

.dock-fs:hover {
  color: #fff;
  background: rgba(217, 119, 87, 0.36);
}

.dock-btn:disabled {
  opacity: 0.45;
}

/* ---------- 更多功能（二级菜单） ---------- */

.more-layer {
  position: fixed;
  inset: 0;
  z-index: 30;
  display: flex;
  align-items: flex-end;
  justify-content: center;
}

.more-scrim {
  position: absolute;
  inset: 0;
  background: rgba(0, 0, 0, 0.5);
  backdrop-filter: blur(2px);
}

.more-sheet {
  position: relative;
  width: min(560px, calc(100% - 16px));
  max-height: min(72vh, calc(100dvh - 70px));
  overflow-y: auto;
  overscroll-behavior: contain;
  margin-bottom: calc(8px + var(--safe-bottom));
  padding: 12px;
  border-radius: 20px;
  background: linear-gradient(180deg, rgba(32, 29, 25, 0.97), rgba(16, 14, 12, 0.96));
  border: 1px solid rgba(255, 255, 255, 0.13);
  box-shadow: 0 24px 60px rgba(0, 0, 0, 0.55);
  backdrop-filter: blur(20px);
  color: #fff8ef;
}

.more-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 2px 10px;
}

.more-title {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 12.5px;
  font-weight: 650;
}

.more-x {
  width: 28px;
  height: 28px;
  display: grid;
  place-items: center;
  border: none;
  border-radius: 50%;
  background: rgba(255, 255, 255, 0.08);
  color: rgba(255, 248, 239, 0.75);
  cursor: pointer;
}

.more-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 6px;
}

.more-item {
  display: flex;
  align-items: center;
  gap: 9px;
  min-width: 0;
  padding: 9px 10px;
  border: 1px solid rgba(255, 255, 255, 0.08);
  border-radius: 14px;
  background: rgba(255, 255, 255, 0.05);
  color: #fff8ef;
  text-align: left;
  cursor: pointer;
}

.more-item:hover {
  background: rgba(255, 255, 255, 0.1);
}

.more-item:disabled {
  opacity: 0.5;
}

.more-item.wide {
  grid-column: 1 / -1;
}

.mi-icon {
  flex: 0 0 auto;
  width: 30px;
  height: 30px;
  display: grid;
  place-items: center;
  border-radius: 10px;
  background: rgba(217, 119, 87, 0.16);
  color: var(--accent);
}

.mi-text {
  display: flex;
  flex-direction: column;
  gap: 1px;
  min-width: 0;
}

.mi-text strong {
  font-size: 12px;
  font-weight: 600;
}

.mi-text small {
  font-size: 10px;
  color: rgba(255, 248, 239, 0.5);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.more-quality {
  margin-top: 8px;
  padding: 9px 10px 8px;
  border: 1px solid rgba(255, 255, 255, 0.08);
  border-radius: 14px;
  background: rgba(255, 255, 255, 0.04);
}

.mq-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 7px;
  font-size: 11px;
  font-weight: 600;
}

.mq-head small {
  min-width: 0;
  font-size: 9.5px;
  font-weight: 400;
  color: rgba(255, 248, 239, 0.45);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.mq-pills {
  display: flex;
  flex-wrap: wrap;
  gap: 5px;
}

.mq-pills button {
  border: none;
  border-radius: 999px;
  padding: 5px 10px;
  background: rgba(255, 255, 255, 0.08);
  color: rgba(255, 248, 239, 0.72);
  font-size: 10.5px;
  cursor: pointer;
}

.mq-pills button.on {
  background: var(--accent);
  color: #fff;
  font-weight: 600;
}

.mq-pills button:disabled {
  opacity: 0.5;
}

.mq-empty {
  font-size: 10.5px;
  color: rgba(255, 248, 239, 0.4);
}

/* ---------- 多路快捷面板 ---------- */

.multi-pop {
  position: absolute;
  right: calc(10px + var(--safe-right));
  bottom: calc(66px + var(--safe-bottom));
  width: min(390px, calc(100% - 20px));
  max-height: min(64%, calc(100% - 76px));
  overflow-y: auto;
  overscroll-behavior: contain;
  z-index: 7;
  display: flex;
  flex-direction: column;
  gap: 7px;
  padding: 11px;
  border-radius: 18px;
  background: linear-gradient(180deg, rgba(30, 27, 23, 0.92), rgba(16, 14, 12, 0.9));
  border: 1px solid rgba(255, 255, 255, 0.12);
  box-shadow: 0 18px 44px rgba(0, 0, 0, 0.45);
  backdrop-filter: blur(20px);
  color: #fff8ef;
}

/* 还有内容在下面时，底部渐隐提示可以继续滑 */
.multi-pop.scroll-fade {
  mask-image: linear-gradient(180deg, #000 calc(100% - 22px), transparent);
  -webkit-mask-image: linear-gradient(180deg, #000 calc(100% - 22px), transparent);
}

.mp-head {
  display: flex;
  align-items: center;
  gap: 8px;
}

.mp-title {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  font-weight: 650;
}

.mp-head b {
  color: var(--accent);
  font-size: 11px;
}

.mp-more {
  margin-left: auto;
  border: none;
  background: rgba(255, 255, 255, 0.08);
  color: rgba(255, 248, 239, 0.78);
  font-size: 10.5px;
  padding: 5px 9px;
  border-radius: 999px;
  cursor: pointer;
}

.mp-import {
  display: flex;
  gap: 6px;
}

.mp-import input {
  flex: 1;
  min-width: 0;
  height: 34px;
  border-radius: 10px;
  border: 1px solid rgba(255, 255, 255, 0.14);
  background: rgba(255, 255, 255, 0.08);
  color: #fff8ef;
  padding: 0 10px;
  font-size: 12.5px;
  outline: none;
}

.mp-import input::placeholder {
  color: rgba(255, 248, 239, 0.4);
}

.mp-add {
  flex: 0 0 auto;
  border: none;
  border-radius: 10px;
  background: var(--accent);
  color: var(--on-accent);
  font-size: 11.5px;
  font-weight: 650;
  padding: 0 12px;
  cursor: pointer;
}

.mp-add:disabled {
  opacity: 0.45;
}

.mp-err {
  margin: 0;
  color: #ffb4a4;
  font-size: 10.5px;
}

.mp-row {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 6px;
  border-radius: 12px;
  background: rgba(255, 255, 255, 0.05);
}

.mp-badge {
  flex: 0 0 auto;
  min-width: 16px;
  height: 16px;
  padding: 0 5px;
  display: inline-grid;
  place-items: center;
  border-radius: 999px;
  color: #fff;
  font-size: 9px;
  font-weight: 750;
}

.mp-who {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 1px;
}

.mp-who strong {
  font-size: 11.5px;
  font-weight: 600;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.mp-who small {
  font-size: 9.5px;
  color: rgba(255, 248, 239, 0.5);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.mp-toggle {
  flex: 0 0 auto;
  display: inline-flex;
  align-items: center;
  gap: 3px;
  border: none;
  border-radius: 8px;
  background: rgba(255, 255, 255, 0.07);
  color: rgba(255, 248, 239, 0.6);
  font-size: 9.5px;
  padding: 5px 7px;
  cursor: pointer;
}

.mp-toggle.on {
  background: rgba(217, 119, 87, 0.24);
  color: #f2b49e;
}

.mp-remove {
  flex: 0 0 auto;
  width: 26px;
  height: 26px;
  display: grid;
  place-items: center;
  border: none;
  border-radius: 8px;
  background: transparent;
  color: rgba(255, 248, 239, 0.5);
  cursor: pointer;
}

.mp-remove:hover {
  background: rgba(190, 70, 52, 0.5);
  color: #fff;
}

.mp-empty {
  margin: 0;
  font-size: 10.5px;
  line-height: 1.5;
  color: rgba(255, 248, 239, 0.5);
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

.mini-frame {
  position: absolute;
  inset: 0;
  transform-origin: 0 0;
  overflow: hidden;
  will-change: transform;
}

.mini video {
  width: 100%;
  height: 100%;
  object-fit: cover;
  pointer-events: none;
  transform-origin: center center;
  transition: transform 0.08s linear;
}

/* 小窗「全屏」：JS 按画面区域算好盒子（四边留 14px + 安全区），
   比例和小窗一致，画面不会被裁掉，手机边缘手势也不挡框选 */
.mini.expanded {
  border-radius: 16px;
  border: 1px solid rgba(255, 255, 255, 0.24);
  box-shadow: 0 16px 40px rgba(0, 0, 0, 0.55);
  cursor: default;
  z-index: 11;
}

/* 实际画面比例和盒子不一致时用 contain 兜底，保证完整显示不裁切 */
.mini.expanded video {
  object-fit: contain;
  transition: none;
}

.mini.hidden {
  opacity: 0;
  pointer-events: none;
  transform: translateX(200vw);
}

/* 点一下画面：隐藏小窗自己的按钮，只留干净画面 */
.mini.immersive {
  cursor: pointer;
  border-color: transparent;
  box-shadow: none;
}

.mini.immersive .mini-top {
  opacity: 0;
  pointer-events: none;
}

.mini.immersive .mini-resize {
  opacity: 0;
  pointer-events: none;
}

.mini.immersive .mini-imm-hint {
  opacity: 1;
}

.mini-imm-hint {
  position: absolute;
  left: 50%;
  bottom: 12px;
  z-index: 5;
  transform: translateX(-50%);
  padding: 5px 10px;
  border-radius: 999px;
  background: rgba(0, 0, 0, 0.58);
  color: rgba(255, 248, 239, 0.84);
  font-size: 9.5px;
  white-space: nowrap;
  pointer-events: none;
  backdrop-filter: blur(8px);
  opacity: 0;
  transition: opacity 0.2s ease;
}

.mini-top {
  transition: opacity 0.18s ease;
}

.mini-top {
  position: absolute;
  top: 0;
  left: 0;
  right: 0;
  z-index: 4;
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

.mini-btns button.on {
  background: var(--accent);
}

/* ---------- 框选裁切 ---------- */

.crop-layer {
  position: absolute;
  inset: 0;
  z-index: 3;
  cursor: crosshair;
  touch-action: none;
  background: rgba(0, 0, 0, 0.16);
}

.crop-box {
  position: absolute;
  border: 2px solid #fff;
  background: rgba(255, 255, 255, 0.08);
  box-shadow: 0 0 0 9999px rgba(0, 0, 0, 0.42);
  pointer-events: none;
}

.crop-tip {
  position: absolute;
  left: 50%;
  bottom: 64px;
  transform: translateX(-50%);
  padding: 6px 12px;
  border-radius: 999px;
  background: rgba(0, 0, 0, 0.6);
  color: rgba(255, 255, 255, 0.88);
  font-size: 11px;
  white-space: nowrap;
  pointer-events: none;
  backdrop-filter: blur(8px);
}

.crop-actions {
  position: absolute;
  left: 50%;
  bottom: calc(18px + var(--safe-bottom));
  transform: translateX(-50%);
  z-index: 4;
  display: flex;
  gap: 8px;
  padding: 7px;
  border-radius: 999px;
  background: rgba(20, 17, 14, 0.82);
  border: 1px solid rgba(255, 255, 255, 0.16);
  backdrop-filter: blur(14px);
  box-shadow: 0 12px 30px rgba(0, 0, 0, 0.4);
}

.crop-btn {
  border: none;
  border-radius: 999px;
  padding: 8px 15px;
  font-size: 12px;
  font-weight: 600;
  background: rgba(255, 255, 255, 0.12);
  color: #fff8ef;
  cursor: pointer;
  white-space: nowrap;
}

.crop-btn.primary {
  background: var(--accent);
  color: #fff;
}

.crop-btn.dim {
  background: transparent;
  color: rgba(255, 248, 239, 0.6);
}

.crop-btn:disabled {
  opacity: 0.4;
}

/* ---------- 小窗四角缩放 ---------- */

.mini-resize {
  position: absolute;
  width: 22px;
  height: 22px;
  z-index: 3;
  touch-action: none;
}

.mini-resize::after {
  content: '';
  position: absolute;
  inset: 6px;
  border: 0 solid rgba(255, 255, 255, 0.72);
  filter: drop-shadow(0 1px 2px rgba(0, 0, 0, 0.5));
}

.mini-resize.br {
  right: 0;
  bottom: 0;
  cursor: nwse-resize;
}

.mini-resize.br::after {
  border-right-width: 2px;
  border-bottom-width: 2px;
  border-bottom-right-radius: 3px;
}

.mini-resize.bl {
  left: 0;
  bottom: 0;
  cursor: nesw-resize;
}

.mini-resize.bl::after {
  border-left-width: 2px;
  border-bottom-width: 2px;
  border-bottom-left-radius: 3px;
}

.mini-resize.tr {
  right: 0;
  top: 30px;
  cursor: nesw-resize;
}

.mini-resize.tl {
  left: 0;
  top: 30px;
  cursor: nwse-resize;
}

.mini-resize.tr::after {
  border-right-width: 2px;
  border-top-width: 2px;
  border-top-right-radius: 3px;
}

.mini-resize.tl::after {
  border-left-width: 2px;
  border-top-width: 2px;
  border-top-left-radius: 3px;
}

/* SC 悬浮窗和 A 小窗并排时让出空间 */
.video-zone.mini-open :deep(.sc-overlay) {
  width: min(380px, calc(100% - 280px));
}

/* ---------- 弹幕快捷条 ---------- */

/*
 * 点中弹幕后只浮一条胶囊：+1 / 回复 / 屏蔽 / 关闭。
 * 不重复显示昵称和弹幕内容，尽量少占画面。
 */
.quickbar {
  position: absolute;
  left: 50%;
  transform: translate(-50%, calc(-100% - 4px));
  width: max-content;
  max-width: calc(100% - 16px);
  z-index: 12;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 4px;
  /* 胶囊始终是浅色底，文字固定用深色，深色主题下也不会看不清 */
  color: #201d19;
  animation: fadeUp 0.16s ease;
  /* 胶囊之间的空白不挡画面点击 */
  pointer-events: none;
}

.quickbar > * {
  pointer-events: auto;
}

.quickbar.below {
  transform: translate(-50%, 0);
}

.quickbar.from-hover {
  animation: none;
}

.quick-pill {
  display: flex;
  align-items: center;
  gap: 1px;
  max-width: 100%;
  padding: 3px;
  border-radius: 999px;
  background: rgba(255, 251, 244, 0.97);
  border: 1px solid rgba(255, 255, 255, 0.72);
  box-shadow: 0 10px 28px rgba(0, 0, 0, 0.34);
  backdrop-filter: blur(18px);
}

/* 回复时把胶囊拉长成一条输入框，仍然保持椭圆 */
.quick-pill.reply {
  width: min(380px, calc(100vw - 24px));
  padding-left: 12px;
}

.quick-pill input {
  flex: 1;
  min-width: 0;
  padding: 4px 2px;
  border: none;
  background: transparent;
  color: #201d19;
  font-size: 13px;
  outline: none;
}

.quick-pill input::placeholder {
  color: rgba(32, 29, 25, 0.45);
}

.quick-x {
  flex: 0 0 auto;
  display: grid;
  place-items: center;
  width: 26px;
  height: 26px;
  border: none;
  border-radius: 50%;
  background: transparent;
  color: rgba(32, 29, 25, 0.45);
  cursor: pointer;
}

.quick-x:hover {
  background: rgba(32, 29, 25, 0.08);
  color: #201d19;
}

.quick-hint {
  margin: 0;
  max-width: 100%;
  padding: 3px 9px;
  border-radius: 999px;
  background: rgba(88, 38, 33, 0.94);
  color: #ffe9e2;
  font-size: 10.5px;
  line-height: 1.4;
}

.qbtn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 3px;
  height: 30px;
  padding: 0 10px;
  border: none;
  border-radius: 999px;
  background: transparent;
  color: #201d19;
  font-size: 12px;
  font-weight: 600;
  white-space: nowrap;
  cursor: pointer;
}

.qbtn:hover {
  background: rgba(32, 29, 25, 0.07);
}

.qbtn.plus {
  background: var(--accent);
  color: var(--on-accent);
  box-shadow: 0 4px 12px rgba(217, 119, 87, 0.24);
}

.qbtn.plus:hover {
  background: var(--accent);
  filter: brightness(1.05);
}

.qbtn.send-mini {
  flex: 0 0 auto;
  width: 32px;
  height: 32px;
  padding: 0;
  border-radius: 50%;
}

.qbtn:disabled {
  opacity: 0.45;
}

/* ---------- 延迟悬浮调节条 ---------- */

.offset-float {
  position: absolute;
  left: 50%;
  bottom: calc(70px + var(--safe-bottom));
  transform: translateX(-50%);
  z-index: 9;
  width: min(440px, calc(100% - 24px));
  height: 44px;
  display: flex;
  align-items: center;
  gap: 7px;
  padding: 0 6px 0 12px;
  border-radius: 999px;
  background: linear-gradient(180deg, rgba(30, 27, 23, 0.86), rgba(16, 14, 12, 0.82));
  border: 1px solid rgba(255, 255, 255, 0.12);
  box-shadow: 0 14px 38px rgba(0, 0, 0, 0.4);
  backdrop-filter: blur(22px) saturate(1.15);
  color: #fff8ef;
}

.of-icon {
  flex: 0 0 auto;
  display: grid;
  place-items: center;
  color: rgba(255, 248, 239, 0.68);
}

.of-range {
  flex: 1;
  min-width: 0;
  height: 28px;
  margin: 0;
  appearance: none;
  -webkit-appearance: none;
  background: transparent;
  cursor: pointer;
}

.of-range::-webkit-slider-runnable-track {
  height: 4px;
  border-radius: 999px;
  background: linear-gradient(
    90deg,
    var(--accent) var(--fill),
    rgba(255, 255, 255, 0.2) var(--fill)
  );
}

.of-range::-webkit-slider-thumb {
  -webkit-appearance: none;
  width: 16px;
  height: 16px;
  margin-top: -6px;
  border-radius: 50%;
  background: #fff;
  border: 2px solid var(--accent);
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.45);
}

.of-range::-moz-range-track {
  height: 4px;
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.2);
}

.of-range::-moz-range-progress {
  height: 4px;
  border-radius: 999px;
  background: var(--accent);
}

.of-range::-moz-range-thumb {
  width: 14px;
  height: 14px;
  border-radius: 50%;
  background: #fff;
  border: 2px solid var(--accent);
}

.of-value {
  flex: 0 0 auto;
  min-width: 52px;
  height: 28px;
  padding: 0 8px;
  border: none;
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.1);
  color: #fff8ef;
  font-size: 11px;
  font-weight: 650;
  font-variant-numeric: tabular-nums;
  cursor: pointer;
}

.of-value.zero {
  color: rgba(255, 248, 239, 0.5);
}

.of-x {
  flex: 0 0 auto;
  width: 28px;
  height: 28px;
  display: grid;
  place-items: center;
  border: none;
  border-radius: 50%;
  background: transparent;
  color: rgba(255, 248, 239, 0.6);
  cursor: pointer;
}

.of-x:hover {
  background: rgba(255, 255, 255, 0.1);
  color: #fff8ef;
}

/* ---------- 错误提示 ---------- */

.err-banner {
  position: absolute;
  left: 50%;
  transform: translateX(-50%);
  width: min(560px, calc(100% - 20px));
  bottom: calc(70px + var(--safe-bottom));
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
  /* 错误条可能压在小窗上，只让按钮接点击，画面照常可以点按 */
  pointer-events: none;
}

.err-banner span {
  flex: 1;
  min-width: 0;
}

.err-banner button {
  pointer-events: auto;
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
  bottom: calc(72px + var(--safe-bottom));
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
    /* 不跟画面平分宽度：横屏优先把空间留给视频 */
    flex: 0 0 clamp(280px, 38vw, 400px);
    width: auto;
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
    max-width: calc(100% - 28px);
    bottom: 14px;
    padding: 6px 8px;
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

  .ib {
    width: 30px;
    height: 30px;
  }

  .video-zone.mini-open :deep(.sc-overlay) {
    width: min(240px, calc(100% - 150px));
  }
}

/* 手机、小窗和横屏短屏：底部只留一排图标，绝不横向滚动 */
@media (max-width: 720px), (max-height: 500px) and (orientation: landscape) {
  .bottombar {
    gap: 3px;
    padding: 3px;
    border-radius: 15px;
  }

  .dock-btn {
    flex: 0 0 auto;
    min-width: 36px;
    height: 44px;
    gap: 2px;
    padding: 1px 3px;
    justify-content: center;
    font-size: 9px;
  }

  .dock-play {
    flex: 0 0 auto;
    min-width: 44px;
    height: 44px;
    padding: 1px 4px;
    border-radius: 12px;
    font-size: 9px;
  }

  .bottombar .dock-fs {
    flex: 0 0 42px;
    min-width: 42px;
    max-width: none;
    height: 44px;
  }

  .dock-compose {
    flex: 1 1 auto;
    height: 44px;
    border-radius: 12px;
    padding-left: 10px;
  }

  .dock-input {
    font-size: 12.5px;
  }

  .dock-hint {
    font-size: 10.5px;
  }

  .multi-pop {
    bottom: calc(64px + var(--safe-bottom));
  }
}

/* 超窄屏：输入框再挤也留出位置，按钮稍微收窄 */
@media (max-width: 350px) {
  .dock-btn {
    min-width: 32px;
  }

  .dock-play,
  .bottombar .dock-fs {
    min-width: 38px;
    flex-basis: 38px;
  }

  .dock-compose {
    padding-left: 8px;
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
