import { defineConfig, devices } from "@playwright/test";

/**
 * A7 — E2E smoke. Builds the SPA and serves the production bundle via `vite preview`,
 * then drives a headless Chromium over the fixture-backed pages + routing. Kept to the
 * pages that render without a backend (the live `/games/:id` view needs the API); this
 * is a "does the SPA boot, route, and render without crashing" smoke, not a data test.
 *
 * CI activation is automatic: frontend.yml runs this when playwright.config.ts exists.
 */
export default defineConfig({
  testDir: "./e2e",
  fullyParallel: true,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 1 : 0,
  workers: process.env.CI ? 1 : undefined,
  reporter: process.env.CI ? [["html", { open: "never" }]] : "list",
  use: {
    baseURL: "http://localhost:4173",
    trace: "on-first-retry",
    screenshot: "only-on-failure",
  },
  projects: [{ name: "chromium", use: { ...devices["Desktop Chrome"] } }],
  webServer: {
    command: "npm run build && npm run preview -- --port 4173 --strictPort",
    url: "http://localhost:4173",
    reuseExistingServer: !process.env.CI,
    timeout: 120_000,
  },
});
