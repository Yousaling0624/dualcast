<script setup lang="ts">
/**
 * 关注主播选择器：只列出「关注的、正在直播的」主播。
 * - A / B 模式：选中后交给父组件填到对应输入框；
 * - 多路模式：直接调用 addSource，默认把画面加进多路墙，可以连续选。
 */
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { addSource, store } from '../lib/store'
import { fetchFollowedLive, formatCount } from '../lib/bili'
import type { FollowedLiveRoom } from '../lib/followed'
import AppIcon from './AppIcon.vue'

type Mode = 'a' | 'b' | 'multi'

const props = defineProps<{ fullscreenEl?: HTMLElement | null }>()
const emit = defineEmits<{
  (e: 'pick', room: FollowedLiveRoom, mode: 'a' | 'b'): void
  (e: 'login'): void
}>()

const visible = ref(false)
const mode = ref<Mode>('a')
const rooms = ref<FollowedLiveRoom[]>([])
const loading = ref(false)
const error = ref('')
const actionError = ref('')
const query = ref('')
/** 正在加入多路的房间号，0 表示空闲 */
const adding = ref(0)
let loadedAt = 0
let prevOverflow = ''

const subtitle = computed(() => {
  if (mode.value === 'a') return '点一个主播，填到 A 路 · 主直播间'
  if (mode.value === 'b') return '点一个主播，填到 B 路 · 画面直播间'
  return '可以连续选多个，选中的会加入多路墙'
})

const filtered = computed(() => {
  const q = query.value.trim().toLowerCase()
  if (!q) return rooms.value
  return rooms.value.filter((room) =>
    [room.uname, room.title, room.areaName].some((v) => v.toLowerCase().includes(q))
  )
})

function open(m: Mode) {
  mode.value = m
  query.value = ''
  error.value = ''
  actionError.value = ''
  visible.value = true
  if (!store.loggedIn) return
  if (!loadedAt || Date.now() - loadedAt > 60_000) void load()
}

function close() {
  visible.value = false
}

defineExpose({ open })

async function load(force = false) {
  if (!store.loggedIn) return
  const skeleton = !rooms.value.length
  if (skeleton) loading.value = true
  error.value = ''
  actionError.value = ''
  try {
    rooms.value = await fetchFollowedLive(force)
    loadedAt = Date.now()
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
  } finally {
    loading.value = false
  }
}

function goLogin() {
  close()
  emit('login')
}

/** 这个直播间已经在哪个位置（A / B / 多路） */
function occupiedBy(room: FollowedLiveRoom): 'a' | 'b' | 'multi' | '' {
  const id = String(room.roomId)
  if (store.a.roomId === room.roomId || store.a.input.trim() === id) return 'a'
  if (store.b.roomId === room.roomId || store.b.input.trim() === id) return 'b'
  if (store.sources.some((s) => s.roomId === room.roomId)) return 'multi'
  return ''
}

interface ActionState {
  label: string
  disabled: boolean
  tone: '' | 'taken' | 'current' | 'added'
}

function actionFor(room: FollowedLiveRoom): ActionState {
  const at = occupiedBy(room)
  if (mode.value === 'multi') {
    if (at === 'multi') return { label: '已加入', disabled: true, tone: 'added' }
    if (at === 'a') return { label: '已在 A', disabled: true, tone: 'taken' }
    if (at === 'b') return { label: '已在 B', disabled: true, tone: 'taken' }
    if (adding.value === room.roomId) return { label: '加入中…', disabled: true, tone: '' }
    // 一次只处理一个，避免连续点击时 addSource 的 busy 状态互相打架
    if (adding.value || store.sourceBusy) return { label: '加入多路', disabled: true, tone: '' }
    return { label: '加入多路', disabled: false, tone: '' }
  }
  if (at === mode.value) {
    return { label: `当前 ${mode.value.toUpperCase()}`, disabled: true, tone: 'current' }
  }
  if (at === 'a') return { label: '已在 A', disabled: true, tone: 'taken' }
  if (at === 'b') return { label: '已在 B', disabled: true, tone: 'taken' }
  if (at === 'multi') return { label: '已在多路', disabled: true, tone: 'taken' }
  return { label: `选为 ${mode.value.toUpperCase()}`, disabled: false, tone: '' }
}

async function choose(room: FollowedLiveRoom) {
  const action = actionFor(room)
  if (action.disabled) return
  actionError.value = ''
  if (mode.value === 'multi') {
    adding.value = room.roomId
    try {
      const added = await addSource(String(room.roomId), { video: true })
      if (!added) actionError.value = store.sourceError || '加入失败，稍后重试'
    } finally {
      adding.value = 0
    }
    return
  }
  emit('pick', room, mode.value)
  close()
}

