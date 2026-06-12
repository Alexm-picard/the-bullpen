/**
 * <ErrorBoundary> — the top-level safety net so a thrown render error degrades
 * to a calm fallback card instead of a white screen.
 *
 * The app routes via `<BrowserRouter>` + `<Routes>` (not `createBrowserRouter`),
 * so there is no router-native `errorElement`; a React class boundary is the
 * mechanism. It wraps the whole route tree in `App.tsx`. A render that throws
 * (a bad fixture shape, an unexpected null deref, a lazy-chunk eval error)
 * surfaces here rather than blanking the SPA — important because the public
 * site is the first thing a visitor clicks and a white screen is the worst
 * possible first impression.
 *
 * Deliberately dependency-free: no `react-error-boundary`, no Sentry coupling
 * (the optional Sentry SDK in `main.tsx` patches React's own error reporting
 * when a DSN is present; this boundary just logs + renders the fallback).
 *
 * Token discipline: colors come from `design/tokens`; Mantine components carry
 * the theme. No hex in this file.
 */

import { Button, Container, Stack, Text, Title } from "@mantine/core";
import { Component, type ErrorInfo, type ReactNode } from "react";

import { colors } from "../../design/broadcast";

type Props = { children: ReactNode };
type State = { error: Error | null };

export class ErrorBoundary extends Component<Props, State> {
  state: State = { error: null };

  static getDerivedStateFromError(error: Error): State {
    return { error };
  }

  componentDidCatch(error: Error, info: ErrorInfo): void {
    // Off the request path; the optional Sentry SDK (main.tsx) captures
    // uncaught errors on its own when a DSN is configured. Log for local dev.
    console.error("ErrorBoundary caught a render error:", error, info);
  }

  private handleReload = (): void => {
    this.setState({ error: null });
    if (typeof window !== "undefined") {
      window.location.reload();
    }
  };

  render(): ReactNode {
    if (this.state.error === null) {
      return this.props.children;
    }
    return (
      <Container
        size="sm"
        py="xl"
        style={{ minHeight: "60vh", display: "grid", placeItems: "center" }}
      >
        <Stack gap="sm" align="center" ta="center">
          <Text size="sm" fw={700} style={{ color: colors.goldInk }}>
            SOMETHING WENT WRONG
          </Text>
          <Title order={2}>This page hit an unexpected error</Title>
          <Text style={{ color: colors.textMuted }} maw={440}>
            The rest of the site is fine — reloading usually clears it. If it
            keeps happening, the backend may be briefly unavailable.
          </Text>
          <Button onClick={this.handleReload} mt="xs">
            Reload the page
          </Button>
        </Stack>
      </Container>
    );
  }
}
