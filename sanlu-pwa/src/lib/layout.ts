/**
 * 解说小窗的纯几何计算：框选裁切、按角等比缩放。
 * 不依赖 DOM，LiveView 只负责把指针坐标传进来。
 */

export interface CropRect {
  /** 0~1 的归一化坐标，相对小窗显示区域 */
  x: number
  y: number
  w: number
  h: number
}

export interface BoxSize {
  width: number
  height: number
}

/** 小窗用 right/top 定位，宽度定高（16:9） */
export interface MiniBox {
  right: number
  top: number
  width: number
  height: number
}

export type MiniCorner = 'tl' | 'tr' | 'bl' | 'br'

export const MINI_ASPECT = 16 / 9
export const MINI_MIN_WIDTH = 160

function clamp01(n: number): number {
  if (!Number.isFinite(n)) return 0
  return Math.max(0, Math.min(1, n))
}

function clamp(n: number, min: number, max: number): number {
  if (!Number.isFinite(n)) return min
  return Math.max(min, Math.min(max, n))
}

/** 把两个像素点（顺序任意）变成归一化裁切框，最小保留 1% 防止除零 */
export function normalizeCrop(
  x1: number,
  y1: number,
  x2: number,
  y2: number,
  width: number,
  height: number
): CropRect {
  const w = Math.max(1, width)
  const h = Math.max(1, height)
  const ax = clamp01(x1 / w)
  const ay = clamp01(y1 / h)
  const bx = clamp01(x2 / w)
  const by = clamp01(y2 / h)
  return {
    x: Math.min(ax, bx),
    y: Math.min(ay, by),
    w: Math.max(0.01, Math.abs(bx - ax)),
    h: Math.max(0.01, Math.abs(by - ay)),
  }
}

/** 宽高够不够成为一个有意义的裁切框 */
export function isUsableCrop(crop: CropRect | null, min = 0.05): crop is CropRect {
  return !!crop && crop.w >= min && crop.h >= min
}

/**
 * CSS transform：把裁切区域等比放大铺满容器，保持画面不变形。
 *
 * 取 max(1/w, 1/h) 保证两个方向都覆盖容器；多出来的方向以裁切框中心为准裁掉。
 * CSS 的 transform 从右往左作用，所以顺序是 scale(...) translate(...)；
 * transform-origin 必须是 0 0，百分比 translate 相对容器自身尺寸。
 */
export function cropTransform(crop: CropRect | null): string {
  if (!crop) return ''
  if (crop.w >= 0.999 && crop.h >= 0.999 && crop.x <= 0.001 && crop.y <= 0.001) return ''
  const scale = Math.max(1 / crop.w, 1 / crop.h)
  const tx = 0.5 / scale - (crop.x + crop.w / 2)
  const ty = 0.5 / scale - (crop.y + crop.h / 2)
  return `scale(${scale.toFixed(4)}) translate(${(tx * 100).toFixed(4)}%, ${(ty * 100).toFixed(4)}%)`
}

/**
 * 拖动小窗四个角等比缩放。
 *
 * - 拖右角时左边缘不动，拖左角时右边缘不动；
 * - 拖上角时下边缘不动，拖下角时上边缘不动；
 * - 始终维持 16:9，并且不超出画面区域。
 */
export function proportionalResize(
  start: MiniBox,
  corner: MiniCorner,
  dx: number,
  _dy: number,
  bounds: BoxSize,
  minWidth = MINI_MIN_WIDTH
): MiniBox {
  const pad = 8
  const left = bounds.width - start.right - start.width
  const bottom = start.top + start.height

  const movingRight = corner.endsWith('r')
  const movingTop = corner.startsWith('t')

  let width = start.width + (movingRight ? dx : -dx)

  const maxByWidth = movingRight
    ? bounds.width - left - pad
    : bounds.width - start.right - pad
  const maxByHeight = movingTop
    ? (bottom - pad) * MINI_ASPECT
    : (bounds.height - start.top - pad) * MINI_ASPECT

  const maxWidth = Math.max(40, Math.min(maxByWidth, maxByHeight, bounds.width - pad * 2))
  width = clamp(width, Math.min(minWidth, maxWidth), maxWidth)
  const height = width / MINI_ASPECT

  const right = movingRight
    ? start.right - (width - start.width)
    : start.right
  const top = movingTop ? bottom - height : start.top

  return {
    right: clamp(right, pad, Math.max(pad, bounds.width - width - pad)),
    top: clamp(top, pad, Math.max(pad, bounds.height - height - pad)),
    width,
    height,
  }
}

/** 首次打开小窗的默认宽度：手机别太小（不好拖），大屏也别太夸张 */
export function defaultMiniWidth(zoneWidth: number): number {
  const byRatio = zoneWidth * 0.42
  return Math.round(Math.min(420, Math.max(220, byRatio)))
}
