/**
 * 全局状态与会话编排：解析房间 → 双路播放 → A 房弹幕 → 聊天室 / 发弹幕。
 */
import { reactive, watch } from 'vue'
import { DanmakuClient, type ChatMsg, type DanmakuStatus } from './danmaku'
import { StreamPlayer } from './player'
import {
  fetchBuvid,
  fetchPlay,
  fetchRoomInfo,
  hasCookie,
  initRoom,
  saveCookieString,
  sendDanmaku,
} from './bili'

export interface SlotState {
  input: string
  roomId: number
  title: string
  uname: string
  liveStatus: number
  qn: number
  acceptQn: { qn: number; desc: string }[]
  state: 'idle' | 'loading' | 'ready' | 'error'
  error: string
}

export interface Combo {
  a: string
  aTitle: string
  b: string
  bTitle: string
}

export interface RecentRoom {
  roomId: number
  title: string
  uname: string
}

export interface Settings {
  danmaku: {
    enabled: boolean
    fontScale: number
    alpha: number
    speed: number
    area: number
    showName: boolean
    outline: boolean
    blocked: string[]
  }
  volume: { a: number; b: number }
  cover: boolean
}

function emptySlot(): SlotState {
  return {
    input: '',
    roomId: 0,
    title: '',
    uname: '',
    liveStatus: 0,
    qn: 0,
    acceptQn: [],
    state: 'idle',
    error: '',
  }
}

function load<T>(key: string, fallback: T): T {
  try {
    const raw = localStorage.getItem(key)
    return raw ? { ...fallback, ...JSON.parse(raw) } : fallback
  } catch {
    return fallback
  }
}

const DEFAULT_SETTINGS: Settings = {
  danmaku: {
    enabled: true,
    fontScale: 1,
    alpha: 1,
    speed: 1,
    area: 0.75,
    showName: false,
    outline: true,
    blocked: [],
  },
  volume: { a: 1, b: 1 },
  cover: false,
}

type View = 'home' | 'live'

export const store = reactive({
  view: 'home' as View,
  a: emptySlot(),
  b: emptySlot(),
  messages: [] as ChatMsg[],
  viewer: 0,
  danmakuStatus: 'idle' as DanmakuStatus,
  loggedIn: hasCookie(),
  starting: false,
  startError: '',
  swapping: false,
  reconnecting: false,
  sendState: { sending: false, error: '', lastOkAt: 0 },
  settings: load('dc_settings', DEFAULT_SETTINGS) as Settings,
  combos: load<{ list: Combo[] }>('dc_combos', { list: [] }).list as Combo[],
  recent: load<{ list: RecentRoom[] }>('dc_recent', { list: [] }).list as RecentRoom[],
  playing: true,
})

/* ---------- 非响应式实例 ---------- */

const danmaku = new DanmakuClient()
let playerA: StreamPlayer | null = null
let playerB: StreamPlayer | null = null
let videoAEl: HTMLVideoElement | null = null
let videoBEl: HTMLVideoElement | null = null

/** 弹幕层订阅：canvas 每帧从这里拿新弹幕去画 */
export const feedBuffer: ChatMsg[] = []

const MAX_MESSAGES = 200
let pushSeq = 0

function pushMessage(m: ChatMsg) {
  feedBuffer.push(m)
  store.messages.push(m)
  if (store.messages.length > MAX_MESSAGES) {
    store.messages.splice(0, store.messages.length - MAX_MESSAGES)
  }
}

watch(
  () => store.settings,
  () => localStorage.setItem('dc_settings', JSON.stringify(store.settings)),
  { deep: true }
)

// 音量实时应用到两个播放器
watch(
  () => store.settings.volume,
  (v) => {
    playerA?.setMuted(v.a === 0)
    playerB?.setMuted(v.b === 0)
    if (videoAEl) videoAEl.volume = Math.min(v.a, 1)
    if (videoBEl) videoBEl.volume = Math.min(v.b, 1)
  },
  { deep: true }
)

/* ---------- 视频 ---------- */

export function bindVideos(a: HTMLVideoElement, b: HTMLVideoElement) {
  videoAEl = a
  videoBEl = b
  playerA?.setMuted(store.settings.volume.a === 0)
  playerB?.setMuted(store.settings.volume.b === 0)
  playerA?.play()
  playerB?.play()
}

function destroyPlayers() {
  playerA?.destroy()
  playerB?.destroy()
  playerA = null
  playerB = null
}

