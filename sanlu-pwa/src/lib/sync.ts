/**
 * 音画偏移的纯计算部分。
 *
 * 直播不能往回无限 seek，所以「把某一路往后退」最可靠的办法是：
 * 暂停这一路一段时间，让直播缓冲继续往前跑，恢复后它就永久落后了。
 * 「把某一路往前赶」则直接 seek 到缓冲里更靠后的位置。
 *
 * 这里的函数不碰 DOM，方便单元测试，StreamPlayer 只负责执行动作。
 */

/** 常规播放时保持在直播边缘之后约 1.2 秒，给抖动留余量 */
export const LIVE_EDGE_LATENCY = 1.2

/** 手动音画偏移上限 ±20 秒 */
export const MAX_OFFSET_MS = 20_000
export const MIN_OFFSET_MS = -20_000

/** 小于这个值的偏差交给 playbackRate 慢慢追，避免频繁暂停/跳转 */
export const OFFSET_ACTION_THRESHOLD_MS = 350

export interface SessionOffsets {
  /** A 路需要额外延迟的毫秒数（>= 0） */
  a: number
  /** B 路需要额外延迟的毫秒数（>= 0） */
  b: number
}

export function clampOffset(ms: number): number {
  if (!Number.isFinite(ms)) return 0
  return Math.max(MIN_OFFSET_MS, Math.min(MAX_OFFSET_MS, Math.round(ms)))
}

/**
 * sessionOffset > 0：A 晚一点；< 0：B 晚一点。
 * 两路都不会拿到负数偏移——负值由另一路承担。
 */
export function offsetsForSession(offsetMs: number): SessionOffsets {
  const off = clampOffset(offsetMs)
  return { a: Math.max(0, off), b: Math.max(0, -off) }
}

/** 当前这一路相对「直播边缘 - 1.2s」已经落后多少毫秒，负数按 0 处理 */
export function measuredLatencyMs(
  currentTime: number,
  bufferedEnd: number,
  edge = LIVE_EDGE_LATENCY
): number {
  if (!Number.isFinite(currentTime) || !Number.isFinite(bufferedEnd)) return 0
  return Math.max(0, (bufferedEnd - currentTime - edge) * 1000)
}

export type OffsetActionKind = 'pause' | 'seek' | 'none'

export interface OffsetAction {
  kind: OffsetActionKind
  /** pause：需要暂停的毫秒数；seek：需要向前跳过的毫秒数 */
  ms: number
}

/**
 * 根据「目标偏移」和「实测偏移」决定下一步：
 * - 还差得多 → 暂停，让缓冲追上；
 * - 已经多出很多 → 向前 seek，把多余的延迟跳掉；
 * - 差不多 → 交给速率微调。
 */
export function offsetAction(
  desiredMs: number,
  measuredMsValue: number,
  threshold = OFFSET_ACTION_THRESHOLD_MS
): OffsetAction {
  const desired = clampOffset(desiredMs)
  const measured = Math.max(0, Number.isFinite(measuredMsValue) ? measuredMsValue : 0)
  const deficit = desired - measured
  if (deficit > threshold) return { kind: 'pause', ms: deficit }
  if (deficit < -threshold) return { kind: 'seek', ms: -deficit }
  return { kind: 'none', ms: 0 }
}

/**
 * 连续拖动滑杆时，把多次暂停累加。
 * 例如已经停到 now+300ms，又调高 100ms，就延到 now+400ms。
 */
export function nextPauseDeadline(now: number, currentDeadline: number, addMs: number): number {
  const base = Math.max(now, Number.isFinite(currentDeadline) ? currentDeadline : 0)
  return base + Math.max(0, Number.isFinite(addMs) ? addMs : 0)
}

export function remainingPauseMs(now: number, deadline: number): number {
  return Math.max(0, deadline - now)
}

/**
 * 暂停补延迟的过程中用户把目标调小了：按「暂停开始时已经落后的量 + 暂停时长」
 * 估算现在攒到多少延迟，返回还需要继续暂停多久。
 *
 * 直播缓冲在暂停期间按实时速度增长，所以已经攒到的延迟 ≈ 起始延迟 + 已暂停时长。
 * 返回 0 表示目标已经达到（甚至超前），可以提前结束暂停去 seek。
 */
export function pauseRemainingFor(
  desiredMs: number,
  latencyAtPauseStartMs: number,
  elapsedMs: number
): number {
  const start = Math.max(0, Number.isFinite(latencyAtPauseStartMs) ? latencyAtPauseStartMs : 0)
  const elapsed = Math.max(0, Number.isFinite(elapsedMs) ? elapsedMs : 0)
  return Math.max(0, clampOffset(desiredMs) - (start + elapsed))
}
