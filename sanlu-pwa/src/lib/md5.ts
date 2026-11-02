/**
 * 纯 JS MD5 —— 只用在一个地方：B 站 WBI 签名的 w_rid。
 *
 * Web Crypto 不提供 MD5（规范有意不支持），而 WBI 又必须用 MD5，
 * 所以自带一份同步实现。输入按 UTF-8 编码，输出 32 位小写十六进制。
 */

function cmn(q: number, a: number, b: number, x: number, s: number, t: number): number {
  a = (a + q + x + t) | 0
  return (((a << s) | (a >>> (32 - s))) + b) | 0
}

function ff(a: number, b: number, c: number, d: number, x: number, s: number, t: number): number {
  return cmn((b & c) | (~b & d), a, b, x, s, t)
}

function gg(a: number, b: number, c: number, d: number, x: number, s: number, t: number): number {
  return cmn((b & d) | (c & ~d), a, b, x, s, t)
}

function hh(a: number, b: number, c: number, d: number, x: number, s: number, t: number): number {
  return cmn(b ^ c ^ d, a, b, x, s, t)
}

function ii(a: number, b: number, c: number, d: number, x: number, s: number, t: number): number {
  return cmn(c ^ (b | ~d), a, b, x, s, t)
}

export function md5(input: string): string {
  const bytes = new TextEncoder().encode(input)
  const padded = new Uint8Array((((bytes.length + 8) >>> 6) + 1) << 6)
  padded.set(bytes)
  padded[bytes.length] = 0x80

  const view = new DataView(padded.buffer)
  // 64 位小端 bit length（字符串长度远小于 2^53，低 32 位足够，高位写 0）
  view.setUint32(padded.length - 8, (bytes.length << 3) >>> 0, true)
  view.setUint32(padded.length - 4, Math.floor(bytes.length / 0x20000000), true)

  let a0 = 0x67452301
  let b0 = 0xefcdab89
  let c0 = 0x98badcfe
  let d0 = 0x10325476

  const x = new Int32Array(16)
  for (let off = 0; off < padded.length; off += 64) {
    for (let i = 0; i < 16; i++) x[i] = view.getInt32(off + i * 4, true)

    let a = a0
    let b = b0
    let c = c0
    let d = d0

    a = ff(a, b, c, d, x[0], 7, -680876936)
    d = ff(d, a, b, c, x[1], 12, -389564586)
    c = ff(c, d, a, b, x[2], 17, 606105819)
    b = ff(b, c, d, a, x[3], 22, -1044525330)
    a = ff(a, b, c, d, x[4], 7, -176418897)
    d = ff(d, a, b, c, x[5], 12, 1200080426)
    c = ff(c, d, a, b, x[6], 17, -1473231341)
    b = ff(b, c, d, a, x[7], 22, -45705983)
    a = ff(a, b, c, d, x[8], 7, 1770035416)
    d = ff(d, a, b, c, x[9], 12, -1958414417)
    c = ff(c, d, a, b, x[10], 17, -42063)
    b = ff(b, c, d, a, x[11], 22, -1990404162)
    a = ff(a, b, c, d, x[12], 7, 1804603682)
    d = ff(d, a, b, c, x[13], 12, -40341101)
    c = ff(c, d, a, b, x[14], 17, -1502002290)
    b = ff(b, c, d, a, x[15], 22, 1236535329)

    a = gg(a, b, c, d, x[1], 5, -165796510)
    d = gg(d, a, b, c, x[6], 9, -1069501632)
    c = gg(c, d, a, b, x[11], 14, 643717713)
    b = gg(b, c, d, a, x[0], 20, -373897302)
    a = gg(a, b, c, d, x[5], 5, -701558691)
    d = gg(d, a, b, c, x[10], 9, 38016083)
    c = gg(c, d, a, b, x[15], 14, -660478335)
    b = gg(b, c, d, a, x[4], 20, -405537848)
    a = gg(a, b, c, d, x[9], 5, 568446438)
    d = gg(d, a, b, c, x[14], 9, -1019803690)
    c = gg(c, d, a, b, x[3], 14, -187363961)
    b = gg(b, c, d, a, x[8], 20, 1163531501)
    a = gg(a, b, c, d, x[13], 5, -1444681467)
    d = gg(d, a, b, c, x[2], 9, -51403784)
    c = gg(c, d, a, b, x[7], 14, 1735328473)
    b = gg(b, c, d, a, x[12], 20, -1926607734)

    a = hh(a, b, c, d, x[5], 4, -378558)
    d = hh(d, a, b, c, x[8], 11, -2022574463)
    c = hh(c, d, a, b, x[11], 16, 1839030562)
    b = hh(b, c, d, a, x[14], 23, -35309556)
    a = hh(a, b, c, d, x[1], 4, -1530992060)
    d = hh(d, a, b, c, x[4], 11, 1272893353)
    c = hh(c, d, a, b, x[7], 16, -155497632)
    b = hh(b, c, d, a, x[10], 23, -1094730640)
    a = hh(a, b, c, d, x[13], 4, 681279174)
    d = hh(d, a, b, c, x[0], 11, -358537222)
    c = hh(c, d, a, b, x[3], 16, -722521979)
    b = hh(b, c, d, a, x[6], 23, 76029189)
    a = hh(a, b, c, d, x[9], 4, -640364487)
    d = hh(d, a, b, c, x[12], 11, -421815835)
    c = hh(c, d, a, b, x[15], 16, 530742520)
    b = hh(b, c, d, a, x[2], 23, -995338651)

    a = ii(a, b, c, d, x[0], 6, -198630844)
    d = ii(d, a, b, c, x[7], 10, 1126891415)
    c = ii(c, d, a, b, x[14], 15, -1416354905)
    b = ii(b, c, d, a, x[5], 21, -57434055)
    a = ii(a, b, c, d, x[12], 6, 1700485571)
    d = ii(d, a, b, c, x[3], 10, -1894986606)
    c = ii(c, d, a, b, x[10], 15, -1051523)
    b = ii(b, c, d, a, x[1], 21, -2054922799)
    a = ii(a, b, c, d, x[8], 6, 1873313359)
    d = ii(d, a, b, c, x[15], 10, -30611744)
    c = ii(c, d, a, b, x[6], 15, -1560198380)
    b = ii(b, c, d, a, x[13], 21, 1309151649)
    a = ii(a, b, c, d, x[4], 6, -145523070)
    d = ii(d, a, b, c, x[11], 10, -1120210379)
    c = ii(c, d, a, b, x[2], 15, 718787259)
    b = ii(b, c, d, a, x[9], 21, -343485551)

    a0 = (a0 + a) | 0
    b0 = (b0 + b) | 0
    c0 = (c0 + c) | 0
    d0 = (d0 + d) | 0
  }

  const out = new Uint8Array(16)
  const outView = new DataView(out.buffer)
  outView.setInt32(0, a0, true)
  outView.setInt32(4, b0, true)
  outView.setInt32(8, c0, true)
  outView.setInt32(12, d0, true)
  let hex = ''
  for (const byte of out) hex += byte.toString(16).padStart(2, '0')
  return hex
}