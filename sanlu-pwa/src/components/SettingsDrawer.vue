<script setup lang="ts">
import { nextTick, onBeforeUnmount, ref, watch } from 'vue'
import QRCode from 'qrcode'
import {
  finishLogin,
  logout,
  nudgeOffset,
  resetOffset,
  setCommentary,
  setQuality,
  setVolume,
  store,
} from '../lib/store'
import { qnLabel, qrGenerate, qrPoll } from '../lib/bili'
import {
  defaultRelay,
  getRelayBase,
  netStatus,
  probeRelay,
  setRelayBase,
} from '../lib/net'

type Tab = 'account' | 'danmaku' | 'mix' | 'video' | 'relay' | 'about'

const visible = ref(false)
const tab = ref<Tab>('account')

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
      await QRCode.toCanvas(qrCanvas.value, qr.imageUrl, { width: 204, margin: 1 })
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

function only(which: 'a' | 'b') {
  setVolume('a', which === 'a' ? 1 : 0)
  setVolume('b', which === 'b' ? 1 : 0)
}

function both() {
  setVolume('a', 0.85)
  setVolume('b', 1)
}

onBeforeUnmount(stopPoll)
defineOptions({ name: 'SettingsDrawer' })
</script>

<template>
  <Teleport to="body">
    <div v-if="visible" class="mask" @click.self="close">
      <div class="sheet">
        <header class="tabs">
          <button
            v-for="t in (['account', 'danmaku', 'mix', 'video', 'relay', 'about'] as const)"
            :key="t"
            class="tab"
            :class="{ on: tab === t }"
            @click="tab = t"
          >
            {{
              t === 'account'
                ? '账号'
                : t === 'danmaku'
                  ? '弹幕'
                  : t === 'mix'
                    ? '混音'
                    : t === 'video'
                      ? '画面'
                      : t === 'relay'
                        ? '中转'
                        : '关于'
            }}
          </button>
          <button class="close" @click="close">×</button>
        </header>

        <!-- 账号 -->
        <section v-if="tab === 'account'" class="pane">
          <template v-if="store.loggedIn">
            <div class="account-card">
              <img v-if="store.account.avatar" :src="store.account.avatar" referrerpolicy="no-referrer" alt="" />
              <div v-else class="avatar-empty">B</div>
              <div class="account-info">
                <strong>{{ store.account.name || '已登录' }}</strong>
                <small>
                  UID {{ store.account.mid || '—' }}
                  <template v-if="store.account.level"> · LV{{ store.account.level }}</template>
                </small>
              </div>
            </div>
            <p class="ok">✓ 已登录，可以发弹幕、+1、回复</p>
            <div class="segs wide">
              <button @click="doLogout">退出登录</button>
            </div>
          </template>
          <template v-else>
            <p class="dim">用 B 站 App 扫码登录。看直播不用登录，发弹幕才需要。</p>
            <div class="qr-box">
              <canvas v-show="qrState === 'waiting' || qrState === 'scanned'" ref="qrCanvas"></canvas>
              <div v-if="qrState === 'loading'" class="spinner big"></div>
              <div v-if="qrState === 'scanned'" class="qr-tip dim-tip">已扫码，请在手机上确认</div>
              <div v-if="qrState === 'expired'" class="qr-tip dim-tip">
                二维码过期了
                <button class="ghost" @click="startQr">刷新</button>
              </div>
              <div v-if="qrState === 'error'" class="qr-tip dim-tip">
                {{ qrError }}
                <button class="ghost" @click="startQr">重试</button>
              </div>
            </div>

            <details class="manual">
              <summary>手机不方便扫码？手动粘贴 Cookie</summary>
              <p class="dim">
                电脑浏览器登录 bilibili.com → F12 → Application → Cookies，把
                SESSDATA、bili_jct、DedeUserID 拼成 <code>k=v; k=v</code> 粘进来。
              </p>
              <textarea v-model="cookieText" rows="3" placeholder="SESSDATA=...; bili_jct=...; DedeUserID=..."></textarea>
              <p v-if="manualError" class="err-text">{{ manualError }}</p>
              <button class="ghost" :disabled="!cookieText.trim() || manualBusy" @click="saveCookie">
                {{ manualBusy ? '校验中…' : '保存 Cookie' }}
              </button>
            </details>
          </template>
        </section>

        <!-- 弹幕 -->
        <section v-else-if="tab === 'danmaku'" class="pane">
          <label class="row-item">
            <span>显示弹幕</span>
            <input type="checkbox" v-model="store.settings.danmaku.enabled" />
          </label>
          <label class="row-item">
            <span>显示昵称</span>
            <input type="checkbox" v-model="store.settings.danmaku.showName" />
          </label>
          <label class="row-item">
            <span>加粗</span>
            <input type="checkbox" v-model="store.settings.danmaku.bold" />
          </label>
          <div class="row-item">
            <span>样式</span>
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
          <div class="row-item">
            <span>字号</span>
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
          <div class="row-item">
            <span>不透明度</span>
            <input type="range" min="0.2" max="1" step="0.05" v-model.number="store.settings.danmaku.alpha" />
          </div>
          <div class="row-item">
            <span>速度</span>
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
          <div class="row-item">
            <span>显示区域</span>
            <div class="segs">
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
          <div class="row-item col">
            <span>屏蔽词（逗号或空格分隔）</span>
            <textarea v-model="blockedText" rows="2" placeholder="比如：抽奖, 活动, 关注"></textarea>
          </div>
        </section>

        <!-- 混音 -->
        <section v-else-if="tab === 'mix'" class="pane">
          <p class="dim">
            A 出声音和弹幕，B 出画面。两路解说有先后时，用下面的按钮把 A 的声音挪一挪；
            音量配比会按当前房间组合记住。
          </p>
          <div class="vol">
            <span>A 声音</span>
            <input
              type="range"
              min="0"
              max="1"
              step="0.05"
              :value="store.sessionVolumes.a"
              @input="setVolume('a', Number(($event.target as HTMLInputElement).value))"
            />
            <em>{{ Math.round(store.sessionVolumes.a * 100) }}%</em>
          </div>
          <div class="vol">
            <span>B 声音</span>
            <input
              type="range"
              min="0"
              max="1"
              step="0.05"
              :value="store.sessionVolumes.b"
              @input="setVolume('b', Number(($event.target as HTMLInputElement).value))"
            />
            <em>{{ Math.round(store.sessionVolumes.b * 100) }}%</em>
          </div>
          <div class="segs wide">
            <button @click="only('a')">只听 A</button>
            <button @click="only('b')">只听 B</button>
            <button @click="both">默认配比</button>
          </div>
          <div class="offset">
            <div class="offset-head">
              <span>A 相对 B 的延迟</span>
              <em>{{ store.sessionOffset > 0 ? '+' : '' }}{{ store.sessionOffset }} ms</em>
            </div>
            <div class="segs wide">
              <button @click="nudgeOffset(-500)">-500</button>
              <button @click="nudgeOffset(-100)">-100</button>
              <button @click="nudgeOffset(100)">+100</button>
              <button @click="nudgeOffset(500)">+500</button>
              <button @click="resetOffset">归零</button>
            </div>
            <p class="dim small">正数让 A 晚一点；负数改为让 B 晚一点（效果是 A 相对提前）。偏移按房间组合记忆。</p>
          </div>
        </section>

        <!-- 画面 -->
        <section v-else-if="tab === 'video'" class="pane">
          <div class="row-item">
            <span>A 路清晰度</span>
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
              <span v-if="!store.a.acceptQn.length" class="dim">开播后可选</span>
            </div>
          </div>
          <div class="row-item">
            <span>B 路清晰度</span>
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
              <span v-if="!store.b.acceptQn.length" class="dim">开播后可选</span>
            </div>
          </div>
          <div class="row-item col">
            <span>下次开播默认清晰度（A / B 通用）</span>
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
          <p class="dim small">当前 B 路：{{ qnLabel(store.b.qn) }}。原画码率高，移动网络建议超清。</p>
          <div class="row-item">
            <span>画面缩放</span>
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
          <label class="row-item">
            <span>A 解说小窗</span>
            <input
              type="checkbox"
              :checked="store.settings.commentaryWindow"
              @change="setCommentary(($event.target as HTMLInputElement).checked)"
            />
          </label>
        </section>

        <!-- 中转 -->
        <section v-else-if="tab === 'relay'" class="pane">
          <p class="dim">
            B 站网关会拒绝非 bilibili.com 来源的浏览器请求（CORS Origin 白名单），
            所以纯静态网页无法直接调用房间/播放接口。这里会自动直连优先，被拦后走中转。
          </p>
          <div class="mode-card" :class="netStatus.mode">
            <strong>
              当前：{{
                netStatus.mode === 'direct'
                  ? '浏览器直连'
                  : netStatus.mode === 'relay'
                    ? '中转'
                    : netStatus.mode === 'none'
                      ? '不可用'
                      : '未探测'
              }}
            </strong>
            <small>{{ netStatus.relay || '尚未建立连接' }}</small>
          </div>
          <div class="row-item col">
            <span>中转地址</span>
            <input v-model="relayBase" placeholder="https://你的中转.example.com" />
          </div>
          <div class="segs wide wrap">
            <button @click="saveRelay(false)">保存</button>
            <button @click="saveRelay(true)">用默认</button>
            <button :disabled="relayStatus === 'testing'" @click="testRelay">
              {{ relayStatus === 'testing' ? '测试中…' : '测试' }}
            </button>
            <button @click="disableRelay">只用直连</button>
          </div>
          <p v-if="relayStatus === 'ok'" class="ok">✓ 中转可用</p>
          <p v-if="relayStatus === 'fail'" class="err-text">✗ 不可用：线上地址必须是 https://，且 /api/live/room/v1/Room/room_init?id=6 能返回 JSON</p>
          <p class="dim small">
            项目自带的中转：{{
              defaultRelay()
            }}。也可以把仓库里的 deno/proxy.ts 部署到 Deno Deploy，填它给你的地址。
          </p>
        </section>

        <!-- 关于 -->
        <section v-else class="pane">
          <div class="about-logo">叠</div>
          <h3>叠播 DualCast v1.3</h3>
          <p class="dim">
            给「没有比赛画面版权、只能解说」的主播和粉丝：A 房间出弹幕和声音，
            B 房间出比赛画面，还能像斗鱼一样快速 +1、回复弹幕。
          </p>
          <p class="dim small">
            网页版是静态 PWA，可添加到主屏幕；B 站接口直连被 CORS 拦截时由中转兜底。Android 原生版在仓库 DualLive/。
          </p>
          <div class="segs wide wrap">
            <a class="link-btn" href="https://github.com/Yousaling0624/dualcast" target="_blank" rel="noreferrer">GitHub 仓库</a>
            <a class="link-btn" :href="`https://yousaling0624.github.io/dualcast/`" target="_blank" rel="noreferrer">网页版地址</a>
          </div>
          <p class="dim small">
            看直播不登录；发弹幕需要 B 站账号。设置与登录 Cookie 只存在本机浏览器；
            需要中转时会随请求转发给 B 站接口。
          </p>
        </section>
      </div>
    </div>
  </Teleport>