function metaText(room: FollowedLiveRoom): string {
  const bits: string[] = []
  if (room.online > 0) bits.push(`${formatCount(room.online)} 人气`)
  if (room.areaName) bits.push(room.areaName)
  return bits.join(' · ')
}

function onKey(e: KeyboardEvent) {
  if (!visible.value || e.key !== 'Escape') return
  // 抢在设置面板的 Esc 处理之前关掉选择器，避免两层一起关
  e.stopImmediatePropagation()
  e.preventDefault()
  close()
}

onMounted(() => {
  window.addEventListener('keydown', onKey, true)
})

onBeforeUnmount(() => {
  window.removeEventListener('keydown', onKey, true)
  unlockScroll()
})

// 打开时锁住背景滚动；关闭时恢复打开前的值，避免和设置面板互相覆盖
let overflowWatched = false
function lockScroll() {
  if (overflowWatched) return
  overflowWatched = true
  prevOverflow = document.body.style.overflow
  document.body.style.overflow = 'hidden'
}
function unlockScroll() {
  if (!overflowWatched) return
  overflowWatched = false
  document.body.style.overflow = prevOverflow
}

watch(visible, (v) => {
  if (v) lockScroll()
  else unlockScroll()
})
</script>

<template>
  <Teleport :to="props.fullscreenEl || 'body'">
    <Transition name="follow">
      <div v-if="visible" class="mask" @click.self="close">
        <div class="sheet" role="dialog" aria-modal="true" aria-label="关注的主播">
          <div class="grabber"></div>

          <header class="head">
            <div class="head-copy">
              <h2>关注的主播</h2>
              <p>
                {{ subtitle }}
                <template v-if="store.loggedIn && rooms.length">
                  · 正在直播 {{ rooms.length }} 位
                </template>
              </p>
            </div>
            <button
              v-if="store.loggedIn"
              class="icon-btn"
              :disabled="loading"
              title="刷新关注列表"
              @click="load(true)"
            >
              <span v-if="loading" class="spinner"></span>
              <AppIcon v-else name="refresh" :size="17" />
            </button>
            <button class="icon-btn" title="关闭" @click="close">
              <AppIcon name="close" :size="18" />
            </button>
          </header>

          <template v-if="!store.loggedIn">
            <div class="state">
              <span class="state-icon"><AppIcon name="user" :size="24" /></span>
              <strong>登录后可以读取关注列表</strong>
              <p>只会显示关注主播里正在直播的那些，方便直接填到 A / B 或多路。</p>
              <button class="state-btn" @click="goLogin">去登录</button>
            </div>
          </template>

          <template v-else>
            <div class="search">
              <AppIcon name="chat" :size="14" />
              <input
                v-model="query"
                type="search"
                placeholder="搜索主播名、直播间标题"
                aria-label="搜索关注的主播"
              />
            </div>

            <p v-if="actionError" class="error-bar">{{ actionError }}</p>
            <p v-if="error && rooms.length" class="error-bar">
              刷新失败：{{ error }}，下面是上次的结果
            </p>

            <div class="list scroll-y">
              <template v-if="loading && !rooms.length">
                <div v-for="n in 4" :key="n" class="skeleton">
                  <span class="sk-avatar"></span>
                  <span class="sk-lines">
                    <i></i>
                    <i></i>
                  </span>
                </div>
              </template>

              <div v-else-if="error && !rooms.length" class="state">
                <span class="state-icon danger"><AppIcon name="warning" :size="24" /></span>
                <strong>读取关注列表失败</strong>
                <p>{{ error }}</p>
                <button class="state-btn" @click="load(true)">重试</button>
              </div>

              <div v-else-if="!rooms.length" class="state">
                <span class="state-icon"><AppIcon name="clock" :size="24" /></span>
                <strong>关注的主播里现在没人开播</strong>
                <p>等主播开播后再来看看，或者点右上角刷新。</p>
                <button class="state-btn" @click="load(true)">刷新</button>
              </div>

              <template v-else-if="filtered.length">
                <button
                  v-for="room in filtered"
                  :key="room.roomId"
                  class="item"
                  :class="{ done: actionFor(room).tone === 'added' }"
                  :disabled="actionFor(room).disabled"
                  @click="choose(room)"
                >
                  <span class="avatar">
                    <img
                      v-if="room.face"
                      :src="room.face"
                      referrerpolicy="no-referrer"
                      loading="lazy"
                      alt=""
                    />
                    <span v-else class="avatar-empty">
                      {{ (room.uname || room.title || '?').slice(0, 1) }}
                    </span>
                    <span class="live-dot" title="直播中"></span>
                  </span>
                  <span class="who">
                    <span class="name-line">
                      <strong>{{ room.uname || `房间 ${room.roomId}` }}</strong>
                      <em v-if="room.areaName">{{ room.areaName }}</em>
                    </span>
                    <span class="room-title">{{ room.title }}</span>
                    <span v-if="metaText(room)" class="room-meta">{{ metaText(room) }}</span>
                  </span>
                  <span class="action" :class="actionFor(room).tone">
                    <span v-if="adding === room.roomId" class="spinner"></span>
                    <template v-else>{{ actionFor(room).label }}</template>
                  </span>
                </button>
              </template>

              <div v-else class="state small">
                <strong>没有匹配的主播</strong>
                <p>换个关键词试试。</p>
              </div>
            </div>

            <footer v-if="mode === 'multi'" class="foot">
              <span>多路已有 {{ store.sources.length }} 位主播</span>
              <button class="foot-done" @click="close">完成</button>
            </footer>
          </template>
        </div>
      </div>
    </Transition>
  </Teleport>
