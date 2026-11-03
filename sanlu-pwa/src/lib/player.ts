/**
 * 双路直播播放器。mpegts.js(flv) 优先 —— 延迟低；不支持 MSE 或 flv 全挂时
 * 回退 hls.js，再回退原生 HLS（iOS）。
 *
 * 流量原则：浏览器优先直连 B 站 CDN（页面 referrer=no-referrer）；
 * 直连失败再走中转的 /stream 兜底。
 *
 * 音画偏移：直播无法往回 seek，所以「让这一路晚 N 毫秒」靠暂停 N 毫秒，
 * 让直播缓冲自然长出来；「让这一路早一点」则 seek 到缓冲更靠后的位置。
 */
import mpegts from 'mpegts.js'
import Hls from 'hls.js'
import { getRelayBase } from './net'
import {
  LIVE_EDGE_LATENCY,
  MAX_OFFSET_MS,
  measuredLatencyMs,
  nextPauseDeadline,
  offsetAction,
  pauseRemainingFor,
  remainingPauseMs,
} from './sync'

export interface StreamSet {
  flv: string[]
  hls: string[]
}

export type PlayState = 'loading' | 'playing' | 'error' | 'stopped'

export function proxyUrl(u: string): string {
  const relay = getRelayBase()
  const base = relay || ''
  // 同域 /stream 只在 Cloudflare Pages 部署时存在；GH Pages 下由中转提供
  return `${base}/stream/${encodeURIComponent(u)}`
}

export function supportsFlv(): boolean {
  try {
    return mpegts.getFeatureList().mseLivePlayback
  } catch {
    return false
  }
}

export class StreamPlayer {
  private video: HTMLVideoElement
  private urls: StreamSet
  private onState: (s: PlayState, msg?: string) => void
  private onBlocked: () => void
  /** 这一路卡缓冲 / 恢复时通知上层，用来让另一路一起暂停等待 */
  private onStall: (stalled: boolean) => void
  private engine: mpegts.Player | Hls | null = null
  private mode: 'mpegts' | 'hls' | 'native' | null = null
  private candidates: { mode: 'mpegts' | 'hls' | 'native'; url: string }[] = []
  private idx = 0
  private keepTimer: number | null = null
  private state: PlayState = 'stopped'
  private muted = true
  private nativeErr: (() => void) | null = null
  /** 正值表示这一路应该额外落后直播边缘多少毫秒，由 store 的混音面板调节 */
  private desiredOffset = 0
  /** 用户主动暂停：对齐用的临时暂停不能把它顶掉 */
  private userPaused = false
  /** 正在为「增加延迟」而主动暂停 */
  private syncPaused = false
  private syncTimer: number | null = null
  private pauseDeadline = 0
  /** 这次暂停开始时的实测延迟和开始时刻，调小目标时用来重算还要停多久 */
  private pauseStartLatency = 0
  private pauseStartedAt = 0
  /** 另一路卡缓冲时由 store 触发的等待暂停 */
  private peerHold = false
  /** 本路已经卡缓冲（去抖后） */
  private stalled = false
  private stallTimer: number | null = null

  constructor(
    video: HTMLVideoElement,
    urls: StreamSet,
    onState: (s: PlayState, msg?: string) => void,
    onBlocked: () => void = () => {},
    onStall: (stalled: boolean) => void = () => {}
  ) {
    this.video = video
    this.urls = urls
    this.onState = onState
    this.onBlocked = onBlocked
    this.onStall = onStall
    this.video.addEventListener('playing', this.onPlaying)
    this.video.addEventListener('waiting', this.onWaiting)
    this.video.addEventListener('pause', this.onNativePause)
    this.buildCandidates()
  }

  /** 浏览器可能拦截带声音的自动播放；由 store 弹出「点此播放」 */
  private tryPlay() {
    this.video.play().catch((e: unknown) => {
      if ((e as DOMException)?.name === 'NotAllowedError') this.onBlocked()
    })
  }

