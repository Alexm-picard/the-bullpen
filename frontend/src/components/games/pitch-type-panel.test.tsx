/**
 * PitchTypePanel states, plus the [183] pins.
 *
 * The load-bearing tests here are the NEGATIVE ones: that no row is visually emphasised and that
 * no "most likely" headline appears. A distribution panel that quietly grows a winner is the exact
 * misreading decision [183] exists to prevent, and it would look like an improvement in review.
 */
import { MantineProvider } from "@mantine/core";
import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";

import { GameApiError, type PitchTypePriorResponse } from "../../api/games";
import { theme } from "../../design/theme";

import { PitchTypePanel } from "./pitch-type-panel";

const PRIOR: PitchTypePriorResponse = {
  probabilities: {
    FF: 0.302,
    SL: 0.281,
    SI: 0.154,
    CH: 0.121,
    CU: 0.084,
    FC: 0.041,
    OFF: 0.017,
  },
  modelName: "pitch_type_pre",
  servingVersion: "v1",
  priorPitches: 14237,
  elapsedMicros: 2100,
  correlationId: "cid-1",
};

function render(
  props: Partial<React.ComponentProps<typeof PitchTypePanel>> = {},
): string {
  return renderToStaticMarkup(
    <MantineProvider theme={theme}>
      <PitchTypePanel
        prior={PRIOR}
        isLoading={false}
        error={undefined}
        enabled
        {...props}
      />
    </MantineProvider>,
  );
}

/**
 * Visible text only - Mantine injects a stylesheet whose tokens would otherwise match probes.
 *
 * Duplicated from game-page.test.tsx. Extraction into a shared util is tracked in issue 400; it is
 * deliberately NOT done here, because this helper exists to dodge a CodeQL sanitizer finding and
 * moving it would change the file CodeQL analyses - re-siting a security alert inside a PR about
 * something else.
 */
function visibleText(html: string): string {
  const out: string[] = [];
  let i = 0;
  while (i < html.length) {
    const lt = html.indexOf("<", i);
    if (lt === -1) {
      out.push(html.slice(i));
      break;
    }
    out.push(html.slice(i, lt));
    const gt = html.indexOf(">", lt);
    if (gt === -1) break;
    const name = html
      .slice(lt + 1, gt)
      .trim()
      .toLowerCase()
      .split(/[\s/]/)[0];
    if (name === "style" || name === "script") {
      const close = html.toLowerCase().indexOf(`</${name}`, gt);
      const closeEnd = close === -1 ? -1 : html.indexOf(">", close);
      i = closeEnd === -1 ? html.length : closeEnd + 1;
    } else {
      i = gt + 1;
    }
    out.push(" ");
  }
  return out.join("");
}

