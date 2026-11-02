# 三叠播 PWA 部署指南

> 现在主站由 GitHub Actions 自动部署到 **https://yousaling0624.github.io/dualcast/**（见仓库根目录 `.github/workflows/deploy-pages.yml`）。
> 本文档保留 Cloudflare Pages 的部署方式：它同时也是默认 API/直播流中转。

Cloudflare 目标地址：`https://sanlu.yousaling.dpdns.org`（备用：`https://sanlu.pages.dev`）

## 方式一：给我 Token，我帮你全自动部署（推荐）

1. 打开 https://dash.cloudflare.com/profile/api-tokens → **创建令牌** → 底部 **创建自定义令牌**
2. 权限按下表勾选（共 4 条）：

   | 范围 | 资源 | 权限 |
   |---|---|---|
   | 帐户 | Cloudflare Pages | 编辑 |
   | 帐户 | 帐户设置 | 读取 |
   | 区域 | 区域 | 读取 |
   | 区域 | DNS | 编辑 |

3. 把 Token 发给我（形如 `xK9...` 一长串），我执行 `deploy.sh` 完成构建、建项目、部署、绑域名、配 DNS 全流程。

## 方式二：自己部署（3 条命令）

```bash
cd sanlu-pwa
export CLOUDFLARE_API_TOKEN=你的Token
bash deploy.sh
```

脚本会自动完成全部步骤；也可以手动逐步执行：

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