  private onPlaying = () => {
    if (this.state === 'stopped') return
    this.clearStallTimer()
    this.setStalled(false)
    this.setState('playing')
    this.syncNow(true)
  }

  /**
   * 卡缓冲：先观察 400ms，真卡住了才通知 store 暂停另一路。
   * 网络抖一下就来回暂停会让两路都一顿一顿的。
   */
  private onWaiting = () => {
    if (this.state === 'stopped' || this.userPaused || this.syncPaused || this.peerHold) return
    if (this.stallTimer) return
    this.stallTimer = window.setTimeout(() => {
      this.stallTimer = null
      if (this.state === 'stopped' || this.userPaused || this.video.paused) return
      if (this.video.readyState >= 3) return
      this.setStalled(true)
    }, 400)
  }

  /** 自己（而不是同步补偿）暂停时，卡缓冲状态结束，别一直拖着另一路 */
  private onNativePause = () => {
    this.clearStallTimer()
    this.setStalled(false)
  }

  private clearStallTimer() {
    if (this.stallTimer) {
      clearTimeout(this.stallTimer)
      this.stallTimer = null
    }
  }

  private setStalled(v: boolean) {
    if (this.stalled === v) return
    this.stalled = v
    this.onStall(v)
  }

  /**
   * 另一路卡住时把这一路也暂停，两边一起等；对方恢复后再一起继续。
   * 暂停期间两路都落在直播后面同样多，相对延迟保持不变。
   */
  holdByPeer(on: boolean) {
    if (on === this.peerHold) return
    this.peerHold = on
    if (on) {
      this.clearStallTimer()
      if (!this.userPaused && !this.syncPaused && !this.video.paused) this.video.pause()
      return
    }
    if (this.state === 'stopped' || this.userPaused || this.syncPaused) return
    const p = this.video.play()
    if (p && typeof p.catch === 'function') {
      p.catch((e: unknown) => {
        if ((e as DOMException)?.name === 'NotAllowedError') this.onBlocked()
      })
    }
    window.setTimeout(() => this.syncNow(true), 200)
  }

  private buildCandidates() {
    const list: { mode: 'mpegts' | 'hls' | 'native'; url: string }[] = []
    const push = (via: 'direct' | 'proxy') => {
      const wrap = (u: string) => (via === 'direct' ? u : proxyUrl(u))
      if (supportsFlv()) {
        for (const u of this.urls.flv) list.push({ mode: 'mpegts', url: wrap(u) })
      }
      if (Hls.isSupported()) {
        for (const u of this.urls.hls) list.push({ mode: 'hls', url: wrap(u) })
      }
      // iOS Safari 原生 HLS：video.src 直接喂 m3u8，跨域不需要 CORS
      for (const u of this.urls.hls) list.push({ mode: 'native', url: wrap(u) })
    }
    push('direct')
    // 中转作为最后兜底；没有中转时也保留同域 /stream（Cloudflare 部署可用）
    push('proxy')
    this.candidates = list.filter((c) => c.url)
  }

  start() {
    if (!this.candidates.length) {
      this.setState('error', '当前浏览器不支持这个直播流（缺少 H.264/MSE），换个浏览器试试')
      return
    }
    this.userPaused = false
    this.cancelSyncPause()
    this.resetStallState()
    this.idx = 0
    this.playCurrent()
  }

  restart() {
    this.userPaused = false
    this.cancelSyncPause()
    this.resetStallState()
    this.destroyEngine()
    this.idx = 0
    this.playCurrent()
  }

  private resetStallState() {
    this.clearStallTimer()
    this.peerHold = false
    this.setStalled(false)
  }

  private setState(s: PlayState, msg?: string) {
    this.state = s
    // 换线 / 报错 / 停止时不再算「卡缓冲」，否则会一直拖着另一路
    if (s !== 'playing') {
      this.clearStallTimer()
      this.setStalled(false)
    }
    this.onState(s, msg)
  }

