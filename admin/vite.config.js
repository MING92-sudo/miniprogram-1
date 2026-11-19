import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

/**
 * 把仓库根 constants/permission-matrix.js（CJS，供 mock/server.js `require` 复用）转成 ESM 供浏览器加载。
 *
 * 为什么必须在 dev 也转：Vite 的 commonjs 插件只在 build 阶段生效，dev 直接把源码发给浏览器，
 * 而 CJS 文件没有具名导出 → 浏览器抛
 *   SyntaxError: The requested module '.../permission-matrix.js' does not provide an export named 'ROLE'
 * → main.js 整条依赖链求值失败 → #app 为空白（黑屏）。Vitest 走 SSR 转换能兼容 CJS，故单测与
 *   `vite build` 都不报错，只有 dev 预览暴露（2026-11-19 实测）。
 *
 * 做法：不复制、不改写事实源，仅在内存里把 `module.exports = {...}` 展开成具名导出，
 * 单一事实源（防手写漂移）不受影响。
 */
function cjsPermissionMatrixToEsm() {
  return {
    name: 'cjs-permission-matrix-to-esm',
    enforce: 'pre',
    transform(code, id) {
      const file = id.replace(/\\/g, '/').split('?')[0]
      if (!file.endsWith('constants/permission-matrix.js')) return null
      const m = code.match(/module\.exports\s*=\s*\{([\s\S]*?)\}/)
      if (!m) return null
      const names = m[1].split(',').map((s) => s.trim()).filter(Boolean)
      // 这些标识符在上面已是 `const xxx = ...`，因此只能用 export 列表（写 `export const X = X` 会重复声明）
      const generated = [
        `export { ${names.join(', ')} };`,
        `export default { ${names.join(', ')} };`
      ].join('\n')
      return { code: `${code.slice(0, m.index)}${generated}\n`, map: null }
    }
  }
}

// docs/09 §二：开发期 Vite 代理规避 CORS（后端不开放跨域）；生产走同域反代/静态托管
export default defineConfig({
  plugins: [cjsPermissionMatrixToEsm(), vue()],
  server: {
    port: 5173,
    // 事实源在 admin 根目录之外（仓库根 constants/），dev 需显式放行，否则 /@fs/ 请求被 403
    fs: { allow: ['..'] },
    proxy: {
      '/api': {
        target: process.env.VITE_PROXY_TARGET || 'http://localhost:8080',
        changeOrigin: true,
        secure: false,
        rewrite: (path) => path.replace(/^\/api/, '')
      }
    }
  },
  build: {
    // node_modules 依赖走 commonjs 转换。
    // 注：constants/permission-matrix.js 由上方 cjsPermissionMatrixToEsm 插件在 transform 阶段
    // 已转为 ESM，此处无需再列它（留着也是 no-op，曾被误当作 dev 方案而只修了 build → 黑屏）。
    commonjsOptions: {
      include: [/node_modules/]
    },
    // vendor 分包（docs/09 V3.6）：框架/图表/HTTP 各自成块，配合路由级懒加载降低首屏体积。
    // Element Plus 已于 M5 移除（自建 ui/ 组件库），940 kB 全量块不复存在；
    // 阈值 1000 仅作历史保守余量保留（2026-11-16 审查更新注释）。
    chunkSizeWarningLimit: 1000,
    rollupOptions: {
      output: {
        manualChunks: {
          'vendor-vue': ['vue', 'vue-router', 'pinia'],
          'vendor-echarts': ['echarts'],
          'vendor-http': ['axios']
        }
      }
    }
  },
  test: {
    environment: 'happy-dom',
    include: ['src/**/*.test.js']
  }
})
