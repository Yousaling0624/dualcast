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
import AppIcon from './AppIcon.vue'

const drawer = ref<InstanceType<typeof SettingsDrawer> | null>(null)
const installEvent = ref<any>(null)
const showInstall = ref(false)

const rooms = computed(() => [
  {
    key: 'a' as const,
    letter: 'A',
    tone: 'coral',
    title: '主直播间',
    desc: '出弹幕和声音，发弹幕也发到这里',
    slot: store.a,
  },
  {
    key: 'b' as const,
    letter: 'B',
    tone: 'peri',
    title: '画面直播间',
    desc: '找比赛画面的那一路，只出画面',
    slot: store.b,
  },
])

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

const modeText = computed(() =>
  netStatus.mode === 'direct'
    ? '直连 B 站'
    : netStatus.mode === 'relay'
      ? '中转模式'
      : netStatus.lastError
        ? '接口异常'
        : '准备中'
)

function slotLabel(s: SlotState): string {
  if (s.state === 'loading') return '正在识别房间…'
  if (s.state === 'error') return s.error || '解析失败'
  if (s.state === 'ready') {
    if (s.liveStatus === 1) return s.error || `直播中${s.online ? ` · ${formatCount(s.online)} 人气` : ''}`
    if (s.liveStatus === 2) return '轮播中'
    return '未开播'
  }
  return ''
}

