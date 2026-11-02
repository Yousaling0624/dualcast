/**
 * 三叠播 · B 站 API 中转（部署到 Deno Deploy，免费）
 *
 * 作用：B 站封了 Cloudflare 的出口 IP、拒绝非 bilibili.com 的浏览器直连，
 * 但不封普通服务器请求。Deno Deploy 的出口 IP 干净，做一层轻量转发即可。
 *
 * 部署步骤（1 分钟）：
 *   1. 打开 https://dash.deno.com → 用 GitHub 账号登录（免费，无需信用卡）
 *   2. 点 New Playground → 清空默认代码，粘贴本文件全部内容
 *   3. 点 Save（或 Ctrl+S）自动部署，右上角得到地址 https://你的名字.deno.dev
 *   4. 打开叠播网页 → 设置 → 中转，填入 https://你的名字.deno.dev → 保存
 *   5. 验证：浏览器访问 https://你的名字.deno.dev/api/live/room/v1/Room/room_init?id=6
 *      看到 {"code":0,...} 即部署成功
 *
 * 端点：/api/{live|passport|bili}/<路径>?<参数>
 *   请求头 x-bili-cookie 会被转成上游 Cookie（登录态中转）。
 *   免费额度：每月 100 万次请求，对弹幕/房间信息这种小请求绰绰有余。
 */

const HOSTS: Record<string, string> = {
  live: 'https://api.live.bilibili.com',
  passport: 'https://passport.bilibili.com',
  bili: 'https://api.bilibili.com',
}

const UA =
  'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36'

const CORS: Record<string, string> = {
  'access-control-allow-origin': '*',
  'access-control-allow-methods': 'GET,POST,OPTIONS',
  'access-control-allow-headers': 'content-type,x-bili-cookie',
  'access-control-max-age': '86400',
}

const json = (obj: unknown, status = 200) =>
  new Response(JSON.stringify(obj), {
    status,
    headers: {
      'content-type': 'application/json; charset=utf-8',
      'cache-control': 'no-store',
      ...CORS,
    },
  })

/** getDanmuInfo 风控（-352）时，从直播间页面提取 w_webid */
async function fetchWWebId(roomId: string, cookie: string): Promise<string> {
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

Deno.serve(async (req) => {
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
  if (cookie) h.set('Cookie', cookie)
  if (req.method === 'POST') {
    const ct = req.headers.get('content-type')
    if (ct) h.set('Content-Type', ct)
  }

  const init: RequestInit = { method: req.method, headers: h, redirect: 'follow' }
  if (req.method !== 'GET' && req.method !== 'HEAD') {
    init.body = await req.arrayBuffer()
  }

  let resp: Response
  try {
    resp = await fetch(target, init)
  } catch (e) {
    return json({ code: -502, message: `upstream error: ${e}` }, 502)
  }

  // 弹幕 token 风控：抓 w_webid 重试一次
  if (bucket === 'live' && path.includes('getDanmuInfo')) {
    try {
      const first = await resp.clone().json()
      if (first?.code === -352) {
        const roomId = u.searchParams.get('id') || ''
        const w = await fetchWWebId(roomId, cookie)
        if (w) {
          const t2 = new URL(target)
          t2.searchParams.set('w_webid', w)
          const retry = await fetch(t2.toString(), init)
          if ((await retry.clone().json().catch(() => ({ code: -1 }))).code === 0) {
            resp = retry
          }
        }
      }
    } catch {
      /* 保持原响应 */
    }
  }

  // 扫码登录 poll：把 Set-Cookie 解析进响应体（浏览器脚本读不到 Set-Cookie）
  if (bucket === 'passport' && (resp.headers.get('content-type') || '').includes('application/json')) {
    const body = await resp.json()
    const setCookies =
      typeof resp.headers.getSetCookie === 'function' ? resp.headers.getSetCookie() : []
    const cookies: Record<string, string> = {}
    for (const line of setCookies) {
      const pair = line.split(';')[0]
      const eq = pair.indexOf('=')
      if (eq > 0) cookies[pair.slice(0, eq).trim()] = pair.slice(eq + 1).trim()
    }
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

  const out = new Headers(CORS)
  out.set('content-type', resp.headers.get('content-type') || 'application/json; charset=utf-8')
  out.set('cache-control', 'no-store')
  return new Response(resp.body, { status: resp.status, headers: out })
})
