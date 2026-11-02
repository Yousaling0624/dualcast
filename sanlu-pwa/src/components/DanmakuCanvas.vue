<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { feedBuffer, store } from '../lib/store'
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
}

const items: Item[] = []
let raf = 0
let ctx: CanvasRenderingContext2D | null = null
let laneH = 24
let ro: ResizeObserver | null = null
let lastTick = 0
let lastWidth = 0

function fontCss(scale: number, bold: boolean) {
  return `${bold ? '600 ' : ''}${Math.round(18 * scale)}px 'PingFang SC', 'HarmonyOS Sans SC', 'Microsoft YaHei', sans-serif`
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
  if (ctx) ctx.font = fontCss(d.fontScale, d.bold)
  const widthChanged = Math.abs(rect.width - lastWidth) > 1
  lastWidth = rect.width
  for (const it of items) {
    it.w = ctx ? ctx.measureText(it.text).width : it.w
    it.top = it.lane * laneH + 2
    // 只在画布宽度变化时重新从右侧入场；调透明度/速度不应让弹幕回炉
    if (!it.sticky && widthChanged) it.x = rect.width + Math.random() * 40
  }
}

function spawn() {
  const el = canvas.value
  const c = ctx
  if (!el || !c) return
  const d = store.settings.danmaku
  const now = performance.now()
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
    const w = c.measureText(text).width
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
      })
    }
    if (items.length > 260) items.splice(0, items.length - 260)
  }
}

function drawItem(c: CanvasRenderingContext2D, it: Item, alpha: number) {
  const d = store.settings.danmaku
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

  if (d.style === 'outline') {
    c.lineWidth = 3
    c.strokeStyle = 'rgba(0,0,0,0.85)'
    c.strokeText(it.text, it.x, it.top + laneH * 0.78)
  } else if (d.style === 'shadow') {
    c.shadowColor = 'rgba(0,0,0,0.75)'
    c.shadowBlur = 4
    c.shadowOffsetY = 1
  }
  c.fillStyle = it.color
  c.fillText(it.text, it.x, it.top + laneH * 0.78)
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

  if (!d.enabled) {
    // 关闭弹幕时立刻清屏，并丢弃积压的弹幕，避免重新打开时井喷
    feedBuffer.length = 0
    items.length = 0
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
      it.x -= it.speed * dt
      if (it.x + it.w < -20) {
        items.splice(i, 1)
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

/** 画面上的弹幕点击命中测试（LiveView 把坐标换算成 canvas 坐标后调用） */
function hitTest(x: number, y: number): ChatMsg | null {
  for (let i = items.length - 1; i >= 0; i--) {
    const it = items[i]
    if (x >= it.x - 4 && x <= it.x + it.w + 4 && y >= it.top - 2 && y <= it.top + laneH) {
      return it.msg
    }
  }
  return null
}

function clear() {
  items.length = 0
}

defineExpose({ hitTest, clear })

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