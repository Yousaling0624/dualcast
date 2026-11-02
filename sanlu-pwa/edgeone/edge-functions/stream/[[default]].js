/**
 * 三叠播 · 直播流兜底中转（EdgeOne Makers 边缘函数）
 *
 * /stream/<encodeURIComponent(完整URL)>
 * 浏览器直连 B 站 CDN 通常带 access-control-allow-origin: *；
 * 这里只作为 CORS 失败时的兜底，所以带宽占用很低。
 */

const REFERER = 'https://live.bilibili.com/'
const UA =
  'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36'

const ALLOWED_SUFFIX = [
  '.bilivideo.com',
  '.bilivideo.cn',
  '.hdslb.com',
  '.akamaized.net',
  '.bilibili.com',
  '.biliapi.net',
  '.szbdyd.com',
]

const CORS = {
  'access-control-allow-origin': '*',
  'access-control-allow-methods': 'GET,OPTIONS',
  'access-control-allow-headers': 'range,x-bili-cookie',
  'access-control-max-age': '86400',
}

function allowed(u) {
  const host = u.hostname.toLowerCase()
  return ALLOWED_SUFFIX.some((s) => host.endsWith(s))
}

function proxied(u, base) {
  try {
    return '/stream/' + encodeURIComponent(new URL(u, base).toString())
  } catch {
    return u
  }
}

function rewritePlaylist(text, base) {
  return text
    .split(/\r?\n/)
    .map((raw) => {
      const line = raw.trim()
      if (!line) return raw
      if (line.startsWith('#')) {
        return line.replace(/URI="([^"]+)"/g, (_, u) => `URI="${proxied(u, base)}"`)
      }
      return proxied(line, base)
    })
    .join('\n')
}

function json(obj, status = 200) {
  return new Response(JSON.stringify(obj), {
    status,
    headers: {
      'content-type': 'application/json; charset=utf-8',
      'cache-control': 'no-store',
      ...CORS,
    },
  })
}

export default async function onRequest(context) {
  const request = context.request
  if (request.method === 'OPTIONS') return new Response(null, { status: 204, headers: CORS })

  const url = new URL(request.url)
  const encoded = url.pathname.replace(/^\/stream\//, '')
  let target
  try {
    target = new URL(decodeURIComponent(encoded))
  } catch {
    return json({ code: -400, message: 'bad target' }, 400)
  }
  if (target.protocol !== 'https:' || !allowed(target)) {
    return json({ code: -403, message: 'host not allowed' }, 403)
  }

  const headers = new Headers()
  headers.set('User-Agent', UA)
  headers.set('Referer', REFERER)
  headers.set('Origin', 'https://live.bilibili.com')
  const range = request.headers.get('range')
  if (range) headers.set('Range', range)

  let resp
  try {
    resp = await fetch(target.toString(), { headers, redirect: 'follow' })
  } catch (e) {
    return json({ code: -502, message: `upstream error: ${e}` }, 502)
  }

  const ct = resp.headers.get('content-type') || ''
  if (ct.includes('mpegurl') || target.pathname.endsWith('.m3u8')) {
    const text = await resp.text()
    return new Response(rewritePlaylist(text, target.toString()), {
      status: resp.status,
      headers: {
        'content-type': ct || 'application/vnd.apple.mpegurl',
        'cache-control': 'no-store',
        ...CORS,
      },
    })
  }

  const out = new Headers(CORS)
  out.set('content-type', ct)
  out.set('cache-control', 'no-store')
  const len = resp.headers.get('content-length')
  if (len) out.set('content-length', len)
  const cr = resp.headers.get('content-range')
  if (cr) out.set('content-range', cr)
  out.set('accept-ranges', resp.headers.get('accept-ranges') || 'bytes')
  return new Response(resp.body, { status: resp.status, headers: out })
}
