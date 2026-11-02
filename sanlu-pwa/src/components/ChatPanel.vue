<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import {
  buildReply,
  canInteract,
  clearSendError,
  plusOne,
  replyInputLimit,
  send,
  shouldShowSourceBadges,
  store,
  toggleIncludeBDanmaku,
} from '../lib/store'
import type { ChatMsg } from '../lib/danmaku'
import AppIcon from './AppIcon.vue'

const emit = defineEmits<{ collapse: []; login: []; import: [] }>()

const listEl = ref<HTMLElement | null>(null)
const focusInput = ref<HTMLInputElement | null>(null)
const input = ref('')
const selected = ref<ChatMsg | null>(null)
const replyTarget = ref<ChatMsg | null>(null)
const atBottom = ref(true)
const hint = ref('')
const clearOnSuccess = ref(false)
const now = ref(Date.now())
let clock: number | null = null
let handledSeq = store.sendState.sentSeq

const LIMIT = 20

const statusLabel = computed(() => {
  const s = store.danmakuStatus
  const extras: string[] = []
  if (store.sources.length) extras.push(`+${store.sources.length}路`)
  if (store.settings.danmaku.includeB && store.b.roomId) extras.push('A+B')
  const extra = extras.length ? ` · ${extras.join(' · ')}` : ''
  if (s === 'open') return (store.viewer > 0 ? `${formatViewer(store.viewer)}人气` : '已连接') + extra
  if (s === 'reconnecting') return '弹幕重连中…' + extra
  if (s === 'connecting') return '弹幕连接中…' + extra
  if (s === 'failed') return '弹幕连接失败' + extra
  return '弹幕未连接' + extra
})

const showSourceBadges = computed(() => shouldShowSourceBadges())

const sendRoomLabel = computed(() => {
  const id = replyTarget.value?.sourceId
  if (id && id !== 'a') return replyTarget.value?.sourceName || '导入直播间'
  return store.a.uname || '主直播间'
})

const inputLimit = computed(() =>
  replyTarget.value ? replyInputLimit(replyTarget.value) : LIMIT
)

const preview = computed(() =>
  replyTarget.value && input.value.trim() ? buildReply(input.value, replyTarget.value) : ''
)

const cooldownLeft = computed(() => {
  const ms = store.sendState.cooldownUntil - now.value
  return ms > 0 ? Math.ceil(ms / 1000) : 0
})

const canSend = computed(
  () => !!input.value.trim() && !store.sendState.sending && cooldownLeft.value === 0
)

function formatViewer(n: number): string {
  if (n >= 100_000_000) return `${(n / 100_000_000).toFixed(1)}亿`
  if (n >= 10_000) return `${(n / 10_000).toFixed(1)}万`
  return String(n)
}

function avatarUrl(u?: string): string {
  if (!u) return ''
  return u.startsWith('//') ? `https:${u}` : u
}

function initial(name: string): string {
  return (name || '?').trim().slice(0, 1).toUpperCase()
}

function rowClass(m: ChatMsg): string {
  return [
    'row',
    m.self ? 'self' : '',
    selected.value?.seq === m.seq ? 'selected' : '',
  ]
    .filter(Boolean)
    .join(' ')
}

function onTap(m: ChatMsg) {
  if (!canInteract(m) && !m.self) return
  selected.value = selected.value?.seq === m.seq ? null : m
  clearSendError()
  hint.value = ''
}

function focusReply(target: ChatMsg) {
  if (!store.loggedIn) {
    hint.value = '先登录 B 站账号才能互动'
    emit('login')
    return
  }
  replyTarget.value = target
  selected.value = null
  hint.value = ''
  const max = replyInputLimit(target)
  if (input.value.length > max) input.value = input.value.slice(0, max)
  void nextTick(() => focusInput.value?.focus())
}

function doReply() {
  if (selected.value) focusReply(selected.value)
}