describe("PitchTypePanel", () => {
  it("renders all seven classes with percentages, ranked by probability", () => {
    const text = visibleText(render());
    for (const label of [
      "Four-seam",
      "Slider",
      "Sinker",
      "Changeup",
      "Curveball",
      "Cutter",
      "Other",
    ]) {
      expect(text).toContain(label);
    }
    expect(text).toContain("30.2%");
    expect(text).toContain("28.1%");
    // Ranked: the highest appears before the lowest in document order.
    expect(text.indexOf("Four-seam")).toBeLessThan(text.indexOf("Other"));
  });

  it("captions the distribution as a PRIOR, never as a prediction", () => {
    const text = visibleText(render());
    expect(text).toContain("calibrated pitch-type prior");
    expect(text).toContain("not a top-1 prediction");
    expect(text).not.toMatch(/most likely/i);
    expect(text).not.toMatch(/predicted pitch/i);
    expect(text).not.toMatch(/\bwill throw\b/i);
  });

  it("surfaces the sample the prior was computed over", () => {
    // A prior over 12 career pitches and one over 14,000 are not comparable; the field exists so a
    // reader can tell, and a panel that hides it defeats the reason it was added.
    expect(visibleText(render())).toContain("14,237 career pitches");
    expect(
      visibleText(render({ prior: { ...PRIOR, priorPitches: 12 } })),
    ).toContain("12 career pitches");
  });

  it("gives no row visual emphasis over the others", () => {
    // THE [183] PIN. NextPitchPanel bolds + golds its argmax because a promoted outcome
    // distribution's most probable class is a meaningful read. Here top-1 is ~0.45, so an
    // emphasised row would be wrong more often than right while carrying the authority of an
    // answer. Asserted on the MARKUP, because the defect is visual: the fix someone would
    // plausibly make is a style change, not a text change, so a text-only pin would not catch it.
    const html = render();
    // Scan only the post-stylesheet slice: Mantine injects ~10KB of CSS whose contents are outside
    // this panel's control, so scanning the whole document makes these pins a latent false-red.
    const body = html.slice(html.lastIndexOf("</style>") + 1);
    const rows = body.split("<li").slice(1);
    expect(rows).toHaveLength(7);

    // STRUCTURAL INVARIANT over MARKUP AND STYLING, plus a companion pin over TEXT. The first
    // version enumerated the mechanisms I happened to think of and review found seven ways past
    // it: weight 600, a className, gold TEXT colour, a larger font-size, a border, a taller bar,
    // and a marker glyph. Normalising away the bar width and the text content, then demanding the
    // seven rows be byte-identical, closes every markup and styling mechanism - including the
    // gold-text case, which is the one that would really have happened, since NextPitchPanel
    // emphasises with THREE mechanisms including text colour.
    //
    // It does NOT close text-content emphasis, and saying otherwise would be the same defect as a
    // comment claiming a pin that does not exist: stripping text is exactly how this tolerates the
    // differing labels and percentages, so a "> " glyph prepended to the top label rides straight
    // through. The second assertion below is that missing side - each row's visible text must be
    // exactly its label and its percentage, nothing else.
    const normalised = rows.map((r) =>
      r
        .slice(0, r.indexOf("</li>"))
        // Anchored to the BAR by its neighbouring declaration: an unanchored width would also
        // normalise a percentage width applied to the label column, letting that through.
        .replace(/width:[\d.]+%;background:/g, "width:X;background:")
        .replace(/>[^<]*</g, "><"),
    );
    for (const row of normalised) {
      expect(row).toBe(normalised[0]);
    }

    // The text side: label + percentage and nothing else. Closes the marker-glyph mechanism the
    // markup invariant structurally cannot see.
    for (const raw of rows) {
      const cell = raw.slice(0, raw.indexOf("</li>"));
      const texts = [...cell.matchAll(/>([^<]+)</g)].map((m) => m[1]!.trim());
      expect(texts).toHaveLength(2);
      // Shape, not the label list: a plain word, so a prepended marker glyph or a "(most likely)"
      // suffix fails. Asserting shape rather than importing CLASS_LABELS also keeps the panel file
      // exporting only its component (react-refresh) and avoids a test that would pass merely
      // because it and the component read the same constant.
      expect(texts[0]).toMatch(/^[A-Z][a-z]+(-[a-z]+)?$/);
      expect(texts[1]).toMatch(/^\d+\.\d%$/);
    }
  });

  it("renders the server's own reason on a 503 rather than classifying it", () => {
    // Two distinct conditions share the status AND the error code today (issue 401), differing only in
    // prose, so any client-side classification would be string-matching English. The panel reports
    // what the server said.
    const html = render({
      prior: undefined,
      error: new GameApiError(
        503,
        "pitcher 476454 has no career-prior snapshot row; refusing rather than serving a prior computed over the wrong history",
      ),
    });
    const text = visibleText(html);
    expect(html).toContain("pitch-type-unavailable");
    expect(text).toContain("no career-prior snapshot row");
    expect(text).toContain("refusing rather than serving a prior");
    expect(text).not.toMatch(/\d+\.\d%/);
  });

  it("says so plainly when a 503 carries no reason at all", () => {
    const text = visibleText(
      render({ prior: undefined, error: new GameApiError(503, "") }),
    );
    expect(text).toContain("the server gave no reason");
  });

  it("renders an awaiting state when gated off, and makes no claim", () => {
    const text = visibleText(render({ enabled: false, prior: undefined }));
    expect(text).toContain("Awaiting a settled at-bat");
    expect(text).not.toMatch(/\d+\.\d%/);
  });

  it("renders a busy state while the prior is in flight", () => {
    const html = render({ prior: undefined, isLoading: true });
    expect(html).toContain('aria-busy="true"');
    expect(visibleText(html)).not.toMatch(/\d+\.\d%/);
  });

  it("degrades to a plain message on a non-503 error", () => {
    const text = visibleText(
      render({ prior: undefined, error: new GameApiError(500, "boom") }),
    );
    expect(text).toContain("unavailable right now");
    expect(text).not.toContain("boom"); // a 500's message is not a designed explanation
  });
});
