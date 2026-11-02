/**
 * API 反向代理：绕过浏览器 CORS，并把登录态 cookie 转成 Cookie 头。
 *
 * 路由（[[path]] 兜底捕获）：
 *   /api/live/<path>      → https://api.live.bilibili.com/<path>
 *   /api/passport/<path>  → https://passport.bilibili.com/<path>
 *   /api/bili/<path>      → https://api.bilibili.com/<path>
 *
 * 双通道：
 *   1. 原生 fetch —— 快，正常情况用它；
 *   2. 原始 TLS socket（cloudflare:sockets）手写 HTTP/1.1 —— B 站的 -412/-352
 *      风控按 fetch 的 TLS/HTTP2 指纹识别，raw socket 指纹完全不同，同一出口 IP 放行。
 *      fetch 被风控（403/412/-352）时自动切换重试。
 *
 * 约定：
 * - 前端把 B 站 cookie 放在自定义头 `x-bili-cookie`（JS 不允许直接设置 Cookie 头）；
 * - passport 的扫码 poll 响应里的 Set-Cookie 会被解析成 JSON 塞回 body，
 *   否则前端 JS 读不到（浏览器禁止脚本读取 Set-Cookie）。
 */

import { connect } from 'cloudflare:sockets'

const UPSTREAM: Record<string, { host: string; referer: string }> = {
  live: { host: 'api.live.bilibili.com', referer: 'https://live.bilibili.com/' },
  passport: { host: 'passport.bilibili.com', referer: 'https://passport.bilibili.com/' },
  bili: { host: 'api.bilibili.com', referer: 'https://www.bilibili.com/' },
}

const UA =
  'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36'

/** B 站部分接口有风控，请求头补齐浏览器特征 */
function browserHeaders(extra: Headers): Headers {
  const h = new Headers(extra)
  h.set('sec-ch-ua', '"Chromium";v="131", "Not_A Brand";v="24"')
  h.set('sec-ch-ua-mobile', '?0')
  h.set('sec-ch-ua-platform', '"Windows"')
  h.set('sec-fetch-dest', 'empty')
  h.set('sec-fetch-mode', 'cors')
  h.set('sec-fetch-site', 'same-site')
  h.set('accept', 'application/json, text/plain, */*')
  h.set('accept-language', 'zh-CN,zh;q=0.9')
  return h
}

/* ================= raw TLS socket 通道 ================= */

interface RawResp {
  status: number
  headers: Record<string, string>
  setCookies: string[]
  body: Uint8Array
}

/** 手写 HTTP/1.1 over TLS，绕开 fetch 的指纹 */
async function rawHttp(
  host: string,
  method: string,
  pathQuery: string,
  extraHeaders: Record<string, string>,
  body?: Uint8Array
): Promise<RawResp> {
  const socket = connect(`${host}:443`, { secureTransport: 'on' })
  const w = socket.writable.getWriter()
  const lines = [
    `${method} ${pathQuery} HTTP/1.1`,
    `Host: ${host}`,
    `User-Agent: ${UA}`,
    'Accept: application/json, text/plain, */*',
    'Accept-Language: zh-CN,zh;q=0.9',
    'Accept-Encoding: identity',
    'Connection: close',
  ]
  for (const [k, v] of Object.entries(extraHeaders)) lines.push(`${k}: ${v}`)
  if (body) lines.push(`Content-Length: ${body.length}`)
  const req = new TextEncoder().encode(lines.join('\r\n') + '\r\n\r\n')
  await w.write(req)
  if (body) await w.write(body)

  const r = socket.readable.getReader()
  const chunks: Uint8Array[] = []
  let total = 0
  while (true) {
    const { done, value } = await r.read()
    if (done) break
    if (value) {
      chunks.push(value)
      total += value.length
    }
  }
  try {
    w.releaseLock()
  } catch {
    /* noop */
  }
  try {
    socket.close()
  } catch {
    /* noop */
  }

  const raw = new Uint8Array(total)
  let off = 0
  for (const c of chunks) {
    raw.set(c, off)
    off += c.length
  }

  // 按字节找 \r\n\r\n 头体边界
  let headerEnd = -1
  for (let i = 0; i < raw.length - 4; i++) {
    if (raw[i] === 13 && raw[i + 1] === 10 && raw[i + 2] === 13 && raw[i + 3] === 10) {
      headerEnd = i
      break
    }
  }
  if (headerEnd < 0) {
    throw new Error('malformed http response')
  }
  const headText = new TextDecoder('utf-8', { fatal: false }).decode(raw.subarray(0, headerEnd))
  const status = parseInt(headText.slice(9, headText.indexOf('\r\n')), 10) || 500

  const headers: Record<string, string> = {}
  const setCookies: string[] = []
  for (const line of headText.split('\r\n').slice(1)) {
    const c = line.indexOf(':')
    if (c <= 0) continue
    const k = line.slice(0, c).trim().toLowerCase()
    const v = line.slice(c + 1).trim()
    if (k === 'set-cookie') setCookies.push(v)
    else headers[k] = v
  }

  const rest = raw.subarray(headerEnd + 4)
  let bodyBytes: Uint8Array
  const te = (headers['transfer-encoding'] || '').toLowerCase()
  if (te.includes('chunked')) {
    const out: number[] = []
    let p = 0
    while (p < rest.length) {
      let nl = p
      while (nl < rest.length && rest[nl] !== 13) nl++
      const size = parseInt(new TextDecoder().decode(rest.subarray(p, nl)), 16)
      if (!size) break
      out.push(...rest.subarray(nl + 2, nl + 2 + size))
      p = nl + 2 + size + 2
    }
    bodyBytes = new Uint8Array(out)
  } else {
    bodyBytes = rest
  }
  const cl = Number(headers['content-length'])
  if (cl && bodyBytes.length > cl) bodyBytes = bodyBytes.subarray(0, cl)

  return { status, headers, setCookies, body: bodyBytes }
}