async function doPlusOne(target?: ChatMsg | null) {
  const msg = target || selected.value
  if (!msg) return
  if (!store.loggedIn) {
    hint.value = '先登录 B 站账号才能 +1'
    emit('login')
    return
  }
  if (!canInteract(msg)) return
  clearOnSuccess.value = false
  await plusOne(msg)
  if (!store.sendState.error) {
    selected.value = null
    hint.value = ''
  }
}

async function doSend() {
  const text = input.value.trim()
  if (!text || store.sendState.sending || cooldownLeft.value > 0) return
  if (!store.loggedIn) {
    hint.value = '先登录 B 站账号才能发弹幕'
    emit('login')
    return
  }
  clearOnSuccess.value = true
  const ok = await send(text, replyTarget.value)
  if (ok) {
    input.value = ''
    replyTarget.value = null
    hint.value = ''
  }
}

function onScroll() {
  const el = listEl.value
  if (!el) return
  atBottom.value = el.scrollHeight - el.scrollTop - el.clientHeight < 60
}

function jumpToBottom() {
  atBottom.value = true
  const el = listEl.value
  if (el) el.scrollTop = el.scrollHeight
}

watch(
  () => store.messages.length,
  async () => {
    if (!atBottom.value) return
    await nextTick()
    const el = listEl.value
    if (el) el.scrollTop = el.scrollHeight
  }
)

watch(
  () => store.a.roomId,
  () => {
    selected.value = null
    replyTarget.value = null
    input.value = ''
  }
)

watch(
  () => store.sendState.sentSeq,
  (seq) => {
    if (seq === handledSeq) return
    handledSeq = seq
    if (clearOnSuccess.value) {
      input.value = ''
      replyTarget.value = null
    }
    clearOnSuccess.value = false
    hint.value = ''
  }
)

watch(
  () => store.sendState.error,
  (err) => {
    if (err) {
      clearOnSuccess.value = false
      hint.value = ''
    }
  }
)

watch(input, () => {
  hint.value = ''
  if (store.sendState.error) clearSendError()
})

onMounted(() => {
  clock = window.setInterval(() => (now.value = Date.now()), 500)
  // 从全屏/收起状态重新打开时，直接落到最新一条
  void nextTick(jumpToBottom)
})
onBeforeUnmount(() => {
  if (clock) clearInterval(clock)
})

defineExpose({ focus: () => focusInput.value?.focus() })
</script>

