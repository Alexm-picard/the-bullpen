import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";

import type { ArsenalPitch } from "../../api/players";

import { PitcherArsenalCard } from "./pitcher-arsenal-card";

describe("PitcherArsenalCard", () => {
  const arsenal: ArsenalPitch[] = [
    {
      pitchType: "FF",
      count: 1200,
      usagePct: 0.55,
      veloMinMph: 95.1,
      veloAvgMph: 97.8,
      veloMaxMph: 100.3,
    },
    {
      pitchType: "SL",
      count: 600,
      usagePct: 0.27,
      veloMinMph: 84.0,
      veloAvgMph: 86.5,
      veloMaxMph: 89.1,
    },
  ];

  it("renders each pitch type with its usage and the velocity range (min-max + avg)", () => {
    const html = renderToStaticMarkup(<PitcherArsenalCard pitches={arsenal} />);
    expect(html).toContain("FF");
    expect(html).toContain("55%");
    expect(html).toContain("95.1"); // range min
    expect(html).toContain("100.3"); // range max
    expect(html).toContain("avg 97.8");
    expect(html).toContain("SL");
  });

  it("shows an honest empty state when the pitcher has no tracked pitches", () => {
    const html = renderToStaticMarkup(<PitcherArsenalCard pitches={[]} />);
    expect(html).toContain("No velocity-tracked pitches");
  });
});