function attachPlayer(slot: 'a' | 'b', play: { flv: string[]; hls: string[] }) {
  const video = slot === 'a' ? videoAEl : videoBEl
  if (!video) return
  const p = new StreamPlayer(video, play, (state) => {
    if (slot === 'a') {
      if (state === 'error') slotOf('a').error = 'A 路播放失败，点右上角重连试试'
    } else if (state === 'error') {
      slotOf('b').error = 'B 路播放失败，点右上角重连试试'
    }
  })
  p.setMuted(store.settings.volume[slot] === 0)
  p.start()
  if (slot === 'a') playerA = p
  else playerB = p
}

function slotOf(key: 'a' | 'b'): SlotState {
  return key === 'a' ? store.a : store.b
}

/* ---------- 房间解析 ---------- */

export async function resolveSlot(key: 'a' | 'b', input?: string) {
  const slot = slotOf(key)
  const value = (input ?? slot.input).trim()
  if (!value) {
    slot.state = 'error'
    slot.error = '先填房间号'
    return
  }
  slot.input = value
  slot.state = 'loading'
  slot.error = ''
  try {
    const { roomId } = await initRoom(value)
    const [info, play] = await Promise.all([fetchRoomInfo(roomId), fetchPlay(roomId)])
    slot.roomId = roomId
    slot.title = info.title
    slot.uname = info.uname
    slot.liveStatus = info.liveStatus
    slot.qn = play.qn
    slot.acceptQn = play.acceptQn
    slot.state = 'ready'
    if (info.liveStatus !== 1) slot.error = info.liveStatus === 2 ? '轮播中' : '未开播'
    // 播放地址挂在 slot 上，attach 时用
    slotStreams[key] = { flv: [], hls: [] }
    for (const s of play.streams) {
      if (s.protocol === 'flv') slotStreams[key].flv.push(...s.urls)
      else slotStreams[key].hls.push(...s.urls)
    }
  } catch (e) {
    slot.state = 'error'
    slot.error = e instanceof Error ? e.message : String(e)
  }
}

const slotStreams: Record<'a' | 'b', { flv: string[]; hls: string[] }> = {
  a: { flv: [], hls: [] },
  b: { flv: [], hls: [] },
}

export async function resolvePreview(key: 'a' | 'b') {
  // 首页「检测」用：只解析信息，不开播
  await resolveSlot(key)
}

/* ---------- 会话 ---------- */

export async function startSession() {
  if (store.starting) return
  store.starting = true
  store.startError = ''
  try {
    await Promise.all([resolveSlot('a'), resolveSlot('b')])
    if (store.a.state !== 'ready' || store.b.state !== 'ready') {
      throw new Error(
        store.a.state === 'error' ? `A 路解析失败：${store.a.error}` : store.b.error || '解析失败'
      )
    }
    if (store.a.liveStatus !== 1 || store.b.liveStatus !== 1) {
      throw new Error('两个房间都得在播才能叠，检查下未开播的那路')
    }
    beginSession()
  } catch (e) {
    store.startError = e instanceof Error ? e.message : String(e)
  } finally {
    store.starting = false
  }
}

function beginSession() {
  destroyPlayers()
  store.view = 'live'
  store.playing = true
  // 弹幕在 attachVideos 后启动（需要等 view 切过去拿 video 元素？弹幕不依赖 video，直接连）
  danmaku
    .connect(store.a.roomId, {
      onMsg: pushMessage,
      onStatus: (s) => (store.danmakuStatus = s),
      onViewer: (n) => (store.viewer = n),
    })
    .catch(() => {})
  saveCombo()
  pushRecent()
}

/** LiveView 挂载后调用：把待播的流接到 video 元素上 */
export function attachStreams() {
  if (slotStreams.a.flv.length || slotStreams.a.hls.length) {
    attachPlayer('a', slotStreams.a)
  }
  if (slotStreams.b.flv.length || slotStreams.b.hls.length) {
    attachPlayer('b', slotStreams.b)
  }
}

export async function reconnect() {
  if (store.reconnecting) return
  store.reconnecting = true
  try {
    await Promise.all([resolveSlot('a'), resolveSlot('b')])
    destroyPlayers()
    attachStreams()
    danmaku
      .connect(store.a.roomId, {
        onMsg: pushMessage,
        onStatus: (s) => (store.danmakuStatus = s),
        onViewer: (n) => (store.viewer = n),
      })
      .catch(() => {})
    store.messages = []
  } finally {
    store.reconnecting = false
  }
}

