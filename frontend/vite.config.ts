import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'
import { defineConfig } from 'vite'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react(), tailwindcss()],
  server: {
    port: 5173,
    proxy: {
      // 백엔드(Spring, :8080) API 요청을 프론트 개발서버로 프록시.
      // 인증 경로도 /api/v1/auth/... 로 통일돼서 /api 하나로 충분함 (예전엔 /oauth2, /login도 필요했음)
      '/api': 'http://localhost:8080',
    },
  },
})