  private playCurrent() {
    const cand = this.candidates[this.idx]
    if (!cand) {
      this.setState('error', '所有线路都失败了')
      return
    }
    this.setState('loading')
    this.destroyEngine()
    const url = cand.url

    if (cand.mode === 'mpegts') {
      if (!mpegts.isSupported()) {
        this.next()
        return
      }
      const player = mpegts.createPlayer(
        { type: 'flv', isLive: true, url, cors: true },
        {
          enableStashBuffer: false,
          // 交给我们自己的 syncNow 维护直播边缘和手动偏移；
          // mpegts 自带的追帧会在设置延迟后把 A 又拉回直播边缘。
          liveBufferLatencyChasing: false,
          lazyLoad: false,
          autoCleanupSourceBuffer: true,
          reconnectTries: 2,
          reconnectInterval: 1000,
        } as mpegts.Config
      )
      player.attachMediaElement(this.video)
      player.on(mpegts.Events.ERROR, () => {
        if (this.state === 'stopped') return
        this.next()
      })
      player.load()
      this.engine = player
      this.mode = 'mpegts'
      this.video.muted = this.muted
      this.tryPlay()
      this.startKeepalive()
    } else if (cand.mode === 'hls') {
      const hls = new Hls({
        liveDurationInfinity: true,
        lowLatencyMode: false,
        // 手动偏移最大 20 秒：暂停补延迟时缓冲要能提前攒够这么长
        maxBufferLength: 30,
        manifestLoadingTimeOut: 12_000,
        fragLoadingTimeOut: 12_000,
      })
      hls.loadSource(url)
      hls.attachMedia(this.video)
      hls.on(Hls.Events.ERROR, (_e, data) => {
        if (this.state === 'stopped') return
        if (data.fatal) this.next()
      })
      this.engine = hls
      this.mode = 'hls'
      this.video.muted = this.muted
      this.tryPlay()
      this.startKeepalive()
    } else {
      this.mode = 'native'
      this.video.src = url
      this.video.muted = this.muted
      this.tryPlay()
      this.startKeepalive()
      // 原生播放错误只能靠 video 事件；监听只对本次地址生效
      const onErr = () => {
        this.removeNativeErr()
        if (this.state !== 'stopped') this.next()
      }
      this.nativeErr = onErr
      this.video.addEventListener('error', onErr)
    }

    // 1.2 秒内时间推进或开始播放，视为成功
    const t0 = this.video.currentTime
    window.setTimeout(() => {
      if (this.state === 'stopped') return
      if (!this.video.paused) this.setState('playing')
      else if (this.video.currentTime > t0) this.setState('playing')
    }, 1200)
  }

  private next() {
    this.destroyEngine()
    this.idx++
    if (this.idx >= this.candidates.length) {
      this.setState('error', '所有线路都失败了')
      return
    }
    this.playCurrent()
  }

  /**
   * 直播追帧 + 手动音画偏移。
   *
   * 关键点：直播不能往回无限 seek。要把某一路往后推 N 毫秒，
   * 就把它暂停 N 毫秒，让缓冲继续增长；恢复后它就永久落后 N 毫秒。
   * 要把延迟减小，则直接 seek 到缓冲里更靠后的位置。
   *
   * 小偏差用 playbackRate 慢慢追，避免每秒 seek 造成卡顿。
   */
  private startKeepalive() {
    this.stopKeepalive()
    this.keepTimer = window.setInterval(() => this.syncNow(false), 1000)
  }

  private stopKeepalive() {
    if (this.keepTimer) {
      clearInterval(this.keepTimer)
      this.keepTimer = null
    }
  }

