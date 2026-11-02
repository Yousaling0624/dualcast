/**
 * B 站直播弹幕 WebSocket 客户端。
 *
 * 协议参考 bilibili-API-collect 与 PiliPlus：
 * - 16 字节包头：包长 / 头长 / 协议版本 / 操作码 / 序号；
 * - 连接后发 AUTH(op=7)，服务器回 AUTH_REPLY(op=8)；
 * - 每 30 秒心跳(op=2)，回包里带人气值(op=3)；
 * - 消息包 op=5，ver=0 原始 JSON、ver=2 zlib、ver=3 brotli。
 */
import { fetchDanmuInfo } from './bili'

const OP = {
  HEARTBEAT: 2,
  HEARTBEAT_REPLY: 3,
  MESSAGE: 5,
  AUTH: 7,
  AUTH_REPLY: 8,
} as const

const HEARTBEAT_BODY = new TextEncoder().encode('[object Object]')

export type ChatKind = 'danmu' | 'gift' | 'sc' | 'guard' | 'sys'

export interface ChatMsg {
  seq: number
  kind: ChatKind
  text: string
  uname: string
  uid?: number
  dmid?: string
  /** 主播弹幕颜色（B 站十进制 RGB） */
  color?: number
  /** SC / 礼物 / 舰长金额 */
  price?: number
  /** 粉丝牌名 */
  medal?: string
  medalLevel?: number
  /** SC 发送者头像 */
  avatar?: string
  /** SC 服务端给的展示时长（毫秒） */
  scDuration?: number
  /** SC 唯一 id，用于去重 / 删除 */
  scId?: string
  self?: boolean
  /** 进房时补拉的历史弹幕 */
  history?: boolean
  /** 这条弹幕回复的是谁（部分服务端会带） */
  replyTo?: string
}

export type DanmakuStatus = 'idle' | 'connecting' | 'open' | 'reconnecting' | 'closed' | 'failed'

export interface DanmakuEvents {
  onMsg: (m: ChatMsg) => void
  onStatus: (s: DanmakuStatus, error?: string) => void
  onViewer: (n: number) => void
}

interface ConnectOptions {
  uid?: number
  buvid?: string
  events: DanmakuEvents
}

let brotliDecompress: ((u8: Uint8Array) => Uint8Array) | null = null

type BrotliModule = { decompress: (u8: Uint8Array) => Uint8Array }

async function ensureBrotli() {
  if (brotliDecompress) return
  // brotli-wasm 的 ESM/浏览器入口 default 导出的是初始化 Promise；
  // 同时兼容直接带 decompress 的实现。
  const mod = (await import('brotli-wasm')) as unknown as BrotliModule & {
    default?: BrotliModule | Promise<BrotliModule>
  }
  const resolved = typeof mod.decompress === 'function' ? mod : await mod.default
  if (!resolved || typeof resolved.decompress !== 'function') {
    throw new Error('brotli 解压器初始化失败')
  }
  brotliDecompress = (u8) => resolved.decompress(u8)
}

const textEnc = new TextEncoder()
const textDec = new TextDecoder()

function pack(op: number, body: Uint8Array): Uint8Array {
  const total = 16 + body.length
  const buf = new ArrayBuffer(total)
  const view = new DataView(buf)
  view.setUint32(0, total)
  view.setUint16(4, 16)
  view.setUint16(6, 1)
  view.setInt32(8, op)
  view.setInt32(12, 1)
  new Uint8Array(buf, 16).set(body)
  return new Uint8Array(buf)
}

async function inflate(ver: number, body: Uint8Array): Promise<Uint8Array> {
  if (ver === 0) return body
  if (ver === 2) {
    const ds = new DecompressionStream('deflate')
    const stream = new Blob([body.slice().buffer as ArrayBuffer]).stream().pipeThrough(ds)
    return new Uint8Array(await new Response(stream).arrayBuffer())
  }
  if (ver === 3) {
    await ensureBrotli()
    const out = brotliDecompress!(body)
    return out instanceof Uint8Array ? out : new Uint8Array(out)
  }
  throw new Error(`unknown proto ver ${ver}`)
}

