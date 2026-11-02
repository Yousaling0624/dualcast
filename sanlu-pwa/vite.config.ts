import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// GitHub Pages 项目站点部署在 /<repo>/ 下，构建时用 VITE_BASE 覆盖；
// Cloudflare Pages / 本地开发保持根路径。
const base = process.env.VITE_BASE || '/'

export default defineConfig({
  base,
  plugins: [vue()],
  build: {
    target: 'es2020',
    chunkSizeWarningLimit: 1600,
  },
})