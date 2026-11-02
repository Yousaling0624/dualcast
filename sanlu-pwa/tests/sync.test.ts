import { describe, expect, it } from 'vitest'
import {
  LIVE_EDGE_LATENCY,
  MAX_OFFSET_MS,
  MIN_OFFSET_MS,
  clampOffset,
  measuredLatencyMs,
  nextPauseDeadline,
  offsetAction,
  offsetsForSession,
  remainingPauseMs,
} from '../src/lib/sync'

describe('clampOffset', () => {
  it('把越界值夹到允许范围', () => {
    expect(clampOffset(MAX_OFFSET_MS + 1)).toBe(MAX_OFFSET_MS)
    expect(clampOffset(MIN_OFFSET_MS - 1)).toBe(MIN_OFFSET_MS)
    expect(clampOffset(123.6)).toBe(124)
  })

  it('非法输入回到 0', () => {
    expect(clampOffset(Number.NaN)).toBe(0)
    expect(clampOffset(Number.POSITIVE_INFINITY)).toBe(0)
  })
})

describe('offsetsForSession', () => {
  it('正值只让 A 晚，负值只让 B 晚', () => {
    expect(offsetsForSession(800)).toEqual({ a: 800, b: 0 })
    expect(offsetsForSession(-800)).toEqual({ a: 0, b: 800 })
    expect(offsetsForSession(0)).toEqual({ a: 0, b: 0 })
  })

  it('先夹取再分配给两路', () => {
    expect(offsetsForSession(99999)).toEqual({ a: MAX_OFFSET_MS, b: 0 })
    expect(offsetsForSession(-99999)).toEqual({ a: 0, b: MAX_OFFSET_MS })
  })
})

describe('measuredLatencyMs', () => {
  it('返回相对直播边缘的落后毫秒数', () => {
    expect(measuredLatencyMs(8, 10)).toBeCloseTo((10 - 8 - LIVE_EDGE_LATENCY) * 1000)
  })

  it('贴得比边缘还近时按 0 处理', () => {
    expect(measuredLatencyMs(9.5, 10)).toBe(0)
  })

  it('非法数值不产生 NaN', () => {
    expect(measuredLatencyMs(Number.NaN, 10)).toBe(0)
    expect(measuredLatencyMs(8, Number.POSITIVE_INFINITY)).toBe(0)
  })
})

describe('offsetAction', () => {
  it('差得还多时暂停对应差额', () => {
    expect(offsetAction(1000, 100)).toEqual({ kind: 'pause', ms: 900 })
  })

  it('已经落后太多时向前 seek', () => {
    expect(offsetAction(200, 2400)).toEqual({ kind: 'seek', ms: 2200 })
  })

  it('差距在阈值内交给速率微调', () => {
    expect(offsetAction(500, 400)).toEqual({ kind: 'none', ms: 0 })
    expect(offsetAction(500, 700)).toEqual({ kind: 'none', ms: 0 })
  })

  it('阈值可覆盖，并且不会返回负的暂停时长', () => {
    expect(offsetAction(500, 0, 100)).toEqual({ kind: 'pause', ms: 500 })
    expect(offsetAction(0, 0, 100)).toEqual({ kind: 'none', ms: 0 })
  })
})

describe('pause deadline', () => {
  it('连续调高会累加暂停时长', () => {
    expect(nextPauseDeadline(1000, 0, 300)).toBe(1300)
    expect(nextPauseDeadline(1000, 1300, 200)).toBe(1500)
  })

  it('已经过期的 deadline 从当前时间重新算', () => {
    expect(nextPauseDeadline(5000, 1300, 200)).toBe(5200)
  })

  it('负数/非法增量不会缩短暂停', () => {
    expect(nextPauseDeadline(1000, 1300, -500)).toBe(1300)
    expect(nextPauseDeadline(1000, 1300, Number.NaN)).toBe(1300)
  })

  it('剩余时长不会为负', () => {
    expect(remainingPauseMs(1000, 1300)).toBe(300)
    expect(remainingPauseMs(2000, 1300)).toBe(0)
  })
})
