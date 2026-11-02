# 三叠播 PWA 部署指南

> 两个主站都已上线：
> - GitHub Actions → **https://yousaling0624.github.io/dualcast/**
> - Cloudflare Pages → **https://sanlu.yousaling.dpdns.org**（备用：`https://sanlu.pages.dev`）
>
> Cloudflare 版本同时提供同源的 `/api/*` 与 `/stream/*` 中转。

## 方式一：Wrangler 登录后一键部署（推荐）

```bash
cd sanlu-pwa
npx wrangler login     # 首次：按提示在浏览器完成 OAuth 授权
npm run deploy         # 构建 + 发布到 Pages 项目 sanlu
```

CI 环境不能交互登录时，改为设置 `CLOUDFLARE_API_TOKEN`。

## 方式二：自定义域 / DNS 首次配置

项目 `sanlu` 与自定义域 `sanlu.yousaling.dpdns.org` 已绑定，日常只需方式一。首次在新账号创建时可执行：

```bash
export CLOUDFLARE_API_TOKEN=你的Token
bash deploy.sh
```

脚本会完成构建、建项目、部署、绑域名、配 DNS；也可手动逐步执行：

```bash
npx vite build                              # 构建
npx wrangler pages deploy dist --project-name sanlu   # 部署
```

然后到 Cloudflare 控制台：**Workers 和 Pages → sanlu → 自定义域** 添加 `sanlu.yousaling.dpdns.org`，并到 **DNS** 添加记录 `CNAME | sanlu | sanlu.pages.dev | 已代理`。

## 前提条件

- `yousaling.dpdns.org` 必须已托管在你的 Cloudflare 账号下（免费计划即可）。
  检查方法：Cloudflare 控制台首页能看到 `yousaling.dpdns.org` 这个站点。
  如果没有：**添加站点** → 输入 `yousaling.dpdns.org` → 免费计划 → 按提示到 dpdns.org 的域名管理处把 NS 记录改成 Cloudflare 分配的两个 NS，生效后即可（dpdns.org 是免费二级域服务，在其管理后台可改 NS）。

## 常见问题

- **弹幕连不上 / getDanmuInfo 返回 -352**：B 站风控。项目已内置 `w_webid` 兜底抓取；Cloudflare 出口 IP 通常不在风控名单，若仍出现，登录 B 站账号后使用（Cookie 会透传给 API）。
- **直播间提示"需要登录"**：部分直播间强制登录，进设置页扫码登录 B 站即可。
- **更新版本**：改完代码重新跑 `bash deploy.sh` 即可，Pages 会发新版本并自动生效。
