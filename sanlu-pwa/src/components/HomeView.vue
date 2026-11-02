<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import {
  clearSlot,
  deleteCombo,
  isPlayable,
  resolvePreview,
  startCombo,
  startSession,
  store,
  swap,
  useRecent,
} from '../lib/store'
import type { Combo, SlotState } from '../lib/store'
import { formatCount } from '../lib/bili'
import { netStatus } from '../lib/net'
import SettingsDrawer from './SettingsDrawer.vue'

const drawer = ref<InstanceType<typeof SettingsDrawer> | null>(null)
const installEvent = ref<any>(null)
const showInstall = ref(false)

const canStart = computed(
  () =>
    !!store.a.input.trim() &&
    !!store.b.input.trim() &&
    store.a.state === 'ready' &&
    store.b.state === 'ready' &&
    isPlayable(store.a.liveStatus) &&
    isPlayable(store.b.liveStatus) &&
    !store.starting
)

function slotLabel(s: SlotState): string {
  if (s.state === 'loading') return '解析中…'
  if (s.state === 'error') return s.error || '解析失败'
  if (s.state === 'ready') {
    if (s.liveStatus === 1) return s.error || `直播中${s.online ? ` · ${formatCount(s.online)}人气` : ''}`
    if (s.liveStatus === 2) return '轮播中'
    return '未开播'
  }
  return ''
}

function liveText(status: number): string {
  return status === 1 ? '直播中' : status === 2 ? '轮播' : '未开播'
}

function openSettings(tab: 'account' | 'danmaku' | 'mix' | 'video' | 'relay' | 'about' = 'account') {
  drawer.value?.open(tab)
}

function onCombo(c: Combo) {
  void startCombo(c)
}

function install() {
  const e = installEvent.value
  if (!e) return
  e.prompt()
  e.userChoice?.finally(() => {
    installEvent.value = null
    showInstall.value = false
  })
}

function onInstallPrompt(e: Event) {
  e.preventDefault()
  installEvent.value = e
  showInstall.value = true
}

onMounted(() => {
  window.addEventListener('beforeinstallprompt', onInstallPrompt)
})
onBeforeUnmount(() => {
  window.removeEventListener('beforeinstallprompt', onInstallPrompt)
})
</script>

