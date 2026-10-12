/**
 * Unit tests for <ErrorBoundary>.
 *
 * The suite renders via `renderToStaticMarkup` (no jsdom), which does not drive
 * React's client-side error-catch path - so the catch-and-render-fallback
 * behaviour is verified manually (kill the backend / throw in a page). Here we
 * cover what SSR can prove: children pass through when there's no error, and the
 * `getDerivedStateFromError` reducer maps a thrown error into render state.
 */
import { MantineProvider } from "@mantine/core";
import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";

import { theme } from "../../design/theme";
import { visibleText } from "../../test-support/visible-text";

import { ErrorBoundary } from "./error-boundary";

function render(ui: React.ReactElement): string {
  return renderToStaticMarkup(
    <MantineProvider theme={theme}>{ui}</MantineProvider>,
  );
}

function fallbackHtml(): string {
  // SSR cannot drive the catch path, so render the fallback branch directly
  // from an instance whose state already holds an error.
  const boundary = new ErrorBoundary({ children: null });
  boundary.state = { error: new Error("boom") };
  return render(<>{boundary.render()}</>);
}

describe("ErrorBoundary", () => {
  it("renders its children when there is no error", () => {
    const text = visibleText(
      render(
        <ErrorBoundary>
          <div>healthy content</div>
        </ErrorBoundary>,
      ),
    );
    expect(text).toContain("healthy content");
    expect(text).not.toContain("SOMETHING WENT WRONG");
  });

  it("getDerivedStateFromError stores the error so the fallback can render", () => {
    const err = new Error("boom");
    expect(ErrorBoundary.getDerivedStateFromError(err)).toEqual({ error: err });
  });

  it("fallback offers an in-place retry, a full reload, and a router-free way home", () => {
    const html = fallbackHtml();
    const text = visibleText(html);
    expect(text).toContain("SOMETHING WENT WRONG");
    expect(text).toContain("This page hit an unexpected error");
    expect(text).toContain("Try again");
    expect(text).toContain("Reload the page");
    expect(html).toMatch(/<a[^>]*href="\/"[^>]*>Back to home<\/a>/);
  });

  it("fallback speaks the editorial grammar: one serif h1 on the edition ground", () => {
    const html = fallbackHtml();
    expect(html).toContain('class="ed-page"');
    expect(html).toContain('class="ed-dateline"');
    expect((html.match(/<h1\b/g) ?? []).length).toBe(1);
    expect(html).toMatch(/<h1 class="ed-h1">/);
    // The two actions are real buttons (keyboard + screen reader), styled as editorial links.
    expect(
      (html.match(/<button type="button" class="ed-link eb-action"/g) ?? [])
        .length,
    ).toBe(2);
  });
});
