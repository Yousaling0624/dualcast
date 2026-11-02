<script setup lang="ts">
import { nextTick, ref, watch } from 'vue'
import { plusOne, send, store } from '../lib/store'
import type { ChatMsg } from '../lib/danmaku'

const emit = defineEmits<{ collapse: [] }>()

const listEl = ref<HTMLElement | null>(null)
const input = ref('')
const selected = ref<ChatMsg | null>(null)
const atBottom = ref(true)
const focusInput = ref<HTMLInputElement | null>(null)

const LIMIT = 20

function preview(): string {
  if (!selected.value || !input.value.trim()) return input.value
  // 简版预览：与 buildReply 同款规则
  const name = (selected.value.uname || '用户').slice(0, 12)
  const budget = LIMIT - input.value.trim().length - 1 - name.length - 1
  const quote =
    budget <= 0
      ? ''
      : selected.value.text.length > budget
        ? selected.value.text.slice(0, Math.max(budget - 1, 0)) + '…'
        : selected.value.text
  return `${input.value.trim()}@${name}:${quote}`.slice(0, LIMIT)
}

function statusLabel(): string {
  const s = store.danmakuStatus
  if (s === 'open') return `${store.viewer} 人在看`
  if (s === 'reconnecting') return '弹幕重连中…'
  if (s === 'connecting') return '弹幕连接中…'
  return '弹幕未连接'
}

function rowClass(m: ChatMsg): string {
  return [
    'row',
    m.kind !== 'danmu' ? 'special' : '',
    m.self ? 'self' : '',
    selected.value?.seq === m.seq ? 'selected' : '',
  ]
    .filter(Boolean)
    .join(' ')
}

function onTap(m: ChatMsg) {
  selected.value = selected.value?.seq === m.seq ? null : m
}

function doPlusOne() {
  if (!selected.value) return
  void plusOne(selected.value)
  selected.value = null
}

async function doSend() {
  const text = input.value.trim()
  if (!text || store.sendState.sending) return
  const target = selected.value
  await send(text, target)
  if (!store.sendState.error) {
    input.value = ''
    if (target) selected.value = null
  }
}

function onScroll() {
  const el = listEl.value
  if (!el) return
  atBottom.value = el.scrollHeight - el.scrollTop - el.clientHeight < 60
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

defineExpose({ focus: () => focusInput.value?.focus() })
</script>

<template>
  <aside class="chat">
    <header>
      <span class="dot" :class="store.danmakuStatus"></span>
      <strong>聊天室 · {{ statusLabel() }}</strong>
      <button class="collapse" title="收起聊天室" @click="emit('collapse')">⌄</button>
    </header>

    <div ref="listEl" class="list scroll-y" @scroll="onScroll">
      <p v-if="!store.messages.length" class="empty">
        {{ store.danmakuStatus === 'open' ? '还没人说话，来发第一条弹幕' : '正在连接弹幕…' }}
      </p>
      <div
        v-for="m in store.messages"
        :key="m.seq"
        class="row-wrap"
      >
        <div :class="rowClass(m)" @click="onTap(m)">
          <span class="name" :class="{ self: m.self }">{{ m.self ? '我' : m.uname }}</span>
          <span class="medal" v-if="m.medal">{{ m.medal }}</span>
          <span class="text">{{ m.text }}</span>
          <span class="price" v-if="m.price">¥{{ m.price }}</span>
        </div>
      </div>
    </div>

    <div v-if="!atBottom" class="jump" @click="atBottom = true; listEl?.scrollTo({top: listEl.scrollHeight, behavior:'smooth'})">
      有新弹幕 ↓
    </div>

    <div v-if="selected" class="quickbar">
      <span class="sel-text" v-if="selected.kind === 'danmu'">回复 @{{ selected.uname }}</span>
      <span class="sel-text" v-else>{{ selected.kind === 'sc' ? `SC ¥${selected.price}` : selected.kind === 'gift' ? '礼物' : '舰长' }}</span>
      <button v-if="store.loggedIn" class="chip" @click="doPlusOne">+1</button>
      <button class="chip" @click="focusInput?.focus()">回复</button>
      <button class="chip dim" @click="selected = null">取消</button>
      <p v-if="!store.loggedIn" class="nologin">登录后才能 +1 / 回复</p>
    </div>

    <div class="composer">
      <p v-if="selected && input.trim()" class="preview">{{ preview() }}</p>
      <p v-if="store.sendState.error" class="send-err">{{ store.sendState.error }}</p>
      <div class="input-row">
        <input
          ref="focusInput"
          v-model="input"
          :maxlength="LIMIT"
          :placeholder="selected ? `回复 @${selected.uname}` : '发条弹幕（发到 A 房间）'"
          @keydown.enter="doSend"
        />
        <span class="count" v-if="input">{{ input.length }}/{{ LIMIT }}</span>
        <button :disabled="!input.trim() || store.sendState.sending" class="send" @click="doSend">
          <span v-if="store.sendState.sending" class="spinner"></span>
          <template v-else>发送</template>
        </button>
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
  padding: 10px 14px;
  border-bottom: 1px solid var(--line);
  font-size: 13px;
}

.dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: #666;
}

.dot.open {
  background: var(--ok);
}

.dot.connecting,
.dot.reconnecting {
  background: var(--accent2);
  animation: blink 1s infinite;
}

@keyframes blink {
  50% {
    opacity: 0.3;
  }
}

header strong {
  flex: 1;
  font-weight: 600;
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
}

.list {
  flex: 1;
  min-height: 0;
  padding: 8px 10px;
  display: flex;
  flex-direction: column;
  gap: 4px;
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
  padding: 5px 9px;
  border-radius: 8px;
  font-size: 13.5px;
  line-height: 1.45;
  word-break: break-all;
  cursor: pointer;
}

.row:active {
  background: rgba(255, 255, 255, 0.07);
}

.row.selected {
  background: rgba(91, 140, 255, 0.18);
  outline: 1px solid rgba(91, 140, 255, 0.5);
}

.row.special {
  background: rgba(255, 184, 77, 0.08);
}

.row.special .price {
  color: var(--accent2);
  margin-left: 6px;
  font-size: 12px;
}

.row.self {
  background: rgba(255, 184, 77, 0.14);
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
  margin-right: 6px;
}

.jump {
  position: absolute;
  bottom: 92px;
  left: 50%;
  transform: translateX(-50%);
  background: var(--accent);
  color: #fff;
  font-size: 12px;
  border-radius: 999px;
  padding: 5px 14px;
  cursor: pointer;
  box-shadow: 0 4px 16px rgba(0, 0, 0, 0.4);
}

.quickbar {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 12px 0;
  animation: fadeUp 0.18s ease;
}

.sel-text {
  font-size: 12px;
  color: var(--text-dim);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  flex: 1;
}

.chip {
  background: var(--surface2);
  color: var(--text);
  border: none;
  border-radius: 999px;
  padding: 7px 14px;
  font-size: 13px;
  cursor: pointer;
}

.chip.dim {
  background: transparent;
  color: var(--text-dim);
}

.nologin {
  margin: 0;
  font-size: 11px;
  color: var(--text-dim);
}

.composer {
  padding: 8px 12px calc(10px + var(--safe-bottom));
  border-top: 1px solid var(--line);
}

.preview {
  margin: 0 0 6px;
  font-size: 12px;
  color: var(--accent2);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.send-err {
  margin: 0 0 6px;
  font-size: 12px;
  color: var(--danger);
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

.count {
  font-size: 11px;
  color: var(--text-dim);
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

/* 横屏：整个面板独立成栏 */
@media (min-width: 768px) {
  .chat {
    border-top: none;
    border-left: 1px solid var(--line);
  }
}
</style>
