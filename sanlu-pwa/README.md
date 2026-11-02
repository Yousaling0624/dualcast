# 三叠播 PWA

把两个 B 站直播间叠在一起看的网页版：**A 房出弹幕和声音，B 房出画面**。PC / iOS / Android / 鸿蒙浏览器可用，支持添加到主屏幕。

- GitHub Pages：https://yousaling0624.github.io/dualcast/
- 备用 Cloudflare Pages：https://sanlu.yousaling.dpdns.org
- 默认中转：https://relay.yousaling.dpdns.org（EdgeOne 海外区）
- 技术栈：Vite 6 + Vue 3 + TypeScript、mpegts.js / hls.js、Canvas 弹幕、WebSocket 弹幕、EdgeOne Makers / Cloudflare Pages Functions 中转

## 功能

### 播放
- 双直播间叠加：B 路主画面 + A 路解说小窗（可拖动 / 四角等比缩放 / 全屏 / 关闭）
- 点一下小窗画面进入沉浸模式，隐藏悬浮按钮；再点一下恢复，和 B 站移动端一致
- 小窗全屏后可框选裁切画面，退出全屏后裁切保留，继续拖动边角调节大小
- A / B 一键互换；FLV（mpegts.js）优先，HLS（hls.js / 原生）回退
- 清晰度切换（A、B 独立）；画面适应 / 铺满 / 拉伸
- 画中画、全屏 + 横屏锁定、Media Session、屏幕常亮
- 浏览器拦截带声音自动播放时，显示「点此开始播放」解锁

### 弹幕与互动
- A 房弹幕 Canvas 渲染：字号、速度、透明度、显示区域、描边/阴影/纯色、昵称、屏蔽词
- **多路弹幕 / 声音 / 画面**：可导入多个直播间同时显示，用颜色 / 主播首字 / 自定义字气泡区分来源；每一路可单独加入多路声音混音，也可加入画面左下角的多路墙
- **A+B 同屏弹幕**：设置里或聊天室顶部的 `A+B` 按钮可把比赛画面（B 路）的弹幕也合进同一屏，用蓝色气泡区分
- 画面上飘过的弹幕支持点击或鼠标悬停冻结，弹出 `+1` / `回复` / `举报` 快捷条；离开后自动恢复飘动
- 举报走 B 站直播端接口，接口不可用时引导打开原直播间处理
- 点聊天室弹幕或画面上的浮动弹幕，弹出 `+1` / `回复` 快捷条
- 回复格式 `回复内容@昵称:原话`，按 B 站 20 字上限自动截断引用；回复 / +1 会发回弹幕来源房间
- 发送冷却、错误码中文提示、本地先显示 + 服务端回显去重
- **SC 悬浮窗**：像浏览器脚本一样，在画面左上角显示头像、昵称、金额、留言和倒计时进度，全屏时同样可见；可在设置里开关、调停留时长和同时显示数量
- 礼物 / SC / 舰长 / 开播下播系统消息；聊天室里 SC 会显示价格和独立卡片
- 进房补拉历史弹幕

### 账号
- B 站 App 扫码登录（轮询），或手动粘贴 Cookie
- 登录后显示昵称 / 头像 / UID / 等级；退出登录
- Cookie 只保存在本机 `localStorage`

### 体验
- Material Design 3 Expressive + Claude 暖纸色界面：大圆角卡片、serif 标题、图标按钮、动效
- 我的组合（A/B 房间对，一键开播）、最近看过、房间预览
- 混音台：A/B 独立音量与静音、只听一路、A 相对 B 的 ±5 秒音画偏移和滑杆微调
- 延迟校准：正偏移暂停 A 路、负偏移暂停 B 路，用暂停时长补足目标延迟；减小延迟时 seek 到缓冲更靠前的位置，小偏差用 ±6% 倍速平滑追帧
- 关闭 mpegts 内置追帧，避免手动延迟被拉回直播边缘
- 音量与偏移按「A 房间 + B 房间」组合记忆；A/B 互换时偏移方向同步反转
- 可安装 PWA；`sw.js` 缓存应用外壳，接口与直播流永不缓存
- Vitest 单元测试覆盖偏移计算、来源气泡和裁切 / 缩放几何；GitHub Actions 执行 typecheck、test、build

## 目录

