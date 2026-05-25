import react from "@vitejs/plugin-react";
import { defineConfig } from "vite";

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
});
