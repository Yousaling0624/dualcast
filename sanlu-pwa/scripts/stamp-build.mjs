/**
 * 构建后写入发布标识：
 * - 把 dist/sw.js 里的 __BUILD_ID__ 换成「版本-commit」，
 *   保证每次发布 Service Worker 缓存名都不同，用户能拿到新外壳；
 * - 生成 dist/version.json，方便排查线上到底是哪次构建。
 *
 * 版本来自 CI 的 APP_VERSION（v0.1.<run_number>）；本地构建用 package.json 版本。
 */
import { readFileSync, writeFileSync } from 'node:fs'
import { resolve } from 'node:path'

const version =
  process.env.APP_VERSION || process.env.npm_package_version || '0.1.0'
const buildId =
  process.env.BUILD_ID || `${version}-${Date.now().toString(36)}`
const commit = process.env.GITHUB_SHA || ''

const swPath = resolve(process.cwd(), 'dist/sw.js')
const sw = readFileSync(swPath, 'utf8')
if (!sw.includes('__BUILD_ID__')) {
  throw new Error(`dist/sw.js 里没有 __BUILD_ID__ 占位符：${swPath}`)
}
writeFileSync(swPath, sw.replaceAll('__BUILD_ID__', buildId))

const meta = {
  version,
  build: buildId,
  commit,
  builtAt: new Date().toISOString(),
}
writeFileSync(
  resolve(process.cwd(), 'dist/version.json'),
  `${JSON.stringify(meta, null, 2)}\n`
)

console.log(`构建标识：${buildId}`)
