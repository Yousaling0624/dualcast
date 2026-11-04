import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// GitHub Pages 项目站点部署在 /<repo>/ 下，构建时用 VITE_BASE 覆盖；
// Cloudflare Pages / 本地开发保持根路径。
const base = process.env.VITE_BASE || '/'

// CI 每次构建传入 APP_VERSION（v0.1.<run_number>）；本地取 package.json 版本
const appVersion =
  process.env.APP_VERSION || process.env.npm_package_version || '0.1.0'

export default defineConfig({
  base,
  define: {
    __APP_VERSION__: JSON.stringify(appVersion),
  },
  plugins: [vue()],
  build: {
    target: 'es2020',
    chunkSizeWarningLimit: 1600,
  },
})