/** 用 raw socket 请求上游并解 gzip/deflate */
async function rawUpstream(
  host: string,
  method: string,
  pathQuery: string,
  extraHeaders: Record<string, string>,
  body?: Uint8Array
): Promise<RawResp | null> {
  try {
    const resp = await rawHttp(host, method, pathQuery, extraHeaders, body)
    const enc = (resp.headers['content-encoding'] || '').toLowerCase()
    if (enc.includes('gzip') || enc.includes('deflate')) {
      try {
        const ds = new DecompressionStream(enc.includes('gzip') ? 'gzip' : 'deflate')
        const stream = new Blob([resp.body.slice().buffer as ArrayBuffer]).stream().pipeThrough(ds)
        resp.body = new Uint8Array(await new Response(stream).arrayBuffer())
      } catch {
        /* 解压失败则原样返回 */
      }
    }
    return resp
  } catch {
    return null
  }
}

/** fetch 是否被 B 站风控拦截 */
async function isBanned(resp: Response | null): Promise<boolean> {
  if (!resp) return true
  if (resp.status === 403 || resp.status === 412) return true
  const ct = resp.headers.get('content-type') || ''
  if (ct.includes('application/json')) {
    const j = await resp.clone().json().catch(() => null)
    if (j && typeof j.code === 'number') {
      return j.code === -412 || j.code === -352 || j.code === 403
    }
  }
  return false
}

/** 同一 isolate 内记住 fetch 通道是否已被风控，被 ban 后直接走 raw socket */
let fetchBanned = false

/* ================= 业务逻辑 ================= */

/**
 * getDanmuInfo 需要 w_webid 风控参数：从直播间页面 HTML 里提取。
 * 页面抓取也走 raw socket（fetch 通道抓页面同样被风控）。
 */
const wWebIdCache = new Map<string, { id: string; at: number }>()

async function fetchWWebId(roomId: string, cookie: string): Promise<string> {
  const hit = wWebIdCache.get(roomId)
  if (hit && Date.now() - hit.at < 600_000) return hit.id
  let id = ''
  const resp = await rawUpstream('live.bilibili.com', 'GET', `/${roomId}?platform=web`, {
    Referer: 'https://www.bilibili.com/',
    Accept: 'text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8',
    ...(cookie ? { Cookie: cookie } : {}),
  })
  if (resp) {
    const html = new TextDecoder().decode(resp.body)
    const m = html.match(/"w_webid"\s*:\s*"([^"]+)"/) || html.match(/w_webid=([^&"']+)/)
    if (m) id = m[1]
  }
  wWebIdCache.set(roomId, { id, at: Date.now() })
  return id
}

function pickCookies(setCookies: string[]): Record<string, string> {
  const out: Record<string, string> = {}
  for (const line of setCookies) {
    const pair = line.split(';')[0]
    const eq = pair.indexOf('=')
    if (eq > 0) out[pair.slice(0, eq).trim()] = pair.slice(eq + 1).trim()
  }
  return out
}

const jsonHeaders: Record<string, string> = {
  'content-type': 'application/json; charset=utf-8',
  'cache-control': 'no-store',
  'access-control-allow-origin': '*',
}

interface UpstreamResult {
  status: number
  contentType: string
  body: Uint8Array
  setCookies: string[]
}