</template>

<style scoped>
.mask {
  position: fixed;
  inset: 0;
  z-index: 80;
  display: grid;
  place-items: end center;
  background: rgba(28, 23, 18, 0.5);
  backdrop-filter: blur(3px);
}

.sheet {
  position: relative;
  width: min(560px, 100%);
  max-height: 88dvh;
  display: flex;
  flex-direction: column;
  border: 1px solid var(--glass-border);
  border-bottom: none;
  border-radius: 30px 30px 0 0;
  background: var(--bg-soft);
  box-shadow: 0 -24px 80px rgba(28, 23, 18, 0.28);
  padding-bottom: var(--safe-bottom);
  overflow: hidden;
  animation: followUp 0.3s cubic-bezier(0.2, 0.85, 0.25, 1);
}

@keyframes followUp {
  from {
    transform: translateY(24px);
    opacity: 0.7;
  }
  to {
    transform: translateY(0);
    opacity: 1;
  }
}

.follow-enter-active,
.follow-leave-active {
  transition: opacity 0.22s ease;
}

.follow-enter-from,
.follow-leave-to {
  opacity: 0;
}

.grabber {
  width: 38px;
  height: 4px;
  margin: 8px auto 2px;
  border-radius: 999px;
  background: var(--line-strong);
  flex: 0 0 auto;
}

.head {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 16px 8px;
  flex: 0 0 auto;
}

.head-copy {
  flex: 1;
  min-width: 0;
}

.head-copy h2 {
  margin: 0;
  font-family: var(--font-display);
  font-size: 22px;
  font-weight: 550;
  letter-spacing: -0.015em;
  color: var(--text);
}

.head-copy p {
  margin: 2px 0 0;
  font-size: 11px;
  line-height: 1.4;
  color: var(--text-faint);
}

.head .icon-btn {
  width: 34px;
  height: 34px;
  flex: 0 0 auto;
}

.head .icon-btn:disabled {
  opacity: 0.55;
  cursor: progress;
}

.search {
  display: flex;
  align-items: center;
  gap: 7px;
  margin: 2px 16px 8px;
  padding: 0 11px;
  border: 1px solid var(--line);
  border-radius: 14px;
  background: var(--surface);
  color: var(--text-faint);
  flex: 0 0 auto;
}

.search:focus-within {
  border-color: var(--accent);
  box-shadow: 0 0 0 3px var(--accent-soft);
}

.search input {
  flex: 1;
  min-width: 0;
  border: none;
  outline: none;
  background: transparent;
  color: var(--text);
  padding: 9px 0;
  font-size: 13px;
}

.search input::placeholder {
  color: var(--text-faint);
}

.error-bar {
  margin: 0 16px 6px;
  padding: 8px 11px;
  border-radius: 12px;
  background: var(--danger-soft);
  color: var(--danger);
  font-size: 11.5px;
  line-height: 1.45;
  flex: 0 0 auto;
}

.list {
  flex: 1;
  min-height: 120px;
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 2px 12px 12px;
  overscroll-behavior: contain;
}

.item {
  display: flex;
  align-items: center;
  gap: 10px;
  width: 100%;
  padding: 8px 10px;
  border: 1px solid transparent;
  border-radius: 16px;
  background: var(--surface);
  color: var(--text);
  text-align: left;
  cursor: pointer;
  transition:
    border-color 0.15s ease,
    transform 0.15s ease;
}