</template>

<style scoped>
.mask {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.58);
  z-index: 70;
  display: grid;
  place-items: end center;
}

.sheet {
  background: var(--surface);
  border-radius: 18px 18px 0 0;
  width: min(720px, 100%);
  max-height: 84dvh;
  display: flex;
  flex-direction: column;
  animation: fadeUp 0.22s ease;
  padding-bottom: var(--safe-bottom);
}

.tabs {
  display: flex;
  gap: 4px;
  padding: 10px 12px 0;
  align-items: center;
}

.tab {
  flex: 1;
  border: none;
  background: transparent;
  color: var(--text-dim);
  padding: 9px 0;
  border-radius: 9px 9px 0 0;
  cursor: pointer;
  font-size: 13px;
}

.tab.on {
  color: var(--text);
  background: var(--surface2);
  font-weight: 600;
}

.close {
  width: 30px;
  height: 30px;
  border: none;
  border-radius: 50%;
  background: var(--surface2);
  color: var(--text-dim);
  cursor: pointer;
  flex: 0 0 auto;
}

.pane {
  padding: 16px 18px 22px;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 13px;
}

.dim {
  color: var(--text-dim);
  font-size: 13px;
  margin: 0;
  line-height: 1.55;
}

.dim.small {
  font-size: 11px;
  opacity: 0.85;
}

