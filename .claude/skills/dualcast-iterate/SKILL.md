---
name: dualcast-iterate
description: 双路直播（DualCast）项目的标准迭代流程。用于在 sanlu-pwa/ 中实现功能、修复缺陷、性能优化，或改动文档与 CI：先读规范与经验、限定改动范围、为纯逻辑补测试，再跑 typecheck/test/build，按约定提交并推送 main，由 CI 自动部署 Pages 并发版。
---

# 双路直播迭代流程

本 skill 是操作清单；规则冲突时以 `AGENTS.md` 为准，历史踩坑见 `LESSONS.md`。

## 0. 开始前

- 读 `AGENTS.md` 与 `LESSONS.md`；涉及发布、构建、B 站接口或性能时，先看对应条目。
- `git status --short --branch` 确认工作区干净、在 `main` 上；有未预期改动先弄清来源。
- 定位相关组件、`src/lib/` 模块和现有测试，沿用当前实现方式，不凭记忆造新接口。

## 1. 界定改动

- 默认只改 `sanlu-pwa/`。`DualLive/` 除非用户明确要求，否则不动；不顺手重构、升依赖、格式化。
- 按目录职责放置代码：
  - UI → `src/components/*.vue`（`<script setup lang="ts">`）
  - 纯逻辑 / 网络 / 播放 / 弹幕 / 状态 → `src/lib/`
  - 共享状态只通过 `src/lib/store.ts` 导出的方法修改
  - 全局主题 → `src/styles.css`；组件样式用 `<style scoped>`
- 不新增运行时依赖 / UI 框架 / CSS 框架；确需新增先问用户。
- 持久化 key 用 `dc_` 前缀；改已存数据结构必须兼容旧数据并能安全回退。
- 新增用户可见文案用中文；错误提示给出下一步操作，而不只是报错码。

## 2. 实现约定

- TypeScript strict，不用 `any` 污染自有数据结构；外部 API 响应在边界处归一化。
- B 站 REST 一律走 `lib/net.ts` 的 `requestJson`；WBI 签名用 `lib/wbi.ts` 的 `signParams`；不要裸 `fetch`。
- Cookie 只存 `localStorage` 的 `dc_cookies`，不进代码、日志、URL。
- 错误抛 `ApiError`，消息中文且可展示给用户。
- 2 空格缩进、单引号、不加分号、尾逗号；注释中文且解释「为什么」。
- 弹幕 / RAF 相关改动先对照 `LESSONS.md` 的性能条目（烘焙位图、停 RAF、消息合并刷新）。

## 3. 补测试

- 新增/修改解析、归一化、排序、时间/偏移、颜色等纯逻辑，必须补 `tests/*.test.ts`。
- 测试只把样例数据喂给纯函数，不发真实网络请求、不依赖登录态。
- 不删除、不跳过已有测试。

## 4. 验证（全绿才提交）

```bash
cd sanlu-pwa
# 首次或 package-lock.json 变化时
npm ci
npm run typecheck && npm test && npm run build
```

- 必须用 `npm run build`；裸 `vite build` 不会 stamp SW 缓存名，也不生成 `dist/version.json`。
- 改了 CI shell：`bash -n .github/scripts/release-notes.sh`。
- 改了工作流：保留 `contents: write` / `pages: write` / `id-token: write`、`cancel-in-progress: false`、Release 的 `needs: [build, deploy]` 与 `--target "$GITHUB_SHA"`。
- 失败就修；修不好如实报告。不要 `--no-verify`、跳过测试、删除测试或改 CI 阈值来「通过」。

## 5. 提交前自查

- [ ] 只改了预期范围内的文件（默认 `sanlu-pwa/`）
- [ ] 新纯逻辑有测试，未删/跳测试
- [ ] 未手改版本号、tag、Release、CHANGELOG、`dist/`、`package-lock.json`
- [ ] `git status` 只有预期文件
- [ ] 标题/正文可直接作为 Release notes

## 6. 提交并推送 main

```bash
git add -A
git commit -m "PWA：一句话总结

- 具体改动 1
- 具体改动 2

Co-Authored-By: Claude Code <noreply@anthropic.com>"
git push origin main
```

- 标题：PWA 改动用 `PWA：`；CI/文档改动可用 `CI：`、`文档：`。
- 标题写用户能看懂的变化；正文用 `-` 列表。
- 不写版本号，不打 tag，不发 Release。

## 7. 汇报

- 说明改了什么、跑了哪些验证、结果如何。
- 提醒：push 后 CI 会自动部署 <https://yousaling0624.github.io/dualcast/> 并创建 `v0.1.<run_number>` Release；不要预测具体版本号。
- 纯文档改动（如 `.claude/`、根目录 md）不命中 CI 路径，不会部署/发版，需明确告知。

## 先问用户的例外

- 产品/交互取舍不明确，规范里没有答案。
- 破坏性 Git 操作（force push、改写 `main`、删 tag/release）。
- Cloudflare 部署（`npm run deploy`）。
- 新增付费服务、运行时依赖或改变发版策略。
- 需要用户账号/密钥才能完成的验证（不要让用户贴 Cookie）。

## CI 链路（备查）

push `main` 且命中 `sanlu-pwa/**`、`.github/workflows/deploy-pages.yml`、`.github/scripts/**` 时：

1. 生成版本 `0.1.${GITHUB_RUN_NUMBER}`；
2. `npm ci` → `typecheck` → `test` → `build`；
3. 部署 GitHub Pages；
4. 部署成功后创建 tag `v0.1.N` 和 Release，附 `dualcast-pwa-0.1.N.zip`。

发布排队执行（`cancel-in-progress: false`），连续 push 产生多个版本是预期行为。