function readJson(text: string): any | null {
  const clean = text.replace(/\u0000/g, '').trim()
  if (!clean) return null
  try {
    return JSON.parse(clean)
  } catch {
    return null
  }
}

export class DanmakuClient {
  private ws: WebSocket | null = null
  private heartbeatTimer: number | null = null
  private reconnectTimer: number | null = null
  private stopped = true
  private roomId = 0
  private events: DanmakuEvents | null = null
  private seq = 1
  private attempt = 0
  private hostIndex = 0
  private uid = 0
  private buvid = ''
  private hosts: { host: string; wss_port: number }[] = []
  private token = ''
  private lastError = ''

  async connect(roomId: number, uid: number, buvid: string, events: DanmakuEvents) {
    this.stop()
    this.roomId = roomId
    this.uid = uid
    this.buvid = buvid
    this.events = events
    this.stopped = false
    this.attempt = 0
    this.hostIndex = 0
    await this.open()
  }

  private setStatus(s: DanmakuStatus, error?: string) {
    if (s === 'open') this.lastError = ''
    else if (error) this.lastError = error
    this.events?.onStatus(s, error)
  }

  private async open() {
    if (this.stopped) return
    this.setStatus('connecting')
    try {
      const info = await fetchDanmuInfo(this.roomId)
      if (this.stopped) return
      this.token = info.token
      this.hosts = info.hosts
      this.openSocket()
    } catch (e) {
      this.lastError = e instanceof Error ? e.message : '获取弹幕服务器失败'
      this.scheduleReconnect()
    }
  }

  private openSocket() {
    if (this.stopped) return
    const pick = this.hosts[this.hostIndex % Math.max(this.hosts.length, 1)]
    if (!pick) {
      this.scheduleReconnect()
      return
    }
    this.hostIndex++
    const url = `wss://${pick.host}:${pick.wss_port || 443}/sub`
    let ws: WebSocket
    try {
      ws = new WebSocket(url)
    } catch {
      this.scheduleReconnect()
      return
    }
    ws.binaryType = 'arraybuffer'
    this.ws = ws

    ws.onopen = () => {
      if (this.stopped || ws !== this.ws) return
      const auth = JSON.stringify({
        uid: this.uid,
        roomid: this.roomId,
        protover: 3,
        platform: 'web',
        type: 2,
        key: this.token,
        ...(this.buvid ? { buvid: this.buvid } : {}),
      })
      ws.send(pack(OP.AUTH, textEnc.encode(auth)))
      this.startHeartbeat(ws)
    }

    ws.onmessage = (ev) => {
      if (this.stopped || ws !== this.ws) return
      const data = new Uint8Array(ev.data as ArrayBuffer)
      this.handlePackets(data).catch(() => {})
    }

    ws.onclose = () => {
      if (ws !== this.ws) return
      this.cleanupSocket()
      if (!this.stopped) this.scheduleReconnect()
    }

    ws.onerror = () => {
      try {
        ws.close()
      } catch {
        /* noop */
      }
    }
  }

  private startHeartbeat(ws: WebSocket) {
    this.stopHeartbeat()
    const beat = () => {
      if (ws.readyState === WebSocket.OPEN) ws.send(pack(OP.HEARTBEAT, HEARTBEAT_BODY))
    }
    // 先等认证回包；B 站对过早心跳不敏感，但稍等更稳
    setTimeout(beat, 1200)
    this.heartbeatTimer = window.setInterval(beat, 30_000)
  }

  private stopHeartbeat() {
    if (this.heartbeatTimer) {
      clearInterval(this.heartbeatTimer)
      this.heartbeatTimer = null
    }
  }

  private cleanupSocket() {
    this.stopHeartbeat()
    try {
      this.ws?.close()
    } catch {
      /* noop */
    }
    this.ws = null
  }

