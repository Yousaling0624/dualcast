<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'

/**
 * 全屏时要把面板 Teleport 到全屏元素里面，否则浏览器只渲染全屏子树，
 * 面板看不见就只能先退出全屏。非全屏仍挂到 body。
 */
const props = defineProps<{ fullscreenEl?: HTMLElement | null }>()
import QRCode from 'qrcode'
import {
  addSource,
  finishLogin,
  logout,
  nudgeOffset,
  removeSource,
  resetOffset,
  retrySource,
  setCommentary,
  setOffset,
  setQuality,
  setVolume,
  store,
  unblockUser,
  updateSource,
} from '../lib/store'
import type { ExtraRoom } from '../lib/store'
import { SOURCE_PALETTE, badgePreviewText } from '../lib/sources'
import { qnLabel, qrGenerate, qrPoll } from '../lib/bili'
import {
  defaultRelay,
  getRelayBase,
  netStatus,
  probeRelay,
  setRelayBase,
} from '../lib/net'
import AppIcon from './AppIcon.vue'
import FollowPicker from './FollowPicker.vue'

type Tab = 'account' | 'danmaku' | 'multi' | 'mix' | 'video' | 'relay' | 'about'

const TABS = [
  { key: 'account', label: '账号', icon: 'user' },
  { key: 'danmaku', label: '弹幕', icon: 'danmaku' },
  { key: 'multi', label: '多路', icon: 'pip' },
  { key: 'mix', label: '混音', icon: 'tune' },
  { key: 'video', label: '画面', icon: 'quality' },
  { key: 'relay', label: '中转', icon: 'link' },
  { key: 'about', label: '关于', icon: 'sparkles' },
] as const

const visible = ref(false)
const tab = ref<Tab>('account')
const followPicker = ref<InstanceType<typeof FollowPicker> | null>(null)

const qrCanvas = ref<HTMLCanvasElement | null>(null)
const qrState = ref<'idle' | 'loading' | 'waiting' | 'scanned' | 'ok' | 'expired' | 'error'>('idle')
const qrError = ref('')
const cookieText = ref('')
const manualBusy = ref(false)
const manualError = ref('')
let pollTimer: number | null = null
let qrKey = ''
let pollFails = 0

function maybeStartQr() {
  // 已经生成/等待中的二维码不要因为重新打开面板而重置；
  // 但关闭面板时停掉的轮询要恢复。
  if (qrState.value === 'loading') return
  if (qrState.value === 'waiting' || qrState.value === 'scanned') {
    if (!pollTimer) pollTimer = window.setInterval(poll, 2000)
    return
  }
  if (!store.loggedIn) void startQr()
}

function open(t?: Tab) {
  if (t) tab.value = t
  visible.value = true
  if (tab.value === 'account') maybeStartQr()
  if (tab.value === 'relay') relayBase.value = getRelayBase()
}

defineExpose({ open })

function close() {
  visible.value = false
  stopPoll()
}

watch(tab, (t) => {
  if (t === 'account') maybeStartQr()
  if (t === 'relay') relayBase.value = getRelayBase()
})

watch(visible, (v) => {
  document.body.style.overflow = v ? 'hidden' : ''
})

async function startQr() {
  stopPoll()
  pollFails = 0
  qrState.value = 'loading'
  qrError.value = ''
  try {
    await nextTick()
    const qr = await qrGenerate()
    qrKey = qr.qrcodeKey
    if (qrCanvas.value) {
      await QRCode.toCanvas(qrCanvas.value, qr.imageUrl, {
        width: 204,
        margin: 1,
        color: { dark: '#201d19', light: '#fffdf9' },
      })
    }
    qrState.value = 'waiting'
    pollTimer = window.setInterval(poll, 2000)
  } catch (e) {
    qrState.value = 'error'
    qrError.value = e instanceof Error ? e.message : String(e)
  }
}

async function poll() {
  const key = qrKey
  try {
    const r = await qrPoll(key)
    if (key !== qrKey) return
    pollFails = 0
    if (r.code === 0) {
      // 先停轮询，避免多个 poll 并发重复登录
      stopPoll()
      if (!r.cookieString) {
        qrState.value = 'error'
        qrError.value = '登录成功但没拿到 Cookie，请重试或改用手动 Cookie'
        return
      }
      await finishLogin(r.cookieString)
      qrState.value = 'ok'
    } else if (r.code === 86090) {
      qrState.value = 'scanned'
    } else if (r.code === 86038) {
      qrState.value = 'expired'
      stopPoll()
    }
  } catch {
    // 网络抖动先等下一次；连续失败说明中转不可用，给用户明确提示
    if (++pollFails >= 3) {
      stopPoll()
      qrState.value = 'error'
      qrError.value = '扫码状态查询失败，检查「中转」设置后重试'
    }
  }
}

function stopPoll() {
  if (pollTimer) {
    clearInterval(pollTimer)
    pollTimer = null
  }
}

async function doLogout() {
  await logout()
  qrState.value = 'idle'
  void startQr()
}

async function saveCookie() {
  const text = cookieText.value.trim()
  if (!text) return
  if (!/SESSDATA=/.test(text)) {
    manualError.value = 'Cookie 里至少要包含 SESSDATA，最好再带上 bili_jct 和 DedeUserID'
    return
  }
  manualError.value = ''
  manualBusy.value = true
  try {
    await finishLogin(text)
    cookieText.value = ''
  } finally {
    manualBusy.value = false
  }
}

const blockedText = ref(store.settings.danmaku.blocked.join(','))
watch(blockedText, (t) => {
  store.settings.danmaku.blocked = t
    .split(/[,，\s]+/)
    .map((s) => s.trim())
    .filter(Boolean)
})

const AREA_LABEL: Record<number, string> = { 0.25: '1/4', 0.5: '半屏', 0.62: '默认', 0.75: '3/4', 1: '全屏' }
const SCALE_LABEL = { fit: '适应', crop: '铺满', fill: '拉伸' } as const

/* 中转 */
const relayBase = ref(getRelayBase())
const relayStatus = ref<'idle' | 'ok' | 'fail' | 'testing'>('idle')