<template>
  <aside class="chat">
    <header class="chat-head">
      <div class="room-avatar">
        <img
          v-if="store.a.face"
          :src="avatarUrl(store.a.face)"
          referrerpolicy="no-referrer"
          alt=""
        />
        <span v-else>{{ initial(store.a.uname || 'A') }}</span>
        <i class="status-pin" :class="store.danmakuStatus"></i>
      </div>
      <div class="head-text">
        <strong>聊天室</strong>
        <small>
          {{ store.a.uname || store.a.title || 'A 房间' }}
          <span class="head-sep">·</span>
          {{ statusLabel }}
        </small>
      </div>
      <button
        class="ab-btn"
        :class="{ on: store.settings.danmaku.includeB }"
        :title="
          store.settings.danmaku.includeB
            ? '只显示 A 路弹幕'
            : '同时显示 A / B 两路弹幕'
        "
        :aria-pressed="store.settings.danmaku.includeB"
        @click="toggleIncludeBDanmaku"
      >
        A+B
      </button>
      <button class="import-btn" title="导入更多直播间的弹幕 / 解说" @click="emit('import')">
        <AppIcon name="plus" :size="16" />
      </button>
      <button class="collapse" title="收起聊天室" @click="emit('collapse')">
        <AppIcon name="chevron-down" :size="18" />
      </button>
    </header>

    <div ref="listEl" class="list scroll-y" @scroll="onScroll">
      <div v-if="!store.messages.length" class="empty">
        <span class="empty-icon"><AppIcon name="chat" :size="22" /></span>
        <strong>{{ store.danmakuStatus === 'open' ? '还很安静' : '正在连接弹幕…' }}</strong>
        <small>
          {{
            store.danmakuStatus === 'open'
              ? '来发第一条弹幕，和大家一起看'
              : '连上 A 房间后，弹幕会出现在这里'
          }}
        </small>
      </div>

      <template v-for="m in store.messages" :key="m.seq">
        <div v-if="m.kind === 'sys'" class="sys-row">
          <span>{{ m.text }}</span>
        </div>

        <div v-else-if="m.kind === 'sc'" class="sc-row">
          <div class="sc-avatar">
            <img
              v-if="m.avatar"
              :src="avatarUrl(m.avatar)"
              referrerpolicy="no-referrer"
              alt=""
            />
            <span v-else>{{ initial(m.uname) }}</span>
          </div>
          <div class="sc-main">
            <div class="sc-meta">
              <span
                v-if="showSourceBadges && m.sourceColor"
                class="src-badge"
                :class="{ dot: !m.badge }"
                :style="{ background: m.sourceColor }"
                :title="m.sourceName"
              >
                {{ m.badge }}
              </span>
              <strong>{{ m.uname || '匿名' }}</strong>
              <span v-if="m.price" class="sc-price">¥{{ m.price }}</span>
              <span class="sc-label">SC</span>
            </div>
            <p>{{ m.text }}</p>
          </div>
        </div>

        <div v-else-if="m.kind === 'gift' || m.kind === 'guard'" class="event-row">
          <span class="event-icon">
            <AppIcon :name="m.kind === 'guard' ? 'user' : 'sparkles'" :size="14" />
          </span>
          <span class="event-text">
            <strong>{{ m.uname || '观众' }}</strong>
            {{ m.text }}
            <em v-if="m.price">¥{{ m.price }}</em>
          </span>
        </div>

        <div v-else class="row-wrap">
          <div :class="rowClass(m)" @click="onTap(m)">
            <span
              v-if="showSourceBadges && m.sourceColor"
              class="src-badge"
              :class="{ dot: !m.badge }"
              :style="{ background: m.sourceColor }"
              :title="m.sourceName"
            >
              {{ m.badge }}
            </span>
            <span class="name" :class="{ self: m.self }">{{ m.self ? '我' : m.uname || '匿名' }}</span>
            <span v-if="m.medal" class="medal">{{ m.medal }}</span>
            <span v-if="m.replyTo" class="reply-tag">
              <AppIcon name="reply" :size="10" />
              {{ m.replyTo }}
            </span>
            <span class="text">{{ m.text }}</span>
            <span v-if="m.price && m.kind === 'danmu'" class="price">¥{{ m.price }}</span>
            <button
              v-if="canInteract(m) && store.loggedIn"
              class="row-plus"
              title="+1"
              @click.stop="doPlusOne(m)"
            >
              +1
            </button>
          </div>
        </div>
      </template>
    </div>

    <Transition name="jump">
      <button v-if="!atBottom" class="jump" @click="jumpToBottom">
        有新弹幕
        <AppIcon name="chevron-down" :size="13" />
      </button>
    </Transition>

    <Transition name="quick">
      <div v-if="selected" class="selection">
        <div class="sel-preview">
          <span class="sel-name">{{ selected.uname || '匿名' }}</span>
          <span class="sel-text">{{ selected.text }}</span>
        </div>
        <button v-if="canInteract(selected)" class="chip plus" @click="doPlusOne()">
          <AppIcon name="plus" :size="14" />
          +1
        </button>
        <button v-if="canInteract(selected)" class="chip" @click="doReply">
          <AppIcon name="reply" :size="14" />
          回复
        </button>
        <button class="chip dim" @click="selected = null">
          <AppIcon name="close" :size="14" />
        </button>
      </div>
    </Transition>

    <div class="composer">
      <div v-if="!store.loggedIn" class="login-tip">
        <span class="login-icon"><AppIcon name="user" :size="14" /></span>
        <span>登录后可以发弹幕、+1、回复</span>
        <button @click="emit('login')">去登录</button>
      </div>

      <div v-if="replyTarget" class="reply-tip">
        <span class="reply-who">
          <AppIcon name="reply" :size="12" />
          回复 @{{ replyTarget.uname || '匿名' }}
        </span>
        <span v-if="preview" class="reply-preview">{{ preview }}</span>
        <button class="reply-x" @click="replyTarget = null">
          <AppIcon name="close" :size="12" />
        </button>
      </div>

      <div class="input-row">
        <input
          ref="focusInput"
          v-model="input"
          :maxlength="inputLimit"
          :placeholder="replyTarget ? `回复 @${replyTarget.uname || '匿名'}` : '说点什么…'"
          @keydown.enter="(e) => !e.isComposing && doSend()"
        />
        <span v-if="cooldownLeft" class="cooldown">{{ cooldownLeft }}s</span>
        <button :disabled="!canSend" class="send" title="发送弹幕" @click="doSend">
          <span v-if="store.sendState.sending" class="spinner"></span>
          <AppIcon v-else name="send" :size="17" />
        </button>
      </div>
      <div class="under-row">
        <span class="count">
          {{ replyTarget && input ? preview.length : input.length }}/{{ inputLimit }}
        </span>
        <span class="to-room">发到 · {{ sendRoomLabel }}</span>
        <span v-if="hint" class="hint">{{ hint }}</span>
        <span v-else-if="store.sendState.error" class="send-err">{{ store.sendState.error }}</span>
      </div>
    </div>
  </aside>
