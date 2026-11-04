<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { feedBuffer, isUserBlocked, shouldShowSourceBadges, store } from '../lib/store'
import type { ChatMsg } from '../lib/danmaku'

const canvas = ref<HTMLCanvasElement | null>(null)

/**
 * 预渲染好的弹幕贴图。
 *
 * 弹幕每帧都在移动，如果每帧都 fillText / strokeText + 设置字体，
 * 弹幕多的时候主线程会被文字排版吃掉，表现出来就是掉帧。
 * 这里在弹幕创建时把「气泡 + 文字 + 描边 / 阴影」烘焙成一张小位图，
 * 之后每帧只做 drawImage。相同文字复用同一张图（密集房间大量 666）。
 */
interface Sprite {
  canvas: HTMLCanvasElement
  /** 正文 + 气泡的宽度，用于移动和点击命中 */
  w: number
  /** 贴图画布尺寸与相对 it.x / it.top 的偏移（含描边 / 阴影留白） */
  sw: number
  sh: number
  sx: number
  sy: number
}

interface Item {
  msg: ChatMsg
  text: string
  color: string
  lane: number
  x: number
  w: number
  top: number
  speed: number
  born: number
  sticky: boolean
  /** 多路弹幕的来源气泡 */
  badge: string
  badgeColor: string
  showBadge: boolean
  /** 鼠标悬停 / 点击后冻结，不再飘动 */
  frozen: boolean
  sprite: Sprite | null
}

interface DmHit {
  msg: ChatMsg
  x: number
  y: number
  w: number
  h: number
  bottom: number
}

const items: Item[] = []
const spriteCache = new Map<string, Sprite>()
/** 缓存上限：密集弹幕下大量重复文本能命中，又不至于把内存堆满 */
const SPRITE_CACHE_MAX = 64

let raf = 0
let running = false
let ctx: CanvasRenderingContext2D | null = null
let laneH = 24
let ro: ResizeObserver | null = null
let lastTick = 0
let lastWidth = 0
/** 画布的 CSS 尺寸，避免每帧读 clientWidth / clientHeight 触发布局查询 */
let cssW = 0
let cssH = 0
/** 当前设备像素比，贴图绘制时对齐设备像素，避免文字被插值糊掉 */
let pxRatio = 1
let frozenSeq = 0
/** 正文字心到基线的距离，layout 时按实际字体量一次，气泡靠它对齐 */
let inkOffset = 18 * 0.35
/** 字号 / 描边 / DPR 变化时标记贴图过期，下一帧统一重建 */
let spritesDirty = false
let lastPaintSig = ''
/** 只用来量文字尺寸，和主画布状态隔离 */
let measureCtx: CanvasRenderingContext2D | null = null

function fontCss(scale: number, bold: boolean) {
  return `${bold ? '600 ' : ''}${Math.round(18 * scale)}px 'PingFang SC', 'HarmonyOS Sans SC', 'Microsoft YaHei', sans-serif`
}

function badgeFontPx(scale: number) {
  // 气泡里的字比弹幕正文小一号，跟着字号缩放
  return Math.max(8, Math.round(10 * scale))
}

function badgeFontCss(scale: number) {
  return `700 ${badgeFontPx(scale)}px 'PingFang SC', 'HarmonyOS Sans SC', 'Microsoft YaHei', sans-serif`
}

/** 气泡高度：和正文的视觉高度对齐，做成细长椭圆 */
function badgeBarH(scale: number): number {
  return Math.max(12, Math.round(18 * scale * 0.86))
}

function colorOf(m: ChatMsg): string {
  if (m.self) return '#ffd166'
  if (m.kind === 'danmu' && typeof m.color === 'number' && m.color >= 0) {
    const hex = (m.color & 0xffffff).toString(16).padStart(6, '0')
    return `#${hex}`
  }
  if (m.kind === 'sc') return '#ffd302'
  if (m.kind === 'guard') return '#7cd4ff'
  if (m.kind === 'gift') return '#ffb0e0'
  return '#9fb3ff'
}

function showSourceBadges(): boolean {
  return shouldShowSourceBadges()
}

function measureContext(): CanvasRenderingContext2D | null {
  if (!measureCtx) {
    const el = document.createElement('canvas')
    el.width = 1
    el.height = 1
    measureCtx = el.getContext('2d')
  }
  return measureCtx
}

