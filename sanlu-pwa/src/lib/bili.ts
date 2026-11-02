/**
 * B 站接口封装 —— 浏览器直连优先，代理兜底。
 *
 * 流量原则：页面只做分发，媒体与 API 流量由用户浏览器直连 B 站，
 * Cloudflare 只托管静态资源；同域 /api 代理仅作兜底与登录态中转（低频小请求）。
 *
 * 直连通道（GET）：
 *   1. credentials:'include' —— 浏览器若登录过 bilibili.com，自动带上原生
 *      SESSDATA/buvid，风控最友好（要求 B 站回 ACAO+ACAC，否则浏览器拦截）；
 *   2. credentials:'omit' —— 匿名直连；
 *   3. 同域代理 —— 登录态由 x-bili-cookie 中转，getDanmuInfo 被风控时
 *      Worker 自动抓 w_webid 重试。
 * 会话内探测一次直连是否可用，可用后不再打代理。
 */

const LIVE_HOST = 'https://api.live.bilibili.com'
const BILI_HOST = 'https://api.bilibili.com'

export interface RoomInfo {
  roomId: number
  shortId: number
  title: string
  uname: string
  face: string
  liveStatus: number // 0 未开播 1 直播 2 轮播
  liveTime: number
}

export interface StreamCandidate {
  protocol: 'flv' | 'hls'
  urls: string[]
  qn: number
}

export interface RoomPlay {
  roomId: number
  liveStatus: number
  qn: number
  acceptQn: { qn: number; desc: string }[]
  streams: StreamCandidate[]
}

export const QN_DESC: Record<number, string> = {
  30000: '杜比',
  20000: '4K',
  10000: '原画',
  401: '原画',
  400: '蓝光',
  250: '超清',
  150: '高清',
  80: '流畅',
}

export function cookieString(): string {
  try {
    const raw = localStorage.getItem('dc_cookies')
    return raw ? (JSON.parse(raw).cookie_string || '') : ''
  } catch {
    return ''
  }
}

export function saveCookieString(cs: string) {
  localStorage.setItem('dc_cookies', JSON.stringify({ cookie_string: cs }))
}

export function hasCookie(): boolean {
  return /SESSDATA=/.test(cookieString())
}

/* ---------- 双通道核心 ---------- */

/** 这些错误码说明当前通道被风控/身份不对，应换下一级通道重试 */
const RISK_CODES = new Set([-352, -412, -101, -111, -401])

/** null=未探测 true=直连可用 false=本会话直连不可用，全走代理 */
let directState: boolean | null = null

async function tryDirect(url: string, credentials: RequestCredentials): Promise<any | null> {
  try {
    const res = await fetch(url, { credentials, mode: 'cors' })
    if (!res.ok) return null
    const j = await res.json()
    return j && typeof j === 'object' && 'code' in j ? j : null
  } catch {
    return null // CORS 拦截或网络错误
  }
}

/** 中转服务基地址（Deno Deploy 等），空 = 未配置；localStorage 可在设置里改 */
export function getProxyBase(): string {
  const v = (localStorage.getItem('dc_proxy_base') || '').trim()
  return v && /^https:\/\//.test(v) ? v.replace(/\/+$/, '') : ''
}

export function setProxyBase(v: string) {
  localStorage.setItem('dc_proxy_base', v.trim().replace(/\/+$/, ''))
}

/** 同域代理（登录 cookie 经 x-bili-cookie 头中转）；中转配置了则优先走中转 */
async function proxyJson(bucket: 'live' | 'passport' | 'bili', path: string, init?: RequestInit) {
  const headers = new Headers(init?.headers)
  const cs = cookieString()
  if (cs) headers.set('x-bili-cookie', cs)
  const bases: string[] = []
  const relay = getProxyBase()
  if (relay) bases.push(relay)
  bases.push('') // 同域 /api
  let lastErr: unknown = null
  for (const base of bases) {
    try {
      const res = await fetch(`${base}/api/${bucket}/${path}`, { ...init, headers })
      if (!res.ok) throw new Error(`HTTP ${res.status}`)
      return await res.json()
    } catch (e) {
      lastErr = e
    }
  }
  if (!relay) {
    throw new Error('B 站接口被风控：浏览器直连与同域代理均不可用。到 设置 → 中转 配置 Deno Deploy 地址即可修复')
  }
  throw lastErr instanceof Error ? lastErr : new Error('中转服务不可用')
}

