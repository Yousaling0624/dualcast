import { describe, expect, it } from 'vitest'
import {
  PRIMARY_SOURCE_COLOR,
  SOURCE_PALETTE,
  badgeForSource,
  badgePreviewText,
  firstGlyph,
  normalizeBadgeText,
  paletteIndex,
  roomColor,
} from '../src/lib/sources'

describe('firstGlyph', () => {
  it('取第一个字符并去掉首尾空白', () => {
    expect(firstGlyph('  小明')).toBe('小')
    expect(firstGlyph('Alice')).toBe('A')
  })

  it('emoji 不会被切成半个代理对', () => {
    expect(firstGlyph('😀主播')).toBe('😀')
  })

  it('空昵称返回空串', () => {
    expect(firstGlyph('')).toBe('')
    expect(firstGlyph(undefined)).toBe('')
    expect(firstGlyph('   ')).toBe('')
  })
})

describe('normalizeBadgeText', () => {
  it('去空白并截断到指定长度', () => {
    expect(normalizeBadgeText('  主  ')).toBe('主')
    expect(normalizeBadgeText('二台')).toBe('二台')
    expect(normalizeBadgeText('ABCD')).toBe('AB')
    expect(normalizeBadgeText('ABCD', 3)).toBe('ABC')
  })

  it('空值返回空串', () => {
    expect(normalizeBadgeText('')).toBe('')
    expect(normalizeBadgeText(undefined)).toBe('')
  })
})

describe('颜色分配', () => {
  it('同一个房间每次得到相同的颜色', () => {
    expect(paletteIndex(12345)).toBe(paletteIndex(12345))
    expect(roomColor(12345)).toBe(roomColor(12345))
  })

  it('下标始终落在调色板内', () => {
    for (const id of [0, 1, 6, -1, 2147483647, -2147483648, 999999999]) {
      const index = paletteIndex(id)
      expect(index).toBeGreaterThanOrEqual(0)
      expect(index).toBeLessThan(SOURCE_PALETTE.length)
      expect(SOURCE_PALETTE[index]).toBeTruthy()
    }
  })
})

describe('badgeForSource', () => {
  it('颜色模式只返回气泡色，不写字', () => {
    expect(badgeForSource('color', { color: '#123456' })).toEqual({
      text: '',
      color: '#123456',
    })
  })

  it('首字模式用主播昵称的第一个字', () => {
    expect(badgeForSource('initial', { uname: '三鹿解说', color: '#abcdef' })).toEqual({
      text: '三',
      color: '#abcdef',
    })
  })

  it('昵称为空时用「间」兜底', () => {
    expect(badgeForSource('initial', { color: '#abcdef' }).text).toBe('间')
  })

  it('自定义模式优先用自定义字', () => {
    expect(badgeForSource('custom', { custom: '二台', uname: '主播' }).text).toBe('二台')
  })

  it('自定义字为空时退回主播首字，再退回「间」', () => {
    expect(badgeForSource('custom', { custom: '  ', uname: '主播' }).text).toBe('主')
    expect(badgeForSource('custom', { uname: '' }).text).toBe('间')
  })

  it('没有指定颜色时使用房间色，主色板可导出', () => {
    expect(badgeForSource('initial', { uname: 'A' }).color).toBe(roomColor(0))
    expect(PRIMARY_SOURCE_COLOR).toBe(SOURCE_PALETTE[0])
  })
})

describe('badgePreviewText', () => {
  it('颜色模式用圆点占位', () => {
    expect(badgePreviewText('color', { color: '#111111' })).toBe('●')
  })

  it('其他模式直接显示气泡文字', () => {
    expect(badgePreviewText('initial', { uname: '解说' })).toBe('解')
    expect(badgePreviewText('custom', { custom: 'A' })).toBe('A')
  })
})
