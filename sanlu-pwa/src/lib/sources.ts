/**
 * 多直播间弹幕的来源标记。
 *
 * 每个导入的直播间会分到一种颜色，并可以选：
 * - color：只显示颜色气泡；
 * - initial：显示主播昵称的第一个字；
 * - custom：显示用户自定义的字。
 */

export type BadgeMode = 'color' | 'initial' | 'custom'

/** 和主色板区分度较高的气泡色；主房间固定用 --accent */
export const SOURCE_PALETTE = [
  '#d97757',
  '#6679cf',
  '#2f9e8f',
  '#b9842e',
  '#8e6bbf',
  '#c25b8f',
  '#4d8a4d',
  '#bf5a4f',
] as const

export const PRIMARY_SOURCE_COLOR = '#d97757'
/** A/B 两路同屏时，B 路弹幕用蓝色气泡区分 */
export const SECONDARY_SOURCE_COLOR = '#6679cf'

export interface BadgeOptions {
  uname?: string
  custom?: string
  color?: string
}

export interface SourceBadge {
  /** 空字符串表示只画一个颜色气泡，不写字 */
  text: string
  color: string
}

/** 取名字的第一个「字」，兼容 emoji / 代理对，避免切出半个字符 */
export function firstGlyph(name: string | undefined): string {
  const chars = Array.from((name || '').trim())
  return chars.length ? chars[0] : ''
}

export function normalizeBadgeText(text: string | undefined, max = 2): string {
  return Array.from((text || '').trim()).slice(0, Math.max(1, max)).join('')
}

/** 房间号 → 稳定的调色板下标（同一个房间每次打开颜色一致） */
export function paletteIndex(roomId: number): number {
  const n = Math.abs(Math.trunc(Number.isFinite(roomId) ? roomId : 0)) % 2147483647
  const mixed = ((n ^ 0x9e3779b9) * 2654435761) >>> 0
  return mixed % SOURCE_PALETTE.length
}

export function roomColor(roomId: number): string {
  return SOURCE_PALETTE[paletteIndex(roomId)]
}

/**
 * 计算一条来源弹幕的气泡。
 * custom 模式没填字时退化成主播首字，再退化成「间」。
 */
export function badgeForSource(mode: BadgeMode, opts: BadgeOptions = {}): SourceBadge {
  const color = opts.color || roomColor(0)
  if (mode === 'color') return { text: '', color }
  if (mode === 'custom') {
    return {
      text: normalizeBadgeText(opts.custom) || firstGlyph(opts.uname) || '间',
      color,
    }
  }
  return { text: firstGlyph(opts.uname) || '间', color }
}

/** 给设置页预览用：气泡上显示的字 */
export function badgePreviewText(mode: BadgeMode, opts: BadgeOptions = {}): string {
  return badgeForSource(mode, opts).text || '●'
}

/**
 * 是否需要在弹幕 / SC 上画来源气泡。
 * 只有 A 一路时保持干净；有导入房间，或 A+B 同屏且 B 是另一个房间时才显示。
 */
export function needsSourceBadges(
  sourceCount: number,
  includeB: boolean,
  hasDistinctBRoom: boolean
): boolean {
  return sourceCount > 0 || (includeB && hasDistinctBRoom)
}