/** 用实际字体的字形包围盒算字心，比按 em 估算更准，换字体也不跑偏 */
function measureInkOffset(c: CanvasRenderingContext2D, scale: number, bold: boolean): number {
  c.font = fontCss(scale, bold)
  const m = c.measureText('国')
  const asc = m.actualBoundingBoxAscent
  const desc = m.actualBoundingBoxDescent
  if (!Number.isFinite(asc) || !Number.isFinite(desc) || asc <= 0) return 18 * scale * 0.35
  return (asc - desc) / 2
}

function badgeWidth(c: CanvasRenderingContext2D, text: string, scale: number): number {
  const fs = badgeFontPx(scale)
  c.font = badgeFontCss(scale)
  if (!text) return Math.round(badgeBarH(scale) * 0.62)
  return Math.round(c.measureText(text).width + fs * 1.1)
}

function roundRect(
  c: CanvasRenderingContext2D,
  x: number,
  y: number,
  w: number,
  h: number,
  r: number
) {
  c.beginPath()
  c.moveTo(x + r, y)
  c.arcTo(x + w, y, x + w, y + h, r)
  c.arcTo(x + w, y + h, x, y + h, r)
  c.arcTo(x, y + h, x, y, r)
  c.arcTo(x, y, x + w, y, r)
  c.closePath()
}

/** 把一条弹幕画成位图；文字、气泡和描边都只排版这一次 */
function renderSprite(it: Item): Sprite | null {
  const d = store.settings.danmaku
  const scale = d.fontScale
  const m = measureContext()
  if (!m) return null

  m.font = fontCss(scale, d.bold)
  const textW = m.measureText(it.text).width
  const badgeW = it.showBadge ? badgeWidth(m, it.badge, scale) : 0
  const contentW = textW + (badgeW ? badgeW + 5 : 0)
  // 描边宽度 3 / 阴影 blur 4，留一点边距避免贴图把效果裁掉
  const pad = it.sticky ? 3 : d.style === 'shadow' ? 8 : 3
  const dpr = Math.min(window.devicePixelRatio || 1, 2)
  const sw = Math.ceil(contentW + (it.sticky ? 16 : 0) + pad * 2)
  const sh = Math.ceil(laneH + pad * 2)
  const sprite = document.createElement('canvas')
  sprite.width = Math.max(1, Math.round(sw * dpr))
  sprite.height = Math.max(1, Math.round(sh * dpr))
  const g = sprite.getContext('2d')
  if (!g) return null
  g.setTransform(dpr, 0, 0, dpr, 0, 0)

  // sticky 的背景从 it.top - 2 开始，所以贴图里的基线要再下移 2
  const baseline = pad + laneH * 0.78 + (it.sticky ? 2 : 0)
  let textX = pad

  if (it.sticky) {
    g.fillStyle = 'rgba(0,0,0,0.55)'
    roundRect(g, pad, pad, contentW + 16, laneH, 6)
    g.fill()
    textX = pad + 8
  } else if (badgeW) {
    const barH = badgeBarH(scale)
    const centerY = pad + laneH * 0.78 - inkOffset
    // 只有颜色没有字时缩成一个小圆点，有字时是细长椭圆
    const bw = it.badge ? Math.max(badgeW, barH) : Math.round(barH * 0.62)
    const bh = it.badge ? barH : bw
    g.fillStyle = it.badgeColor || '#d97757'
    roundRect(g, pad, centerY - bh / 2, bw, bh, bh / 2)
    g.fill()
    if (it.badge) {
      g.fillStyle = '#ffffff'
      g.font = badgeFontCss(scale)
      // 气泡文字也按字形包围盒居中，别用 middle 留出字体的上下不对称空隙
      const bm = g.measureText(it.badge)
      const asc = bm.actualBoundingBoxAscent
      const desc = bm.actualBoundingBoxDescent
      const ty = Number.isFinite(asc) && asc > 0 ? centerY + (asc - desc) / 2 : centerY
      g.textAlign = 'center'
      g.fillText(it.badge, pad + bw / 2, ty)
      g.textAlign = 'left'
    }
    textX = pad + badgeW + 5
  }

  g.font = fontCss(scale, d.bold)
  if (!it.sticky) {
    if (d.style === 'outline') {
      g.lineWidth = 3
      g.strokeStyle = 'rgba(0,0,0,0.85)'
      g.strokeText(it.text, textX, baseline)
    } else if (d.style === 'shadow') {
      g.shadowColor = 'rgba(0,0,0,0.75)'
      g.shadowBlur = 4
      g.shadowOffsetY = 1
    }
  }
  g.fillStyle = it.color
  g.fillText(it.text, textX, baseline)

  return {
    canvas: sprite,
    w: contentW,
    sw,
    sh,
    // sticky 的背景比正文左右各宽 8 / 上宽 2，贴图偏移跟着补偿
    sx: it.sticky ? -pad - 8 : -pad,
    sy: it.sticky ? -pad - 2 : -pad,
  }
}

