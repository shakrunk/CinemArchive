import path from 'path'
import { defineConfig } from 'vitest/config'
import react from '@vitejs/plugin-react'

// Showtime fixtures are written with -06:00 offsets and asserted in local time; pin the zone so
// results don't depend on the machine (CI runs in UTC). Set before workers spawn so they inherit it.
process.env.TZ = 'America/Denver'

export default defineConfig({
  plugins: [react()],
  resolve: {
    alias: {
      '@': path.resolve(import.meta.dirname, './src'),
      'src': path.resolve(import.meta.dirname, './src'),
    },
  },
  test: {
    environment: 'jsdom',
    setupFiles: ['./src/test/setup.ts'],
    css: false,
    // SQL suites use node:test with real embedded PostgreSQL, not jsdom.
    exclude: ['node_modules', 'dist', 'e2e', 'scripts/**'],
  },
})