  private syncNow(force: boolean) {
    const v = this.video
    if (!v || v.readyState < 2 || v.paused || this.syncPaused || this.state === 'stopped') return

    let start = 0
    let end = 0
    try {
      const n = v.buffered.length
      if (!n) return
      start = v.buffered.start(0)
      end = v.buffered.end(n - 1)
    } catch {
      return
    }
    if (end <= start) return

    // 实测「相对直播边缘已经落后多少」，再和用户目标比较
    const measured = measuredLatencyMs(v.currentTime, end)
    const action = offsetAction(this.desiredOffset, measured)

    if (action.kind === 'pause') {
      // 还差一段延迟：暂停这一路，让直播缓冲往前跑
      this.scheduleSyncPause(action.ms, measured)
      return
    }

    if (action.kind === 'seek') {
      // 用户刚调小延迟 → 立即跳进缓冲里更靠后的位置；
      // 平时守边的轻微偏差用 1.06 倍速慢慢追，避免每秒 seek 卡顿。
      if (!force && action.ms < 2200) {
        if (v.playbackRate !== 1.06) v.playbackRate = 1.06
        return
      }
      const target = this.clampTarget(end - LIVE_EDGE_LATENCY - this.desiredOffset / 1000, start, end)
      if (Math.abs(v.currentTime - target) > 0.03) v.currentTime = target
      if (v.playbackRate !== 1) v.playbackRate = 1
      return
    }

    const target = this.clampTarget(end - LIVE_EDGE_LATENCY - this.desiredOffset / 1000, start, end)
    const drift = v.currentTime - target
    if (Math.abs(drift) > 2.2) {
      v.currentTime = target
      v.playbackRate = 1
    } else if (Math.abs(drift) > 0.22) {
      const rate = drift > 0 ? 0.94 : 1.06
      if (v.playbackRate !== rate) v.playbackRate = rate
    } else if (v.playbackRate !== 1) {
      v.playbackRate = 1
    }
  }

  private clampTarget(target: number, start: number, end: number): number {
    return Math.min(Math.max(target, start + 0.05), end - 0.35)
  }

  /** 暂停 ms 毫秒来增加延迟；连续调滑杆时在现有截止时间上累加 */
  private scheduleSyncPause(ms: number, measuredMs: number) {
    if (this.userPaused || this.state === 'stopped' || this.video.paused) return
    const now = performance.now()
    this.pauseDeadline = nextPauseDeadline(now, this.pauseDeadline, Math.min(MAX_OFFSET_MS, ms))
    this.syncPaused = true
    this.pauseStartedAt = now
    this.pauseStartLatency = measuredMs
    this.video.pause()
    if (this.video.playbackRate !== 1) this.video.playbackRate = 1
    this.armPauseTimer()
  }

  /** 暂停过程中用户又调大了延迟：延长暂停时间，不打断/不重复 pause() */
  private extendSyncPause(ms: number) {
    const now = performance.now()
    this.pauseDeadline = nextPauseDeadline(now, this.pauseDeadline, Math.min(MAX_OFFSET_MS, ms))
    this.armPauseTimer()
  }

  /**
   * 暂停过程中用户把延迟调小：按已经攒到的延迟重算剩余时长。
   * 不需要再停了就立刻恢复，避免调回 0 还要干等原来的 20 秒。
   */
  private reduceSyncPause() {
    const now = performance.now()
    const remaining = pauseRemainingFor(
      this.desiredOffset,
      this.pauseStartLatency,
      now - this.pauseStartedAt
    )
    if (remaining <= 5) {
      this.finishSyncPause()
      return
    }
    this.pauseDeadline = now + remaining
    this.armPauseTimer()
  }

  private armPauseTimer() {
    if (this.syncTimer) {
      clearTimeout(this.syncTimer)
      this.syncTimer = null
    }
    const left = remainingPauseMs(performance.now(), this.pauseDeadline)
    this.syncTimer = window.setTimeout(() => {
      this.syncTimer = null
      const rest = remainingPauseMs(performance.now(), this.pauseDeadline)
      if (rest > 5) {
        this.armPauseTimer()
        return
      }
      this.finishSyncPause()
    }, left)
  }

