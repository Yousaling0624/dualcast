/**
 * B 站请求层：直连优先，失败自动切换中转。
 *
 * 为什么需要中转：B 站直播网关会按 Origin 白名单拦掉非 bilibili.com 的
 * 浏览器请求（带外域 Origin 直接 403，且浏览器不允许脚本伪造 Origin）。
 * 这和 PiliPlus 这类原生 App 不同 —— App 可以自由设置 Origin/Referer/Cookie。
 *
 * 所以这里的策略是：
 *   1. 先试浏览器直连（部分接口/部分网络环境可通，而且没有隐私成本）；
 *   2. 直连被风控（HTTP 403/412 或 code -352/-412）时，自动走中转；
 *   3. 中转地址可在设置里改，默认用项目已有的 Cloudflare 中转。
 *
 * 直连能做的事（不经过中转）：
 *   - 直播弹幕 WebSocket（不受 CORS 限制）；
 *   - 直播 CDN 拉流（配合页面 no-referrer）；
 *   - buvid 指纹接口 api.bilibili.com/x/frontend/finger/spi。
 */
import { reactive } from 'vue'

export type Bucket = 'live' | 'passport' | 'bili'
export type ApiMode = 'unknown' | 'direct' | 'relay' | 'none'

const HOSTS: Record<Bucket, string> = {
  live: 'https://api.live.bilibili.com',
  passport: 'https://passport.bilibili.com',
  bili: 'https://api.bilibili.com',
}

/** 项目自带的 Cloudflare Pages 中转；构建时可被 VITE_RELAY_URL 覆盖 */
const ENV = ((import.meta as any).env || {}) as { VITE_RELAY_URL?: string }
const DEFAULT_RELAY = (
  ENV.VITE_RELAY_URL || 'https://sanlu.yousaling.dpdns.org'
).replace(/\/+$/, '')

const RELAY_KEY = 'dc_relay_base'
const COOKIE_KEY = 'dc_cookies'

/** 这些返回码说明请求被风控或身份失效，应该换通道 / 重试 */
const RISK_CODES = new Set([-352, -401, -412, -101, -111, -403])

export class ApiError extends Error {
  code: number
  constructor(code: number, message: string) {
    super(message)
    this.name = 'ApiError'
    this.code = code
  }
}

export const netStatus = reactive({
  mode: 'unknown' as ApiMode,
  lastError: '',
  /** 本页面生命周期内直连是否已经被判定不可用；刷新后重新探测 */
  directFailed: false,
  relay: '',
})

/* ---------- Cookie ---------- */

export function cookieString(): string {
  try {
    const raw = localStorage.getItem(COOKIE_KEY)
    return raw ? String(JSON.parse(raw).cookie_string || '') : ''
  } catch {
    return ''
  }
}

export function saveCookieString(cs: string) {
  localStorage.setItem(COOKIE_KEY, JSON.stringify({ cookie_string: cs.trim() }))
}

export function getCookieValue(key: string): string {
  const cs = cookieString()
  const m = cs.match(new RegExp(`(?:^|;\\s*)${key}=([^;]+)`))
  return m ? m[1] : ''
}

/** 合并 cookie 串，后写的同名项覆盖前面的 */
export function mergeCookie(base: string, extra: string): string {
  const map = new Map<string, string>()
  for (const part of `${base}; ${extra}`.split(';')) {
    const eq = part.indexOf('=')
    if (eq > 0) map.set(part.slice(0, eq).trim(), part.slice(eq + 1).trim())
  }
  return [...map.entries()].map(([k, v]) => `${k}=${v}`).join('; ')
}

export function hasCookie(): boolean {
  return /SESSDATA=/.test(cookieString())
}

/* ---------- 中转配置 ---------- */

/**
 * 返回当前中转地址。
 * localStorage 里显式存空串 = 用户主动关闭中转（纯直连模式）。
 */
export function getRelayBase(): string {
  try {
    const saved = localStorage.getItem(RELAY_KEY)
    if (saved !== null) return saved.trim().replace(/\/+$/, '')
  } catch {
    /* ignore */
  }
  return DEFAULT_RELAY
}

export function setRelayBase(value: string) {
  const v = value.trim().replace(/\/+$/, '')
  localStorage.setItem(RELAY_KEY, v)
  netStatus.relay = v
  netStatus.directFailed = false
  netStatus.mode = 'unknown'
}

export function defaultRelay(): string {
  return DEFAULT_RELAY
}

/* ---------- 请求工具 ---------- */

function timeoutSignal(ms: number): { signal: AbortSignal; done: () => void } {
  const ctrl = new AbortController()
  const timer = setTimeout(() => ctrl.abort(), ms)
  return { signal: ctrl.signal, done: () => clearTimeout(timer) }
}

