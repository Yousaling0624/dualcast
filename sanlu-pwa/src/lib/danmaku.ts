/**
 * B 站直播弹幕 WebSocket 客户端。
 *
 * 协议：https://github.com/SocialSisterYi/bilibili-API-collect
 * - 连接后先发 AUTH（op=7），服务器回 op=8；
 * - 每 30 秒发一次心跳（op=2），服务器回人气（op=3）；
 * - 弹幕等命令包 op=5，payload ver=0 原始 JSON、ver=1 zlib、ver=3 brotli。
 */

import { fetchDanmuInfo } from './bili'

const OP = {
  HEARTBEAT: 2,
  HEARTBEAT_REPLY: 3,
  MESSAGE: 5,
  AUTH: 7,
  AUTH_REPLY: 8,
} as const

export type ChatKind = 'danmu' | 'gift' | 'sc' | 'guard' | 'sys'

export interface ChatMsg {
  seq: number
  kind: ChatKind
  text: string
  uname: string
  /** 主播弹幕颜色（B 站十进制色） */
  color?: number
  /** SC / 礼物 / 舰长金额 */
  price?: number
  /** 粉丝牌名 */
  medal?: string
  medalLevel?: number
  self?: boolean
}

export type DanmakuStatus = 'idle' | 'connecting' | 'open' | 'reconnecting' | 'closed'

export interface DanmakuEvents {
  onMsg: (m: ChatMsg) => void
  onStatus: (s: DanmakuStatus) => void
  onViewer: (n: number) => void
}

let brotliDecompress: ((u8: Uint8Array) => Uint8Array) | null = null