function spriteFor(it: Item): Sprite | null {
  const d = store.settings.danmaku
  const dpr = Math.min(window.devicePixelRatio || 1, 2)
  const key = [
    it.sticky ? 's' : 'f',
    Math.round(d.fontScale * 100),
    d.bold ? 1 : 0,
    d.style,
    dpr,
    it.color,
    it.showBadge ? 1 : 0,
    it.badge,
    it.badgeColor,
    it.text,
  ].join('|')

  const hit = spriteCache.get(key)
  if (hit) {
    // Map 的插入顺序当 LRU 用：命中后挪到队尾
    spriteCache.delete(key)
    spriteCache.set(key, hit)
    return hit
  }

  const built = renderSprite(it)
  if (built) {
    spriteCache.set(key, built)
    if (spriteCache.size > SPRITE_CACHE_MAX) {
      const oldest = spriteCache.keys().next().value
      if (oldest !== undefined) spriteCache.delete(oldest)
    }
  }
  return built
}

function applySprite(it: Item) {
  const sprite = spriteFor(it)
  if (!sprite) return
  it.sprite = sprite
  it.w = sprite.w
}

function layout() {
  const el = canvas.value
  if (!el) return
  const dpr = Math.min(window.devicePixelRatio || 1, 2)
  const rect = el.getBoundingClientRect()
  if (!rect.width || !rect.height) return
  cssW = rect.width
  cssH = rect.height
  pxRatio = dpr
  el.width = Math.round(cssW * dpr)
  el.height = Math.round(cssH * dpr)
  if (!ctx) ctx = el.getContext('2d')
  ctx?.setTransform(dpr, 0, 0, dpr, 0, 0)

  const d = store.settings.danmaku
  laneH = 20 * d.fontScale * 1.35
  if (ctx) inkOffset = measureInkOffset(ctx, d.fontScale, d.bold)
  const widthChanged = lastWidth > 0 && Math.abs(cssW - lastWidth) > 1
  const ratio = widthChanged ? cssW / lastWidth : 1
  lastWidth = cssW
  for (const it of items) {
    it.top = it.lane * laneH + (it.sticky ? 4 : 2)
    // 画布尺寸变化（全屏 / 切画面）时按比例换算位置和速度：
    // 已经在飘的弹幕原地继续走，不会整屏回炉重来
    if (!it.sticky && !it.frozen && widthChanged) {
      it.x *= ratio
      it.speed *= ratio
    }
  }

  // 字号 / 描边 / DPR 变了才重建贴图。拖设置里的滑块时可能一帧触发多次，
  // 这里只打标记，由 tick 每帧统一处理一次。
  const sig = `${d.fontScale}|${d.bold ? 1 : 0}|${d.style}|${dpr}`
  if (sig !== lastPaintSig) {
    lastPaintSig = sig
    spritesDirty = true
  }
}