/**
 * GET 双通道：直连（带原生 cookie → 匿名）→ 代理。
 * 返回 B 站 JSON；直连拿到非风控错误码时直接返回（如 -400 房间不存在）。
 */
async function dualGet(host: string, bucket: 'live' | 'bili', path: string): Promise<any> {
  const url = `https://${host}/${path}`
  if (directState !== false) {
    let r = await tryDirect(url, 'include')
    if (r && (r.code === 0 || !RISK_CODES.has(r.code))) {
      directState = true
      return r
    }
    r = await tryDirect(url, 'omit')
    if (r && (r.code === 0 || !RISK_CODES.has(r.code))) {
      directState = true
      return r
    }
    if (directState === null) directState = false // 探测失败，本会话固定走代理
  }
  return proxyJson(bucket, path)
}

/* ---------- 房间与播放 ---------- */

/** 房间号/短号/直播间 URL 统一解析成真实 room_id */
export async function initRoom(input: string): Promise<{ roomId: number; shortId: number }> {
  const trimmed = input.trim()
  let id = trimmed
  const m = trimmed.match(/live\.bilibili\.com\/(?:h5\/)?(\d+)/)
  if (m) id = m[1]
  if (!/^\d+$/.test(id)) throw new Error('房间号格式不对')
  const r = await dualGet(LIVE_HOST, 'live', `room/v1/Room/room_init?id=${id}`)
  if (r.code !== 0) throw new Error(r.message || '房间不存在')
  return { roomId: r.data.room_id, shortId: r.data.short_id || 0 }
}

export async function fetchRoomInfo(roomId: number): Promise<RoomInfo> {
  const r = await dualGet(LIVE_HOST, 'live', `xlive/web-room/v1/index/getInfoByRoom?room_id=${roomId}`)
  if (r.code !== 0) throw new Error(r.message || '获取房间信息失败')
  const room = r.data.room_info
  const anchor = r.data.anchor_info?.base_info || {}
  return {
    roomId: room.room_id,
    shortId: room.short_id || 0,
    title: room.title || '',
    uname: anchor.uname || '',
    face: anchor.face || '',
    liveStatus: room.live_status ?? 0,
    liveTime: room.live_start_time || 0,
  }
}

export async function fetchPlay(roomId: number, qn = 0): Promise<RoomPlay> {
  const qs = new URLSearchParams({
    room_id: String(roomId),
    protocol: '0,1',
    format: '0,2',
    codec: '0,1',
    qn: String(qn || 0),
    platform: 'web',
    ptype: '16',
    dolby: '5',
    panorama: '1',
  })
  const r = await dualGet(LIVE_HOST, 'live', `xlive/web-room/v2/index/getRoomPlayInfo?${qs}`)
  if (r.code !== 0) throw new Error(r.message || '获取播放地址失败')
  const data = r.data
  const play = data.playurl_info?.playurl
  const streams: StreamCandidate[] = []
  const acceptQn: { qn: number; desc: string }[] = (play?.accept_qn || []).map((q: number) => ({
    qn: q,
    desc: QN_DESC[q] || `${q}`,
  }))
  let currentQn = 0
  for (const proto of play?.stream || []) {
    for (const fmt of proto.format || []) {
      const isFlv = fmt.format_name === 'flv'
      const isHls = fmt.format_name === 'hls'
      if (!isFlv && !isHls) continue
      for (const codec of fmt.codec || []) {
        currentQn = Math.max(currentQn, codec.current_qn || 0)
        // 只挑 AVC：浏览器软解最稳；flv 只收 avc，hls 收 avc/hevc 里 avc 优先
        if (codec.codec_name !== 'avc' && isFlv) continue
        const urls: string[] = []
        for (const host of codec.host_list || []) {
          urls.push(`https://${host.host}${codec.base_url}${codec.extra}`)
          for (const fb of host.host_fallback || []) {
            urls.push(`https://${fb.host}${codec.base_url}${codec.extra}`)
          }
        }
        if (urls.length) {
          streams.push({ protocol: isFlv ? 'flv' : 'hls', urls, qn: codec.current_qn || 0 })
        }
      }
    }
  }
  // flv 低延迟优先
  streams.sort((a, b) => (a.protocol === 'flv' ? -1 : 1) - (b.protocol === 'flv' ? -1 : 1))
  return { roomId: data.room_id, liveStatus: data.live_status, qn: currentQn, acceptQn, streams }
}

