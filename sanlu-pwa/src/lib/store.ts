/**
 * 全局状态与会话编排：解析房间 → 双路播放 → A 房弹幕 → 聊天室 / 发弹幕。
 *
 * 设计对齐 Android 版：
 * - A 路负责弹幕和声音，B 路负责画面（也可以出声音、同屏弹幕）；
 * - 导入直播间可额外提供弹幕、声音和画面（多路墙）；
 * - 音量/音画偏移按「A 房间 + B 房间」组合记忆；
 * - 聊天室里点一条弹幕可以 +1 / 回复，B 站没有楼中楼，
 *   回复直接拼成「回复内容@昵称:原话」发出去；
 * - 自己发的弹幕本地先插入，8 秒内收到服务端回显就丢掉，避免重复。
 */
import { reactive, watch } from 'vue'
import { DanmakuClient, type ChatMsg, type DanmakuStatus } from './danmaku'
import { StreamPlayer, type StreamSet } from './player'
import {
  biliLogout,
  clearFollowCache,
  fetchAccount,
  fetchBuvid,
  fetchDanmakuHistory,
  fetchPlay,
  fetchRoomInfo,
  formatCount,
  initRoom,
  qnLabel,
  sendDanmaku,
  type BiliAccount,
  type RoomInfo,
  type RoomPlay,
} from './bili'
import { getCookieValue, hasCookie, saveCookieString } from './net'
import { clampOffset, offsetsForSession } from './sync'
import {
  badgeForSource,
  firstGlyph,
  needsSourceBadges,
  normalizeBadgeText,
  PRIMARY_SOURCE_COLOR,
  roomColor,
  SECONDARY_SOURCE_COLOR,
  type BadgeMode,
} from './sources'

export type DanmakuStyle = 'outline' | 'shadow' | 'plain'
export type VideoScale = 'fit' | 'crop' | 'fill'

export interface SlotState {
  input: string
  roomId: number
  shortId: number
  uid: number
  title: string
  uname: string
  face: string
  cover: string
  liveStatus: number
  online: number
  areaName: string
  qn: number
  acceptQn: { qn: number; desc: string }[]
  state: 'idle' | 'loading' | 'ready' | 'error'
  error: string
  /** 浏览器拦截了带声音的自动播放，需要一次用户点击 */
  blocked: boolean
}

export interface Combo {
  aInput: string
  aRoomId: number
  aTitle: string
  aAnchor: string
  bInput: string
  bRoomId: number
  bTitle: string
  bAnchor: string
  updatedAt: number
}

export interface RecentRoom {
  input: string
  roomId: number
  title: string
  uname: string
  usedAt: number
}

/**
 * 导入的额外直播间：
 * - 总是订阅该房间的弹幕；
 * - 打开 commentary 后，把这一路的声音混进解说；
 * - 打开 video 后，还会在画面左下角的多路墙里显示这一路直播。
 * 声音和画面可以只开一个，也可以同时开。
 */
export interface ExtraRoom {
  id: string
  input: string
  roomId: number
  shortId: number
  uid: number
  title: string
  uname: string
  face: string
  liveStatus: number
  online: number
  areaName: string
  /** 气泡模式：纯颜色 / 主播首字 / 自定义字 */
  badgeMode: BadgeMode
  badgeText: string
  color: string
  /** 混入多路声音 */
  commentary: boolean
  /** 加入多路画面（左下角小窗墙） */
  video: boolean
  volume: number
  status: DanmakuStatus
  error: string
  /** 媒体（声音 / 画面）的单独错误，避免和弹幕连接错误互相覆盖 */
  audioError: string
  /** 媒体播放状态，多路墙用来显示加载 / 失败提示 */
  mediaState: 'idle' | 'loading' | 'playing' | 'error'
  blocked: boolean
}

export type ThemeMode = 'auto' | 'light' | 'dark'

export interface BlockedUser {
  uid: number
  name: string
}

export interface Settings {
  /** 界面配色：跟随系统 / 浅色 / 深色 */
  theme: ThemeMode
  danmaku: {
    enabled: boolean
    /** 把 B 路（比赛画面）直播间的弹幕也合并到同一屏 */
    includeB: boolean
    fontScale: number
    alpha: number
    speed: number
    area: number
    showName: boolean
    bold: boolean
    style: DanmakuStyle
    blocked: string[]
    /** 屏蔽的用户；uid 为 0 时按昵称匹配（礼物 / 舰长消息没有 uid） */
    blockedUsers: BlockedUser[]
  }
  video: {
    scale: VideoScale
    defaultQn: number
  }
  volume: { a: number; b: number }
  commentaryWindow: boolean
  /** 全屏视频上方的醒目留言（SC）悬浮窗 */
  sc: {
    enabled: boolean
    duration: number
    max: number
    showPrice: boolean
  }
}

interface RoomProfile {
  aVolume: number
  bVolume: number
  offsetMs: number
  updatedAt: number
}

export type SendState = {
  sending: boolean
  error: string
  sentSeq: number
  cooldownUntil: number
  lastOkAt: number
}

const LIMIT = 20
const MAX_MESSAGES = 300
/** 弹幕画布暂停 / 掉帧时的积压上限，避免回到前台后一次处理上千条 */
const MAX_FEED_BUFFER = 260
const SEND_COOLDOWN = 1500
const SELF_ECHO_WINDOW = 8000
const NAME_LIMIT = 12
const QUOTE_RESERVE = 3

const DEFAULT_SETTINGS: Settings = {
  theme: 'auto',
  danmaku: {
    enabled: true,
    includeB: false,
    fontScale: 1,
    alpha: 0.94,
    speed: 1,
    area: 0.62,
    showName: false,
    bold: true,
    style: 'outline',
    blocked: [],
    blockedUsers: [],
  },
  video: { scale: 'fit', defaultQn: 250 },
  volume: { a: 0.85, b: 1 },
  commentaryWindow: false,
  sc: { enabled: true, duration: 8, max: 3, showPrice: true },
}

function emptySlot(): SlotState {
  return {
    input: '',
    roomId: 0,
    shortId: 0,
    uid: 0,
    title: '',
    uname: '',
    face: '',
    cover: '',
    liveStatus: 0,
    online: 0,
    areaName: '',
    qn: 0,
    acceptQn: [],
    state: 'idle',
    error: '',
    blocked: false,
  }
}

function readJson<T>(key: string, fallback: T): T {
  try {
    const raw = localStorage.getItem(key)
    return raw ? (JSON.parse(raw) as T) : fallback
  } catch {
    return fallback
  }
}

function writeJson(key: string, value: unknown) {
  try {
    localStorage.setItem(key, JSON.stringify(value))
  } catch {
    /* ignore */
  }
}

function loadSettings(): Settings {
  const raw: any = readJson('dc_settings', null)
  const s: Settings = structuredClone(DEFAULT_SETTINGS)
  if (!raw || typeof raw !== 'object') return s
  if (raw.theme === 'light' || raw.theme === 'dark') s.theme = raw.theme
  if (raw.danmaku && typeof raw.danmaku === 'object') {
    Object.assign(s.danmaku, raw.danmaku)
    // 旧版本用 outline 布尔值控制描边
    if (typeof raw.danmaku.outline === 'boolean' && !raw.danmaku.style) {
      s.danmaku.style = raw.danmaku.outline ? 'outline' : 'shadow'
    }
    if (typeof raw.danmaku.bold !== 'boolean') s.danmaku.bold = true
    if (typeof s.danmaku.includeB !== 'boolean') s.danmaku.includeB = false
    if (!Array.isArray(s.danmaku.blocked)) s.danmaku.blocked = []
    const seenUsers = new Set<string>()
    s.danmaku.blockedUsers = Array.isArray(s.danmaku.blockedUsers)
      ? s.danmaku.blockedUsers
          .map((u: any) =>
            typeof u === 'string'
              ? { uid: 0, name: u.trim() }
              : { uid: Number(u?.uid) || 0, name: String(u?.name ?? '').trim() }
          )
          .filter((u) => {
            const key = u.uid > 0 ? `uid:${u.uid}` : u.name ? `name:${u.name}` : ''
            if (!key || seenUsers.has(key)) return false
            seenUsers.add(key)
            return true
          })
          .slice(0, 200)
      : []
  }
  if (raw.video && typeof raw.video === 'object') {
    Object.assign(s.video, raw.video)
  } else if (typeof raw.cover === 'boolean') {
    s.video.scale = raw.cover ? 'crop' : 'fit'
  }
  if (raw.volume && typeof raw.volume === 'object') {
    s.volume.a = Number(raw.volume.a ?? s.volume.a)
    s.volume.b = Number(raw.volume.b ?? s.volume.b)
  }
  if (typeof raw.commentaryWindow === 'boolean') s.commentaryWindow = raw.commentaryWindow
  if (raw.sc && typeof raw.sc === 'object') Object.assign(s.sc, raw.sc)
  s.sc.duration = Math.max(4, Math.min(60, Number(s.sc.duration) || 8))
  s.sc.max = Math.max(1, Math.min(5, Number(s.sc.max) || 3))
  return s
}