.ok {
  color: var(--ok);
  font-size: 13px;
  margin: 0;
}

.err-text {
  color: var(--danger);
  font-size: 12px;
  margin: 0;
  line-height: 1.5;
}

.account-card {
  display: flex;
  align-items: center;
  gap: 12px;
  background: var(--bg2);
  border: 1px solid var(--line);
  border-radius: 14px;
  padding: 12px;
}

.account-card img,
.avatar-empty {
  width: 52px;
  height: 52px;
  border-radius: 50%;
  object-fit: cover;
  background: linear-gradient(135deg, #5b8cff, #7c5bff);
  display: grid;
  place-items: center;
  color: #fff;
  font-weight: 700;
  font-size: 20px;
}

.account-info {
  display: flex;
  flex-direction: column;
  gap: 3px;
}

.account-info strong {
  font-size: 15px;
}

.account-info small {
  font-size: 11px;
  color: var(--text-dim);
}

.qr-box {
  align-self: center;
  background: #fff;
  border-radius: 14px;
  padding: 10px;
  min-width: 224px;
  min-height: 224px;
  display: grid;
  place-items: center;
}

.qr-tip {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  font-size: 13px;
}

.dim-tip {
  color: #333;
}

.spinner.big {
  width: 26px;
  height: 26px;
  border-color: #ccc;
  border-top-color: #5b8cff;
}

.manual {
  border-top: 1px solid var(--line);
  padding-top: 10px;
}

.manual summary {
  font-size: 12px;
  color: var(--text-dim);
  cursor: pointer;
  margin-bottom: 8px;
}

.manual textarea,
.row-item textarea {
  width: 100%;
  background: var(--bg2);
  border: 1px solid var(--line);
  border-radius: 10px;
  color: var(--text);
  padding: 8px 10px;
  font-size: 12px;
  resize: vertical;
  margin-bottom: 8px;
  font-family: inherit;
}

.manual code {
  color: var(--accent2);
}

.row-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  font-size: 14px;
}

