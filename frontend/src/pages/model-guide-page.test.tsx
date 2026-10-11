/**
 * /models/guide static render (SPEC-guide-about). Assertions run on visibleText() (never
 * regex-stripped markup) and are scoped to the card they are about, so a second emitter of the same
 * words elsewhere on the page cannot keep a dead pin alive.
 */
import { MantineProvider } from "@mantine/core";
import { renderToStaticMarkup } from "react-dom/server";
import { MemoryRouter } from "react-router";
import { describe, expect, it } from "vitest";

import {
  countFigure,
  GUIDE_CHECKS,
  GUIDE_MARGIN,
  type GuideAnchor,
} from "../data/guide-evidence";
import { PHYSICS_CAVEAT_LEAD } from "../data/parks-claims";
import { theme } from "../design/theme";
import { visibleText } from "../test-support/visible-text";

import ModelGuidePage from "./model-guide-page";

function render(
  entry:
    | string
    | { pathname: string; hash: string; state?: unknown } = "/models/guide",
): string {
  return renderToStaticMarkup(
    <MemoryRouter initialEntries={[entry]}>
      <MantineProvider theme={theme}>
        <ModelGuidePage />
      </MantineProvider>
    </MemoryRouter>,
  );
}

/** The markup of one card, from its opening tag to its closing </section>. */
function card(html: string, anchor: string): string {
  const at = html.indexOf(`id="${anchor}"`);
  expect(at).toBeGreaterThan(-1);
  const start = html.lastIndexOf("<section", at);
  const end = html.indexOf("</section>", at);
  return html.slice(start, end);
}

const MODEL_ANCHORS: GuideAnchor[] = [
  "next-pitch",
  "pitch-type",
  "post-pitch",
  "batted-ball",
];

describe("ModelGuidePage", () => {
  const html = render();
  const text = visibleText(html);

  it("is the editorial guide, framed as offline evidence", () => {
    expect(text).toContain("The model guide");
    expect(text).toContain("Four models, and the test each one passed");
    expect(text).toContain("not live accuracy");
    expect((html.match(/<h1/g) ?? []).length).toBe(1);
  });

  it("keeps every anchor the other pages link to, plus the new #post-pitch", () => {
    for (const id of [...MODEL_ANCHORS, "how-scored"]) {
      expect(html).toContain(`id="${id}"`);
    }
  });

  it("every in-page link targets an id that exists", () => {
    const targets = [...html.matchAll(/href="#([^"]+)"/g)].map((m) => m[1]);
    expect(targets.length).toBeGreaterThanOrEqual(5);
    for (const t of targets) expect(html).toContain(`id="${t}"`);
  });

  it("says three models speak during a game and one reads after, not 'four predict live'", () => {
    expect(text).toContain("Three of them speak during a game");
    expect(text).not.toMatch(/four calibrated models predict live/i);
  });

  it("one number, one place: each margin figure is printed exactly once on the page", () => {
    for (const anchor of MODEL_ANCHORS) {
      for (const f of GUIDE_MARGIN[anchor]) {
        expect(countFigure(text, f.value), `${anchor} ${f.value}`).toBe(1);
        // ...and it is printed inside its own card's margin.
        expect(visibleText(card(html, anchor))).toContain(f.value);
      }
    }
  });

  it("each card's checks table carries its gate rows, with failures named in words", () => {
    for (const anchor of MODEL_ANCHORS) {
      const t = visibleText(card(html, anchor));
      for (const c of GUIDE_CHECKS[anchor]) {
        expect(t).toContain(c.check);
        expect(t).toContain(c.verdict);
      }
    }
    expect(visibleText(card(html, "pitch-type"))).toContain("Over");
    expect(visibleText(card(html, "batted-ball"))).toContain("LR wins");
  });

  it("[163]: the batted-ball card says it is a physics estimate", () => {
    const t = visibleText(card(html, "batted-ball"));
    expect(t).toContain(PHYSICS_CAVEAT_LEAD);
    expect(t).toContain("not a record of what happened");
  });

  it("[183]: the pitch-type card makes no top-1 claim", () => {
    expect(visibleText(card(html, "pitch-type"))).not.toMatch(/top-1/i);
  });

  it("drops the unverifiable calibrator size and the rule-7 miscitation", () => {
    expect(text).not.toContain("241 KB");
    expect(text).not.toMatch(/rule 7/i);
  });

  it("how-scored names the live 30-day reliability chart and the 2026 holdout", () => {
    const t = visibleText(card(html, "how-scored"));
    expect(t).toContain("30-day reliability chart");
    expect(t).toContain("2026 season is never trained or tuned on");
  });

  it("marks no card as arrived without a hash", () => {
    expect(html).not.toContain('data-arrived="true"');
    expect(text).not.toContain("You came from");
  });
});

describe("ModelGuidePage, arrived from a link", () => {
  it("marks the hashed card and says where the reader came from", () => {
    const html = render({
      pathname: "/models/guide",
      hash: "#pitch-type",
      state: { from: "the game page" },
    });
    expect(card(html, "pitch-type")).toContain('data-arrived="true"');
    expect(visibleText(card(html, "pitch-type"))).toContain(
      "You came from the game page.",
    );
    expect(card(html, "next-pitch")).not.toContain('data-arrived="true"');
  });

  it("marks the card but invents no origin when the link carried none", () => {
    const html = render({ pathname: "/models/guide", hash: "#post-pitch" });
    expect(card(html, "post-pitch")).toContain('data-arrived="true"');
    expect(visibleText(html)).not.toContain("You came from");
  });
});
