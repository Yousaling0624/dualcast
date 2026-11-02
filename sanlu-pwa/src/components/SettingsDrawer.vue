<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from 'vue'
import QRCode from 'qrcode'
import { finishLogin, logout, setQuality, store } from '../lib/store'
import { qrGenerate, qrPoll, hasCookie, getProxyBase, setProxyBase } from '../lib/bili'

const visible = ref(false)
const tab = ref<'account' | 'danmaku' | 'mix' | 'video' | 'relay'>('account')

const qrCanvas = ref<HTMLCanvasElement | null>(null)
const qrState = ref<'idle' | 'loading' | 'waiting' | 'scanned' | 'ok' | 'expired' | 'error'>('idle')
const qrError = ref('')
let pollTimer: number | null = null
let qrKey = ''

function open(t?: 'account' | 'danmaku' | 'mix' | 'video' | 'relay') {
  if (t) tab.value = t
  visible.value = true
  if (tab.value === 'account' && !store.loggedIn) startQr()
}
defineExpose({ open })

function close() {
  visible.value = false
  stopPoll()
}

watch(tab, (t) => {
  if (t === 'account' && !store.loggedIn && qrState.value !== 'waiting') startQr()
})

async function startQr() {
  stopPoll()
  qrState.value = 'loading'
  qrError.value = ''
  try {
    const qr = await qrGenerate()
    qrKey = qr.qrcodeKey
    await QRCode.toCanvas(qrCanvas.value, qr.imageUrl, { width: 216, margin: 1 })
    qrState.value = 'waiting'
    pollTimer = window.setInterval(poll, 2000)
  } catch (e) {
    qrState.value = 'error'
    qrError.value = e instanceof Error ? e.message : String(e)
  }
}

async function poll() {
  try {
    const r = await qrPoll(qrKey)
    if (r.code === 0 && r.cookieString) {
      await finishLogin(r.cookieString)
      qrState.value = 'ok'
      stopPoll()
    } else if (r.code === 86090) {
      qrState.value = 'scanned'
    } else if (r.code === 86038) {
      qrState.value = 'expired'
      stopPoll()
    }
  } catch {
    /* 网络抖动等下一次 */
  }
}

function stopPoll() {
  if (pollTimer) {
    clearInterval(pollTimer)
    pollTimer = null
  }
}

async function doLogout() {
  logout()
  qrState.value = 'idle'
  startQr()
}

const blockedText = ref(store.settings.danmaku.blocked.join(','))
watch(blockedText, (t) => {
  store.settings.danmaku.blocked = t
    .split(/[,，\s]+/)
    .map((s) => s.trim())
    .filter(Boolean)
})

function setVolume(which: 'a' | 'b', v: number) {
  store.settings.volume[which] = v
}

function only(which: 'a' | 'b') {
  store.settings.volume.a = which === 'a' ? 1 : 0
  store.settings.volume.b = which === 'b' ? 1 : 0
}

function both() {
  store.settings.volume.a = 0.85
  store.settings.volume.b = 1
}

const AREA_LABEL: Record<number, string> = { 0.25: '1/4', 0.5: '半屏', 0.75: '3/4', 1: '全屏' }

/* 中转设置 */
const relayBase = ref(getProxyBase())
const relayStatus = ref<'idle' | 'ok' | 'fail' | 'testing'>('idle')