.row-item.col {
  flex-direction: column;
  align-items: stretch;
}

.row-item.col > span {
  margin-bottom: 6px;
}

.row-item input[type='text'],
.row-item > input:not([type]) {
  flex: 1;
  background: var(--bg2);
  border: 1px solid var(--line);
  border-radius: 10px;
  color: var(--text);
  padding: 9px 11px;
  font-size: 13px;
  outline: none;
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
}

.segs button {
  border: 1px solid var(--line);
  background: var(--bg2);
  color: var(--text-dim);
  border-radius: 999px;
  padding: 7px 13px;
  cursor: pointer;
  font-size: 13px;
}

.segs button.on {
  background: rgba(91, 140, 255, 0.2);
  border-color: rgba(91, 140, 255, 0.6);
  color: var(--text);
  font-weight: 600;
}

.segs button:disabled {
  opacity: 0.5;
}

input[type='range'] {
  flex: 1;
  accent-color: var(--accent);
}

input[type='checkbox'] {
  width: 20px;
  height: 20px;
  accent-color: var(--accent);
}

.vol {
  display: flex;
  align-items: center;
  gap: 12px;
  font-size: 14px;
}

.vol span {
  width: 62px;
}

.vol em {
  width: 42px;
  text-align: right;
  font-style: normal;
  color: var(--text-dim);
  font-size: 12px;
}

.offset {
  display: flex;
  flex-direction: column;
  gap: 8px;
  background: var(--bg2);
  border: 1px solid var(--line);
  border-radius: 12px;
  padding: 11px;
}

.offset-head {
  display: flex;
  justify-content: space-between;
  font-size: 13px;
}

.offset-head em {
  font-style: normal;
  color: var(--accent2);
}

.mode-card {
  display: flex;
  flex-direction: column;
  gap: 3px;
  background: var(--bg2);
  border: 1px solid var(--line);
  border-radius: 12px;
  padding: 11px 13px;
}

.mode-card strong {
  font-size: 14px;
}

.mode-card small {
  font-size: 11px;
  color: var(--text-dim);
  word-break: break-all;
}

.mode-card.direct strong {
  color: var(--ok);
}

.mode-card.relay strong {
  color: var(--accent2);
}

.mode-card.none strong {
  color: var(--danger);
}

.about-logo {
  width: 54px;
  height: 54px;
  border-radius: 14px;
  background: linear-gradient(135deg, #5b8cff, #7c5bff);
  display: grid;
  place-items: center;
  font-size: 26px;
  font-weight: 700;
  color: #fff;
}

.about-logo + h3 {
  margin: 0;
  font-size: 18px;
}

.link-btn {
  display: inline-block;
  border: 1px solid var(--line);
  background: var(--bg2);
  color: var(--text-dim);
  border-radius: 999px;
  padding: 7px 13px;
  cursor: pointer;
  font-size: 13px;
  text-decoration: none;
}

@media (max-width: 420px) {
  .tab {
    font-size: 12px;
  }
}
</style>