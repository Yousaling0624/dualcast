# 三叠播 / DualCast

把两个 B 站直播间叠在一起看：一路出画面，一路出弹幕和声音。

| 子项目 | 说明 | 状态 |
|---|---|---|
| [`sanlu-pwa/`](./sanlu-pwa/README.md) | **网页版 PWA**（PC / iOS / 鸿蒙浏览器，已上线） | ✅ 已部署 https://sanlu.yousaling.dpdns.org |
| [`DualLive/`](./DualLive/README.md) | **Android 原生版**（Jetpack Compose，ExoPlayer） | ✅ 已构建 APK（最新 1.1.1，versionCode 13） |

## 网页版要点（交接必读）

线上跑在 Cloudflare Pages（项目 `sanlu`，自定义域 `sanlu.yousaling.dpdns.org`）。

B 站风控是本项目最大的技术难点，三条实测结论：

1. **浏览器直连 B 站 API 不可行**：B 站按 Origin 白名单拒绝，非 `*.bilibili.com` 域名的请求在网关层直接 403（浏览器无法伪造 Origin，纯前端无解）。
2. **Cloudflare 的 `fetch()` 出口被封**：返回 -412 `request was banned`，按 TLS/HTTP2 指纹识别，带完整 buvid cookie 也没用。
3. **原始 TLS socket（`cloudflare:sockets`）手写 HTTP/1.1 可以通过**：同一出口 IP 直接 200 —— 这是当前代理能跑通的关键。

所以 `sanlu-pwa/functions/api/[[path]].ts` 是双通道：`fetch()` 失败（403/412/-352）自动切 raw socket 重试。

**当前未完全解决**：`getDanmuInfo` 可能仍返回 -352（B 站对数据中心出口的弹幕 token 风控更严），前端已用默认 host + 空 token 裸连兜底。这是最值得继续攻的点。详见 [sanlu-pwa/README.md](./sanlu-pwa/README.md)。

## 部署

```bash
cd sanlu-pwa
npm install && npm run build
export CLOUDFLARE_API_TOKEN=xxx   # Pages 编辑 + 帐户设置读取 + 区域读取 + DNS 编辑
bash deploy.sh                    # 一键：构建 → 部署 → 绑自定义域 → 配 DNS
```

Android 版：`cd DualLive && ./gradlew assembleDebug`（产出 `app/build/outputs/apk/debug/`）。

## 环境信息

- Cloudflare 账号里已有：Pages 项目 `sanlu`、zone `yousaling.dpdns.org` 与 `lovehgr.dpdns.org`
- 未在仓库中保存任何 Token / 密钥，全部走环境变量
