import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// 开发期 Vite 代理规避 CORS（后端不开放跨域）；生产走同域反代/静态托管
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
  test: {
    environment: 'happy-dom',
    include: ['src/**/*.test.js']
  }
})
