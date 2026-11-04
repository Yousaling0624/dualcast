/**
 * B 站直播接口封装。
 *
 * 接口路径与参数参考 web 端 live.bilibili.com 与 PiliPlus：
 * - getRoomPlayInfo / getDanmuInfo / msg/send 都带 WBI 签名；
 * - 发弹幕除正文外带 room_type / jumpfrom / statistics，降低被风控概率；
 * - 房间信息用轻量的 get_info，失败再退回 getInfoByRoom。
 *
 * 所有请求都走 net.ts 的「直连 → 中转」双通道。
 */
import {
  ApiError,
  cookieString,
  getCookieValue,
  hasCookie,
  mergeCookie,
  requestJson,
  saveCookieString,
} from './net'
import {
  followedLiveFromFollowing,
  followedLiveFromWebList,
  mergeFollowedRooms,
  type FollowedLiveRoom,
} from './followed'
import { clearWbiCache, signParams } from './wbi'

export type { FollowedLiveRoom } from './followed'

const QN_DESC: Record<number, string> = {
  30000: '杜比',
  20000: '4K',
  15000: '2K',
  10000: '原画',
  401: '蓝光',
  400: '蓝光',
  250: '超清',
  150: '高清',
  80: '流畅',
}

export function qnLabel(qn: number): string {
  if (QN_DESC[qn]) return QN_DESC[qn]
  if (qn >= 10000) return '原画'
  if (qn >= 400) return '蓝光'
  if (qn >= 250) return '超清'
  if (qn >= 150) return '高清'
  if (qn > 0) return '流畅'
  return '画质'
}

