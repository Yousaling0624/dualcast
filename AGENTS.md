# 双路直播（DualCast）开发规范

> 本文件是仓库唯一的开发规范，适用于人类和所有 AI Agent。
> `CLAUDE.md`、`GEMINI.md`、`.github/copilot-instructions.md` 只是各工具的入口；
> 规则冲突时以本文件为准。

## 1. 项目范围

- `sanlu-pwa/`：Vue 3 + TypeScript + Vite 的 PWA，主要开发对象。
- `DualLive/`：Android 原生端（Kotlin + Compose + ExoPlayer）。**除非用户明确要求，不要改动**；也不要顺手重构、升依赖或格式化。
- `.github/`：自动构建、部署、发版流程。
- 根目录文档：`README.md` / `AGENTS.md` / `LESSONS.md`。

## 2. 已授权的工作流（无需逐次询问）

用户已授权：完成 PWA 改动并通过验证后，**直接提交并推送 `main`**，由 CI 自动部署和发版。不要停下来问「要不要提交 / 要不要部署」。

标准流程：

1. 读相关代码和 `LESSONS.md`，确认现有实现方式。
2. 只改必要文件，保持现有风格，不夹带无关重构。
3. 为可测试的纯逻辑补单元测试。
4. 在 `sanlu-pwa/` 跑完整验证：

   ```bash
   cd sanlu-pwa
   npm ci                 # 首次或 lockfile 变化时
   npm run typecheck && npm test && npm run build
   ```

   改了 CI 脚本时再跑 `bash -n .github/scripts/release-notes.sh`。

5. `git status` 确认只有预期文件。
6. 提交并推送：

   ```bash
   git add -A
   git commit -m "PWA：一句话总结

   - 具体改动 1
   - 具体改动 2

   Co-Authored-By: Claude Code <noreply@anthropic.com>"
   git push origin main
   ```

7. 向用户汇报改了什么、验证结果，以及 push 后会自动部署/发版。具体版本号 `v0.1.<run_number>` 由 CI 生成，不要预测数字。

**验证失败时不要提交。** 先修复；修不好就如实说明失败项和原因。不要用 `--no-verify`、跳过测试、删测试或改 CI 阈值来「通过」。

### 例外：什么时候要先问用户

- 产品/交互取舍不明确，规范里没有答案；
- 破坏性 Git 操作（force push、改写 `main`、删 tag/release）；
- Cloudflare 部署；
- 新增付费服务、运行时依赖或改变发版策略；
- 需要用户账号/密钥才能完成的验证（不要让用户把 Cookie 贴进对话）。

## 3. 版本与发布（重要）

- **不要手动升版本号。** `sanlu-pwa/package.json` 永远是基线 `0.1.0`；不要跑 `npm version`。
- **不要手动打 tag、发 Release、改 CHANGELOG。** 全部由 CI 完成。
- CI 在每次 push `main`（且改动命中 `sanlu-pwa/**`、部署工作流或 `.github/scripts/**`）时：
  1. 生成版本 `0.1.${GITHUB_RUN_NUMBER}`；
  2. typecheck / test / build；
  3. 部署到 <https://yousaling0624.github.io/dualcast/>；
  4. 部署成功后创建 tag `v0.1.N` 和 GitHub Release，附 `dualcast-pwa-0.1.N.zip`。
- 构建时 `APP_VERSION` 注入界面版本；`scripts/stamp-build.mjs` 把 `dist/sw.js` 里的 `__BUILD_ID__` 换成「版本-commit」，并生成 `dist/version.json`。**必须用 `npm run build`**，不要用裸 `vite build`。
- 纯文档改动不命中 CI 路径，不会发版，这是有意的；不要为了发版去改 `sanlu-pwa/` 的无关文件。
- Cloudflare 只在用户明确要求时部署：`cd sanlu-pwa && npm run deploy`（或 `bash deploy.sh`）。它使用根路径 `/` 和同源 `/api`、`/stream` 转发。

## 4. Commit message 规范

Release notes 直接从 commit 生成，所以 commit 就是发布说明：

- 标题格式：`PWA：一句话总结`。CI/文档改动可用 `CI：`、`文档：`。
- 标题写用户能看懂的变化，不要写「修改代码」「update」。
- 正文用 `-` 列表写具体改动；单 commit 发版时正文会原样进入 Release notes。
- 多 commit 发版时，脚本按「新增/添加/支持」「修复/修正/解决」「优化/调整/重构」等关键词归类；标题里带上这些词。
- 不要写版本号（CI 负责）；不要提交生成物（`dist/`、zip、`release-notes.md` 已忽略）。
- Agent 如果支持署名，附对应 `Co-Authored-By`；Claude Code 固定用 `Co-Authored-By: Claude Code <noreply@anthropic.com>`。

