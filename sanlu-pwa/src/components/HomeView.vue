<script setup lang="ts">
import { ref, computed } from 'vue'
import { store, resolvePreview, startSession, startCombo, deleteCombo, useRecent, swap } from '../lib/store'
import type { SlotState } from '../lib/store'

const settingsOpen = ref(false)

function slotLabel(s: SlotState): string {
  if (s.state === 'loading') return '解析中…'
  if (s.state === 'error') return s.error || '解析失败'
  if (s.state === 'ready') {
    if (s.liveStatus === 1) return `直播中 · ${s.uname || s.title}`
    if (s.liveStatus === 2) return '轮播中'
    return '未开播'
  }
  return ''
}
</script>

<template>
  <div class="home">
    <div class="bg-glow" aria-hidden="true"></div>
    <header class="top">
      <div class="brand">
        <div class="logo">叠</div>
        <div>
          <h1>叠播</h1>
          <p>一路出画面 · 一路出弹幕和声音</p>
        </div>
      </div>
      <button class="ghost" @click="settingsOpen = true">
        {{ store.loggedIn ? '已登录' : '设置' }}
      </button>
    </header>

    <main class="body scroll-y">
      <section v-if="store.combos.length" class="combos">
        <h2>我的组合</h2>
        <div class="combo-row">
          <div v-for="(c, i) in store.combos" :key="c.a + '-' + c.b" class="combo">
            <button class="combo-main" @click="startCombo(c)">
              <span class="badge a">A</span>
              <span class="t">{{ c.aTitle || c.a }}</span>
              <span class="badge b">B</span>
              <span class="t">{{ c.bTitle || c.b }}</span>
            </button>
            <button class="combo-del" title="删除" @click="deleteCombo(i)">×</button>
          </div>
        </div>
      </section>

      <section class="inputs">
        <div class="card">
          <div class="card-head">
            <span class="badge a">A</span>
            <div class="card-title">
              <strong>主直播间</strong>
              <small>出弹幕和声音（在这里发弹幕）</small>
            </div>
          </div>
          <input
            v-model="store.a.input"
            placeholder="房间号或直播间链接"
            inputmode="numeric"
            @blur="resolvePreview('a')"
          />
          <p class="slot-status" :class="{ err: store.a.state === 'error', ok: store.a.liveStatus === 1 && store.a.state === 'ready' }">
            {{ slotLabel(store.a) }}
          </p>
        </div>

        <button class="swap" title="交换 A / B" @click="swap()">⇅ 交换</button>

        <div class="card">
          <div class="card-head">
            <span class="badge b">B</span>
            <div class="card-title">
              <strong>画面直播间</strong>
              <small>只出画面（找比赛画面的那路）</small>
            </div>
          </div>
          <input
            v-model="store.b.input"
            placeholder="房间号或直播间链接"
            inputmode="numeric"
            @blur="resolvePreview('b')"
          />
          <p class="slot-status" :class="{ err: store.b.state === 'error', ok: store.b.liveStatus === 1 && store.b.state === 'ready' }">
            {{ slotLabel(store.b) }}
          </p>
        </div>
      </section>

      <p v-if="store.startError" class="error">{{ store.startError }}</p>

      <button class="primary start" :disabled="store.starting" @click="startSession">
        <span v-if="store.starting" class="spinner"></span>
        {{ store.starting ? '正在解析…' : '开始叠播' }}
      </button>

      <section v-if="store.recent.length" class="recent">
        <h2>最近看过（A 路）</h2>
        <div class="recent-row">
          <button v-for="r in store.recent" :key="r.roomId" class="recent-item" @click="useRecent(r)">
            <span class="t">{{ r.title || r.roomId }}</span>
            <small>{{ r.uname }}</small>
          </button>
        </div>
      </section>
    </main>

    <Teleport to="body">
      <div v-if="settingsOpen" class="mask" @click.self="settingsOpen = false">
        <div class="sheet">
          <p class="sheet-hint">在直播间里也能打开完整设置（账号 / 弹幕 / 声音 / 画面）。</p>
          <p v-if="store.loggedIn" class="sheet-ok">✓ 已登录 B 站，可以发弹幕</p>
          <p v-else class="sheet-dim">还没登录：看直播不用登录，发弹幕需要扫码。</p>
          <button class="primary" @click="settingsOpen = false">知道了</button>
        </div>
      </div>
    </Teleport>
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

.bg-glow {
  pointer-events: none;
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

.top p {
  margin: 2px 0 0;
  color: var(--text-dim);
  font-size: 12px;
}

.body {
  flex: 1;
  width: 100%;
  max-width: 980px;
  margin: 0 auto;
  padding: 10px 22px calc(32px + var(--safe-bottom));
  display: flex;
  flex-direction: column;
  gap: 18px;
}

h2 {
  font-size: 14px;
  color: var(--text-dim);
  margin: 0 0 10px;
  font-weight: 600;
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
  align-items: center;
  gap: 8px;
  background: none;
  border: none;
  color: var(--text);
  padding: 12px 34px 12px 12px;
  cursor: pointer;
  max-width: 340px;
}

.combo-main:active {
  transform: scale(0.98);
}

.combo-main .t {
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  max-width: 110px;
  font-size: 13px;
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
  font-size: 14px;
  color: #fff;
  flex: 0 0 auto;
}

.badge.a {
  background: linear-gradient(135deg, #ffb84d, #ff8a4d);
}

.badge.b {
  background: linear-gradient(135deg, #5b8cff, #7c5bff);
}

.card input {
  width: 100%;
  background: var(--bg2);
  border: 1px solid var(--line);
  border-radius: 10px;
  color: var(--text);
  padding: 12px 14px;
  font-size: 16px;
  outline: none;
}

.card input:focus {
  border-color: rgba(91, 140, 255, 0.55);
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

/* 简易设置提示 */
.mask {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.55);
  display: grid;
  place-items: end center;
  z-index: 60;
}

.sheet {
  background: var(--surface);
  border-radius: 18px 18px 0 0;
  padding: 20px 20px calc(20px + var(--safe-bottom));
  width: min(560px, 100%);
  display: flex;
  flex-direction: column;
  gap: 10px;
  animation: fadeUp 0.22s ease;
}

.sheet-hint {
  margin: 0;
  color: var(--text-dim);
  font-size: 13px;
}

.sheet-ok {
  margin: 0;
  color: var(--ok);
  font-size: 14px;
}

.sheet-dim {
  margin: 0;
  color: var(--text-dim);
  font-size: 14px;
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
