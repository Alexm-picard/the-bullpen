/**
 * /parks components (SPEC-parks): the strip's fill rule and fixed axis, the field's settled vs
 * draft drawing, and the ink-only factor table. Rendered statically; assertions on visibleText()
 * or on the specific element they are about.
 */
import { MantineProvider } from "@mantine/core";
import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";

import { PARK_ROWS } from "../../data/parks-fixtures";
import { theme } from "../../design/theme";
import { visibleText } from "../../test-support/visible-text";
import { parkLines } from "../games/game-account";

import { ParkFactorsTable } from "./park-factors-table";
import { ParkField } from "./park-field";
import { ParkStrip } from "./park-strip";

const html = (ui: React.ReactElement) =>
  renderToStaticMarkup(<MantineProvider theme={theme}>{ui}</MantineProvider>);

describe("ParkStrip", () => {
  const lines = parkLines(
    { probHrByPark: { COL: 0.95, LAD: 0.99, NYY: 0.01, SD: 0.5, SEA: 0.49 } },
    undefined,
    null,
  );
  const out = html(<ParkStrip lines={lines} />);

  it("fills a dot at or above .50 (the game page's threshold) and rings it below", () => {
    expect(out.match(/pk-strip__dot pk-strip__dot--hr/g)).toHaveLength(3); // LAD COL SD
    expect(out.match(/class="pk-strip__dot"/g)).toHaveLength(2); // SEA NYY
  });

  it("is a fixed 0..1 axis with its ends named, summarised for assistive tech", () => {
    const t = visibleText(out);
    expect(t).toContain("1.0");
    expect(t).toContain(".50");
    expect(t).toContain("NYY");
    expect(t).toContain("LAD");
    expect(out).toContain(
      'aria-label="5 parks on a 0 to 1 scale of estimated home-run probability; 3 at .50 or higher. Lowest Yankee Stadium, highest Dodger Stadium."',
    );
  });
});

describe("ParkField", () => {
  const carry = {
    min: { id: "SF", name: "Oracle Park", ft: 412 },
    max: { id: "COL", name: "Coors Field", ft: 439 },
  };

  it("settled: labels the carry range and the input distance, in ink", () => {
    const out = html(
      <ParkField
        sprayDeg={0}
        inputDistanceFt={400}
        carry={carry}
        settled
        label="L"
      />,
    );
    const t = visibleText(out);
    expect(t).toContain("439 ft, Coors Field");
    expect(t).toContain("412 ft, Oracle Park");
    expect(t).toContain("input, about 400 ft");
    expect(out).toContain('class="pk-field__carry"');
    expect(t).not.toMatch(/foul/i);
  });

  it("draft: hides the scored labels, greys the bracket, and says what to do", () => {
    const out = html(
      <ParkField
        sprayDeg={22}
        inputDistanceFt={400}
        carry={carry}
        settled={false}
        label="L"
      />,
    );
    const t = visibleText(out);
    expect(t).not.toContain("Coors Field");
    expect(t).toContain("release to rescore");
    expect(out).toContain("pk-field__carry pk-field__carry--draft");
  });

  it("draws no bracket when the champion serves no carry", () => {
    const out = html(
      <ParkField
        sprayDeg={0}
        inputDistanceFt={400}
        carry={null}
        settled
        label="L"
      />,
    );
    expect(out).not.toContain("pk-field__carry");
  });
});

describe("ParkFactorsTable", () => {
  const out = html(<ParkFactorsTable rows={PARK_ROWS} />);

  it("prints all 30 fixture parks, highest HR factor first, every value in ink (no tints)", () => {
    const t = visibleText(out);
    for (const r of PARK_ROWS) expect(t).toContain(r.parkName);
    expect(t.indexOf("Coors Field")).toBeLessThan(t.indexOf("Petco Park"));
    // The only inline styles are the tick's geometry; no cell carries a colour.
    const styles = [...out.matchAll(/ style="([^"]*)"/g)].map((m) => m[1]!);
    expect(styles.length).toBeGreaterThan(0);
    for (const st of styles) expect(st).toMatch(/^left:[\d.]+%;width:[\d.]+%$/);
  });

  it("says what the tick means in its caption", () => {
    expect(visibleText(out)).toContain(
      "The tick runs right of centre above average",
    );
  });
});