function saveRelay(useDefault = false) {
  let v = useDefault ? defaultRelay() : relayBase.value.trim()
  v = v.replace(/\/+$/, '')
  if (v && !/^https?:\/\//.test(v)) v = ''
  relayBase.value = v
  setRelayBase(v)
  relayStatus.value = 'idle'
}

function disableRelay() {
  relayBase.value = ''
  setRelayBase('')
  relayStatus.value = 'idle'
}

async function testRelay() {
  relayStatus.value = 'testing'
  const ok = await probeRelay()
  relayStatus.value = ok ? 'ok' : 'fail'
}

/* 混音 */
const lastVol = { a: 0.85, b: 1 }

const offsetLabel = computed(() => {
  const o = store.sessionOffset
  if (!o) return '两路同步'
  return o > 0 ? `A 晚 ${o} ms` : `B 晚 ${-o} ms`
})

const offsetValue = computed({
  get: () => store.sessionOffset,
  set: (v: number | string) => setOffset(Number(v)),
})

function pct(which: 'a' | 'b'): number {
  return Math.round(store.sessionVolumes[which] * 100)
}

function toggleMute(which: 'a' | 'b') {
  const v = store.sessionVolumes[which]
  if (v > 0) {
    lastVol[which] = v
    setVolume(which, 0)
  } else {
    setVolume(which, lastVol[which] || 0.85)
  }
}

function only(which: 'a' | 'b') {
  setVolume('a', which === 'a' ? 1 : 0)
  setVolume('b', which === 'b' ? 1 : 0)
}

function both() {
  setVolume('a', 0.85)
  setVolume('b', 1)
}

/* 多路弹幕 / 解说 */
const sourceInput = ref('')

async function importSource() {
  if (store.sourceBusy || !sourceInput.value.trim()) return
  const source = await addSource(sourceInput.value)
  if (source) sourceInput.value = ''
}

function openFollowPicker() {
  followPicker.value?.open('multi')
}

function onFollowLogin() {
  tab.value = 'account'
}

function sourceStatus(s: ExtraRoom): string {
  if (s.audioError) return s.audioError
  if (s.error && s.status === 'idle') return s.error
  const media: string[] = []
  if (s.commentary) media.push('声音')
  if (s.video) media.push('画面')
  if (s.status === 'open') {
    if (!media.length) return '弹幕已连接'
    const mediaText = `${media.join(' + ')}${s.mediaState === 'playing' ? '' : '准备中'}`
    return `弹幕已连接 · ${mediaText}`
  }
  if (s.status === 'connecting') return '连接中…'
  if (s.status === 'reconnecting') return '重连中…'
  if (s.status === 'failed') return s.error || '连接失败'
  if (media.length) {
    return s.mediaState === 'loading' ? `${media.join(' + ')}准备中…` : `${media.join(' + ')}待播放`
  }
  return s.liveStatus === 1 ? '直播中 · 弹幕未连接' : '弹幕未连接'
}

function sourceBadge(s: ExtraRoom): string {
  return badgePreviewText(s.badgeMode, {
    uname: s.uname,
    custom: s.badgeText,
    color: s.color,
  })
}

function cycleSourceColor(s: ExtraRoom) {
  const index = SOURCE_PALETTE.indexOf(s.color as (typeof SOURCE_PALETTE)[number])
  updateSource(s.id, { color: SOURCE_PALETTE[(index + 1) % SOURCE_PALETTE.length] })
}

function setSourceBadgeMode(s: ExtraRoom, mode: ExtraRoom['badgeMode']) {
  updateSource(s.id, { badgeMode: mode })
}

function setSourceCustom(s: ExtraRoom, e: Event) {
  updateSource(s.id, { badgeText: (e.target as HTMLInputElement).value })
}

function setSourceCommentary(s: ExtraRoom, e: Event) {
  updateSource(s.id, { commentary: (e.target as HTMLInputElement).checked })
}

function setSourceVideo(s: ExtraRoom, e: Event) {
  updateSource(s.id, { video: (e.target as HTMLInputElement).checked })
}

function setSourceVolume(s: ExtraRoom, e: Event) {
  updateSource(s.id, { volume: Number((e.target as HTMLInputElement).value) })
}

function relayModeText(): string {
  if (netStatus.mode === 'direct') return '浏览器直连'
  if (netStatus.mode === 'relay') return '中转'
  if (netStatus.lastError) return '接口不可用'
  return '尚未探测'
}

function onKey(e: KeyboardEvent) {
  if (e.key === 'Escape' && visible.value) close()
}

onMounted(() => window.addEventListener('keydown', onKey))
onBeforeUnmount(() => {
  stopPoll()
  window.removeEventListener('keydown', onKey)
  document.body.style.overflow = ''
})
defineOptions({ name: 'SettingsDrawer' })
</script>

<template>
  <Teleport :to="props.fullscreenEl || 'body'">
    <Transition name="sheet">
      <div v-if="visible" class="mask" @click.self="close">
        <div class="sheet" role="dialog" aria-modal="true" aria-label="设置">
          <div class="grabber"></div>

          <header class="sheet-head">
            <div>
              <h2>设置</h2>
              <p>账号、弹幕、混音与播放</p>
            </div>
            <button class="close" title="关闭" @click="close">
              <AppIcon name="close" :size="19" />
            </button>
          </header>

          <nav class="tabs" aria-label="设置分类">
            <button
              v-for="t in TABS"
              :key="t.key"
              class="tab"
              :class="{ on: tab === t.key }"
              @click="tab = t.key"
            >
              <AppIcon :name="t.icon" :size="16" />
              <span>{{ t.label }}</span>
            </button>
          </nav>

          <div class="pane scroll-y">
            <!-- 账号 -->
            <section v-if="tab === 'account'" class="pane-inner">
              <div class="group">
                <div class="group-head">
                  <div>
                    <h3>外观</h3>
                    <p>深色模式会跟着系统自动切换，也可以在这里固定。</p>
                  </div>
                  <AppIcon name="sparkles" :size="19" class="group-icon accent" />
                </div>
                <div class="row theme-row">
                  <span class="row-text">
                    <strong>主题</strong>
                    <small>页面、设置面板和输入框统一换色</small>
                  </span>
                  <div class="segs theme-segs">
                    <button
                      :class="{ on: store.settings.theme === 'auto' }"
                      @click="store.settings.theme = 'auto'"
                    >
                      跟随系统
                    </button>
                    <button
                      :class="{ on: store.settings.theme === 'light' }"
                      @click="store.settings.theme = 'light'"
                    >
                      浅色
                    </button>
                    <button
                      :class="{ on: store.settings.theme === 'dark' }"
                      @click="store.settings.theme = 'dark'"
                    >
                      深色
                    </button>
                  </div>
                </div>
              </div>

              <template v-if="store.loggedIn">
                <div class="account-card">
                  <img
                    v-if="store.account.avatar"
                    :src="store.account.avatar"
                    referrerpolicy="no-referrer"
                    alt=""
                  />
                  <div v-else class="avatar-empty">{{ (store.account.name || 'B').slice(0, 1) }}</div>
                  <div class="account-info">
                    <strong>{{ store.account.name || '已登录' }}</strong>
                    <small>
                      UID {{ store.account.mid || '—' }}
                      <template v-if="store.account.level"> · LV{{ store.account.level }}</template>
                    </small>
                  </div>
                  <span class="account-badge"><AppIcon name="check" :size="13" />已登录</span>
                </div>
                <p class="dim">可以发弹幕、+1 和回复。看直播本身不需要登录。</p>
                <button class="btn-outline wide" @click="doLogout">
                  <AppIcon name="user" :size="16" />
                  退出登录
                </button>
              </template>

              <template v-else>
                <div class="group">
                  <div class="group-head">
                    <div>
                      <h3>扫码登录</h3>
                      <p>用 B 站 App 扫一下，看直播不用登录，互动才需要。</p>
                    </div>
                    <AppIcon name="user" :size="19" class="group-icon" />
                  </div>

                  <div class="qr-box">
                    <canvas v-show="qrState === 'waiting' || qrState === 'scanned'" ref="qrCanvas"></canvas>
                    <div v-if="qrState === 'loading'" class="qr-tip">
                      <span class="spinner big"></span>
                      <small>正在生成二维码…</small>
                    </div>
                    <div v-if="qrState === 'scanned'" class="qr-tip qr-overlay">
                      <span class="qr-ok"><AppIcon name="check" :size="18" /></span>
                      <small>已扫码，请在手机上确认</small>
                    </div>
                    <div v-if="qrState === 'expired'" class="qr-tip">
                      <small>二维码过期了</small>
                      <button class="btn-outline" @click="startQr">刷新</button>
                    </div>
                    <div v-if="qrState === 'error'" class="qr-tip">
                      <small>{{ qrError }}</small>
                      <button class="btn-outline" @click="startQr">重试</button>
                    </div>
                  </div>
                </div>

                <details class="manual">
                  <summary>手机不方便扫码？手动粘贴 Cookie</summary>
                  <p class="dim">
                    电脑浏览器登录 bilibili.com → F12 → Application → Cookies，把
                    SESSDATA、bili_jct、DedeUserID 拼成 <code>k=v; k=v</code> 粘进来。
                  </p>
                  <textarea
                    v-model="cookieText"
                    class="field"
                    rows="3"
                    placeholder="SESSDATA=...; bili_jct=...; DedeUserID=..."
                  ></textarea>
                  <p v-if="manualError" class="err-text">{{ manualError }}</p>
                  <button
                    class="btn-outline"
                    :disabled="!cookieText.trim() || manualBusy"
                    @click="saveCookie"
                  >
                    {{ manualBusy ? '校验中…' : '保存 Cookie' }}
                  </button>
                </details>
              </template>
            </section>

            <!-- 弹幕 -->
            <section v-else-if="tab === 'danmaku'" class="pane-inner">
              <div class="group">
                <div class="group-head">
                  <div>
                    <h3>弹幕显示</h3>
                    <p>控制画面上的滚动弹幕。</p>
                  </div>
                  <AppIcon name="danmaku" :size="19" class="group-icon" />
                </div>

                <label class="row">
                  <span class="row-text"><strong>显示弹幕</strong><small>关闭后画面更干净</small></span>
                  <span class="switch">
                    <input v-model="store.settings.danmaku.enabled" type="checkbox" />
                    <span class="track"></span><span class="thumb"></span>
                  </span>
                </label>
                <label class="row">
                  <span class="row-text">
                    <strong>同时看 A+B 弹幕</strong>
                    <small>把比赛画面（B 路）的弹幕也合进同一屏，用蓝色气泡区分</small>
                  </span>
                  <span class="switch">
                    <input v-model="store.settings.danmaku.includeB" type="checkbox" />
                    <span class="track"></span><span class="thumb"></span>
                  </span>
                </label>
                <label class="row">
                  <span class="row-text"><strong>显示昵称</strong><small>在弹幕前加上发送者</small></span>
                  <span class="switch">
                    <input v-model="store.settings.danmaku.showName" type="checkbox" />
                    <span class="track"></span><span class="thumb"></span>
                  </span>
                </label>
                <label class="row">
                  <span class="row-text"><strong>加粗</strong><small>更醒目，适合小屏</small></span>
                  <span class="switch">
                    <input v-model="store.settings.danmaku.bold" type="checkbox" />
                    <span class="track"></span><span class="thumb"></span>
                  </span>
                </label>

                <div class="row col">
                  <span class="row-text"><strong>样式</strong></span>
                  <div class="segs">
                    <button
                      v-for="s in (['outline', 'shadow', 'plain'] as const)"
                      :key="s"
                      :class="{ on: store.settings.danmaku.style === s }"
                      @click="store.settings.danmaku.style = s"
                    >
                      {{ s === 'outline' ? '描边' : s === 'shadow' ? '阴影' : '纯色' }}
                    </button>
                  </div>
                </div>

                <div class="row col">
                  <span class="row-text"><strong>字号</strong></span>
                  <div class="segs">
                    <button
                      v-for="s in [0.8, 1, 1.25, 1.5]"
                      :key="s"
                      :class="{ on: Math.abs(store.settings.danmaku.fontScale - s) < 0.01 }"
                      @click="store.settings.danmaku.fontScale = s"
                    >
                      {{ s === 0.8 ? '小' : s === 1 ? '中' : s === 1.25 ? '大' : '特大' }}
                    </button>
                  </div>
                </div>

                <div class="row col">
                  <div class="range-label">
                    <span class="row-text"><strong>不透明度</strong></span>
                    <em>{{ Math.round(store.settings.danmaku.alpha * 100) }}%</em>
                  </div>
                  <input
                    v-model.number="store.settings.danmaku.alpha"
                    type="range"
                    min="0.2"
                    max="1"
                    step="0.05"
                    :style="{ '--fill': Math.round(store.settings.danmaku.alpha * 100) + '%' }"
                  />
                </div>

                <div class="row col">
                  <span class="row-text"><strong>速度</strong></span>
                  <div class="segs">
                    <button
                      v-for="s in [0.6, 1, 1.6]"
                      :key="s"
                      :class="{ on: Math.abs(store.settings.danmaku.speed - s) < 0.01 }"
                      @click="store.settings.danmaku.speed = s"
                    >
                      {{ s === 0.6 ? '慢' : s === 1 ? '正常' : '快' }}
                    </button>
                  </div>
                </div>

                <div class="row col">
                  <span class="row-text"><strong>显示区域</strong></span>
                  <div class="segs wrap">
                    <button
                      v-for="(label, val) in AREA_LABEL"
                      :key="val"
                      :class="{ on: Math.abs(store.settings.danmaku.area - Number(val)) < 0.01 }"
                      @click="store.settings.danmaku.area = Number(val)"
                    >
                      {{ label }}
                    </button>
                  </div>
                </div>

                <div class="row col">
                  <div class="range-label">
                    <span class="row-text"><strong>屏蔽词</strong><small>逗号或空格分隔；留空则不过滤</small></span>
                    <button
                      v-if="store.settings.danmaku.blocked.length"
                      class="text-btn"
                      @click="blockedText = ''"
                    >
                      清空（{{ store.settings.danmaku.blocked.length }}）
                    </button>
                  </div>
                  <textarea v-model="blockedText" class="field" rows="2" placeholder="比如：抽奖, 活动, 关注"></textarea>
                </div>

                <div v-if="store.settings.danmaku.blockedUsers.length" class="row col">
                  <div class="range-label">
                    <span class="row-text">
                      <strong>屏蔽用户</strong>
                      <small>点画面弹幕或聊天室里的「屏蔽用户」添加；点名字可解除</small>
                    </span>
                    <button
                      class="text-btn"
                      @click="store.settings.danmaku.blockedUsers = []"
                    >
                      清空（{{ store.settings.danmaku.blockedUsers.length }}）
                    </button>
                  </div>
                  <div class="blocked-users">
                    <button
                      v-for="u in store.settings.danmaku.blockedUsers"
                      :key="u.uid || u.name"
                      class="blocked-user"
                      :title="`解除屏蔽 ${u.name || 'UID ' + u.uid}`"
                      @click="unblockUser(u.uid, u.name)"
                    >
                      <span>{{ u.name || `UID ${u.uid}` }}</span>
                      <AppIcon name="close" :size="11" />
                    </button>
                  </div>
                </div>
              </div>

              <div class="group">
                <div class="group-head">
                  <div>
                    <h3>SC 悬浮窗</h3>
                    <p>和浏览器脚本一样，在画面左上角浮出醒目留言；全屏也会显示。</p>
                  </div>
                  <AppIcon name="sc" :size="19" class="group-icon accent" />
                </div>

                <label class="row">
                  <span class="row-text"><strong>显示 SC</strong><small>画面左上角的悬浮卡片</small></span>
                  <span class="switch">
                    <input v-model="store.settings.sc.enabled" type="checkbox" />
                    <span class="track"></span><span class="thumb"></span>
                  </span>
                </label>
                <label class="row">
                  <span class="row-text"><strong>显示金额</strong><small>在卡片上显示 SC 价格</small></span>
                  <span class="switch">
                    <input v-model="store.settings.sc.showPrice" type="checkbox" />
                    <span class="track"></span><span class="thumb"></span>
                  </span>
                </label>

                <div class="row col">
                  <div class="range-label">
                    <span class="row-text"><strong>停留时长</strong><small>到时间自动收起，可在卡片上点 × 提前关闭</small></span>
                    <em>{{ store.settings.sc.duration }}s</em>
                  </div>
                  <input
                    v-model.number="store.settings.sc.duration"
                    type="range"
                    min="4"
                    max="30"
                    step="1"
                    :style="{ '--fill': ((store.settings.sc.duration - 4) / 26) * 100 + '%' }"
                  />
                </div>

                <div class="row col">
                  <span class="row-text"><strong>同时显示</strong></span>
                  <div class="segs">
                    <button
                      v-for="n in 5"
                      :key="n"
                      :class="{ on: store.settings.sc.max === n }"
                      @click="store.settings.sc.max = n"
                    >
                      {{ n }}
                    </button>
                  </div>
                </div>
              </div>
            </section>

            <!-- 混音 -->
            <section v-else-if="tab === 'multi'" class="pane-inner">
              <div class="group">
                <div class="group-head">
                  <div>
                    <h3>多路弹幕 / 声音 / 画面</h3>
                    <p>
                      导入其他直播间后，弹幕会带上彩色气泡。可以单独开声音，也可以把画面放进
                      左下角的多路墙；比赛主画面仍然保留 B 路。
                    </p>
                  </div>
                  <AppIcon name="plus" :size="19" class="group-icon accent" />
                </div>

                <div class="source-import">
                  <input
                    v-model="sourceInput"
                    class="field"
                    placeholder="房间号或 live.bilibili.com 链接"
                    @keydown.enter="importSource"
                  />
                  <button
                    class="btn-outline"
                    :disabled="store.sourceBusy || !sourceInput.trim()"
                    @click="importSource"
                  >
                    {{ store.sourceBusy ? '解析中…' : '导入' }}
                  </button>
                </div>
                <p v-if="store.sourceError" class="err-text">{{ store.sourceError }}</p>

                <button class="btn-outline wide source-follow-btn" @click="openFollowPicker">
                  <AppIcon name="star" :size="15" />
                  从关注的主播里选正在直播的
                </button>

                <div v-for="s in store.sources" :key="s.id" class="source-card">
                  <div class="source-head">
                    <span class="source-badge" :style="{ background: s.color }">
                      {{ sourceBadge(s) }}
                    </span>
                    <div class="source-who">
                      <strong>{{ s.uname || s.title || `房间 ${s.roomId}` }}</strong>
                      <small>
                        {{ s.title || `房间 ${s.roomId}` }} · {{ sourceStatus(s) }}
                      </small>
                    </div>
                    <button
                      class="mini-icon"
                      title="重试这一路"
                      @click="retrySource(s.id)"
                    >
                      <AppIcon name="refresh" :size="15" />
                    </button>
                    <button
                      class="mini-icon danger"
                      title="移除这个直播间"
                      @click="removeSource(s.id)"
                    >
                      <AppIcon name="close" :size="15" />
                    </button>
                  </div>

                  <div class="source-row">
                    <span class="source-kicker">气泡</span>
                    <div class="segs">
                      <button
                        v-for="mode in (['color', 'initial', 'custom'] as const)"
                        :key="mode"
                        :class="{ on: s.badgeMode === mode }"
                        @click="setSourceBadgeMode(s, mode)"
                      >
                        {{ mode === 'color' ? '颜色' : mode === 'initial' ? '首字' : '自定义' }}
                      </button>
                    </div>
                    <button class="color-cycle" title="换一个气泡颜色" @click="cycleSourceColor(s)">
                      <i :style="{ background: s.color }"></i>
                      换色
                    </button>
                  </div>

                  <div v-if="s.badgeMode === 'custom'" class="source-row">
                    <span class="source-kicker">自定义字</span>
                    <input
                      class="field compact"
                      :value="s.badgeText"
                      maxlength="2"
                      placeholder="比如：主、二台、A"
                      @input="setSourceCustom(s, $event)"
                    />
                  </div>

                  <label class="row source-commentary">
                    <span class="row-text">
                      <strong>加入多路声音</strong>
                      <small>和 A 解说一起混音，可独立调音量</small>
                    </span>
                    <span class="switch">
                      <input
                        type="checkbox"
                        :checked="s.commentary"
                        @change="setSourceCommentary(s, $event)"
                      />
                      <span class="track"></span><span class="thumb"></span>
                    </span>
                  </label>

                  <div v-if="s.commentary" class="source-row">
                    <span class="source-kicker">音量</span>
                    <input
                      class="source-vol"
                      type="range"
                      min="0"
                      max="1"
                      step="0.01"
                      :value="s.volume"
                      :style="{ '--fill': Math.round(s.volume * 100) + '%' }"
                      @input="setSourceVolume(s, $event)"
                    />
                    <em class="source-pct">{{ Math.round(s.volume * 100) }}%</em>
                  </div>

                  <label class="row source-commentary">
                    <span class="row-text">
                      <strong>加入多路画面</strong>
                      <small>在画面左下角的多路墙里显示这一路直播</small>
                    </span>
                    <span class="switch">
                      <input
                        type="checkbox"
                        :checked="s.video"
                        @change="setSourceVideo(s, $event)"
                      />
                      <span class="track"></span><span class="thumb"></span>
                    </span>
                  </label>
                </div>

                <p v-if="!store.sources.length" class="dim small">
                  还没有导入其他直播间。导入后可以同时看多个直播间的弹幕。
                </p>
              </div>
            </section>

            <section v-else-if="tab === 'mix'" class="pane-inner">
              <div class="group">
                <div class="group-head">
                  <div>
                    <h3>两路音量</h3>
                    <p>A 出解说和弹幕，B 出比赛画面。音量配比按房间组合记忆。</p>
                  </div>
                  <AppIcon name="volume" :size="19" class="group-icon" />
                </div>

                <div class="channel-card a">
                  <div class="channel-head">
                    <span class="channel-letter a">A</span>
                    <div class="channel-info">
                      <strong>解说 · 声音与弹幕</strong>
                      <small>{{ store.a.uname || store.a.title || '主直播间' }}</small>
                    </div>
                    <button
                      class="mini-icon"
                      :class="{ muted: store.sessionVolumes.a === 0 }"
                      :title="store.sessionVolumes.a === 0 ? '取消静音' : '静音'"
                      @click="toggleMute('a')"
                    >
                      <AppIcon name="volume" :size="17" />
                    </button>
                  </div>
                  <div class="vol-slider">
                    <input
                      type="range"
                      min="0"
                      max="1"
                      step="0.01"
                      :value="store.sessionVolumes.a"
                      :style="{ '--fill': pct('a') + '%' }"
                      @input="setVolume('a', Number(($event.target as HTMLInputElement).value))"
                    />
                    <em>{{ pct('a') }}%</em>
                  </div>
                  <div class="channel-foot">
                    <span>声音 · 弹幕 · SC</span>
                    <button class="text-btn" @click="only('a')">只听 A</button>
                  </div>
                </div>

                <div class="channel-card b">
                  <div class="channel-head">
                    <span class="channel-letter b">B</span>
                    <div class="channel-info">
                      <strong>画面 · 比赛现场</strong>
                      <small>{{ store.b.uname || store.b.title || '画面直播间' }}</small>
                    </div>
                    <button
                      class="mini-icon"
                      :class="{ muted: store.sessionVolumes.b === 0 }"
                      :title="store.sessionVolumes.b === 0 ? '取消静音' : '静音'"
                      @click="toggleMute('b')"
                    >
                      <AppIcon name="volume" :size="17" />
                    </button>
                  </div>
                  <div class="vol-slider">
                    <input
                      type="range"
                      min="0"
                      max="1"
                      step="0.01"
                      :value="store.sessionVolumes.b"
                      :style="{ '--fill': pct('b') + '%' }"
                      @input="setVolume('b', Number(($event.target as HTMLInputElement).value))"
                    />
                    <em>{{ pct('b') }}%</em>
                  </div>
                  <div class="channel-foot">
                    <span>画面 · 现场声</span>
                    <button class="text-btn" @click="only('b')">只听 B</button>
                  </div>
                </div>

                <div class="segs wide">
                  <button @click="both">默认配比</button>
                  <button @click="only('a')">只留解说</button>
                  <button @click="only('b')">只留现场</button>
                </div>
              </div>

              <div class="group offset-group">
                <div class="group-head">
                  <div>
                    <h3>延迟校准</h3>
                    <p>解说比画面快或慢时，让快的那一路主动暂停一下，等另一路追上来。</p>
                  </div>
                  <AppIcon name="tune" :size="19" class="group-icon accent" />
                </div>

                <div class="offset-card">
                  <div class="offset-head">
                    <span class="offset-kicker">当前偏移</span>
                    <strong :class="{ zero: !store.sessionOffset }">{{ offsetLabel }}</strong>
                  </div>
                  <div class="offset-ends">
                    <span>← A 提前</span>
                    <span>A 延后 →</span>
                  </div>
                  <input
                    v-model.number="offsetValue"
                    class="offset-range"
                    type="range"
                    min="-20000"
                    max="20000"
                    step="50"
                    :style="{ '--fill': ((store.sessionOffset + 20000) / 40000) * 100 + '%' }"
                  />
                  <div class="offset-scale">
                    <span>-20s</span>
                    <span>0</span>
                    <span>+20s</span>
                  </div>
                  <div class="nudge-row">
                    <button class="chip" @click="nudgeOffset(-500)">-0.5s</button>
                    <button class="chip" @click="nudgeOffset(-100)">-0.1s</button>
                    <button class="chip" @click="nudgeOffset(100)">+0.1s</button>
                    <button class="chip" @click="nudgeOffset(500)">+0.5s</button>
                    <button class="chip reset" :disabled="!store.sessionOffset" @click="resetOffset">
                      归零
                    </button>
                  </div>
                </div>

                <p class="dim small">
                  往右拖（正数）时 A 路会暂停相应时长，让直播缓冲先跑，恢复后解说就落在画面后面；
                  往左拖（负数）则改为暂停 B 路。往回调时会自动跳到缓冲更靠前的位置追回来，
                  所以调节时某一头短暂停住是预期效果。偏移和音量都会按「A 房间 + B 房间」记住。
                </p>
              </div>
            </section>

            <!-- 画面 -->
            <section v-else-if="tab === 'video'" class="pane-inner">
              <div class="group">
                <div class="group-head">
                  <div>
                    <h3>清晰度</h3>
                    <p>原画码率高，移动网络建议超清。</p>
                  </div>
                  <AppIcon name="quality" :size="19" class="group-icon" />
                </div>
                <div class="row col">
                  <span class="row-text"><strong>A 路 · 解说</strong><small>当前 {{ qnLabel(store.a.qn) }}</small></span>
                  <div class="segs wrap">
                    <button
                      v-for="q in store.a.acceptQn"
                      :key="q.qn"
                      :class="{ on: q.qn === store.a.qn }"
                      :disabled="store.qualitySwitching"
                      @click="setQuality('a', q.qn)"
                    >
                      {{ q.desc }}
                    </button>
                    <span v-if="!store.a.acceptQn.length" class="dim small">开播后可选</span>
                  </div>
                </div>
                <div class="row col">
                  <span class="row-text"><strong>B 路 · 画面</strong><small>当前 {{ qnLabel(store.b.qn) }}</small></span>
                  <div class="segs wrap">
                    <button
                      v-for="q in store.b.acceptQn"
                      :key="q.qn"
                      :class="{ on: q.qn === store.b.qn }"
                      :disabled="store.qualitySwitching"
                      @click="setQuality('b', q.qn)"
                    >
                      {{ q.desc }}
                    </button>
                    <span v-if="!store.b.acceptQn.length" class="dim small">开播后可选</span>
                  </div>
                </div>
                <div class="row col">
                  <span class="row-text"><strong>下次开播默认清晰度</strong><small>A / B 通用</small></span>
                  <div class="segs wrap">
                    <button
                      v-for="qn in [10000, 400, 250, 150]"
                      :key="qn"
                      :class="{ on: store.settings.video.defaultQn === qn }"
                      @click="store.settings.video.defaultQn = qn"
                    >
                      {{ qnLabel(qn) }}
                    </button>
                  </div>
                </div>
              </div>

              <div class="group">
                <div class="group-head">
                  <div>
                    <h3>画面布局</h3>
                    <p>只影响 B 路视频的显示方式。</p>
                  </div>
                  <AppIcon name="pip" :size="19" class="group-icon" />
                </div>
                <div class="row col">
                  <span class="row-text"><strong>缩放</strong></span>
                  <div class="segs">
                    <button
                      v-for="s in (['fit', 'crop', 'fill'] as const)"
                      :key="s"
                      :class="{ on: store.settings.video.scale === s }"
                      @click="store.settings.video.scale = s"
                    >
                      {{ SCALE_LABEL[s] }}
                    </button>
                  </div>
                </div>
                <label class="row">
                  <span class="row-text"><strong>A 解说小窗</strong><small>在画面右上角显示 A 路视频</small></span>
                  <span class="switch">
                    <input
                      type="checkbox"
                      :checked="store.settings.commentaryWindow"
                      @change="setCommentary(($event.target as HTMLInputElement).checked)"
                    />
                    <span class="track"></span><span class="thumb"></span>
                  </span>
                </label>
              </div>
            </section>

            <!-- 中转 -->
            <section v-else-if="tab === 'relay'" class="pane-inner">
              <p class="dim">
                B 站网关会拒绝非 bilibili.com 来源的浏览器请求（CORS Origin 白名单），
                所以纯静态网页无法直接调用房间/播放接口。这里会自动直连优先，被拦后走中转。
              </p>

              <div class="mode-card" :class="[netStatus.mode, { bad: !!netStatus.lastError }]">
                <span class="mode-dot"></span>
                <div>
                  <strong>{{ relayModeText() }}</strong>
                  <small>{{ netStatus.relay || '尚未建立连接' }}</small>
                  <small v-if="netStatus.lastError" class="mode-err">{{ netStatus.lastError }}</small>
                </div>
              </div>

              <div class="row col">
                <span class="row-text"><strong>中转地址</strong><small>必须是 https://，末尾不带斜杠</small></span>
                <div class="field-with-icon">
                  <AppIcon name="link" :size="16" />
                  <input v-model="relayBase" class="field" placeholder="https://你的中转.example.com" />
                </div>
              </div>

              <div class="segs wide wrap">
                <button @click="saveRelay(false)">保存</button>
                <button @click="saveRelay(true)">用默认</button>
                <button :disabled="relayStatus === 'testing'" @click="testRelay">
                  <span v-if="relayStatus === 'testing'" class="spinner"></span>
                  <template v-else>测试</template>
                </button>
                <button @click="disableRelay">只用直连</button>
              </div>

              <p v-if="relayStatus === 'ok'" class="ok">
                <AppIcon name="check" :size="14" />中转可用
              </p>
              <p v-if="relayStatus === 'fail'" class="err-text">
                <AppIcon name="warning" :size="14" />
                不可用：线上地址必须是 https://，且 <code>/api/live/room/v1/Room/room_init?id=6</code> 能返回 JSON
              </p>
              <p class="dim small">
                项目自带的中转：{{ defaultRelay() }}（EdgeOne）。也可以填自建地址；
                中转只转发 B 站接口和直播流，不改变你的网络出口。
              </p>
            </section>

            <!-- 关于 -->
            <section v-else class="pane-inner">
              <div class="about-hero">
                <div class="about-logo">叠</div>
                <div>
                  <h3>叠播 DualCast</h3>
                  <p>v1.12.0 · PWA 网页版</p>
                </div>
              </div>
              <p class="dim">
                给「没有比赛画面版权、只能解说」的主播和粉丝：A 房间出弹幕和声音，
                B 房间出比赛画面，还能像斗鱼一样快速 +1、回复弹幕，并在画面上看 SC。
              </p>
              <p class="dim small">
                网页版是静态 PWA，可添加到主屏幕；B 站接口直连被 CORS 拦截时由中转兜底。
                Android 原生版在仓库 DualLive/。
              </p>

              <div class="links">
                <a class="link-btn" href="https://github.com/Yousaling0624/dualcast" target="_blank" rel="noreferrer">
                  <AppIcon name="github" :size="16" />
                  GitHub 仓库
                </a>
                <a class="link-btn" href="https://yousaling0624.github.io/dualcast/" target="_blank" rel="noreferrer">
                  <AppIcon name="link" :size="16" />
                  GitHub Pages
                </a>
                <a class="link-btn" href="https://sanlu.yousaling.dpdns.org" target="_blank" rel="noreferrer">
                  <AppIcon name="link" :size="16" />
                  Cloudflare 备用
                </a>
              </div>

              <div class="privacy-card">
                <AppIcon name="warning" :size="17" />
                <p>
                  看直播不登录；发弹幕需要 B 站账号。设置与登录 Cookie 只存在本机浏览器；
                  需要中转时会随请求转发给 B 站接口。不要在这个设备上登录他人账号。
                </p>
              </div>
            </section>
          </div>
        </div>
      </div>
    </Transition>
    <FollowPicker
      ref="followPicker"
      :fullscreen-el="props.fullscreenEl"
      @login="onFollowLogin"
    />
  </Teleport>
</template>

<style scoped>
.mask {
  position: fixed;
  inset: 0;
  background: rgba(28, 23, 18, 0.5);
  z-index: 70;
  display: grid;
  place-items: end center;
  backdrop-filter: blur(3px);
}

.sheet {
  position: relative;
  background: var(--bg-soft);
  border-radius: 30px 30px 0 0;
  width: min(760px, 100%);
  max-height: 88dvh;
  display: flex;
  flex-direction: column;
  animation: sheetUp 0.3s cubic-bezier(0.2, 0.85, 0.25, 1);
  padding-bottom: var(--safe-bottom);
  border: 1px solid var(--glass-border);
  border-bottom: none;
  box-shadow: 0 -24px 80px rgba(28, 23, 18, 0.28);
  overflow: hidden;
}

.sheet-enter-active,
.sheet-leave-active {
  transition: opacity 0.24s ease;
}

.sheet-enter-from,
.sheet-leave-to {
  opacity: 0;
}

.sheet-enter-active .sheet,
.sheet-leave-active .sheet {
  transition: transform 0.28s cubic-bezier(0.2, 0.85, 0.25, 1);
}

.sheet-enter-from .sheet,
.sheet-leave-to .sheet {
  transform: translateY(24px);
}

@keyframes sheetUp {
  from {
    transform: translateY(24px);
    opacity: 0.7;
  }
  to {
    transform: translateY(0);
    opacity: 1;
  }
}

.grabber {
  width: 38px;
  height: 4px;
  border-radius: 999px;
  background: var(--line-strong);
  margin: 8px auto 2px;
  flex: 0 0 auto;
}

.sheet-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 10px 20px 12px;
  flex: 0 0 auto;
}

