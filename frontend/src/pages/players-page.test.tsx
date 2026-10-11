/**
 * /players on the [195] identity (SPEC-players, layout A "Almanac"). Static render: the search's
 * visible label, the "what a file holds" honesty key, the index, and the fenced Showcase part.
 * Scoped assertions (pins-decay rule), read through visibleText().
 */
import { MantineProvider } from "@mantine/core";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { renderToStaticMarkup } from "react-dom/server";
import { MemoryRouter } from "react-router";
import { describe, expect, it } from "vitest";

import { theme } from "../design/theme";
import { visibleText } from "../test-support/visible-text";

import PlayersPage from "./players-page";

function render(): string {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  return renderToStaticMarkup(
    <QueryClientProvider client={client}>
      <MemoryRouter>
        <MantineProvider theme={theme}>
          <PlayersPage />
        </MantineProvider>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

function after(html: string, marker: string): string {
  const at = html.indexOf(marker);
  return at < 0 ? "" : html.slice(at);
}

describe("PlayersPage", () => {
  const html = render();
  const text = visibleText(html);

  it("leads with the H1 and a search labelled 'Name or Statcast ID' (no jersey claim)", () => {
    expect(text).toContain("Look up a player.");
    expect(text).toContain("Name or Statcast ID");
    expect(text.toLowerCase()).not.toContain("jersey");
  });

  it("says what is live, what is not yet, and what is showcase", () => {
    expect(text).toContain("What a player file holds");
    expect(text).toContain("Not yet");
    expect(text).toContain("per-player truth-join we do not run yet");
  });

  it("fences the fixture lists in a Showcase part with the inline D4 tag", () => {
    const showcase = visibleText(
      after(html, 'aria-labelledby="pl-showcase-title"'),
    );
    expect(showcase).toContain("Showcase");
    expect(showcase).toContain("There is no leaders endpoint yet");
    expect(showcase).toContain("Featured reports");
    expect(showcase).toContain("Model standouts");
  });

  it("puts team colour marks ONLY in the index (30 of them), none in the head or showcase", () => {
    expect(html.match(/class="pl-mark"/g)).toHaveLength(30);
    const head = html.slice(
      0,
      html.indexOf('aria-labelledby="pl-index-title"'),
    );
    expect(head).not.toContain("pl-mark");
    expect(after(html, 'aria-labelledby="pl-showcase-title"')).not.toContain(
      "pl-mark",
    );
  });

  it("is quiet until a facet is picked (no roster table, no loading shells)", () => {
    const index = after(html, 'aria-labelledby="pl-index-title"');
    expect(index).not.toContain("Loading the roster");
    expect(visibleText(index)).toContain("One filter at a time");
  });
});
