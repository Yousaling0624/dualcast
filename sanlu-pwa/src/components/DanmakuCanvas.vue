<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { feedBuffer, store } from '../lib/store'

const canvas = ref<HTMLCanvasElement | null>(null)

interface Item {
  text: string
  uname: string
  color: string
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

const BILI_COLORS = ['#ffffff', '#fe0302', '#ff7204', '#ffaa02', '#ffd302', '#ffff00', '#a0ee00', '#00cd00', '#00cc5f', '#00cccc', '#007cff', '#0048ff', '#8a2be2', '#ff6699', '#cc272e']

function fontCss(scale: number, bold: boolean) {
  return `${bold ? '600 ' : ''}${Math.round(18 * scale)}px 'PingFang SC', 'HarmonyOS Sans SC', 'Microsoft YaHei', sans-serif`
}

function layout() {
  const el = canvas.value
  if (!el) return
  const dpr = Math.min(window.devicePixelRatio || 1, 2)
  const rect = el.getBoundingClientRect()
  if (!rect.width || !rect.height) return
  el.width = Math.round(rect.width * dpr)
  el.height = Math.round(rect.height * dpr)
  ctx?.setTransform(dpr, 0, 0, dpr, 0, 0)
  laneH = 20 * store.settings.danmaku.fontScale * 1.3
  // 尺寸变了重排
  for (const it of items) {
    if (!it.sticky) it.x = rect.width + Math.random() * 40
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
    if (!d.enabled || !m.text) continue
    const lower = m.text.toLowerCase()
    if (d.blocked.some((w) => w && lower.includes(w.toLowerCase()))) continue
    const text = d.showName && m.uname && m.kind === 'danmu' ? `${m.uname}：${m.text}` : m.text
    c.font = fontCss(d.fontScale, !d.outline)
    const w = c.measureText(text).width
    const color =
      m.kind === 'danmu' && m.color
        ? BILI_COLORS[m.color % BILI_COLORS.length] || '#ffffff'
        : m.kind === 'sc'
          ? '#ffd302'
          : m.kind === 'guard'
            ? '#7cd4ff'
            : m.kind === 'gift'
              ? '#ffb0e0'
              : '#9fb3ff'
    const sticky = m.kind === 'sc' || m.kind === 'guard'
    if (sticky) {
      // 顶部居中：从最上面的空道开始找
      let lane = 0
      while (
        items.some((it) => it.sticky && it.top === lane * laneH && now - it.born < 4200) &&
        lane < 8
      )
        lane++
      items.push({
        text,
        uname: m.uname,
        color,
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
      // 找一条不重叠的道：该道最后一条弹幕已经完全进屏
      for (let l = 0; l < maxLane; l++) {
        const last = [...items]
          .reverse()
          .find((it) => !it.sticky && Math.abs(it.top - (l * laneH + 2)) < laneH / 2)
        if (!last) {
          lane = l
          break
        }
        const elapsed = (now - last.born) / 1000
        if (last.x + last.w + 24 < width - elapsed * speed) {
          lane = l
          break
        }
      }
      items.push({
        text,
        uname: m.uname,
        color,
        x: width + 8,
        w,
        top: lane * laneH + 2,
        speed,
        born: now,
        sticky: false,
      })
    }
  }
}

function tick() {
  const el = canvas.value
  const c = ctx
  if (!el || !c) {
    raf = requestAnimationFrame(tick)
    return
  }
  const d = store.settings.danmaku
  const now = performance.now()
  const dt = Math.min((now - (tick as any).last || 16) / 1000, 0.1)
  ;(tick as any).last = now
  c.clearRect(0, 0, el.clientWidth, el.clientHeight)
  c.font = fontCss(d.fontScale, !d.outline)
  c.globalAlpha = d.alpha
  for (let i = items.length - 1; i >= 0; i--) {
    const it = items[i]
    if (it.sticky) {
      if (now - it.born > 4200) {
        items.splice(i, 1)
        continue
      }
      const alpha = now - it.born > 3800 ? 1 - (now - it.born - 3800) / 400 : 1
      c.globalAlpha = d.alpha * alpha
      const x = (el.clientWidth - it.w) / 2
      c.fillStyle = 'rgba(0,0,0,0.55)'
      roundRect(c, x - 8, it.top - 2, it.w + 16, laneH, 6)
      c.fill()
      c.fillStyle = it.color
      c.fillText(it.text, x, it.top + laneH * 0.78)
      c.globalAlpha = d.alpha
    } else {
      it.x -= it.speed * dt
      if (it.x + it.w < -20) {
        items.splice(i, 1)
        continue
      }
      if (d.outline) {
        c.lineWidth = 3
        c.strokeStyle = 'rgba(0,0,0,0.85)'
        c.strokeText(it.text, it.x, it.top + laneH * 0.78)
      } else {
        c.shadowColor = 'rgba(0,0,0,0.7)'
        c.shadowBlur = 4
        c.shadowOffsetY = 1
      }
      c.fillStyle = it.color
      c.fillText(it.text, it.x, it.top + laneH * 0.78)
      c.shadowBlur = 0
    }
  }
  raf = requestAnimationFrame(tick)
}

function roundRect(c: CanvasRenderingContext2D, x: number, y: number, w: number, h: number, r: number) {
  c.beginPath()
  c.moveTo(x + r, y)
  c.arcTo(x + w, y, x + w, y + h, r)
  c.arcTo(x + w, y + h, x, y + h, r)
  c.arcTo(x, y + h, x, y, r)
  c.arcTo(x, y, x + w, y, r)
  c.closePath()
}

onMounted(() => {
  const el = canvas.value!
  ctx = el.getContext('2d')
  ro = new ResizeObserver(layout)
  ro.observe(el)
  layout()
  ;(tick as any).last = performance.now()
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