/* ---------- 弹幕 ---------- */

export interface DanmuInfo {
  token: string
  hosts: { host: string; wss_port: number }[]
}

const DEFAULT_DANMU_HOSTS = [
  { host: 'broadcastlv.chat.bilibili.com', wss_port: 443 },
]

export async function fetchDanmuInfo(roomId: number): Promise<DanmuInfo> {
  const r = await dualGet(LIVE_HOST, 'live', `xlive/web-room/v1/index/getDanmuInfo?id=${roomId}&type=ws`)
  if (r.code === 0) {
    return {
      token: r.data.token,
      hosts: r.data.host_list.map((h: any) => ({ host: h.host, wss_port: h.wss_port || 443 })),
    }
  }
  // 全通道拿 token 失败：裸连兜底（部分房间接受空 token 的 auth）
  return { token: '', hosts: DEFAULT_DANMU_HOSTS }
}

export interface SendResult {
  ok: boolean
  message: string
}

/** 发弹幕必须带登录 cookie，固定走代理 */
export async function sendDanmaku(roomId: number, text: string): Promise<SendResult> {
  if (!hasCookie()) return { ok: false, message: '先扫码登录 B 站账号' }
  const csrf = getCookieValue('bili_jct')
  if (!csrf) return { ok: false, message: '登录态不完整，重新扫码登录试试' }
  const body = new URLSearchParams({
    bubble: '0',
    msg: text,
    color: '16777215',
    mode: '1',
    fontsize: '25',
    rnd: String(Math.floor(Date.now() / 1000)),
    roomid: String(roomId),
    csrf,
    csrf_token: csrf,
  })
  const r = await proxyJson('live', 'msg/send', {
    method: 'POST',
    headers: { 'content-type': 'application/x-www-form-urlencoded' },
    body: body.toString(),
  })
  if (r.code === 0) return { ok: true, message: '已发送' }
  if (r.code === -101) return { ok: false, message: '登录过期，重新扫码' }
  if (r.code === -111) return { ok: false, message: '校验失败，重新扫码登录' }
  return { ok: false, message: r.message || `发送失败(${r.code})` }
}

function getCookieValue(key: string): string {
  const cs = cookieString()
  const m = cs.match(new RegExp(`(?:^|;\\s*)${key}=([^;]+)`))
  return m ? m[1] : ''
}

/* ---------- 扫码登录（Set-Cookie 需代理解析，固定走代理） ---------- */

export interface QrState {
  qrcodeKey: string
  imageUrl: string
}

export async function qrGenerate(): Promise<QrState> {
  const r = await proxyJson('passport', 'x/passport-login/web/qrcode/generate')
  if (r.code !== 0) throw new Error(r.message || '生成二维码失败')
  return { qrcodeKey: r.data.qrcode_key, imageUrl: r.data.url }
}

export interface QrPollResult {
  code: number // 0 成功 86090 已扫未确认 86101 未扫 86038 过期
  message: string
  cookieString?: string
}

export async function qrPoll(qrcodeKey: string): Promise<QrPollResult> {
  const r = await proxyJson('passport', `x/passport-login/web/qrcode/poll?qrcode_key=${qrcodeKey}`)
  return {
    code: r.code,
    message: r.message || '',
    cookieString: r.cookie_string,
  }
}

/** 拿设备指纹 buvid3/buvid4，发弹幕时和登录 cookie 一起带上 */
export async function fetchBuvid(): Promise<void> {
  try {
    const r = await dualGet(BILI_HOST, 'bili', 'x/frontend/finger/spi')
    if (r.code === 0 && r.data?.b_3) {
      let cs = cookieString()
      cs = mergeCookie(cs, `buvid3=${r.data.b_3}; buvid4=${r.data.b_4}`)
      saveCookieString(cs)
    }
  } catch {
    // 拿不到也不影响看直播，只可能影响发弹幕
  }
}

function mergeCookie(base: string, extra: string): string {
  const map = new Map<string, string>()
  for (const part of `${base}; ${extra}`.split(';')) {
    const eq = part.indexOf('=')
    if (eq > 0) map.set(part.slice(0, eq).trim(), part.slice(eq + 1).trim())
  }
  return [...map.entries()].map(([k, v]) => `${k}=${v}`).join('; ')
}
