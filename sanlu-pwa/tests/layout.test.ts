import { describe, expect, it } from 'vitest'
import {
  MINI_ASPECT,
  MINI_MIN_WIDTH,
  cropThroughView,
  cropTransform,
  defaultMiniWidth,
  fitAspectBox,
  isUsableCrop,
  normalizeCrop,
  proportionalResize,
} from '../src/lib/layout'
import type { MiniBox } from '../src/lib/layout'

function box(partial: Partial<MiniBox> = {}): MiniBox {
  return { right: 10, top: 10, width: 320, height: 180, ...partial }
}

describe('normalizeCrop', () => {
  it('任意方向的拖拽都能得到左上角为原点的矩形', () => {
    expect(normalizeCrop(300, 200, 100, 50, 400, 400)).toEqual({
      x: 0.25,
      y: 0.125,
      w: 0.5,
      h: 0.375,
    })
  })

  it('坐标超出容器时夹回 0~1', () => {
    expect(normalizeCrop(-100, -100, 500, 500, 400, 400)).toEqual({
      x: 0,
      y: 0,
      w: 1,
      h: 1,
    })
  })

  it('宽高为 0 时保留最小 1%，避免后续除零', () => {
    const crop = normalizeCrop(200, 200, 200, 200, 400, 400)
    expect(crop.w).toBe(0.01)
    expect(crop.h).toBe(0.01)
  })

  it('容器尺寸异常时不会产生 NaN', () => {
    const crop = normalizeCrop(0, 0, 10, 10, 0, Number.NaN)
    expect(Number.isFinite(crop.x)).toBe(true)
    expect(Number.isFinite(crop.w)).toBe(true)
    expect(crop.w).toBeGreaterThan(0)
  })
})

describe('isUsableCrop', () => {
  it('太小的框不算有效裁切', () => {
    expect(isUsableCrop(null)).toBe(false)
    expect(isUsableCrop({ x: 0, y: 0, w: 0.02, h: 0.5 })).toBe(false)
    expect(isUsableCrop({ x: 0, y: 0, w: 0.05, h: 0.05 })).toBe(true)
  })

  it('阈值可覆盖', () => {
    expect(isUsableCrop({ x: 0, y: 0, w: 0.2, h: 0.2 }, 0.3)).toBe(false)
  })
})

describe('cropTransform', () => {
  it('没有裁切或全图裁切时不加 transform', () => {
    expect(cropTransform(null)).toBe('')
    expect(cropTransform({ x: 0, y: 0, w: 1, h: 1 })).toBe('')
  })

  it('等比放大到覆盖容器，并把裁切框中心对准画面中心', () => {
    expect(cropTransform({ x: 0.25, y: 0.5, w: 0.5, h: 0.25 })).toBe(
      'scale(4.0000) translate(-37.5000%, -50.0000%)'
    )
  })

  it('居中裁切框等比放大到铺满', () => {
    expect(cropTransform({ x: 0.25, y: 0.25, w: 0.5, h: 0.5 })).toBe(
      'scale(2.0000) translate(-25.0000%, -25.0000%)'
    )
  })
})

describe('fitAspectBox', () => {
  it('高度受限时按高度撑满，左右居中留边', () => {
    const b = fitAspectBox({ width: 390, height: 219 }, MINI_ASPECT, 14)
    expect(b.height).toBeCloseTo(191, 5)
    expect(b.width).toBeCloseTo(191 * MINI_ASPECT, 5)
    expect(b.top).toBeCloseTo(14, 5)
    expect(b.left).toBeCloseTo((390 - 191 * MINI_ASPECT) / 2, 5)
  })

  it('宽度受限时按宽度撑满，上下居中留边', () => {
    const b = fitAspectBox({ width: 800, height: 500 }, MINI_ASPECT, 14)
    expect(b.width).toBeCloseTo(772, 5)
    expect(b.height).toBeCloseTo(772 / MINI_ASPECT, 5)
    expect(b.left).toBeCloseTo(14, 5)
    expect(b.top).toBeCloseTo((500 - 772 / MINI_ASPECT) / 2, 5)
  })

  it('安全区会额外压缩可用空间，盒子始终不侵入安全区', () => {
    const safe = { left: 20, right: 20 }
    const b = fitAspectBox({ width: 390, height: 219 }, MINI_ASPECT, 14, safe)
    expect(b.left).toBeGreaterThanOrEqual(14 + safe.left - 0.001)
    expect(b.left + b.width).toBeLessThanOrEqual(390 - 14 - safe.right + 0.001)
  })

  it('盒子始终维持目标比例', () => {
    for (const bounds of [
      { width: 390, height: 219 },
      { width: 844, height: 390 },
      { width: 1366, height: 768 },
      { width: 200, height: 600 },
    ]) {
      const b = fitAspectBox(bounds, MINI_ASPECT, 14)
      expect(b.width / b.height).toBeCloseTo(MINI_ASPECT, 5)
      expect(b.width).toBeGreaterThan(0)
      expect(b.height).toBeGreaterThan(0)
    }
  })
})