function loadCombos(): Combo[] {
  const raw: any = readJson('dc_combos', { list: [] })
  const list: any[] = Array.isArray(raw?.list) ? raw.list : []
  return list
    .map((c) => ({
      aInput: String(c.aInput ?? c.a ?? ''),
      aRoomId: Number(c.aRoomId || 0),
      aTitle: String(c.aTitle || ''),
      aAnchor: String(c.aAnchor || ''),
      bInput: String(c.bInput ?? c.b ?? ''),
      bRoomId: Number(c.bRoomId || 0),
      bTitle: String(c.bTitle || ''),
      bAnchor: String(c.bAnchor || ''),
      updatedAt: Number(c.updatedAt || 0),
    }))
    .filter((c) => c.aInput && c.bInput)
}

function loadRecent(): RecentRoom[] {
  const raw: any = readJson('dc_recent', { list: [] })
  const list: any[] = Array.isArray(raw?.list) ? raw.list : []
  return list
    .map((r) => ({
      input: String(r.input ?? r.roomId ?? ''),
      roomId: Number(r.roomId || 0),
      title: String(r.title || ''),
      uname: String(r.uname || r.anchor || ''),
      usedAt: Number(r.usedAt || 0),
    }))
    .filter((r) => r.input)
}

function loadAccount(): BiliAccount {
  const raw: any = readJson('dc_account', null)
  return {
    mid: Number(raw?.mid || 0),
    name: String(raw?.name || ''),
    avatar: String(raw?.avatar || ''),
    level: Number(raw?.level || 0),
  }
}

function loadProfiles(): Record<string, RoomProfile> {
  return readJson('dc_profiles', {})
}

const BADGE_MODES: BadgeMode[] = ['color', 'initial', 'custom']

function normalizeSource(raw: any): ExtraRoom | null {
  const roomId = Number(raw?.roomId || 0)
  if (!roomId) return null
  const uname = String(raw?.uname || '')
  const mode = BADGE_MODES.includes(raw?.badgeMode) ? (raw.badgeMode as BadgeMode) : 'initial'
  const color = /^#[0-9a-fA-F]{6}$/.test(String(raw?.color || ''))
    ? String(raw.color)
    : roomColor(roomId)
  return {
    id: String(raw?.id || `r${roomId}`),
    input: String(raw?.input || roomId),
    roomId,
    shortId: Number(raw?.shortId || 0),
    uid: Number(raw?.uid || 0),
    title: String(raw?.title || ''),
    uname,
    face: String(raw?.face || ''),
    liveStatus: Number(raw?.liveStatus || 0),
    online: Number(raw?.online || 0),
    areaName: String(raw?.areaName || ''),
    badgeMode: mode,
    badgeText: normalizeBadgeText(String(raw?.badgeText || '')),
    color,
    commentary: !!raw?.commentary,
    video: !!raw?.video,
    volume: Number.isFinite(Number(raw?.volume))
      ? Math.max(0, Math.min(1, Number(raw.volume)))
      : 0.6,
    status: 'idle',
    error: '',
    audioError: '',
    mediaState: 'idle',
    blocked: false,
  }
}

function loadSources(): ExtraRoom[] {
  const raw: any = readJson('dc_sources', { list: [] })
  const list: any[] = Array.isArray(raw?.list) ? raw.list : Array.isArray(raw) ? raw : []
  const seen = new Set<number>()
  return list
    .map(normalizeSource)
    .filter((s): s is ExtraRoom => {
      if (!s || seen.has(s.roomId)) return false
      seen.add(s.roomId)
      return true
    })
}

function sourceRecord(s: ExtraRoom) {
  return {
    id: s.id,
    input: s.input,
    roomId: s.roomId,
    shortId: s.shortId,
    uid: s.uid,
    title: s.title,
    uname: s.uname,
    face: s.face,
    liveStatus: s.liveStatus,
    online: s.online,
    areaName: s.areaName,
    badgeMode: s.badgeMode,
    badgeText: s.badgeText,
    color: s.color,
    commentary: s.commentary,
    video: s.video,
    volume: s.volume,
  }
}

function persistSources() {
  writeJson('dc_sources', { list: store.sources.map(sourceRecord) })
}

export const store = reactive({
  view: 'home' as 'home' | 'live',
  /** 会话是否还活着：回主页后保持播放时仍为 true，LiveView 继续挂在后台 */
  sessionActive: false,
  a: emptySlot(),
  b: emptySlot(),
  messages: [] as ChatMsg[],
  /** 消息列表版本号：增删消息时 +1，聊天室据此节流刷新，避免逐条重渲染长列表 */
  messageRev: 0,
  /** 新 SC 的独立队列，画面上的 SC 悬浮窗消费它，不依赖聊天室滚动位置 */
  scFeed: [] as ChatMsg[],
  viewer: 0,
  danmakuStatus: 'idle' as DanmakuStatus,
  danmakuError: '',
  /** B 路同屏弹幕的连接状态（只有 includeB 打开时才有意义） */
  bDanmakuStatus: 'idle' as DanmakuStatus,
  bDanmakuError: '',
  loggedIn: hasCookie(),
  account: loadAccount(),
  starting: false,
  startError: '',
  swapping: false,
  reconnecting: false,
  qualitySwitching: false,
  sendState: {
    sending: false,
    error: '',
    sentSeq: 0,
    cooldownUntil: 0,
    lastOkAt: 0,
  } as SendState,
  settings: loadSettings(),
  combos: loadCombos(),
  recent: loadRecent(),
  /** 导入的额外弹幕 / 解说直播间 */
  sources: loadSources(),
  sourceBusy: false,
  sourceError: '',
  playing: true,
  sessionVolumes: { a: 0.85, b: 1 },
  sessionOffset: 0,
  commentaryOn: true,
})

/* ---------- 非响应式实例 ---------- */

const danmaku = new DanmakuClient()
/** A+B 同屏时，B 路单独一条弹幕连接 */
const danmakuB = new DanmakuClient()
let playerA: StreamPlayer | null = null
let playerB: StreamPlayer | null = null
let videoAEl: HTMLVideoElement | null = null
let videoBEl: HTMLVideoElement | null = null
/** A / B 各自是否卡在缓冲；一路卡住时把另一路也暂停等它 */
const peerStalled = { a: false, b: false }

/** 导入房间各自的弹幕连接 */
const extraClients = new Map<string, DanmakuClient>()
/**
 * 导入房间中「加入多路声音 / 画面」的那几路。
 * 只开声音时用屏幕外的隐藏 video；开画面时复用多路墙里的可见 video。
 */
const extraPlayers = new Map<
  string,
  { video: HTMLVideoElement; player: StreamPlayer; mode: 'hidden' | 'visible' }
>()
const extraPlayerPending = new Set<string>()
/** 多路墙挂载的可见 video 元素；store 负责在上面创建播放器 */
const sourceVideoEls = new Map<string, HTMLVideoElement>()

const slotStreams: Record<'a' | 'b', StreamSet> = {
  a: { flv: [], hls: [] },
  b: { flv: [], hls: [] },
}

/** 弹幕层订阅：canvas 每帧从这里拿新弹幕去画 */
export const feedBuffer: ChatMsg[] = []

let pushSeq = 0
let historySeq = 0
let resolveToken = { a: 0, b: 0 }
let saveSlotsTimer: number | null = null
let saveProfileTimer: number | null = null
let wakeLock: WakeLockSentinel | null = null
const pendingSelfEcho: { text: string; roomId: number; until: number }[] = []

const profiles = loadProfiles()