function saveRelay() {
  let v = relayBase.value.trim().replace(/\/+$/, '')
  if (v && !/^https:\/\//.test(v)) v = ''
  relayBase.value = v
  setProxyBase(v)
  relayStatus.value = 'idle'
}

async function testRelay() {
  const base = getProxyBase()
  if (!base) return
  relayStatus.value = 'testing'
  try {
    const res = await fetch(`${base}/api/live/room/v1/Room/room_init?id=6`)
    const j = await res.json()
    relayStatus.value = j?.code === 0 ? 'ok' : 'fail'
  } catch {
    relayStatus.value = 'fail'
  }
}

onBeforeUnmount(stopPoll)
defineOptions({ name: 'SettingsDrawer' })
</script>

<template>
  <Teleport to="body">
    <div v-if="visible" class="mask" @click.self="close">
      <div class="sheet">
        <header>
          <button
            v-for="t in (['account','danmaku','mix','video','relay'] as const)"
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
                    ? '声音'
                    : t === 'video'
                      ? '画面'
                      : '中转'
            }}
          </button>
          <button class="close" @click="close">×</button>
        </header>

        <!-- 账号 -->
        <section v-if="tab === 'account'" class="pane">
          <template v-if="store.loggedIn">
            <p class="ok">✓ 已登录 B 站，可以发弹幕、+1、回复</p>
            <button class="ghost" @click="doLogout">退出登录</button>
          </template>
          <template v-else>
            <p class="dim">用 B 站 App 扫码登录。看直播不用登录，发弹幕才需要。</p>
            <div class="qr-box">
              <canvas v-show="qrState === 'waiting' || qrState === 'scanned'" ref="qrCanvas"></canvas>
              <div v-if="qrState === 'loading'" class="spinner big"></div>
              <div v-if="qrState === 'scanned'" class="qr-tip">已扫码，请在手机上确认</div>
              <div v-if="qrState === 'expired'" class="qr-tip">
                二维码过期了
                <button class="ghost" @click="startQr">刷新</button>
              </div>
              <div v-if="qrState === 'error'" class="qr-tip">
                {{ qrError }}
                <button class="ghost" @click="startQr">重试</button>
              </div>
            </div>
          </template>
        </section>

        <!-- 弹幕 -->
        <section v-else-if="tab === 'danmaku'" class="pane">
          <label class="row-item">
            <span>显示弹幕</span>
            <input type="checkbox" v-model="store.settings.danmaku.enabled" />
          </label>
          <label class="row-item">
            <span>显示用户名</span>
            <input type="checkbox" v-model="store.settings.danmaku.showName" />
          </label>
          <label class="row-item">
            <span>描边样式</span>
            <input type="checkbox" v-model="store.settings.danmaku.outline" />
          </label>
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
            <input type="range" min="0.3" max="1" step="0.05" v-model.number="store.settings.danmaku.alpha" />
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
            <span>屏蔽词（逗号分隔）</span>
            <textarea v-model="blockedText" rows="2" placeholder="比如：抽, 活动, 关注"></textarea>
          </div>
        </section>

        <!-- 声音 -->
        <section v-else-if="tab === 'mix'" class="pane">
          <p class="dim">A 出声音和弹幕，B 出画面。哪路太快 / 太慢就在混音里微调听感。</p>
          <div class="vol">
            <span>A 声音</span>
            <input type="range" min="0" max="1" step="0.05" :value="store.settings.volume.a" @input="setVolume('a', Number(($event.target as HTMLInputElement).value))" />
            <em>{{ Math.round(store.settings.volume.a * 100) }}%</em>
          </div>
          <div class="vol">
            <span>B 声音</span>
            <input type="range" min="0" max="1" step="0.05" :value="store.settings.volume.b" @input="setVolume('b', Number(($event.target as HTMLInputElement).value))" />
            <em>{{ Math.round(store.settings.volume.b * 100) }}%</em>
          </div>
          <div class="segs wide">
            <button @click="only('a')">只听 A</button>
            <button @click="only('b')">只听 B</button>
            <button @click="both">双声道</button>
          </div>
        </section>

        <!-- 中转 -->
        <section v-else-if="tab === 'relay'" class="pane">
          <p class="dim">
            B 站封了浏览器直连和本站代理出口。部署一个免费的 Deno 中转（1 分钟）即可恢复：
            dash.deno.com → New Playground → 粘贴项目里的 deno/proxy.ts → Save。
          </p>
          <div class="row-item col">
            <span>中转地址</span>
            <input v-model="relayBase" placeholder="https://你的名字.deno.dev" />
          </div>
          <div class="segs wide">
            <button @click="saveRelay">保存</button>
            <button :disabled="!getProxyBase()" @click="testRelay">测试</button>
          </div>
          <p v-if="relayStatus === 'ok'" class="ok">✓ 中转可用，房间解析已恢复</p>
          <p v-if="relayStatus === 'testing'" class="dim">测试中…</p>
          <p v-if="relayStatus === 'fail'" class="dim">✗ 不可用：确认 deno 脚本已保存、地址以 https:// 开头</p>
        </section>

        <!-- 画面 -->
        <section v-else class="pane">
          <p class="dim">画质影响 B 路（画面那路），清晰度列表来自 B 站。</p>
          <div class="row-item">
            <span>画质</span>
            <div class="segs">
              <button
                v-for="q in store.b.acceptQn"
                :key="q.qn"
                :class="{ on: q.qn === store.b.qn }"
                @click="setQuality('b', q.qn)"
              >
                {{ q.desc }}
              </button>
              <span v-if="!store.b.acceptQn.length" class="dim">开播后可选</span>
            </div>
          </div>
          <label class="row-item">
            <span>画面铺满（裁掉多余部分）</span>
            <input type="checkbox" v-model="store.settings.cover" />
          </label>
        </section>
      </div>
    </div>
  </Teleport>
