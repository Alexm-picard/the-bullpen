/**
 * /parks static render (SPEC-parks). The query is left in its loading state (static render), which
 * is exactly the first-load frame: shells, no numbers, and the honesty copy already in place.
 * Assertions run on visibleText() (never regex-stripped markup) and are scoped to the part of the
 * page they are about, so a second emitter of the same words cannot keep a dead pin alive.
 */
import { MantineProvider } from "@mantine/core";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { renderToStaticMarkup } from "react-dom/server";
import { MemoryRouter } from "react-router";
import { describe, expect, it } from "vitest";

import { OBSERVED_HOME_RUN_SPRAY_MAX_DEG, SPRAY_LIMIT_DEG } from "../api/parks";
import { BUILD_SHA } from "../build-info";
import {
  PHYSICS_CAVEAT_BODY,
  PHYSICS_CAVEAT_LEAD,
  SHOWCASE_FACTORS_NOTE,
} from "../data/parks-claims";
import { PARK_ROWS } from "../data/parks-fixtures";
import { theme } from "../design/theme";
import { visibleText } from "../test-support/visible-text";

import ParksPage from "./parks-page";

function render(): string {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  return renderToStaticMarkup(
    <QueryClientProvider client={client}>
      <MemoryRouter>
        <MantineProvider theme={theme}>
          <ParksPage />
        </MantineProvider>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

/** The markup of one section, by the id its heading/label carries. */
function section(html: string, labelledBy: string): string {
  const at = html.indexOf(`aria-labelledby="${labelledBy}"`);
  expect(at).toBeGreaterThan(-1);
  const end = html.indexOf("</section>", at);
  return html.slice(at, end);
}

describe("ParksPage (static)", () => {
  const html = render();
  const text = visibleText(html);

  it("is the editorial park explorer, with the physics-estimate framing in the dateline", () => {
    expect(text).toContain("Park explorer");
    expect(text).toContain("A calibrated physics estimate");
    expect(text).toContain("Where does it leave the yard?");
    expect(text).toContain(BUILD_SHA);
  });

  it("puts the [163] caveat in the answer column, before any number arrives", () => {
    const answer = visibleText(section(html, "pk-answer-label"));
    expect(answer).toContain(PHYSICS_CAVEAT_LEAD);
    expect(answer).toContain(PHYSICS_CAVEAT_BODY);
    // First load: a shell, never an invented count.
    expect(answer).toContain("Scoring this ball across the 30 parks");
    expect(answer).not.toMatch(/\d+ of \d+/);
  });

  it("fences the fixture factors in one labelled Showcase part, all 30 parks", () => {
    const showcase = visibleText(section(html, "pk-showcase-title"));
    expect(showcase).toContain("Showcase");
    expect(showcase).toContain("fixtures, not from the models");
    expect(showcase).toContain(SHOWCASE_FACTORS_NOTE);
    for (const r of PARK_ROWS) expect(showcase).toContain(r.parkName);
    // The Every park part carries no fixture value.
    const every = visibleText(section(html, "pk-every-title"));
    expect(every).not.toContain("Showcase");
  });

  it("drops the fixture copy that described a model that does not exist ([202])", () => {
    for (const gone of [
      "park_factor_v2",
      "shrinkage",
      "437,210",
      "Edition",
      "Appendix",
      "Spotlight",
      "Mini Heatmaps",
    ]) {
      expect(text).not.toContain(gone);
    }
  });

  it("labels direction absolutely (left/right field), never pull/oppo, and never rules a ball foul", () => {
    expect(text).toContain("Left field");
    expect(text).toContain("Right field");
    expect(text).toContain("Straightaway");
    expect(text).not.toMatch(/\bpull\b|\boppo/i);
    expect(text).not.toMatch(/\bfoul\b/i);
  });

  it("says the field is generic and links to how the model was built", () => {
    expect(text).toContain("The field is generic");
    expect(html).toContain('href="/models/guide"');
  });

  it("lets the user reach the sprays the model was trained on", () => {
    // Pinned to the EMPIRICAL bound, not to itself: the slider's range must admit the widest spray
    // ever observed on a home run.
    expect(SPRAY_LIMIT_DEG).toBeGreaterThan(OBSERVED_HOME_RUN_SPRAY_MAX_DEG);
    expect(html).toContain(`aria-valuemax="${SPRAY_LIMIT_DEG}"`);
  });
});
