<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { feedBuffer, shouldShowSourceBadges, store } from '../lib/store'
import type { ChatMsg } from '../lib/danmaku'

const canvas = ref<HTMLCanvasElement | null>(null)

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
  badgeW: number
  badgeFont: number
  /** 鼠标悬停 / 点击后冻结，不再飘动 */
  frozen: boolean
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
let raf = 0
let ctx: CanvasRenderingContext2D | null = null
let laneH = 24
let ro: ResizeObserver | null = null
let lastTick = 0
let lastWidth = 0
let frozenSeq = 0
/** 正文字心到基线的距离，layout 时按实际字体量一次，气泡靠它对齐 */
let inkOffset = 18 * 0.35

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

function layout() {
  const el = canvas.value
  if (!el) return
  const dpr = Math.min(window.devicePixelRatio || 1, 2)
  const rect = el.getBoundingClientRect()
  if (!rect.width || !rect.height) return
  el.width = Math.round(rect.width * dpr)
  el.height = Math.round(rect.height * dpr)
  if (!ctx) ctx = el.getContext('2d')
  ctx?.setTransform(dpr, 0, 0, dpr, 0, 0)

  const d = store.settings.danmaku
  laneH = 20 * d.fontScale * 1.35
  if (ctx) inkOffset = measureInkOffset(ctx, d.fontScale, d.bold)
  const widthChanged = lastWidth > 0 && Math.abs(rect.width - lastWidth) > 1
  const ratio = widthChanged ? rect.width / lastWidth : 1
  lastWidth = rect.width
  for (const it of items) {
    if (ctx) {
      ctx.font = fontCss(d.fontScale, d.bold)
      it.w = ctx.measureText(it.text).width
      if (it.badgeW) {
        it.badgeW = badgeWidth(ctx, it.badge, d.fontScale)
        it.badgeFont = badgeFontPx(d.fontScale)
        it.w += it.badgeW + 5
      }
    }
    it.top = it.lane * laneH + 2
    // 画布尺寸变化（全屏 / 切画面）时按比例换算位置和速度：
    // 已经在飘的弹幕原地继续走，不会整屏回炉重来
    if (!it.sticky && !it.frozen && widthChanged) {
      it.x *= ratio
      it.speed *= ratio
    }
  }
}

function spawn() {
  const el = canvas.value
  const c = ctx
  if (!el || !c) return
  const d = store.settings.danmaku
  const now = performance.now()
  const withBadges = showSourceBadges()
  while (feedBuffer.length) {
    const m = feedBuffer.shift()!
    if (!d.enabled || !m.text || m.history) continue
    // SC 由画面上的 SuperChatOverlay 负责，不再挤在弹幕轨道里
    if (m.kind === 'sc') continue
    const lower = m.text.toLowerCase()
    if (d.blocked.some((w) => w && lower.includes(w.toLowerCase()))) continue

    const text =
      d.showName && m.uname && m.kind === 'danmu' ? `${m.uname}：${m.text}` : m.text
    c.font = fontCss(d.fontScale, d.bold)
    const textW = c.measureText(text).width
    const showBadge = withBadges && m.kind === 'danmu' && !!m.sourceColor
    const badge = showBadge ? m.badge || '' : ''
    const badgeColor = showBadge ? m.sourceColor || '' : ''
    const badgeW = showBadge ? badgeWidth(c, badge, d.fontScale) : 0
    const w = textW + (badgeW ? badgeW + 5 : 0)
    const color = colorOf(m)
    const sticky = m.kind === 'guard'

    if (sticky) {
      let lane = 0
      while (
        items.some((it) => it.sticky && it.lane === lane && now - it.born < 4200) &&
        lane < 5
      ) {
        lane++
      }
      items.push({
        msg: m,
        text,
        color,
        lane,
        x: 0,
        w,
        top: lane * laneH + 4,
        speed: 0,
        born: now,
        sticky: true,
        badge: '',
        badgeColor: '',
        badgeW: 0,
        badgeFont: badgeFontPx(d.fontScale),
        frozen: false,
      })
    } else {
      const maxLane = Math.max(1, Math.floor((el.clientHeight * d.area) / laneH))
      let lane = 0
      const width = el.clientWidth
      const speed = (width / 9) * d.speed
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
        const elapsed = (now - last.born) / 1000
        if (last.x + last.w + 24 < width - elapsed * speed) {
          lane = l
          found = true
          break
        }
      }
      if (!found) lane = Math.floor(Math.random() * maxLane)
      items.push({
        msg: m,
        text,
        color,
        lane,
        x: width + 8,
        w,
        top: lane * laneH + 2,
        speed,
        born: now,
        sticky: false,
        badge,
        badgeColor,
        badgeW,
        badgeFont: badgeFontPx(d.fontScale),
        frozen: false,
      })
    }
    if (items.length > 260) {
      const removed = items.splice(0, items.length - 260)
      if (removed.some((it) => it.msg.seq === frozenSeq)) frozenSeq = 0
    }
  }
}