describe('cropThroughView', () => {
  it('原始视图下保持裁切框不变', () => {
    const crop = { x: 0.25, y: 0.25, w: 0.5, h: 0.5 }
    expect(cropThroughView(crop, 1, 0, 0, 360, 180)).toEqual(crop)
  })

  it('2 倍放大后把屏幕上的框还原成完整画面里的区域', () => {
    const crop = cropThroughView({ x: 0.25, y: 0.25, w: 0.5, h: 0.5 }, 2, 0, 0, 360, 180)
    expect(crop!.x).toBeCloseTo(0.375, 5)
    expect(crop!.y).toBeCloseTo(0.375, 5)
    expect(crop!.w).toBeCloseTo(0.25, 5)
    expect(crop!.h).toBeCloseTo(0.25, 5)
  })

  it('平移也会一起反向抵消', () => {
    const crop = cropThroughView({ x: 0.25, y: 0.25, w: 0.5, h: 0.5 }, 1, 36, 18, 360, 180)
    expect(crop!.x).toBeCloseTo(0.15, 5)
    expect(crop!.y).toBeCloseTo(0.15, 5)
    expect(crop!.w).toBeCloseTo(0.5, 5)
    expect(crop!.h).toBeCloseTo(0.5, 5)
  })

  it('还原结果夹在 0~1 内', () => {
    const crop = cropThroughView({ x: 0, y: 0, w: 0.5, h: 0.5 }, 3, 0, 0, 360, 180)
    expect(crop!.x).toBeGreaterThanOrEqual(0)
    expect(crop!.y).toBeGreaterThanOrEqual(0)
    expect(crop!.x + crop!.w).toBeLessThanOrEqual(1)
    expect(crop!.y + crop!.h).toBeLessThanOrEqual(1)
  })

  it('没有裁切框时返回 null', () => {
    expect(cropThroughView(null, 2, 0, 0, 360, 180)).toBeNull()
  })
})

describe('proportionalResize', () => {
  it('拖右下角缩小：左边缘和上边缘保持不动', () => {
    const start = box()
    const next = proportionalResize(start, 'br', -160, -90, { width: 1280, height: 720 })
    expect(next.width).toBe(160)
    expect(next.height).toBeCloseTo(160 / MINI_ASPECT)
    expect(next.right).toBe(start.right + start.width - next.width)
    expect(next.top).toBe(start.top)
  })

  it('拖左下角向左放大：右边缘和上边缘保持不动', () => {
    const start = box()
    const next = proportionalResize(start, 'bl', -160, 0, { width: 1280, height: 720 })
    expect(next.width).toBe(480)
    expect(next.height).toBeCloseTo(480 / MINI_ASPECT)
    expect(next.right).toBe(start.right)
    expect(next.top).toBe(start.top)
  })

  it('拖左上角缩小：右边缘和下边缘保持不动', () => {
    const start = box()
    const next = proportionalResize(start, 'tl', 100, 0, { width: 1280, height: 720 })
    expect(next.width).toBe(220)
    expect(next.height).toBeCloseTo(220 / MINI_ASPECT)
    expect(next.right).toBe(start.right)
    expect(next.top + next.height).toBeCloseTo(start.top + start.height)
  })

  it('始终维持 16:9', () => {
    for (const corner of ['tl', 'tr', 'bl', 'br'] as const) {
      const next = proportionalResize(box(), corner, 37, -21, { width: 1280, height: 720 })
      expect(next.width / next.height).toBeCloseTo(MINI_ASPECT)
    }
  })

  it('不小于最小宽度，也不超出画面区域', () => {
    const shrunk = proportionalResize(box(), 'br', -5000, -5000, { width: 1280, height: 720 })
    expect(shrunk.width).toBe(MINI_MIN_WIDTH)

    const grown = proportionalResize(box(), 'bl', -5000, 0, { width: 800, height: 600 })
    expect(grown.width).toBeLessThanOrEqual(800 - grown.right - 8)
    expect(grown.top).toBeGreaterThanOrEqual(8)
    expect(grown.top + grown.height).toBeLessThanOrEqual(600 - 8)
  })

  it('靠近顶边时拖上角放大不会跑出画面', () => {
    const next = proportionalResize(
      box({ top: 10 }),
      'tl',
      -2000,
      0,
      { width: 1280, height: 720 }
    )
    expect(next.top).toBeGreaterThanOrEqual(8)
    expect(next.height).toBeCloseTo(next.width / MINI_ASPECT)
  })
})

describe('defaultMiniWidth', () => {
  it('手机上有下限，避免第一次打开太小', () => {
    expect(defaultMiniWidth(320)).toBe(220)
    expect(defaultMiniWidth(375)).toBe(220)
  })

  it('按画面宽度比例增长', () => {
    expect(defaultMiniWidth(600)).toBe(252)
    expect(defaultMiniWidth(800)).toBe(336)
  })

  it('大屏上有上限', () => {
    expect(defaultMiniWidth(1600)).toBe(420)
  })
})