.sheet-head h2 {
  margin: 0;
  font-family: var(--font-display);
  font-size: 24px;
  font-weight: 550;
  letter-spacing: -0.015em;
}

.sheet-head p {
  margin: 1px 0 0;
  font-size: 11px;
  color: var(--text-faint);
}

.close {
  width: 36px;
  height: 36px;
  border: 1px solid var(--line);
  border-radius: 50%;
  background: var(--surface);
  color: var(--text-dim);
  display: grid;
  place-items: center;
  cursor: pointer;
  flex: 0 0 auto;
}

.tabs {
  display: flex;
  gap: 5px;
  padding: 0 16px 12px;
  overflow-x: auto;
  flex: 0 0 auto;
  scrollbar-width: none;
}

.tabs::-webkit-scrollbar {
  display: none;
}

.tab {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  border: 1px solid transparent;
  background: transparent;
  color: var(--text-dim);
  border-radius: 999px;
  padding: 8px 13px;
  font-size: 12px;
  font-weight: 550;
  cursor: pointer;
  white-space: nowrap;
  flex: 0 0 auto;
  transition:
    background 0.16s ease,
    color 0.16s ease;
}

.tab:hover {
  background: var(--surface-2);
}

.tab.on {
  background: var(--surface);
  border-color: var(--line);
  color: var(--accent-strong);
  box-shadow: var(--shadow-card);
}

