/**
 * 全局状态与会话编排：解析房间 → 双路播放 → A 房弹幕 → 聊天室 / 发弹幕。
 *
 * 设计对齐 Android 版：
 * - A 路负责弹幕和声音，B 路负责画面（也可以出声音）；
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
} from './bili'
import { getCookieValue, hasCookie, saveCookieString } from './net'

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

export interface Settings {
  danmaku: {
    enabled: boolean
    fontScale: number
    alpha: number
    speed: number
    area: number
    showName: boolean
    bold: boolean
    style: DanmakuStyle
    blocked: string[]
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
const SEND_COOLDOWN = 1500
const SELF_ECHO_WINDOW = 8000
const NAME_LIMIT = 12
const QUOTE_RESERVE = 3

const DEFAULT_SETTINGS: Settings = {
  danmaku: {
    enabled: true,
    fontScale: 1,
    alpha: 0.94,
    speed: 1,
    area: 0.62,
    showName: false,
    bold: true,
    style: 'outline',
    blocked: [],
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
  if (raw.danmaku && typeof raw.danmaku === 'object') {
    Object.assign(s.danmaku, raw.danmaku)
    // 旧版本用 outline 布尔值控制描边
    if (typeof raw.danmaku.outline === 'boolean' && !raw.danmaku.style) {
      s.danmaku.style = raw.danmaku.outline ? 'outline' : 'shadow'
    }
    if (typeof raw.danmaku.bold !== 'boolean') s.danmaku.bold = true
    if (!Array.isArray(s.danmaku.blocked)) s.danmaku.blocked = []
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

export const store = reactive({
  view: 'home' as 'home' | 'live',
  a: emptySlot(),
  b: emptySlot(),
  messages: [] as ChatMsg[],
  /** 新 SC 的独立队列，画面上的 SC 悬浮窗消费它，不依赖聊天室滚动位置 */
  scFeed: [] as ChatMsg[],
  viewer: 0,
  danmakuStatus: 'idle' as DanmakuStatus,
  danmakuError: '',
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
  playing: true,
  sessionVolumes: { a: 0.85, b: 1 },
  sessionOffset: 0,
  commentaryOn: true,
})

/* ---------- 非响应式实例 ---------- */

const danmaku = new DanmakuClient()
let playerA: StreamPlayer | null = null
let playerB: StreamPlayer | null = null
let videoAEl: HTMLVideoElement | null = null
let videoBEl: HTMLVideoElement | null = null

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
const pendingSelfEcho: { text: string; until: number }[] = []

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

function consumeSelfEcho(text: string): boolean {
  const now = Date.now()
  while (pendingSelfEcho.length && pendingSelfEcho[0].until < now) pendingSelfEcho.shift()
  const i = pendingSelfEcho.findIndex((e) => e.text === text)
  if (i < 0) return false
  pendingSelfEcho.splice(i, 1)
  return true
}

function pushMessage(m: ChatMsg) {
  if (m.self && consumeSelfEcho(m.text)) return
  if (hasDmid(m.dmid)) return
  const msg: ChatMsg = { ...m, seq: m.seq || 900000000 + ++pushSeq }
  store.messages.push(msg)
  if (!msg.history) {
    feedBuffer.push(msg)
    if (msg.kind === 'sc') {
      store.scFeed.push(msg)
      if (store.scFeed.length > 40) store.scFeed.splice(0, store.scFeed.length - 40)
    }
  }
  trimMessages()
}

function insertHistory(list: ChatMsg[]) {
  const fresh = list
    .filter((m) => m.text && !hasDmid(m.dmid))
    .map((m) => ({ ...m, seq: -(++historySeq), history: true }))
  if (!fresh.length) return
  store.messages.unshift(...fresh)
  trimMessages()
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
  playerA?.destroy()
  playerB?.destroy()
  playerA = null
  playerB = null
}

function attachPlayer(slot: 'a' | 'b', streams: StreamSet) {
  const video = slot === 'a' ? videoAEl : videoBEl
  if (!video) return
  if (!streams.flv.length && !streams.hls.length) {
    setSlotError(slot, '没有可用的播放地址')
    return
  }
  const s = slot === 'a' ? store.a : store.b
  const p = new StreamPlayer(
    video,
    streams,
    (state, msg) => {
      if (state === 'playing') s.blocked = false
      if (state === 'error') setSlotError(slot, msg || `${slot.toUpperCase()} 路播放失败，点右上角重连试试`)
    },
    () => {
      s.blocked = true
    }
  )
  p.setVolume(store.sessionVolumes[slot])
  p.setSyncOffset(offsetFor(slot))
  p.start()
  // 换清晰度 / 切画面时保持原来的暂停状态
  if (!store.playing) p.pause()
  if (slot === 'a') playerA = p
  else playerB = p
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
  const off = store.sessionOffset
  if (slot === 'a') return Math.max(0, off)
  return Math.max(0, -off)
}