</template>

<style scoped>
.chat {
  position: relative;
  display: flex;
  flex: 1;
  flex-direction: column;
  min-height: 0;
  background:
    radial-gradient(500px 240px at 100% 0%, rgba(217, 119, 87, 0.06), transparent 70%),
    var(--bg-soft);
  border-top: 1px solid var(--line);
  color: var(--text);
}

.chat-head {
  display: flex;
  align-items: center;
  gap: 9px;
  padding: 10px 13px;
  border-bottom: 1px solid var(--line);
  background: rgba(249, 245, 238, 0.9);
  backdrop-filter: blur(16px);
}

.room-avatar {
  position: relative;
  width: 34px;
  height: 34px;
  border-radius: 12px;
  flex: 0 0 auto;
  background: linear-gradient(135deg, #e28a6d, var(--accent));
  color: #fff;
  display: grid;
  place-items: center;
  font-size: 13px;
  font-weight: 650;
  overflow: visible;
}

.room-avatar img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  border-radius: 12px;
}

.status-pin {
  position: absolute;
  right: -2px;
  bottom: -2px;
  width: 9px;
  height: 9px;
  border-radius: 50%;
  border: 2px solid var(--bg-soft);
  background: var(--text-faint);
}

.status-pin.open {
  background: var(--ok);
}

.status-pin.connecting,
.status-pin.reconnecting {
  background: var(--gold);
  animation: blink 1s infinite;
}

.status-pin.failed {
  background: var(--danger);
}

@keyframes blink {
  50% {
    opacity: 0.3;
  }
}

.head-text {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  line-height: 1.25;
  gap: 1px;
}

.head-text strong {
  font-size: 13.5px;
  font-weight: 650;
}

.head-text small {
  font-size: 10.5px;
  color: var(--text-faint);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.head-sep {
  opacity: 0.55;
  margin: 0 2px;
}

.collapse,
.import-btn {
  border: 1px solid var(--line);
  background: var(--surface);
  color: var(--text-dim);
  width: 30px;
  height: 30px;
  border-radius: 50%;
  cursor: pointer;
  flex: 0 0 auto;
  display: grid;
  place-items: center;
}

.import-btn {
  color: var(--accent-strong);
  background: var(--accent-soft);
  border-color: transparent;
}

.ab-btn {
  flex: 0 0 auto;
  height: 26px;
  padding: 0 8px;
  border-radius: 999px;
  border: 1px solid var(--line);
  background: var(--surface);
  color: var(--text-dim);
  font-size: 10px;
  font-weight: 750;
  letter-spacing: 0.02em;
  cursor: pointer;
}

.ab-btn.on {
  color: #fff;
  background: #6679cf;
  border-color: transparent;
}

/* 多路弹幕来源气泡 */
.src-badge {
  display: inline-grid;
  place-items: center;
  min-width: 16px;
  height: 16px;
  padding: 0 4px;
  border-radius: 5px;
  color: #fff;
  font-size: 9.5px;
  font-weight: 750;
  line-height: 1;
  vertical-align: 1px;
  margin-right: 4px;
  text-shadow: 0 1px 1px rgba(0, 0, 0, 0.25);
}

.src-badge.dot {
  min-width: 8px;
  width: 8px;
  height: 14px;
  padding: 0;
  border-radius: 999px;
}

.list {
  flex: 1;
  min-height: 0;
  padding: 8px 9px 10px;
  display: flex;
  flex-direction: column;
  gap: 2px;
  overflow-anchor: none;
}

.empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 4px;
  margin: auto;
  padding: 30px 20px;
  text-align: center;
}