.pane {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 0 16px 22px;
}

.pane-inner {
  display: flex;
  flex-direction: column;
  gap: 14px;
  max-width: 640px;
  margin: 0 auto;
}

.group {
  background: var(--surface);
  border: 1px solid var(--line);
  border-radius: 22px;
  padding: 15px 15px 10px;
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.group-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  padding: 0 2px 10px;
  border-bottom: 1px solid var(--line);
  margin-bottom: 4px;
}

.group-head h3 {
  margin: 0;
  font-size: 14px;
  font-weight: 650;
}

.group-head p {
  margin: 3px 0 0;
  font-size: 11.5px;
  line-height: 1.5;
  color: var(--text-faint);
  max-width: 440px;
}

.group-icon {
  color: var(--text-faint);
  flex: 0 0 auto;
  margin-top: 1px;
}

.group-icon.accent {
  color: var(--accent);
}

.row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 14px;
  min-height: 48px;
  padding: 7px 2px;
  border-bottom: 1px solid var(--line);
  font-size: 13.5px;
}

.row:last-child {
  border-bottom: none;
}

.row.col {
  flex-direction: column;
  align-items: stretch;
  gap: 8px;
  padding-top: 10px;
  padding-bottom: 10px;
}

.row-text {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}

.row-text strong {
  font-size: 13px;
  font-weight: 550;
}