  /** 结束补齐暂停并恢复播放，随后按新目标重新对齐 */
  private finishSyncPause() {
    if (this.syncTimer) {
      clearTimeout(this.syncTimer)
      this.syncTimer = null
    }
    this.syncPaused = false
    this.pauseDeadline = 0
    if (this.state === 'stopped' || this.userPaused) return
    // 另一路还在卡缓冲：先不恢复，等 holdByPeer(false) 再一起播
    if (this.peerHold) return
    const p = this.video.play()
    if (p && typeof p.catch === 'function') {
      p.catch((e: unknown) => {
        if ((e as DOMException)?.name === 'NotAllowedError') this.onBlocked()
      })
    }
    window.setTimeout(() => this.syncNow(true), 200)
  }

  private cancelSyncPause() {
    if (this.syncTimer) {
      clearTimeout(this.syncTimer)
      this.syncTimer = null
    }
    this.pauseDeadline = 0
    this.pauseStartLatency = 0
    this.pauseStartedAt = 0
    this.syncPaused = false
  }

  private removeNativeErr() {
    if (this.nativeErr) {
      this.video.removeEventListener('error', this.nativeErr)
      this.nativeErr = null
    }
  }

  private destroyEngine() {
    this.stopKeepalive()
    this.cancelSyncPause()
    this.removeNativeErr()
    this.video.playbackRate = 1
    const e = this.engine
    this.engine = null
    if (!e) {
      if (this.mode === 'native') {
        this.video.removeAttribute('src')
        this.video.load()
      }
      this.mode = null
      return
    }
    try {
      if (this.mode === 'mpegts') {
        const p = e as mpegts.Player
        p.pause()
        p.unload()
        p.detachMediaElement()
        p.destroy()
      } else if (this.mode === 'hls') {
        ;(e as Hls).destroy()
      } else {
        this.video.removeAttribute('src')
        this.video.load()
      }
    } catch {
      /* noop */
    }
    this.mode = null
  }

  setMuted(m: boolean) {
    this.muted = m
    this.video.muted = m
  }

  setVolume(v: number) {
    const vol = Math.max(0, Math.min(1, v))
    // 记住静音状态：playCurrent 切线路时会重新套用 this.muted
    this.muted = vol === 0
    this.video.volume = vol
    this.video.muted = this.muted
  }

  setSyncOffset(ms: number) {
    const next = Math.max(-MAX_OFFSET_MS, Math.min(MAX_OFFSET_MS, Math.round(ms)))
    const delta = next - this.desiredOffset
    this.desiredOffset = next
    if (this.state === 'stopped') return

    // 正在为延迟而暂停：调大顺延；调小按已攒到的延迟重算，
    // 目标已经够了就提前恢复，随后由 syncNow seek 到新目标。
    if (this.syncPaused) {
      if (delta > 0) this.extendSyncPause(delta)
      else if (delta < 0) this.reduceSyncPause()
      return
    }
    if (this.userPaused) return

    // 立刻按实测偏差执行：加延迟会暂停，减延迟会向前跳。
    this.syncNow(true)
  }

  seekToLive() {
    try {
      const n = this.video.buffered.length
      if (n) {
        this.video.currentTime =
          this.video.buffered.end(n - 1) - LIVE_EDGE_LATENCY - this.desiredOffset / 1000
      }
    } catch {
      /* noop */
    }
  }

  async play() {
    this.userPaused = false
    this.cancelSyncPause()
    // 另一路还在卡缓冲：先继续停着，等对方恢复后 holdByPeer(false) 会一起播
    if (this.peerHold) return
    try {
      await this.video.play()
      this.syncNow(true)
    } catch (e) {
      if ((e as DOMException)?.name === 'NotAllowedError') this.onBlocked()
    }
  }

  pause() {
    this.userPaused = true
    this.cancelSyncPause()
    this.video.pause()
  }

  destroy() {
    this.userPaused = true
    this.setState('stopped')
    this.cancelSyncPause()
    this.clearStallTimer()
    this.video.removeEventListener('playing', this.onPlaying)
    this.video.removeEventListener('waiting', this.onWaiting)
    this.video.removeEventListener('pause', this.onNativePause)
    this.destroyEngine()
  }
}