watch(
  () => store.settings,
  () => writeJson('dc_settings', store.settings),
  { deep: true }
)

watch(
  () => [store.a.input, store.b.input],
  () => persistSlots(),
  { deep: true }
)

watch(
  () => store.settings.danmaku.includeB,
  (on) => {
    if (on) {
      connectBDanmaku()
    } else {
      disconnectBDanmaku()
    }
  }
)

/* ---------- 消息 ---------- */

function hasDmid(dmid?: string): boolean {
  if (!dmid) return false
  return store.messages.some((m) => m.dmid === dmid)
}

function trimMessages() {
  if (store.messages.length > MAX_MESSAGES) {
    store.messages.splice(0, store.messages.length - MAX_MESSAGES)
  }
}

/** 消息列表有增删时调用，聊天室按版本号做节流刷新 */
function touchMessages() {
  store.messageRev++
}

function consumeSelfEcho(text: string, roomId?: number): boolean {
  const now = Date.now()
  while (pendingSelfEcho.length && pendingSelfEcho[0].until < now) pendingSelfEcho.shift()
  const i = pendingSelfEcho.findIndex(
    (e) => e.text === text && (!roomId || e.roomId === roomId)
  )
  if (i < 0) return false
  pendingSelfEcho.splice(i, 1)
  return true
}

interface SourceMeta {
  id: string
  roomId: number
  name: string
  color: string
  badge: string
}

/** 消息来源的展示信息；主房间固定为 A，B 路同屏时单独用蓝色区分。 */
export function sourceMeta(sourceId?: string): SourceMeta | null {
  if (!sourceId || sourceId === 'a') {
    return {
      id: 'a',
      roomId: store.a.roomId,
      name: store.a.uname || store.a.title || 'A 房间',
      color: PRIMARY_SOURCE_COLOR,
      badge: firstGlyph(store.a.uname) || 'A',
    }
  }
  if (sourceId === 'b') {
    return {
      id: 'b',
      roomId: store.b.roomId,
      name: store.b.uname || store.b.title || 'B 房间',
      color: SECONDARY_SOURCE_COLOR,
      badge: firstGlyph(store.b.uname) || 'B',
    }
  }
  const s = store.sources.find((x) => x.id === sourceId)
  if (!s) return null
  const badge = badgeForSource(s.badgeMode, {
    uname: s.uname,
    custom: s.badgeText,
    color: s.color,
  })
  return {
    id: s.id,
    roomId: s.roomId,
    name: s.uname || s.title || `房间 ${s.roomId}`,
    color: badge.color,
    badge: badge.text,
  }
}

/** 同屏来源不止 A 路时，才需要显示颜色气泡。 */
export function shouldShowSourceBadges(): boolean {
  return needsSourceBadges(
    store.sources.length,
    store.settings.danmaku.includeB,
    store.b.roomId > 0 && store.b.roomId !== store.a.roomId
  )
}

type UserRef = { uid?: number; uname?: string }

function sameBlockedUser(m: UserRef, u: BlockedUser): boolean {
  const uid = Number(m.uid) || 0
  // 有 uid 就认 uid，改昵称也还能挡住；没有 uid 的礼物 / 舰长消息只能按昵称
  if (u.uid > 0) return uid > 0 && uid === u.uid
  const name = (m.uname || '').trim()
  return !!name && u.name === name
}

/** 这条消息的发送者是否已被屏蔽 */
export function isUserBlocked(m: UserRef): boolean {
  return store.settings.danmaku.blockedUsers.some((u) => sameBlockedUser(m, u))
}

/** 把发送者加入屏蔽名单，并清掉聊天室里已经收到的消息 */
export function blockUser(m: UserRef): boolean {
  const name = (m.uname || '').trim()
  const uid = Number(m.uid) || 0
  if (!uid && !name) return false
  const user: BlockedUser = { uid, name }
  const ref: UserRef = { uid, uname: name }
  if (store.settings.danmaku.blockedUsers.some((u) => sameBlockedUser(ref, u))) return false
  store.settings.danmaku.blockedUsers.push(user)
  store.messages = store.messages.filter((x) => !sameBlockedUser(x, user))
  touchMessages()
  store.scFeed = store.scFeed.filter((x) => !sameBlockedUser(x, user))
  for (let i = feedBuffer.length - 1; i >= 0; i--) {
    if (sameBlockedUser(feedBuffer[i], user)) feedBuffer.splice(i, 1)
  }
  return true
}

/** 从屏蔽名单移除 */
export function unblockUser(uid: number, name: string) {
  const id = Number(uid) || 0
  store.settings.danmaku.blockedUsers = store.settings.danmaku.blockedUsers.filter(
    (u) => !((id > 0 && u.uid === id) || (id === 0 && u.name === name))
  )
}

function enrichMessage(m: ChatMsg, sourceId: string): ChatMsg {
  const meta = sourceMeta(sourceId)
  if (!meta) return m
  return {
    ...m,
    sourceId: meta.id,
    sourceName: meta.name,
    sourceColor: meta.color,
    badge: meta.badge,
    sourceRoomId: meta.roomId,
  }
}

/** 收到某个来源的一条消息 */
function ingest(sourceId: string, raw: ChatMsg) {
  const meta = sourceMeta(sourceId)
  if (!meta) return
  pushMessage(enrichMessage(raw, sourceId))
}

function pushMessage(m: ChatMsg) {
  // 礼物提示（「观众 送出 礼物 ×1」）不展示，直接丢弃
  if (m.kind === 'gift') return
  if (isUserBlocked(m)) return
  if (m.self && consumeSelfEcho(m.text, m.sourceRoomId)) return
  if (hasDmid(m.dmid)) return
  const msg: ChatMsg = { ...m, seq: m.seq || 900000000 + ++pushSeq }
  store.messages.push(msg)
  if (!msg.history) {
    feedBuffer.push(msg)
    if (feedBuffer.length > MAX_FEED_BUFFER) {
      feedBuffer.splice(0, feedBuffer.length - MAX_FEED_BUFFER)
    }
    if (msg.kind === 'sc') {
      store.scFeed.push(msg)
      if (store.scFeed.length > 40) store.scFeed.splice(0, store.scFeed.length - 40)
    }
  }
  trimMessages()
  touchMessages()
}

function insertHistory(sourceId: string, list: ChatMsg[]) {
  const meta = sourceMeta(sourceId)
  if (!meta) return
  const fresh = list
    .filter((m) => m.text && m.kind !== 'gift' && !isUserBlocked(m) && !hasDmid(m.dmid))
    .map((m, i) =>
      enrichMessage(
        {
          ...m,
          seq: -(++historySeq),
          history: true,
          dmid: m.dmid || `${sourceId}-history-${i}`,
        },
        sourceId
      )
    )
  if (!fresh.length) return
  store.messages.unshift(...fresh)
  trimMessages()
  touchMessages()
}

/* ---------- 视频 ---------- */

export function bindVideos(a: HTMLVideoElement, b: HTMLVideoElement) {
  videoAEl = a
  videoBEl = b
  applyVolumes()
  if (playerA) playerA.play()
  if (playerB) playerB.play()
}

function destroyPlayers() {
  peerStalled.a = false
  peerStalled.b = false
  playerA?.destroy()
  playerB?.destroy()
  playerA = null
  playerB = null
}

/** 一路卡缓冲时暂停另一路；两边都在卡就各等各的，避免互相拖死 */
function updatePeerHolds() {
  playerA?.holdByPeer(peerStalled.b && !peerStalled.a)
  playerB?.holdByPeer(peerStalled.a && !peerStalled.b)
}

function attachPlayer(slot: 'a' | 'b', streams: StreamSet) {
  const video = slot === 'a' ? videoAEl : videoBEl
  if (!video) return
  if (!streams.flv.length && !streams.hls.length) {
    setSlotError(slot, '没有可用的播放地址')
    return
  }
  const s = slot === 'a' ? store.a : store.b
  peerStalled[slot] = false
  const p = new StreamPlayer(
    video,
    streams,
    (state, msg) => {
      if (state === 'playing') s.blocked = false
      if (state === 'error') setSlotError(slot, msg || `${slot.toUpperCase()} 路播放失败，点右上角重连试试`)
    },
    () => {
      s.blocked = true
    },
    (stalled) => {
      peerStalled[slot] = stalled
      updatePeerHolds()
    }
  )
  p.setVolume(store.sessionVolumes[slot])
  p.setSyncOffset(offsetFor(slot))
  p.start()
  // 换清晰度 / 切画面时保持原来的暂停状态
  if (!store.playing) p.pause()
  if (slot === 'a') playerA = p
  else playerB = p
  updatePeerHolds()
}