function drawBadge(c: CanvasRenderingContext2D, it: Item, alpha: number) {
  const scale = store.settings.danmaku.fontScale
  const barH = badgeBarH(scale)
  // 和正文的字心对齐：正文基线在 top + laneH * 0.78
  const centerY = it.top + laneH * 0.78 - inkOffset
  const x = it.x
  // 只有颜色没有字时缩成一个小圆点，有字时是细长椭圆
  const w = it.badge ? Math.max(it.badgeW, barH) : Math.round(barH * 0.62)
  const h = it.badge ? barH : w
  c.globalAlpha = Math.min(1, alpha + 0.06)
  c.fillStyle = it.badgeColor || '#d97757'
  roundRect(c, x, centerY - h / 2, w, h, h / 2)
  c.fill()
  if (it.badge) {
    c.globalAlpha = alpha
    c.fillStyle = '#ffffff'
    c.font = badgeFontCss(scale)
    // 气泡文字也按字形包围盒居中，别用 middle 留出字体的上下不对称空隙
    const m = c.measureText(it.badge)
    const asc = m.actualBoundingBoxAscent
    const desc = m.actualBoundingBoxDescent
    const ty = Number.isFinite(asc) && asc > 0 ? centerY + (asc - desc) / 2 : centerY
    c.textAlign = 'center'
    c.textBaseline = 'alphabetic'
    c.fillText(it.badge, x + w / 2, ty)
    c.textAlign = 'left'
    // 画完气泡把字体恢复成正文字体，否则后面的弹幕文字会沿用气泡的小字号
    c.font = fontCss(scale, store.settings.danmaku.bold)
  }
}

function drawItem(c: CanvasRenderingContext2D, it: Item, alpha: number) {
  const d = store.settings.danmaku
  const textX = it.x + (it.badgeW ? it.badgeW + 5 : 0)
  c.globalAlpha = alpha
  c.font = fontCss(d.fontScale, d.bold)
  if (it.sticky) {
    const x = (canvas.value!.clientWidth - it.w) / 2
    it.x = x
    c.fillStyle = 'rgba(0,0,0,0.55)'
    roundRect(c, x - 8, it.top - 2, it.w + 16, laneH, 6)
    c.fill()
    c.fillStyle = it.color
    c.fillText(it.text, x, it.top + laneH * 0.78)
    return
  }

  if (it.frozen) {
    c.globalAlpha = Math.min(1, alpha + 0.1)
    c.fillStyle = 'rgba(20, 17, 14, 0.62)'
    roundRect(c, it.x - 6, it.top - 2, it.w + 12, laneH + 2, 7)
    c.fill()
    c.globalAlpha = alpha
  }

  if (it.badgeW) drawBadge(c, it, alpha)

  if (d.style === 'outline') {
    c.lineWidth = 3
    c.strokeStyle = 'rgba(0,0,0,0.85)'
    c.strokeText(it.text, textX, it.top + laneH * 0.78)
  } else if (d.style === 'shadow') {
    c.shadowColor = 'rgba(0,0,0,0.75)'
    c.shadowBlur = 4
    c.shadowOffsetY = 1
  }
  c.fillStyle = it.color
  c.fillText(it.text, textX, it.top + laneH * 0.78)
  c.shadowBlur = 0
  c.shadowOffsetY = 0
}

function tick(now: number) {
  const el = canvas.value
  const c = ctx
  if (!el || !c) {
    raf = requestAnimationFrame(tick)
    return
  }
  const d = store.settings.danmaku
  const dt = Math.min((now - (lastTick || now)) / 1000, 0.1)
  lastTick = now
  c.clearRect(0, 0, el.clientWidth, el.clientHeight)

  // 回主页后播放器在后台：不画弹幕、丢弃积压，省一点 CPU / 电量
  if (store.view !== 'live') {
    feedBuffer.length = 0
    items.length = 0
    frozenSeq = 0
    raf = requestAnimationFrame(tick)
    return
  }

  if (!d.enabled) {
    // 关闭弹幕时立刻清屏，并丢弃积压的弹幕，避免重新打开时井喷
    feedBuffer.length = 0
    items.length = 0
    frozenSeq = 0
    raf = requestAnimationFrame(tick)
    return
  }

  spawn()
  c.globalAlpha = d.alpha

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

onMounted(() => {
  const el = canvas.value!
  ctx = el.getContext('2d')
  ro = new ResizeObserver(layout)
  ro.observe(el)
  layout()
  lastTick = performance.now()
  raf = requestAnimationFrame(tick)
})

onBeforeUnmount(() => {
  cancelAnimationFrame(raf)
  ro?.disconnect()
})

watch(
  () => store.settings.danmaku,
  () => layout(),
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
