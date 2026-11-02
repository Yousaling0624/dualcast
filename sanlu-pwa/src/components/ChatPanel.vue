<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import {
  buildReply,
  canInteract,
  clearSendError,
  plusOne,
  replyInputLimit,
  send,
  store,
} from '../lib/store'
import type { ChatMsg } from '../lib/danmaku'

const emit = defineEmits<{ collapse: []; login: [] }>()

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
  if (s === 'open') return store.viewer > 0 ? `${formatViewer(store.viewer)}人气` : '已连接'
  if (s === 'reconnecting') return '弹幕重连中…'
  if (s === 'connecting') return '弹幕连接中…'
  if (s === 'failed') return '弹幕连接失败'
  return '弹幕未连接'
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

function rowClass(m: ChatMsg): string {
  return [
    'row',
    m.kind !== 'danmu' ? `special ${m.kind}` : '',
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
    <header>
      <span class="dot" :class="store.danmakuStatus"></span>
      <div class="head-text">
        <strong>聊天室</strong>
        <small>{{ store.a.uname || store.a.title || 'A 房间' }} · {{ statusLabel }}</small>
      </div>
      <button class="collapse" title="收起聊天室" @click="emit('collapse')">⌄</button>
    </header>

    <div ref="listEl" class="list scroll-y" @scroll="onScroll">
      <p v-if="!store.messages.length" class="empty">
        {{ store.danmakuStatus === 'open' ? '还没人说话，来发第一条弹幕' : '正在连接弹幕…' }}
      </p>
      <div v-for="m in store.messages" :key="m.seq" class="row-wrap">
        <div :class="rowClass(m)" @click="onTap(m)">
          <span class="name" :class="{ self: m.self }">{{ m.self ? '我' : m.uname || '匿名' }}</span>
          <span v-if="m.medal" class="medal">{{ m.medal }}</span>
          <span v-if="m.replyTo" class="reply-tag">↩ {{ m.replyTo }}</span>
          <span class="text">{{ m.text }}</span>
          <span class="price" v-if="m.price">¥{{ m.price }}</span>
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
    </div>

    <div v-if="!atBottom" class="jump" @click="jumpToBottom">有新弹幕 ↓</div>

    <div v-if="selected" class="quickbar">
      <div class="sel-preview">
        <span class="sel-name">{{ selected.uname || '匿名' }}</span>
        <span class="sel-text">{{ selected.text }}</span>
      </div>
      <button v-if="canInteract(selected)" class="chip plus" @click="doPlusOne()">+1</button>
      <button v-if="canInteract(selected)" class="chip" @click="doReply">回复</button>
      <button class="chip dim" @click="selected = null">取消</button>
    </div>

    <div class="composer">
      <div v-if="!store.loggedIn" class="login-tip">
        <span>登录 B 站账号后才能发弹幕、+1、回复</span>
        <button @click="emit('login')">去登录</button>
      </div>

      <div v-if="replyTarget" class="reply-tip">
        <span class="reply-who">回复 @{{ replyTarget.uname || '匿名' }}</span>
        <span v-if="preview" class="reply-preview">{{ preview }}</span>
        <button class="reply-x" @click="replyTarget = null">×</button>
      </div>

      <div class="input-row">
        <input
          ref="focusInput"
          v-model="input"
          :maxlength="inputLimit"
          :placeholder="replyTarget ? `回复 @${replyTarget.uname || '匿名'}` : '发条弹幕（发到 A 房间）'"
          @keydown.enter="(e) => !e.isComposing && doSend()"
        />
        <span v-if="cooldownLeft" class="cooldown">{{ cooldownLeft }}s</span>
        <button :disabled="!canSend" class="send" @click="doSend">
          <span v-if="store.sendState.sending" class="spinner"></span>
          <template v-else>发送</template>
        </button>
      </div>
      <div class="under-row">
        <span class="count">{{ replyTarget && input ? preview.length : input.length }}/{{ LIMIT }}</span>
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
  flex-direction: column;
  min-height: 0;
  background: var(--bg2);
  border-top: 1px solid var(--line);
}

header {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 9px 12px;
  border-bottom: 1px solid var(--line);
}

.dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: #666;
  flex: 0 0 auto;
}

.dot.open {
  background: var(--ok);
}

.dot.connecting,
.dot.reconnecting {
  background: var(--accent2);
  animation: blink 1s infinite;
}

.dot.failed {
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
}

.head-text strong {
  font-size: 13px;
}