function setSlotError(slot: 'a' | 'b', msg: string) {
  const s = slot === 'a' ? store.a : store.b
  s.error = msg
}

export function attachStreams() {
  attachPlayer('a', slotStreams.a)
  attachPlayer('b', slotStreams.b)
}

function applyVolumes() {
  if (playerA) playerA.setVolume(store.sessionVolumes.a)
  if (playerB) playerB.setVolume(store.sessionVolumes.b)
  if (videoAEl) videoAEl.volume = Math.min(store.sessionVolumes.a, 1)
  if (videoBEl) videoBEl.volume = Math.min(store.sessionVolumes.b, 1)
}

/** sessionOffset > 0：A 晚一点；< 0：改为让 B 晚一点，效果是 A 相对提前 */
function offsetFor(slot: 'a' | 'b'): number {
  return offsetsForSession(store.sessionOffset)[slot]
}

function applyOffset() {
  playerA?.setSyncOffset(offsetFor('a'))
  playerB?.setSyncOffset(offsetFor('b'))
  // 导入的解说声音跟着 A 一起晚 / 早，保证解说之间相对同步
  const extraOffset = offsetFor('a')
  for (const { player } of extraPlayers.values()) player.setSyncOffset(extraOffset)
}

/* ---------- 房间解析 ---------- */

function fillSlot(slot: SlotState, room: RoomInfo) {
  slot.roomId = room.roomId
  slot.shortId = room.shortId
  slot.uid = room.uid
  slot.title = room.title
  slot.uname = room.uname
  slot.face = room.face
  slot.cover = room.cover || room.keyframe
  slot.liveStatus = room.liveStatus
  slot.online = room.online
  slot.areaName = room.areaName
}

export async function resolveSlot(key: 'a' | 'b', input?: string): Promise<SlotState> {
  const slot = key === 'a' ? store.a : store.b
  const value = (input ?? slot.input).trim()
  if (!value) {
    slot.state = 'error'
    slot.error = '先填房间号'
    return slot
  }
  slot.input = value
  slot.state = 'loading'
  slot.error = ''
  store.startError = ''
  const token = ++resolveToken[key]
  try {
    const { roomId } = await initRoom(value)
    const [infoR, playR] = await Promise.allSettled([
      fetchRoomInfo(roomId),
      fetchPlay(roomId, store.settings.video.defaultQn),
    ])
    if (token !== resolveToken[key]) return slot
    if (infoR.status === 'rejected' && playR.status === 'rejected') {
      throw infoR.reason
    }
    if (infoR.status === 'fulfilled') fillSlot(slot, infoR.value)
    else slot.roomId = roomId

    if (playR.status === 'fulfilled') {
      const play = playR.value
      slot.qn = play.qn || store.settings.video.defaultQn
      slot.acceptQn = play.acceptQn.length
        ? play.acceptQn
        : [10000, 400, 250, 150, 80].map((qn) => ({ qn, desc: qnLabel(qn) }))
      slot.liveStatus = play.liveStatus || slot.liveStatus
      slotStreams[key] = { flv: [], hls: [] }
      for (const s of play.streams) {
        if (s.protocol === 'flv') slotStreams[key].flv.push(...s.urls)
        else slotStreams[key].hls.push(...s.urls)
      }
    } else {
      slotStreams[key] = { flv: [], hls: [] }
      slot.error = playR.reason instanceof Error ? playR.reason.message : '获取播放地址失败'
    }

    slot.state = 'ready'
    if (slot.liveStatus !== 1 && slot.liveStatus !== 2) {
      slot.error = slot.liveStatus === 2 ? '轮播中' : '未开播'
    } else if (!slotStreams[key].flv.length && !slotStreams[key].hls.length && !slot.error) {
      slot.error = '拿不到播放地址，可能需要登录或稍后重试'
    }
    persistSlots()
    return slot
  } catch (e) {
    if (token !== resolveToken[key]) return slot
    slot.state = 'error'
    slot.error = e instanceof Error ? e.message : String(e)
    return slot
  }
}

export function resolvePreview(key: 'a' | 'b') {
  void resolveSlot(key)
}

export function clearSlot(key: 'a' | 'b') {
  resolveToken[key]++
  const slot = key === 'a' ? store.a : store.b
  Object.assign(slot, emptySlot())
  slotStreams[key] = { flv: [], hls: [] }
  store.startError = ''
  persistSlots()
}

/* ---------- 会话 ---------- */

function playable(status: number): boolean {
  return status === 1 || status === 2
}

export async function startSession() {
  if (store.starting) return
  store.starting = true
  store.startError = ''
  try {
    await Promise.all([resolveSlot('a'), resolveSlot('b')])
    if (store.a.state !== 'ready' || store.b.state !== 'ready') {
      throw new Error(
        store.a.state === 'error'
          ? `A 路解析失败：${store.a.error}`
          : `B 路解析失败：${store.b.error}`
      )
    }
    if (!playable(store.a.liveStatus) || !playable(store.b.liveStatus)) {
      const offline = playable(store.a.liveStatus) ? store.b : store.a
      throw new Error(`「${offline.title || offline.input}」当前未开播，开播后再试`)
    }
    if (!hasStreams('a') || !hasStreams('b')) {
      throw new Error('有一路没有拿到播放地址，点重连再试一次')
    }
    beginSession()
  } catch (e) {
    store.startError = e instanceof Error ? e.message : String(e)
  } finally {
    store.starting = false
  }
}

function hasStreams(key: 'a' | 'b'): boolean {
  return slotStreams[key].flv.length > 0 || slotStreams[key].hls.length > 0
}

function profileKey(): string {
  return `${store.a.roomId}:${store.b.roomId}`
}

function applyRoomProfile() {
  const p = profiles[profileKey()]
  if (p) {
    store.sessionVolumes.a = Number(p.aVolume)
    store.sessionVolumes.b = Number(p.bVolume)
    store.sessionOffset = clampOffset(Number(p.offsetMs || 0))
  } else {
    store.sessionVolumes.a = store.settings.volume.a
    store.sessionVolumes.b = store.settings.volume.b
    store.sessionOffset = 0
  }
}

function saveRoomProfileSoon() {
  if (!store.a.roomId || !store.b.roomId) return
  if (saveProfileTimer) clearTimeout(saveProfileTimer)
  saveProfileTimer = window.setTimeout(() => {
    const p: RoomProfile = {
      aVolume: store.sessionVolumes.a,
      bVolume: store.sessionVolumes.b,
      offsetMs: store.sessionOffset,
      updatedAt: Date.now(),
    }
    profiles[profileKey()] = p
    writeJson('dc_profiles', profiles)
    // 下次遇到新组合时，起手就是最常用的音量配比
    store.settings.volume.a = p.aVolume
    store.settings.volume.b = p.bVolume
  }, 500)
}

function beginSession() {
  destroyPlayers()
  store.sessionActive = true
  store.view = 'live'
  store.playing = true
  store.a.blocked = false
  store.b.blocked = false
  store.messages = []
  touchMessages()
  store.scFeed = []
  feedBuffer.length = 0
  applyRoomProfile()
  store.commentaryOn = store.settings.commentaryWindow
  store.danmakuError = ''
  store.bDanmakuError = ''
  store.bDanmakuStatus = 'idle'
  store.viewer = store.a.online
  // 首次进会话时 LiveView 还没挂载，等 onMounted 绑定后自己 attach；
  // 从主页换一个组合再开时 LiveView 还在，这里要重新挂上新的播放地址
  attachStreams()
  connectAllDanmaku()
  ensureAllExtraPlayers()
  saveCombo()
  pushRecentFromSlot(store.a)
  pushRecentFromSlot(store.b)
  updateMediaSession()
  void requestWakeLock()
}