function slotStatusTone(s: SlotState): string {
  if (s.state === 'error' || (s.error && s.state === 'ready')) return 'danger'
  if (s.liveStatus === 1 && s.state === 'ready') return 'ok'
  return 'muted'
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
    <div class="ambient" aria-hidden="true">
      <span class="halo coral"></span>
      <span class="halo sky"></span>
      <span class="halo gold"></span>
    </div>

    <header class="top">
      <div class="brand">
        <span class="logo">叠</span>
        <div class="brand-text">
          <div class="brand-line">
            <h1>叠播</h1>
            <span class="ver">v1.7.4</span>
          </div>
          <p>DualCast · 两个直播间一起看</p>
        </div>
      </div>

      <div class="top-actions">
        <button v-if="showInstall" class="soft-btn" @click="install">
          <AppIcon name="download" :size="16" />
          安装
        </button>
        <button class="account-btn" @click="openSettings('account')">
          <img
            v-if="store.loggedIn && store.account.avatar"
            :src="store.account.avatar"
            referrerpolicy="no-referrer"
            alt=""
          />
          <span v-else class="account-icon"><AppIcon name="user" :size="17" /></span>
          <span class="account-name">
            {{ store.loggedIn ? store.account.name || '已登录' : '登录' }}
          </span>
        </button>
      </div>
    </header>

    <main class="body scroll-y">
      <section class="hero">
        <div class="hero-copy">
          <span class="eyebrow"><i></i> A 解说 + B 画面</span>
          <h2 class="display">
            把解说，<br />
            叠到<em>比赛画面</em>上。
          </h2>
          <p class="hero-sub">
            一路出声与弹幕，一路出画面。带 SC 悬浮窗、混音延迟校准，以及斗鱼式的
            +1 和回复。
          </p>
          <div class="hero-chips">
            <span><AppIcon name="sc" :size="13" />SC 悬浮窗</span>
            <span><AppIcon name="tune" :size="13" />延迟校准</span>
            <span><AppIcon name="chat" :size="13" />弹幕互动</span>
          </div>
        </div>

        <div class="hero-art" aria-hidden="true">
          <div class="art-card art-b">
            <span class="art-tag">B · 画面</span>
            <div class="art-pitch">
              <i></i><i></i><i></i><i></i><i></i><i></i>
            </div>
            <span class="art-score">LIVE</span>
          </div>
          <div class="art-card art-a">
            <span class="art-tag">A · 解说</span>
            <div class="art-wave">
              <i></i><i></i><i></i><i></i><i></i><i></i><i></i><i></i>
            </div>
            <span class="art-live"><i></i>语音</span>
          </div>
        </div>
      </section>

      <section v-if="store.combos.length" class="combos">
        <div class="section-head">
          <h3>我的组合</h3>
          <span>点一下直接开播</span>
        </div>
        <div class="combo-scroll">
          <div
            v-for="(c, i) in store.combos"
            :key="`${c.aRoomId}-${c.bRoomId}`"
            class="combo"
          >
            <button class="combo-main" @click="onCombo(c)">
              <div class="combo-top">
                <span class="combo-pair"><b>A</b><i></i><b>B</b></span>
                <span class="combo-time">{{ new Date(c.updatedAt).toLocaleDateString('zh-CN', { month: 'numeric', day: 'numeric' }) }}</span>
              </div>
              <span class="combo-title">{{ c.aTitle || c.aInput }}</span>
              <span class="combo-title dim">{{ c.bTitle || c.bInput }}</span>
              <small v-if="c.aAnchor || c.bAnchor">{{ c.aAnchor }} / {{ c.bAnchor }}</small>
            </button>
            <button class="combo-del" title="删除这个组合" @click="deleteCombo(i)">
              <AppIcon name="close" :size="13" />
            </button>
          </div>
        </div>
      </section>

      <section class="stage">
        <div class="section-head">
          <h3>选择两个直播间</h3>
          <span>看直播不用登录</span>
        </div>

        <div class="room-grid">
          <article
            v-for="room in rooms"
            :key="room.key"
            class="room-card"
            :class="[room.tone, { ready: room.slot.state === 'ready' }]"
          >
            <div class="room-head">
              <span class="letter" :class="room.tone">{{ room.letter }}</span>
              <div class="room-title">
                <strong>{{ room.title }}</strong>
                <small>{{ room.desc }}</small>
              </div>
              <span v-if="room.slot.state === 'ready'" class="live-state" :class="{ on: room.slot.liveStatus === 1 }">
                {{ liveText(room.slot.liveStatus) }}
              </span>
            </div>

            <div class="input-wrap">
              <AppIcon name="link" :size="17" class="input-icon" />
              <input
                v-model="room.slot.input"
                placeholder="房间号或 live.bilibili.com 链接"
                inputmode="numeric"
                @keydown.enter="resolvePreview(room.key)"
              />
              <button
                v-if="room.slot.input"
                class="clear-btn"
                title="清空"
                @click="clearSlot(room.key)"
              >
                <AppIcon name="close" :size="14" />
              </button>
              <button
                class="detect-btn"
                :disabled="room.slot.state === 'loading'"
                @click="resolvePreview(room.key)"
              >
                <span v-if="room.slot.state === 'loading'" class="spinner"></span>
                <template v-else>检测</template>
              </button>
            </div>

            <Transition name="preview">
              <div v-if="room.slot.state === 'ready'" class="preview">
                <div class="cover">
                  <img
                    v-if="room.slot.cover"
                    :src="room.slot.cover"
                    referrerpolicy="no-referrer"
                    loading="lazy"
                    alt=""
                  />
                  <span v-else class="cover-empty">LIVE</span>
                  <span class="cover-live"><i></i>{{ liveText(room.slot.liveStatus) }}</span>
                </div>
                <div class="preview-info">
                  <strong>{{ room.slot.title || '未命名直播间' }}</strong>
                  <span class="preview-meta">
                    <template v-if="room.slot.uname">{{ room.slot.uname }}</template>
                    <template v-if="room.slot.online"> · {{ formatCount(room.slot.online) }} 人气</template>
                    <template v-if="room.slot.areaName"> · {{ room.slot.areaName }}</template>
                  </span>
                </div>
              </div>
            </Transition>

            <p v-if="slotLabel(room.slot)" class="slot-status" :class="slotStatusTone(room.slot)">
              <span class="status-dot"></span>
              {{ slotLabel(room.slot) }}
            </p>
          </article>
        </div>

        <div class="swap-row">
          <span class="hairline"></span>
          <button class="swap-btn" :disabled="store.swapping" title="交换 A / B" @click="swap()">
            <AppIcon name="swap" :size="17" />
            交换 A / B
          </button>
          <span class="hairline"></span>
        </div>

        <p v-if="store.startError" class="notice danger">
          <AppIcon name="warning" :size="16" />
          <span>{{ store.startError }}</span>
        </p>
        <p v-if="netStatus.mode === 'unknown' && netStatus.lastError" class="notice">
          <AppIcon name="warning" :size="16" />
          <span>接口不可用：{{ netStatus.lastError }}</span>
        </p>

        <button class="primary start" :disabled="!canStart" @click="startSession">
          <span v-if="store.starting" class="spinner"></span>
          <AppIcon v-else name="sparkles" :size="19" />
          {{ store.starting ? '正在解析两路直播…' : '开始叠播' }}
        </button>
        <p
          v-if="store.a.state === 'ready' && store.b.state === 'ready' && !canStart && !store.starting"
          class="hint"
        >
          有一个房间当前未开播，开播后即可叠播
        </p>
      </section>

      <section v-if="store.recent.length" class="recent-section">
        <div class="section-head">
          <h3>最近看过</h3>
          <span>点一下填到空的输入框</span>
        </div>
        <div class="recent-row">
          <button v-for="r in store.recent" :key="r.roomId" class="recent-item" @click="useRecent(r)">
            <span class="recent-avatar">{{ (r.title || r.uname || '?').slice(0, 1) }}</span>
            <span class="recent-copy">
              <span class="t">{{ r.title || r.input }}</span>
              <small>{{ r.uname || `房间 ${r.roomId}` }}</small>
            </span>
          </button>
        </div>
      </section>

      <footer class="foot">
        <span class="mode" :class="netStatus.mode">
          <i></i>
          {{ modeText }}
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
  overflow: hidden;
  padding-top: var(--safe-top);
  background:
    radial-gradient(900px 500px at 88% -12%, rgba(217, 119, 87, 0.15), transparent 62%),
    radial-gradient(760px 480px at -8% 42%, rgba(102, 121, 207, 0.11), transparent 64%),
    linear-gradient(180deg, #f7f2ea 0%, var(--bg) 40%, #f0e9dc 100%);
}

.ambient {
  position: absolute;
  inset: 0;
  pointer-events: none;
  overflow: hidden;
}

.halo {
  position: absolute;
  border-radius: 50%;
  filter: blur(60px);
  opacity: 0.5;
}

.halo.coral {
  width: 360px;
  height: 360px;
  right: -120px;
  top: -80px;
  background: rgba(217, 119, 87, 0.28);
  animation: float 14s ease-in-out infinite;
}

.halo.sky {
  width: 300px;
  height: 300px;
  left: -130px;
  top: 38%;
  background: rgba(102, 121, 207, 0.2);
  animation: float 18s ease-in-out infinite reverse;
}

.halo.gold {
  width: 240px;
  height: 240px;
  right: 12%;
  bottom: -100px;
  background: rgba(185, 132, 46, 0.15);
  animation: float 16s ease-in-out infinite;
}

.top {
  position: relative;
  z-index: 2;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: calc(20px + var(--safe-top)) clamp(18px, 5vw, 42px) 10px;
}

.brand {
  display: flex;
  align-items: center;
  gap: 12px;
}

.logo {
  width: 46px;
  height: 46px;
  border-radius: 16px;
  background: linear-gradient(145deg, #e28a6d, var(--accent) 55%, #c46545);
  display: grid;
  place-items: center;
  font-family: var(--font-display);
  font-size: 24px;
  font-weight: 700;
  color: #fff9f4;
  box-shadow:
    0 10px 24px rgba(184, 93, 63, 0.28),
    inset 0 1px 0 rgba(255, 255, 255, 0.35);
}

.brand-line {
  display: flex;
  align-items: baseline;
  gap: 8px;
}

.top h1 {
  margin: 0;
  font-family: var(--font-display);
  font-size: 24px;
  font-weight: 650;
  letter-spacing: -0.01em;
}

.ver {
  font-size: 10px;
  letter-spacing: 0.06em;
  color: var(--text-faint);
  border: 1px solid var(--line);
  border-radius: 999px;
  padding: 1px 7px;
}

.brand-text p {
  margin: 2px 0 0;
  color: var(--text-dim);
  font-size: 11.5px;
  letter-spacing: 0.01em;
}

.top-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}

.soft-btn {
  display: flex;
  align-items: center;
  gap: 5px;
  height: 36px;
  padding: 0 13px;
  border: 1px solid var(--line);
  border-radius: 999px;
  background: var(--surface);
  color: var(--text);
  font-size: 12.5px;
  cursor: pointer;
}

.account-btn {
  display: flex;
  align-items: center;
  gap: 7px;
  height: 36px;
  padding: 3px 12px 3px 4px;
  border: 1px solid var(--line);
  border-radius: 999px;
  background: var(--surface);
  color: var(--text);
  cursor: pointer;
  max-width: 180px;
}

.account-btn img,
.account-icon {
  width: 28px;
  height: 28px;
  border-radius: 50%;
  object-fit: cover;
  display: grid;
  place-items: center;
  background: var(--surface-2);
  color: var(--text-dim);
  flex: 0 0 auto;
}

.account-name {
  font-size: 12.5px;
  max-width: 100px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.body {
  position: relative;
  z-index: 1;
  flex: 1;
  width: 100%;
  max-width: 1000px;
  margin: 0 auto;
  padding: 6px clamp(18px, 5vw, 42px) calc(34px + var(--safe-bottom));
  display: flex;
  flex-direction: column;
  gap: 26px;
}

/* ---------- Hero ---------- */

.hero {
  display: flex;
  align-items: center;
  gap: 24px;
  padding: 22px 4px 4px;
}

.hero-copy {
  flex: 1;
  min-width: 0;
  animation: fadeUp 0.45s ease both;
}

.eyebrow {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  font-size: 11px;
  font-weight: 650;
  letter-spacing: 0.11em;
  text-transform: uppercase;
  color: var(--accent-strong);
  background: var(--accent-soft);
  border: 1px solid rgba(217, 119, 87, 0.2);
  border-radius: 999px;
  padding: 5px 11px;
}

.eyebrow i {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--accent);
  box-shadow: 0 0 0 4px rgba(217, 119, 87, 0.16);
}

.display {
  margin: 15px 0 10px;
  font-family: var(--font-display);
  font-size: clamp(34px, 5.4vw, 55px);
  line-height: 1.12;
  font-weight: 500;
  letter-spacing: -0.025em;
  color: var(--text);
}

.display em {
  font-style: italic;
  color: var(--accent-strong);
  position: relative;
  white-space: nowrap;
}

.display em::after {
  content: '';
  position: absolute;
  left: 2%;
  right: 2%;
  bottom: 0.06em;
  height: 0.12em;
  border-radius: 999px;
  background: linear-gradient(90deg, rgba(217, 119, 87, 0.45), rgba(185, 132, 46, 0.2));
}

.hero-sub {
  max-width: 480px;
  margin: 0;
  color: var(--text-dim);
  font-size: 14px;
  line-height: 1.7;
}

.hero-chips {
  display: flex;
  flex-wrap: wrap;
  gap: 7px;
  margin-top: 16px;
}

.hero-chips span {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  font-size: 11.5px;
  color: var(--text-dim);
  background: rgba(255, 253, 249, 0.75);
  border: 1px solid var(--line);
  border-radius: 999px;
  padding: 5px 10px;
  backdrop-filter: blur(8px);
}

.hero-art {
  position: relative;
  width: 300px;
  height: 190px;
  flex: 0 0 auto;
  animation: fadeUp 0.55s 0.08s ease both;
}

.art-card {
  position: absolute;
  border-radius: 22px;
  padding: 11px 13px;
  border: 1px solid var(--line);
  box-shadow: var(--shadow-card);
}

.art-b {
  right: 0;
  top: 8px;
  width: 218px;
  height: 140px;
  background: linear-gradient(150deg, #2e3447, #171b26 65%);
  transform: rotate(4deg);
  color: #f4efe6;
  overflow: hidden;
}

.art-a {
  left: 0;
  bottom: 0;
  width: 196px;
  height: 122px;
  background: linear-gradient(150deg, #fff8ee, #f6e7d3);
  transform: rotate(-3deg);
  z-index: 2;
}

.art-tag {
  display: inline-block;
  font-size: 9.5px;
  font-weight: 700;
  letter-spacing: 0.08em;
  padding: 2px 7px;
  border-radius: 999px;
  background: rgba(0, 0, 0, 0.22);
}

.art-a .art-tag {
  background: var(--accent-soft);
  color: var(--accent-strong);
}

.art-pitch {
  position: absolute;
  left: 14px;
  right: 14px;
  bottom: 30px;
  display: flex;
  flex-direction: column;
  gap: 4px;
  opacity: 0.35;
}

.art-pitch i {
  display: block;
  height: 2px;
  border-radius: 2px;
  background: #c9d2ef;
  width: 100%;
}

.art-pitch i:nth-child(2) {
  width: 62%;
}

.art-pitch i:nth-child(4) {
  width: 78%;
}

.art-score {
  position: absolute;
  right: 13px;
  bottom: 10px;
  font-size: 9px;
  letter-spacing: 0.1em;
  opacity: 0.5;
}

.art-wave {
  position: absolute;
  left: 16px;
  right: 16px;
  bottom: 32px;
  height: 38px;
  display: flex;
  align-items: center;
  gap: 4px;
}

.art-wave i {
  flex: 1;
  border-radius: 999px;
  background: linear-gradient(180deg, #e3a087, var(--accent));
  animation: wave 1.6s ease-in-out infinite;
}

.art-wave i:nth-child(1) { height: 30%; animation-delay: -0.1s; }
.art-wave i:nth-child(2) { height: 62%; animation-delay: -0.5s; }
.art-wave i:nth-child(3) { height: 92%; animation-delay: -0.9s; }
.art-wave i:nth-child(4) { height: 48%; animation-delay: -0.3s; }
.art-wave i:nth-child(5) { height: 76%; animation-delay: -0.7s; }
.art-wave i:nth-child(6) { height: 36%; animation-delay: -1.1s; }
.art-wave i:nth-child(7) { height: 64%; animation-delay: -0.4s; }
.art-wave i:nth-child(8) { height: 26%; animation-delay: -0.8s; }

.art-live {
  position: absolute;
  right: 13px;
  bottom: 9px;
  display: flex;
  align-items: center;
  gap: 4px;
  color: var(--accent-strong);
  font-size: 9px;
  font-weight: 650;
  letter-spacing: 0.05em;
}

.art-live i {
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background: var(--accent);
}

@keyframes wave {
  0%,
  100% {
    transform: scaleY(0.75);
    opacity: 0.7;
  }
  50% {
    transform: scaleY(1);
    opacity: 1;
  }
}

/* ---------- Section ---------- */

.section-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 10px;
  margin-bottom: 11px;
}

.section-head h3 {
  margin: 0;
  font-size: 13px;
  font-weight: 650;
  letter-spacing: 0.01em;
}

.section-head span {
  font-size: 11px;
  color: var(--text-faint);
}

/* ---------- Combos ---------- */

.combo-scroll {
  display: flex;
  gap: 10px;
  overflow-x: auto;
  padding: 4px 2px 8px;
  scrollbar-width: none;
}

.combo-scroll::-webkit-scrollbar {
  display: none;
}

.combo {
  position: relative;
  flex: 0 0 auto;
  border-radius: 20px;
  background: var(--surface);
  border: 1px solid var(--line);
  box-shadow: var(--shadow-card);
  transition: transform 0.16s ease;
}

.combo:hover {
  transform: translateY(-2px);
}

.combo-main {
  display: flex;
  flex-direction: column;
  align-items: stretch;
  gap: 4px;
  background: none;
  border: none;
  color: var(--text);
  padding: 13px 15px 12px;
  cursor: pointer;
  width: 244px;
  text-align: left;
}

.combo-top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 3px;
}

.combo-pair {
  display: flex;
  align-items: center;
  gap: 3px;
}

.combo-pair b {
  width: 17px;
  height: 17px;
  border-radius: 6px;
  display: grid;
  place-items: center;
  font-size: 9px;
  color: #fff;
}

.combo-pair b:first-child {
  background: var(--accent);
}

.combo-pair b:last-child {
  background: var(--accent-2);
}

.combo-pair i {
  width: 10px;
  height: 1px;
  background: var(--line-strong);
}

.combo-time {
  font-size: 9.5px;
  color: var(--text-faint);
}

.combo-title {
  font-size: 12.5px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.combo-title.dim {
  color: var(--text-dim);
}

.combo-main small {
  margin-top: 3px;
  color: var(--text-faint);
  font-size: 10px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.combo-del {
  position: absolute;
  top: 8px;
  right: 7px;
  width: 22px;
  height: 22px;
  border: none;
  border-radius: 50%;
  background: transparent;
  color: var(--text-faint);
  display: grid;
  place-items: center;
  cursor: pointer;
  opacity: 0;
  transition: opacity 0.15s ease, background 0.15s ease;
}

.combo:hover .combo-del,
.combo-del:focus-visible {
  opacity: 1;
}

.combo-del:hover {
  background: var(--surface-2);
  color: var(--danger);
}

@media (hover: none) {
  .combo-del {
    opacity: 1;
  }
}

/* ---------- Room stage ---------- */

.stage {
  border-radius: var(--radius-xl);
  background: rgba(255, 253, 249, 0.62);
  border: 1px solid rgba(255, 255, 255, 0.66);
  box-shadow: 0 24px 60px rgba(74, 55, 34, 0.08);
  padding: 18px;
  backdrop-filter: blur(18px);
}

.room-grid {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.room-card {
  position: relative;
  border-radius: var(--radius-lg);
  background: var(--surface);
  border: 1px solid var(--line);
  padding: 15px 16px 13px;
  transition:
    border-color 0.2s ease,
    box-shadow 0.2s ease;
  overflow: hidden;
}

.room-card::before {
  content: '';
  position: absolute;
  left: 0;
  top: 16px;
  bottom: 16px;
  width: 3px;
  border-radius: 0 3px 3px 0;
  background: var(--accent);
  opacity: 0.75;
}

.room-card.peri::before {
  background: var(--accent-2);
}

.room-card.ready {
  border-color: rgba(217, 119, 87, 0.22);
  box-shadow: 0 10px 30px rgba(74, 55, 34, 0.055);
}

.room-head {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 11px;
}

.letter {
  width: 34px;
  height: 34px;
  border-radius: 12px;
  display: grid;
  place-items: center;
  font-family: var(--font-display);
  font-size: 16px;
  font-weight: 700;
  color: #fff9f4;
  background: linear-gradient(140deg, #e28a6d, var(--accent));
  box-shadow: 0 6px 16px rgba(217, 119, 87, 0.24);
  flex: 0 0 auto;
}

.letter.peri {
  background: linear-gradient(140deg, #8b99df, var(--accent-2));
  box-shadow: 0 6px 16px rgba(102, 121, 207, 0.24);
}

.room-title {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.room-title strong {
  font-size: 14px;
  font-weight: 650;
}

.room-title small {
  color: var(--text-dim);
  font-size: 11.5px;
}

.live-state {
  flex: 0 0 auto;
  font-size: 10px;
  font-weight: 600;
  color: var(--text-faint);
  background: var(--surface-2);
  border-radius: 999px;
  padding: 3px 9px;
}

.live-state.on {
  color: var(--accent-strong);
  background: var(--accent-soft);
}

.input-wrap {
  display: flex;
  align-items: center;
  gap: 7px;
  background: var(--bg-soft);
  border: 1px solid var(--line);
  border-radius: 16px;
  padding: 4px 5px 4px 12px;
  transition:
    border-color 0.16s ease,
    background 0.16s ease,
    box-shadow 0.16s ease;
}

.input-wrap:focus-within {
  background: var(--surface);
  border-color: rgba(217, 119, 87, 0.5);
  box-shadow: 0 0 0 4px var(--accent-soft);
}

.room-card.peri .input-wrap:focus-within {
  border-color: rgba(102, 121, 207, 0.45);
  box-shadow: 0 0 0 4px var(--accent-2-soft);
}

.input-icon {
  color: var(--text-faint);
  flex: 0 0 auto;
  margin-top: 1px;
}

.input-wrap input {
  flex: 1;
  min-width: 0;
  background: transparent;
  border: none;
  color: var(--text);
  padding: 9px 0;
  font-size: 15px;
  outline: none;
}

.input-wrap input::placeholder {
  color: var(--text-faint);
  font-size: 13px;
}

.clear-btn {
  width: 27px;
  height: 27px;
  border: none;
  border-radius: 50%;
  background: var(--surface-2);
  color: var(--text-dim);
  display: grid;
  place-items: center;
  cursor: pointer;
  flex: 0 0 auto;
}

.detect-btn {
  height: 36px;
  min-width: 58px;
  border: none;
  border-radius: 12px;
  background: var(--surface-2);
  color: var(--text);
  padding: 0 13px;
  font-size: 12.5px;
  font-weight: 600;
  cursor: pointer;
  flex: 0 0 auto;
  display: grid;
  place-items: center;
  transition: background 0.15s ease;
}

.detect-btn:hover:not(:disabled) {
  background: var(--surface-3);
}

.detect-btn:disabled {
  opacity: 0.55;
  cursor: progress;
}

.preview {
  display: flex;
  gap: 11px;
  margin-top: 10px;
  padding: 7px;
  background: var(--bg-soft);
  border-radius: 15px;
}

.cover {
  position: relative;
  width: 108px;
  aspect-ratio: 16 / 9;
  border-radius: 10px;
  overflow: hidden;
  background: var(--surface-2);
  flex: 0 0 auto;
}

.cover img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.cover-empty {
  width: 100%;
  height: 100%;
  display: grid;
  place-items: center;
  font-size: 10px;
  letter-spacing: 0.16em;
  color: var(--text-faint);
}

.cover-live {
  position: absolute;
  left: 5px;
  bottom: 5px;
  display: flex;
  align-items: center;
  gap: 3px;
  font-size: 8.5px;
  font-weight: 650;
  color: #fff;
  background: rgba(20, 16, 12, 0.55);
  border-radius: 999px;
  padding: 2px 6px;
  backdrop-filter: blur(6px);
}

.cover-live i {
  width: 4px;
  height: 4px;
  border-radius: 50%;
  background: #ff8d6b;
}

.preview-info {
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 4px;
  justify-content: center;
}

.preview-info strong {
  font-size: 12.5px;
  line-height: 1.4;
  overflow: hidden;
  text-overflow: ellipsis;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
}

.preview-meta {
  font-size: 10.5px;
  color: var(--text-faint);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.slot-status {
  display: flex;
  align-items: center;
  gap: 6px;
  margin: 8px 2px 0;
  font-size: 11.5px;
  color: var(--text-dim);
  min-height: 16px;
}

.status-dot {
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background: currentColor;
  flex: 0 0 auto;
}

.slot-status.ok {
  color: var(--ok);
}

.slot-status.danger {
  color: var(--danger);
}

/* swap */
.swap-row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 10px 2px 2px;
}

.hairline {
  height: 1px;
  flex: 1;
  background: linear-gradient(90deg, transparent, var(--line-strong), transparent);
}

.swap-btn {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  border: 1px solid var(--line-strong);
  border-radius: 999px;
  background: var(--surface);
  color: var(--text-dim);
  padding: 7px 14px;
  font-size: 12px;
  cursor: pointer;
  flex: 0 0 auto;
  transition:
    color 0.15s ease,
    border-color 0.15s ease,
    transform 0.15s ease;
}

.swap-btn:hover {
  color: var(--text);
  border-color: var(--text-faint);
}

.swap-btn:active {
  transform: scale(0.97);
}

.swap-btn:disabled {
  opacity: 0.5;
}

.notice {
  display: flex;
  align-items: flex-start;
  gap: 7px;
  margin: 12px 0 0;
  padding: 10px 12px;
  border-radius: 14px;
  background: var(--surface-2);
  color: var(--text-dim);
  font-size: 12px;
  line-height: 1.5;
}

.notice.danger {
  background: var(--danger-soft);
  color: var(--danger);
}

.notice :deep(.icon) {
  margin-top: 1px;
  flex: 0 0 auto;
}

.start {
  width: 100%;
  margin-top: 14px;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
}

.start:disabled {
  box-shadow: none;
}

.hint {
  color: var(--text-faint);
  font-size: 11.5px;
  margin: 9px 0 0;
  text-align: center;
}

/* ---------- Recent ---------- */

.recent-row {
  display: flex;
  gap: 9px;
  overflow-x: auto;
  padding-bottom: 4px;
  scrollbar-width: none;
}

.recent-row::-webkit-scrollbar {
  display: none;
}

.recent-item {
  flex: 0 0 auto;
  display: flex;
  align-items: center;
  gap: 9px;
  background: rgba(255, 253, 249, 0.7);
  border: 1px solid var(--line);
  color: var(--text);
  border-radius: 16px;
  padding: 8px 13px 8px 8px;
  cursor: pointer;
  max-width: 220px;
  text-align: left;
  transition:
    background 0.15s ease,
    transform 0.15s ease;
}

.recent-item:hover {
  background: var(--surface);
  transform: translateY(-1px);
}

.recent-avatar {
  width: 30px;
  height: 30px;
  border-radius: 10px;
  background: var(--surface-3);
  color: var(--text-dim);
  display: grid;
  place-items: center;
  font-size: 12px;
  font-weight: 650;
  flex: 0 0 auto;
}

.recent-copy {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}

.recent-item .t {
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  max-width: 164px;
  font-size: 12px;
}

.recent-item small {
  color: var(--text-faint);
  font-size: 10px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  max-width: 164px;
}

/* ---------- Footer ---------- */

.foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  color: var(--text-faint);
  font-size: 10.5px;
  padding: 0 2px;
}

.mode {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  border: 1px solid var(--line);
  border-radius: 999px;
  padding: 3px 9px;
  white-space: nowrap;
  background: rgba(255, 253, 249, 0.6);
}

.mode i {
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background: var(--text-faint);
}

.mode.direct {
  color: var(--ok);
  border-color: rgba(62, 124, 89, 0.28);
}

.mode.direct i {
  background: var(--ok);
}

.mode.relay {
  color: var(--gold);
  border-color: rgba(185, 132, 46, 0.28);
}

.mode.relay i {
  background: var(--gold);
}

.mode.none {
  color: var(--danger);
  border-color: rgba(178, 59, 51, 0.3);
}

.mode.none i {
  background: var(--danger);
}

/* ---------- Transitions ---------- */

.preview-enter-active {
  transition:
    opacity 0.22s ease,
    transform 0.24s ease;
}

.preview-leave-active {
  transition: opacity 0.15s ease;
}

.preview-enter-from {
  opacity: 0;
  transform: translateY(-4px);
}

.preview-leave-to {
  opacity: 0;
}

/* ---------- Responsive ---------- */

@media (max-width: 760px) {
  .hero {
    flex-direction: column;
    align-items: flex-start;
    gap: 18px;
    padding: 14px 2px 0;
  }

  .hero-art {
    align-self: center;
    width: min(300px, 100%);
    height: 176px;
    transform: scale(0.94);
    transform-origin: center top;
    margin-bottom: -12px;
  }

  .display {
    font-size: clamp(32px, 9vw, 44px);
  }
}

@media (max-width: 520px) {
  .top {
    padding-top: calc(14px + var(--safe-top));
  }

  .logo {
    width: 40px;
    height: 40px;
    border-radius: 14px;
    font-size: 21px;
  }

  .top h1 {
    font-size: 21px;
  }

  .brand-text p {
    display: none;
  }

  .account-name {
    display: none;
  }

  .account-btn {
    padding: 3px;
  }

  .body {
    gap: 22px;
  }

  .stage {
    padding: 13px;
    border-radius: 24px;
  }

  .room-card {
    padding: 13px 13px 11px;
  }

  .detect-btn {
    min-width: 52px;
  }

  .cover {
    width: 92px;
  }

  .hero-art {
    height: 160px;
    transform: scale(0.86);
  }

  .art-b {
    width: 200px;
    height: 128px;
  }

  .art-a {
    width: 178px;
    height: 112px;
  }

  .foot {
    flex-direction: column;
    align-items: flex-start;
    gap: 6px;
  }
}

/* PC 大屏：两个房间并排，交换按钮浮在中间 */
@media (min-width: 900px) {
  .room-grid {
    flex-direction: row;
    align-items: stretch;
    gap: 12px;
  }

  .room-card {
    flex: 1;
    min-width: 0;
  }

  .room-card:nth-child(2) {
    margin-top: 16px;
  }

  .swap-row {
    justify-content: flex-end;
    padding-top: 6px;
    margin-top: -2px;
  }

  .hairline {
    display: none;
  }

  .start {
    max-width: 420px;
    margin-left: auto;
    margin-right: auto;
  }
}
</style>