```
src/
  components/
    HomeView.vue        首页：房间输入 / 预览 / 组合 / 最近
    LiveView.vue        播放页：双路视频、弹幕层、快捷互动、小窗、全屏
    ChatPanel.vue       聊天室：+1 / 回复 / 发送、SC / 礼物卡片
    DanmakuCanvas.vue   Canvas 弹幕渲染与点击命中测试
    MultiWall.vue       导入直播间的多路画面小窗墙
    SuperChatOverlay.vue 画面上的 SC 悬浮窗（全屏可用）
    AppIcon.vue         SVG 图标集
    SettingsDrawer.vue  账号 / 弹幕 / 混音 / 画面 / 中转 / 关于
  lib/
    net.ts     直连优先 + 中转兜底、Cookie、中转配置
    wbi.ts     WBI 签名（w_rid / wts / mixinKey）
    md5.ts     纯 JS MD5
    bili.ts    房间、播放地址、弹幕 token、历史弹幕、发弹幕、扫码登录
    danmaku.ts 弹幕 WebSocket（op 2/3/5/7/8，zlib / brotli）
    player.ts  双路播放器与音画偏移
    store.ts   全局状态、会话编排、聊天室互动
    sync.ts    偏移 / 暂停时长等纯计算
    sources.ts 多路弹幕来源气泡与配色
    layout.ts  小窗裁切、等比缩放几何
tests/       Vitest 单元测试
functions/
  api/[[path]].ts    API 中转（fetch + 原始 TLS socket 双通道）
  stream/[[url]].ts  直播流兜底中转
public/              manifest、sw.js、icons
```

## 接口通道说明（重要）

B 站直播网关按 `Origin` 白名单拦截浏览器请求，非 `*.bilibili.com` 来源直接 403；浏览器脚本无法伪造 `Origin`。所以网页版**不可能像原生 App 一样纯前端直连全部 REST 接口**。

当前策略：

1. **GET 先直连** `api.live.bilibili.com` / `api.bilibili.com` / `passport.bilibili.com`；
2. 遇到 CORS、超时、HTTP 403/412 或风控码（-352/-412 等）自动切到中转 `${relay}/api/<bucket>/<path>`；
3. **POST（发弹幕、退出登录）只走中转**，因为需要 `Cookie` 头；
4. 弹幕 WebSocket、直播 CDN 拉流、buvid 指纹尽量直连，不经过中转。

默认中转：`https://relay.yousaling.dpdns.org`（EdgeOne Makers 海外区，CORS `*`）。Cloudflare Pages 的 `https://sanlu.yousaling.dpdns.org` 保留为备用。构建时可用 `VITE_RELAY_URL` 覆盖，运行时可在 **设置 → 中转** 修改或关闭。

EdgeOne 中转在 [`edgeone/`](./edgeone/)，端点与域名配置见 [EDGEONE.md](./EDGEONE.md)。Cloudflare 备用中转的 `functions/api/[[path]].ts`：

- 先走 `fetch()`；被 B 站按 TLS 指纹风控（403/412/-352）时自动改用 `cloudflare:sockets` 手写 HTTP/1.1；
- 扫码 `poll` 的 `Set-Cookie` 会解析成 `cookie_string` / `cookies` 放进 JSON，浏览器才能拿到登录态；
- `getDanmuInfo` 出现 -352 时抓直播间页面里的 `w_webid` 重试；
- `x-bili-cookie` 请求头传递用户 Cookie。

## 本地开发

```bash
npm ci
npm run dev          # http://localhost:5173
npm run typecheck
npm test             # Vitest 单元测试
npm run build        # 输出 dist/
npm run preview
```

本地没有 Cloudflare Functions 时，默认中转仍然可用；也可以在设置里填自己的中转。

## 部署

### GitHub Pages（推荐）

仓库根目录的 `.github/workflows/deploy-pages.yml` 会在推送到 `main` 时自动：

1. `npm ci` + `npm run typecheck`；
2. 以 `VITE_BASE=/<仓库名>/` 构建；
3. 上传 `sanlu-pwa/dist` 并发布 Pages。

首次使用：仓库 **Settings → Pages → Build and deployment → Source** 选择 **GitHub Actions**。

如需自定义中转，在 **Settings → Secrets and variables → Actions → Variables** 添加 `VITE_RELAY_URL`。

### Cloudflare Pages

线上地址：https://sanlu.yousaling.dpdns.org （备用 https://sanlu.pages.dev ）

```bash
npx wrangler login      # 首次：浏览器 OAuth 授权
npm run deploy          # 构建并发布到 Pages 项目 sanlu
```

CI 环境可用 `CLOUDFLARE_API_TOKEN` 代替 `wrangler login`。

### 中转（EdgeOne）

当前默认中转跑在 EdgeOne Makers 海外区，域名 `https://relay.yousaling.dpdns.org`，不经过 ICP 备案。
源码在 [`edgeone/`](./edgeone/)，重新部署和自定义域名的 TXT/CNAME 配置见 [EDGEONE.md](./EDGEONE.md)。

`functions/` 会随 Pages 一起部署，提供 `/api/*` 与 `/stream/*`；通过自定义域访问时中转与页面同源（pages.dev 会调用自定义域中转）。

## 已知限制

- B 站弹幕正文上限 20 个字符（JS 按 UTF-16 计，emoji 会更保守）。
- 移动端浏览器可能拦截带声音的自动播放，需要点一次「点此开始播放」。
- 部分房间的 `getDanmuInfo` 对数据中心出口有额外风控；失败时用默认 host + 空 token 兜底，可能只能看不能发。
- 登录态保存在 `localStorage`，公共设备用完请退出登录。