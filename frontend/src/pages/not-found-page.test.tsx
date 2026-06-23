/**
 * S6 smoke test - the catch-all 404 page renders its code, message, and a link home.
 * The route wiring itself (path="*" -> NotFoundPage) is asserted end-to-end in e2e/smoke.spec.ts.
 */
import { renderToStaticMarkup } from "react-dom/server";
import { MemoryRouter } from "react-router-dom";
import { describe, expect, it } from "vitest";

import NotFoundPage from "./not-found-page";

describe("NotFoundPage", () => {
  it("renders the 404 code, message, and a link home", () => {
    const html = renderToStaticMarkup(
      <MemoryRouter>
        <NotFoundPage />
      </MemoryRouter>,
    );
    expect(html).toContain("404");
    expect(html).toContain("No play at this base.");
    expect(html).toContain('href="/"');
  });
});