  private scheduleReconnect() {
    if (this.stopped) return
    this.attempt++
    if (this.attempt > 8) {
      this.setStatus('failed', this.lastError || '弹幕连接多次失败')
      return
    }
    this.setStatus('reconnecting', this.lastError)
    if (this.reconnectTimer) clearTimeout(this.reconnectTimer)
    const delay = Math.min(20_000, 1200 * this.attempt)
    this.reconnectTimer = window.setTimeout(() => {
      this.reconnectTimer = null
      void this.open()
    }, delay)
  }

  stop() {
    this.stopped = true
    this.cleanupSocket()
    if (this.reconnectTimer) {
      clearTimeout(this.reconnectTimer)
      this.reconnectTimer = null
    }
    this.setStatus('closed')
  }

  /** 手动重试（登录态变化 / 用户点重试），可顺带更新登录凭据 */
  retry(uid?: number, buvid?: string) {
    if (!this.roomId) return
    if (uid !== undefined) this.uid = uid
    if (buvid !== undefined) this.buvid = buvid
    this.stopped = true
    this.cleanupSocket()
    if (this.reconnectTimer) clearTimeout(this.reconnectTimer)
    this.reconnectTimer = null
    this.stopped = false
    this.attempt = 0
    this.hostIndex = 0
    void this.open()
  }