.row-text small {
  font-size: 10.5px;
  color: var(--text-faint);
  line-height: 1.4;
}

.range-label {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 10px;
}

.range-label em {
  font-style: normal;
  font-size: 12px;
  font-weight: 650;
  color: var(--accent-strong);
}

.segs {
  display: flex;
  gap: 6px;
  flex-wrap: nowrap;
}

.segs.wrap {
  flex-wrap: wrap;
}

.segs.wide {
  justify-content: center;
  padding-top: 4px;
}

/* 外观：三个选项在手机上也不挤 */
.theme-segs {
  flex-wrap: wrap;
  justify-content: flex-end;
}

.theme-segs button {
  padding: 6px 10px;
  font-size: 11.5px;
}

.segs button {
  border: 1px solid var(--line);
  background: var(--bg-soft);
  color: var(--text-dim);
  border-radius: 999px;
  padding: 7px 13px;
  cursor: pointer;
  font-size: 12px;
  font-weight: 500;
  transition:
    background 0.15s ease,
    border-color 0.15s ease,
    color 0.15s ease;
}

.segs button:hover:not(:disabled) {
  background: var(--surface-2);
}

.segs button.on {
  background: var(--accent-soft);
  border-color: rgba(217, 119, 87, 0.35);
  color: var(--accent-strong);
  font-weight: 650;
}

