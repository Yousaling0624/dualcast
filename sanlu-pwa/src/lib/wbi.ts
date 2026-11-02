/**
 * B 站 WBI 签名。
 *
 * 2023 年后 getRoomPlayInfo / getDanmuInfo / msg/send 等接口要求带 wts + w_rid，
 * 否则返回 -352。算法参考 bilibili-API-collect 与 PiliPlus：
 *   w_rid = md5(按 key 排序的 query + mixinKey)
 * mixinKey 由 nav 接口下发的 img_url / sub_url 经固定表重排后取前 32 位。
 */
import { md5 } from './md5'
import { requestJson } from './net'

/** B 站前端写死的重排表（64 位） */
const MIXIN_TAB = [
  46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35, 27, 43, 5, 49, 33, 9, 42, 19, 29,
  28, 14, 39, 12, 38, 41, 13, 37, 48, 7, 16, 24, 55, 40, 61, 26, 17, 0, 1, 60, 51, 30, 4, 22, 25,
  54, 21, 56, 59, 6, 63, 57, 62, 11, 36, 20, 34, 44, 52,
]

const CACHE_KEY = 'dc_wbi_mixin'
const TTL = 6 * 3600 * 1000

let inflight: Promise<string> | null = null

function fromCache(): string {
  try {
    const raw = localStorage.getItem(CACHE_KEY)
    if (!raw) return ''
    const j = JSON.parse(raw) as { key?: string; at?: number }
    if (j.key && j.at && Date.now() - j.at < TTL) return j.key
  } catch {
    /* ignore */
  }
  return ''
}

function toCache(key: string) {
  try {
    localStorage.setItem(CACHE_KEY, JSON.stringify({ key, at: Date.now() }))
  } catch {
    /* ignore */
  }
}

export function mixinKey(orig: string): string {
  return MIXIN_TAB.map((i) => orig[i] || '').join('').slice(0, 32)
}

async function fetchMixinKey(): Promise<string> {
  const cached = fromCache()
  if (cached) return cached
  if (inflight) return inflight
  inflight = (async () => {
    try {
      const j = await requestJson('bili', 'x/web-interface/nav')
      const img = String(j?.data?.wbi_img?.img_url || '')
      const sub = String(j?.data?.wbi_img?.sub_url || '')
      const name = (u: string) => u.split('/').pop()!.split('.')[0] || ''
      const raw = name(img) + name(sub)
      if (raw.length < 64) return ''
      const key = mixinKey(raw)
      toCache(key)
      return key
    } catch {
      return ''
    } finally {
      inflight = null
    }
  })()
  return inflight
}

/** 清缓存（比如切换账号、出现 -352 时） */
export function clearWbiCache() {
  try {
    localStorage.removeItem(CACHE_KEY)
  } catch {
    /* ignore */
  }
}

function scrub(value: string): string {
  // B 站会先过滤这几个字符再参与签名
  return value.replace(/[!'()*]/g, '')
}

function encode(v: string): string {
  return encodeURIComponent(v)
}

/** 把参数签名后拼成可直接拼到 URL 后面的 query string */
export async function signParams(params: Record<string, string | number | undefined>): Promise<string> {
  const key = await fetchMixinKey()
  const all: Record<string, string> = {}
  for (const [k, v] of Object.entries(params)) {
    if (v !== undefined && v !== null && v !== '') all[k] = String(v)
  }
  all.wts = String(Math.floor(Date.now() / 1000))
  const query = Object.keys(all)
    .sort()
    .map((k) => `${encode(k)}=${encode(scrub(all[k]))}`)
    .join('&')
  return key ? `${query}&w_rid=${md5(query + key)}` : query
}

/** 给已经拼好 query 的 URL 补签名（path 里不能有 #） */
export async function signPath(path: string): Promise<string> {
  const q = path.indexOf('?')
  if (q < 0) return path
  const base = path.slice(0, q)
  const params: Record<string, string> = {}
  for (const pair of path.slice(q + 1).split('&')) {
    if (!pair) continue
    const eq = pair.indexOf('=')
    const k = eq < 0 ? pair : pair.slice(0, eq)
    const v = eq < 0 ? '' : pair.slice(eq + 1)
    try {
      params[decodeURIComponent(k)] = decodeURIComponent(v)
    } catch {
      params[k] = v
    }
  }
  return `${base}?${await signParams(params)}`
}