</template>

<style scoped>
.mask {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.55);
  z-index: 70;
  display: grid;
  place-items: end center;
}

.sheet {
  background: var(--surface);
  border-radius: 18px 18px 0 0;
  width: min(680px, 100%);
  max-height: 82dvh;
  display: flex;
  flex-direction: column;
  animation: fadeUp 0.22s ease;
  padding-bottom: var(--safe-bottom);
}

header {
  display: flex;
  gap: 6px;
  padding: 12px 14px 0;
  align-items: center;
}

.tab {
  flex: 1;
  border: none;
  background: transparent;
  color: var(--text-dim);
  padding: 10px 0;
  border-radius: 10px 10px 0 0;
  cursor: pointer;
  font-size: 14px;
}

.tab.on {
  color: var(--text);
  background: var(--surface2);
  font-weight: 600;
}

.close {
  width: 32px;
  height: 32px;
  border: none;
  border-radius: 50%;
  background: var(--surface2);
  color: var(--text-dim);
  cursor: pointer;
}

.pane {
  padding: 16px 18px 20px;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.dim {
  color: var(--text-dim);
  font-size: 13px;
  margin: 0;
}

.ok {
  color: var(--ok);
  font-size: 14px;
  margin: 0;
}

.qr-box {
  align-self: center;
  background: #fff;
  border-radius: 14px;
  padding: 10px;
  min-width: 236px;
  min-height: 236px;
  display: grid;
  place-items: center;
}

.qr-tip {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  color: #333;
  font-size: 13px;
}

.spinner.big {
  width: 26px;
  height: 26px;
  border-color: #ccc;
  border-top-color: #5b8cff;
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

.row-item textarea {
  background: var(--bg2);
  border: 1px solid var(--line);
  border-radius: 10px;
  color: var(--text);
  padding: 8px 10px;
  font-size: 13px;
  resize: none;
}

.segs {
  display: flex;
  gap: 6px;
}

.segs.wide {
  justify-content: center;
}

.segs button {
  border: 1px solid var(--line);
  background: var(--bg2);
  color: var(--text-dim);
  border-radius: 999px;
  padding: 7px 14px;
  cursor: pointer;
  font-size: 13px;
}

.segs button.on {
  background: rgba(91, 140, 255, 0.2);
  border-color: rgba(91, 140, 255, 0.6);
  color: var(--text);
  font-weight: 600;
}

input[type='range'] {
  flex: 1;
  accent-color: var(--accent);
}

.vol {
  display: flex;
  align-items: center;
  gap: 12px;
  font-size: 14px;
}

.vol span {
  width: 64px;
}

.vol em {
  width: 40px;
  text-align: right;
  font-style: normal;
  color: var(--text-dim);
  font-size: 12px;
}
</style>