export async function swap() {
  if (store.swapping) return
  store.swapping = true
  const { a, b } = store
  const sa = { ...a }
  store.a = { ...b }
  store.b = sa
  const streams = slotStreams.a
  slotStreams.a = slotStreams.b
  slotStreams.b = streams
  store.messages = []
  try {
    await Promise.all([resolveSlot('a'), resolveSlot('b')])
    if (store.a.state !== 'ready' || store.b.state !== 'ready') throw new Error('解析失败')
    beginSessionInPlace()
  } catch (e) {
    store.startError = e instanceof Error ? e.message : String(e)
  } finally {
    store.swapping = false
  }
}

function beginSessionInPlace() {
  destroyPlayers()
  attachStreams()
  store.playing = true
  danmaku
    .connect(store.a.roomId, {
      onMsg: pushMessage,
      onStatus: (s) => (store.danmakuStatus = s),
      onViewer: (n) => (store.viewer = n),
    })
    .catch(() => {})
}

export function exitSession() {
  danmaku.stop()
  destroyPlayers()
  slotStreams.a = { flv: [], hls: [] }
  slotStreams.b = { flv: [], hls: [] }
  store.view = 'home'
  store.messages = []
}

export function togglePlay() {
  store.playing = !store.playing
  if (store.playing) {
    playerA?.play()
    playerB?.play()
  } else {
    playerA?.pause()
    playerB?.pause()
  }
}

export async function setQuality(key: 'a' | 'b', qn: number) {
  const slot = slotOf(key)
  if (slot.roomId) {
    const play = await fetchPlay(slot.roomId, qn)
    slot.qn = play.qn
    slotStreams[key] = { flv: [], hls: [] }
    for (const s of play.streams) {
      if (s.protocol === 'flv') slotStreams[key].flv.push(...s.urls)
      else slotStreams[key].hls.push(...s.urls)
    }
    if (key === 'b' && playerB) {
      playerB.destroy()
      attachPlayer('b', slotStreams.b)
    }
  }
}

/* ---------- 弹幕发送 ---------- */

const LIMIT = 20

export function buildReply(reply: string, target: ChatMsg): string {
  const name = (target.uname || '用户').slice(0, 12)
  const budget = LIMIT - reply.length - 1 - name.length - 1
  if (budget <= 0) return reply.slice(0, LIMIT)
  const quote =
    target.text.length > budget ? target.text.slice(0, Math.max(budget - 1, 0)) + '…' : target.text
  return `${reply}@${name}:${quote}`.slice(0, LIMIT)
}

export async function send(text: string, replyTarget?: ChatMsg | null) {
  if (store.sendState.sending) return
  const raw = text.trim()
  if (!raw) return
  store.sendState.sending = true
  store.sendState.error = ''
  try {
    const final = replyTarget ? buildReply(raw, replyTarget) : raw.slice(0, LIMIT)
    const r = await sendDanmaku(store.a.roomId, final)
    if (r.ok) {
      store.sendState.lastOkAt = Date.now()
      pushMessage({
        seq: 900000000 + ++pushSeq,
        kind: 'danmu',
        text: final,
        uname: '我',
        self: true,
      })
    } else {
      store.sendState.error = r.message
    }
  } catch (e) {
    store.sendState.error = e instanceof Error ? e.message : String(e)
  } finally {
    store.sendState.sending = false
  }
}

export async function plusOne(target: ChatMsg) {
  await send('+1')
}

/* ---------- 登录 ---------- */

export async function finishLogin(cookieStr: string) {
  saveCookieString(cookieStr)
  await fetchBuvid()
  store.loggedIn = true
}

export function logout() {
  localStorage.removeItem('dc_cookies')
  store.loggedIn = false
}

/* ---------- 组合与最近 ---------- */

function saveCombo() {
  const combo: Combo = {
    a: store.a.input,
    aTitle: store.a.title || store.a.uname,
    b: store.b.input,
    bTitle: store.b.title || store.b.uname,
  }
  const list = store.combos.filter((c) => !(c.a === combo.a && c.b === combo.b))
  list.unshift(combo)
  store.combos = list.slice(0, 12)
  localStorage.setItem('dc_combos', JSON.stringify({ list: store.combos }))
}

export function deleteCombo(i: number) {
  store.combos.splice(i, 1)
  localStorage.setItem('dc_combos', JSON.stringify({ list: store.combos }))
}

export async function startCombo(combo: Combo) {
  store.a.input = combo.a
  store.b.input = combo.b
  await startSession()
}

function pushRecent() {
  const list = store.recent.filter((r) => r.roomId !== store.a.roomId)
  list.unshift({ roomId: store.a.roomId, title: store.a.title, uname: store.a.uname })
  store.recent = list.slice(0, 8)
  localStorage.setItem('dc_recent', JSON.stringify({ list: store.recent }))
}

export function useRecent(room: RecentRoom) {
  store.a.input = String(room.roomId)
  void startSession()
}
