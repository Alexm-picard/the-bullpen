/**
 * <ErrorBoundary> - the safety net so a thrown render error degrades to a calm
 * page instead of a white screen.
 *
 * The app routes via `<BrowserRouter>` + `<Routes>` (not `createBrowserRouter`),
 * so there is no router-native `errorElement`; a React class boundary is the
 * mechanism. It wraps the whole tree in `main.tsx` and each route in `App.tsx`.
 * A render that throws (a bad payload shape, an unexpected null deref, a
 * lazy-chunk eval error) surfaces here rather than blanking the SPA - important
 * because a white screen is the worst possible first impression.
 *
 * Deliberately dependency-free: no `react-error-boundary`, no Sentry coupling
 * (the optional Sentry SDK in `main.tsx` patches React's own error reporting
 * when a DSN is present; this boundary just logs + renders the fallback).
 *
 * [195] editorial grammar, the 404 page's at its smallest: dateline, slug,
 * serif H1, a plain-language dek, then the ways out as links. No Mantine and no
 * router: the root boundary sits outside both the router and ThemeProvider, so
 * the ways out are plain buttons and a plain href, and the edition comes from
 * editorial.css (which follows the OS preference before data-theme is stamped).
 */

import { Component, type ErrorInfo, type ReactNode } from "react";

const ET_DATE = new Intl.DateTimeFormat("en-US", {
  weekday: "long",
  month: "long",
  day: "numeric",
  year: "numeric",
  timeZone: "America/New_York",
});

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

  // Re-render the subtree in place: a transient throw (a chunk that failed to
  // evaluate once, a momentary bad payload) recovers without a full reload.
  private handleRetry = (): void => {
    this.setState({ error: null });
  };

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
      <div className="ed-page">
        <div className="ed-col">
          <p className="ed-dateline">
            <span>{ET_DATE.format(new Date())}</span>
            <span className="ed-dateline__issued">Render error</span>
            <span>SOMETHING WENT WRONG</span>
          </p>

          <div className="eb">
            <p className="ed-slug">Page error</p>
            <h1 className="ed-h1">This page hit an unexpected error</h1>
            <p className="ed-dek">
              The rest of the site is fine, and reloading usually clears it. If
              it keeps happening, the backend may be briefly unavailable.
            </p>
            <p className="nf-links">
              <button
                type="button"
                className="ed-link eb-action"
                onClick={this.handleRetry}
              >
                Try again
              </button>
              <button
                type="button"
                className="ed-link eb-action"
                onClick={this.handleReload}
              >
                Reload the page
              </button>
              {/* Plain href: the root boundary sits outside the router. */}
              <a className="ed-link" href="/">
                Back to home
              </a>
            </p>
          </div>
        </div>
      </div>
    );
  }
}
