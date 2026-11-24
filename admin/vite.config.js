import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

/** dev 也需转换该 CJS 模块，否则浏览器无法识别具名导出。 */
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
    commonjsOptions: {
      include: [/node_modules/]
    },
    chunkSizeWarningLimit: 1000,
    rollupOptions: {
      output: {
        manualChunks: {
          'vendor-vue': ['vue', 'vue-router', 'pinia'],
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
