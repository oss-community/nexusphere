import { fileURLToPath } from 'node:url'
import type { Plugin } from 'vite'
import { defineConfig } from 'vitest/config'
import react from '@vitejs/plugin-react'

const sdk = fileURLToPath(new URL('../sdk/typescript/src/index.ts', import.meta.url))

const policy = [
  "default-src 'none'",
  "script-src 'unsafe-inline'",
  "style-src 'unsafe-inline'",
  'img-src data:',
  "connect-src 'none'",
  "form-action 'none'",
  "base-uri 'none'",
].join('; ')

function singleFile(): Plugin {
  return {
    name: 'nexusphere-single-file',
    apply: 'build',
    enforce: 'post',
    generateBundle(_, bundle) {
      const page = bundle['index.html']
      if (!page || page.type !== 'asset') {
        return
      }
      let html = String(page.source)
      for (const [name, chunk] of Object.entries(bundle)) {
        if (chunk.type === 'chunk' && chunk.isEntry) {
          html = html.replace(
            new RegExp(`<script type="module" crossorigin src="[^"]*${name}"></script>`),
            () => `<script type="module">${chunk.code.replaceAll('</script', '<\\/script')}</script>`,
          )
          delete bundle[name]
        } else if (chunk.type === 'asset' && name.endsWith('.css')) {
          html = html.replace(
            new RegExp(`<link rel="stylesheet" crossorigin href="[^"]*${name}">`),
            () => `<style>${String(chunk.source)}</style>`,
          )
          delete bundle[name]
        }
      }
      page.source = html.replace(
        '<meta charset="UTF-8" />',
        `<meta charset="UTF-8" />\n    <meta http-equiv="Content-Security-Policy" content="${policy}" />`,
      )
    },
  }
}

export default defineConfig({
  plugins: [react(), singleFile()],
  resolve: {
    alias: { '@nexusphere/ledger': sdk },
  },
  build: {
    assetsInlineLimit: Number.MAX_SAFE_INTEGER,
    modulePreload: false,
  },
  server: {
    port: 5174,
    fs: { allow: ['..'] },
  },
  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: ['./src/setup-tests.ts'],
    include: ['src/**/*.test.{ts,tsx}'],
  },
})