.item:hover:not(:disabled) {
  border-color: var(--line-strong);
  transform: translateY(-1px);
}

.item:disabled {
  cursor: default;
}

.item.done {
  background: var(--ok-soft);
}

.avatar {
  position: relative;
  width: 44px;
  height: 44px;
  border-radius: 14px;
  overflow: hidden;
  background: var(--surface-2);
  flex: 0 0 auto;
}

.avatar img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.avatar-empty {
  width: 100%;
  height: 100%;
  display: grid;
  place-items: center;
  color: var(--text-faint);
  font-size: 15px;
  font-weight: 650;
}

.live-dot {
  position: absolute;
  left: 5px;
  bottom: 5px;
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: #ff5b4a;
  border: 1.5px solid var(--surface);
  box-shadow: 0 0 0 2px rgba(255, 91, 74, 0.22);
}

.who {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 1px;
}

.name-line {
  display: flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
}

.name-line strong {
  min-width: 0;
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
  font-size: 13px;
  font-weight: 650;
}

.name-line em {
  flex: 0 0 auto;
  max-width: 96px;
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
  padding: 1px 6px;
  border-radius: 999px;
  background: var(--surface-2);
  color: var(--text-faint);
  font-size: 9.5px;
  font-style: normal;
}

.room-title {
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
  color: var(--text-dim);
  font-size: 11.5px;
}

.room-meta {
  color: var(--text-faint);
  font-size: 10px;
}

.action {
  flex: 0 0 auto;
  min-width: 70px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 4px;
  padding: 7px 10px;
  border-radius: 999px;
  background: var(--accent);
  color: var(--on-accent);
  font-size: 11px;
  font-weight: 650;
  white-space: nowrap;
}

.action.taken,
.action.current {
  background: var(--surface-2);
  color: var(--text-faint);
}

.action.current {
  color: var(--accent-strong);
  background: var(--accent-soft);
}

.action.added {
  color: var(--ok);
  background: var(--ok-soft);
}

.action .spinner {
  width: 12px;
  height: 12px;
  border-width: 1.5px;
}

.state {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 6px;
  margin: auto;
  padding: 34px 24px;
  text-align: center;
}

.state.small {
  padding: 22px 24px;
}

.state-icon {
  width: 46px;
  height: 46px;
  display: grid;
  place-items: center;
  margin-bottom: 2px;
  border-radius: 50%;
  background: var(--accent-soft);
  color: var(--accent-strong);
}

.state-icon.danger {
  background: var(--danger-soft);
  color: var(--danger);
}

.state strong {
  font-size: 13.5px;
  font-weight: 650;
}

.state p {
  margin: 0;
  max-width: 300px;
  color: var(--text-dim);
  font-size: 11.5px;
  line-height: 1.55;
}

.state-btn {
  margin-top: 6px;
  padding: 8px 18px;
  border: none;
  border-radius: 999px;
  background: var(--accent);
  color: var(--on-accent);
  font-size: 12px;
  font-weight: 650;
  cursor: pointer;
}

.skeleton {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 10px;
  border-radius: 16px;
  background: var(--surface);
}

.sk-avatar {
  width: 44px;
  height: 44px;
  border-radius: 14px;
  background: var(--surface-2);
  flex: 0 0 auto;
}

.sk-lines {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 7px;
}

.sk-lines i {
  display: block;
  height: 9px;
  border-radius: 999px;
  background: var(--surface-2);
  animation: skPulse 1.2s ease-in-out infinite;
}

.sk-lines i:first-child {
  width: 42%;
}

.sk-lines i:last-child {
  width: 72%;
  animation-delay: 0.15s;
}

@keyframes skPulse {
  50% {
    opacity: 0.45;
  }
}

.foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  padding: 10px 16px calc(12px + var(--safe-bottom));
  border-top: 1px solid var(--line);
  color: var(--text-dim);
  font-size: 11.5px;
  flex: 0 0 auto;
}

.foot-done {
  border: none;
  border-radius: 999px;
  padding: 9px 22px;
  background: var(--accent);
  color: var(--on-accent);
  font-size: 12.5px;
  font-weight: 650;
  cursor: pointer;
}

@media (min-width: 720px) {
  .mask {
    place-items: center;
  }

  .sheet {
    border-bottom: 1px solid var(--glass-border);
    border-radius: 28px;
    max-height: min(640px, 84dvh);
    box-shadow: 0 30px 90px rgba(28, 23, 18, 0.36);
  }
}
</style>
