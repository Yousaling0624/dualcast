# 项目经验与坑

> 新发现的、会导致返工或线上问题的经验追加到这里；不要写代码结构能直接看出来的东西。

## 构建

- 必须 `npm run build`。裸 `vite build` 不会执行 `scripts/stamp-build.mjs`，SW 缓存名会停留在 `__BUILD_ID__`，也不会生成 `dist/version.json`。
- GitHub Pages 必须 `VITE_BASE=/dualcast/`（CI 已用仓库名注入）；本地和 Cloudflare 用 `/`。写死绝对路径会让子路径部署 404。
- 已安装 PWA 靠 SW 缓存名更新；不要在 `public/sw.js` 里把缓存名改成固定值。

## 发版

- `GITHUB_RUN_NUMBER` 是工作流级序号，不等于 push 次数；不要用它推断版本或对比本地。
- Release notes 脚本用 `git log --pretty=format`，最后一行没有换行符；读取时必须 `while IFS= read -r x || [ -n "$x" ]`，计数用 `git rev-list --count`，不要用 `wc -l`。
- Release 必须等 Pages 部署成功（`needs: [build, deploy]`），并用 `--target "$GITHUB_SHA"`；否则 main 在构建期间前进时 tag 会指错 commit。
- 发布会排队（`cancel-in-progress: false`），连续 push 会依次生成多个 `v0.1.N`，这是预期行为。

## B 站接口

- 浏览器直连 B 站 REST API 会被 Origin 白名单拦（403）。统一走 `requestJson` 的「直连 → 中转」，不要另起 `fetch`。
- 中转通过 `x-bili-cookie` 传登录态；Cookie 只放 `localStorage` 的 `dc_cookies`，不要日志输出。
- 关注列表：`GetWebList?hit_ab=true` 只返回正在直播的关注；字段可能被置 0，需要 `followed.ts` 归一化。失败时回退到分页 `following` 接口并去重。
- 直播间号有长短号（`room_id` / `roomid`、`short_id`），解析和去重都要兼容。

## 测试

- v0.1.26 时基线是 4 个测试文件 / 74 个用例。新增解析逻辑先写纯函数，再补 `tests/*.test.ts`。
- 测试里不要发真实网络请求；B 站接口会风控，也不该依赖登录态。
- `vitest` 跑全量很快，不要只跑单个文件就提交。

## 性能

- 屏幕弹幕不要逐帧 `fillText` / `strokeText`：先把「文字 + 气泡 + 描边」烘焙成小位图，再每帧 `drawImage`；重复文本复用同一张图。文字排版是弹幕掉帧的主要来源。
- canvas 的 RAF 在回主页、`document.hidden` 或弹幕关闭时要真正停掉，否则空循环也占主线程和电量；恢复时不要补播暂停期间积压的弹幕。
- 聊天室列表不要每条消息都整表重渲染；按 `store.messageRev` 合并成 ~10fps 刷新，消息数据仍实时进 `store.messages`。
