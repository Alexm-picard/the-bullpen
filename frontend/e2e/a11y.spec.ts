import { AxeBuilder } from "@axe-core/playwright";
import { expect, test } from "@playwright/test";

/**
 * Real a11y validation (W3.4): runs axe-core against the rendered app on each public route and
 * fails on any CRITICAL or SERIOUS WCAG 2.0/2.1 A/AA violation. This is the proper replacement for
 * the static regex heuristic in scripts/audit-a11y-static.mjs (which its own header calls a "cheap
 * surrogate" for exactly this axe run) - axe evaluates the live accessibility tree, catching
 * missing form labels, invalid ARIA, landmark/heading structure, name-role-value, etc.
 *
 * Scope: the public routes reachable from the header nav. The /admin/* operator tools are unlisted
 * + auth-gated and out of scope. Impact is filtered to critical/serious so the gate blocks the
 * defects that actually lock a user out, not minor/moderate best-practice nits (which can be
 * ratcheted in later).
 */
// D4: extended to the parameterized + remaining public routes. The ids are arbitrary - with no
// backend behind the preview server the pages render their error/empty states, which must be
// accessible too (that IS the common cold state).
const ROUTES = [
  "/",
  "/parks",
  "/ops",
  "/about",
  "/games",
  "/players",
  "/players/592450",
  "/games/745804",
  "/accuracy",
];

for (const route of ROUTES) {
  test(`no critical or serious a11y violations on ${route}`, async ({
    page,
  }) => {
    await page.goto(route);
    // Wait for the page's first heading so axe audits the rendered view, not a loading shell.
    await page.locator("h1").first().waitFor();

    // color-contrast is ENFORCED since D4 (the dedicated contrast pass the earlier exclusion
    // deferred to): the broadcast palette's conditional-format cells were brought to AA, so the
    // rule now guards regressions instead of being waived wholesale.
    const results = await new AxeBuilder({ page })
      .withTags(["wcag2a", "wcag2aa", "wcag21a", "wcag21aa"])
      .analyze();

    const blocking = results.violations.filter(
      (v) => v.impact === "critical" || v.impact === "serious",
    );

    // On failure, surface rule id + impact + node count for fast triage.
    expect(
      blocking.map((v) => `${v.id} [${v.impact}] x${v.nodes.length}`),
      `axe critical/serious violations on ${route}`,
    ).toEqual([]);
  });
}
