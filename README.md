# 三叠播 / DualCast

把两个 B 站直播间叠在一起看：**A 房出弹幕和声音，B 房出画面**。适合「主播能解说、但没有比赛画面版权」的场景 —— 主播开 A 房陪大家看球，B 房找一路比赛画面，再把 A 房的弹幕盖在 B 房上。

| 子项目 | 说明 | 地址 |
|---|---|---|
| [`sanlu-pwa/`](./sanlu-pwa/README.md) | **网页版 PWA**（PC / iOS / Android / 鸿蒙浏览器） | [GitHub Pages](https://yousaling0624.github.io/dualcast/) · [Cloudflare](https://sanlu.yousaling.dpdns.org) |
| [`DualLive/`](./DualLive/README.md) | **Android 原生版**（Jetpack Compose，ExoPlayer） | 本地构建 APK |

## 网页版功能

- 两个直播间叠加播放，A/B 一键互换
- A 房弹幕 Canvas 渲染：字号 / 速度 / 透明度 / 显示区域 / 描边样式 / 屏蔽词
- **多路弹幕**：导入多个直播间同时显示，用颜色 / 主播首字 / 自定义字气泡区分；导入房间可单独加入解说混音
- **斗鱼式互动**：点聊天室或画面上的弹幕即可 `+1`、`回复`、`举报`；鼠标悬停也能冻结弹幕并弹出快捷条；回复自动拼成 `内容@昵称:原话`（B 站 20 字限制内），带发送冷却、失败原因、自己弹幕回显去重
- B 站扫码登录（也可手动粘贴 Cookie），发弹幕 / 收礼物 / SC / 舰长消息
- **SC 悬浮窗**：画面左上角显示醒目留言卡片（头像、金额、留言、倒计时），全屏时同样可见
- 混音台：A/B 独立音量、只听一路、A 相对 B 的 ±5 秒音画偏移；按房间组合记忆
- 延迟校准：加延迟时暂停对应一路补齐缓冲，减延迟时向前 seek 追赶，小偏差用倍速平滑修正
- Material Design 3 Expressive + Claude 风格暖纸色界面
- A 解说小窗：可拖动、四角等比缩放、全屏后框选裁切并保留裁切；画中画、全屏、方向锁、Media Session、屏幕常亮
- 清晰度切换（A/B）、画面适应 / 铺满 / 拉伸
- 我的组合、最近看过、房间预览（封面 / 主播 / 人气 / 分区 / 开播状态）
- 可安装 PWA，离线打开应用外壳

## 部署（GitHub Pages + Cloudflare Pages）

推送到 `main` 后，[`.github/workflows/deploy-pages.yml`](./.github/workflows/deploy-pages.yml) 会自动构建 `sanlu-pwa/` 并发布到 GitHub Pages。首次使用时在仓库 **Settings → Pages → Build and deployment** 选择 **GitHub Actions** 即可。

构建时自动使用：

- `VITE_BASE=/<仓库名>/` —— 项目站点子路径
- `VITE_RELAY_URL` —— 可在 **Settings → Secrets and variables → Actions → Variables** 覆盖中转地址，不设置则用默认 Cloudflare 中转

手动构建：

```bash
cd sanlu-pwa
npm ci
npm run typecheck
VITE_BASE=/dualcast/ npm run build   # 输出 dist/
```

Cloudflare Pages 版本使用根路径构建，并自带同源 `/api`、`/stream` 转发：

```bash
cd sanlu-pwa
npx wrangler login      # 首次：浏览器 OAuth 授权
npm run deploy          # 构建并发布到 Pages 项目 sanlu
```

线上地址：https://sanlu.yousaling.dpdns.org （备用 https://sanlu.pages.dev ）。

Android 版：`cd DualLive && ./gradlew assembleDebug`（产出 `app/build/outputs/apk/debug/`）。

## B 站接口为什么还需要中转

纯静态网页**无法完全直连** B 站 REST API：B 站直播网关按 `Origin` 白名单校验，非 `*.bilibili.com` 的浏览器请求在网关层直接 403，而浏览器不允许脚本伪造 `Origin`。PiliPlus 这类原生 App 能自由设置 `Origin/Referer/Cookie`，所以可以直连；网页做不到。

因此网页版采用 **直连优先 + 中转兜底**：

| 能力 | 通道 |
|---|---|
| 弹幕 WebSocket | ✅ 浏览器直连（WebSocket 不受 CORS 限制） |
| 直播 CDN 拉流 | ✅ 浏览器直连（页面 `referrer=no-referrer`，失败再走 `/stream` 中转） |
| buvid 指纹 `finger/spi` | ✅ 尝试直连 |
| 房间信息 / 播放地址 / 扫码登录 / 发弹幕 | 先直连；被 CORS/风控拦截自动走中转 |

默认中转为项目已有的 EdgeOne 海外区服务 `https://relay.yousaling.dpdns.org`（国内直连比 Cloudflare Pages 快 5～10 倍），也可在 **设置 → 中转** 里换成自建地址（CORS 允许 `*`）。中转代码有两套：

- [`sanlu-pwa/edgeone/`](./sanlu-pwa/edgeone/)：EdgeOne Makers 边缘函数（当前默认）；
- [`sanlu-pwa/functions/`](./sanlu-pwa/functions/)：Cloudflare Pages Functions（备用）。

Cloudflare 备用站点 `https://sanlu.yousaling.dpdns.org` 仍然保留。

## 仓库结构

```
sanlu-pwa/     Vue 3 + Vite PWA（网页版）
DualLive/      Android 原生版
.github/       GitHub Pages 自动部署工作流
```

详细技术说明见 [sanlu-pwa/README.md](./sanlu-pwa/README.md)。