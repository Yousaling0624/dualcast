/// <reference types="vite/client" />

/** 构建时注入的版本号：CI 为 v0.1.<run_number>，本地为 package.json 版本 */
declare const __APP_VERSION__: string

interface ImportMetaEnv {
  readonly VITE_RELAY_URL?: string
  readonly VITE_BASE?: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}

declare module '*.vue' {
  import type { DefineComponent } from 'vue'
  const component: DefineComponent<{}, {}, any>
  export default component
}