async function handle(request: Request): Promise<Response> {
  const url = new URL(request.url)
  const parts = url.pathname.replace(/^\/api\//, '').split('/')
  const bucket = parts.shift()
  const target = bucket ? UPSTREAM[bucket] : undefined
  if (!target) {
    return new Response(JSON.stringify({ code: -400, message: 'unknown api bucket' }), {
      status: 404,
      headers: jsonHeaders,
    })
  }
  const pathQuery = `/${parts.join('/')}${url.search}`
  const cookie = request.headers.get('x-bili-cookie') || ''
  const common: Record<string, string> = {
    Referer: target.referer,
    Origin: target.referer.replace(/\/$/, ''),
  }
  if (cookie) common.Cookie = cookie

  // POST body
  let postBody: Uint8Array | undefined
  if (request.method === 'POST') {
    const ab = await request.arrayBuffer()
    if (ab.byteLength) {
      postBody = new Uint8Array(ab)
      const ct = request.headers.get('content-type')
      if (ct) {
        common['Content-Type'] = ct
      }
    }
  }

  // ---------- 通道 1：原生 fetch（同实例内已知被风控就跳过，直接 raw） ----------
  const headers = browserHeaders(new Headers())
  headers.set('User-Agent', UA)
  headers.set('Referer', target.referer)
  headers.set('Origin', target.referer.replace(/\/$/, ''))
  if (cookie) headers.set('Cookie', cookie)
  if (postBody && common['Content-Type']) headers.set('Content-Type', common['Content-Type'])
  const init: RequestInit = { method: request.method, headers, redirect: 'follow' }
  if (postBody) init.body = postBody.slice().buffer as ArrayBuffer

  let fetchResp: Response | null = null
  if (!fetchBanned) {
    try {
      fetchResp = await fetch(`https://${target.host}/${pathQuery}`, init)
    } catch {
      fetchResp = null
    }
  }

  let result: UpstreamResult | null = null

  if (fetchResp && !(await isBanned(fetchResp))) {
    const setCookies =
      typeof fetchResp.headers.getSetCookie === 'function' ? fetchResp.headers.getSetCookie() : []
    result = {
      status: fetchResp.status,
      contentType: fetchResp.headers.get('content-type') || 'application/json',
      body: new Uint8Array(await fetchResp.arrayBuffer()),
      setCookies,
    }
  } else {
    // ---------- 通道 2：raw socket（绕风控指纹） ----------
    if (fetchResp) fetchBanned = true // fetch 在但被风控，记住，后续直接 raw
    const raw = await rawUpstream(target.host, request.method, pathQuery, common, postBody)
    if (raw && raw.status < 500) {
      result = {
        status: raw.status,
        contentType: raw.headers['content-type'] || 'application/json',
        body: raw.body,
        setCookies: raw.setCookies,
      }
    } else if (fetchResp) {
      // raw 也失败，退回 fetch 原始响应
      const setCookies =
        typeof fetchResp.headers.getSetCookie === 'function' ? fetchResp.headers.getSetCookie() : []
      result = {
        status: fetchResp.status,
        contentType: fetchResp.headers.get('content-type') || 'application/json',
        body: new Uint8Array(await fetchResp.arrayBuffer()),
        setCookies,
      }
    } else {
      return new Response(JSON.stringify({ code: -502, message: 'upstream unavailable' }), {
        status: 502,
        headers: jsonHeaders,
      })
    }
  }

  // getDanmuInfo 风控（-352）：抓 w_webid 重试（raw 通道）
  if (bucket === 'live' && pathQuery.includes('getDanmuInfo')) {
    const text = new TextDecoder().decode(result.body)
    if (/"code"\s*:\s*-352/.test(text)) {
      const roomId = url.searchParams.get('id') || ''
      const wWebId = await fetchWWebId(roomId, cookie)
      if (wWebId) {
        const sep = pathQuery.includes('?') ? '&' : '?'
        const raw = await rawUpstream(
          target.host,
          request.method,
          `${pathQuery}${sep}w_webid=${encodeURIComponent(wWebId)}`,
          common,
          postBody
        )
        if (raw && !/"code"\s*:\s*-352/.test(new TextDecoder().decode(raw.body))) {
          result = {
            status: raw.status,
            contentType: raw.headers['content-type'] || 'application/json',
            body: raw.body,
            setCookies: raw.setCookies,
          }
        }
      }
    }
  }

  // 扫码登录 poll：把 Set-Cookie 解析进 body（前端才能拿到登录态）
  if (bucket === 'passport' && result.contentType.includes('application/json')) {
    const cookies = pickCookies(result.setCookies)
    const text = new TextDecoder().decode(result.body)
    try {
      const body = JSON.parse(text)
      if (Object.keys(cookies).length) {
        body.cookies = cookies
        body.cookie_string = Object.entries(cookies)
          .map(([k, v]) => `${k}=${v}`)
          .join('; ')
      }
      return new Response(JSON.stringify(body), { status: result.status, headers: jsonHeaders })
    } catch {
      /* 非 JSON，原样透传 */
    }
  }

  const out = new Headers()
  out.set('content-type', result.contentType)
  out.set('cache-control', 'no-store')
  out.set('access-control-allow-origin', '*')
  return new Response(result.body.slice().buffer as ArrayBuffer, {
    status: result.status,
    headers: out,
  })
}

export const onRequestGet = ({ request }: { request: Request }) => handle(request)
export const onRequestPost = ({ request }: { request: Request }) => handle(request)
export const onRequestOptions = async () =>
  new Response(null, {
    status: 204,
    headers: {
      'access-control-allow-origin': '*',
      'access-control-allow-headers': 'content-type,x-bili-cookie',
      'access-control-allow-methods': 'GET,POST,OPTIONS',
    },
  })