.empty-icon {
  width: 48px;
  height: 48px;
  border-radius: 18px;
  display: grid;
  place-items: center;
  background: var(--surface);
  border: 1px solid var(--line);
  color: var(--accent);
  margin-bottom: 6px;
}

.empty strong {
  font-size: 13px;
  font-weight: 600;
}

.empty small {
  font-size: 11px;
  color: var(--text-faint);
}

/* 普通弹幕 */
.row-wrap {
  animation: fadeUp 0.18s ease;
}

.row {
  position: relative;
  padding: 5px 8px;
  border-radius: 11px;
  font-size: 13px;
  line-height: 1.5;
  word-break: break-word;
  cursor: pointer;
  padding-right: 38px;
  transition: background 0.14s ease;
}

.row:hover {
  background: rgba(255, 253, 249, 0.8);
}

.row:active {
  background: var(--surface-2);
}

.row.selected {
  background: var(--accent-soft);
  outline: 1px solid rgba(217, 119, 87, 0.26);
}

.row.self {
  background: var(--accent-soft);
}

.row.self .name {
  color: var(--accent-strong);
}

.name {
  font-weight: 650;
  color: var(--accent-2);
  margin-right: 5px;
  font-size: 12px;
}

.medal {
  display: inline-block;
  font-size: 9px;
  color: var(--accent-strong);
  border: 1px solid rgba(217, 119, 87, 0.28);
  background: rgba(217, 119, 87, 0.07);
  border-radius: 999px;
  padding: 0 5px;
  margin-right: 5px;
  vertical-align: 1px;
}