async function ensureBrotli() {
  if (brotliDecompress) return
  const mod = (await import('brotli-wasm')) as unknown as Promise<{
    decompress: (u8: Uint8Array) => Uint8Array
  }>
  const lib = await mod
  brotliDecompress = (u8) => lib.decompress(u8)
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
    // zlib
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

export class DanmakuClient {
  private ws: WebSocket | null = null
  private heartbeatTimer: number | null = null
  private reconnectTimer: number | null = null
  private stopped = true
  private roomId = 0
  private events!: DanmakuEvents
  private seq = 1
  private backoff = 1000

  async connect(roomId: number, events: DanmakuEvents) {
    this.stop()
    this.roomId = roomId
    this.events = events
    this.stopped = false
    await this.open()
  }

  private setStatus(s: DanmakuStatus) {
    this.events.onStatus(s)
  }

  private async open() {
    if (this.stopped) return
    this.setStatus('connecting')
    try {
      const info = await fetchDanmuInfo(this.roomId)
      if (this.stopped) return
      const pick = info.hosts[0]
      const url = `wss://${pick.host}:${pick.wss_port}/sub`
      const ws = new WebSocket(url)
      ws.binaryType = 'arraybuffer'
      this.ws = ws

      ws.onopen = () => {
        if (this.stopped) return
        this.backoff = 1000
        const auth = JSON.stringify({
          uid: 0,
          roomid: this.roomId,
          protover: 3,
          platform: 'web',
          type: 2,
          key: info.token,
        })
        ws.send(pack(OP.AUTH, textEnc.encode(auth)))
        this.heartbeatTimer = window.setInterval(() => {
          if (ws.readyState === WebSocket.OPEN) ws.send(pack(OP.HEARTBEAT, new Uint8Array(0)))
        }, 30_000)
        ws.send(pack(OP.HEARTBEAT, new Uint8Array(0)))
      }

      ws.onmessage = (ev) => {
        if (this.stopped) return
        const data = new Uint8Array(ev.data as ArrayBuffer)
        this.handlePackets(data).catch(() => {})
      }

      ws.onclose = () => {
        this.cleanupTimers()
        if (!this.stopped) this.scheduleReconnect()
      }
      ws.onerror = () => {
        try {
          ws.close()
        } catch {
          /* noop */
        }
      }
    } catch {
      this.cleanupTimers()
      if (!this.stopped) this.scheduleReconnect()
    }
  }

  private scheduleReconnect() {
    this.setStatus('reconnecting')
    this.reconnectTimer = window.setTimeout(
      () => {
        this.backoff = Math.min(this.backoff * 2, 15_000)
        this.open()
      },
      this.backoff
    )
  }

  private cleanupTimers() {
    if (this.heartbeatTimer) {
      clearInterval(this.heartbeatTimer)
      this.heartbeatTimer = null
    }
    try {
      this.ws?.close()
    } catch {
      /* noop */
    }
    this.ws = null
  }

  stop() {
    this.stopped = true
    this.cleanupTimers()
    if (this.reconnectTimer) {
      clearTimeout(this.reconnectTimer)
      this.reconnectTimer = null
    }
    this.setStatus('closed')
  }

  private async handlePackets(bytes: Uint8Array) {
    const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength)
    let offset = 0
    while (offset + 16 <= bytes.length) {
      const total = view.getUint32(offset)
      if (total < 16 || offset + total > bytes.length) break
      const headerLen = view.getUint16(offset + 4)
      const ver = view.getUint16(offset + 6)
      const op = view.getInt32(offset + 8)
      const body = bytes.subarray(offset + headerLen, offset + total)

      if (op === OP.AUTH_REPLY) {
        this.setStatus('open')
      } else if (op === OP.HEARTBEAT_REPLY && body.length >= 4) {
        this.events.onViewer(new DataView(body.buffer, body.byteOffset, 4).getUint32(0))
      } else if (op === OP.MESSAGE) {
        const raw = await inflate(ver, body)
        this.handlePayload(raw)
      }
      offset += total
    }
  }

  private handlePayload(raw: Uint8Array) {
    // 解压后可能还是多个带头的包
    const view = new DataView(raw.buffer, raw.byteOffset, raw.byteLength)
    let offset = 0
    let parsedAny = false
    while (offset + 16 <= raw.length) {
      const total = view.getUint32(offset)
      if (total < 16 || offset + total > raw.length) break
      const headerLen = view.getUint16(offset + 4)
      const op = view.getInt32(offset + 8)
      const body = raw.subarray(offset + headerLen, offset + total)
      if (op === OP.MESSAGE) {
        try {
          const text = textDec.decode(body)
          for (const line of text.split('\n')) {
            if (!line.trim()) continue
            try {
              this.dispatch(JSON.parse(line))
              parsedAny = true
            } catch {
              /* 跳过坏行 */
            }
          }
        } catch {
          /* skip */
        }
      }
      offset += total
    }
    // 某些回包解压后就是纯 JSON（无嵌套头）
    if (!parsedAny) {
      try {
        const text = textDec.decode(raw)
        if (text.startsWith('{')) this.dispatch(JSON.parse(text))
      } catch {
        /* skip */
      }
    }
  }

  private dispatch(cmd: any) {
    const fn = cmd?.cmd || ''
    const seq = ++this.seq
    try {
      if (fn === 'DANMU_MSG' || fn.startsWith('DANMU_MSG:')) {
        const info = cmd.info || []
        this.events.onMsg({
          seq,
          kind: 'danmu',
          text: String(info[1] ?? ''),
          uname: String(info[2]?.[2] ?? ''),
          color: Number(info[2]?.[0] ?? 0) || undefined,
          medal: info[3]?.[1] ? String(info[3][1]) : undefined,
          medalLevel: info[3]?.[0] ? Number(info[3][0]) : undefined,
        })
      } else if (fn === 'SUPER_CHAT_MESSAGE') {
        const d = cmd.data || {}
        this.events.onMsg({
          seq,
          kind: 'sc',
          text: String(d.message || ''),
          uname: String(d.user_info?.uname || ''),
          price: Number(d.price || 0),
        })
      } else if (fn === 'USER_TOAST_MSG' || fn === 'USER_TOAST_MSG_V2') {
        const d = cmd.data || {}
        this.events.onMsg({
          seq,
          kind: 'guard',
          text: `开通了 ${d.unit || '舰长'}`,
          uname: String(d.username || ''),
          price: Number(d.price || 0),
        })
      } else if (fn === 'SEND_GIFT') {
        const d = cmd.data || {}
        const price = Number(d.price || 0) * Number(d.num || 1) / 1000
        this.events.onMsg({
          seq,
          kind: 'gift',
          text: `送出 ${d.giftName || '礼物'} ×${d.num || 1}`,
          uname: String(d.uname || ''),
          price: price >= 1 ? Math.round(price) : undefined,
        })
      } else if (fn === 'LIVE' || fn === 'PREPARING') {
        this.events.onMsg({
          seq,
          kind: 'sys',
          text: fn === 'LIVE' ? '开播了，刷新播放地址' : '主播下播了',
          uname: '',
        })
      }
    } catch {
      /* skip */
    }
  }
}