function connectDanmaku() {
  danmaku.stop()
  store.messages = []
  touchMessages()
  store.scFeed = []
  feedBuffer.length = 0
  store.danmakuStatus = 'connecting'
  store.danmakuError = ''
  const roomId = store.a.roomId
  const buvid = getCookieValue('buvid3')
  danmaku
    .connect(roomId, store.account.mid || 0, buvid, {
      onMsg: (m) => ingest('a', m),
      onStatus: (s, err) => {
        store.danmakuStatus = s
        store.danmakuError = err || ''
      },
      onViewer: (n) => {
        if (n > 0) store.viewer = n
      },
    })
    .catch(() => {})

  void fetchDanmakuHistory(roomId).then((list) => {
    if (roomId !== store.a.roomId) return
    insertHistory(
      'a',
      list.map((h, i) => ({
        seq: 0,
        kind: 'danmu' as const,
        text: h.text,
        uname: h.uname,
        uid: h.uid,
        dmid: h.dmid || `history-${i}`,
        color: h.color,
        medal: h.medal,
        history: true,
      }))
    )
  })
}

/**
 * 单独连接 B 路弹幕。只在「A+B 同屏」打开时建立，
 * 和 A 路共用聊天室 / 弹幕画布，用蓝色气泡 + B 主播首字区分。
 */
function connectBDanmaku() {
  danmakuB.stop()
  store.bDanmakuError = ''
  store.bDanmakuStatus = 'idle'
  if (!store.settings.danmaku.includeB || store.view !== 'live') return
  const roomId = store.b.roomId
  if (!roomId || roomId === store.a.roomId) return
  store.bDanmakuStatus = 'connecting'
  const buvid = getCookieValue('buvid3')
  danmakuB
    .connect(roomId, store.account.mid || 0, buvid, {
      onMsg: (m) => ingest('b', m),
      onStatus: (s, err) => {
        store.bDanmakuStatus = s
        store.bDanmakuError = err || ''
      },
      onViewer: () => {},
    })
    .catch(() => {})

  void fetchDanmakuHistory(roomId).then((list) => {
    if (roomId !== store.b.roomId || !store.settings.danmaku.includeB) return
    insertHistory(
      'b',
      list.map((h, i) => ({
        seq: 0,
        kind: 'danmu' as const,
        text: h.text,
        uname: h.uname,
        uid: h.uid,
        dmid: h.dmid || `b-history-${i}`,
        color: h.color,
        medal: h.medal,
        history: true,
      }))
    )
  })
}

/** 从聊天室、SC 队列和待绘制队列里清掉某个来源的消息 */
function purgeSourceMessages(sourceId: string) {
  store.messages = store.messages.filter((m) => m.sourceId !== sourceId)
  touchMessages()
  store.scFeed = store.scFeed.filter((m) => m.sourceId !== sourceId)
  for (let i = feedBuffer.length - 1; i >= 0; i--) {
    if (feedBuffer[i].sourceId === sourceId) feedBuffer.splice(i, 1)
  }
}

function disconnectBDanmaku() {
  danmakuB.stop()
  store.bDanmakuStatus = 'idle'
  store.bDanmakuError = ''
  purgeSourceMessages('b')
}

function connectAllDanmaku() {
  connectDanmaku()
  connectBDanmaku()
  for (const source of store.sources) connectSource(source)
}

/** 导入的房间和 A/B 路重复时跳过，避免同一条弹幕出现两次、同一路声音混两遍 */
function duplicateSource(source: ExtraRoom): boolean {
  return (
    source.roomId > 0 &&
    (source.roomId === store.a.roomId || source.roomId === store.b.roomId)
  )
}

function connectSource(source: ExtraRoom) {
  const old = extraClients.get(source.id)
  old?.stop()
  if (store.view !== 'live' || !source.roomId) {
    source.status = 'idle'
    return
  }
  if (duplicateSource(source)) {
    source.status = 'idle'
    source.error = '这个直播间已经在 A / B 路，已跳过'
    return
  }
  const client = new DanmakuClient()
  extraClients.set(source.id, client)
  source.status = 'connecting'
  source.error = ''
  const buvid = getCookieValue('buvid3')
  client
    .connect(source.roomId, store.account.mid || 0, buvid, {
      onMsg: (m) => ingest(source.id, m),
      onStatus: (s, err) => {
        source.status = s
        source.error = err || ''
      },
      onViewer: () => {},
    })
    .catch(() => {})

  void fetchDanmakuHistory(source.roomId).then((list) => {
    if (!store.sources.some((x) => x.id === source.id)) return
    insertHistory(
      source.id,
      list.map((h, i) => ({
        seq: 0,
        kind: 'danmu' as const,
        text: h.text,
        uname: h.uname,
        uid: h.uid,
        dmid: h.dmid || `${source.id}-history-${i}`,
        color: h.color,
        medal: h.medal,
        history: true,
      }))
    )
  })
}

function stopSourceConnection(sourceId: string) {
  const client = extraClients.get(sourceId)
  if (client) {
    client.stop()
    extraClients.delete(sourceId)
  }
  const s = store.sources.find((x) => x.id === sourceId)
  if (s) {
    s.status = 'idle'
    s.error = ''
  }
}

function destroyExtraPlayer(sourceId: string) {
  const entry = extraPlayers.get(sourceId)
  if (!entry) return
  entry.player.destroy()
  // 可见 video 由 Vue 管理，不能从 DOM 里 remove；隐藏 audio video 才需要清理
  if (entry.mode === 'hidden') entry.video.remove()
  extraPlayers.delete(sourceId)
  const s = store.sources.find((x) => x.id === sourceId)
  if (s) s.mediaState = 'idle'
}

function destroyAllExtraPlayers() {
  for (const id of [...extraPlayers.keys()]) destroyExtraPlayer(id)
}

function streamsFromPlay(play: RoomPlay): StreamSet {
  const set: StreamSet = { flv: [], hls: [] }
  for (const s of play.streams) {
    if (s.protocol === 'flv') set.flv.push(...s.urls)
    else set.hls.push(...s.urls)
  }
  return set
}

function sourceVolume(source: ExtraRoom): number {
  return source.commentary ? source.volume : 0
}

/**
 * 让某个导入房间的播放器与当前设置保持一致：
 * - 声音 + 画面：复用多路墙里的可见 video；
 * - 只要声音：创建屏幕外的隐藏 video；
 * - 都不要：销毁播放器。
 * 画面开关先于 video 挂载生效时，等 MultiWall 调用 attachSourceVideo。
 */
function syncSourcePlayer(source: ExtraRoom) {
  if (store.view !== 'live') {
    destroyExtraPlayer(source.id)
    return
  }
  if (!source.commentary && !source.video) {
    destroyExtraPlayer(source.id)
    source.blocked = false
    source.audioError = ''
    source.mediaState = 'idle'
    return
  }
  if (duplicateSource(source)) {
    destroyExtraPlayer(source.id)
    source.audioError = '这个直播间已经在 A / B 路，不能重复播放'
    source.mediaState = 'idle'
    return
  }

  const visible = source.video ? sourceVideoEls.get(source.id) || null : null
  if (source.video && !visible) {
    // 等多路墙把这个房间的 video 挂上来；挂上后会再调用一次
    destroyExtraPlayer(source.id)
    source.audioError = ''
    return
  }

  const existing = extraPlayers.get(source.id)
  const mode = visible ? 'visible' : 'hidden'
  if (existing) {
    if (existing.mode === mode && existing.video === (visible || existing.video)) {
      existing.player.setVolume(sourceVolume(source))
      existing.player.setSyncOffset(offsetFor('a'))
      return
    }
    destroyExtraPlayer(source.id)
  }
  void ensureExtraPlayer(source)
}