interface RequestOptions {
  method?: string
  headers?: Record<string, string>
  body?: BodyInit | null
  /** 直连超时；中转默认更长 */
  timeout?: number
  /** 强制走中转（例如扫码 poll 必须由中转把 Set-Cookie 解析进响应体） */
  relayOnly?: boolean
}

function isRiskCode(code: unknown): boolean {
  return typeof code === 'number' && RISK_CODES.has(code)
}

/** 浏览器直连；CORS 失败、HTTP 非 2xx、风控码都返回 null */
async function directJson(bucket: Bucket, path: string, init: RequestOptions): Promise<any | null> {
  if (netStatus.directFailed) return null
  if ((init.method || 'GET').toUpperCase() !== 'GET') return null
  const t = timeoutSignal(init.timeout ?? 6000)
  try {
    const res = await fetch(`${HOSTS[bucket]}/${path}`, {
      method: 'GET',
      mode: 'cors',
      credentials: 'include',
      signal: t.signal,
      headers: { Accept: 'application/json, text/plain, */*', ...(init.headers || {}) },
    })
    if (!res.ok) return null
    const data = await res.json()
    if (data && typeof data === 'object' && 'code' in data && isRiskCode(data.code)) return null
    netStatus.mode = 'direct'
    netStatus.directFailed = false
    netStatus.lastError = ''
    return data
  } catch {
    // CORS / 网络错误 / 超时：本页面后续直接走中转，避免每次多等一轮
    netStatus.directFailed = true
    return null
  } finally {
    t.done()
  }
}

/** 走中转（或同域 /api 兜底） */
async function relayJson(
  bucket: Bucket,
  path: string,
  init: RequestOptions
): Promise<any | null> {
  const bases: string[] = []
  const relay = getRelayBase()
  if (relay) bases.push(relay)
  bases.push('') // 同域 /api：Cloudflare Pages 部署时可用

  const headers: Record<string, string> = { ...(init.headers || {}) }
  const cs = cookieString()
  if (cs) headers['x-bili-cookie'] = cs

  let lastErr = ''
  for (const base of bases) {
    const t = timeoutSignal(init.timeout ?? 18000)
    try {
      const res = await fetch(`${base}/api/${bucket}/${path}`, {
        method: init.method || 'GET',
        headers,
        body: init.body ?? null,
        signal: t.signal,
      })
      const text = await res.text()
      if (!res.ok) {
        lastErr = `HTTP ${res.status}`
        continue
      }
      let data: any
      try {
        data = JSON.parse(text)
      } catch {
        lastErr = '返回内容不是 JSON'
        continue
      }
      netStatus.mode = 'relay'
      netStatus.relay = base || location.origin
      netStatus.lastError = ''
      return data
    } catch (e) {
      lastErr = e instanceof Error ? e.message : String(e)
    } finally {
      t.done()
    }
  }
  netStatus.lastError = lastErr
  return null
}

/**
 * 请求 B 站 JSON。GET 直连优先 → 中转；POST 只走中转（需要带 Cookie）。
 * 拿不到任何通道时抛出带建议的 ApiError。
 */
export async function requestJson(
  bucket: Bucket,
  path: string,
  init: RequestOptions = {}
): Promise<any> {
  const method = (init.method || 'GET').toUpperCase()
  if (method === 'GET' && !init.relayOnly) {
    const direct = await directJson(bucket, path, init)
    if (direct) return direct
  }
  const relayed = await relayJson(bucket, path, init)
  if (relayed) return relayed

  const relay = getRelayBase()
  // 单次失败可能只是网络抖动（尤其扫码轮询），不要把全局模式永久打成 none；
  // 保留上一次成功通道，lastError 供界面提示。
  throw new ApiError(
    -502,
    relay
      ? `B 站接口暂不可用（直连被风控，中转 ${relay} 也失败）。可在设置 → 中转里换一个地址`
      : 'B 站接口暂不可用：浏览器直连被风控，且没有配置中转。到设置 → 中转填一个中转地址即可'
  )
}

/** 中转连通性测试：返回 true 表示 /api/live/... 可用 */
export async function probeRelay(): Promise<boolean> {
  const t = timeoutSignal(12000)
  try {
    const relay = getRelayBase()
    const bases = relay ? [relay] : []
    bases.push('')
    for (const base of bases) {
      try {
        const res = await fetch(`${base}/api/live/room/v1/Room/room_init?id=6`, {
          signal: t.signal,
          headers: { Accept: 'application/json' },
        })
        if (!res.ok) continue
        const j = await res.json()
        if (j?.code === 0) {
          netStatus.mode = 'relay'
          netStatus.relay = base || location.origin
          return true
        }
      } catch {
        /* next */
      }
    }
    return false
  } finally {
    t.done()
  }
}