export interface RoomInfo {
  roomId: number
  shortId: number
  uid: number
  title: string
  uname: string
  face: string
  cover: string
  keyframe: string
  liveStatus: number // 0 未开播 1 直播 2 轮播
  liveTime: number
  online: number
  areaName: string
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

export interface DanmuInfo {
  token: string
  hosts: { host: string; wss_port: number }[]
}

export interface HistoryDanmaku {
  text: string
  uname: string
  uid: number
  dmid: string
  color?: number
  medal?: string
}

export interface BiliAccount {
  mid: number
  name: string
  avatar: string
  level: number
}

export interface SendResult {
  ok: boolean
  message: string
}

/* ---------- 房间 ---------- */

const roomCache = new Map<number, { at: number; info: RoomInfo }>()
const playCache = new Map<string, { at: number; play: RoomPlay }>()
const ROOM_TTL = 20_000
const PLAY_TTL = 15_000

export function parseRoomInput(raw: string): string {
  const input = raw.trim()
  if (!input) throw new ApiError(-400, '请先填写直播间链接或房间号')
  const fromUrl = input.match(/live\.bilibili\.com\/(?:h5\/|blanc\/)?(\d+)/)
  if (fromUrl) return fromUrl[1]
  if (/^\d{1,12}$/.test(input)) return input
  throw new ApiError(-400, '没认出 B 站房间号，试试 live.bilibili.com/12345')
}

/** 房间号 / 短号 / 直播间链接 → 真实 room_id */
export async function initRoom(input: string): Promise<{
  roomId: number
  shortId: number
  uid: number
  liveStatus: number
}> {
  const id = parseRoomInput(input)
  const r = await requestJson('live', `room/v1/Room/room_init?id=${encodeURIComponent(id)}`)
  if (r.code !== 0) throw new ApiError(r.code, r.message || r.msg || '房间不存在')
  return {
    roomId: Number(r.data?.room_id || 0),
    shortId: Number(r.data?.short_id || 0),
    uid: Number(r.data?.uid || 0),
    liveStatus: Number(r.data?.live_status || 0),
  }
}

function normUrl(u: string): string {
  if (!u) return ''
  if (u.startsWith('//')) return `https:${u}`
  if (u.startsWith('http://')) return u.replace(/^http:\/\//, 'https://')
  return u
}

function formatCount(value: number): string {
  if (value >= 100_000_000) return `${(value / 100_000_000).toFixed(1)}亿`
  if (value >= 10_000) return `${(value / 10_000).toFixed(1)}万`
  return String(value)
}

export { formatCount }

/** 拉房间信息（含主播、封面、人气、分区） */
export async function fetchRoomInfo(roomId: number): Promise<RoomInfo> {
  const hit = roomCache.get(roomId)
  if (hit && Date.now() - hit.at < ROOM_TTL) return hit.info

  const anchorTask = requestJson(
    'live',
    `live_user/v1/UserInfo/get_anchor_in_room?roomid=${roomId}`
  ).catch(() => null)

  let info: RoomInfo | null = null
  try {
    const r = await requestJson('live', `room/v1/Room/get_info?room_id=${roomId}`)
    if (r.code === 0 && r.data) {
      const d = r.data
      info = {
        roomId: Number(d.room_id || roomId),
        shortId: Number(d.short_id || 0),
        uid: Number(d.uid || 0),
        title: String(d.title || `房间 ${roomId}`),
        uname: '',
        face: '',
        cover: normUrl(String(d.user_cover || '')),
        keyframe: normUrl(String(d.keyframe || '')),
        liveStatus: Number(d.live_status ?? 0),
        liveTime: Number(d.live_start_time || 0),
        online: Number(d.online || 0),
        areaName: String(d.area_name || ''),
      }
    }
  } catch {
    /* fall through */
  }

  if (!info) {
    const r = await requestJson(
      'live',
      `xlive/web-room/v1/index/getInfoByRoom?room_id=${roomId}`
    )
    if (r.code !== 0) throw new ApiError(r.code, r.message || r.msg || '获取房间信息失败')
    const room = r.data?.room_info || {}
    const base = r.data?.anchor_info?.base_info || {}
    info = {
      roomId: Number(room.room_id || roomId),
      shortId: Number(room.short_id || 0),
      uid: Number(room.uid || 0),
      title: String(room.title || `房间 ${roomId}`),
      uname: String(base.uname || ''),
      face: normUrl(String(base.face || '')),
      cover: normUrl(String(room.user_cover || room.cover || '')),
      keyframe: normUrl(String(room.keyframe || '')),
      liveStatus: Number(room.live_status ?? 0),
      liveTime: Number(room.live_start_time || 0),
      online: Number(room.online || 0),
      areaName: String(room.area_name || ''),
    }
  }

  // 主播昵称/头像单独一个接口，失败不影响房间信息
  const anchor: any = await anchorTask
  const a = anchor?.data?.info
  if (a) {
    info.uname = String(a.uname || info.uname || '')
    info.face = normUrl(String(a.face || info.face || ''))
  }

  roomCache.set(roomId, { at: Date.now(), info })
  return info
}

/* ---------- 关注列表 ---------- */

const FOLLOW_TTL = 45_000
let followCache: { at: number; list: FollowedLiveRoom[] } | null = null

export function clearFollowCache() {
  followCache = null
}

/**
 * 关注的正在直播的主播。
 * 优先用 PC 端开播列表（一次拿全）；被风控或字段不可解析时，
 * 回退到分页的关注列表接口，逐页筛 live_status。
 */
export async function fetchFollowedLive(force = false): Promise<FollowedLiveRoom[]> {
  if (!hasCookie()) throw new ApiError(-101, '登录 B 站账号后才能读取关注列表')
  if (!force && followCache && Date.now() - followCache.at < FOLLOW_TTL) {
    return followCache.list
  }

  const live = await fetchFollowedFromWebList()
  if (live !== null) {
    followCache = { at: Date.now(), list: live }
    return live
  }

  const list = await fetchFollowedFromFollowing()
  followCache = { at: Date.now(), list }
  return list
}

/** 返回 null 表示这个接口不可用，调用方应回退；空数组表示确实没人开播 */
async function fetchFollowedFromWebList(): Promise<FollowedLiveRoom[] | null> {
  try {
    const r = await requestJson(
      'live',
      'xlive/web-ucenter/v1/xfetter/GetWebList?hit_ab=true'
    )
    if (Number(r?.code) === -101) {
      throw new ApiError(-101, '登录状态过期了，在设置里重新登录')
    }
    if (Number(r?.code) !== 0) return null
    const rawCount = Array.isArray(r.data?.rooms)
      ? r.data.rooms.length
      : Array.isArray(r.data?.list)
        ? r.data.list.length
        : 0
    const rooms = followedLiveFromWebList(r.data)
    // 接口说有直播，但 hit_ab 把房间号等字段清零了：回退到分页接口
    if (rawCount > 0 && rooms.length === 0) return null
    return rooms
  } catch (e) {
    if (e instanceof ApiError && e.code === -101) throw e
    return null
  }
}

async function fetchFollowedFromFollowing(): Promise<FollowedLiveRoom[]> {
  const url = (page: number) =>
    `xlive/web-ucenter/user/following?page=${page}&page_size=30&ignoreRecord=1&hit_ab=true`

  const first = await requestJson('live', url(1))
  if (Number(first?.code) === -101) {
    throw new ApiError(-101, '登录状态过期了，在设置里重新登录')
  }
  if (Number(first?.code) !== 0) {
    throw new ApiError(
      Number(first?.code) || -1,
      first?.message || first?.msg || '读取关注列表失败'
    )
  }

  let list = followedLiveFromFollowing(first.data)
  const totalPage = Number(first.data?.totalPage || first.data?.total_page || 1)
  // 最多补查 12 页（约 360 个关注），避免关注很多时打爆接口
  const pages = Math.min(12, Math.max(1, totalPage))
  if (pages > 1) {
    const rest = await Promise.all(
      Array.from({ length: pages - 1 }, (_, i) =>
        requestJson('live', url(i + 2)).catch(() => null)
      )
    )
    for (const r of rest) {
      if (Number(r?.code) === 0) {
        list = mergeFollowedRooms([...list, ...followedLiveFromFollowing(r.data)])
      }
    }
  }
  return list
}

/* ---------- 播放地址 ---------- */

function collectPlay(data: any, roomId: number): RoomPlay {
  const playurl = data?.playurl_info?.playurl
  const streams: StreamCandidate[] = []
  const qns = new Set<number>()
  let currentQn = 0

  for (const stream of playurl?.stream || []) {
    const protocol = String(stream.protocol_name || '')
    for (const format of stream.format || []) {
      const fmt = String(format.format_name || '')
      const isFlv = fmt === 'flv'
      const isHls = fmt === 'hls'
      if (!isFlv && !isHls) continue
      for (const codec of format.codec || []) {
        const codecName = String(codec.codec_name || '')
        const qn = Number(codec.current_qn || 0)
        if (qn) qns.add(qn)
        for (const aq of codec.accept_qn || []) {
          const n = Number(aq)
          if (n) qns.add(n)
        }
        currentQn = Math.max(currentQn, qn)
        // FLV 只收 AVC；HLS 收 AVC/HEVC（Safari 原生 HLS 能放 HEVC）
        if (isFlv && codecName !== 'avc') continue
        const urls: string[] = []
        for (const info of codec.url_info || []) {
          const u = normUrl(String(info.host || '') + String(codec.base_url || '') + String(info.extra || ''))
          if (u) urls.push(u)
        }
        for (const host of codec.host_list || []) {
          const h = String(host.host || '')
          if (!h) continue
          urls.push(normUrl(`https://${h}${codec.base_url || ''}${codec.extra || ''}`))
          for (const fb of host.host_fallback || []) {
            if (fb?.host) urls.push(normUrl(`https://${fb.host}${codec.base_url || ''}${codec.extra || ''}`))
          }
        }
        const unique = [...new Set(urls.filter(Boolean))]
        if (!unique.length) continue
        const priority = isFlv ? (codecName === 'avc' ? 0 : 3) : codecName === 'avc' ? 1 : 2
        streams.push({ protocol: isFlv ? 'flv' : 'hls', urls: unique, qn })
        ;(streams[streams.length - 1] as any).__priority = priority
      }
    }
    // protocol_name 只是辅助；format 已经区分 flv/hls
    void protocol
  }

  streams.sort((a, b) => ((a as any).__priority ?? 9) - ((b as any).__priority ?? 9))
  for (const s of streams) delete (s as any).__priority

  const acceptQn = [...qns]
    .sort((a, b) => b - a)
    .map((qn) => ({ qn, desc: qnLabel(qn) }))

  return {
    roomId: Number(data?.room_id || roomId),
    liveStatus: Number(data?.live_status ?? 0),
    qn: Number(playurl?.g_qn || 0) || currentQn,
    acceptQn,
    streams,
  }
}

export async function fetchPlay(roomId: number, qn = 0): Promise<RoomPlay> {
  const key = `${roomId}:${qn}`
  const hit = playCache.get(key)
  if (hit && Date.now() - hit.at < PLAY_TTL) return hit.play

  const params = {
    room_id: roomId,
    protocol: '0,1',
    format: '0,1,2',
    codec: '0,1',
    qn,
    platform: 'web',
    ptype: 8,
    dolby: 5,
    panorama: 1,
    web_location: '444.8',
  }

  const tryOnce = async (signed: boolean) => {
    const qs = signed ? await signParams(params) : new URLSearchParams(
      Object.entries(params).map(([k, v]) => [k, String(v)])
    ).toString()
    return requestJson('live', `xlive/web-room/v2/index/getRoomPlayInfo?${qs}`)
  }

  let r = await tryOnce(true)
  if (r?.code === -352) {
    // mixinKey 可能过期；清缓存再签一次
    clearWbiCache()
    r = await tryOnce(true)
  }
  if (r?.code === -352) r = await tryOnce(false)

  if (r.code !== 0) throw new ApiError(r.code, r.message || r.msg || '获取播放地址失败')
  const play = collectPlay(r.data, roomId)
  playCache.set(key, { at: Date.now(), play })
  return play
}

export function clearPlayCache() {
  playCache.clear()
  roomCache.clear()
}

/* ---------- 弹幕 ---------- */

const DEFAULT_DANMU_HOSTS = [{ host: 'broadcastlv.chat.bilibili.com', wss_port: 443 }]

export async function fetchDanmuInfo(roomId: number): Promise<DanmuInfo> {
  const tryOnce = async (signed: boolean) => {
    const params = { id: roomId, type: 0, web_location: '444.8' }
    const qs = signed
      ? await signParams(params)
      : new URLSearchParams(Object.entries(params).map(([k, v]) => [k, String(v)])).toString()
    return requestJson('live', `xlive/web-room/v1/index/getDanmuInfo?${qs}`)
  }

  let r: any = null
  try {
    r = await tryOnce(true)
    if (r?.code === -352) {
      clearWbiCache()
      r = await tryOnce(true)
    }
    if (r?.code === -352) r = await tryOnce(false)
  } catch {
    r = null
  }

  if (r?.code === 0) {
    const hosts = (r.data?.host_list || [])
      .map((h: any) => ({ host: String(h.host || ''), wss_port: Number(h.wss_port || 443) }))
      .filter((h: any) => h.host)
    const token = String(r.data?.token || '')
    if (hosts.length) return { token, hosts }
    // 有 token 但没给 host 的少数情况，用官方默认域名
    if (token) return { token, hosts: DEFAULT_DANMU_HOSTS }
  }

  // 拿不到 token 时不要用空 token 硬连（服务端会直接断开），
  // 交给 DanmakuClient 重试；重试期间用户仍能看画面。
  throw new ApiError(
    Number(r?.code || -502),
    r?.message || r?.msg || '获取弹幕服务器失败，检查「中转」设置后重试'
  )
}

/** 进房先拉一波历史弹幕，聊天室不至于空白 */
export async function fetchDanmakuHistory(roomId: number): Promise<HistoryDanmaku[]> {
  try {
    const r = await requestJson(
      'live',
      `xlive/web-room/v1/dM/gethistory?roomid=${roomId}`
    )
    if (r.code !== 0) return []
    const list: any[] = r.data?.room || []
    return list
      .slice(-30)
      .map((m) => {
        const user = m.user || {}
        const base = user.base || {}
        const check = m.check_info || {}
        const color = Number(m.color || check.color || 0xffffff)
        return {
          text: String(m.text || ''),
          uname: String(m.nickname || base.name || user.uname || ''),
          uid: Number(m.uid || user.uid || 0),
          dmid: String(m.id_str || m.dmid || ''),
          color: color > 0 ? color & 0xffffff : 0xffffff,
          medal: base.medal?.name ? String(base.medal.name) : undefined,
        }
      })
      .filter((m) => m.text)
  } catch {
    return []
  }
}

/* ---------- 发弹幕 ---------- */

export async function sendDanmaku(roomId: number, text: string): Promise<SendResult> {
  if (!hasCookie()) return { ok: false, message: '先扫码登录 B 站账号' }
  const csrf = getCookieValue('bili_jct')
  if (!csrf) return { ok: false, message: '登录态不完整，重新扫码登录试试' }

  const signed = await signParams({ web_location: '444.8' })
  const body = new URLSearchParams({
    bubble: '0',
    msg: text,
    color: '16777215',
    mode: '1',
    room_type: '0',
    jumpfrom: '0',
    fontsize: '25',
    rnd: String(Math.floor(Date.now() / 1000)),
    roomid: String(roomId),
    csrf,
    csrf_token: csrf,
    statistics: '{"appId":100,"platform":5}',
  })

  let r: any
  try {
    r = await requestJson('live', `msg/send?${signed}`, {
      method: 'POST',
      headers: { 'content-type': 'application/x-www-form-urlencoded' },
      body: body.toString(),
    })
  } catch (e) {
    return { ok: false, message: e instanceof Error ? e.message : '发送失败' }
  }

  const code = Number(r.code ?? 0)
  const message = String(r.message || r.msg || '')
  if (code === 0 && message.toLowerCase() !== 'f') return { ok: true, message: '已发送' }
  if (code === 0) return { ok: false, message: '这条被内容过滤拦下了，换个说法' }
  return { ok: false, message: sendErrorText(code, message) }
}

function sendErrorText(code: number, raw: string): string {
  switch (code) {
    case -101:
      return '登录状态失效了，去设置里重新登录'
    case -111:
      return '登录校验失败（csrf 不匹配），请重新登录'
    case -403:
      return '当前账号没有发弹幕权限'
    case 10030:
      return '弹幕内容不合法，可能有敏感词'
    case 10031:
      return '发得太快了，等几秒再发'
    case 10032:
      return '你在这个直播间被禁言了'
    case 10033:
      return '账号等级不够，发不了弹幕'
    case 10034:
      return '粉丝勋章等级不够，发不了弹幕'
    case 10035:
      return '弹幕包含敏感内容'
    case 1003212:
      return '相同内容重复发送，换一句试试'
    default:
      return raw || `发送失败（${code}）`
  }
}

/* ---------- 扫码登录 ---------- */

export interface QrState {
  qrcodeKey: string
  imageUrl: string
}

export async function qrGenerate(): Promise<QrState> {
  const r = await requestJson(
    'passport',
    'x/passport-login/web/qrcode/generate?source=main-fe-header'
  )
  if (r.code !== 0) throw new ApiError(r.code, r.message || '生成二维码失败')
  return {
    qrcodeKey: String(r.data?.qrcode_key || ''),
    imageUrl: String(r.data?.url || ''),
  }
}

export interface QrPollResult {
  code: number // 0 成功 86090 已扫未确认 86101 未扫 86038 过期
  message: string
  cookieString?: string
}

export async function qrPoll(qrcodeKey: string): Promise<QrPollResult> {
  const r = await requestJson(
    'passport',
    `x/passport-login/web/qrcode/poll?qrcode_key=${encodeURIComponent(qrcodeKey)}&source=main-fe-header`,
    // 浏览器的跨域请求读不到 Set-Cookie，必须让中转把 Cookie 放进 JSON
    { relayOnly: true }
  )
  if (r.code !== 0) {
    return { code: Number(r.code || -1), message: String(r.message || '登录接口异常') }
  }
  const inner = r.data || {}
  let cookieString = String(r.cookie_string || '')
  if (!cookieString && r.cookies && typeof r.cookies === 'object') {
    cookieString = Object.entries(r.cookies)
      .map(([k, v]) => `${k}=${v}`)
      .join('; ')
  }
  return {
    code: Number(inner.code ?? 0),
    message: String(inner.message || ''),
    cookieString: cookieString || undefined,
  }
}

/** 登录后查昵称/头像；失败返回 null（Cookie 过期等） */
export async function fetchAccount(): Promise<BiliAccount | null> {
  if (!hasCookie()) return null
  try {
    const r = await requestJson('bili', 'x/web-interface/nav')
    const d = r?.data
    if (!d?.isLogin) return null
    return {
      mid: Number(d.mid || 0),
      name: String(d.uname || ''),
      avatar: normUrl(String(d.face || '')),
      level: Number(d.level_info?.current_level || 0),
    }
  } catch {
    return null
  }
}

export async function biliLogout(): Promise<void> {
  const csrf = getCookieValue('bili_jct')
  if (!csrf) return
  try {
    await requestJson('passport', `login/exit/v2?biliCSRF=${encodeURIComponent(csrf)}`)
  } catch {
    /* 失败不影响本地退出 */
  }
}

/** 拿设备指纹 buvid3/buvid4，发弹幕和弹幕认证时一起带上 */
export async function fetchBuvid(): Promise<void> {
  try {
    const r = await requestJson('bili', 'x/frontend/finger/spi')
    if (r.code === 0 && r.data?.b_3) {
      const cs = mergeCookie(
        cookieString(),
        `buvid3=${r.data.b_3}; buvid4=${r.data.b_4 || ''}; b_nut=${Math.floor(Date.now() / 1000)}`
      )
      saveCookieString(cs)
    }
  } catch {
    // 拿不到不影响看直播，只可能影响发弹幕
  }
}

/** 给外部（设置页）用的当前登录态摘要 */
export function loginCookie(): string {
  return cookieString()
}