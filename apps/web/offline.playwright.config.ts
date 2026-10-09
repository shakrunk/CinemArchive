import { defineConfig, devices } from 'playwright/test'

export default defineConfig({
  testDir: './e2e', testMatch: 'offline-library.spec.ts', workers: 1, timeout: 30_000,
  reporter: 'list', use: { baseURL: 'http://127.0.0.1:4180' },
  projects: [
    { name: 'chromium', use: devices['Desktop Chrome'] },
    { name: 'firefox', use: devices['Desktop Firefox'] },
    { name: 'webkit', use: devices['Desktop Safari'] },
  ],
  webServer: {
    command: 'npm run dev -- --mode e2e --host 127.0.0.1 --port 4180 --strictPort',
    url: 'http://127.0.0.1:4180', reuseExistingServer: false,
    env: { VITE_SUPABASE_URL: '', VITE_SUPABASE_ANON_KEY: '' },
  },
})
