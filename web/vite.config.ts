import { defineConfig } from 'vitest/config'
import vue from '@vitejs/plugin-vue'
import { fileURLToPath, URL } from 'node:url'

/**
 * 开发代理说明
 * ---------------------------------------------------------------
 * 后端 context-path 是 /api（server.servlet.context-path: /api），
 * 端口 8123。前端所有请求都发相对路径 /api/**，由 Vite 代理转发。
 *
 * 这样做的原因：
 * 1. 后端没有配置 CORS，浏览器直连 8123 会被拦；
 * 2. 前端不需要知道完整服务器地址，避免把地址写死进代码；
 * 3. 生产部署时把静态产物交给网关，同源即可，无需改代码。
 *
 * 代理目标可用环境变量 VITE_PROXY_TARGET 覆盖（默认 http://localhost:8123）。
 */
const proxyTarget = process.env.VITE_PROXY_TARGET || 'http://localhost:8123'

export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: proxyTarget,
        changeOrigin: true,
      },
    },
  },
  test: {
    environment: 'jsdom',
    globals: true,
    include: ['src/**/*.spec.ts'],
    css: false,
  },
})
