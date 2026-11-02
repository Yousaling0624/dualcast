/**
 * 双路直播播放器。mpei-ts.js(flv) 优先 —— 延迟低；不支持 MSE 或 flv 全挂时
 * 回退 hls.js，再回退原生 HLS（iOS）。
 * 流量原则：浏览器直连 B 站 CDN（配合页面 referrer=no-referrer），
 * 同域 /stream 代理仅作直连全部失败后的兜底。
 */
import mpegts from 'mpegts.js'
import Hls from 'hls.js'

export interface StreamSet {
  flv: string[]
  hls: string[]
}

export type PlayState = 'loading' | 'playing' | 'error' | 'stopped'

export function proxyUrl(u: string): string {
  return '/stream/' + encodeURIComponent(u)
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
  private engine: mpegts.Player | Hls | null = null
  private mode: 'mpegts' | 'hls' | 'native' | null = null
  private candidates: { mode: 'mpegts' | 'hls' | 'native'; url: string }[] = []
  private idx = 0
  private keepTimer: number | null = null
  private state: PlayState = 'stopped'
  // 先静音起播绕过自动播放限制，attach 后由 store 按音量设置解除
  private muted = true

  constructor(video: HTMLVideoElement, urls: StreamSet, onState: (s: PlayState, msg?: string) => void) {
    this.video = video
    this.urls = urls
    this.onState = onState
    this.buildCandidates()
  }

  private buildCandidates() {
    const list: { mode: 'mpegts' | 'hls' | 'native'; url: string }[] = []
    // 先放全部直连线路，再放代理兜底 —— 正常情况下流量不经过 Cloudflare
    const push = (via: 'direct' | 'proxy') => {
      const wrap = (u: string) => (via === 'direct' ? u : proxyUrl(u))
      if (supportsFlv()) {
        for (const u of this.urls.flv) list.push({ mode: 'mpegts', url: wrap(u) })
      }
      if (Hls.isSupported()) {
        for (const u of this.urls.hls) list.push({ mode: 'hls', url: wrap(u) })
      }
      for (const u of this.urls.hls) list.push({ mode: 'native', url: wrap(u) })
    }
    push('direct')
    push('proxy')
    this.candidates = list
  }

  start() {
    if (!this.candidates.length) {
      this.setState('error', '没有可用的播放地址')
      return
    }
    this.idx = 0
    this.playCurrent()
  }

  /** 网络抖动 / 断流后从头再试一轮 */
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
        {
          type: 'flv',
          isLive: true,
          url,
          cors: true,
        },
        {
          enableStashBuffer: false,
          liveBufferLatencyChasing: true,
          lazyLoad: false,
          autoCleanupSourceBuffer: true,
        }
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
      this.video.play().catch(() => {})
      this.startKeepalive()
    } else if (cand.mode === 'hls') {
      const hls = new Hls({
        liveDurationInfinity: true,
        lowLatencyMode: false,
        maxBufferLength: 10,
        manifestLoadingTimeOut: 12_000,
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
      this.video.play().catch(() => {})
      this.startKeepalive()
    } else {
      // 原生 HLS（iOS Safari）
      this.mode = 'native'
      this.video.src = url
      this.video.muted = this.muted
      this.video.play().catch(() => {})
      this.startKeepalive()
      // 原生播放错误只能靠 video 事件
      const onErr = () => {
        this.video.removeEventListener('error', onErr)
        if (this.state !== 'stopped') this.next()
      }
      this.video.addEventListener('error', onErr)
    }

    // 播放成功判定：1 秒内有时间推进就算 playing
    const t0 = this.video.currentTime
    window.setTimeout(() => {
      if (this.state === 'stopped') return
      if (this.video.currentTime > t0 || !this.video.paused) {
        if (!this.video.paused) this.setState('playing')
      }
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

  /** 直播延迟追帧：缓冲超过 6 秒就跳到最新 */
  private startKeepalive() {
    this.stopKeepalive()
    this.keepTimer = window.setInterval(() => {
      const v = this.video
      if (v.readyState < 2 || v.paused) return
      try {
        const end = v.buffered.length ? v.buffered.end(v.buffered.length - 1) : 0
        if (end - v.currentTime > 6) {
          v.currentTime = end - 1.5
        }
      } catch {
        /* noop */
      }
    }, 5000)
  }

  private stopKeepalive() {
    if (this.keepTimer) {
      clearInterval(this.keepTimer)
      this.keepTimer = null
    }
  }

  private destroyEngine() {
    this.stopKeepalive()
    const e = this.engine
    this.engine = null
    if (!e) return
    try {
      if (this.mode === 'mpegts') {
        ;(e as mpegts.Player).pause()
        ;(e as mpegts.Player).unload()
        ;(e as mpegts.Player).detachMediaElement()
        ;(e as mpegts.Player).destroy()
      } else if (this.mode === 'hls') {
        ;(e as Hls).destroy()
      } else {
        this.video.removeAttribute('src')
        this.video.load()
      }
    } catch {
      /* noop */
    }
  }

  setMuted(m: boolean) {
    this.muted = m
    this.video.muted = m
  }

  async play() {
    try {
      await this.video.play()
    } catch {
      /* 用户手势限制时静默失败，界面上再点 */
    }
  }

  pause() {
    this.video.pause()
  }

  destroy() {
    this.setState('stopped')
    this.destroyEngine()
  }
}
