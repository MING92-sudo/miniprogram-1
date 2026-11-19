#!/usr/bin/env node
/**
 * 把 admin/dist 同步到后端 static，使 `http://localhost:8080/` 直接提供最新管理端。
 *
 * 为什么需要：本地用 `mvn spring-boot:run` 时，Spring 从
 * `backend/src/main/resources/static`（及其编译副本 `target/classes/static`）提供静态页。
 * 该目录是 .gitignore 的构建产物，**不会自动跟随 admin 源码更新** ——
 * 忘记同步就会在 8080 看到几天前的旧界面（Dockerfile 第 20 行注释里的 #195「旧版管理端」即此类问题，
 * 镜像里靠 `rm -rf` 旧 dist 规避，本地没有这一步）。
 *
 * 用法：
 *   cd admin && npm run sync:backend          # 复用已有 dist
 *   cd admin && npm run build:backend         # 先 build 再同步（推荐）
 *
 * 注意：必须先清空旧产物再拷贝。否则旧哈希的 assets 会与新 index.html 共存，
 * 极端情况下浏览器缓存命中旧入口 → 依旧是旧版（与 #195 完全同构）。
 */
import { existsSync, rmSync, cpSync, readdirSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const here = dirname(fileURLToPath(import.meta.url))
const dist = resolve(here, '..', 'dist')
const targets = [
  resolve(here, '..', '..', 'backend', 'src', 'main', 'resources', 'static'),
  // spring-boot:run 实际读取的是编译产物；两份都刷，避免必须重启 Maven 才生效
  resolve(here, '..', '..', 'backend', 'target', 'classes', 'static')
]

if (!existsSync(dist)) {
  console.error(`[sync] 未找到 ${dist}，请先执行 npm run build`)
  process.exit(1)
}
if (!existsSync(resolve(dist, 'index.html'))) {
  console.error('[sync] dist 里没有 index.html，构建产物不完整')
  process.exit(1)
}

for (const target of targets) {
  if (!existsSync(dirname(target))) continue
  rmSync(target, { recursive: true, force: true })
  cpSync(dist, target, { recursive: true })
  const entry = readdirSync(resolve(target, 'assets')).filter((f) => /^index-.*\.js$/.test(f))
  console.log(`[sync] ${target}\n        入口: ${entry.join(', ') || '(未找到)'}`)
}
console.log('[sync] 完成。若 8080 仍显示旧版，请 Ctrl+F5 强制刷新（index.html 可能被浏览器缓存）。')