.head-text small {
  font-size: 11px;
  color: var(--text-dim);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.collapse {
  border: none;
  background: var(--surface);
  color: var(--text-dim);
  width: 26px;
  height: 26px;
  border-radius: 8px;
  cursor: pointer;
  flex: 0 0 auto;
}

.list {
  flex: 1;
  min-height: 0;
  padding: 6px 8px;
  display: flex;
  flex-direction: column;
  gap: 3px;
}

.empty {
  color: var(--text-dim);
  font-size: 13px;
  text-align: center;
  margin-top: 24px;
}

.row-wrap {
  animation: fadeUp 0.18s ease;
}

.row {
  position: relative;
  padding: 5px 9px;
  border-radius: 8px;
  font-size: 13.5px;
  line-height: 1.45;
  word-break: break-word;
  cursor: pointer;
  padding-right: 40px;
}

.row:active {
  background: rgba(255, 255, 255, 0.06);
}

.row.selected {
  background: rgba(91, 140, 255, 0.18);
  outline: 1px solid rgba(91, 140, 255, 0.5);
}

.row.special {
  background: rgba(255, 184, 77, 0.08);
}

.row.special.sc {
  background: rgba(255, 211, 2, 0.1);
}

.row.special .price {
  color: var(--accent2);
  margin-left: 6px;
  font-size: 12px;
}

.row.self {
  background: rgba(255, 209, 102, 0.14);
}

.name {
  font-weight: 600;
  color: #9db7ff;
  margin-right: 6px;
}

.name.self {
  color: var(--accent2);
}

.medal {
  font-size: 10px;
  color: #ff9db8;
  border: 1px solid rgba(255, 157, 184, 0.5);
  border-radius: 4px;
  padding: 0 3px;
  margin-right: 5px;
}

.reply-tag {
  font-size: 10px;
  color: var(--text-dim);
  background: var(--surface2);
  border-radius: 4px;
  padding: 0 4px;
  margin-right: 5px;
}

.row-plus {
  position: absolute;
  right: 6px;
  top: 50%;
  transform: translateY(-50%);
  border: none;
  border-radius: 999px;
  background: var(--surface2);
  color: var(--text-dim);
  font-size: 11px;
  padding: 3px 8px;
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
}

.jump {
  position: absolute;
  bottom: 96px;
  left: 50%;
  transform: translateX(-50%);
  background: var(--accent);
  color: #fff;
  font-size: 12px;
  border-radius: 999px;
  padding: 5px 14px;
  cursor: pointer;
  box-shadow: 0 4px 16px rgba(0, 0, 0, 0.4);
  z-index: 2;
}

.quickbar {
  display: flex;
  align-items: center;
  gap: 7px;
  margin: 0 10px 6px;
  padding: 7px 8px;
  background: var(--surface2);
  border-radius: 12px;
  animation: fadeUp 0.18s ease;
}

.sel-preview {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
}

.sel-name {
  font-size: 11px;
  color: var(--text-dim);
}

.sel-text {
  font-size: 12px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.chip {
  background: var(--surface);
  color: var(--text);
  border: none;
  border-radius: 999px;
  padding: 7px 13px;
  font-size: 13px;
  cursor: pointer;
  flex: 0 0 auto;
}

.chip.plus {
  background: rgba(255, 184, 77, 0.18);
  color: var(--accent2);
  font-weight: 600;
}

.chip.dim {
  background: transparent;
  color: var(--text-dim);
}

.composer {
  padding: 0 10px calc(9px + var(--safe-bottom));
  border-top: 1px solid var(--line);
  padding-top: 7px;
}

.login-tip {
  display: flex;
  align-items: center;
  gap: 8px;
  background: rgba(91, 140, 255, 0.12);
  border-radius: 9px;
  padding: 6px 10px;
  margin-bottom: 7px;
  font-size: 11px;
  color: var(--text-dim);
}

.login-tip span {
  flex: 1;
}

.login-tip button {
  border: none;
  background: none;
  color: var(--accent);
  font-size: 12px;
  cursor: pointer;
  padding: 0;
}

.reply-tip {
  display: flex;
  align-items: center;
  gap: 7px;
  background: rgba(255, 184, 77, 0.12);
  border-radius: 9px;
  padding: 5px 9px;
  margin-bottom: 6px;
  font-size: 11px;
}

.reply-who {
  color: var(--accent2);
  font-weight: 600;
  flex: 0 0 auto;
}

.reply-preview {
  flex: 1;
  min-width: 0;
  color: var(--text-dim);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.reply-x {
  border: none;
  background: none;
  color: var(--text-dim);
  font-size: 15px;
  cursor: pointer;
  padding: 0 2px;
}

.input-row {
  display: flex;
  align-items: center;
  gap: 8px;
}

.input-row input {
  flex: 1;
  min-width: 0;
  background: var(--surface);
  border: 1px solid var(--line);
  border-radius: 999px;
  padding: 10px 14px;
  color: var(--text);
  font-size: 14px;
  outline: none;
}

.input-row input:focus {
  border-color: rgba(91, 140, 255, 0.55);
}

.cooldown {
  font-size: 11px;
  color: var(--accent2);
}

.send {
  border: none;
  background: linear-gradient(135deg, #5b8cff, #7c5bff);
  color: #fff;
  border-radius: 999px;
  padding: 10px 16px;
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
}

.send:disabled {
  filter: grayscale(0.7);
  opacity: 0.5;
}

.under-row {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 4px 4px 0;
  min-height: 16px;
}

.count {
  font-size: 10px;
  color: var(--text-dim);
  opacity: 0.7;
}

.hint,
.send-err {
  font-size: 11px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.hint {
  color: var(--accent2);
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
}
</style>