  private async handlePackets(bytes: Uint8Array): Promise<void> {
    const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength)
    let offset = 0
    while (offset + 16 <= bytes.length) {
      const total = view.getUint32(offset)
      if (total < 16 || offset + total > bytes.length) break
      const headerLen = view.getUint16(offset + 4)
      const ver = view.getUint16(offset + 6)
      const op = view.getInt32(offset + 8)
      const body = bytes.subarray(offset + Math.max(headerLen, 16), offset + total)

      if (op === OP.AUTH_REPLY) {
        const j = readJson(textDec.decode(body))
        if (j?.code === 0) {
          this.attempt = 0
          this.setStatus('open')
        } else {
          const msg = String(j?.message || j?.msg || `弹幕认证失败（${j?.code ?? '?'}）`)
          // 登录态失效时自动降级匿名再试一轮，避免 Cookie 过期就完全看不了弹幕
          if (this.uid !== 0) {
            this.uid = 0
            this.lastError = '登录态认证失败，已改用匿名连接'
            this.cleanupSocket()
            this.scheduleReconnect()
          } else {
            this.lastError = msg
            this.cleanupSocket()
            this.scheduleReconnect()
          }
          return
        }
      } else if (op === OP.HEARTBEAT_REPLY) {
        if (body.length >= 4) {
          this.events?.onViewer(new DataView(body.buffer, body.byteOffset, 4).getUint32(0))
        }
      } else if (op === OP.MESSAGE) {
        try {
          const raw = await inflate(ver, body)
          this.handlePayload(raw)
        } catch {
          /* 坏包跳过 */
        }
      }
      offset += total
    }
  }

  /** 解压后可能还是多个带头包，也可能是纯 JSON */
  private handlePayload(raw: Uint8Array) {
    const view = new DataView(raw.buffer, raw.byteOffset, raw.byteLength)
    let offset = 0
    let parsedAny = false
    while (offset + 16 <= raw.length) {
      const total = view.getUint32(offset)
      if (total < 16 || offset + total > raw.length) break
      const headerLen = view.getUint16(offset + 4)
      const ver = view.getUint16(offset + 6)
      const op = view.getInt32(offset + 8)
      const body = raw.subarray(offset + Math.max(headerLen, 16), offset + total)
      if (op === OP.MESSAGE) {
        if (ver === 2 || ver === 3) {
          parsedAny = true
          inflate(ver, body)
            .then((inner) => this.handlePayload(inner))
            .catch(() => {})
        } else {
          this.parseJsonLines(textDec.decode(body))
          parsedAny = true
        }
      } else if (op === OP.AUTH_REPLY) {
        const j = readJson(textDec.decode(body))
        if (j?.code === 0) {
          this.attempt = 0
          this.setStatus('open')
        }
      }
      offset += total
    }
    if (!parsedAny) {
      const text = textDec.decode(raw).trim()
      if (text.startsWith('{')) this.parseJsonLines(text)
    }
  }

  private parseJsonLines(text: string) {
    for (const line of text.split('\n')) {
      const j = readJson(line)
      if (j) this.dispatch(j)
    }
  }

  private emit(m: Omit<ChatMsg, 'seq'>) {
    this.events?.onMsg({ ...m, seq: ++this.seq })
  }

  private dispatch(cmd: any) {
    const fn = String(cmd?.cmd || '').split(':')[0]
    try {
      if (fn === 'DANMU_MSG') {
        const info = cmd.info || []
        const meta = info[0] || {}
        const user = info[2] || []
        const medal = info[3] || []
        const text = String(info[1] ?? '').trim()
        if (!text) return
        let replyTo = ''
        try {
          const extra = typeof meta[15] === 'object' ? meta[15]?.extra : meta.extra
          const e = typeof extra === 'string' ? JSON.parse(extra) : extra
          if (e?.reply_uname) replyTo = String(e.reply_uname)
        } catch {
          /* ignore */
        }
        this.emit({
          kind: 'danmu',
          text,
          uname: String(user[1] ?? ''),
          uid: Number(user[0] || 0),
          dmid: String(meta[7] || ''),
          color: Number(meta[3] || 0xffffff) & 0xffffff,
          medal: medal[1] ? String(medal[1]) : undefined,
          medalLevel: medal[0] ? Number(medal[0]) : undefined,
          self: this.uid > 0 && Number(user[0] || 0) === this.uid,
          replyTo: replyTo || undefined,
        })
      } else if (fn === 'SUPER_CHAT_MESSAGE') {
        const d = cmd.data || {}
        const text = String(d.message || '').trim()
        if (!text) return
        const user = d.user_info || {}
        const rawTime = Number(d.time || 0)
        // 老版本给秒，新版本给毫秒；统一成毫秒后交给悬浮窗
        const scDuration = rawTime > 0 ? (rawTime < 1000 ? rawTime * 1000 : rawTime) : undefined
        const scId = String(d.id || '')
        this.emit({
          kind: 'sc',
          text,
          uname: String(user.uname || ''),
          uid: Number(user.uid || 0),
          avatar: String(user.face || ''),
          price: Number(d.price || 0),
          scId: scId || undefined,
          scDuration,
          dmid: scId ? `sc-${scId}` : undefined,
        })
      } else if (fn === 'USER_TOAST_MSG' || fn === 'USER_TOAST_MSG_V2' || fn === 'GUARD_BUY') {
        const d = cmd.data || {}
        this.emit({
          kind: 'guard',
          text: `开通了 ${d.unit || d.gift_name || '舰长'}${Number(d.num || 1) > 1 ? ` ×${d.num}` : ''}`,
          uname: String(d.username || d.uname || ''),
          price: Number(d.price || 0),
        })
      } else if (fn === 'SEND_GIFT' || fn === 'SEND_GIFT_V2') {
        const d = cmd.data || {}
        const name = String(d.giftName || '礼物')
        const num = Number(d.num || 1)
        const totalCoin = Number(d.total_coin || 0)
        const price = totalCoin > 0 ? Math.round(totalCoin / 1000) : undefined
        this.emit({
          kind: 'gift',
          text: `送出 ${name} ×${num}`,
          uname: String(d.uname || ''),
          price: price && price >= 1 ? price : undefined,
        })
      } else if (fn === 'LIVE' || fn === 'PREPARING') {
        this.emit({
          kind: 'sys',
          text: fn === 'LIVE' ? '主播开播了' : '主播下播了',
          uname: '',
        })
      } else if (fn === 'WATCHED_CHANGE') {
        // data.num 是累计「看过」人数，不等于当前人气；人气以心跳 OP=3 为准
      }
    } catch {
      /* 单条坏消息不影响连接 */
    }
  }
}