/**
 * Unit tests for <ErrorBoundary>.
 *
 * The suite renders via `renderToStaticMarkup` (no jsdom), which does not drive
 * React's client-side error-catch path — so the catch-and-render-fallback
 * behaviour is verified manually (kill the backend / throw in a page). Here we
 * cover what SSR can prove: children pass through when there's no error, and the
 * `getDerivedStateFromError` reducer maps a thrown error into render state.
 */
import { MantineProvider } from "@mantine/core";
import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";

import { theme } from "../../design/theme";

import { ErrorBoundary } from "./error-boundary";

function render(ui: React.ReactElement): string {
  return renderToStaticMarkup(
    <MantineProvider theme={theme}>{ui}</MantineProvider>,
  );
}

describe("ErrorBoundary", () => {
  it("renders its children when there is no error", () => {
    const html = render(
      <ErrorBoundary>
        <div>healthy content</div>
      </ErrorBoundary>,
    );
    expect(html).toContain("healthy content");
    expect(html).not.toContain("SOMETHING WENT WRONG");
  });

  it("getDerivedStateFromError stores the error so the fallback can render", () => {
    const err = new Error("boom");
    expect(ErrorBoundary.getDerivedStateFromError(err)).toEqual({ error: err });
  });
});
