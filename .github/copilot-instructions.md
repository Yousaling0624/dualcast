# 双路直播（DualCast）

完整开发规范在仓库根目录 [`AGENTS.md`](../AGENTS.md)，动手前必须先阅读；项目经验在 [`LESSONS.md`](../LESSONS.md)。

最低限度必须遵守：

- 只改 `sanlu-pwa/`，不要动 `DualLive/`（除非用户明确要求）。
- 改完在 `sanlu-pwa/` 跑 `npm run typecheck && npm test && npm run build`。
- 验证通过后直接 `git commit` + `git push origin main`，不要问用户要不要提交/部署。
- 不要手动升版本号、打 tag、发 Release；CI 自动生成 `v0.1.<run_number>` 并部署 Pages。
- Cloudflare 部署只在用户明确要求时执行。
- commit 标题使用 `PWA：一句话总结`，正文用列表写具体改动（Release notes 会直接使用）。
