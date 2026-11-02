/**
 * 三叠播 · B 站 API 中转（EdgeOne Makers 边缘函数）
 *
 * 端点：/api/{live|passport|bili}/<路径>?<参数>
 * - 请求头 x-bili-cookie 会转成上游 Cookie（登录态中转）；
 * - passport 扫码 poll 的 Set-Cookie 会被解析进响应体（浏览器读不到 Set-Cookie）；
 * - getDanmuInfo 风控 -352 时抓 w_webid 重试一次。
 *
 * 部署：见 sanlu-pwa/EDGEONE.md
 */

const HOSTS = {
  live: 'https://api.live.bilibili.com',
  passport: 'https://passport.bilibili.com',
  bili: 'https://api.bilibili.com',
}

const UA =
  'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36'

const CORS = {
  'access-control-allow-origin': '*',
  'access-control-allow-methods': 'GET,POST,OPTIONS',
  'access-control-allow-headers': 'content-type,x-bili-cookie',
  'access-control-max-age': '86400',
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

/** getDanmuInfo 风控（-352）时，从直播间页面提取 w_webid */
async function fetchWWebId(roomId, cookie) {
  try {
    const resp = await fetch(`https://live.bilibili.com/${roomId}?platform=web`, {
      headers: {
        'User-Agent': UA,
        Referer: 'https://www.bilibili.com/',
        ...(cookie ? { Cookie: cookie } : {}),
      },
      redirect: 'follow',
    })
    const html = await resp.text()
    const m = html.match(/"w_webid"\s*:\s*"([^"]+)"/) || html.match(/w_webid=([^&"']+)/)
    return m ? m[1] : ''
  } catch {
    return ''
  }
}

/** 兼容有/没有 getSetCookie 的运行时 */
function readSetCookies(headers) {
  if (typeof headers.getSetCookie === 'function') return headers.getSetCookie()
  const raw = headers.get('set-cookie') || ''
  if (!raw) return []
  // Expires 里也有逗号，只在“逗号 + 下一个 cookie 名=”处切
  return raw.split(/,\s*(?=[^;,=\s]+=)/)
}

function parseCookies(lines) {
  const out = {}
  for (const line of lines) {
    const pair = String(line).split(';')[0]
    const eq = pair.indexOf('=')
    if (eq > 0) out[pair.slice(0, eq).trim()] = pair.slice(eq + 1).trim()
  }
  return out
}

export default async function onRequest(context) {
  const req = context.request
  if (req.method === 'OPTIONS') return new Response(null, { status: 204, headers: CORS })

  const u = new URL(req.url)
  const m = u.pathname.match(/^\/api\/(live|passport|bili)\/(.*)$/)
  if (!m) return json({ code: -400, message: '路径应为 /api/{live|passport|bili}/...' }, 404)

  const bucket = m[1]
  const path = m[2]
  const target = `${HOSTS[bucket]}/${path}${u.search}`
  const cookie = req.headers.get('x-bili-cookie') || ''

  const h = new Headers()
  h.set('User-Agent', UA)
  h.set('Referer', 'https://live.bilibili.com/')
  h.set('Origin', 'https://live.bilibili.com')
  h.set('Accept', 'application/json, text/plain, */*')
  h.set('Accept-Language', 'zh-CN,zh;q=0.9')
  if (cookie) h.set('Cookie', cookie)
  if (req.method === 'POST') {
    const ct = req.headers.get('content-type')
    if (ct) h.set('Content-Type', ct)
  }

  const init = { method: req.method, headers: h, redirect: 'follow' }
  if (req.method !== 'GET' && req.method !== 'HEAD') {
    init.body = await req.arrayBuffer()
  }

  let resp
  try {
    resp = await fetch(target, init)
  } catch (e) {
    return json({ code: -502, message: `upstream error: ${e}` }, 502)
  }

  // 弹幕 token 风控：抓 w_webid 重试一次
  if (bucket === 'live' && path.includes('getDanmuInfo')) {
    try {
      const first = await resp.clone().json()
      if (first && first.code === -352) {
        const roomId = u.searchParams.get('id') || ''
        const w = await fetchWWebId(roomId, cookie)
        if (w) {
          const t2 = new URL(target)
          t2.searchParams.set('w_webid', w)
          const retry = await fetch(t2.toString(), init)
          const j = await retry.clone().json().catch(() => ({ code: -1 }))
          if (j && j.code === 0) resp = retry
        }
      }
    } catch {
      /* 保持原响应 */
    }
  }

  // 扫码登录 poll：把 Set-Cookie 解析进响应体
  const ct = resp.headers.get('content-type') || ''
  if (bucket === 'passport' && ct.includes('application/json')) {
    let body
    try {
      body = await resp.json()
    } catch {
      body = null
    }
    if (body && typeof body === 'object') {
      const cookies = parseCookies(readSetCookies(resp.headers))
      if (Object.keys(cookies).length) {
        body.cookies = cookies
        body.cookie_string = Object.entries(cookies)
          .map(([k, v]) => `${k}=${v}`)
          .join('; ')
      }
      return new Response(JSON.stringify(body), {
        status: resp.status,
        headers: {
          'content-type': 'application/json; charset=utf-8',
          'cache-control': 'no-store',
          ...CORS,
        },
      })
    }
  }

  const out = new Headers(CORS)
  out.set('content-type', ct || 'application/json; charset=utf-8')
  out.set('cache-control', 'no-store')
  return new Response(resp.body, { status: resp.status, headers: out })
}