async function ensureExtraPlayer(source: ExtraRoom) {
  if (store.view !== 'live') return
  if (!source.commentary && !source.video) return
  if (duplicateSource(source)) {
    source.audioError = '这个直播间已经在 A / B 路，不能重复播放'
    source.mediaState = 'idle'
    return
  }
  if (extraPlayerPending.has(source.id) || extraPlayers.has(source.id)) return
  extraPlayerPending.add(source.id)
  source.audioError = ''
  source.mediaState = 'loading'
  try {
    const play = await fetchPlay(source.roomId, store.settings.video.defaultQn)
    // 拉地址期间用户可能已经切换开关或删除了房间
    if (!store.sources.some((x) => x.id === source.id)) return
    if (!source.commentary && !source.video) {
      source.mediaState = 'idle'
      source.audioError = ''
      return
    }
    if (store.view !== 'live' || extraPlayers.has(source.id)) return
    const visible = source.video ? sourceVideoEls.get(source.id) || null : null
    if (source.video && !visible) {
      // 等可见 video 挂载后再建播放器，避免先建隐藏播放器又重建
      source.mediaState = 'idle'
      return
    }
    const streams = streamsFromPlay(play)
    source.liveStatus = play.liveStatus || source.liveStatus
    if (!streams.flv.length && !streams.hls.length) {
      source.mediaState = 'error'
      source.audioError =
        source.liveStatus === 1 || source.liveStatus === 2
          ? '拿不到播放地址，无法播放这一路'
          : '当前未开播，无法播放这一路'
      return
    }

    let video = visible
    let mode: 'hidden' | 'visible' = 'visible'
    if (!video) {
      video = document.createElement('video')
      video.playsInline = true
      video.setAttribute('aria-hidden', 'true')
      video.style.cssText =
        'position:fixed;left:-4px;bottom:-4px;width:2px;height:2px;opacity:0.01;pointer-events:none;z-index:-1'
      document.body.appendChild(video)
      mode = 'hidden'
    }
    const player = new StreamPlayer(
      video,
      streams,
      (state, msg) => {
        if (state === 'playing') {
          source.blocked = false
          source.audioError = ''
          source.mediaState = 'playing'
        } else if (state === 'loading') {
          source.mediaState = 'loading'
        } else if (state === 'error') {
          source.mediaState = 'error'
          source.audioError = msg || '多路播放失败'
        } else {
          source.mediaState = 'idle'
        }
      },
      () => {
        source.blocked = true
      }
    )
    player.setVolume(sourceVolume(source))
    player.setSyncOffset(offsetFor('a'))
    player.start()
    if (!store.playing) player.pause()
    extraPlayers.set(source.id, { video, player, mode })
  } catch (e) {
    source.mediaState = 'error'
    source.audioError = e instanceof Error ? e.message : '多路播放失败'
  } finally {
    extraPlayerPending.delete(source.id)
  }
}

/** 多路墙把一个房间的可见 video 挂载 / 卸载时调用 */
export function attachSourceVideo(sourceId: string, el: HTMLVideoElement | null) {
  const old = sourceVideoEls.get(sourceId)
  if (el) {
    if (old === el) {
      const existing = store.sources.find((s) => s.id === sourceId)
      if (existing) syncSourcePlayer(existing)
      return
    }
    sourceVideoEls.set(sourceId, el)
  } else {
    if (!old) return
    sourceVideoEls.delete(sourceId)
  }
  const source = store.sources.find((s) => s.id === sourceId)
  if (source) syncSourcePlayer(source)
}

function ensureAllExtraPlayers() {
  for (const source of store.sources) {
    if (source.commentary || source.video) syncSourcePlayer(source)
  }
}

export function retryDanmaku() {
  const uid = store.account.mid || 0
  const buvid = getCookieValue('buvid3')
  danmaku.retry(uid, buvid)
  if (store.settings.danmaku.includeB) danmakuB.retry(uid, buvid)
  for (const source of store.sources) {
    extraClients.get(source.id)?.retry(uid, buvid)
  }
}

export async function reconnect() {
  if (store.reconnecting) return
  store.reconnecting = true
  try {
    const a = await resolveSlot('a', store.a.input)
    const b = await resolveSlot('b', store.b.input)
    if (a.state !== 'ready' || b.state !== 'ready') throw new Error('房间解析失败')
    destroyPlayers()
    attachStreams()
    store.playing = true
    connectAllDanmaku()
    ensureAllExtraPlayers()
    for (const { player } of extraPlayers.values()) player.play()
    applyOffset()
  } catch (e) {
    store.startError = e instanceof Error ? e.message : String(e)
  } finally {
    store.reconnecting = false
  }
}

export function swap() {
  if (store.swapping || store.view !== 'live') {
    if (store.view !== 'live') {
      const a = { ...store.a }
      store.a = { ...store.b }
      store.b = a
      persistSlots()
    }
    return
  }
  store.swapping = true
  try {
    const a = { ...store.a }
    store.a = { ...store.b }
    store.b = a
    const s = slotStreams.a
    slotStreams.a = slotStreams.b
    slotStreams.b = s
    const va = store.sessionVolumes.a
    store.sessionVolumes.a = store.sessionVolumes.b
    store.sessionVolumes.b = va
    // 偏移是「A 相对 B」的量，互换后方向要反过来，否则听感会对不上
    store.sessionOffset = -store.sessionOffset
    destroyPlayers()
    attachStreams()
    applyVolumes()
    applyOffset()
    connectAllDanmaku()
    ensureAllExtraPlayers()
    updateMediaSession()
    persistSlots()
    saveRoomProfileSoon()
  } finally {
    store.swapping = false
  }
}

export function setQuality(key: 'a' | 'b', qn: number) {
  const slot = key === 'a' ? store.a : store.b
  if (!slot.roomId || store.qualitySwitching) return
  store.qualitySwitching = true
  void (async () => {
    try {
      const play = await fetchPlay(slot.roomId, qn)
      slot.qn = play.qn || qn
      slot.error = ''
      slot.acceptQn = play.acceptQn.length ? play.acceptQn : slot.acceptQn
      slotStreams[key] = { flv: [], hls: [] }
      for (const s of play.streams) {
        if (s.protocol === 'flv') slotStreams[key].flv.push(...s.urls)
        else slotStreams[key].hls.push(...s.urls)
      }
      if (key === 'b') store.settings.video.defaultQn = qn
      if (store.view === 'live') {
        if (key === 'a') {
          playerA?.destroy()
          playerA = null
        } else {
          playerB?.destroy()
          playerB = null
        }
        attachPlayer(key, slotStreams[key])
        applyVolumes()
        applyOffset()
      }
    } catch (e) {
      slot.error = e instanceof Error ? e.message : '切换清晰度失败'
    } finally {
      store.qualitySwitching = false
    }
  })()
}

export function exitSession() {
  danmaku.stop()
  danmakuB.stop()
  destroyPlayers()
  for (const id of [...extraClients.keys()]) {
    extraClients.get(id)?.stop()
    extraClients.delete(id)
  }
  destroyAllExtraPlayers()
  sourceVideoEls.clear()
  for (const source of store.sources) {
    source.status = 'idle'
    source.error = ''
    source.audioError = ''
    source.mediaState = 'idle'
    source.blocked = false
  }
  slotStreams.a = { flv: [], hls: [] }
  slotStreams.b = { flv: [], hls: [] }
  store.sessionActive = false
  store.view = 'home'
  store.messages = []
  store.scFeed = []
  store.playing = true
  store.a.blocked = false
  store.b.blocked = false
  store.danmakuStatus = 'idle'
  store.danmakuError = ''
  store.bDanmakuStatus = 'idle'
  store.bDanmakuError = ''
  feedBuffer.length = 0
  touchMessages()
  releaseWakeLock()
  try {
    if ('mediaSession' in navigator) {
      navigator.mediaSession.metadata = null
      navigator.mediaSession.playbackState = 'none'
    }
  } catch {
    /* ignore */
  }
}

/**
 * 回主页但保持播放：播放器和弹幕连接都不动，只把界面切回首页。
 * 回到首页后释放屏幕常亮，手机锁屏 / 切后台时声音继续走（有系统媒体控制）。
 */
export function leavePlayer() {
  if (!store.sessionActive) return
  store.view = 'home'
  releaseWakeLock()
}

/** 从首页的「正在播放」条回到播放器 */
export function backToPlayer() {
  if (!store.sessionActive) return
  store.view = 'live'
  if (store.playing) void requestWakeLock()
  updateMediaSession()
}

/** 用户点击后恢复播放（解除浏览器自动播放限制） */
export function resumePlayback() {
  store.playing = true
  store.a.blocked = false
  store.b.blocked = false
  playerA?.play()
  playerB?.play()
  for (const source of store.sources) source.blocked = false
  for (const { player } of extraPlayers.values()) player.play()
  void requestWakeLock()
  syncMediaSessionState()
}

export function togglePlay() {
  if (store.a.blocked || store.b.blocked || store.sources.some((s) => s.blocked)) {
    resumePlayback()
    return
  }
  store.playing = !store.playing
  if (store.playing) {
    playerA?.play()
    playerB?.play()
    for (const { player } of extraPlayers.values()) player.play()
    void requestWakeLock()
  } else {
    playerA?.pause()
    playerB?.pause()
    for (const { player } of extraPlayers.values()) player.pause()
    releaseWakeLock()
  }
  syncMediaSessionState()
}

