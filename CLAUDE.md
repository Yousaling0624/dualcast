# 双路直播项目协作约定

## 改动范围
- PWA 在 `sanlu-pwa/`（Vue 3 + TS + Vite），是主要开发对象。
- `DualLive/` 是 Android 端，除非用户明确要求，否则不要改动。

## 改完自动提交并部署（用户已授权，不需要再问）
1. 在 `sanlu-pwa/` 跑完验证：
   ```bash
   cd sanlu-pwa
   npm run typecheck && npm test && npm run build
   ```
2. 同步升版本号：修 bug 升 patch，新功能升 minor。
   - `sanlu-pwa/package.json`
   - `sanlu-pwa/package-lock.json`（顶层和 `packages[""]` 里的 `version`）
   - `sanlu-pwa/public/sw.js` 的 `CACHE`（如 `sanlu-shell-v16` → `v17`）
3. 在仓库根目录直接提交并推送，不需要再询问用户：
   ```bash
   git add -A
   git commit -m "PWA x.y.z：一句话总结

   - 具体改动……

   Co-Authored-By: Claude Code <noreply@anthropic.com>"
   git push origin main
   ```
4. push 到 `main` 后，GitHub Actions（`.github/workflows/deploy-pages.yml`）会自动构建并部署到
   **https://yousaling0624.github.io/dualcast/**，不需要手动部署。
5. 只有用户明确要求时才执行 Cloudflare 部署（`npm run deploy` / `bash deploy.sh`）。

## 其他
- GitHub Pages 构建时 `VITE_BASE=/dualcast/`；本地和 Cloudflare 用 `/`。
- 提交前看一眼 `git status`，确认只有预期文件；`dist/`、`node_modules/` 已被忽略。