function spawn() {
  const el = canvas.value
  if (!el || !ctx || cssW <= 0 || cssH <= 0) return
  const d = store.settings.danmaku
  const now = performance.now()
  const withBadges = showSourceBadges()
  const maxLane = Math.max(1, Math.floor((cssH * d.area) / laneH))
  const speed = (cssW / 9) * d.speed
  while (feedBuffer.length) {
    const m = feedBuffer.shift()!
    if (!d.enabled || !m.text || m.history) continue
    // SC 由画面上的 SuperChatOverlay 负责，不再挤在弹幕轨道里
    if (m.kind === 'sc') continue
    // 礼物提示不飘；被屏蔽用户的弹幕也不飘
    if (m.kind === 'gift' || isUserBlocked(m)) continue
    const lower = m.text.toLowerCase()
    if (d.blocked.some((w) => w && lower.includes(w.toLowerCase()))) continue

    const text =
      d.showName && m.uname && m.kind === 'danmu' ? `${m.uname}：${m.text}` : m.text
    const sticky = m.kind === 'guard'
    const showBadge = withBadges && m.kind === 'danmu' && !!m.sourceColor
    const it: Item = {
      msg: m,
      text,
      color: colorOf(m),
      lane: 0,
      x: 0,
      w: 0,
      top: 0,
      speed: 0,
      born: now,
      sticky,
      badge: showBadge ? m.badge || '' : '',
      badgeColor: showBadge ? m.sourceColor || '' : '',
      showBadge,
      frozen: false,
      sprite: null,
    }

    if (sticky) {
      let lane = 0
      while (
        items.some((x) => x.sticky && x.lane === lane && now - x.born < 4200) &&
        lane < 5
      ) {
        lane++
      }
      it.lane = lane
      it.top = lane * laneH + 4
      applySprite(it)
      items.push(it)
    } else {
      let lane = 0
      let found = false
      for (let l = 0; l < maxLane; l++) {
        let last: Item | undefined
        for (let i = items.length - 1; i >= 0; i--) {
          if (!items[i].sticky && items[i].lane === l) {
            last = items[i]
            break
          }
        }
        if (!last) {
          lane = l
          found = true
          break
        }
        // 冻结的弹幕停在原地，这条轨道不能再放新弹幕
        if (last.frozen) continue
        // last.x 已经在每帧里随速度左移，这里只需判断上一条的尾巴是否已让出
        // 屏幕右侧的出生位置；再减一次 elapsed * speed 会把移动量算两遍，
        // 导致一条轨道很久都腾不出来，弹幕看起来又稀又重叠。
        if (last.x + last.w + 24 < cssW) {
          lane = l
          found = true
          break
        }
      }
      if (!found) lane = Math.floor(Math.random() * maxLane)
      it.lane = lane
      it.x = cssW + 8
      it.top = lane * laneH + 2
      it.speed = speed
      applySprite(it)
      items.push(it)
    }
    if (items.length > 260) {
      const removed = items.splice(0, items.length - 260)
      if (removed.some((x) => x.msg.seq === frozenSeq)) frozenSeq = 0
    }
  }
}

function drawItem(c: CanvasRenderingContext2D, it: Item, alpha: number) {
  if (it.sticky) it.x = (cssW - it.w) / 2

  if (it.frozen) {
    c.globalAlpha = Math.min(1, alpha + 0.1)
    c.fillStyle = 'rgba(20, 17, 14, 0.62)'
    roundRect(c, it.x - 6, it.top - 2, it.w + 12, laneH + 2, 7)
    c.fill()
  }

  const sprite = it.sprite
  if (!sprite) return
  c.globalAlpha = alpha
  // 位置和尺寸都对齐设备像素：贴图与画布 1:1 映射，drawImage 不会做插值
  const x = Math.round((it.x + sprite.sx) * pxRatio) / pxRatio
  const y = Math.round((it.top + sprite.sy) * pxRatio) / pxRatio
  const w = Math.round(sprite.sw * pxRatio) / pxRatio
  const h = Math.round(sprite.sh * pxRatio) / pxRatio
  c.drawImage(sprite.canvas, x, y, w, h)
}

function clearScene(c?: CanvasRenderingContext2D | null) {
  items.length = 0
  feedBuffer.length = 0
  frozenSeq = 0
  spritesDirty = false
  if (c && cssW > 0 && cssH > 0) c.clearRect(0, 0, cssW, cssH)
}

function sleep() {
  running = false
  if (raf) cancelAnimationFrame(raf)
  raf = 0
}

function wake() {
  if (running) return
  if (!canvas.value || store.view !== 'live' || !store.settings.danmaku.enabled) return
  if (document.hidden) return
  // 暂停期间积压的弹幕不再补播，避免回到前台时整屏井喷
  feedBuffer.length = 0
  lastTick = performance.now()
  running = true
  raf = requestAnimationFrame(tick)
}