export function toggleDanmaku() {
  store.settings.danmaku.enabled = !store.settings.danmaku.enabled
}

/** A+B 两路弹幕同屏 */
export function toggleIncludeBDanmaku() {
  store.settings.danmaku.includeB = !store.settings.danmaku.includeB
}

export function setIncludeBDanmaku(on: boolean) {
  store.settings.danmaku.includeB = on
}

/* ---------- 混音 ---------- */

export function setVolume(which: 'a' | 'b', v: number) {
  const vol = Math.max(0, Math.min(1, v))
  store.sessionVolumes[which] = vol
  store.settings.volume[which] = vol
  applyVolumes()
  saveRoomProfileSoon()
}

export function setOffset(ms: number) {
  store.sessionOffset = clampOffset(ms)
  applyOffset()
  saveRoomProfileSoon()
}

export function nudgeOffset(deltaMs: number) {
  setOffset(store.sessionOffset + deltaMs)
}

export function resetOffset() {
  setOffset(0)
}

export function toggleSc() {
  store.settings.sc.enabled = !store.settings.sc.enabled
}

export function setCommentary(on: boolean) {
  store.commentaryOn = on
  store.settings.commentaryWindow = on
}

/* ---------- 多路弹幕 / 解说 ---------- */

export interface AddSourceOptions {
  commentary?: boolean
  video?: boolean
}

export async function addSource(
  input: string,
  initial: AddSourceOptions = {}
): Promise<ExtraRoom | null> {
  if (store.sourceBusy) return null
  const value = input.trim()
  if (!value) {
    store.sourceError = '先填房间号或直播间链接'
    return null
  }
  store.sourceBusy = true
  store.sourceError = ''
  try {
    const init = await initRoom(value)
    if (!init.roomId) throw new Error('房间不存在')
    if (init.roomId === store.a.roomId || init.roomId === store.b.roomId) {
      throw new Error('这个直播间已经在 A / B 路里了')
    }
    if (store.sources.some((s) => s.roomId === init.roomId)) {
      throw new Error('这个直播间已经导入过了')
    }

    const [infoR, playR] = await Promise.allSettled([
      fetchRoomInfo(init.roomId),
      fetchPlay(init.roomId, store.settings.video.defaultQn),
    ])
    const info = infoR.status === 'fulfilled' ? infoR.value : null
    const play = playR.status === 'fulfilled' ? playR.value : null
    const uname = info?.uname || ''
    const source: ExtraRoom = {
      id: `r${init.roomId}`,
      input: value,
      roomId: init.roomId,
      shortId: init.shortId || info?.shortId || 0,
      uid: init.uid || info?.uid || 0,
      title: info?.title || `房间 ${init.roomId}`,
      uname,
      face: info?.face || '',
      liveStatus: play?.liveStatus ?? info?.liveStatus ?? init.liveStatus,
      online: info?.online || 0,
      areaName: info?.areaName || '',
      badgeMode: uname ? 'initial' : 'color',
      badgeText: '',
      color: roomColor(init.roomId),
      commentary: !!initial.commentary,
      video: !!initial.video,
      volume: 0.6,
      status: 'idle',
      error: '',
      audioError: '',
      mediaState: 'idle',
      blocked: false,
    }
    store.sources.push(source)
    persistSources()
    if (store.view === 'live') {
      connectSource(source)
      // 从关注列表直接加入多路时，弹幕和画面一起就绪
      if (source.commentary || source.video) syncSourcePlayer(source)
    }
    return source
  } catch (e) {
    store.sourceError = e instanceof Error ? e.message : String(e)
    return null
  } finally {
    store.sourceBusy = false
  }
}

export type SourcePatch = Partial<
  Pick<
    ExtraRoom,
    'badgeMode' | 'badgeText' | 'color' | 'commentary' | 'video' | 'volume'
  >
>

export function updateSource(id: string, patch: SourcePatch) {
  const source = store.sources.find((s) => s.id === id)
  if (!source) return
  if (patch.badgeMode) source.badgeMode = patch.badgeMode
  if (patch.badgeText !== undefined) source.badgeText = normalizeBadgeText(patch.badgeText)
  if (patch.color) source.color = patch.color
  if (typeof patch.commentary === 'boolean') source.commentary = patch.commentary
  if (typeof patch.video === 'boolean') source.video = patch.video
  if (patch.volume !== undefined) source.volume = Math.max(0, Math.min(1, patch.volume))
  persistSources()

  if (typeof patch.volume === 'number') {
    extraPlayers.get(id)?.player.setVolume(sourceVolume(source))
  }
  if (typeof patch.commentary === 'boolean' || typeof patch.video === 'boolean') {
    syncSourcePlayer(source)
  }
}

export function removeSource(id: string) {
  stopSourceConnection(id)
  destroyExtraPlayer(id)
  const i = store.sources.findIndex((s) => s.id === id)
  if (i >= 0) store.sources.splice(i, 1)
  persistSources()
}

export function retrySource(id: string) {
  const source = store.sources.find((s) => s.id === id)
  if (!source) return
  source.error = ''
  const buvid = getCookieValue('buvid3')
  const client = extraClients.get(id)
  if (client) client.retry(store.account.mid || 0, buvid)
  else if (store.view === 'live') connectSource(source)
  if (source.commentary || source.video) {
    // 多路播放器出错 / 被浏览器拦下时，手动重试要重建一次；
    // 正常播放中的连接不打断。
    if (extraPlayers.has(id) && (source.audioError || source.blocked)) {
      destroyExtraPlayer(id)
    }
    if (!extraPlayers.has(id) && !extraPlayerPending.has(id)) {
      source.audioError = ''
      source.blocked = false
      source.mediaState = 'idle'
      syncSourcePlayer(source)
    }
  }
}

export function roomIdForSource(sourceId?: string): number {
  if (!sourceId || sourceId === 'a') return store.a.roomId
  if (sourceId === 'b') return store.b.roomId
  // 来源被移除后返回 0，调用方应提示用户，而不是误发到主房间
  return store.sources.find((s) => s.id === sourceId)?.roomId || 0
}

/* ---------- 发弹幕 ---------- */

export function replyName(target: ChatMsg): string {
  return (target.uname || 'TA').replace(/\s+/g, ' ').trim().slice(0, NAME_LIMIT) || 'TA'
}

export function replyInputLimit(target: ChatMsg): number {
  const budget = LIMIT - replyName(target).length - 2
  return Math.max(1, budget - QUOTE_RESERVE)
}

export function buildReply(reply: string, target: ChatMsg): string {
  const name = replyName(target)
  const budget = Math.max(1, LIMIT - name.length - 2)
  const replyCap = Math.max(1, budget - QUOTE_RESERVE)
  const body = reply.trim().replace(/[\r\n]+/g, ' ').slice(0, replyCap)
  const quoteRoom = budget - body.length
  const quote = target.text.trim().replace(/[\r\n]+/g, ' ')
  const shortened =
    quoteRoom <= 0
      ? ''
      : quote.length <= quoteRoom
        ? quote
        : quoteRoom === 1
          ? '…'
          : `${quote.slice(0, quoteRoom - 1)}…`
  return `${body}@${name}:${shortened}`.slice(0, LIMIT)
}

export function canInteract(target: ChatMsg | null): boolean {
  return !!target && target.kind === 'danmu' && !target.self
}