.segs button:disabled {
  opacity: 0.45;
}

input[type='range'] {
  width: 100%;
  flex: 1;
  accent-color: var(--accent);
  height: 22px;
}

.field {
  width: 100%;
  background: var(--bg-soft);
  border: 1px solid var(--line);
  border-radius: 13px;
  color: var(--text);
  padding: 10px 13px;
  font-size: 13px;
  outline: none;
  resize: vertical;
  transition:
    border-color 0.15s ease,
    box-shadow 0.15s ease;
}

.field:focus {
  border-color: rgba(217, 119, 87, 0.55);
  box-shadow: 0 0 0 3px var(--accent-soft);
  background: var(--surface);
}

.field-with-icon {
  position: relative;
  display: flex;
  align-items: center;
  color: var(--text-faint);
}

.field-with-icon :deep(.icon) {
  position: absolute;
  left: 12px;
  pointer-events: none;
}

.field-with-icon .field {
  padding-left: 36px;
}

.dim {
  color: var(--text-dim);
  font-size: 13px;
  margin: 0;
  line-height: 1.6;
}

.dim.small {
  font-size: 11.5px;
  line-height: 1.6;
}

.ok {
  display: flex;
  align-items: center;
  gap: 5px;
  color: var(--ok);
  font-size: 12px;
  margin: 0;
}

