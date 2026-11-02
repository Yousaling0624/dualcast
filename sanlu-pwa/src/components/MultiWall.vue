<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { attachSourceVideo, retrySource, store, updateSource } from '../lib/store'
import { badgePreviewText } from '../lib/sources'
import type { ExtraRoom } from '../lib/store'
import AppIcon from './AppIcon.vue'

const narrow = () =>
  typeof window !== 'undefined' &&
  window.matchMedia('(max-width: 600px), (max-height: 420px)').matches

const sources = computed(() => store.sources.filter((s) => s.video))
// 手机竖屏上默认收起，避免多路墙挡住主画面；点左下角胶囊展开
const collapsed = ref(narrow() && sources.value.length > 0)
const videoEls = new Map<string, HTMLVideoElement>()

watch(
  () => sources.value.length,
  (n, old) => {
    if (n > 0 && old === 0 && narrow()) collapsed.value = true
  }
)

function bindVideo(id: string, el: unknown) {
  const video = (el as HTMLVideoElement | null) || null
  const old = videoEls.get(id)
  if (video) {
    if (old === video) return
    videoEls.set(id, video)
    attachSourceVideo(id, video)
  } else if (old) {
    videoEls.delete(id)
    attachSourceVideo(id, null)
  }
}

function badge(s: ExtraRoom): string {
  return badgePreviewText(s.badgeMode, {
    uname: s.uname,
    custom: s.badgeText,
    color: s.color,
  })
}

function toggleAudio(s: ExtraRoom) {
  updateSource(s.id, { commentary: !s.commentary })
}

function closeVideo(s: ExtraRoom) {
  updateSource(s.id, { video: false })
}

function statusText(s: ExtraRoom): string {
  if (s.audioError) return s.audioError
  if (s.mediaState === 'error') return '播放失败'
  if (s.mediaState === 'playing') return ''
  return '连接中…'
}

onBeforeUnmount(() => {
  for (const id of [...videoEls.keys()]) attachSourceVideo(id, null)
  videoEls.clear()
})
</script>

<template>
  <div
    v-if="sources.length"
    class="multi-wall"
    :class="{ collapsed }"
    @pointerdown.stop
    @pointermove.stop
    @click.stop
  >
    <button
      v-if="collapsed"
      class="wall-pill"
      title="展开多路画面"
      @click="collapsed = false"
    >
      <AppIcon name="pip" :size="14" />
      <span>多路画面</span>
      <b>{{ sources.length }}</b>
    </button>

    <!-- 收起时把 UI 移出屏幕而不是 display:none，video 继续播放，多路声音不会中断 -->
    <div class="wall-head">
      <span class="wall-title"><i></i>多路画面 <b>{{ sources.length }}</b></span>
      <button class="wall-collapse" title="收起多路画面" @click="collapsed = true">
        <AppIcon name="chevron-down" :size="13" />
      </button>
    </div>

    <div class="wall-grid">
      <div v-for="s in sources" :key="s.id" class="wall-tile">
        <video :ref="(el) => bindVideo(s.id, el)" playsinline></video>
        <div class="wall-shade"></div>

        <div v-if="statusText(s)" class="wall-status">
          <span v-if="s.audioError" class="wall-err">{{ s.audioError }}</span>
          <span v-else class="wall-loading"><i></i>{{ statusText(s) }}</span>
        </div>

        <div class="wall-foot">
          <span class="wall-badge" :style="{ background: s.color }">{{ badge(s) }}</span>
          <span class="wall-name">{{ s.uname || s.title || `房间 ${s.roomId}` }}</span>
          <button
            v-if="s.audioError"
            class="wall-sound"
            title="重试这一路"
            @click.stop="retrySource(s.id)"
          >
            <AppIcon name="refresh" :size="13" />
          </button>
          <button
            class="wall-sound"
            :class="{ on: s.commentary }"
            :title="s.commentary ? '关闭这一路声音' : '打开这一路声音'"
            @click.stop="toggleAudio(s)"
          >
            <AppIcon :name="s.commentary ? 'volume' : 'volume-off'" :size="13" />
          </button>
          <button
            class="wall-close"
            title="从多路画面移除"
            @click.stop="closeVideo(s)"
          >
            <AppIcon name="close" :size="13" />
          </button>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.multi-wall {
  position: absolute;
  left: 10px;
  bottom: 70px;
  z-index: 5;
  width: min(40%, 380px);
  max-height: calc(100% - 120px);
  display: flex;
  flex-direction: column;
  gap: 4px;
  pointer-events: auto;
}