export async function send(
  text: string,
  replyTarget?: ChatMsg | null,
  targetSourceId?: string
): Promise<boolean> {
  if (store.sendState.sending) return false
  const raw = text.trim().replace(/[\r\n]+/g, ' ')
  if (!raw) return false
  if (!store.loggedIn) {
    store.sendState.error = '发弹幕需要先登录 B 站账号'
    return false
  }
  const now = Date.now()
  if (now < store.sendState.cooldownUntil) {
    store.sendState.error = `刚发过，等 ${Math.ceil((store.sendState.cooldownUntil - now) / 1000)} 秒再发`
    return false
  }
  const sourceId = targetSourceId || replyTarget?.sourceId || 'a'
  const roomId = roomIdForSource(sourceId)
  if (!roomId) {
    store.sendState.error = '这条弹幕的来源直播间已被移除'
    return false
  }
  const final = replyTarget ? buildReply(raw, replyTarget) : raw.slice(0, LIMIT)
  if (!final) return false
  store.sendState.sending = true
  store.sendState.error = ''
  try {
    const r = await sendDanmaku(roomId, final)
    if (!r.ok) {
      store.sendState.error = r.message
      return false
    }
    const mine = enrichMessage(
      {
        seq: 900000000 + ++pushSeq,
        kind: 'danmu',
        text: final,
        uname: store.account.name || '我',
        uid: store.account.mid || undefined,
        color: 0xffffff,
        self: true,
      },
      sourceId
    )
    // 先插本地，再登记回显窗口；pushMessage 对 self 消息会查这个窗口
    pushMessage(mine)
    pendingSelfEcho.push({ text: final, roomId, until: Date.now() + SELF_ECHO_WINDOW })
    store.sendState.sentSeq++
    store.sendState.lastOkAt = Date.now()
    store.sendState.cooldownUntil = Date.now() + SEND_COOLDOWN
    return true
  } catch (e) {
    store.sendState.error = e instanceof Error ? e.message : '发送失败'
    return false
  } finally {
    store.sendState.sending = false
  }
}

export async function plusOne(target: ChatMsg): Promise<boolean> {
  if (!canInteract(target)) return false
  const text = target.text.trim()
  return send(text.length <= LIMIT ? text : '+1', null, target.sourceId)
}

export function clearSendError() {
  store.sendState.error = ''
}

/* ---------- 登录 ---------- */

export async function finishLogin(cookieStr: string) {
  saveCookieString(cookieStr)
  clearFollowCache()
  const [account] = await Promise.all([fetchAccount(), fetchBuvid()])
  store.account = account || {
    mid: Number(getCookieValue('DedeUserID') || 0),
    name: '已登录',
    avatar: '',
    level: 0,
  }
  store.loggedIn = true
  writeJson('dc_account', store.account)
  if (store.view === 'live') retryDanmaku()
}

export async function logout() {
  await biliLogout()
  localStorage.removeItem('dc_cookies')
  clearFollowCache()
  store.loggedIn = false
  store.account = { mid: 0, name: '', avatar: '', level: 0 }
  writeJson('dc_account', store.account)
  if (store.view === 'live') retryDanmaku()
}

export async function refreshProfile() {
  if (!store.loggedIn) return
  const account = await fetchAccount()
  if (account) {
    store.account = account
    writeJson('dc_account', store.account)
  }
}

/* ---------- 组合与最近 ---------- */

function saveCombo() {
  const combo: Combo = {
    aInput: store.a.input,
    aRoomId: store.a.roomId,
    aTitle: store.a.title || store.a.uname,
    aAnchor: store.a.uname,
    bInput: store.b.input,
    bRoomId: store.b.roomId,
    bTitle: store.b.title || store.b.uname,
    bAnchor: store.b.uname,
    updatedAt: Date.now(),
  }
  const list = store.combos.filter(
    (c) => !(c.aRoomId === combo.aRoomId && c.bRoomId === combo.bRoomId)
  )
  list.unshift(combo)
  store.combos = list.slice(0, 12)
  writeJson('dc_combos', { list: store.combos })
}

export function deleteCombo(index: number) {
  store.combos.splice(index, 1)
  writeJson('dc_combos', { list: store.combos })
}

export async function startCombo(combo: Combo) {
  store.a.input = combo.aInput
  store.b.input = combo.bInput
  await startSession()
}

function pushRecentFromSlot(slot: SlotState) {
  if (!slot.roomId) return
  const list = store.recent.filter((r) => r.roomId !== slot.roomId)
  list.unshift({
    input: slot.input || String(slot.roomId),
    roomId: slot.roomId,
    title: slot.title,
    uname: slot.uname,
    usedAt: Date.now(),
  })
  store.recent = list.slice(0, 12)
  writeJson('dc_recent', { list: store.recent })
}

export async function useRecent(room: RecentRoom) {
  const key: 'a' | 'b' = !store.a.input ? 'a' : !store.b.input ? 'b' : 'b'
  const slot = key === 'a' ? store.a : store.b
  slot.input = room.input
  await resolveSlot(key, room.input)
}

/* ---------- 持久化输入 ---------- */

function persistSlots() {
  if (saveSlotsTimer) clearTimeout(saveSlotsTimer)
  saveSlotsTimer = window.setTimeout(() => {
    writeJson('dc_slots', {
      a: { input: store.a.input },
      b: { input: store.b.input },
    })
  }, 300)
}

function restoreSlots() {
  const raw: any = readJson('dc_slots', null)
  if (raw?.a?.input) store.a.input = String(raw.a.input)
  if (raw?.b?.input) store.b.input = String(raw.b.input)
  if (store.a.input) void resolveSlot('a')
  if (store.b.input) void resolveSlot('b')
}

/* ---------- 系统集成 ---------- */

function updateMediaSession() {
  if (!('mediaSession' in navigator)) return
  try {
    if (typeof MediaMetadata !== 'undefined') {
      navigator.mediaSession.metadata = new MediaMetadata({
        title: store.b.title || '叠播',
        artist: store.a.uname ? `${store.a.uname} · 弹幕` : store.a.title || '叠播',
        artwork: store.a.face ? [{ src: store.a.face, sizes: '512x512' }] : [],
      })
    }
    navigator.mediaSession.setActionHandler('play', () => {
      if (!store.playing) togglePlay()
    })
    navigator.mediaSession.setActionHandler('pause', () => {
      if (store.playing) togglePlay()
    })
    navigator.mediaSession.setActionHandler('stop', () => exitSession())
    navigator.mediaSession.playbackState = store.playing ? 'playing' : 'paused'
  } catch {
    /* 部分浏览器不支持某些 action */
  }
}

/** 同步锁屏 / 系统媒体面板上的播放状态 */
function syncMediaSessionState() {
  try {
    if ('mediaSession' in navigator) {
      navigator.mediaSession.playbackState = store.playing ? 'playing' : 'paused'
    }
  } catch {
    /* ignore */
  }
}

async function requestWakeLock() {
  if (!('wakeLock' in navigator) || wakeLock) return
  try {
    wakeLock = await (navigator as any).wakeLock.request('screen')
    wakeLock?.addEventListener?.('release', () => {
      wakeLock = null
    })
  } catch {
    wakeLock = null
  }
}

function releaseWakeLock() {
  try {
    wakeLock?.release()
  } catch {
    /* ignore */
  }
  wakeLock = null
}

document.addEventListener('visibilitychange', () => {
  if (document.visibilityState === 'visible' && store.view === 'live' && store.playing) {
    void requestWakeLock()
  }
})

/* ---------- 对外只读辅助 ---------- */

export function viewerLabel(): string {
  return store.viewer > 0 ? `${formatCount(store.viewer)}人气` : ''
}

export function slotOf(key: 'a' | 'b'): SlotState {
  return key === 'a' ? store.a : store.b
}

export function isPlayable(status: number): boolean {
  return playable(status)
}

/** 调试用：URL 带 ?debug=1 时挂到 window，方便排查播放/弹幕问题 */
export function debugSnapshot() {
  return {
    view: store.view,
    slots: {
      a: { roomId: store.a.roomId, state: store.a.state, error: store.a.error, liveStatus: store.a.liveStatus, qn: store.a.qn },
      b: { roomId: store.b.roomId, state: store.b.state, error: store.b.error, liveStatus: store.b.liveStatus, qn: store.b.qn },
    },
    streams: {
      a: { flv: slotStreams.a.flv.length, hls: slotStreams.a.hls.length },
      b: { flv: slotStreams.b.flv.length, hls: slotStreams.b.hls.length },
    },
    players: { a: !!playerA, b: !!playerB },
    messages: store.messages.length,
    danmaku: store.danmakuStatus,
    bDanmaku: store.bDanmakuStatus,
    includeB: store.settings.danmaku.includeB,
    sources: store.sources.map((s) => ({
      roomId: s.roomId,
      status: s.status,
      commentary: s.commentary,
      video: s.video,
      media: sourceVideoEls.has(s.id) ? 'visible' : extraPlayers.get(s.id)?.mode || 'none',
      mediaState: s.mediaState,
      audioError: s.audioError,
    })),
    playing: store.playing,
  }
}

restoreSlots()