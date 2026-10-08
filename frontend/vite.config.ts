import { defineConfig } from 'vitest/config'
import react from '@vitejs/plugin-react'

const ledger = process.env.LEDGER_URL ?? 'http://localhost:8090'

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/api': ledger,
      '/public': ledger,
    },
  },
  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: ['./src/setup-tests.ts'],
  },
})
