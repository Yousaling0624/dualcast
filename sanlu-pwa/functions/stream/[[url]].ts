/**
 * 直播流代理：/stream/<encodeURIComponent(完整URL)>
 *
 * 浏览器里 mpegts.js / hls.js 都是 fetch 拉流，受 CORS 限制；B 站 CDN 不给
 * 跨域头，所以统一走这里。Worker 端没有 CORS 概念，带上 Referer 直接透传。
 * HLS 播放列表里的分段地址会被改写成代理地址，保证后续请求继续走本域。
 */

const REFERER = 'https://live.bilibili.com/'
const UA =
  'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36'

/** 允许代理的 CDN 后缀，防止被人当开放代理用 */
const ALLOWED_SUFFIX = [
  '.bilivideo.com',
  '.bilivideo.cn',
  '.hdslb.com',
  '.akamaized.net',
  '.bilibili.com',
  '.szbdyd.com',
]

function allowed(u: URL): boolean {
  const host = u.hostname.toLowerCase()
  return ALLOWED_SUFFIX.some((s) => host.endsWith(s))
}

function proxied(u: string, base: string): string {
  try {
    return '/stream/' + encodeURIComponent(new URL(u, base).toString())
  } catch {
    return u
  }
}

function rewritePlaylist(text: string, base: string): string {
  return text
    .split(/\r?\n/)
    .map((raw) => {
      const line = raw.trim()
      if (!line) return raw
      if (line.startsWith('#')) {
        return line.replace(/URI="([^"]+)"/g, (_, u: string) => `URI="${proxied(u, base)}"`)
      }
      return proxied(line, base)
    })
    .join('\n')
}

const jsonHeaders = {
  'content-type': 'application/json; charset=utf-8',
  'cache-control': 'no-store',
  'access-control-allow-origin': '*',
}

async function handle(request: Request): Promise<Response> {
  const url = new URL(request.url)
  const encoded = url.pathname.replace(/^\/stream\//, '')
  let target: URL
  try {
    target = new URL(decodeURIComponent(encoded))
  } catch {
    return new Response(JSON.stringify({ code: -400, message: 'bad target' }), {
      status: 400,
      headers: jsonHeaders,
    })
  }
  if (target.protocol !== 'https:' || !allowed(target)) {
    return new Response(JSON.stringify({ code: -403, message: 'host not allowed' }), {
      status: 403,
      headers: jsonHeaders,
    })
  }

  const headers = new Headers()
  headers.set('User-Agent', UA)
  headers.set('Referer', REFERER)
  headers.set('Origin', 'https://live.bilibili.com')
  const range = request.headers.get('range')
  if (range) headers.set('Range', range)

  let resp: Response
  try {
    resp = await fetch(target.toString(), { headers, redirect: 'follow' })
  } catch (e) {
    return new Response(JSON.stringify({ code: -502, message: `upstream error: ${e}` }), {
      status: 502,
      headers: jsonHeaders,
    })
  }

  const ct = resp.headers.get('content-type') || ''

  if (ct.includes('mpegurl') || target.pathname.endsWith('.m3u8')) {
    const text = await resp.text()
    return new Response(rewritePlaylist(text, target.toString()), {
      status: resp.status,
      headers: {
        'content-type': ct || 'application/vnd.apple.mpegurl',
        'cache-control': 'no-store',
        'access-control-allow-origin': '*',
      },
    })
  }

  const out = new Headers()
  out.set('content-type', ct)
  out.set('cache-control', 'no-store')
  out.set('access-control-allow-origin', '*')
  const len = resp.headers.get('content-length')
  if (len) out.set('content-length', len)
  const cr = resp.headers.get('content-range')
  if (cr) out.set('content-range', cr)
  out.set('accept-ranges', resp.headers.get('accept-ranges') || 'bytes')
  return new Response(resp.body, { status: resp.status, headers: out })
}

export const onRequestGet = ({ request }: { request: Request }) => handle(request)
export const onRequestOptions = async () =>
  new Response(null, {
    status: 204,
    headers: {
      'access-control-allow-origin': '*',
      'access-control-allow-headers': 'range,x-bili-cookie',
      'access-control-allow-methods': 'GET,OPTIONS',
    },
  })
