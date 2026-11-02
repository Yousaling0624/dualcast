/**
 * 双路直播播放器。mpegts.js(flv) 优先 —— 延迟低；不支持 MSE 或 flv 全挂时
 * 回退 hls.js，再回退原生 HLS（iOS）。
 *
 * 流量原则：浏览器优先直连 B 站 CDN（页面 referrer=no-referrer）；
 * 直连失败再走中转的 /stream 兜底。
 */
import mpegts from 'mpegts.js'
import Hls from 'hls.js'
import { getRelayBase } from './net'

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

function isBuffered(v: HTMLVideoElement, t: number): boolean {
  try {
    for (let i = 0; i < v.buffered.length; i++) {
      if (t >= v.buffered.start(i) && t <= v.buffered.end(i)) return true
    }
  } catch {
    /* ignore */
  }
  return false
}

export class StreamPlayer {
  private video: HTMLVideoElement
  private urls: StreamSet
  private onState: (s: PlayState, msg?: string) => void
  private onBlocked: () => void
  private engine: mpegts.Player | Hls | null = null
  private mode: 'mpegts' | 'hls' | 'native' | null = null
  private candidates: { mode: 'mpegts' | 'hls' | 'native'; url: string }[] = []
  private idx = 0
  private keepTimer: number | null = null
  private state: PlayState = 'stopped'
  private muted = true
  private nativeErr: (() => void) | null = null
  /** 正值表示这一路应该比另一路晚（单位 ms），由 store 的混音面板调节 */
  private syncOffset = 0

  constructor(
    video: HTMLVideoElement,
    urls: StreamSet,
    onState: (s: PlayState, msg?: string) => void,
    onBlocked: () => void = () => {}
  ) {
    this.video = video
    this.urls = urls
    this.onState = onState
    this.onBlocked = onBlocked
    this.video.addEventListener('playing', this.onPlaying)
    this.buildCandidates()
  }

  /** 浏览器可能拦截带声音的自动播放；由 store 弹出「点此播放」 */
  private tryPlay() {
    this.video.play().catch((e: unknown) => {
      if ((e as DOMException)?.name === 'NotAllowedError') this.onBlocked()
    })
  }

  private onPlaying = () => {
    if (this.state !== 'stopped') this.setState('playing')
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
    this.idx = 0
    this.playCurrent()
  }

  restart() {
    this.destroyEngine()
    this.idx = 0
    this.playCurrent()
  }

  private setState(s: PlayState, msg?: string) {
    this.state = s
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
          liveBufferLatencyChasing: true,
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
        maxBufferLength: 12,
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
   * - 常规：缓冲超过 6 秒就跳到最新；
   * - 偏移：把 A 路的目标位置往后挪 offset 秒，让 A 的声音慢一点。
   */
  private startKeepalive() {
    this.stopKeepalive()
    this.keepTimer = window.setInterval(() => {
      const v = this.video
      if (v.readyState < 2 || v.paused) return
      try {
        const n = v.buffered.length
        if (!n) return
        const end = v.buffered.end(n - 1)
        const liveTarget = end - 1.5
        if (this.syncOffset !== 0) {
          const target = liveTarget - this.syncOffset / 1000
          if (Math.abs(v.currentTime - target) > 0.35 && isBuffered(v, target)) {
            v.currentTime = target
          }
        } else if (end - v.currentTime > 6) {
          v.currentTime = liveTarget
        }
      } catch {
        /* noop */
      }
    }, 1000)
  }

  private stopKeepalive() {
    if (this.keepTimer) {
      clearInterval(this.keepTimer)
      this.keepTimer = null
    }
  }

  private removeNativeErr() {
    if (this.nativeErr) {
      this.video.removeEventListener('error', this.nativeErr)
      this.nativeErr = null
    }
  }

  private destroyEngine() {
    this.stopKeepalive()
    this.removeNativeErr()
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
    this.syncOffset = Math.max(-5000, Math.min(5000, ms))
  }

  seekToLive() {
    try {
      const n = this.video.buffered.length
      if (n) this.video.currentTime = this.video.buffered.end(n - 1) - 1.2
    } catch {
      /* noop */
    }
  }

  async play() {
    try {
      await this.video.play()
    } catch (e) {
      if ((e as DOMException)?.name === 'NotAllowedError') this.onBlocked()
    }
  }

  pause() {
    this.video.pause()
  }

  destroy() {
    this.setState('stopped')
    this.video.removeEventListener('playing', this.onPlaying)
    this.destroyEngine()
  }
}