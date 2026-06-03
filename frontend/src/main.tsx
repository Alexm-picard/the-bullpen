import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { MantineProvider } from "@mantine/core";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import "./index.css";
import App from "./App";
import { ErrorBoundary } from "./components/shared/error-boundary";
import { theme } from "./design/theme";

// A6 (ADR-0008): error tracking via Sentry → self-hosted GlitchTip. Dynamically
// imported ONLY when a DSN is configured, so the SDK ships as a lazy chunk (off the
// initial bundle / budget) and never loads in dev or the default no-DSN build.
const sentryDsn = import.meta.env.VITE_SENTRY_DSN;
if (sentryDsn) {
  void import("@sentry/react").then((Sentry) => {
    Sentry.init({
      dsn: sentryDsn,
      environment: import.meta.env.VITE_SENTRY_ENVIRONMENT ?? "production",
      release: import.meta.env.VITE_SENTRY_RELEASE,
      tracesSampleRate: 0,
      sendDefaultPii: false,
    });
  });
}

const queryClient = new QueryClient({
  defaultOptions: { queries: { retry: 1, staleTime: 5_000 } },
});

createRoot(document.getElementById("root")!).render(
  <StrictMode>
    <MantineProvider theme={theme}>
      <QueryClientProvider client={queryClient}>
        <ErrorBoundary>
          <App />
        </ErrorBoundary>
      </QueryClientProvider>
    </MantineProvider>
  </StrictMode>,
);