.multi-wall.collapsed {
  width: auto;
  max-width: calc(100% - 20px);
  max-height: none;
}

.multi-wall.collapsed .wall-head,
.multi-wall.collapsed .wall-grid {
  position: absolute;
  left: -9999px;
  top: 0;
  width: 1px;
  height: 1px;
  overflow: hidden;
  opacity: 0;
  pointer-events: none;
}

.wall-pill {
  align-self: flex-start;
  display: inline-flex;
  align-items: center;
  gap: 5px;
  height: 28px;
  padding: 0 10px;
  border: 1px solid rgba(255, 255, 255, 0.18);
  border-radius: 999px;
  background: rgba(16, 14, 12, 0.74);
  color: #fff8ef;
  font-size: 10.5px;
  font-weight: 650;
  cursor: pointer;
  backdrop-filter: blur(10px);
}

.wall-pill b,
.wall-title b {
  color: var(--accent);
  font-size: 10px;
}

.wall-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 3px;
  color: rgba(255, 248, 239, 0.74);
  font-size: 10px;
  text-shadow: 0 1px 4px rgba(0, 0, 0, 0.75);
}

.wall-title {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  font-weight: 650;
}

.wall-title i {
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background: var(--accent);
  box-shadow: 0 0 0 3px rgba(217, 119, 87, 0.2);
}

.wall-collapse {
  width: 20px;
  height: 20px;
  display: grid;
  place-items: center;
  border: none;
  border-radius: 50%;
  background: rgba(0, 0, 0, 0.46);
  color: #fff;
  cursor: pointer;
}

.wall-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 4px;
  overflow-y: auto;
  overscroll-behavior: contain;
}

.wall-tile {
  position: relative;
  aspect-ratio: 16 / 9;
  overflow: hidden;
  border-radius: 9px;
  border: 1px solid rgba(255, 255, 255, 0.16);
  background: #000;
  box-shadow: 0 8px 20px rgba(0, 0, 0, 0.32);
}

.wall-tile video {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  object-fit: cover;
  pointer-events: none;
}

.wall-shade {
  position: absolute;
  inset: 0;
  background: linear-gradient(transparent 48%, rgba(0, 0, 0, 0.74));
  pointer-events: none;
}

.wall-status {
  position: absolute;
  inset: 0;
  display: grid;
  place-items: center;
  padding: 8px;
  text-align: center;
  color: rgba(255, 248, 239, 0.82);
  font-size: 9.5px;
  line-height: 1.35;
}

.wall-err {
  max-width: 100%;
  overflow: hidden;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
}

.wall-loading {
  display: inline-flex;
  align-items: center;
  gap: 5px;
}

.wall-loading i {
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background: var(--accent);
  animation: wall-blink 1s ease-in-out infinite;
}

@keyframes wall-blink {
  50% {
    opacity: 0.22;
  }
}

.wall-foot {
  position: absolute;
  left: 5px;
  right: 4px;
  bottom: 3px;
  display: flex;
  align-items: center;
  gap: 4px;
  min-width: 0;
}

.wall-badge {
  flex: 0 0 auto;
  display: inline-grid;
  place-items: center;
  min-width: 14px;
  height: 14px;
  padding: 0 3px;
  border-radius: 4px;
  color: #fff;
  font-size: 8.5px;
  font-weight: 750;
  line-height: 1;
}

.wall-name {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
  color: #fff8ef;
  font-size: 9px;
  font-weight: 650;
  text-shadow: 0 1px 3px rgba(0, 0, 0, 0.8);
}

.wall-sound,
.wall-close {
  flex: 0 0 auto;
  width: 19px;
  height: 19px;
  display: grid;
  place-items: center;
  border: none;
  border-radius: 50%;
  background: rgba(0, 0, 0, 0.5);
  color: rgba(255, 248, 239, 0.72);
  cursor: pointer;
}

.wall-sound.on {
  color: #fff;
  background: var(--accent);
}

.wall-close:hover {
  color: #fff;
  background: rgba(190, 70, 52, 0.8);
}

@media (max-width: 600px), (max-height: 420px) {
  .multi-wall {
    width: min(48%, 230px);
    max-height: calc(100% - 108px);
    bottom: 62px;
  }

  .wall-grid {
    display: flex;
    overflow-x: auto;
    overflow-y: hidden;
  }

  .wall-tile {
    flex: 0 0 82%;
  }

  .wall-name {
    font-size: 8.5px;
  }
}
</style>