## 5. 代码约定

### 通用

- TypeScript strict；不要引入 `any` 污染自有数据结构。外部 API 响应在边界处接收后，立即用 `lib/` 里的 normalize 函数转成内部类型。
- 2 空格缩进、单引号、不加分号、尾逗号 —— 跟随现有代码，不引入新的 formatter 配置。
- 注释用中文，解释「为什么」而不是复述代码；模块顶部保留用途说明。
- 不引入新的运行时依赖 / UI 框架 / CSS 框架，除非确有必要并在提交说明里解释。
- 不手动改 `package-lock.json`、`dist/`、`node_modules/`。

### 目录职责

- `src/components/`：Vue 单文件组件，`<script setup lang="ts">` + Composition API。
- `src/lib/`：网络、解析、播放器、弹幕、状态等非 UI 逻辑。纯函数放这里，便于单测。
- `src/lib/store.ts`：全局状态与会话编排；共享状态通过 store 导出的方法修改，组件只保留本地 UI 状态。
- `src/styles.css`：全局主题变量和基础样式；组件样式写在 SFC 的 `<style scoped>`。
- `tests/*.test.ts`：Vitest 单测，只测纯逻辑，不发真实网络请求。

### 网络层

- 所有 B 站 REST 请求必须走 `lib/net.ts` 的 `requestJson`（直连优先、失败自动中转），不要在组件里直接 `fetch`。
- 不要把 Cookie 写进代码、日志或 URL；中转通过 `x-bili-cookie` 头传递。Cookie 只存在 `localStorage` 的 `dc_cookies`。
- 需要 WBI 签名的接口用 `lib/wbi.ts` 的 `signParams`，不要手写签名。
- 错误抛 `ApiError`，消息用中文、可展示给用户；UI 负责捕获并显示。
- API 字段经常有多套命名（`room_id`/`roomid`、`area_v2_name`/`area_name_v2`，`hit_ab` 下部分字段为 0），解析时做兼容和兜底，并把逻辑放纯函数 + 单测。

### UI / 状态

- 复用现有组件、`AppIcon`、CSS 变量和暖纸色设计；保持一致的空状态、加载态、错误态。
- 移动端优先，至少检查窄屏不溢出；新增弹窗要处理滚动锁定、Esc 关闭和点击遮罩关闭（参考 `FollowPicker.vue`）。
- 持久化 key 统一 `dc_` 前缀；修改已存数据结构时要向后兼容旧数据，读取失败要能安全回退。
- 新增用户可见文案用中文；错误提示要给下一步操作，而不只是报错码。

### 测试

- 新增/修改纯逻辑（解析、归一化、排序、时间/偏移、颜色等）必须补 `tests/*.test.ts`。
- 不 mock 网络来「凑覆盖率」；把网络响应样例喂给纯解析函数。
- 不删除、不跳过已有测试；全量 `npm test` 必须通过。

## 6. CI/CD 约定

- 工作流：
  - `.github/workflows/deploy-pages.yml`：push main 的构建、Pages 部署、Release。
  - `.github/workflows/ci.yml`：PR 检查（typecheck / test / build / shell 语法）。
  - `.github/scripts/release-notes.sh`：从上一个 `v0.1.*` tag 到 HEAD 的 commit 生成 notes。
- 改工作流时必须保留：
  - `permissions` 中的 `contents: write`、`pages: write`、`id-token: write`；
  - `concurrency` 不取消进行中的发布（`cancel-in-progress: false`）；
  - Release 的 `needs: [build, deploy]`，确保 Pages 成功后才发版；
  - `gh release create --target "$GITHUB_SHA"`，避免 main 前进后 tag 指错 commit。
- 改 shell 先 `bash -n`；改 YAML 后确认缩进和 `on:` 触发路径。
- 测试/构建失败会阻止部署和发版；不要用 `continue-on-error` 掩盖失败。

## 7. 安全与隐私

- 仓库里绝不能出现 Cookie、token、`.env`、中转密钥。示例用占位符。
- 不记录用户 Cookie；不接入第三方统计/追踪。
- B 站接口返回视为不可信输入；解析前判空、限长，避免把 HTML 直接插入页面。
- 不要请求用户把账号密码或 Cookie 发到对话里；需要真实登录态时，让用户在本地浏览器自行登录验证。

## 8. 提交前自查清单

- [ ] 只改了 `sanlu-pwa/`（或明确获准的 `DualLive/`）
- [ ] `npm run typecheck && npm test && npm run build` 通过
- [ ] 新纯逻辑有测试；没有删/跳过测试
- [ ] 没有手改版本号、tag、Release、生成物
- [ ] commit 标题/正文可直接作为 Release notes
- [ ] `git status` 只有预期文件
- [ ] 已 push `main`，并告知用户 CI 会部署/发版