<template>
  <div class="home">
    <header class="top">
      <div class="brand">
        <div class="logo">叠</div>
        <div>
          <h1>叠播 <span class="ver">v1.3</span></h1>
          <p>A 的弹幕，盖到 B 的画面上</p>
        </div>
      </div>
      <div class="top-actions">
        <button v-if="showInstall" class="ghost small" @click="install">安装</button>
        <button class="ghost small" @click="openSettings('account')">
          {{ store.loggedIn ? store.account.name || '已登录' : '设置' }}
        </button>
      </div>
    </header>

    <main class="body scroll-y">
      <!-- 我的组合 -->
      <section v-if="store.combos.length" class="section">
        <div class="section-head">
          <h2>我的组合</h2>
          <span>点一下直接开播</span>
        </div>
        <div class="combo-row">
          <div v-for="(c, i) in store.combos" :key="`${c.aRoomId}-${c.bRoomId}`" class="combo">
            <button class="combo-main" @click="onCombo(c)">
              <div class="combo-line">
                <span class="badge a">A</span>
                <span class="combo-text">{{ c.aTitle || c.aInput }}</span>
              </div>
              <div class="combo-line">
                <span class="badge b">B</span>
                <span class="combo-text">{{ c.bTitle || c.bInput }}</span>
              </div>
              <small v-if="c.aAnchor || c.bAnchor">{{ c.aAnchor }} / {{ c.bAnchor }}</small>
            </button>
            <button class="combo-del" title="删除" @click="deleteCombo(i)">×</button>
          </div>
        </div>
      </section>

      <!-- 两个房间 -->
      <section class="inputs">
        <div class="card">
          <div class="card-head">
            <span class="badge a">A</span>
            <div class="card-title">
              <strong>主直播间</strong>
              <small>出弹幕和声音，发弹幕也发到这里</small>
            </div>
          </div>
          <div class="input-wrap">
            <input
              v-model="store.a.input"
              placeholder="房间号或 live.bilibili.com 链接"
              inputmode="numeric"
              @keydown.enter="resolvePreview('a')"
            />
            <button v-if="store.a.input" class="clear" title="清空" @click="clearSlot('a')">×</button>
            <button class="go" :disabled="store.a.state === 'loading'" @click="resolvePreview('a')">
              {{ store.a.state === 'loading' ? '…' : '检测' }}
            </button>
          </div>
          <div v-if="store.a.state === 'ready'" class="preview">
            <img
              v-if="store.a.cover"
              :src="store.a.cover"
              referrerpolicy="no-referrer"
              loading="lazy"
              alt=""
            />
            <div v-else class="cover-empty">LIVE</div>
            <div class="preview-info">
              <strong>{{ store.a.title }}</strong>
              <span class="meta">
                <em>{{ liveText(store.a.liveStatus) }}</em>
                <span v-if="store.a.uname">{{ store.a.uname }}</span>
                <span v-if="store.a.online">{{ formatCount(store.a.online) }}人气</span>
                <span v-if="store.a.areaName">{{ store.a.areaName }}</span>
              </span>
            </div>
          </div>
          <p
            class="slot-status"
            :class="{ err: store.a.state === 'error' || (store.a.error && store.a.state === 'ready'), ok: store.a.liveStatus === 1 && store.a.state === 'ready' }"
          >
            {{ slotLabel(store.a) }}
          </p>
        </div>

        <button class="swap" title="交换 A / B" @click="swap()">⇅ 交换</button>

        <div class="card">
          <div class="card-head">
            <span class="badge b">B</span>
            <div class="card-title">
              <strong>画面直播间</strong>
              <small>找比赛画面的那一路，只出画面</small>
            </div>
          </div>
          <div class="input-wrap">
            <input
              v-model="store.b.input"
              placeholder="房间号或 live.bilibili.com 链接"
              inputmode="numeric"
              @keydown.enter="resolvePreview('b')"
            />
            <button v-if="store.b.input" class="clear" title="清空" @click="clearSlot('b')">×</button>
            <button class="go" :disabled="store.b.state === 'loading'" @click="resolvePreview('b')">
              {{ store.b.state === 'loading' ? '…' : '检测' }}
            </button>
          </div>
          <div v-if="store.b.state === 'ready'" class="preview">
            <img
              v-if="store.b.cover"
              :src="store.b.cover"
              referrerpolicy="no-referrer"
              loading="lazy"
              alt=""
            />
            <div v-else class="cover-empty">LIVE</div>
            <div class="preview-info">
              <strong>{{ store.b.title }}</strong>
              <span class="meta">
                <em>{{ liveText(store.b.liveStatus) }}</em>
                <span v-if="store.b.uname">{{ store.b.uname }}</span>
                <span v-if="store.b.online">{{ formatCount(store.b.online) }}人气</span>
                <span v-if="store.b.areaName">{{ store.b.areaName }}</span>
              </span>
            </div>
          </div>
          <p
            class="slot-status"
            :class="{ err: store.b.state === 'error' || (store.b.error && store.b.state === 'ready'), ok: store.b.liveStatus === 1 && store.b.state === 'ready' }"
          >
            {{ slotLabel(store.b) }}
          </p>
        </div>
      </section>

      <p v-if="store.startError" class="error">{{ store.startError }}</p>
      <p v-if="netStatus.mode === 'unknown' && netStatus.lastError" class="error">
        接口不可用：{{ netStatus.lastError }}
      </p>

      <button class="primary start" :disabled="!canStart" @click="startSession">
        <span v-if="store.starting" class="spinner"></span>
        {{ store.starting ? '正在解析…' : '开始叠播' }}
      </button>
      <p v-if="store.a.state === 'ready' && store.b.state === 'ready' && !canStart && !store.starting" class="hint">
        有一个房间当前未开播，开播后即可叠播
      </p>

      <!-- 最近 -->
      <section v-if="store.recent.length" class="section">
        <div class="section-head">
          <h2>最近看过</h2>
          <span>点一下填到空的输入框</span>
        </div>
        <div class="recent-row">
          <button v-for="r in store.recent" :key="r.roomId" class="recent-item" @click="useRecent(r)">
            <span class="t">{{ r.title || r.input }}</span>
            <small>{{ r.uname || r.roomId }}</small>
          </button>
        </div>
      </section>

      <footer class="foot">
        <span class="mode" :class="netStatus.mode">
          {{
            netStatus.mode === 'direct'
              ? '直连 B 站'
              : netStatus.mode === 'relay'
                ? '中转模式'
                : netStatus.lastError
                  ? '接口异常'
                  : '准备中'
          }}
        </span>
        <span>看直播不用登录 · 发弹幕需要扫码</span>
      </footer>
    </main>

    <SettingsDrawer ref="drawer" />
  </div>
