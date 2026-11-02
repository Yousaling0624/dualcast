# 三叠播 PWA（sanlu）

把两个 B 站直播间叠在一起看的网页版：一路出画面，一路出弹幕和声音。适配 PC / iOS / 鸿蒙浏览器，可添加到主屏幕（PWA）。

- 线上地址：https://sanlu.yousaling.dpdns.org
- 托管：Cloudflare Pages（项目名 `sanlu`），自定义域 `sanlu.yousaling.dpdns.org`，DNS CNAME 指向 `sanlu.pages.dev`
- 技术栈：Vite + Vue 3 + TypeScript，mpegts.js（flv）/ hls.js，Canvas 弹幕，Cloudflare Pages Functions

## 目录结构

```
src/
  components/  HomeView / LiveView / ChatPanel / DanmakuCanvas / SettingsDrawer
  lib/
    bili.ts      B 站 API（双通道：浏览器直连 → 同域代理）
    danmaku.ts   弹幕 WebSocket 协议（op=7 认证 / op=2 心跳 / protover 3 brotli）
    player.ts    双路播放器（mpegts.js flv 优先，hls.js 回退）
    store.ts     全局状态与会话编排
functions/
  api/[[path]].ts    API 反向代理（双通道：fetch + 原始 TLS socket）
  stream/[[url]].ts  直播流代理（兜底用）
  rawtest.ts         调试端点：验证 raw socket 对 B 站的可用性
public/           manifest、sw.js、icons
deno/proxy.ts    可选的 Deno Deploy 中转脚本（本仓库暂不需要）
```

## 核心难点：B 站风控三重封锁与破解

这三个结论都是实测得出的，改动前务必了解：

| 通道 | 结果 |
|---|---|
| 浏览器直连 B 站 API | ❌ B 站按 **Origin 白名单**拒绝：Origin 非 `*.bilibili.com` 的请求在网关层直接 403（浏览器无法伪造 Origin，纯前端无解） |
| Cloudflare `fetch()` 出口 | ❌ B 站按 **TLS/HTTP2 指纹**返回 -412 `request was banned`（带完整 buvid cookie 也无效，10 次全挂） |
| 原始 TLS socket（`cloudflare:sockets`）手写 HTTP/1.1 | ✅ **同一出口 IP 直接 200** —— 指纹不同，风控放行 |

因此 `functions/api/[[path]].ts` 采用双通道：先 `fetch()`，被风控（403/412/-352）时自动改用 raw socket 重试；同一 isolate 内记住 fetch 已 ban，后续请求直接走 raw。
`getDanmuInfo` 风控（-352）时还会去直播间页面抓 `w_webid` 再试一次（页面抓取同样走 raw 通道）。

## 当前状态（交接时请注意）

- `room_init` / `getInfoByRoom`：**可用**。注意 `getInfoByRoom` 需要带 buvid cookie（`buvid3`/`buvid4`/`b_nut`），匿名请求会 -352；前端启动时会调 `x/frontend/finger/spi` 拿 buvid 存进 localStorage，之后所有代理请求都会带上。
- `getDanmuInfo`：仍可能 -352（B 站对数据中心出口的弹幕 token 风控更严）。前端已做兜底：全通道失败时用默认 `broadcastlv.chat.bilibili.com` + 空 token 裸连（部分房间接受）。**这是目前最值得继续攻的点。**
- 直播流：浏览器直连 B 站 CDN（页面 `<meta name="referrer" content="no-referrer">`，B 站 CDN 放行无 Referer 请求）；`/stream` 同域代理只作直连全挂后的兜底。**尚未在开播状态下充分验证。**
- 弹幕 WebSocket：浏览器直连 `wss://broadcastlv.chat.bilibili.com/sub`，不受 CORS 限制，正常。

## 本地开发 / 部署

```bash
npm install
npm run dev
npm run build                     # 输出 dist/
export CLOUDFLARE_API_TOKEN=xxx   # 权限：Pages 编辑 + 帐户设置读取 + 区域读取 + DNS 编辑
bash deploy.sh                    # 构建 + 部署 + 绑定自定义域 + 配 DNS（一键）
```

`deploy.sh` 里的域名是 `sanlu.yousaling.dpdns.org`，换域名改脚本顶部两个变量即可。

## 交接建议（给下一个接手的人）

1. 优先解决 `getDanmuInfo` 的 -352：可尝试给 raw 通道带上更完整的浏览器头（含 `LIVE_BUVID`、`b_3` 等）、或改用 B 站开放平台的 token 接口。
2. 验证直播流直连：找正在开播的房间，确认 mpegts.js / hls.js 直连 B 站 CDN 不被 Referer / Origin 拦截。
3. 双通道逻辑集中在 `functions/api/[[path]].ts`（`isBanned` + `rawUpstream`），不要轻易改成单通道。
4. Cloudflare 侧：账号里已有 Pages 项目 `sanlu`、zone `yousaling.dpdns.org`、`lovehgr.dpdns.org`。
