import { expect, test } from "@playwright/test";

/**
 * D2 small-viewport gate. The header nav got its burger + drawer in D1; this asserts the PAGES
 * survive a 375px phone viewport.
 *
 * The invariant: the page body must never scroll horizontally. Wide content (tables, diagram
 * grids, code blocks) is allowed to be wide, but it has to scroll inside its OWN
 * overflow-x container, not push the document sideways. A page that fails this reads as broken
 * on a phone - the whole layout drifts under the thumb.
 *
 * Deliberately paired with a per-element check: pinpointing WHICH node overflows makes the
 * failure actionable instead of "something on /parks is too wide".
 */
const PHONE = { width: 375, height: 812 };

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
  test(`no horizontal page overflow at ${PHONE.width}px on ${route}`, async ({
    page,
  }) => {
    await page.setViewportSize(PHONE);
    await page.goto(route);
    await page.locator("h1").first().waitFor();

    const report = await page.evaluate((vw) => {
      const doc = document.documentElement;
      // Any element wider than the viewport that is NOT inside an element which scrolls its own
      // overflow is a real defect. Walk up to check for a scroll container.
      const scrollsItsOwnOverflow = (el: Element | null): boolean => {
        for (let n = el; n; n = n.parentElement) {
          const ov = getComputedStyle(n).overflowX;
          if (ov === "auto" || ov === "scroll") return true;
        }
        return false;
      };
      const offenders: string[] = [];
      for (const el of Array.from(document.body.querySelectorAll("*"))) {
        const r = el.getBoundingClientRect();
        if (r.width === 0 && r.height === 0) continue;
        // Geometry INSIDE an <svg> reports its drawn bounds even when the SVG viewport clips it
        // (a path drawn past the viewBox is invisible, not an overflow). Only the outer <svg>
        // element's own box can push the page, so skip svg descendants.
        if (el.tagName !== "svg" && el.closest("svg")) continue;
        // right edge past the viewport, and it does not live in a scroller
        if (r.right > vw + 1 && !scrollsItsOwnOverflow(el.parentElement)) {
          const id = el.id ? `#${el.id}` : "";
          const cls =
            typeof el.className === "string" && el.className
              ? `.${el.className.trim().split(/\s+/).slice(0, 2).join(".")}`
              : "";
          offenders.push(
            `${el.tagName.toLowerCase()}${id}${cls} right=${Math.round(r.right)}`,
          );
        }
      }
      return {
        docScrollWidth: doc.scrollWidth,
        offenders: Array.from(new Set(offenders)).slice(0, 8),
      };
    }, PHONE.width);

    expect(
      report.offenders,
      `elements overflowing the ${PHONE.width}px viewport on ${route}`,
    ).toEqual([]);
    // The document itself must not be scrollable sideways (allow 1px for sub-pixel rounding).
    expect(
      report.docScrollWidth,
      `document scrollWidth on ${route}`,
    ).toBeLessThanOrEqual(PHONE.width + 1);
  });
}