</template>

<style scoped>
.home {
  position: relative;
  min-height: 100%;
  display: flex;
  flex-direction: column;
  padding-top: var(--safe-top);
  background:
    radial-gradient(1200px 500px at 80% -10%, rgba(92, 140, 255, 0.16), transparent 60%),
    radial-gradient(900px 420px at 0% 110%, rgba(255, 184, 77, 0.1), transparent 60%),
    var(--bg);
}

.top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: calc(18px + var(--safe-top)) 22px 10px;
}

.brand {
  display: flex;
  align-items: center;
  gap: 12px;
}

.logo {
  width: 44px;
  height: 44px;
  border-radius: 12px;
  background: linear-gradient(135deg, #5b8cff, #7c5bff);
  display: grid;
  place-items: center;
  font-size: 22px;
  font-weight: 700;
  color: #fff;
  box-shadow: 0 8px 24px rgba(92, 140, 255, 0.35);
}

.top h1 {
  margin: 0;
  font-size: 22px;
}

.ver {
  font-size: 10px;
  font-weight: 500;
  color: var(--text-dim);
  vertical-align: middle;
}

.top p {
  margin: 2px 0 0;
  color: var(--text-dim);
  font-size: 12px;
}

.top-actions {
  display: flex;
  gap: 8px;
}

.ghost.small {
  padding: 8px 13px;
  font-size: 13px;
}

.body {
  flex: 1;
  width: 100%;
  max-width: 980px;
  margin: 0 auto;
  padding: 8px 22px calc(32px + var(--safe-bottom));
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.section-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  margin-bottom: 10px;
}

.section-head h2 {
  font-size: 14px;
  color: var(--text-dim);
  margin: 0;
  font-weight: 600;
}

.section-head span {
  font-size: 11px;
  color: var(--text-dim);
  opacity: 0.7;
}

/* 组合 */
.combo-row {
  display: flex;
  gap: 10px;
  overflow-x: auto;
  padding-bottom: 4px;
}

.combo {
  position: relative;
  flex: 0 0 auto;
  display: flex;
  align-items: center;
  background: var(--surface);
  border: 1px solid var(--line);
  border-radius: 12px;
}

.combo-main {
  display: flex;
  flex-direction: column;
  gap: 5px;
  background: none;
  border: none;
  color: var(--text);
  padding: 11px 38px 11px 12px;
  cursor: pointer;
  min-width: 210px;
  max-width: 300px;
  text-align: left;
}

.combo-main:active {
  transform: scale(0.98);
}

.combo-line {
  display: flex;
  align-items: center;
  gap: 7px;
}

.combo-text {
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  font-size: 13px;
}

.combo-main small {
  color: var(--text-dim);
  font-size: 10px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.combo-del {
  position: absolute;
  top: -7px;
  right: -7px;
  width: 22px;
  height: 22px;
  border-radius: 50%;
  border: none;
  background: var(--surface2);
  color: var(--text-dim);
  cursor: pointer;
  font-size: 13px;
  line-height: 1;
}

/* 输入卡 */
.inputs {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.card {
  background: var(--surface);
  border: 1px solid var(--line);
  border-radius: var(--radius);
  padding: 14px 16px;
}

.card-head {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 10px;
}

.card-title {
  display: flex;
  flex-direction: column;
}

.card-title small {
  color: var(--text-dim);
  font-size: 12px;
}

.badge {
  width: 30px;
  height: 30px;
  border-radius: 50%;
  display: grid;
  place-items: center;
  font-weight: 700;
  font-size: 13px;
  color: #fff;
  flex: 0 0 auto;
}

.badge.a {
  background: linear-gradient(135deg, #ffb84d, #ff8a4d);
}

.badge.b {
  background: linear-gradient(135deg, #5b8cff, #7c5bff);
}

.input-wrap {
  display: flex;
  align-items: center;
  gap: 8px;
}

.input-wrap input {
  flex: 1;
  min-width: 0;
  background: var(--bg2);
  border: 1px solid var(--line);
  border-radius: 10px;
  color: var(--text);
  padding: 12px 14px;
  font-size: 16px;
  outline: none;
}

.input-wrap input:focus {
  border-color: rgba(91, 140, 255, 0.55);
}

.clear {
  width: 32px;
  height: 32px;
  border: none;
  border-radius: 50%;
  background: var(--surface2);
  color: var(--text-dim);
  cursor: pointer;
  font-size: 16px;
  flex: 0 0 auto;
}

.go {
  height: 38px;
  border: 1px solid var(--line);
  border-radius: 10px;
  background: var(--surface2);
  color: var(--text);
  padding: 0 14px;
  cursor: pointer;
  font-size: 13px;
  flex: 0 0 auto;
}

.go:disabled {
  opacity: 0.5;
}

/* 房间预览 */
.preview {
  display: flex;
  gap: 10px;
  margin-top: 10px;
  padding: 8px;
  background: var(--bg2);
  border: 1px solid var(--line);
  border-radius: 12px;
}

.preview img,
.cover-empty {
  width: 92px;
  aspect-ratio: 16 / 9;
  border-radius: 8px;
  object-fit: cover;
  background: var(--surface2);
  flex: 0 0 auto;
}

.cover-empty {
  display: grid;
  place-items: center;
  color: var(--text-dim);
  font-size: 11px;
  letter-spacing: 2px;
}

.preview-info {
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 5px;
  justify-content: center;
}

.preview-info strong {
  font-size: 13px;
  overflow: hidden;
  text-overflow: ellipsis;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
}

.meta {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  font-size: 11px;
  color: var(--text-dim);
}

.meta em {
  font-style: normal;
  color: var(--ok);
}

.slot-status {
  margin: 8px 0 0;
  font-size: 12px;
  color: var(--text-dim);
  min-height: 16px;
}

.slot-status.err {
  color: var(--danger);
}

.slot-status.ok {
  color: var(--ok);
}

.swap {
  align-self: center;
  background: none;
  border: 1px dashed var(--line);
  color: var(--text-dim);
  border-radius: 999px;
  padding: 6px 16px;
  cursor: pointer;
}

.error {
  color: var(--danger);
  font-size: 13px;
  margin: 0;
  text-align: center;
}

.hint {
  color: var(--text-dim);
  font-size: 12px;
  margin: 0;
  text-align: center;
}

.start {
  width: 100%;
}

/* 最近 */
.recent-row {
  display: flex;
  gap: 10px;
  overflow-x: auto;
  padding-bottom: 4px;
}

.recent-item {
  flex: 0 0 auto;
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 3px;
  background: var(--surface);
  border: 1px solid var(--line);
  color: var(--text);
  border-radius: 12px;
  padding: 10px 14px;
  cursor: pointer;
  max-width: 200px;
}

.recent-item .t {
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  max-width: 176px;
  font-size: 13px;
}

.recent-item small {
  color: var(--text-dim);
  font-size: 11px;
}

.foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  color: var(--text-dim);
  font-size: 11px;
  padding-top: 6px;
}

.mode {
  border: 1px solid var(--line);
  border-radius: 999px;
  padding: 3px 9px;
  white-space: nowrap;
}

.mode.direct {
  color: var(--ok);
  border-color: rgba(74, 222, 128, 0.35);
}

.mode.relay {
  color: var(--accent2);
  border-color: rgba(255, 184, 77, 0.35);
}

.mode.none {
  color: var(--danger);
  border-color: rgba(255, 90, 95, 0.4);
}

/* PC 大屏 */
@media (min-width: 1024px) {
  .inputs {
    display: grid;
    grid-template-columns: 1fr auto 1fr;
    align-items: start;
  }

  .swap {
    margin-top: 44px;
  }

  .start {
    max-width: 420px;
    align-self: center;
  }
}
</style>