.reply-tag {
  display: inline-flex;
  align-items: center;
  gap: 2px;
  font-size: 9.5px;
  color: var(--text-faint);
  background: var(--surface-2);
  border-radius: 999px;
  padding: 0 5px;
  margin-right: 5px;
  vertical-align: 1px;
  max-width: 100px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.price {
  color: var(--gold);
  margin-left: 5px;
  font-size: 10.5px;
  font-weight: 600;
}

.row-plus {
  position: absolute;
  right: 5px;
  top: 50%;
  transform: translateY(-50%);
  border: 1px solid var(--line);
  border-radius: 999px;
  background: var(--surface);
  color: var(--accent-strong);
  font-size: 10px;
  font-weight: 650;
  padding: 2px 7px;
  cursor: pointer;
  opacity: 0;
  transition: opacity 0.15s ease;
}

.row:hover .row-plus {
  opacity: 1;
}

@media (hover: none) {
  .row-plus {
    display: none;
  }

  .row {
    padding-right: 8px;
  }
}

/* SC */
.sc-row {
  display: flex;
  gap: 9px;
  margin: 4px 0;
  padding: 9px 10px;
  border-radius: 15px;
  background: linear-gradient(135deg, #fff5df, #fbe6bd);
  border: 1px solid rgba(185, 132, 46, 0.28);
  animation: fadeUp 0.2s ease;
}

.sc-avatar {
  width: 30px;
  height: 30px;
  border-radius: 50%;
  overflow: hidden;
  flex: 0 0 auto;
  display: grid;
  place-items: center;
  color: #fff;
  font-size: 11px;
  font-weight: 650;
  background: linear-gradient(135deg, var(--gold), var(--accent));
}

.sc-avatar img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.sc-main {
  min-width: 0;
  flex: 1;
}

.sc-meta {
  display: flex;
  align-items: center;
  gap: 5px;
  min-width: 0;
}

.sc-meta strong {
  font-size: 12px;
  color: #7a5318;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.sc-price {
  font-size: 10px;
  font-weight: 700;
  color: #8a5b13;
  background: rgba(185, 132, 46, 0.14);
  border-radius: 999px;
  padding: 1px 6px;
}

.sc-label {
  margin-left: auto;
  font-size: 8.5px;
  font-weight: 800;
  letter-spacing: 0.1em;
  color: #9a6a1b;
  background: rgba(255, 255, 255, 0.55);
  border-radius: 999px;
  padding: 2px 6px;
}

.sc-main p {
  margin: 3px 0 0;
  font-size: 12.5px;
  line-height: 1.5;
  color: #5d4318;
  word-break: break-word;
}

/* 礼物 / 舰长 */
.event-row {
  display: flex;
  align-items: flex-start;
  gap: 7px;
  margin: 2px 2px;
  padding: 4px 7px;
  border-radius: 10px;
  background: rgba(255, 253, 249, 0.55);
}

.event-icon {
  width: 22px;
  height: 22px;
  border-radius: 7px;
  display: grid;
  place-items: center;
  background: var(--accent-2-soft);
  color: var(--accent-2);
  flex: 0 0 auto;
  margin-top: 1px;
}

.event-text {
  font-size: 11.5px;
  line-height: 1.5;
  color: var(--text-dim);
}

.event-text strong {
  color: var(--text);
  font-size: 12px;
}

.event-text em {
  font-style: normal;
  color: var(--gold);
  font-weight: 600;
}

.sys-row {
  align-self: center;
  margin: 5px 0;
  font-size: 10.5px;
  color: var(--text-faint);
  background: var(--surface-2);
  border-radius: 999px;
  padding: 3px 10px;
}

/* 新弹幕 */
.jump {
  position: absolute;
  bottom: calc(94px + var(--safe-bottom));
  left: 50%;
  transform: translateX(-50%);
  display: inline-flex;
  align-items: center;
  gap: 3px;
  border: none;
  background: var(--accent);
  color: #fff;
  font-size: 11px;
  font-weight: 600;
  border-radius: 999px;
  padding: 5px 13px;
  cursor: pointer;
  box-shadow: 0 6px 18px rgba(217, 119, 87, 0.3);
  z-index: 2;
}

.jump-enter-active,
.jump-leave-active {
  transition:
    opacity 0.18s ease,
    transform 0.18s ease;
}

.jump-enter-from,
.jump-leave-to {
  opacity: 0;
  transform: translate(-50%, 5px);
}

/* 选中弹幕 */
.selection {
  display: flex;
  align-items: center;
  gap: 6px;
  margin: 0 10px 6px;
  padding: 7px 8px 7px 11px;
  background: var(--surface);
  border: 1px solid var(--line);
  border-radius: 15px;
  box-shadow: var(--shadow-card);
}

.quick-enter-active,
.quick-leave-active {
  transition:
    opacity 0.16s ease,
    transform 0.18s ease;
}

.quick-enter-from,
.quick-leave-to {
  opacity: 0;
  transform: translateY(4px);
}

.sel-preview {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 1px;
}

.sel-name {
  font-size: 10px;
  color: var(--accent-strong);
  font-weight: 600;
}

.sel-text {
  font-size: 11.5px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  color: var(--text-dim);
}

.chip {
  display: inline-flex;
  align-items: center;
  gap: 3px;
  background: var(--surface-2);
  color: var(--text);
  border: none;
  border-radius: 999px;
  padding: 6px 11px;
  font-size: 11.5px;
  font-weight: 550;
  cursor: pointer;
  flex: 0 0 auto;
}

.chip.plus {
  background: var(--accent);
  color: var(--on-accent);
}

.chip.dim {
  background: transparent;
  color: var(--text-faint);
  padding: 6px 7px;
}

/* 输入区 */
.composer {
  padding: 7px 10px calc(9px + var(--safe-bottom));
  border-top: 1px solid var(--line);
  background: var(--bg-soft);
}

.login-tip {
  display: flex;
  align-items: center;
  gap: 7px;
  background: var(--surface);
  border: 1px solid var(--line);
  border-radius: 13px;
  padding: 7px 9px;
  margin-bottom: 7px;
  font-size: 10.5px;
  color: var(--text-dim);
}

.login-icon {
  width: 24px;
  height: 24px;
  border-radius: 50%;
  display: grid;
  place-items: center;
  background: var(--accent-soft);
  color: var(--accent-strong);
  flex: 0 0 auto;
}

.login-tip > span:nth-child(2) {
  flex: 1;
}

.login-tip button {
  border: none;
  background: none;
  color: var(--accent-strong);
  font-size: 11px;
  font-weight: 600;
  cursor: pointer;
  padding: 0;
}

.reply-tip {
  display: flex;
  align-items: center;
  gap: 7px;
  background: var(--gold-soft);
  border-radius: 11px;
  padding: 6px 9px;
  margin-bottom: 6px;
  font-size: 10.5px;
}

.reply-who {
  display: inline-flex;
  align-items: center;
  gap: 3px;
  color: #8a5b13;
  font-weight: 600;
  flex: 0 0 auto;
}

.reply-preview {
  flex: 1;
  min-width: 0;
  color: #8a6a35;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.reply-x {
  border: none;
  background: rgba(185, 132, 46, 0.14);
  color: #8a5b13;
  width: 20px;
  height: 20px;
  border-radius: 50%;
  cursor: pointer;
  padding: 0;
  display: grid;
  place-items: center;
  flex: 0 0 auto;
}

.input-row {
  display: flex;
  align-items: center;
  gap: 7px;
  background: var(--surface);
  border: 1px solid var(--line);
  border-radius: 999px;
  padding: 4px 4px 4px 14px;
  transition:
    border-color 0.15s ease,
    box-shadow 0.15s ease;
}

.input-row:focus-within {
  border-color: rgba(217, 119, 87, 0.5);
  box-shadow: 0 0 0 3px var(--accent-soft);
}

.input-row input {
  flex: 1;
  min-width: 0;
  background: transparent;
  border: none;
  padding: 8px 0;
  color: var(--text);
  font-size: 13.5px;
  outline: none;
}

.input-row input::placeholder {
  color: var(--text-faint);
}

.cooldown {
  font-size: 10px;
  color: var(--gold);
  font-weight: 600;
  flex: 0 0 auto;
}

.send {
  width: 36px;
  height: 36px;
  border: none;
  border-radius: 50%;
  display: grid;
  place-items: center;
  background: var(--accent);
  color: var(--on-accent);
  cursor: pointer;
  flex: 0 0 auto;
  transition:
    opacity 0.15s ease,
    transform 0.15s ease;
}

.send:active:not(:disabled) {
  transform: scale(0.92);
}

.send:disabled {
  background: var(--surface-2);
  color: var(--text-faint);
  cursor: not-allowed;
}

.under-row {
  display: flex;
  align-items: center;
  gap: 9px;
  padding: 4px 5px 0;
  min-height: 15px;
  font-size: 9.5px;
}

.count {
  color: var(--text-faint);
  flex: 0 0 auto;
}

.to-room {
  color: var(--text-faint);
  opacity: 0.75;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  flex: 1;
  text-align: right;
}

.hint,
.send-err {
  flex: 1;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  font-size: 10px;
  text-align: right;
}

.hint {
  color: var(--gold);
}

.send-err {
  color: var(--danger);
}

/* 横屏 / PC：独立成栏 */
@media (min-width: 768px) {
  .chat {
    border-top: none;
    border-left: 1px solid var(--line);
  }

  .composer {
    padding-bottom: 10px;
  }
}
</style>
