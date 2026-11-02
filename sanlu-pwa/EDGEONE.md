# 中转部署到腾讯 EdgeOne Makers

默认中转已经部署在 **EdgeOne Makers 海外区（全球可用区，不含中国大陆）**：

```
https://relay.yousaling.dpdns.org
```

选海外区而不是国内区的原因：

- 国内区（`mainland` / `global`）的项目域名在大陆访问时必须带 3 小时有效的
  `eo_token` 预览参数，否则 401；绑定自定义域名又要求 ICP 备案。
- 海外区（`overseas`）没有这个预览 token 限制，自定义域名也**不需要 ICP 备案**。
- 实测自定义域名连接复用后 TTFB 约 0.5 秒；Cloudflare Pages 直连约 1.8–3.4 秒，
  偶尔 25 秒超时。

源码在 `edgeone/`：

```
edgeone/
  index.html                         落地页（自检用）
  edge-functions/
    api/[[default]].js               /api/{live|passport|bili}/...
    stream/[[default]].js            /stream/<encodeURIComponent(URL)>
```

## 当前项目的域名配置

### 1. 归属权验证（TXT）

| 类型 | 名称 | 值 |
| --- | --- | --- |
| TXT | `edgeonereclaim.relay.yousaling.dpdns.org` | `reclaim-1hm55mcviwaso1d98o78eiqfckow6vw0` |

> EdgeOne 面板里可能显示主机记录为 `edgeonereclaim.relay`（相对 `dpdns.org`）；
> Cloudflare 的 zone 是 `yousaling.dpdns.org`，所以实际名称是
> `edgeonereclaim.relay.yousaling.dpdns.org`。

### 2. 访问 CNAME

| 类型 | 名称 | 目标 | 代理 |
| --- | --- | --- | --- |
| CNAME | `relay.yousaling.dpdns.org` | `relay.yousaling.dpdns.org.pages.dnsoe5.com` | **DNS only（灰云）** |

> 必须关掉 Cloudflare 小黄云；否则请求又会绕回 Cloudflare 的洛杉矶线路。
> CNAME 生效后 EdgeOne 会自动签发 SSL 证书，通常需要 5–15 分钟。

## 重新部署

```bash
cd sanlu-pwa
npx edgeone@latest login --site china
npx edgeone@latest makers deploy ./edgeone -n sanlu-relay -e production --site china --area overseas
```

如果 CLI 不支持给已有项目改区域，就到 EdgeOne 控制台：

1. **创建项目** → 选择 **直接上传**；
2. 加速区域选 **全球可用区（不含中国大陆）**；
3. 上传 `edgeone/` 目录或打包的 zip；
4. 部署完成后在 **域名管理** 里绑定 `relay.yousaling.dpdns.org`。

## 匿名部署（临时测试）

```bash
cd sanlu-pwa
npx edgeone@latest makers deploy ./edgeone --anonymous --site china --area overseas -n sanlu-relay --json
```

输出里会带：

- `url`：带 `eo_token` 的临时访问地址（未认领前必须带 token）；
- `claimUrl`：认领链接（需要腾讯云账号登录）；
- `expiresAt`：认领截止时间，通常是部署后 1 小时。

匿名项目未认领会被删除；正式使用请走上面的登录部署或控制台创建项目。

## 端点与行为

- `/api/live/<path>` → `https://api.live.bilibili.com/<path>`
- `/api/passport/<path>` → `https://passport.bilibili.com/<path>`
- `/api/bili/<path>` → `https://api.bilibili.com/<path>`
- `/stream/<encodeURIComponent(完整URL)>` → 直播流兜底（只允许 B 站 CDN 域名）

约定：

- 前端把 B 站 Cookie 放在 `x-bili-cookie` 请求头，边缘函数转成上游 `Cookie`；
- 扫码登录 poll 的 `Set-Cookie` 会解析进响应体的 `cookies` / `cookie_string`；
- `getDanmuInfo` 返回 `-352` 时，会抓直播间页面的 `w_webid` 重试一次；
- 所有响应带 `access-control-allow-origin: *`，支持 `GET/POST/OPTIONS`。

## 自检

```bash
curl 'https://relay.yousaling.dpdns.org/api/live/room/v1/Room/room_init?id=6'
# {"code":0,...}

curl 'https://relay.yousaling.dpdns.org/api/live/xlive/web-room/v1/index/getDanmuInfo?id=6&type=0'
# 未签名可能返回 -352；前端会自动加 WBI 签名，签名后应返回 code 0 和 host_list
```
