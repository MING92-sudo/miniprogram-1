import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// docs/09 §二：开发期 Vite 代理规避 CORS（后端不开放跨域）；生产走同域反代/静态托管
export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5173,
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
    // permission-matrix 镜像为 CJS（小程序 mock/Node 脚本同源复用），构建期显式纳入 commonjs 转换
    commonjsOptions: {
      include: [/node_modules/, /constants\/permission-matrix\.js/]
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
