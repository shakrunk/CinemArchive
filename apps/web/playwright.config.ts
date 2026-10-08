import { defineConfig, devices } from 'playwright/test'

export default defineConfig({
  testDir: './e2e',
  testIgnore: 'offline-library.spec.ts',
  fullyParallel: true,
  forbidOnly: !!process.env.CI,
  retries: 0,
  workers: 2,
  timeout: 30_000,
  globalTimeout: 600_000,
  reporter: [['list'], ['html', { open: 'never' }]],
  use: {
    baseURL: 'http://127.0.0.1:4178',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    contextOptions: { reducedMotion: 'reduce' },
  },
  projects: [
    { name: 'chromium', use: { ...devices['Desktop Chrome'] } },
    { name: 'firefox', use: { ...devices['Desktop Firefox'] } },
    { name: 'webkit', use: { ...devices['Desktop Safari'] } },
    { name: 'mobile-chromium', use: { ...devices['Pixel 7'] } },
  ],
  webServer: {
    // Separate output keeps this credential-free test build out of deploy artifacts.
    command: 'npm run build -- --mode e2e --outDir dist-e2e && npm run preview -- --outDir dist-e2e --host 127.0.0.1 --port 4178 --strictPort',
    url: 'http://127.0.0.1:4178',
    reuseExistingServer: false,
    timeout: 120_000,
    env: { VITE_SUPABASE_URL: '', VITE_SUPABASE_ANON_KEY: '' },
    stdout: 'pipe',
  },
})
