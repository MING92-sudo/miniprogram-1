import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// docs/09 §二：开发期 Vite 代理规避 CORS（后端不开放跨域）；生产走同域反代/静态托管
export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
        rewrite: (path) => path.replace(/^\/api/, '')
      }
    }
  },
  build: {
    // vendor 分包（docs/09 V3.6）：框架/UI 库/图表/HTTP 各自成块，配合路由级懒加载降低首屏体积；
    // element-plus 为全量引入（main.js app.use(ElementPlus)），单块约 940 kB（gzip ~300 kB）——
    // 按需引入需引 unplugin-vue-components（新依赖，需联网安装），列为后置优化；
    // 故阈值放宽到 1000 kB 并注明原因（echarts 已改按需引入，不再超限）。
    chunkSizeWarningLimit: 1000,
    rollupOptions: {
      output: {
        manualChunks: {
          'vendor-vue': ['vue', 'vue-router', 'pinia'],
          'vendor-element': ['element-plus', '@element-plus/icons-vue'],
          'vendor-echarts': ['echarts'],
          'vendor-http': ['axios', 'dayjs']
        }
      }
    }
  },
  test: {
    environment: 'happy-dom',
    include: ['src/**/*.test.js']
  }
})