function tick(now: number) {
  if (!running) return
  const el = canvas.value
  const c = ctx
  if (!el || !c) {
    running = false
    return
  }
  const d = store.settings.danmaku

  // 回主页 / 切后台 / 关弹幕时真正停下 RAF，别让空循环继续占主线程和电量
  if (store.view !== 'live' || !d.enabled || document.hidden) {
    clearScene(c)
    sleep()
    return
  }

  const dt = Math.min((now - (lastTick || now)) / 1000, 0.1)
  lastTick = now

  if (spritesDirty) {
    spritesDirty = false
    for (const it of items) applySprite(it)
  }
  spawn()

  c.clearRect(0, 0, cssW, cssH)

  for (let i = items.length - 1; i >= 0; i--) {
    const it = items[i]
    if (it.sticky) {
      const age = now - it.born
      if (age > 4200) {
        items.splice(i, 1)
        continue
      }
      const fade = age > 3800 ? 1 - (age - 3800) / 400 : 1
      drawItem(c, it, d.alpha * fade)
    } else {
      if (!it.frozen) it.x -= it.speed * dt
      if (!it.frozen && it.x + it.w < -20) {
        items.splice(i, 1)
        if (it.msg.seq === frozenSeq) frozenSeq = 0
        continue
      }
      drawItem(c, it, d.alpha)
    }
  }
  raf = requestAnimationFrame(tick)
}

function itemAt(x: number, y: number): Item | null {
  for (let i = items.length - 1; i >= 0; i--) {
    const it = items[i]
    if (it.sticky || it.msg.kind !== 'danmu') continue
    if (x >= it.x - 4 && x <= it.x + it.w + 4 && y >= it.top - 2 && y <= it.top + laneH) {
      return it
    }
  }
  return null
}

function hitOf(it: Item): DmHit {
  return {
    msg: it.msg,
    x: it.x,
    y: it.top,
    w: it.w,
    h: laneH,
    bottom: it.top + laneH,
  }
}

/** 画面上的弹幕点击命中测试（LiveView 把坐标换算成 canvas 坐标后调用） */
function hitTest(x: number, y: number): ChatMsg | null {
  return itemAt(x, y)?.msg || null
}

function hitRect(x: number, y: number): DmHit | null {
  const it = itemAt(x, y)
  return it ? hitOf(it) : null
}

function freezeItem(it: Item): DmHit {
  release()
  it.frozen = true
  frozenSeq = it.msg.seq
  return hitOf(it)
}

/** 冻结鼠标下的这条弹幕（同时只冻结一条），返回它的位置给快捷条定位 */
function freezeAt(x: number, y: number): DmHit | null {
  const it = itemAt(x, y)
  return it ? freezeItem(it) : null
}

/**
 * 按 seq 冻结。电脑端悬停有 320ms 延迟，期间弹幕还在移动，
 * 用开始悬停时的坐标再去命中会打偏，所以这里按 seq 精确冻结。
 */
function freezeSeq(seq: number): DmHit | null {
  const it = items.find((x) => x.msg.seq === seq)
  return it ? freezeItem(it) : null
}

function release(seq?: number) {
  for (const it of items) {
    if (it.frozen && (!seq || it.msg.seq === seq)) it.frozen = false
  }
  if (!seq || frozenSeq === seq) frozenSeq = 0
}

function rectFor(seq: number): DmHit | null {
  const it = items.find((x) => x.msg.seq === seq)
  return it ? hitOf(it) : null
}

function clear() {
  items.length = 0
  frozenSeq = 0
}

defineExpose({ hitTest, hitRect, freezeAt, freezeSeq, release, rectFor, clear })

function onVisibilityChange() {
  if (document.hidden) {
    sleep()
    clearScene(ctx)
  } else {
    wake()
  }
}

onMounted(() => {
  const el = canvas.value!
  ctx = el.getContext('2d')
  ro = new ResizeObserver(layout)
  ro.observe(el)
  layout()
  lastTick = performance.now()
  document.addEventListener('visibilitychange', onVisibilityChange)
  wake()
})

onBeforeUnmount(() => {
  sleep()
  ro?.disconnect()
  document.removeEventListener('visibilitychange', onVisibilityChange)
})

watch(
  () => store.view,
  (v) => {
    if (v === 'live') wake()
    else {
      sleep()
      clearScene(ctx)
    }
  }
)

watch(
  () => store.settings.danmaku,
  () => {
    layout()
    // 刚屏蔽的用户，屏幕上还在飘的弹幕也立刻收掉
    for (let i = items.length - 1; i >= 0; i--) {
      if (!isUserBlocked(items[i].msg)) continue
      if (items[i].msg.seq === frozenSeq) frozenSeq = 0
      items.splice(i, 1)
    }
    if (store.settings.danmaku.enabled) wake()
  },
  { deep: true }
)
</script>

<template>
  <canvas ref="canvas" class="dm-canvas"></canvas>
</template>

<style scoped>
.dm-canvas {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  pointer-events: none;
}
</style>