.err-text {
  display: flex;
  align-items: flex-start;
  gap: 5px;
  color: var(--danger);
  font-size: 12px;
  margin: 0;
  line-height: 1.5;
}

/* 多路弹幕 */
.source-import {
  display: flex;
  gap: 7px;
  margin: 9px 0 3px;
}

.source-import .field {
  flex: 1;
  min-width: 0;
}

.source-import .btn-outline {
  flex: 0 0 auto;
  padding: 9px 15px;
}

.source-follow-btn {
  margin: 7px 0 3px;
  padding: 9px 15px;
  font-size: 12px;
  color: var(--text-dim);
}

.source-follow-btn:hover {
  color: var(--accent-strong);
  border-color: rgba(217, 119, 87, 0.32);
  background: var(--accent-soft);
}

.source-card {
  margin: 7px 0 4px;
  padding: 10px 11px 6px;
  border: 1px solid var(--line);
  border-radius: 17px;
  background: var(--bg-soft);
}

.source-head {
  display: flex;
  align-items: center;
  gap: 8px;
}

.source-badge {
  min-width: 24px;
  height: 22px;
  padding: 0 8px;
  border-radius: 999px;
  display: inline-grid;
  place-items: center;
  color: #fff;
  font-size: 11px;
  font-weight: 700;
  line-height: 1;
  flex: 0 0 auto;
  box-shadow: inset 0 0 0 1px rgba(255, 255, 255, 0.22);
}

.source-who {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 1px;
}

