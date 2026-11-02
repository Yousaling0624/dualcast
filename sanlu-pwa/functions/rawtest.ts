/**
 * 实验端点：用 CF Worker 的原始 TCP/TLS socket（cloudflare:sockets）
 * 手写 HTTP/1.1 请求 B 站，绕过 fetch 的 TLS/HTTP2 指纹。
 * 若 B 站 -412 是指纹判定而非纯 IP 封禁，这条路能通。
 */
import { connect } from 'cloudflare:sockets'

const UA =
  'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36'

async function rawGet(host: string, path: string): Promise<{ status: string; head: string; body: string }> {
  const socket = connect(`${host}:443`, { secureTransport: 'on' })
  const w = socket.writable.getWriter()
  const req =
    `GET ${path} HTTP/1.1\r\n` +
    `Host: ${host}\r\n` +
    `User-Agent: ${UA}\r\n` +
    `Accept: application/json, text/plain, */*\r\n` +
    `Accept-Language: zh-CN,zh;q=0.9\r\n` +
    `Referer: https://live.bilibili.com/\r\n` +
    `Connection: close\r\n` +
    `\r\n`
  await w.write(new TextEncoder().encode(req))
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
    await w.close()
  } catch {
    /* noop */
  }
  const out = new Uint8Array(total)
  let off = 0
  for (const c of chunks) {
    out.set(c, off)
    off += c.length
  }
  const text = new TextDecoder().decode(out)
  const sep = text.indexOf('\r\n\r\n')
  const status = text.slice(0, text.indexOf('\r\n'))
  const head = text.slice(0, sep)
  let body = text.slice(sep + 4)
  // 简单处理 chunked：去掉分块长度行
  if (/transfer-encoding:\s*chunked/i.test(head)) {
    body = body.replace(/^[0-9a-f]+\r\n/igm, '').replace(/\r\n$/g, '')
  }
  return { status, head, body }
}

export const onRequestGet = async () => {
  try {
    const r = await rawGet('api.live.bilibili.com', '/room/v1/Room/room_init?id=6')
    return new Response(JSON.stringify({ status: r.status, head: r.head.slice(0, 400), body: r.body.slice(0, 300) }), {
      headers: { 'content-type': 'application/json; charset=utf-8' },
    })
  } catch (e) {
    return new Response('ERR: ' + String(e), { status: 500 })
  }
}