function applyOffset() {
  playerA?.setSyncOffset(offsetFor('a'))
  playerB?.setSyncOffset(offsetFor('b'))
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
    store.sessionOffset = Number(p.offsetMs || 0)
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
  store.view = 'live'
  store.playing = true
  store.a.blocked = false
  store.b.blocked = false
  store.messages = []
  store.scFeed = []
  feedBuffer.length = 0
  applyRoomProfile()
  store.commentaryOn = store.settings.commentaryWindow
  store.danmakuError = ''
  store.viewer = store.a.online
  // 播放器等 LiveView 挂载、拿到 video 元素后再绑定（见 LiveView onMounted）
  connectDanmaku()
  saveCombo()
  pushRecentFromSlot(store.a)
  pushRecentFromSlot(store.b)
  updateMediaSession()
  void requestWakeLock()
}

function connectDanmaku() {
  danmaku.stop()
  store.messages = []
  store.scFeed = []
  feedBuffer.length = 0
  store.danmakuStatus = 'connecting'
  store.danmakuError = ''
  const roomId = store.a.roomId
  const buvid = getCookieValue('buvid3')
  danmaku
    .connect(roomId, store.account.mid || 0, buvid, {
      onMsg: pushMessage,
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

export function retryDanmaku() {
  danmaku.retry(store.account.mid || 0, getCookieValue('buvid3'))
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
    connectDanmaku()
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
    connectDanmaku()
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
  destroyPlayers()
  slotStreams.a = { flv: [], hls: [] }
  slotStreams.b = { flv: [], hls: [] }
  store.view = 'home'
  store.messages = []
  store.scFeed = []
  store.playing = true
  store.a.blocked = false
  store.b.blocked = false
  store.danmakuStatus = 'idle'
  store.danmakuError = ''
  feedBuffer.length = 0
  releaseWakeLock()
  if ('mediaSession' in navigator) navigator.mediaSession.metadata = null
}

/** 用户点击后恢复播放（解除浏览器自动播放限制） */
export function resumePlayback() {
  store.playing = true
  store.a.blocked = false
  store.b.blocked = false
  playerA?.play()
  playerB?.play()
  void requestWakeLock()
}

export function togglePlay() {
  if (store.a.blocked || store.b.blocked) {
    resumePlayback()
    return
  }
  store.playing = !store.playing
  if (store.playing) {
    playerA?.play()
    playerB?.play()
    void requestWakeLock()
  } else {
    playerA?.pause()
    playerB?.pause()
    releaseWakeLock()
  }
}

export function toggleDanmaku() {
  store.settings.danmaku.enabled = !store.settings.danmaku.enabled
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
  store.sessionOffset = Math.max(-5000, Math.min(5000, Math.round(ms)))
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

export async function send(text: string, replyTarget?: ChatMsg | null): Promise<boolean> {
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
  const final = replyTarget ? buildReply(raw, replyTarget) : raw.slice(0, LIMIT)
  if (!final) return false
  store.sendState.sending = true
  store.sendState.error = ''
  try {
    const r = await sendDanmaku(store.a.roomId, final)
    if (!r.ok) {
      store.sendState.error = r.message
      return false
    }
    const mine: ChatMsg = {
      seq: 900000000 + ++pushSeq,
      kind: 'danmu',
      text: final,
      uname: store.account.name || '我',
      uid: store.account.mid || undefined,
      color: 0xffffff,
      self: true,
    }
    // 先插本地，再登记回显窗口；pushMessage 对 self 消息会查这个窗口
    pushMessage(mine)
    pendingSelfEcho.push({ text: final, until: Date.now() + SELF_ECHO_WINDOW })
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
  return send(text.length <= LIMIT ? text : '+1')
}

export function clearSendError() {
  store.sendState.error = ''
}

/* ---------- 登录 ---------- */

export async function finishLogin(cookieStr: string) {
  saveCookieString(cookieStr)
  const [account] = await Promise.all([fetchAccount(), fetchBuvid()])
  store.account = account || {
    mid: Number(getCookieValue('DedeUserID') || 0),
    name: '已登录',
    avatar: '',
    level: 0,
  }
  store.loggedIn = true
  writeJson('dc_account', store.account)
  if (store.view === 'live') {
    danmaku.retry(store.account.mid || 0, getCookieValue('buvid3'))
  }
}

export async function logout() {
  await biliLogout()
  localStorage.removeItem('dc_cookies')
  store.loggedIn = false
  store.account = { mid: 0, name: '', avatar: '', level: 0 }
  writeJson('dc_account', store.account)
  if (store.view === 'live') danmaku.retry(0, '')
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
  } catch {
    /* 部分浏览器不支持某些 action */
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
    playing: store.playing,
  }
}

restoreSlots()