.source-who strong {
  font-size: 12.5px;
  font-weight: 600;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.source-who small {
  font-size: 10px;
  color: var(--text-faint);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.source-head .mini-icon {
  width: 29px;
  height: 29px;
}

.source-head .mini-icon.danger {
  color: var(--danger);
}

.source-row {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 8px;
  min-width: 0;
}

.source-kicker {
  width: 54px;
  flex: 0 0 auto;
  font-size: 10.5px;
  color: var(--text-faint);
}

.source-row .segs {
  gap: 4px;
}

.source-row .segs button {
  padding: 5px 10px;
  font-size: 11px;
}

.color-cycle {
  margin-left: auto;
  display: inline-flex;
  align-items: center;
  gap: 5px;
  border: 1px solid var(--line);
  border-radius: 999px;
  background: var(--surface);
  color: var(--text-dim);
  font-size: 10.5px;
  padding: 5px 9px;
  cursor: pointer;
  flex: 0 0 auto;
}

.color-cycle i {
  width: 10px;
  height: 10px;
  border-radius: 50%;
  display: block;
}

.field.compact {
  flex: 1;
  min-width: 0;
  padding: 7px 10px;
  font-size: 12px;
}

.source-commentary {
  border-bottom: none;
  min-height: 0;
  padding-top: 8px;
  padding-bottom: 2px;
}

.source-commentary .row-text strong {
  font-size: 12px;
}

.source-vol {
  flex: 1;
}

.source-pct {
  width: 34px;
  text-align: right;
  font-style: normal;
  font-size: 10px;
  font-weight: 600;
  color: var(--text-dim);
  flex: 0 0 auto;
}

/* 账号 */
.account-card {
  display: flex;
  align-items: center;
  gap: 12px;
  background: var(--surface);
  border: 1px solid var(--line);
  border-radius: 22px;
  padding: 14px;
  box-shadow: var(--shadow-card);
}

.account-card img,
.avatar-empty {
  width: 54px;
  height: 54px;
  border-radius: 18px;
  object-fit: cover;
  background: linear-gradient(145deg, #8b99df, var(--accent-2));
  display: grid;
  place-items: center;
  color: #fff;
  font-weight: 650;
  font-size: 21px;
  flex: 0 0 auto;
}

.account-info {
  display: flex;
  flex-direction: column;
  gap: 3px;
  min-width: 0;
  flex: 1;
}

.account-info strong {
  font-size: 15px;
  font-weight: 650;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.account-info small {
  font-size: 11px;
  color: var(--text-faint);
}

.account-badge {
  display: inline-flex;
  align-items: center;
  gap: 3px;
  font-size: 10px;
  font-weight: 600;
  color: var(--ok);
  background: var(--ok-soft);
  border-radius: 999px;
  padding: 4px 8px;
  flex: 0 0 auto;
}

.btn-outline {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  border: 1px solid var(--line);
  border-radius: 999px;
  background: var(--surface);
  color: var(--text);
  padding: 10px 16px;
  font-size: 12.5px;
  cursor: pointer;
}

.btn-outline.wide {
  width: 100%;
}

.btn-outline:disabled {
  opacity: 0.5;
}

.qr-box {
  align-self: center;
  position: relative;
  background: var(--surface);
  border: 1px solid var(--line);
  border-radius: 22px;
  padding: 12px;
  min-width: 228px;
  min-height: 228px;
  display: grid;
  place-items: center;
  margin: 10px 0 4px;
  box-shadow: var(--shadow-card);
}

.qr-tip {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 9px;
  font-size: 12px;
  color: var(--text-dim);
  text-align: center;
  padding: 0 10px;
}

.qr-overlay {
  position: absolute;
  inset: 12px;
  background: var(--glass-strong);
  border-radius: 14px;
  justify-content: center;
  backdrop-filter: blur(4px);
}

.qr-ok {
  width: 38px;
  height: 38px;
  border-radius: 50%;
  background: var(--ok-soft);
  color: var(--ok);
  display: grid;
  place-items: center;
}

.spinner.big {
  width: 28px;
  height: 28px;
  border-color: var(--surface-3);
  border-right-color: var(--accent);
}

.manual {
  border-top: 1px solid var(--line);
  padding-top: 12px;
}

.manual summary {
  font-size: 12px;
  color: var(--text-dim);
  cursor: pointer;
  margin-bottom: 9px;
}

.manual .dim {
  margin-bottom: 8px;
}

.manual code {
  color: var(--accent-strong);
  background: var(--accent-soft);
  border-radius: 4px;
  padding: 0 3px;
}

.manual .field {
  margin-bottom: 8px;
}

/* 混音 */
.channel-card {
  border: 1px solid var(--line);
  border-radius: 18px;
  background: var(--bg-soft);
  padding: 11px 12px 9px;
  margin: 6px 0;
}

.channel-card.a {
  border-color: rgba(217, 119, 87, 0.22);
  background: linear-gradient(135deg, var(--glass-strong), var(--accent-soft));
}

.channel-card.b {
  border-color: rgba(102, 121, 207, 0.2);
  background: linear-gradient(135deg, var(--glass-strong), var(--accent-2-soft));
}

.channel-head {
  display: flex;
  align-items: center;
  gap: 9px;
}

.channel-letter {
  width: 30px;
  height: 30px;
  border-radius: 10px;
  display: grid;
  place-items: center;
  font-family: var(--font-display);
  font-size: 14px;
  font-weight: 700;
  color: #fff;
  flex: 0 0 auto;
}

.channel-letter.a {
  background: linear-gradient(140deg, #e28a6d, var(--accent));
}

.channel-letter.b {
  background: linear-gradient(140deg, #8b99df, var(--accent-2));
}

.channel-info {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 1px;
}

.channel-info strong {
  font-size: 12.5px;
  font-weight: 600;
}

.channel-info small {
  font-size: 10px;
  color: var(--text-faint);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.mini-icon {
  width: 32px;
  height: 32px;
  border: 1px solid var(--line);
  border-radius: 50%;
  background: var(--surface);
  color: var(--text-dim);
  display: grid;
  place-items: center;
  cursor: pointer;
  flex: 0 0 auto;
}

.mini-icon.muted {
  color: var(--danger);
  background: var(--danger-soft);
  border-color: transparent;
}

.vol-slider {
  display: flex;
  align-items: center;
  gap: 9px;
  margin-top: 7px;
}

.vol-slider em {
  width: 38px;
  text-align: right;
  font-style: normal;
  font-size: 10.5px;
  font-weight: 600;
  color: var(--text-dim);
}

.channel-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: 1px;
  font-size: 10px;
  color: var(--text-faint);
}

.text-btn {
  border: none;
  background: none;
  color: var(--accent-strong);
  font-size: 10.5px;
  font-weight: 600;
  cursor: pointer;
  padding: 2px 0;
}

.blocked-users {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.blocked-user {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  max-width: 100%;
  padding: 5px 8px;
  border: 1px solid var(--line);
  border-radius: 999px;
  background: var(--bg-soft);
  color: var(--text-dim);
  font-size: 11px;
  cursor: pointer;
}

.blocked-user span {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.blocked-user:hover {
  border-color: var(--accent);
  color: var(--accent-strong);
}

.offset-group {
  gap: 4px;
}

.offset-card {
  border: 1px solid var(--line);
  border-radius: 18px;
  background: var(--bg-soft);
  padding: 13px;
  margin-top: 4px;
}

.offset-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 10px;
}

.offset-kicker {
  font-size: 10px;
  letter-spacing: 0.08em;
  text-transform: uppercase;
  color: var(--text-faint);
  font-weight: 650;
}

.offset-head strong {
  font-family: var(--font-display);
  font-size: 17px;
  font-weight: 550;
  color: var(--accent-strong);
}

.offset-head strong.zero {
  color: var(--ok);
}

.offset-ends {
  display: flex;
  justify-content: space-between;
  margin-top: 10px;
  font-size: 9.5px;
  color: var(--text-faint);
}

.offset-range {
  margin: 0;
  width: 100%;
}

.offset-scale {
  display: flex;
  justify-content: space-between;
  margin-top: -3px;
  font-size: 9px;
  color: var(--text-faint);
}

.nudge-row {
  display: flex;
  gap: 6px;
  margin-top: 9px;
  flex-wrap: wrap;
}

.chip {
  border: 1px solid var(--line);
  border-radius: 999px;
  background: var(--surface);
  color: var(--text-dim);
  padding: 5px 10px;
  font-size: 11px;
  cursor: pointer;
}

.chip.reset {
  margin-left: auto;
  color: var(--accent-strong);
  border-color: rgba(217, 119, 87, 0.25);
}

.chip:disabled {
  opacity: 0.4;
}

/* 中转 */
.mode-card {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  background: var(--surface);
  border: 1px solid var(--line);
  border-radius: 18px;
  padding: 13px;
}

.mode-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--text-faint);
  margin-top: 5px;
  flex: 0 0 auto;
}

.mode-card.direct .mode-dot {
  background: var(--ok);
  box-shadow: 0 0 0 4px var(--ok-soft);
}

.mode-card.relay .mode-dot {
  background: var(--gold);
  box-shadow: 0 0 0 4px var(--gold-soft);
}

.mode-card.none .mode-dot,
.mode-card.bad .mode-dot {
  background: var(--danger);
  box-shadow: 0 0 0 4px var(--danger-soft);
}

.mode-card > div {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}

.mode-card strong {
  font-size: 13px;
}

.mode-card.direct strong {
  color: var(--ok);
}

.mode-card.relay strong {
  color: var(--gold);
}

.mode-card.none strong,
.mode-card.bad strong {
  color: var(--danger);
}

.mode-card small {
  font-size: 10.5px;
  color: var(--text-faint);
  word-break: break-all;
}

.mode-card small.mode-err {
  color: var(--danger);
}

/* 关于 */
.about-hero {
  display: flex;
  align-items: center;
  gap: 13px;
  padding: 4px 2px 2px;
}

.about-logo {
  width: 56px;
  height: 56px;
  border-radius: 19px;
  background: linear-gradient(145deg, #e28a6d, var(--accent) 55%, #c46545);
  display: grid;
  place-items: center;
  font-family: var(--font-display);
  font-size: 27px;
  font-weight: 700;
  color: #fff9f4;
  box-shadow: 0 10px 24px rgba(184, 93, 63, 0.22);
  flex: 0 0 auto;
}

.about-hero h3 {
  margin: 0;
  font-family: var(--font-display);
  font-size: 20px;
  font-weight: 550;
}

.about-hero p {
  margin: 2px 0 0;
  font-size: 11px;
  color: var(--text-faint);
}

.links {
  display: flex;
  flex-wrap: wrap;
  gap: 7px;
}

.link-btn {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  border: 1px solid var(--line);
  background: var(--surface);
  color: var(--text-dim);
  border-radius: 999px;
  padding: 8px 13px;
  cursor: pointer;
  font-size: 12px;
  text-decoration: none;
  transition:
    color 0.15s ease,
    border-color 0.15s ease;
}

.link-btn:hover {
  color: var(--text);
  border-color: var(--text-faint);
}

.privacy-card {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  background: var(--surface-2);
  border-radius: 16px;
  padding: 11px 13px;
  color: var(--text-dim);
}

.privacy-card :deep(.icon) {
  color: var(--gold);
  flex: 0 0 auto;
  margin-top: 1px;
}

.privacy-card p {
  margin: 0;
  font-size: 11px;
  line-height: 1.6;
}

@media (min-width: 720px) {
  .mask {
    place-items: center;
  }

  .sheet {
    border-radius: 30px;
    border-bottom: 1px solid var(--glass-border);
    max-height: 86dvh;
  }

  .grabber {
    display: none;
  }

  .sheet-head {
    padding-top: 18px;
  }

  .pane {
    padding-bottom: 24px;
  }
}

@media (max-width: 560px) {
  /* 7 个分类排成 4 + 3，第二行和第一行同一列对齐，不会右半边散开 */
  .tabs {
    display: grid;
    grid-template-columns: repeat(4, minmax(0, 1fr));
    gap: 6px;
    overflow: visible;
  }

  .tab {
    justify-content: center;
    padding: 8px 4px;
    font-size: 11.5px;
    gap: 4px;
  }

  /* 主题三个选项在窄屏上整行等宽排列，不会被挤成 2 + 1 */
  .theme-row {
    flex-direction: column;
    align-items: stretch;
    gap: 8px;
    padding-top: 10px;
    padding-bottom: 10px;
  }

  .theme-row .theme-segs {
    display: grid;
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }
}

@media (max-width: 520px) {
  .sheet-head {
    padding-left: 16px;
    padding-right: 16px;
  }

  .sheet-head h2 {
    font-size: 22px;
  }

  .tabs {
    padding-left: 12px;
    padding-right: 12px;
  }

  .pane {
    padding-left: 12px;
    padding-right: 12px;
  }

  .group {
    border-radius: 20px;
    padding-left: 13px;
    padding-right: 13px;
  }

  .row {
    gap: 10px;
  }

  .segs {
    flex-wrap: wrap;
  }

  .nudge-row {
    gap: 5px;
  }

  .chip {
    padding: 5px 9px;
  }
}
</style>
