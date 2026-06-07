import react from "@vitejs/plugin-react";
import { defineConfig } from "vitest/config";

/**
 * Per-route chunks come from React.lazy in App.tsx. This function-style
 * manualChunks splits the remaining heavy vendor deps into their own
 * long-cacheable bundles so a Mantine patch release doesn't invalidate
 * everything else.
 */
function vendorChunk(id: string): string | undefined {
  if (id.includes("node_modules/@mantine/")) return "mantine";
  if (id.includes("node_modules/@tanstack/")) return "tanstack";
  if (
    id.includes("node_modules/react-router") ||
    id.includes("node_modules/@remix-run/router")
  ) {
    return "router";
  }
  return undefined;
}

export default defineConfig({
  plugins: [react()],
  build: {
    rollupOptions: {
      output: {
        manualChunks: vendorChunk,
      },
    },
  },
  // A2 — coverage measurement (audit remediation). v8 provider; non-gating baseline
  // for now (no `thresholds` block yet) so we publish an honest number before ratcheting.
  // Excludes design-system showcase fixtures + generated/config files from the denominator
  // so the percentage reflects logic, not hand-authored data tables.
  test: {
    // Scope vitest to src/ so it doesn't try to collect the Playwright specs under e2e/
    // (which call Playwright's test(), not vitest's). E2E runs via `npm run test:e2e`.
    include: ["src/**/*.{test,spec}.{ts,tsx}"],
    coverage: {
      provider: "v8",
      reporter: ["text-summary", "json-summary", "html", "lcov"],
      reportsDirectory: "coverage",
      include: ["src/**/*.{ts,tsx}"],
      exclude: [
        "src/**/*.test.{ts,tsx}",
        "src/**/__snapshots__/**",
        "src/data/**", // fixture data tables, not logic
        "src/main.tsx",
        "src/**/*.d.ts",
      ],
      // Regression floor a few points below current (2026-06-07: lines 72.8 / stmts 71.5 /
      // branches 60.6 / functions 67.9). Gates a coverage DROP without blocking today; ratchet up
      // as coverage rises. This is a no-backsliding guard, not the 75% aspiration.
      thresholds: {
        lines: 65,
        statements: 65,
        branches: 55,
        functions: 60,
      },
    },
  },
});
